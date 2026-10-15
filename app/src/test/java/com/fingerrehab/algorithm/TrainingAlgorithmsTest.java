package com.fingerrehab.algorithm;

import static org.junit.Assert.*;

import com.fingerrehab.algorithm.TrainingAlgorithms.Point;
import com.fingerrehab.algorithm.TrainingAlgorithms.Trial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** 用可复现输入验证真实算法行为，不依赖手机、随机分数或界面实现。 */
public class TrainingAlgorithmsTest {
  private List<Point> timed(List<Point> guide, long duration) {
    List<Point> equal = Geometry.resample(guide, 160);
    List<Point> result = new ArrayList<>();
    for (int i = 0; i < equal.size(); i++) {
      Point p = equal.get(i);
      result.add(new Point(p.x, p.y, Math.round(duration * i / (double) (equal.size() - 1))));
    }
    return result;
  }

  @Test
  public void resamplingUsesArcLengthRatherThanInputDensity() {
    List<Point> input =
        Arrays.asList(
            new Point(0, 0, 0),
            new Point(.01, 0, 10),
            new Point(.02, 0, 20),
            new Point(1, 0, 1000));
    List<Point> result = Geometry.resample(input, 5);
    assertEquals(5, result.size());
    assertEquals(.25, result.get(1).x, 1e-9);
    assertEquals(.75, result.get(3).x, 1e-9);
    assertEquals(1000, result.get(4).timeMs);
  }

  @Test
  public void normalizationUsesFixedViewportScale() {
    List<Point> a =
        Geometry.normalize(Arrays.asList(new Point(100, 200), new Point(400, 300)), 500, 500);
    List<Point> b =
        Geometry.normalize(Arrays.asList(new Point(200, 400), new Point(800, 600)), 1000, 1000);
    assertEquals(a.get(0).x, b.get(0).x, 1e-9);
    assertEquals(a.get(1).y, b.get(1).y, 1e-9);
    assertEquals(.2, a.get(0).x, 1e-9);
    assertTrue(Geometry.normalize(a, 0, 500).isEmpty());
  }

  @Test
  public void emptyAndDegenerateGeometryAreFinite() {
    assertTrue(Geometry.resample(null, 128).isEmpty());
    assertTrue(Geometry.resample(Collections.emptyList(), 128).isEmpty());
    assertEquals(1, Geometry.resample(Arrays.asList(new Point(0, 0), new Point(0, 0)), 128).size());
    assertEquals(
        1, Geometry.pointToSegment(new Point(1, 0), new Point(0, 0), new Point(0, 0)), 1e-9);
  }

  @Test
  public void breaksDoNotCreateImaginaryCoverageOrLength() {
    List<Point> strokes =
        Arrays.asList(
            new Point(0, 0, 0),
            new Point(.1, 0, 100),
            new Point(.9, 0, 1000, true),
            new Point(1, 0, 1100));
    assertEquals(.2, Geometry.pathLength(strokes), 1e-9);
    assertEquals(.4, Geometry.distanceToPath(new Point(.5, 0), strokes), 1e-9);
    List<Point> sampled = Geometry.resample(strokes, 9);
    assertEquals(0, sampled.get(0).x, 1e-9);
    assertEquals(.1, sampled.get(4).x, 1e-9);
    assertTrue(sampled.get(5).breakBefore);
    assertEquals(.4, Geometry.distanceToPath(new Point(.5, 0), sampled), 1e-9);
  }

  @Test
  public void eachPerfectTargetCanReceiveHighScore() {
    for (String mode : Arrays.asList("line", "circle", "spiral")) {
      for (int difficulty = 0; difficulty <= 2; difficulty++) {
        List<Point> target = TrainingAlgorithms.tracePath(mode, difficulty);
        TrainingAlgorithms.TraceResult r =
            TrainingAlgorithms.analyzeTrace(timed(target, 9000), target, difficulty, true);
        assertTrue(mode + difficulty + ": " + r.reason, r.valid);
        assertTrue(r.score >= 90);
        assertTrue(r.meanDeviation < .001);
        assertEquals(1, r.coverage, 1e-9);
        assertTrue(r.stability > .9);
      }
    }
  }

  @Test
  public void straightShortcutDoesNotCompleteCircleOrSpiral() {
    for (String mode : Arrays.asList("circle", "spiral")) {
      List<Point> target = TrainingAlgorithms.tracePath(mode, 1);
      Point a = target.get(0), b = target.get(target.size() - 1);
      List<Point> shortcut = timed(Arrays.asList(a, b), 2000);
      TrainingAlgorithms.TraceResult r = TrainingAlgorithms.analyzeTrace(shortcut, target, 1, true);
      assertFalse(r.valid);
      assertEquals(0, r.score, 0);
      assertTrue(r.coverage < .7);
    }
  }

