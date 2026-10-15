package com.fingerrehab.repository;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.TrainingRecord;
import com.fingerrehab.model.UserProfile;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地 SQLite 统一数据入口，记录和计划的读写均自动限定当前档案。 保存档案后即将它选为当前档案；训练会话只插入、不覆盖，由 sessionId 保证重复回调幂等。 */
public final class RehabRepository implements AutoCloseable {
  private static RehabRepository instance;

  /** 明细读取上限，足以容纳正常 16000 点采样；超大旧记录仍可读取聚合指标。 */
  public static final int MAX_DETAILS_CHARACTERS = 4 * 1024 * 1024;

  private static final int DETAILS_CHUNK_CHARACTERS = 64 * 1024;
  // 列表只读摘要字段，避免首页和历史把所有轨迹采样 JSON 同时加载到内存。
  private static final String[] SUMMARY_COLUMNS = {
    "id",
    "user_id",
    "session_id",
    "type",
    "subtype",
    "difficulty",
    "started_at",
    "ended_at",
    "duration_ms",
    "score",
    "status",
    "valid",
    "errors",
    "successes",
    "timeouts",
    "accuracy",
    "average_reaction_ms",
    "fastest_reaction_ms",
    "mean_deviation",
    "coverage",
    "stability",
    "average_interval_ms"
  };
  // 与 TrainingRecord.isEffectiveCompletion() 同口径；数值范围同时排除 SQLite 的正负无穷。
  // typeof 检查防止旧导入的文本分数被 Cursor 当作 0，从而错误完成计划。
  private static final String EFFECTIVE_COMPLETION =
      "valid=1 AND status='completed' AND typeof(score) IN ('integer','real')"
          + " AND score>=0 AND score<=100";
  private static final String[] STAT_COLUMNS = {
    "started_at",
    "duration_ms",
    "score",
    "CASE WHEN " + EFFECTIVE_COMPLETION + " THEN 1 ELSE 0 END AS effective_completion"
  };
  private final RehabDatabase helper;
  private final SharedPreferences preferences;

  private RehabRepository(Context context) {
    Context app = context.getApplicationContext();
    helper = new RehabDatabase(app);
    preferences = app.getSharedPreferences("rehab_profile", Context.MODE_PRIVATE);
  }

  public static synchronized RehabRepository get(Context context) {
    if (instance == null) instance = new RehabRepository(context);
    return instance;
  }

  public synchronized UserProfile getProfile() {
    long selected = preferences.getLong("current_profile_id", 0);
    if (selected > 0) {
      try (Cursor c = db().query("profiles", null, "id=?", args(selected), null, null, null)) {
        if (c.moveToFirst()) return profile(c);
      }
    }
    try (Cursor c = db().query("profiles", null, null, null, null, null, "id DESC", "1")) {
      if (!c.moveToFirst()) return null;
      UserProfile found = profile(c);
      preferences.edit().putLong("current_profile_id", found.id).commit();
      return found;
    }
  }

  public synchronized List<UserProfile> getProfiles() {
    List<UserProfile> result = new ArrayList<>();
    try (Cursor c = db().query("profiles", null, null, null, null, null, "id DESC")) {
      while (c.moveToNext()) result.add(profile(c));
    }
    return result;
  }

  public synchronized long saveProfile(UserProfile profile) {
    validateProfile(profile);
    long createdAt = profile.createdAt;
    if (createdAt <= 0 && profile.id > 0) {
      try (Cursor c =
          db().query(
                  "profiles",
                  new String[] {"created_at"},
                  "id=?",
                  args(profile.id),
                  null,
                  null,
                  null)) {
        if (c.moveToFirst()) createdAt = c.getLong(0);
      }
    }
    if (createdAt <= 0) createdAt = System.currentTimeMillis();
    ContentValues values = new ContentValues();
    values.put("nickname", text(profile.nickname));
    put(values, "age", profile.age);
    values.put("hand", text(profile.hand));
    values.put("goal", text(profile.goal));
    values.put("daily_goal", profile.dailyGoal);
    values.put("created_at", createdAt);
    long savedId = profile.id;
    if (savedId == 0) savedId = db().insertOrThrow("profiles", null, values);
    else if (db().update("profiles", values, "id=?", args(savedId)) != 1)
      throw new IllegalArgumentException("档案不存在");
    // 只在 SQLite 成功写入后回填标识与创建时间，写入失败时原对象仍可直接重试。
    profile.id = savedId;
    profile.createdAt = createdAt;
    preferences.edit().putLong("current_profile_id", profile.id).commit();
    return profile.id;
  }

