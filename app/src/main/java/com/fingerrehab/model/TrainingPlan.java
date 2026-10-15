package com.fingerrehab.model;

/** 训练档案的本地计划；每个档案最多保存一条，包含每日目标与本地时区提醒时间。 */
public class TrainingPlan {
  public long id, userId;
  public int dailyGoal = 3;
  public int hour = 20;
  public int minute;
  public String type = "all";
  public boolean reminderEnabled;
}
