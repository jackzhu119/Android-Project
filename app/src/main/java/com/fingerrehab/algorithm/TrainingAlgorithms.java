package com.fingerrehab.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 三类训练的可复现工程评分，不是医学量表。所有距离使用绘图区固定正方形边长为 1。 轨迹 timeMs 是单调毫秒；反应与协调输入
 * SystemClock.elapsedRealtimeNanos() 的纳秒。 配置集中在 Config，公式与有效性条件见 docs/ALGORITHMS.md。
 */
public final class TrainingAlgorithms {
  private TrainingAlgorithms() {}

  private static final int RESAMPLE_COUNT = 128;

  public static final class Point {
    public final double x, y;
    public final long timeMs;
    public final boolean breakBefore;

    public Point(double x, double y) {
      this(x, y, 0, false);
    }

    public Point(double x, double y, long timeMs) {
      this(x, y, timeMs, false);
    }

    public Point(double x, double y, long timeMs, boolean breakBefore) {
      this.x = x;
      this.y = y;
      this.timeMs = timeMs;
      this.breakBefore = breakBefore;
    }
  }

  /** 半径与容差单位是归一化绘图区边长；所有时限单位毫秒。 */
  public static final class Config {
    public final int difficulty, reactionRounds, sequenceTargets, tappingTargets;
    public final double tolerance, targetRadius;
    public final long reactionTimeoutMs,
        traceTimeLimitMs,
        coordinationTimeLimitMs,
        minTapIntervalMs;
    public final long minimumReactionMs = 80;
    public final long minimumTraceDurationMs = 500;
    public final double minimumCoverage = .70, minimumLengthRatio = .65, maximumLengthRatio = 2.2;

    private Config(
        int difficulty,
        double tolerance,
        double radius,
        long reactionTimeout,
        int reactionRounds,
        int sequenceTargets,
        int tappingTargets,
        long traceTimeLimit,
        long coordinationTimeLimit) {
      this.difficulty = difficulty;
      this.tolerance = tolerance;
      targetRadius = radius;
      reactionTimeoutMs = reactionTimeout;
      this.reactionRounds = reactionRounds;
      this.sequenceTargets = sequenceTargets;
      this.tappingTargets = tappingTargets;
      traceTimeLimitMs = traceTimeLimit;
      coordinationTimeLimitMs = coordinationTimeLimit;
      minTapIntervalMs = 50;
    }
  }

  private static final Config[] CONFIGS = {
    new Config(0, .045, .090, 2500, 8, 6, 12, 60000, 60000),
    new Config(1, .032, .072, 1800, 10, 8, 18, 50000, 50000),
    new Config(2, .023, .055, 1200, 12, 10, 24, 40000, 40000)
  };

  public static Config config(int difficulty) {
    return CONFIGS[Math.max(0, Math.min(2, difficulty))];
  }

  /** 目标方向从第一个点向最后一点，圆为顺时针，螺旋由内向外；绘图区须为正方形。 */
  public static List<Point> tracePath(String subtype, int difficulty) {
    int level = config(difficulty).difficulty;
    List<Point> points = new ArrayList<>();
    int n = 256;
    for (int i = 0; i <= n; i++) {
      double t = i / (double) n;
      if ("circle".equals(subtype)) {
        double radius = .31 + .025 * level;
        double angle = -Math.PI / 2 + t * 2 * Math.PI;
        points.add(new Point(.5 + radius * Math.cos(angle), .5 + radius * Math.sin(angle)));
      } else if ("spiral".equals(subtype)) {
        double turns = 1.25 + .5 * level;
        double angle = -Math.PI / 2 + t * 2 * Math.PI * turns;
        double radius = .04 + .34 * t;
        points.add(new Point(.5 + radius * Math.cos(angle), .5 + radius * Math.sin(angle)));
      } else {
        points.add(new Point(.14 + .72 * t, .72 - .44 * t));
      }
    }
    return Collections.unmodifiableList(points);
  }

  public static final class TraceResult {
    public double score, accuracy, meanDeviation, coverage, stability;

    /** 允许保持 double 调用兼容性；存储与报告必须依标记决定是否写入 null。 */
    public boolean accuracyAvailable, deviationAvailable, coverageAvailable, stabilityAvailable;

