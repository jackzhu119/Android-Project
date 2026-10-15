package com.fingerrehab.algorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 只使用最近五次同类型同难度有效完成记录，给建议而不自动改变设置。 */
public final class DifficultyRecommender {
  private DifficultyRecommender() {}

  public static final class Session {
    public final String type;
    public final int difficulty;
    public final double score;
    public final boolean valid, completed;
    public final long endedAt;

    public Session(
        String type, int difficulty, double score, boolean valid, boolean completed, long endedAt) {
      this.type = type;
      this.difficulty = difficulty;
      this.score = score;
      this.valid = valid;
      this.completed = completed;
      this.endedAt = endedAt;
    }
  }

  public static final class Recommendation {
    public final int difficulty, sampleCount;
    public final Double averageScore;
    public final boolean sufficientData;
    public final String reason;

    private Recommendation(
        int difficulty, int count, Double average, boolean sufficient, String reason) {
      this.difficulty = difficulty;
      sampleCount = count;
      averageScore = average;
      sufficientData = sufficient;
      this.reason = reason;
    }
  }

  public static Recommendation recommend(
      List<Session> sessions, String type, int currentDifficulty) {
    int level = Math.max(0, Math.min(2, currentDifficulty));
    List<Session> eligible = new ArrayList<>();
    if (sessions != null)
      for (Session s : sessions) {
        if (s != null
            && type != null
            && type.equals(s.type)
            && s.difficulty == level
            && s.valid
            && s.completed
            && Double.isFinite(s.score)
            && s.score >= 0
            && s.score <= 100) eligible.add(s);
      }
    eligible.sort(Comparator.comparingLong((Session s) -> s.endedAt).reversed());
    int count = Math.min(5, eligible.size());
    if (count < 5) return new Recommendation(level, count, null, false, "同类型、同难度有效训练不足5次，暂不提供调整结论");
    double sum = 0;
    for (int i = 0; i < 5; i++) sum += eligible.get(i).score;
    double average = sum / 5;
    if (average >= 85 && level < 2)
      return new Recommendation(level + 1, 5, average, true, "最近5次平均成绩达到85分，建议尝试更高难度");
    if (average < 60 && level > 0)
      return new Recommendation(level - 1, 5, average, true, "最近5次平均成绩低于60分，建议尝试更低难度");
    return new Recommendation(level, 5, average, true, "根据最近5次成绩，建议保持当前难度");
  }
}
