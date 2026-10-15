package com.fingerrehab.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 统一显示名称与格式，避免把不存在的指标显示为零。 */
public final class Labels {
  private Labels() {}

  public static String type(String type) {
    if ("trace".equals(type)) return "轨迹描绘";
    if ("reaction".equals(type)) return "反应速度";
    if ("coordination".equals(type)) return "手指协调";
    return "全部训练";
  }

  public static String subtype(String type) {
    if (type == null) return "训练";
    switch (type) {
      case "line":
        return "直线描绘";
      case "circle":
        return "圆形描绘";
      case "spiral":
        return "螺旋描绘";
      case "random":
        return "随机目标";
      case "sequence":
        return "顺序点击";
      case "tapping":
        return "连续点击";
      default:
        return type;
    }
  }

  public static String difficulty(int d) {
    return new String[] {"简单", "普通", "困难"}[Math.max(0, Math.min(2, d))];
  }

  public static String duration(long ms) {
    if (ms < 60000) return String.format(Locale.CHINA, "%.1f 秒", Math.max(0, ms) / 1000.0);
    return String.format(Locale.CHINA, "%d 分 %02d 秒", ms / 60000, ms / 1000 % 60);
  }

  public static String date(long ms) {
    return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(ms));
  }

  public static String number(double n) {
    return Double.isFinite(n) ? String.format(Locale.CHINA, "%.1f", n) : "—";
  }

  public static String percent(Double n) {
    return n == null ? "—" : number(n * 100) + "%";
  }

  public static String status(String s) {
    return "completed".equals(s) ? "已完成" : "aborted".equals(s) ? "已中止" : "未完成";
  }
}