    public long durationMs;
    public boolean valid;
    public String reason = "没有有效轨迹";
  }

  /** 未完成或有效性检查失败保留真实指标、综合分为 0，不能进入有效成绩统计。 */
  public static TraceResult analyzeTrace(
      List<Point> samples, List<Point> guide, int difficulty, boolean completed) {
    TraceResult r = new TraceResult();
    Config c = config(difficulty);
    List<Point> path = new ArrayList<>();
    boolean malformed = false;
    if (samples != null) {
      for (Point p : samples) {
        if (!validNormalizedPoint(p)) {
          malformed = true;
          continue;
        }
        if (!path.isEmpty() && p.timeMs < path.get(path.size() - 1).timeMs) malformed = true;
        path.add(p);
      }
    }
    if (path.isEmpty() || guide == null || guide.size() < 2) return r;
    for (Point p : guide) if (!validNormalizedPoint(p)) return r;
    r.durationMs = positiveDifference(path.get(path.size() - 1).timeMs, path.get(0).timeMs);
    double length = Geometry.pathLength(path), targetLength = Geometry.pathLength(guide);
    if (targetLength < 1e-8) {
      r.reason = "目标路径无效";
      return r;
    }
    List<Point> actual = Geometry.resample(path, RESAMPLE_COUNT);
    List<Point> target = Geometry.resample(guide, RESAMPLE_COUNT);
    double deviation = 0, orderedDeviation = 0;
    for (int i = 0; i < actual.size(); i++) {
      deviation += Geometry.distanceToPath(actual.get(i), guide);
      orderedDeviation += Geometry.distance(actual.get(i), target.get(i));
    }
    r.meanDeviation = deviation / actual.size();
    r.deviationAvailable = true;
    orderedDeviation /= actual.size();
    int covered = 0;
    for (Point p : target) if (Geometry.distanceToPath(p, path) <= c.tolerance) covered++;
    r.coverage = covered / (double) target.size();
    r.coverageAvailable = true;
    double nearAccuracy = clamp(1 - r.meanDeviation / (2 * c.tolerance));
    double orderedAccuracy = clamp(1 - orderedDeviation / (4 * c.tolerance));
    r.accuracy = .75 * nearAccuracy + .25 * orderedAccuracy;
    r.accuracyAvailable = true;
    // 静止点仍有位置偏差与有限覆盖，不能把默认零值冒充完美描绘。
    if (length < 1e-8) {
      r.reason = "有效运动路径过短";
      return r;
    }
    double residualChange = 0;
    for (int i = 1; i < actual.size() - 1; i++) {
      Point a = actual.get(i - 1), b = actual.get(i), d = actual.get(i + 1);
      Point ta = target.get(i - 1), tb = target.get(i), td = target.get(i + 1);
      residualChange +=
          Math.hypot(
              (a.x - 2 * b.x + d.x) - (ta.x - 2 * tb.x + td.x),
              (a.y - 2 * b.y + d.y) - (ta.y - 2 * tb.y + td.y));
    }
    double spatial = 1 / (1 + residualChange / Math.max(1, actual.size() - 2) / (c.tolerance * .5));
    List<Double> speeds = new ArrayList<>();
    for (int i = 1; i < actual.size(); i++) {
      long dt = actual.get(i).timeMs - actual.get(i - 1).timeMs;
      if (dt > 0 && !actual.get(i).breakBefore)
        speeds.add(Geometry.distance(actual.get(i - 1), actual.get(i)) / dt);
    }
    double temporal = speeds.size() >= 2 ? rhythm(speeds) : 0;
    r.stability = clamp(.65 * spatial + .35 * temporal);
    r.stabilityAvailable = actual.size() >= 3 && speeds.size() >= 2;
    double ratio = length / targetLength;
    double endpointTolerance = 2 * c.tolerance;
    boolean endpoints =
        Geometry.distance(path.get(0), guide.get(0)) <= endpointTolerance
            && Geometry.distance(path.get(path.size() - 1), guide.get(guide.size() - 1))
                <= endpointTolerance;
    boolean gaps = false;
    for (int i = 1; i < path.size(); i++)
      if (path.get(i).breakBefore
          && Geometry.distance(path.get(i - 1), path.get(i)) > endpointTolerance) gaps = true;
    if (!completed) r.reason = "训练未完成";
    else if (malformed) r.reason = "采样数据或单调时间异常";
    else if (r.durationMs < c.minimumTraceDurationMs) r.reason = "描绘时间过短";
    else if (r.durationMs > c.traceTimeLimitMs) r.reason = "超过训练时间限制";
    else if (path.size() < 8) r.reason = "有效采样点不足";
    else if (!endpoints) r.reason = "请从起点按方向描绘至终点";
    else if (gaps) r.reason = "笔画之间存在过大的未描绘间隔";
    else if (ratio < c.minimumLengthRatio) r.reason = "路径长度不足";
    else if (ratio > c.maximumLengthRatio) r.reason = "重复描绘或无关路径过多";
    else if (r.coverage < c.minimumCoverage) r.reason = "目标路径覆盖不足";
    else if (r.meanDeviation > c.tolerance * 2.5) r.reason = "路径偏差过大";
    else if (orderedDeviation > c.tolerance * 4) r.reason = "描绘方向或顺序异常";
    else {
      r.valid = true;
      r.reason = "有效训练";
      double idealMs = Math.max(1800, targetLength * (4200 - 500 * c.difficulty));
      double timeScore = Math.min(1, idealMs / Math.max(1, r.durationMs));
      r.score =
          score(100 * (.4 * r.accuracy + .3 * r.coverage + .2 * r.stability + .1 * timeScore));
    }
    return r;
  }

