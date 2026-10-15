package com.fingerrehab.model;

import java.io.Serializable;

/** 仅保存在本机的康复训练档案；年龄为 null 表示用户未提供，不将缺省值解释为 0 岁。 */
public class UserProfile implements Serializable {
  private static final long serialVersionUID = 1L;
  public long id;
  public long createdAt;
  public String nickname = "训练者";
  public String hand = "right";
  public String goal = "提升手指灵活性";
  public Integer age;
  public int dailyGoal = 3;
}