  public synchronized void setCurrentProfile(long id) {
    try (Cursor c =
        db().query("profiles", new String[] {"id"}, "id=?", args(id), null, null, null)) {
      if (!c.moveToFirst()) throw new IllegalArgumentException("档案不存在");
    }
    preferences.edit().putLong("current_profile_id", id).commit();
  }

  /** 删除档案，并由 SQLite 的 ON DELETE CASCADE 同时删除其记录与计划。 */
  public synchronized void deleteProfile(long id) {
    db().delete("profiles", "id=?", args(id));
    if (preferences.getLong("current_profile_id", 0) == id)
      preferences.edit().remove("current_profile_id").commit();
  }

  public synchronized long saveRecord(TrainingRecord record) {
    if (record == null || record.sessionId == null || record.sessionId.trim().isEmpty())
      throw new IllegalArgumentException("训练会话标识不能为空");
    long userId = requireUser();
    requireOwner(record.userId, userId);
    // 先处理重复完成回调；数据库 UNIQUE 约束额外防止并发或进程重启后的重复插入。
    try (Cursor c =
        db().query(
                "records",
                new String[] {"id", "user_id"},
                "session_id=?",
                new String[] {record.sessionId},
                null,
                null,
                null)) {
      if (c.moveToFirst()) {
        if (c.getLong(1) != userId) throw new IllegalArgumentException("会话属于其他档案");
        record.id = c.getLong(0);
        record.userId = userId;
        return record.id;
      }
    }
    validateRecord(record);
    ContentValues v = new ContentValues();
    v.put("user_id", userId);
    v.put("session_id", record.sessionId);
    v.put("type", text(record.type));
    v.put("subtype", text(record.subtype));
    v.put("difficulty", record.difficulty);
    v.put("started_at", record.startedAt);
    v.put("ended_at", record.endedAt);
    v.put("duration_ms", record.durationMs);
    v.put("score", record.score);
    v.put("status", text(record.status));
    v.put("valid", record.valid ? 1 : 0);
    v.put("errors", record.errors);
    v.put("successes", record.successes);
    v.put("timeouts", record.timeouts);
    v.put("details_json", record.detailsJson == null ? "{}" : record.detailsJson);
    put(v, "accuracy", record.accuracy);
    put(v, "average_reaction_ms", record.averageReactionMs);
    put(v, "fastest_reaction_ms", record.fastestReactionMs);
    put(v, "mean_deviation", record.meanDeviation);
    put(v, "coverage", record.coverage);
    put(v, "stability", record.stability);
    put(v, "average_interval_ms", record.averageIntervalMs);
    long savedId = db().insertOrThrow("records", null, v);
    record.id = savedId;
    record.userId = userId;
    return record.id;
  }

  public synchronized TrainingRecord getRecord(long id) {
    long userId = currentUser();
    TrainingRecord result;
    try (Cursor c =
        db().query(
                "records",
                SUMMARY_COLUMNS,
                "id=? AND user_id=?",
                args(id, userId),
                null,
                null,
                null)) {
      if (!c.moveToFirst()) return null;
      result = record(c);
    }
    readDetails(result, userId);
    return result;
  }

