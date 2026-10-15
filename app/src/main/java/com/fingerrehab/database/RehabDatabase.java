package com.fingerrehab.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.HashSet;
import java.util.Set;

/** 使用外键与唯一约束的 SQLite 数据库；升级仅增量修改表结构，不删除用户训练数据。 */
public final class RehabDatabase extends SQLiteOpenHelper {
  public static final String NAME = "finger_rehab.db";
  public static final int VERSION = 3;

  public RehabDatabase(Context context) {
    super(context.getApplicationContext(), NAME, null, VERSION);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    super.onConfigure(db);
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE profiles (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "nickname TEXT NOT NULL,age INTEGER,hand TEXT NOT NULL,goal TEXT NOT NULL,"
            + "daily_goal INTEGER NOT NULL DEFAULT 3,created_at INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "user_id INTEGER NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,"
            + "session_id TEXT NOT NULL UNIQUE,type TEXT NOT NULL,subtype TEXT NOT NULL,"
            + "difficulty INTEGER NOT NULL DEFAULT 0,started_at INTEGER NOT NULL,"
            + "ended_at INTEGER NOT NULL,duration_ms INTEGER NOT NULL DEFAULT 0,"
            + "score REAL NOT NULL DEFAULT 0,status TEXT NOT NULL,valid INTEGER NOT NULL DEFAULT 0,"
            + "errors INTEGER NOT NULL DEFAULT 0,successes INTEGER NOT NULL DEFAULT 0,"
            + "timeouts INTEGER NOT NULL DEFAULT 0,details_json TEXT NOT NULL DEFAULT '{}',"
            + "accuracy REAL,average_reaction_ms REAL,fastest_reaction_ms REAL,"
            + "mean_deviation REAL,coverage REAL,stability REAL,average_interval_ms REAL)");
    db.execSQL(
        "CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "user_id INTEGER NOT NULL UNIQUE REFERENCES profiles(id) ON DELETE CASCADE,"
            + "daily_goal INTEGER NOT NULL DEFAULT 3,hour INTEGER NOT NULL DEFAULT 20,"
            + "minute INTEGER NOT NULL DEFAULT 0,type TEXT NOT NULL DEFAULT 'all',"
            + "reminder_enabled INTEGER NOT NULL DEFAULT 0)");
    createIndexes(db);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    // 版本 1 已包含档案、计划与基础会话字段；版本 2 仅新增可空指标，
    // 既有训练缺失的指标仍为 null，避免把“未知”解释为 0。
    if (oldVersion < 2) {
      Set<String> columns = columns(db, "records");
      for (String name :
          new String[] {
            "accuracy",
            "average_reaction_ms",
            "fastest_reaction_ms",
            "mean_deviation",
            "coverage",
            "stability",
            "average_interval_ms"
          }) {
        if (!columns.contains(name)) db.execSQL("ALTER TABLE records ADD COLUMN " + name + " REAL");
      }
    }
    createIndexes(db);
  }

  private static void createIndexes(SQLiteDatabase db) {
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS records_user_time ON records(user_id,started_at DESC,id DESC)");
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS records_user_type_time ON records(user_id,type,started_at"
            + " DESC)");
    // 版本 3 为同子任务、同难度的推荐与筛选补充索引；仅新增索引，不改动历史记录。
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS records_user_task_time "
            + "ON records(user_id,type,subtype,difficulty,started_at DESC,id DESC)");
  }

  private static Set<String> columns(SQLiteDatabase db, String table) {
    Set<String> names = new HashSet<>();
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      while (cursor.moveToNext()) names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
    }
    return names;
  }
}