  /** 一个点击尝试或一次目标超时。错误点击不结束本轮；超时不属于点击准确率分母。 */
  public static final class Trial {
    public final long stimulusNs, responseNs;
    public final boolean correct, timeout;

    public Trial(long stimulusNs, long responseNs, boolean correct, boolean timeout) {
      this.stimulusNs = stimulusNs;
      this.responseNs = responseNs;
      this.correct = correct;
      this.timeout = timeout;
    }
  }

  public static final class ReactionResult {
    public int successes, errors, timeouts;
    public Double averageReactionMs, fastestReactionMs;
    public double accuracy, score;
    public boolean valid;
    public String reason = "没有有效反应记录";
  }

  public static ReactionResult analyzeReaction(
      List<Trial> trials, int expectedRounds, int difficulty, boolean completed) {
    ReactionResult r = new ReactionResult();
    Config c = config(difficulty);
    double sum = 0, fastest = Double.POSITIVE_INFINITY;
    boolean malformed = false;
    long priorResponse = Long.MIN_VALUE;
    long priorStimulus = Long.MIN_VALUE;
    List<Long> finishedStimuli = new ArrayList<>();
    if (trials != null)
      for (Trial t : trials) {
        if (t == null) {
          malformed = true;
          continue;
        }
        if (t.stimulusNs < priorStimulus) malformed = true;
        priorStimulus = t.stimulusNs;
        if (t.timeout) {
          if (t.correct || finishedStimuli.contains(t.stimulusNs)) malformed = true;
          else {
            r.timeouts++;
            finishedStimuli.add(t.stimulusNs);
          }
          continue;
        }
        double ms = (t.responseNs - (double) t.stimulusNs) / 1_000_000d;
        if (t.responseNs < t.stimulusNs
            || t.responseNs <= priorResponse
            || finishedStimuli.contains(t.stimulusNs)
            || ms > c.reactionTimeoutMs) malformed = true;
        priorResponse = t.responseNs;
        if (t.correct) {
          if (ms < c.minimumReactionMs || ms > c.reactionTimeoutMs || !Double.isFinite(ms))
            malformed = true;
          if (!finishedStimuli.contains(t.stimulusNs)) {
            r.successes++;
            sum += Math.max(0, ms);
            fastest = Math.min(fastest, Math.max(0, ms));
            finishedStimuli.add(t.stimulusNs);
          }
        } else r.errors++;
      }
    int attempts = r.successes + r.errors;
    r.accuracy = attempts == 0 ? 0 : r.successes / (double) attempts;
    if (r.successes > 0) {
      r.averageReactionMs = sum / r.successes;
      r.fastestReactionMs = fastest;
    }
    if (!completed) r.reason = "训练未完成";
    else if (expectedRounds <= 0 || r.successes + r.timeouts != expectedRounds)
      r.reason = "完成轮数不符合要求";
    else if (malformed) r.reason = "反应时间、重复轮次或事件顺序异常";
    else if (r.successes == 0) r.reason = "没有成功点击";
    else {
      r.valid = true;
      r.reason = "有效训练";
      double speed =
          clamp(
              1
                  - (r.averageReactionMs - c.minimumReactionMs)
                      / (c.reactionTimeoutMs - c.minimumReactionMs));
      double completion = r.successes / (double) expectedRounds;
      r.score = score(100 * (.45 * r.accuracy + .35 * speed + .20 * completion));
    }
    return r;
  }

