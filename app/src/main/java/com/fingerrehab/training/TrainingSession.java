package com.fingerrehab.training;

import com.fingerrehab.algorithm.TrainingAlgorithms;
import com.fingerrehab.model.TrainingRecord;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 训练状态与真实采样；所有用时均由调用者传入的单调时钟计算，暂停不计时。 */
public final class TrainingSession implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final int MAX_SAMPLES = 16000;
  private static final int MAX_EVENTS = 4096;
  public final String sessionId = UUID.randomUUID().toString();
  public final String type, subtype;
  public final int difficulty;
  public final long startedAt;
  public boolean running, started, finished, saved;
  public boolean eventLimitReached, timeLimitReached;
  public long recordId, ownerUserId;
  public String pendingStatus, pendingAction;
  private long accumulatedMs, resumedAt, targetShownMs, lastAcceptedTapMs = -100000;
  private long lastTapEventMs = -1;
  public int successes, errors, timeouts, cancelledGestures, ignoredSamples, observedSamples;
  public int round;
  public double targetX = .5, targetY = .5;
  public String feedback = "准备好后，点击开始训练";
  public boolean feedbackGood = true;
  public double feedbackX = .5, feedbackY = .5;
  public long feedbackAtMs = -10000;
  public final ArrayList<Sample> samples = new ArrayList<>();
  public final ArrayList<Tap> taps = new ArrayList<>();
  public final ArrayList<Target> sequence = new ArrayList<>();
  private final ArrayList<Long> successfulTapTimesMs = new ArrayList<>();
  private boolean breakBefore = true;
  private final Random random = new Random();
  private transient List<TrainingAlgorithms.Point> guide;
  private transient boolean[] covered;
  private int coveredCount;
  private double deviationSum;
  private long deviationCount;

  public TrainingSession(
      String requestedType, String requestedSubtype, int difficulty, long wallTime) {
    this.difficulty = Math.max(0, Math.min(2, difficulty));
    if ("trace".equals(requestedType)) {
      type = "trace";
      subtype =
          "circle".equals(requestedSubtype) || "spiral".equals(requestedSubtype)
              ? requestedSubtype
              : "line";
    } else if ("coordination".equals(requestedType)) {
      type = "coordination";
      subtype = "sequence".equals(requestedSubtype) ? "sequence" : "tapping";
    } else {
      type = "reaction";
      subtype = "random";
    }
    startedAt = wallTime;
    if (isTrace()) ensureGuide();
    else if ("sequence".equals(subtype)) createSequence();
    else if ("random".equals(subtype)) nextRandomTarget();
  }

  public TrainingAlgorithms.Config config() {
    return TrainingAlgorithms.config(difficulty);
  }

  public boolean isTrace() {
    return "trace".equals(type);
  }

  public long elapsed(long nowUptimeMs) {
    return accumulatedMs + (running ? Math.max(0, nowUptimeMs - resumedAt) : 0);
  }

  public void resume(long nowUptimeMs) {
    if (finished || running) return;
    started = true;
    resumedAt = nowUptimeMs;
    running = true;
    feedback =
        isTrace()
            ? "从绿色起点出发，沿蓝线描绘"
            : "sequence".equals(subtype)
                ? "请按数字顺序点击：" + (successes + 1)
                : "random".equals(subtype) ? "点击青色目标" : "在青色圆内连续点击，保持自然节奏";
  }

  public void pause(long nowUptimeMs) {
    if (!running) return;
    accumulatedMs = elapsed(nowUptimeMs);
    running = false;
    breakBefore = true;
  }

  public long remaining(long nowUptimeMs) {
    long elapsed = elapsed(nowUptimeMs);
    if ("random".equals(subtype))
      return Math.max(0, config().reactionTimeoutMs - (elapsed - targetShownMs));
    return Math.max(0, timeLimit() - elapsed);
  }

  public long timeLimit() {
    return isTrace() ? config().traceTimeLimitMs : config().coordinationTimeLimitMs;
  }

  public int expectedTargets() {
    return "random".equals(subtype)
        ? config().reactionRounds
        : "sequence".equals(subtype) ? config().sequenceTargets : config().tappingTargets;
  }

  /** 主线程定时调用；每轮只有一个超时事件，结束后不再推进。 */
  public boolean tick(long nowUptimeMs) {
    if (!running || finished) return finished;
    if (taps.size() >= MAX_EVENTS) {
      eventLimitReached = true;
      feedback = "触摸事件过多，本次已结束并保留已有记录";
      finish(nowUptimeMs);
      return true;
    }
    long elapsed = elapsed(nowUptimeMs);
    if ("random".equals(subtype)) {
      if (elapsed - targetShownMs >= config().reactionTimeoutMs) {
        taps.add(
            new Tap(elapsed, null, null, targetX, targetY, targetShownMs, false, true, "timeout"));
        timeouts++;
        round++;
        feedback = "本轮超时，放松后继续";
        feedbackGood = false;
        if (round >= expectedTargets()) finish(nowUptimeMs);
        else {
          targetShownMs = elapsed;
          nextRandomTarget();
        }
      }
    } else if (elapsed >= timeLimit()) {
      timeLimitReached = true;
      feedback = "训练时间已到";
      finish(nowUptimeMs);
    }
    return finished;
  }

  public void beginStroke() {
    breakBefore = true;
  }

  public void cancelGesture() {
    if (running) {
      cancelledGestures++;
      breakBefore = true;
      feedback = "触摸已中断，请用单指继续";
    }
  }

  /** 坐标以居中正方形边长归一化；不把越界坐标夹成边界上的虚假轨迹。 */
  public void addSample(double x, double y, long eventUptimeMs) {
    if (!running || finished || !isTrace()) return;
    observedSamples++;
    // MotionEvent 历史可能跨过暂停恢复；旧采样不能被夹到恢复时刻继续计分。
    if (eventUptimeMs < resumedAt || tick(eventUptimeMs)) {
      ignoredSamples++;
      breakBefore = true;
      return;
    }
    if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x > 1 || y > 1) {
      ignoredSamples++;
      breakBefore = true;
      return;
    }
    long time = elapsed(eventUptimeMs);
    if (!samples.isEmpty()) {
      Sample previous = samples.get(samples.size() - 1);
      if (time < previous.timeMs || (!breakBefore && time - previous.timeMs < 2)) {
        ignoredSamples++;
        return;
      }
    }
    if (samples.size() >= MAX_SAMPLES) compressSamples();
    samples.add(new Sample(x, y, time, breakBefore));
    breakBefore = false;
    ensureGuide();
    double nearest = Double.MAX_VALUE;
    double radius2 = config().tolerance * config().tolerance;
    for (int i = 0; i < guide.size(); i++) {
      TrainingAlgorithms.Point p = guide.get(i);
      double d2 = (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y);
      nearest = Math.min(nearest, d2);
      if (d2 <= radius2 && !covered[i]) {
        covered[i] = true;
        coveredCount++;
      }
    }
    deviationSum += Math.sqrt(nearest);
    deviationCount++;
    feedbackGood = nearest <= radius2;
    feedback = feedbackGood ? "沿线很稳，继续向终点移动" : "稍稍靠近蓝色参考线";
  }

  /** 抬手后由完整算法检查覆盖、路径长度及起终点，避免随意画线完成。 */
  public boolean endStroke(long nowUptimeMs) {
    if (!isTrace() || !running || finished || nowUptimeMs < resumedAt) return false;
    if (tick(nowUptimeMs) || samples.size() < 2) return false;
    if (elapsed(nowUptimeMs) < samples.get(samples.size() - 1).timeMs) return false;
    breakBefore = true;
    TrainingAlgorithms.TraceResult result =
        TrainingAlgorithms.analyzeTrace(algorithmSamples(), guide(), difficulty, true);
    if (result.valid && result.coverage >= .80) {
      finish(nowUptimeMs);
      return true;
    }
    feedback = "可继续补画；完成后点击结束查看报告";
    return false;
  }

  public void tap(double x, double y, long eventUptimeMs) {
    if (!running || finished || isTrace()) return;
    if (eventUptimeMs < resumedAt) return;
    long time = elapsed(eventUptimeMs);
    // 先于恢复、已处理点击或当前目标出现的历史事件不属于当前可见目标。
    if (time <= lastTapEventMs || ("random".equals(subtype) && time < targetShownMs)) return;
    lastTapEventMs = time;
    int previousRound = round;
    if (tick(eventUptimeMs)) return;
    // 在旧目标截止之后收到的触摸只触发超时，不能给尚未显示的新目标记错。
    if (round != previousRound) return;
    feedbackX = Double.isFinite(x) ? x : .5;
    feedbackY = Double.isFinite(y) ? y : .5;
    feedbackAtMs = time;
    boolean tooFast = time - lastAcceptedTapMs < config().minTapIntervalMs;
    boolean anticipated =
        "random".equals(subtype) && time - targetShownMs < config().minimumReactionMs;
    double tx = targetX, ty = targetY;
    if ("sequence".equals(subtype) && successes < sequence.size()) {
      tx = sequence.get(successes).x;
      ty = sequence.get(successes).y;
    }
    boolean hit =
        Double.isFinite(x)
            && Double.isFinite(y)
            && Math.hypot(x - tx, y - ty) <= targetRadius()
            && !tooFast
            && !anticipated;
    if (tooFast) feedback = "点击间隔过短，请每次抬起手指后再点";
    else if (anticipated) feedback = "目标刚出现，请看准位置后再点击";
    else if (hit) feedback = "sequence".equals(subtype) ? "正确！继续点击下一个数字" : "点击成功";
    else feedback = "sequence".equals(subtype) ? "顺序或位置不对，请点击 " + (successes + 1) : "未命中，请点击青色圆内";
    feedbackGood = hit;
    taps.add(
        new Tap(
            time,
            Double.isFinite(x) ? x : null,
            Double.isFinite(y) ? y : null,
            tx,
            ty,
            targetShownMs,
            hit,
            false,
            tooFast ? "too_fast" : anticipated ? "anticipation" : hit ? "hit" : "miss"));
    if (!tooFast) lastAcceptedTapMs = time;
    if (!hit) {
      errors++;
      return;
    }
    successes++;
    successfulTapTimesMs.add(time);
    if ("random".equals(subtype)) {
      round++;
      if (round < expectedTargets()) {
        targetShownMs = time;
        nextRandomTarget();
      }
    }
    if (successes >= expectedTargets() || ("random".equals(subtype) && round >= expectedTargets()))
      finish(eventUptimeMs);
  }

  public double targetRadius() {
    return "tapping".equals(subtype)
        ? Math.max(.14, config().targetRadius * 2.1)
        : "sequence".equals(subtype)
            ? Math.min(.105, config().targetRadius)
            : config().targetRadius;
  }

  public double progress() {
    if (isTrace()) {
      ensureGuide();
      return guide.isEmpty() ? 0 : (double) coveredCount / guide.size();
    }
    return Math.min(1, (double) ("random".equals(subtype) ? round : successes) / expectedTargets());
  }

  public Double liveDeviation() {
    return deviationCount == 0 ? null : deviationSum / deviationCount;
  }

  public List<TrainingAlgorithms.Point> guide() {
    ensureGuide();
    return guide;
  }

  private void ensureGuide() {
    if (guide == null) guide = TrainingAlgorithms.tracePath(subtype, difficulty);
    if (covered == null) {
      covered = new boolean[guide.size()];
      coveredCount = 0;
      double tolerance2 = config().tolerance * config().tolerance;
      for (Sample sample : samples)
        for (int i = 0; i < guide.size(); i++) {
          TrainingAlgorithms.Point p = guide.get(i);
          if (!covered[i]
              && Math.pow(sample.x - p.x, 2) + Math.pow(sample.y - p.y, 2) <= tolerance2) {
            covered[i] = true;
            coveredCount++;
          }
        }
    }
  }

  private void nextRandomTarget() {
    double radius = targetRadius(), inset = Math.max(.12, radius + .04);
    double oldX = targetX, oldY = targetY;
    for (int attempt = 0; attempt < 24; attempt++) {
      targetX = inset + random.nextDouble() * (1 - 2 * inset);
      targetY = inset + random.nextDouble() * (1 - 2 * inset);
      if (Math.hypot(targetX - oldX, targetY - oldY) >= .25) break;
    }
  }

  private void createSequence() {
    ArrayList<Target> cells = new ArrayList<>();
    int rows = (int) Math.ceil(expectedTargets() / 3.0);
    for (int y = 0; y < rows; y++)
      for (int x = 0; x < 3; x++) cells.add(new Target(.18 + x * .32, (y + .5) / rows));
    Collections.shuffle(cells, random);
    sequence.addAll(cells.subList(0, Math.min(expectedTargets(), cells.size())));
  }

  private void compressSamples() {
    // 极端采样频率下保留首尾、笔画分隔与半数中间点；分数仍用真实坐标计算。
    ArrayList<Sample> retained = new ArrayList<>();
    for (int i = 0; i < samples.size(); i++) {
      Sample sample = samples.get(i);
      if (i == 0 || i == samples.size() - 1 || sample.breakBefore || (i & 1) == 0)
        retained.add(sample);
    }
    ignoredSamples += samples.size() - retained.size();
    samples.clear();
    samples.addAll(retained);
    if (samples.size() >= MAX_SAMPLES) {
      samples.remove(1);
      ignoredSamples++;
    }
  }

  public void finish(long nowUptimeMs) {
    // 手动结束可先暂停再确认，仍须检查暂停前已耗尽的总时限。
    if (!"random".equals(subtype) && elapsed(nowUptimeMs) >= timeLimit()) timeLimitReached = true;
    pause(nowUptimeMs);
    finished = true;
  }

  private ArrayList<TrainingAlgorithms.Point> algorithmSamples() {
    ArrayList<TrainingAlgorithms.Point> points = new ArrayList<>();
    for (Sample s : samples)
      points.add(new TrainingAlgorithms.Point(s.x, s.y, s.timeMs, s.breakBefore));
    return points;
  }

  /** null 表示正常结束；aborted 表示退出或重开。不可用的指标保持 null。 */
  public TrainingRecord buildRecord(String forcedStatus, long wallEndTime) {
    TrainingRecord record = new TrainingRecord();
    record.sessionId = sessionId;
    record.type = type;
    record.subtype = subtype;
    record.difficulty = difficulty;
    record.startedAt = startedAt;
    record.endedAt = wallEndTime;
    record.durationMs = accumulatedMs;
    record.successes = successes;
    record.errors = errors;
    record.timeouts = timeouts;
    String reason;
    boolean completed;
    if (isTrace()) {
      TrainingAlgorithms.TraceResult result =
          TrainingAlgorithms.analyzeTrace(algorithmSamples(), guide(), difficulty, true);
      record.score = result.score;
      record.valid = result.valid;
      record.accuracy = result.accuracyAvailable ? result.accuracy : null;
      record.meanDeviation = result.deviationAvailable ? result.meanDeviation : null;
      record.coverage = result.coverageAvailable ? result.coverage : null;
      record.stability = result.stabilityAvailable ? result.stability : null;
      reason = result.reason;
      completed = result.valid;
    } else if ("random".equals(subtype)) {
      ArrayList<TrainingAlgorithms.Trial> trials = new ArrayList<>();
      for (Tap t : taps)
        trials.add(
            new TrainingAlgorithms.Trial(
                t.targetShownMs * 1000000L, t.timeout ? 0 : t.timeMs * 1000000L, t.hit, t.timeout));
      completed = round >= expectedTargets();
      TrainingAlgorithms.ReactionResult result =
          TrainingAlgorithms.analyzeReaction(trials, expectedTargets(), difficulty, completed);
      record.score = result.score;
      record.valid = result.valid;
      record.averageReactionMs = result.averageReactionMs;
      record.fastestReactionMs = result.fastestReactionMs;
      if (successes + errors > 0) record.accuracy = result.accuracy;
      reason = result.reason;
    } else {
      ArrayList<Long> times = new ArrayList<>();
      for (Long time : successfulTapTimesMs) times.add(time * 1000000L);
      completed = successes >= expectedTargets();
      TrainingAlgorithms.CoordinationResult result =
          TrainingAlgorithms.analyzeCoordination(
              times,
              errors,
              expectedTargets(),
              difficulty,
              subtype,
              0,
              accumulatedMs * 1000000L,
              completed);
      record.score = result.score;
      record.valid = result.valid;
      record.averageIntervalMs = result.averageIntervalMs;
      record.stability = result.stability;
      if (successes + errors > 0) record.accuracy = result.accuracy;
      reason = result.reason;
    }
    if (eventLimitReached) {
      record.valid = false;
      record.score = 0;
      reason = "触摸事件超过保护上限，训练提前结束";
    } else if (timeLimitReached) {
      record.valid = false;
      record.score = 0;
      reason = "训练总时限已到，训练未完成";
    }
    record.status =
        forcedStatus != null
            ? forcedStatus
            : completed && record.valid ? "completed" : "incomplete";
    if ("aborted".equals(record.status)) {
      record.valid = false;
      record.score = 0;
    }
    record.detailsJson = details(reason);
    return record;
  }

  private String details(String reason) {
    try {
      JSONObject object = new JSONObject();
      object
          .put("schemaVersion", 1)
          .put("coordinateSystem", "centered-square-side-normalized")
          .put("clock", "uptime active milliseconds; pauses excluded")
          .put("assessmentReason", reason)
          .put("expectedTargets", isTrace() ? JSONObject.NULL : expectedTargets())
          .put("targetRadius", isTrace() ? JSONObject.NULL : targetRadius())
          .put("timeLimitMs", "random".equals(subtype) ? config().reactionTimeoutMs : timeLimit())
          .put("observedSamples", observedSamples)
          .put("discardedOrCompactedSamples", ignoredSamples)
          .put("cancelledGestures", cancelledGestures)
          .put("eventLimitReached", eventLimitReached)
          .put("timeLimitReached", timeLimitReached)
          .put("eventLimit", MAX_EVENTS)
          .put(
              "samplingPolicy",
              "MotionEvent history; minimum 2 ms interval; adaptive compaction above 16000 points");
      JSONArray points = new JSONArray();
      for (Sample sample : samples)
        points.put(
            new JSONObject()
                .put("x", sample.x)
                .put("y", sample.y)
                .put("timeMs", sample.timeMs)
                .put("breakBefore", sample.breakBefore));
      object.put("samples", points);
      JSONArray events = new JSONArray();
      for (Tap t : taps)
        events.put(
            new JSONObject()
                .put("timeMs", t.timeMs)
                .put("x", t.x == null ? JSONObject.NULL : t.x)
                .put("y", t.y == null ? JSONObject.NULL : t.y)
                .put("targetX", t.targetX)
                .put("targetY", t.targetY)
                .put("targetShownMs", t.targetShownMs)
                .put("reactionMs", t.timeout ? JSONObject.NULL : t.timeMs - t.targetShownMs)
                .put("outcome", t.outcome));
      object.put("events", events);
      JSONArray targets = new JSONArray();
      for (int i = 0; i < sequence.size(); i++)
        targets.put(
            new JSONObject()
                .put("number", i + 1)
                .put("x", sequence.get(i).x)
                .put("y", sequence.get(i).y));
      object.put("sequenceTargets", targets);
      return object.toString();
    } catch (JSONException impossible) {
      throw new IllegalStateException("训练明细包含非法数值", impossible);
    }
  }

  public static final class Sample implements Serializable {
    private static final long serialVersionUID = 1L;
    public final double x, y;
    public final long timeMs;
    public final boolean breakBefore;

    Sample(double x, double y, long timeMs, boolean breakBefore) {
      this.x = x;
      this.y = y;
      this.timeMs = timeMs;
      this.breakBefore = breakBefore;
    }
  }

  public static final class Target implements Serializable {
    private static final long serialVersionUID = 1L;
    public final double x, y;

    Target(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  public static final class Tap implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long timeMs, targetShownMs;
    public final Double x, y;
    public final double targetX, targetY;
    public final boolean hit, timeout;
    public final String outcome;

    Tap(
        long time,
        Double x,
        Double y,
        double tx,
        double ty,
        long shown,
        boolean hit,
        boolean timeout,
        String outcome) {
      timeMs = time;
      this.x = x;
      this.y = y;
      targetX = tx;
      targetY = ty;
      targetShownMs = shown;
      this.hit = hit;
      this.timeout = timeout;
      this.outcome = outcome;
    }
  }
}
