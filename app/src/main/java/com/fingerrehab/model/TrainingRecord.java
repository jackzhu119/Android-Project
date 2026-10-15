package com.fingerrehab.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * 一次训练会话的数据快照。同一 sessionId 只写入一次，重复完成回调不会覆盖原记录。 startedAt / endedAt
 * 为墙上时钟毫秒，用于本地日历归属；durationMs、反应时间和间隔 由训练界面使用单调时钟计算。不适用的指标保持 null；detailsJson 保存完整原始采样。
 */
public class TrainingRecord implements Serializable {
  private static final long serialVersionUID = 1L;
  public long id, userId, startedAt, endedAt, durationMs;
  public String sessionId = UUID.randomUUID().toString();
  public String type = "trace";
  public String subtype = "line";
  public String status = "incomplete";
  public String detailsJson = "{}";

  /** 只用于当前读取结果：旧明细超出读取上限或不可读时为 true，不写回数据库。 */
  public boolean detailsUnavailable;

  public int difficulty, errors, successes, timeouts;
  public double score;
  public Double accuracy, averageReactionMs, fastestReactionMs, meanDeviation;
  public Double coverage, stability, averageIntervalMs;
  public boolean valid;

  /** 统一有效完成口径，兼容旧数据库中有效标记与异常分数不一致的记录。 */
  public boolean isEffectiveCompletion() {
    return valid
        && "completed".equals(status)
        && Double.isFinite(score)
        && score >= 0
        && score <= 100;
  }
}