  private void readDetails(TrainingRecord result, long userId) {
    try {
      long characters;
      try (Cursor c =
          db().rawQuery(
                  "SELECT length(details_json) FROM records WHERE id=? AND user_id=?",
                  args(result.id, userId))) {
        if (!c.moveToFirst() || c.isNull(0)) {
          result.detailsUnavailable = true;
          return;
        }
        characters = c.getLong(0);
      }
      if (characters < 0 || characters > MAX_DETAILS_CHARACTERS) {
        result.detailsUnavailable = true;
        return;
      }
      StringBuilder details = new StringBuilder((int) characters);
      // SQLite substr 按 Unicode 字符截取，不会把 UTF-8 字节或代理对切断。
      // 每个窗口只投影一个小块，防止整行明细超过 Android CursorWindow 的限制。
      for (long position = 1; position <= characters; position += DETAILS_CHUNK_CHARACTERS) {
        try (Cursor c =
            db().rawQuery(
                    "SELECT substr(details_json,?,?) FROM records WHERE id=? AND user_id=?",
                    args(position, DETAILS_CHUNK_CHARACTERS, result.id, userId))) {
          if (!c.moveToFirst() || c.isNull(0)) {
            result.detailsUnavailable = true;
            return;
          }
          details.append(c.getString(0));
        }
      }
      result.detailsJson = details.toString();
    } catch (SQLiteException | IllegalStateException unreadableDetails) {
      // 不删除或改写旧明细；结果页仍可展示已成功取得的分数、时长与指标。
      result.detailsUnavailable = true;
    }
  }

  public synchronized List<TrainingRecord> getRecords(String type) {
    return queryRecords(scoped(type), null);
  }

  /** 按当前档案、任务与难度分页读取摘要；offset 从 0 开始，排序包含 id 以稳定处理相同时间。 */
  public synchronized List<TrainingRecord> getRecords(
      String type, String subtype, Integer difficulty, int limit, int offset) {
    if (limit <= 0 || offset < 0) throw new IllegalArgumentException("分页参数无效");
    return queryRecords(scoped(type, subtype, difficulty), offset + "," + limit);
  }

