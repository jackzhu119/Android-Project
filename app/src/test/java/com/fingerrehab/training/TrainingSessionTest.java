package com.fingerrehab.training;

import static org.junit.Assert.*;

import com.fingerrehab.algorithm.TrainingAlgorithms;
import com.fingerrehab.model.TrainingRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 用真实坐标与单调时间验证六种任务、异常事件和可恢复状态。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TrainingSessionTest {
  private TrainingSession session(String type, String subtype, int difficulty) {
    TrainingSession value = new TrainingSession(type, subtype, difficulty, 100000);
    value.resume(1000);
    return value;
  }

  @Test
  public void allThreeGuidesCanBeCompletedWithRealSamples() throws Exception {
    for (String subtype : new String[] {"line", "circle", "spiral"}) {
      TrainingSession s = session("trace", subtype, 1);
      List<TrainingAlgorithms.Point> guide = s.guide();
      for (int i = 0; i < guide.size(); i++) {
        TrainingAlgorithms.Point point = guide.get(i);
        s.addSample(point.x, point.y, 1000 + i * 25);
      }
      assertTrue("应能完成 " + subtype, s.endStroke(1000 + (guide.size() - 1) * 25));
      TrainingRecord record = s.buildRecord(null, 200000);
      assertTrue(subtype, record.valid);
      assertEquals("completed", record.status);
      assertTrue(record.score > 60);
      assertTrue(record.coverage > .95);
      assertTrue(record.meanDeviation < .005);
      assertEquals(
          guide.size(), new JSONObject(record.detailsJson).getJSONArray("samples").length());
    }
  }

  @Test
  public void arbitraryShortCutDoesNotPassTraceValidation() {
    TrainingSession s = session("trace", "circle", 0);
    for (int i = 0; i < 30; i++) s.addSample(.15 + .7 * i / 29, .5, 1000 + i * 40);
    assertFalse(s.endStroke(2200));
    s.finish(2200);
    TrainingRecord record = s.buildRecord(null, 101200);
    assertFalse(record.valid);
    assertEquals(0, record.score, 0);
    assertEquals("incomplete", record.status);
  }

  @Test
  public void absentMeasurementsRemainNullAndStationaryErrorIsReal() {
    TrainingSession s = session("trace", "line", 0);
    s.finish(1100);
    TrainingRecord empty = s.buildRecord(null, 100100);
    assertNull(empty.meanDeviation);
    assertNull(empty.coverage);
    assertNull(empty.stability);
    assertNull(empty.accuracy);
    TrainingSession stationary = session("trace", "line", 0);
    stationary.addSample(.9, .9, 1000);
    stationary.addSample(.9, .9, 1800);
    stationary.finish(1800);
    TrainingRecord record = stationary.buildRecord(null, 100800);
    assertNotNull(record.meanDeviation);
    assertTrue(record.meanDeviation > .2);
    assertNotNull(record.coverage);
    assertEquals(0, record.coverage, 0);
    assertNull(record.stability);
    assertFalse(record.valid);
  }

  @Test
  public void randomReactionMeasuresErrorsAndEverySuccessfulRound() throws Exception {
    TrainingSession s = session("reaction", "random", 0);
    s.tap(0, 0, 1100);
    long now = 1100;
    for (int i = 0; i < s.expectedTargets(); i++) {
      now += 350;
      s.tap(s.targetX, s.targetY, now);
    }
    assertTrue(s.finished);
    TrainingRecord record = s.buildRecord(null, 200000);
    assertTrue(record.valid);
    assertEquals(s.expectedTargets(), record.successes);
    assertEquals(1, record.errors);
    assertEquals(0, record.timeouts);
    assertEquals(
        s.expectedTargets() / (double) (s.expectedTargets() + 1), record.accuracy, .000001);
    assertTrue(record.averageReactionMs >= 350);
    assertEquals(350, record.fastestReactionMs, .000001);
    assertEquals(
        s.expectedTargets() + 1,
        new JSONObject(record.detailsJson).getJSONArray("events").length());
  }

  @Test
  public void anticipationIsAnErrorWithoutAdvancingTheRound() {
    TrainingSession s = session("reaction", "random", 0);
    s.tap(s.targetX, s.targetY, 1020);
    assertEquals(0, s.successes);
    assertEquals(1, s.errors);
    assertEquals(0, s.round);
    assertEquals("anticipation", s.taps.get(0).outcome);
    s.tap(s.targetX, s.targetY, 1300);
    assertEquals(1, s.successes);
    assertEquals(1, s.round);
  }

  @Test
  public void noTouchTimeoutIsNotAnIncorrectClickAndHasNoReactionMeasurement() {
    TrainingSession s = session("reaction", "random", 2);
    long now = 1000;
    for (int i = 0; i < s.expectedTargets(); i++) {
      now += s.config().reactionTimeoutMs;
      s.tick(now);
    }
    assertTrue(s.finished);
    assertFalse(s.running);
    TrainingRecord record = s.buildRecord(null, 200000);
    assertEquals(s.expectedTargets(), record.timeouts);
    assertEquals(0, record.errors);
    assertNull(record.accuracy);
    assertNull(record.averageReactionMs);
    assertNull(record.fastestReactionMs);
    assertFalse(record.valid);
    assertEquals("incomplete", record.status);
    s.tick(now + 100000);
    assertEquals(s.expectedTargets(), s.timeouts);
  }

  @Test
  public void touchAtExpiredTargetDoesNotPenalizeTheNotYetDisplayedNextTarget() {
    TrainingSession s = session("reaction", "random", 0);
    double oldX = s.targetX, oldY = s.targetY;
    s.tap(oldX, oldY, 3501);
    assertEquals(1, s.timeouts);
    assertEquals(0, s.errors);
    assertEquals(1, s.round);
    assertEquals(1, s.taps.size());
    assertEquals("timeout", s.taps.get(0).outcome);
  }

  @Test
  public void extremeClickRateEndsExplicitlyBeforeRecordBecomesUnbounded() throws Exception {
    TrainingSession s = session("coordination", "tapping", 0);
    for (int i = 0; i < 5000 && !s.finished; i++) s.tap(0, 0, 1001 + i);
    assertTrue(s.finished);
    assertTrue(s.eventLimitReached);
    assertFalse(s.running);
    assertEquals(4096, s.taps.size());
    assertEquals(4096, s.errors);
    TrainingRecord record = s.buildRecord(null, 200000);
    assertEquals("incomplete", record.status);
    assertFalse(record.valid);
    JSONObject details = new JSONObject(record.detailsJson);
    assertTrue(details.getBoolean("eventLimitReached"));
    assertEquals(4096, details.getJSONArray("events").length());
    assertTrue(record.detailsJson.length() < 1500000);
  }

  @Test
  public void sequenceDifficultModeHasTenDistinctTargetsAndFinishes() {
    TrainingSession s = session("coordination", "sequence", 2);
    assertEquals(10, s.sequence.size());
    for (int a = 0; a < s.sequence.size(); a++)
      for (int b = a + 1; b < s.sequence.size(); b++) {
        TrainingSession.Target first = s.sequence.get(a), second = s.sequence.get(b);
        assertTrue(Math.hypot(first.x - second.x, first.y - second.y) > 2 * s.targetRadius());
      }
    TrainingSession.Target wrong = s.sequence.get(1);
    s.tap(wrong.x, wrong.y, 1300);
    assertEquals(0, s.successes);
    assertEquals(1, s.errors);
    long now = 1300;
    for (TrainingSession.Target target : s.sequence) {
      now += 500;
      s.tap(target.x, target.y, now);
    }
    assertTrue(s.finished);
    TrainingRecord record = s.buildRecord(null, 200000);
    assertTrue(record.valid);
    assertEquals(10, record.successes);
    assertEquals(10d / 11, record.accuracy, .000001);
    assertEquals(500, record.averageIntervalMs, .000001);
  }

  @Test
  public void tappingRejectsOutsideAndTooFastTouchesThenCompletes() {
    TrainingSession s = session("coordination", "tapping", 0);
    s.tap(0, 0, 1100);
    s.tap(.5, .5, 1300);
    s.tap(.5, .5, 1310);
    assertEquals(1, s.successes);
    assertEquals(2, s.errors);
    long now = 1300;
    while (!s.finished) {
      now += 400;
      s.tap(.5, .5, now);
    }
    TrainingRecord record = s.buildRecord(null, 200000);
    assertTrue(record.valid);
    assertEquals(s.expectedTargets(), record.successes);
    assertEquals(400, record.averageIntervalMs, .000001);
    assertEquals(1, record.stability, .000001);
  }

  @Test
  public void pauseDoesNotConsumeTimeOrCurrentTargetDeadline() {
    TrainingSession s = session("reaction", "random", 0);
    s.pause(1200);
    assertEquals(200, s.elapsed(90000));
    assertFalse(s.tick(90000));
    assertEquals(0, s.timeouts);
    assertEquals(s.config().reactionTimeoutMs - 200, s.remaining(90000));
    s.resume(90000);
    s.tap(s.targetX, s.targetY, 90100);
    assertEquals(1, s.successes);
    s.finish(90100);
    TrainingRecord record = s.buildRecord("aborted", 999999);
    assertEquals(300, record.durationMs);
    assertEquals("aborted", record.status);
    assertFalse(record.valid);
  }

  @Test
  public void traceDeadlineCannotCompleteEvenWhenEarlierSamplesMatchTheGuide() throws Exception {
    for (int trigger = 0; trigger < 5; trigger++) {
      TrainingSession s = session("trace", "line", 0);
      for (int i = 0; i < s.guide().size(); i++) {
        TrainingAlgorithms.Point point = s.guide().get(i);
        s.addSample(point.x, point.y, 1000 + i * 4);
      }
      int retainedSamples = s.samples.size();
      long deadline = 1000 + s.timeLimit();
      if (trigger == 0) assertTrue(s.tick(deadline));
      else if (trigger == 1) assertFalse(s.endStroke(deadline));
      else if (trigger == 2) s.addSample(.86, .28, deadline);
      else if (trigger == 3) s.finish(deadline);
      else {
        s.pause(deadline);
        s.finish(deadline + 10000);
      }
      assertTrue(s.finished);
      assertFalse(s.running);
      assertTrue(s.timeLimitReached);
      assertEquals(retainedSamples, s.samples.size());
      TrainingRecord record = s.buildRecord(null, 200000);
      assertFalse(record.valid);
      assertEquals("incomplete", record.status);
      assertEquals(0, record.score, 0);
      assertTrue(record.coverage > .95);
      assertTrue(record.meanDeviation < .005);
      assertTrue(new JSONObject(record.detailsJson).getBoolean("timeLimitReached"));
    }
  }

  @Test
  public void traceFirstSampleAtDeadlineCannotStartALateValidPath() {
    TrainingSession s = session("trace", "line", 0);
    long deadline = 1000 + s.timeLimit();
    for (int i = 0; i < s.guide().size(); i++) {
      TrainingAlgorithms.Point point = s.guide().get(i);
      s.addSample(point.x, point.y, deadline + i * 4);
    }
    assertFalse(s.endStroke(deadline + 1024));
    assertTrue(s.finished);
    assertTrue(s.samples.isEmpty());
    TrainingRecord record = s.buildRecord(null, 200000);
    assertFalse(record.valid);
    assertNull(record.meanDeviation);
    assertNull(record.coverage);
  }

  @Test
  public void historicalTouchBeforeResumeDoesNotBecomeANewSuccessfulTap() {
    for (String type : new String[] {"reaction", "coordination"}) {
      TrainingSession s = session(type, "tapping", 0);
      s.pause(1300);
      s.resume(90000);
      s.tap(s.targetX, s.targetY, 1250);
      assertEquals(0, s.successes);
      assertEquals(0, s.errors);
      assertTrue(s.taps.isEmpty());
      s.tap(s.targetX, s.targetY, 90100);
      assertEquals(1, s.successes);
      assertEquals(400, s.taps.get(0).timeMs);
    }
  }

  @Test
  public void historicalTouchBeforeCurrentTargetCannotCreateANegativeReaction() {
    TrainingSession s = session("reaction", "random", 0);
    s.tick(3500);
    s.tap(s.targetX, s.targetY, 3499);
    assertEquals(1, s.timeouts);
    assertEquals(0, s.errors);
    assertEquals(1, s.taps.size());
    long now = 3500;
    while (!s.finished) {
      now += 300;
      s.tap(s.targetX, s.targetY, now);
    }
    TrainingRecord record = s.buildRecord(null, 200000);
    assertTrue(record.valid);
    assertEquals(s.expectedTargets() - 1, record.successes);
    assertEquals(300, record.averageReactionMs, .000001);
  }

  @Test
  public void historicalAndDuplicateTapEventsCannotInvalidateLaterValidReactionTrials() {
    TrainingSession s = session("reaction", "random", 0);
    s.tap(0, 0, 1100);
    s.tap(0, 0, 1100);
    s.tap(0, 0, 1099);
    assertEquals(1, s.errors);
    assertEquals(1, s.taps.size());
    long now = 1100;
    while (!s.finished) {
      now += 300;
      s.tap(s.targetX, s.targetY, now);
    }
    assertTrue(s.buildRecord(null, 200000).valid);
  }

  @Test
  public void historicalTraceSampleBeforeResumeIsDiscardedWithoutJoiningTheStroke() {
    TrainingSession s = session("trace", "line", 0);
    s.addSample(.14, .72, 1000);
    s.pause(1100);
    s.resume(90000);
    s.addSample(.2, .7, 1050);
    assertEquals(1, s.samples.size());
    assertEquals(1, s.ignoredSamples);
    s.addSample(.21, .69, 90100);
    assertEquals(2, s.samples.size());
    assertEquals(200, s.samples.get(1).timeMs);
    assertTrue(s.samples.get(1).breakBefore);
    assertFalse(s.endStroke(89999));
    assertTrue(s.running);
  }

  @Test
  public void serializedPausedSessionRetainsTargetsAndMeasurements() throws Exception {
    TrainingSession original = session("coordination", "sequence", 1);
    original.ownerUserId = 42;
    original.pendingStatus = "aborted";
    original.pendingAction = "restart";
    TrainingSession.Target target = original.sequence.get(0);
    original.tap(target.x, target.y, 1300);
    original.pause(1400);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(original);
    }
    TrainingSession restored;
    try (ObjectInputStream input =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (TrainingSession) input.readObject();
    }
    assertEquals(original.sessionId, restored.sessionId);
    assertEquals(42, restored.ownerUserId);
    assertEquals("aborted", restored.pendingStatus);
    assertEquals("restart", restored.pendingAction);
    assertEquals(1, restored.successes);
    assertEquals(original.sequence.get(1).x, restored.sequence.get(1).x, 0);
    restored.resume(90000);
    TrainingSession.Target next = restored.sequence.get(1);
    restored.tap(next.x, next.y, 90500);
    assertEquals(2, restored.successes);
    assertEquals(900, restored.elapsed(90500));
    restored.finish(90500);
    assertEquals(
        2,
        new JSONObject(restored.buildRecord("aborted", 200000).detailsJson)
            .getJSONArray("events")
            .length());
  }

  @Test
  public void cancellationAndOutsideSamplesCannotConnectMissingTraceSegments() {
    TrainingSession s = session("trace", "line", 0);
    s.addSample(.14, .72, 1000);
    s.addSample(.15, .71, 1100);
    s.cancelGesture();
    s.addSample(.85, .29, 1200);
    s.addSample(-.2, .5, 1300);
    s.addSample(.86, .28, 1400);
    assertEquals(1, s.cancelledGestures);
    assertEquals(1, s.ignoredSamples);
    assertTrue(s.samples.get(2).breakBefore);
    assertTrue(s.samples.get(3).breakBefore);
    assertFalse(s.endStroke(1400));
  }

  @Test
  public void generatedRandomTargetsAlwaysFitTheNormalizedSquare() {
    for (int level = 0; level < 3; level++) {
      TrainingSession s = session("reaction", "random", level);
      long now = 1000;
      while (!s.finished) {
        assertTrue(s.targetX - s.targetRadius() >= 0);
        assertTrue(s.targetX + s.targetRadius() <= 1);
        assertTrue(s.targetY - s.targetRadius() >= 0);
        assertTrue(s.targetY + s.targetRadius() <= 1);
        now += 300;
        s.tap(s.targetX, s.targetY, now);
      }
    }
  }
}