  public static final class CoordinationResult {
    public int successes, errors;
    public double score, accuracy;
    public Double averageIntervalMs, stability;
    public long durationMs;
    public boolean valid;
    public String reason = "没有有效点击";
  }

  /** 成功触摸只记录 ACTION_DOWN 一次；50ms 内重复事件不能算作有效目标。 */
  public static CoordinationResult analyzeCoordination(
      List<Long> successfulTapTimesNs,
      int errors,
      int expectedTargets,
      int difficulty,
      String subtype,
      long startedNs,
      long endedNs,
      boolean completed) {
    CoordinationResult r = new CoordinationResult();
    Config c = config(difficulty);
    r.errors = Math.max(0, errors);
    r.durationMs = positiveDifference(endedNs, startedNs) / 1_000_000L;
    boolean malformed = errors < 0 || endedNs < startedNs;
    List<Double> intervals = new ArrayList<>();
    Long previous = null;
    if (successfulTapTimesNs != null)
      for (Long t : successfulTapTimesNs) {
        if (t == null) {
          malformed = true;
          continue;
        }
        r.successes++;
        if (t < startedNs || t > endedNs) malformed = true;
        if (previous != null) {
          double delta = (t - (double) previous) / 1_000_000d;
          if (delta < c.minTapIntervalMs) malformed = true;
          if (delta > 0) intervals.add(delta);
        }
        previous = t;
      }
    int attempts = r.successes + r.errors;
    r.accuracy = attempts == 0 ? 0 : r.successes / (double) attempts;
    if (!intervals.isEmpty()) {
      r.averageIntervalMs = mean(intervals);
      r.stability = rhythm(intervals);
    }
    if (!completed) r.reason = "训练未完成";
    else if (malformed) r.reason = "点击间隔、事件顺序或时间异常";
    else if (expectedTargets <= 0 || r.successes != expectedTargets) r.reason = "有效点击数量未达到要求";
    else if (r.durationMs <= 0 || r.durationMs > c.coordinationTimeLimitMs) r.reason = "训练时间无效或超时";
    else {
      r.valid = true;
      r.reason = "有效训练";
      double idealPerTap =
          "sequence".equals(subtype) ? 1600 - c.difficulty * 250 : 750 - c.difficulty * 150;
      double timeScore = Math.min(1, expectedTargets * idealPerTap / r.durationMs);
      double stable = r.stability == null ? 0 : r.stability;
      r.score = score(100 * (.45 * r.accuracy + .30 * timeScore + .25 * stable));
    }
    return r;
  }

  private static long positiveDifference(long later, long earlier) {
    if (later < earlier) return 0;
    long difference = later - earlier;
    return difference < 0 ? Long.MAX_VALUE : difference;
  }

  private static boolean validNormalizedPoint(Point p) {
    // 正常坐标约为[0,1]；保留有限越界运动，但拒绝会导致浮点溢出的异常尺度。
    return Geometry.finite(p) && Math.abs(p.x) <= 1_000_000 && Math.abs(p.y) <= 1_000_000;
  }

  private static double mean(List<Double> values) {
    double sum = 0;
    for (double v : values) sum += v;
    return values.isEmpty() ? 0 : sum / values.size();
  }

  private static double rhythm(List<Double> values) {
    double avg = mean(values);
    if (avg <= 0) return 0;
    double square = 0;
    for (double v : values) square += (v - avg) * (v - avg);
    double cv = Math.sqrt(square / values.size()) / avg;
    return clamp(1 / (1 + cv));
  }

  private static double clamp(double v) {
    return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
  }

  private static double score(double v) {
    return Math.round(clamp(v / 100) * 1000) / 10d;
  }
}