  /** 获取筛选结果条数，不把历史记录或轨迹加载进内存。 */
  public synchronized int recordCount(String type, String subtype, Integer difficulty) {
    Query query = scoped(type, subtype, difficulty);
    try (Cursor c =
        db().rawQuery("SELECT COUNT(*) FROM records WHERE " + query.where, query.args)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  private List<TrainingRecord> queryRecords(Query query, String limit) {
    List<TrainingRecord> result = new ArrayList<>();
    try (Cursor c =
        db().query(
                "records",
                SUMMARY_COLUMNS,
                query.where,
                query.args,
                null,
                null,
                "started_at DESC,id DESC",
                limit)) {
      while (c.moveToNext()) result.add(record(c));
    }
    return result;
  }

  public synchronized void deleteRecord(long id) {
    db().delete("records", "id=? AND user_id=?", args(id, currentUser()));
  }

  public synchronized TrainingPlan getPlan() {
    try (Cursor c = db().query("plans", null, "user_id=?", args(currentUser()), null, null, null)) {
      if (!c.moveToFirst()) return null;
      TrainingPlan p = new TrainingPlan();
      p.id = value(c, "id");
      p.userId = value(c, "user_id");
      p.dailyGoal = integer(c, "daily_goal");
      p.hour = integer(c, "hour");
      p.minute = integer(c, "minute");
      p.type = string(c, "type");
      p.reminderEnabled = integer(c, "reminder_enabled") == 1;
      return p;
    }
  }

  public synchronized long savePlan(TrainingPlan plan) {
    if (plan == null
        || plan.dailyGoal < 1
        || plan.dailyGoal > 20
        || plan.hour < 0
        || plan.hour > 23
        || plan.minute < 0
        || plan.minute > 59) throw new IllegalArgumentException("计划参数无效");
    long userId = requireUser();
    requireOwner(plan.userId, userId);
    if (plan.type != null && !"all".equals(plan.type) && !isType(plan.type))
      throw new IllegalArgumentException("计划训练类型无效");
    TrainingPlan previous = getPlan();
    ContentValues v = new ContentValues();
    v.put("user_id", userId);
    v.put("daily_goal", plan.dailyGoal);
    v.put("hour", plan.hour);
    v.put("minute", plan.minute);
    v.put("type", plan.type == null ? "all" : plan.type);
    v.put("reminder_enabled", plan.reminderEnabled ? 1 : 0);
    long savedId;
    if (previous == null) savedId = db().insertOrThrow("plans", null, v);
    else {
      savedId = previous.id;
      if (db().update("plans", v, "user_id=?", args(userId)) != 1)
        throw new IllegalStateException("计划未保存，请重试");
    }
    plan.id = savedId;
    plan.userId = userId;
    return plan.id;
  }

  public synchronized void deletePlan() {
    db().delete("plans", "user_id=?", args(currentUser()));
  }

  public synchronized int completedToday(String type) {
    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = LocalDate.now(zone);
    Query q = scoped(type);
    String where = q.where + " AND " + EFFECTIVE_COMPLETION + " AND started_at>=? AND started_at<?";
    try (Cursor c =
        db().rawQuery(
                "SELECT COUNT(*) FROM records WHERE " + where,
                append(q.args, start(today, zone), start(today.plusDays(1), zone)))) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  public synchronized List<TrainingRecord> recentValid(String type, int difficulty, int limit) {
    return recentValid(type, null, difficulty, limit);
  }

  /** 获取同子任务、同难度的最近有效会话，避免自适应推荐混入其他任务的成绩。 */
  public synchronized List<TrainingRecord> recentValid(
      String type, String subtype, Integer difficulty, int limit) {
    List<TrainingRecord> result = new ArrayList<>();
    if (limit <= 0) return result;
    Query q = scoped(type, subtype, difficulty);
    try (Cursor c =
        db().query(
                "records",
                SUMMARY_COLUMNS,
                q.where + " AND " + EFFECTIVE_COMPLETION,
                q.args,
                null,
                null,
                "started_at DESC,id DESC",
                String.valueOf(limit))) {
      while (c.moveToNext()) result.add(record(c));
    }
    return result;
  }

  /** 按本地日期统计，补齐空白天；逐日计算午夜，避免夏令时 23/25 小时日期错位。 */
  public synchronized Stats getStats(String type, int days) {
    return getStats(type, null, null, days);
  }

  /** 统计与历史/推荐采用相同的 SQL 筛选口径；null 难度表示全部难度。 */
  public synchronized Stats getStats(String type, String subtype, Integer difficulty, int days) {
    ZoneId zone = ZoneId.systemDefault();
    return getStatsAt(type, subtype, difficulty, days, LocalDate.now(zone), zone);
  }

  // 显式日期锚点供边界和夏令时测试使用，无需修改设备时钟。
  // 正式入口始终使用设备当前本地日期。
  synchronized Stats getStatsAt(String type, int days, LocalDate today, ZoneId zone) {
    return getStatsAt(type, null, null, days, today, zone);
  }

  synchronized Stats getStatsAt(
      String type, String subtype, Integer difficulty, int days, LocalDate today, ZoneId zone) {
    if (days < 1 || days > 3660) throw new IllegalArgumentException("统计天数应为 1 至 3660");
    LocalDate first = today.minusDays(days - 1L);
    Stats result = new Stats();
    Map<LocalDate, DayStat> buckets = new LinkedHashMap<>();
    Map<LocalDate, Double> sums = new LinkedHashMap<>();
    Map<LocalDate, Integer> validCounts = new LinkedHashMap<>();
    for (int i = 0; i < days; i++) {
      LocalDate day = first.plusDays(i);
      DayStat bucket = new DayStat();
      bucket.dayStart = start(day, zone);
      result.days.add(bucket);
      buckets.put(day, bucket);
      sums.put(day, 0d);
      validCounts.put(day, 0);
    }
    Query q = scoped(type, subtype, difficulty);
    double scoreSum = 0;
    int scoreCount = 0;
    try (Cursor c =
        db().query(
                "records",
                STAT_COLUMNS,
                q.where + " AND started_at>=? AND started_at<?",
                append(q.args, start(first, zone), start(today.plusDays(1), zone)),
                null,
                null,
                null)) {
      while (c.moveToNext()) {
        long startedAt = value(c, "started_at");
        LocalDate date = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate();
        DayStat bucket = buckets.get(date);
        if (bucket == null) continue;
        result.count++;
        bucket.count++;
        result.durationMs += value(c, "duration_ms");
        if (integer(c, "effective_completion") == 1) {
          double score = c.getDouble(index(c, "score"));
          scoreSum += score;
          scoreCount++;
          result.bestScore = result.bestScore == null ? score : Math.max(result.bestScore, score);
          sums.put(date, sums.get(date) + score);
          validCounts.put(date, validCounts.get(date) + 1);
          result.completed++;
        }
      }
    }
    if (scoreCount > 0) result.averageScore = scoreSum / scoreCount;
    for (Map.Entry<LocalDate, DayStat> entry : buckets.entrySet()) {
      int valid = validCounts.get(entry.getKey());
      if (valid > 0) entry.getValue().averageScore = sums.get(entry.getKey()) / valid;
    }
    return result;
  }

  public static final class Stats {
    public int count, completed;
    public long durationMs;
    public Double averageScore, bestScore;
    public List<DayStat> days = new ArrayList<>();
  }

  public static final class DayStat {
    public long dayStart;
    public int count;
    public Double averageScore;
  }

  @Override
  public synchronized void close() {
    helper.close();
    synchronized (RehabRepository.class) {
      if (instance == this) instance = null;
    }
  }

  private SQLiteDatabase db() {
    return helper.getWritableDatabase();
  }

  private long currentUser() {
    UserProfile p = getProfile();
    return p == null ? -1 : p.id;
  }

  private long requireUser() {
    long user = currentUser();
    if (user < 0) throw new IllegalStateException("请先创建个人档案");
    return user;
  }

  private Query scoped(String type) {
    return scoped(type, null, null);
  }

  private Query scoped(String type, String subtype, Integer difficulty) {
    if (difficulty != null && (difficulty < 0 || difficulty > 2))
      throw new IllegalArgumentException("难度应为 0 至 2");
    StringBuilder where = new StringBuilder("user_id=?");
    List<String> values = new ArrayList<>();
    values.add(String.valueOf(currentUser()));
    if (type != null && !"all".equals(type)) {
      where.append(" AND type=?");
      values.add(type);
    }
    if (subtype != null && !"all".equals(subtype)) {
      where.append(" AND subtype=?");
      values.add(subtype);
    }
    if (difficulty != null) {
      where.append(" AND difficulty=?");
      values.add(String.valueOf(difficulty));
    }
    return new Query(where.toString(), values.toArray(new String[0]));
  }

  private static void requireOwner(long declaredUser, long currentUser) {
    if (declaredUser != 0 && declaredUser != currentUser)
      throw new IllegalArgumentException("资料属于其他档案，请重新打开当前页面");
  }

  private static void validateProfile(UserProfile p) {
    if (p == null
        || p.id < 0
        || text(p.nickname).trim().isEmpty()
        || p.nickname.length() > 24
        || text(p.goal).trim().isEmpty()
        || p.goal.length() > 120
        || text(p.hand).trim().isEmpty()
        || (p.age != null && (p.age < 1 || p.age > 120))
        || p.dailyGoal < 1
        || p.dailyGoal > 20) throw new IllegalArgumentException("档案参数无效");
  }

  private static boolean isType(String type) {
    return "trace".equals(type) || "reaction".equals(type) || "coordination".equals(type);
  }

  private static void validateRecord(TrainingRecord r) {
    if (!isType(r.type)
        || text(r.subtype).trim().isEmpty()
        || r.difficulty < 0
        || r.difficulty > 2
        || !("completed".equals(r.status)
            || "incomplete".equals(r.status)
            || "aborted".equals(r.status))
        || r.durationMs < 0
        || r.errors < 0
        || r.successes < 0
        || r.timeouts < 0) throw new IllegalArgumentException("训练记录参数无效");
    metric("分数", r.score, 100);
    metric("准确率", r.accuracy, 1);
    metric("覆盖率", r.coverage, 1);
    metric("稳定性", r.stability, 1);
    metric("平均反应时间", r.averageReactionMs, Double.MAX_VALUE);
    metric("最快反应时间", r.fastestReactionMs, Double.MAX_VALUE);
    metric("平均路径偏差", r.meanDeviation, Double.MAX_VALUE);
    metric("平均点击间隔", r.averageIntervalMs, Double.MAX_VALUE);
  }

  private static void metric(String label, Double value, double max) {
    if (value != null && (!Double.isFinite(value) || value < 0 || value > max))
      throw new IllegalArgumentException(label + "超出有效范围");
  }

  private static final class Query {
    final String where;
    final String[] args;

    Query(String where, String[] args) {
      this.where = where;
      this.args = args;
    }
  }

  private static long start(LocalDate day, ZoneId zone) {
    return day.atStartOfDay(zone).toInstant().toEpochMilli();
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static String[] args(long... values) {
    String[] result = new String[values.length];
    for (int i = 0; i < values.length; i++) result[i] = String.valueOf(values[i]);
    return result;
  }

  private static String[] append(String[] original, long... values) {
    String[] result = new String[original.length + values.length];
    System.arraycopy(original, 0, result, 0, original.length);
    for (int i = 0; i < values.length; i++) result[original.length + i] = String.valueOf(values[i]);
    return result;
  }

  private static void put(ContentValues values, String name, Number value) {
    if (value == null) values.putNull(name);
    else if (value instanceof Integer) values.put(name, value.intValue());
    else values.put(name, value.doubleValue());
  }

  private static int index(Cursor c, String name) {
    return c.getColumnIndexOrThrow(name);
  }

  private static long value(Cursor c, String name) {
    return c.getLong(index(c, name));
  }

  private static int integer(Cursor c, String name) {
    return c.getInt(index(c, name));
  }

  private static String string(Cursor c, String name) {
    return c.getString(index(c, name));
  }

  private static Double optional(Cursor c, String name) {
    int i = index(c, name);
    return c.isNull(i) ? null : c.getDouble(i);
  }

  private static UserProfile profile(Cursor c) {
    UserProfile p = new UserProfile();
    p.id = value(c, "id");
    p.createdAt = value(c, "created_at");
    p.nickname = string(c, "nickname");
    p.hand = string(c, "hand");
    p.goal = string(c, "goal");
    p.dailyGoal = integer(c, "daily_goal");
    int age = index(c, "age");
    p.age = c.isNull(age) ? null : c.getInt(age);
    return p;
  }

  private static TrainingRecord record(Cursor c) {
    TrainingRecord r = new TrainingRecord();
    r.id = value(c, "id");
    r.userId = value(c, "user_id");
    r.sessionId = string(c, "session_id");
    r.type = string(c, "type");
    r.subtype = string(c, "subtype");
    r.difficulty = integer(c, "difficulty");
    r.startedAt = value(c, "started_at");
    r.endedAt = value(c, "ended_at");
    r.durationMs = value(c, "duration_ms");
    r.score = score(c);
    r.status = string(c, "status");
    r.valid = integer(c, "valid") == 1;
    r.errors = integer(c, "errors");
    r.successes = integer(c, "successes");
    r.timeouts = integer(c, "timeouts");
    int details = c.getColumnIndex("details_json");
    if (details >= 0) r.detailsJson = c.getString(details);
    r.accuracy = optional(c, "accuracy");
    r.averageReactionMs = optional(c, "average_reaction_ms");
    r.fastestReactionMs = optional(c, "fastest_reaction_ms");
    r.meanDeviation = optional(c, "mean_deviation");
    r.coverage = optional(c, "coverage");
    r.stability = optional(c, "stability");
    r.averageIntervalMs = optional(c, "average_interval_ms");
    return r;
  }

  private static double score(Cursor cursor) {
    int column = index(cursor, "score");
    int type = cursor.getType(column);
    // 不修改数据库中的旧值；不可解析的旧分数在模型中标记为 NaN，统一判为无效完成。
    return type == Cursor.FIELD_TYPE_INTEGER || type == Cursor.FIELD_TYPE_FLOAT
        ? cursor.getDouble(column)
        : Double.NaN;
  }
}