  @Test
  public void emptyNoTouchShortAndZeroTimePathsAreInvalid() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 0);
    assertFalse(TrainingAlgorithms.analyzeTrace(null, target, 0, true).valid);
    assertFalse(TrainingAlgorithms.analyzeTrace(Collections.emptyList(), target, 0, true).valid);
    assertFalse(
        TrainingAlgorithms.analyzeTrace(
                Collections.singletonList(new Point(.5, .5)), target, 0, true)
            .valid);
    assertFalse(TrainingAlgorithms.analyzeTrace(timed(target, 0), target, 0, true).valid);
    List<Point> shortPath = timed(target.subList(0, 20), 1000);
    assertFalse(TrainingAlgorithms.analyzeTrace(shortPath, target, 0, true).valid);
  }

  @Test
  public void stationaryTouchReportsRealDeviationWithoutInventedStability() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 0);
    TrainingAlgorithms.TraceResult r =
        TrainingAlgorithms.analyzeTrace(
            Arrays.asList(new Point(.1, .1, 0), new Point(.1, .1, 1500)), target, 0, true);
    assertFalse(r.valid);
    assertTrue(r.deviationAvailable);
    assertTrue(r.coverageAvailable);
    assertTrue(r.accuracyAvailable);
    assertFalse(r.stabilityAvailable);
    assertTrue(r.meanDeviation > .1);
    assertEquals(0, r.coverage, 0);
    TrainingAlgorithms.TraceResult empty = TrainingAlgorithms.analyzeTrace(null, target, 0, true);
    assertFalse(empty.deviationAvailable);
    assertFalse(empty.coverageAvailable);
    assertFalse(empty.stabilityAvailable);
  }

  @Test
  public void tracingMustHaveCorrectEndpointsAndCompletionState() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 0);
    List<Point> actual = timed(target, 3000);
    assertFalse(TrainingAlgorithms.analyzeTrace(actual, target, 0, false).valid);
    assertEquals(0, TrainingAlgorithms.analyzeTrace(actual, target, 0, false).score, 0);
    Collections.reverse(actual);
    assertFalse(TrainingAlgorithms.analyzeTrace(actual, target, 0, true).valid);
  }

  @Test
  public void wrongCircleDirectionDoesNotPassOrderCheck() {
    List<Point> target = TrainingAlgorithms.tracePath("circle", 1);
    List<Point> reversed = new ArrayList<>(target);
    Collections.reverse(reversed);
    TrainingAlgorithms.TraceResult result =
        TrainingAlgorithms.analyzeTrace(timed(reversed, 5000), target, 1, true);
    assertEquals(1, result.coverage, .001);
    assertFalse(result.valid);
  }

  @Test
  public void repeatedLoopsAndRandomScribblesAreInvalid() {
    List<Point> target = TrainingAlgorithms.tracePath("circle", 1);
    List<Point> loops = new ArrayList<>();
    loops.addAll(target);
    loops.addAll(target);
    loops.addAll(target);
    assertFalse(TrainingAlgorithms.analyzeTrace(timed(loops, 15000), target, 1, true).valid);
    List<Point> scribble = new ArrayList<>();
    for (int i = 0; i < 100; i++)
      scribble.add(new Point(i % 2 == 0 ? .1 : .9, ((i * 37) % 100) / 100d, i * 100));
    assertFalse(TrainingAlgorithms.analyzeTrace(scribble, target, 1, true).valid);
  }

  @Test
  public void disconnectedStrokesCannotCoverMissingPath() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 1);
    List<Point> actual = timed(target, 4000);
    List<Point> disconnected = new ArrayList<>(actual.subList(0, 50));
    Point after = actual.get(110);
    disconnected.add(new Point(after.x, after.y, after.timeMs, true));
    disconnected.addAll(actual.subList(111, actual.size()));
    assertFalse(TrainingAlgorithms.analyzeTrace(disconnected, target, 1, true).valid);
  }

  @Test
  public void jitterLowersStabilityAndDeviationAccuracy() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 0);
    List<Point> perfect = timed(target, 5000), noisy = new ArrayList<>();
    for (int i = 0; i < perfect.size(); i++) {
      Point p = perfect.get(i);
      noisy.add(
          new Point(
              p.x,
              p.y + (i == 0 || i == perfect.size() - 1 ? 0 : (i % 2 == 0 ? .0035 : -.0035)),
              p.timeMs));
    }
    TrainingAlgorithms.TraceResult a = TrainingAlgorithms.analyzeTrace(perfect, target, 0, true);
    TrainingAlgorithms.TraceResult b = TrainingAlgorithms.analyzeTrace(noisy, target, 0, true);
    assertTrue(b.valid);
    assertTrue(b.stability < a.stability);
    assertTrue(b.meanDeviation > a.meanDeviation);
    assertTrue(b.score < a.score);
  }

  @Test
  public void invalidCoordinatesAndBackwardClockFailSafely() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 0);
    List<Point> actual = timed(target, 4000);
    actual.add(10, new Point(Double.NaN, .5, 200));
    assertFalse(TrainingAlgorithms.analyzeTrace(actual, target, 0, true).valid);
    actual = timed(target, 4000);
    Point p = actual.get(20);
    actual.set(20, new Point(p.x, p.y, -1));
    assertFalse(TrainingAlgorithms.analyzeTrace(actual, target, 0, true).valid);
  }

  @Test
  public void extremeCoordinatesCannotOverflowMetricsOrGeometry() {
    List<Point> target = TrainingAlgorithms.tracePath("line", 0);
    TrainingAlgorithms.TraceResult result =
        TrainingAlgorithms.analyzeTrace(
            Arrays.asList(new Point(1e308, 1e308, 0), new Point(-1e308, -1e308, 1000)),
            target,
            0,
            true);
    assertFalse(result.valid);
    assertFalse(result.deviationAvailable);
    assertTrue(Double.isFinite(result.score));
    assertTrue(
        Geometry.resample(Arrays.asList(new Point(1e308, 0), new Point(-1e308, 0)), 10).isEmpty());
    assertTrue(
        Geometry.normalize(Collections.singletonList(new Point(1e308, 0)), 1e-308, 1e-308)
            .isEmpty());
  }

  @Test
  public void reactionAccuracyExcludesTimeoutsAndUsesMonotonicNanos() {
    List<Trial> trials =
        Arrays.asList(
            new Trial(0, 200_000_000, false, false),
            new Trial(0, 400_000_000, true, false),
            new Trial(1_000_000_000, 1_300_000_000, true, false),
            new Trial(2_000_000_000L, 0, false, true));
    TrainingAlgorithms.ReactionResult r = TrainingAlgorithms.analyzeReaction(trials, 3, 0, true);
    assertTrue(r.reason, r.valid);
    assertEquals(2, r.successes);
    assertEquals(1, r.errors);
    assertEquals(1, r.timeouts);
    assertEquals(2d / 3, r.accuracy, 1e-9);
    assertEquals(350, r.averageReactionMs, 1e-9);
    assertEquals(300, r.fastestReactionMs, 1e-9);
    assertTrue(r.score >= 0 && r.score <= 100);
  }

  @Test
  public void noReactionOrOnlyTimeoutsHaveNoMeasuredReactionTime() {
    TrainingAlgorithms.ReactionResult empty = TrainingAlgorithms.analyzeReaction(null, 1, 0, true);
    assertFalse(empty.valid);
    assertNull(empty.averageReactionMs);
    assertNull(empty.fastestReactionMs);
    TrainingAlgorithms.ReactionResult timeout =
        TrainingAlgorithms.analyzeReaction(
            Collections.singletonList(new Trial(0, 0, false, true)), 1, 0, true);
    assertFalse(timeout.valid);
    assertEquals(1, timeout.timeouts);
    assertEquals(0, timeout.errors);
    assertEquals(0, timeout.accuracy, 0);
    assertNull(timeout.averageReactionMs);
  }

  @Test
  public void impossibleFastNegativeLateAndDuplicateReactionsAreInvalid() {
    for (long response : new long[] {0, 10_000_000, -10_000_000, 3_000_000_000L}) {
      assertFalse(
          TrainingAlgorithms.analyzeReaction(
                  Collections.singletonList(new Trial(0, response, true, false)), 1, 0, true)
              .valid);
    }
    List<Trial> duplicate =
        Arrays.asList(
            new Trial(0, 300_000_000, true, false), new Trial(0, 400_000_000, true, false));
    assertFalse(TrainingAlgorithms.analyzeReaction(duplicate, 1, 0, true).valid);
  }

  @Test
  public void unfinishedReactionCannotEarnScore() {
    TrainingAlgorithms.ReactionResult result =
        TrainingAlgorithms.analyzeReaction(
            Collections.singletonList(new Trial(0, 400_000_000, true, false)), 1, 0, false);
    assertFalse(result.valid);
    assertEquals(0, result.score, 0);
  }

  @Test
  public void reactionRejectsBackwardStimuliAndLateErrors() {
    List<Trial> backwards =
        Arrays.asList(
            new Trial(100_000_000L, 300_000_000L, true, false),
            new Trial(0, 400_000_000L, true, false));
    assertFalse(TrainingAlgorithms.analyzeReaction(backwards, 2, 0, true).valid);
    List<Trial> lateError =
        Arrays.asList(
            new Trial(0, 3_000_000_000L, false, false),
            new Trial(4_000_000_000L, 4_300_000_000L, true, false),
            new Trial(0, 0, false, true));
    assertFalse(TrainingAlgorithms.analyzeReaction(lateError, 2, 0, true).valid);
  }

  @Test
  public void regularCoordinationHasCorrectIntervalsAndHighScore() {
    List<Long> times = Arrays.asList(300_000_000L, 600_000_000L, 900_000_000L, 1_200_000_000L);
    TrainingAlgorithms.CoordinationResult r =
        TrainingAlgorithms.analyzeCoordination(times, 0, 4, 0, "tapping", 0, 1_200_000_000L, true);
    assertTrue(r.reason, r.valid);
    assertEquals(300, r.averageIntervalMs, 1e-9);
    assertEquals(1, r.stability, 1e-9);
    assertEquals(100, r.score, 0);
  }

  @Test
  public void coordinationCountsErrorsAndIrregularRhythm() {
    List<Long> times = Arrays.asList(100_000_000L, 200_000_000L, 900_000_000L, 1_200_000_000L);
    TrainingAlgorithms.CoordinationResult r =
        TrainingAlgorithms.analyzeCoordination(times, 2, 4, 0, "sequence", 0, 1_200_000_000L, true);
    assertTrue(r.valid);
    assertEquals(4d / 6, r.accuracy, 1e-9);
    assertTrue(r.stability < 1);
    assertTrue(r.score < 100);
  }

  @Test
  public void emptyZeroTimeHighFrequencyAndIncompleteCoordinationAreInvalid() {
    TrainingAlgorithms.CoordinationResult empty =
        TrainingAlgorithms.analyzeCoordination(null, 0, 4, 0, "tapping", 0, 0, true);
    assertFalse(empty.valid);
    assertNull(empty.averageIntervalMs);
    assertNull(empty.stability);
    assertFalse(
        TrainingAlgorithms.analyzeCoordination(
                Arrays.asList(0L, 0L), 0, 2, 0, "tapping", 0, 0, true)
            .valid);
    assertFalse(
        TrainingAlgorithms.analyzeCoordination(
                Arrays.asList(1_000_000L, 2_000_000L), 0, 2, 0, "tapping", 0, 500_000_000L, true)
            .valid);
    assertFalse(
        TrainingAlgorithms.analyzeCoordination(
                Arrays.asList(100_000_000L, 200_000_000L),
                0,
                3,
                0,
                "tapping",
                0,
                300_000_000L,
                true)
            .valid);
    assertFalse(
        TrainingAlgorithms.analyzeCoordination(
                Arrays.asList(100_000_000L, 200_000_000L),
                0,
                2,
                0,
                "tapping",
                0,
                300_000_000L,
                false)
            .valid);
  }

  @Test
  public void coordinationRejectsBackwardOrOutOfSessionTimestamps() {
    assertFalse(
        TrainingAlgorithms.analyzeCoordination(
                Arrays.asList(300_000_000L, 200_000_000L),
                0,
                2,
                0,
                "tapping",
                0,
                400_000_000L,
                true)
            .valid);
    assertFalse(
        TrainingAlgorithms.analyzeCoordination(
                Arrays.asList(100_000_000L, 500_000_000L),
                0,
                2,
                0,
                "tapping",
                0,
                400_000_000L,
                true)
            .valid);
  }

  @Test
  public void difficultyConfigurationIsCentralizedAndProgressive() {
    assertTrue(TrainingAlgorithms.config(0).tolerance > TrainingAlgorithms.config(2).tolerance);
    assertTrue(
        TrainingAlgorithms.config(0).targetRadius > TrainingAlgorithms.config(2).targetRadius);
    assertTrue(
        TrainingAlgorithms.config(0).reactionTimeoutMs
            > TrainingAlgorithms.config(2).reactionTimeoutMs);
    assertTrue(
        TrainingAlgorithms.config(0).tappingTargets < TrainingAlgorithms.config(2).tappingTargets);
    assertEquals(0, TrainingAlgorithms.config(-1).difficulty);
    assertEquals(2, TrainingAlgorithms.config(7).difficulty);
  }
}
