package com.fingerrehab.repository;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.TrainingRecord;
import com.fingerrehab.model.UserProfile;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RehabRepositoryTest {
  private Context context;
  private RehabRepository repo;
  private TimeZone originalZone;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", Context.MODE_PRIVATE).edit().clear().commit();
    originalZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    repo = RehabRepository.get(context);
  }

  @After
  public void tearDown() {
    repo.close();
    context.deleteDatabase(RehabDatabase.NAME);
    TimeZone.setDefault(originalZone);
  }

  @Test
  public void profileCreateUpdateReopenAndDelete() {
    assertNull(repo.getProfile());
    UserProfile p = profile("小安");
    assertTrue(p.id > 0);
    assertNull(repo.getProfile().age);
    long createdAt = p.createdAt;
    p.nickname = "小安更新";
    p.age = 24;
    p.hand = "left";
    p.dailyGoal = 5;
    assertEquals(p.id, repo.saveProfile(p));
    repo.close();
    repo = RehabRepository.get(context);
    UserProfile stored = repo.getProfile();
    assertEquals("小安更新", stored.nickname);
    assertEquals(Integer.valueOf(24), stored.age);
    assertEquals("left", stored.hand);
    assertEquals(5, stored.dailyGoal);
    assertEquals(createdAt, stored.createdAt);
    repo.deleteProfile(p.id);
    assertNull(repo.getProfile());
    assertTrue(repo.getProfiles().isEmpty());
  }

  @Test
  public void recordRawSamplesNullableMetricsAndDedupSurviveReopen() {
    UserProfile p = profile("训练者");
    TrainingRecord r = completed(System.currentTimeMillis(), "trace", 81.5);
    r.detailsJson =
        "{ \"samples\": [{\"x\":0.125,\"y\":0.25,\"t\":17}, {\"x\":0.5,\"y\":0.75,\"t\":26}],"
            + " \"notes\":\"原始采样\" }";
    r.accuracy = .85;
    r.meanDeviation = .025;
    r.coverage = .8;
    r.errors = 2;
    r.successes = 12;
    r.timeouts = 1;
    r.difficulty = 2;
    long id = repo.saveRecord(r);
    assertEquals(p.id, r.userId);
    repo.close();
    repo = RehabRepository.get(context);
    TrainingRecord stored = repo.getRecord(id);
    assertNotNull(stored);
    assertEquals(r.detailsJson, stored.detailsJson);
    assertEquals(r.sessionId, stored.sessionId);
    assertEquals(r.durationMs, stored.durationMs);
    assertEquals(r.accuracy, stored.accuracy);
    assertEquals(r.meanDeviation, stored.meanDeviation);
    assertEquals(r.coverage, stored.coverage);
    assertNull(stored.averageReactionMs);
    assertNull(stored.fastestReactionMs);
    assertNull(stored.stability);
    assertNull(stored.averageIntervalMs);
    assertEquals(2, stored.errors);
    assertEquals(12, stored.successes);
    assertEquals(1, stored.timeouts);
    r.score = 5;
    assertEquals(id, repo.saveRecord(r));
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("{}", repo.getRecords(null).get(0).detailsJson);
    assertEquals("{}", repo.recentValid("trace", 2, 1).get(0).detailsJson);
    assertEquals(81.5, repo.getRecord(id).score, .00001);
    repo.deleteRecord(id);
    assertNull(repo.getRecord(id));
    assertTrue(repo.getRecords("all").isEmpty());
  }

  @Test
  public void usersCannotReadDeleteOrOverwriteEachOthersRecordsAndPlans() {
    UserProfile first = profile("甲");
    TrainingRecord firstRecord = completed(System.currentTimeMillis(), "reaction", 90);
    long firstId = repo.saveRecord(firstRecord);
    TrainingPlan firstPlan = new TrainingPlan();
    firstPlan.dailyGoal = 5;
    repo.savePlan(firstPlan);
    UserProfile second = profile("乙");
    assertNull(repo.getRecord(firstId));
    assertTrue(repo.getRecords(null).isEmpty());
    assertNull(repo.getPlan());
    assertEquals(0, repo.completedToday(null));
    assertEquals(0, repo.getStats(null, 7).count);
    repo.deleteRecord(firstId);
    try {
      repo.saveRecord(firstRecord);
      fail("Cross-profile session collision must be rejected");
    } catch (IllegalArgumentException expected) {
      /* Keeps the first owner's session intact. */
    }
    TrainingRecord secondRecord = completed(System.currentTimeMillis(), "trace", 60);
    repo.saveRecord(secondRecord);
    TrainingPlan secondPlan = new TrainingPlan();
    secondPlan.dailyGoal = 2;
    repo.savePlan(secondPlan);
    assertEquals(1, repo.getRecords(null).size());
    repo.setCurrentProfile(first.id);
    assertNotNull(repo.getRecord(firstId));
    assertNull(repo.getRecord(secondRecord.id));
    assertEquals(5, repo.getPlan().dailyGoal);
    repo.deletePlan();
    repo.setCurrentProfile(second.id);
    assertEquals(2, repo.getPlan().dailyGoal);
  }

  @Test
  public void profileDeletionCascadesRecordsAndPlan() {
    UserProfile p = profile("待删除");
    repo.saveRecord(completed(System.currentTimeMillis(), "coordination", 70));
    repo.savePlan(new TrainingPlan());
    repo.deleteProfile(p.id);
    try (SQLiteDatabase raw =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(RehabDatabase.NAME).getPath(),
            null,
            SQLiteDatabase.OPEN_READONLY)) {
      for (String table : new String[] {"records", "plans"}) {
        try (Cursor c = raw.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
          assertTrue(c.moveToFirst());
          assertEquals(0, c.getInt(0));
        }
      }
    }
  }

  @Test
  public void planCreateUpdatePersistAndDelete() {
    profile("计划用户");
    assertNull(repo.getPlan());
    TrainingPlan p = new TrainingPlan();
    p.type = "coordination";
    p.dailyGoal = 4;
    p.hour = 9;
    p.minute = 35;
    p.reminderEnabled = true;
    long id = repo.savePlan(p);
    p.hour = 18;
    p.dailyGoal = 6;
    assertEquals(id, repo.savePlan(p));
    repo.close();
    repo = RehabRepository.get(context);
    TrainingPlan stored = repo.getPlan();
    assertEquals(id, stored.id);
    assertEquals("coordination", stored.type);
    assertEquals(6, stored.dailyGoal);
    assertEquals(18, stored.hour);
    assertEquals(35, stored.minute);
    assertTrue(stored.reminderEnabled);
    repo.deletePlan();
    assertNull(repo.getPlan());
  }

  @Test
  public void emptyStatisticsHaveNullAveragesAndSevenOrThirtyLocalDays() {
    for (int days : new int[] {7, 30}) {
      RehabRepository.Stats stats = repo.getStats("all", days);
      assertEquals(0, stats.count);
      assertEquals(0, stats.completed);
      assertEquals(0, stats.durationMs);
      assertNull(stats.averageScore);
      assertNull(stats.bestScore);
      assertEquals(days, stats.days.size());
      for (int i = 0; i < days; i++) {
        RehabRepository.DayStat day = stats.days.get(i);
        assertEquals(0, day.count);
        assertNull(day.averageScore);
        if (i > 0) assertTrue(stats.days.get(i - 1).dayStart < day.dayStart);
      }
    }
  }

  @Test
  public void statisticsUseRangeTypeAndValidScoresWithoutInventingZeroAverages() {
    profile("统计用户");
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    LocalDate today = LocalDate.of(2026, 10, 15);
    long start = day(today.minusDays(6), zone), end = day(today.plusDays(1), zone);
    repo.saveRecord(completed(start - 1, "trace", 99)); // Before the inclusive lower bound.
    repo.saveRecord(completed(start, "trace", 80));
    TrainingRecord invalid = completed(start + 1, "trace", 0);
    invalid.valid = false;
    invalid.status = "incomplete";
    invalid.durationMs = 500;
    repo.saveRecord(invalid);
    TrainingRecord invalidOnlyDay = completed(day(today.minusDays(5), zone), "trace", 0);
    invalidOnlyDay.valid = false;
    invalidOnlyDay.status = "aborted";
    repo.saveRecord(invalidOnlyDay);
    repo.saveRecord(completed(end - 1, "trace", 100));
    repo.saveRecord(completed(end, "trace", 1)); // Exclusive upper bound.
    repo.saveRecord(completed(start + 2, "reaction", 10));
    RehabRepository.Stats stats = repo.getStatsAt("trace", 7, today, zone);
    assertEquals(4, stats.count);
    assertEquals(2, stats.completed);
    assertEquals(3500, stats.durationMs);
    assertEquals(90, stats.averageScore, .00001);
    assertEquals(100, stats.bestScore, .00001);
    assertEquals(2, stats.days.get(0).count);
    assertEquals(80, stats.days.get(0).averageScore, .00001);
    assertEquals(1, stats.days.get(1).count);
    assertNull(stats.days.get(1).averageScore);
    assertEquals(0, stats.days.get(2).count);
    assertNull(stats.days.get(2).averageScore);
    assertEquals(1, stats.days.get(6).count);
    assertEquals(5, repo.getStatsAt(null, 7, today, zone).count);
  }

  @Test
  public void incompleteAndAbortedSessionsRemainVisibleButDoNotCompletePlans() {
    profile("中断用户");
    long now = System.currentTimeMillis();
    TrainingRecord completed = completed(now, "trace", 88);
    completed.difficulty = 1;
    repo.saveRecord(completed);
    TrainingRecord incomplete = completed(now - 1, "trace", 40);
    incomplete.status = "incomplete";
    incomplete.valid = false;
    incomplete.difficulty = 1;
    repo.saveRecord(incomplete);
    TrainingRecord aborted = completed(now - 2, "trace", 20);
    aborted.status = "aborted";
    aborted.valid = false;
    aborted.difficulty = 1;
    repo.saveRecord(aborted);
    TrainingRecord reaction = completed(now - 3, "reaction", 90);
    repo.saveRecord(reaction);
    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = LocalDate.now(zone);
    repo.saveRecord(completed(day(today, zone) - 1, "coordination", 99));
    repo.saveRecord(completed(day(today.plusDays(1), zone), "coordination", 99));
    assertEquals(6, repo.getRecords("all").size());
    assertEquals(3, repo.getRecords("trace").size());
    assertEquals(completed.id, repo.getRecords("trace").get(0).id);
    assertEquals(1, repo.completedToday("trace"));
    assertEquals(0, repo.completedToday("coordination"));
    assertEquals(2, repo.completedToday(null));
    assertEquals(1, repo.recentValid("trace", 1, 10).size());
    assertEquals(0, repo.recentValid("trace", 2, 10).size());
    assertEquals(0, repo.recentValid("trace", 1, 0).size());
  }

  @Test
  public void daylightSavingSpringDayIs23HoursAndFallDayIs25Hours() {
    profile("夏令时用户");
    ZoneId zone = ZoneId.of("America/New_York");
    LocalDate spring = LocalDate.of(2026, 3, 8);
    long springStart = day(spring, zone), springEnd = day(spring.plusDays(1), zone);
    assertEquals(23 * 60 * 60 * 1000L, springEnd - springStart);
    repo.saveRecord(completed(springEnd - 1, "trace", 70));
    repo.saveRecord(completed(springEnd, "trace", 80));
    RehabRepository.Stats springStats = repo.getStatsAt(null, 7, spring.plusDays(1), zone);
    assertEquals(1, springStats.days.get(5).count);
    assertEquals(1, springStats.days.get(6).count);
    assertEquals(springStart, springStats.days.get(5).dayStart);
    assertEquals(springEnd, springStats.days.get(6).dayStart);
    LocalDate fall = LocalDate.of(2026, 11, 1);
    long fallStart = day(fall, zone), fallEnd = day(fall.plusDays(1), zone);
    assertEquals(25 * 60 * 60 * 1000L, fallEnd - fallStart);
    repo.saveRecord(completed(fallEnd - 1, "trace", 90));
    RehabRepository.Stats fallStats = repo.getStatsAt(null, 7, fall.plusDays(1), zone);
    assertEquals(1, fallStats.days.get(5).count);
    assertEquals(0, fallStats.days.get(6).count);
    assertNull(fallStats.days.get(6).averageScore);
    assertEquals(
        fallEnd - fallStart, fallStats.days.get(6).dayStart - fallStats.days.get(5).dayStart);
  }

  @Test
  public void versionOneUpgradeKeepsProfilesPlansAndRawSessionDetails() {
    repo.close();
    context.deleteDatabase(RehabDatabase.NAME);
    SQLiteDatabase old =
        context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null);
    old.execSQL(
        "CREATE TABLE profiles (id INTEGER PRIMARY KEY AUTOINCREMENT,nickname TEXT NOT NULL,age"
            + " INTEGER,hand TEXT NOT NULL,goal TEXT NOT NULL,daily_goal INTEGER NOT NULL DEFAULT"
            + " 3,created_at INTEGER NOT NULL)");
    old.execSQL(
        "CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL"
            + " REFERENCES profiles(id) ON DELETE CASCADE,session_id TEXT NOT NULL UNIQUE,type TEXT"
            + " NOT NULL,subtype TEXT NOT NULL,difficulty INTEGER NOT NULL DEFAULT 0,started_at"
            + " INTEGER NOT NULL,ended_at INTEGER NOT NULL,duration_ms INTEGER NOT NULL DEFAULT"
            + " 0,score REAL NOT NULL DEFAULT 0,status TEXT NOT NULL,valid INTEGER NOT NULL DEFAULT"
            + " 0,errors INTEGER NOT NULL DEFAULT 0,successes INTEGER NOT NULL DEFAULT 0,timeouts"
            + " INTEGER NOT NULL DEFAULT 0,details_json TEXT NOT NULL DEFAULT '{}')");
    old.execSQL(
        "CREATE TABLE plans (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL UNIQUE"
            + " REFERENCES profiles(id) ON DELETE CASCADE,daily_goal INTEGER NOT NULL DEFAULT"
            + " 3,hour INTEGER NOT NULL DEFAULT 20,minute INTEGER NOT NULL DEFAULT 0,type TEXT NOT"
            + " NULL DEFAULT 'all',reminder_enabled INTEGER NOT NULL DEFAULT 0)");
    old.execSQL("INSERT INTO profiles VALUES(1,'旧档案',NULL,'right','灵活训练',3,123)");
    old.execSQL(
        "INSERT INTO"
            + " records(user_id,session_id,type,subtype,started_at,ended_at,status,valid,score,details_json)"
            + " VALUES(1,'old-session','trace','line',1000,2000,'completed',1,66,'{\"samples\":[1,2,3]}')");
    old.execSQL("INSERT INTO plans(user_id,daily_goal) VALUES(1,7)");
    old.setVersion(1);
    old.close();
    repo = RehabRepository.get(context);
    assertEquals("旧档案", repo.getProfile().nickname);
    assertEquals(7, repo.getPlan().dailyGoal);
    TrainingRecord preserved = repo.getRecords(null).get(0);
    assertEquals("old-session", preserved.sessionId);
    assertEquals("{\"samples\":[1,2,3]}", repo.getRecord(preserved.id).detailsJson);
    assertEquals(66, preserved.score, .0001);
    assertNull(preserved.coverage);
    assertNull(preserved.averageReactionMs);
    preserved.sessionId = "after-upgrade";
    preserved.accuracy = .75;
    long newId = repo.saveRecord(preserved);
    assertEquals(Double.valueOf(.75), repo.getRecord(newId).accuracy);
    assertEquals(2, repo.getRecords(null).size());
  }

  @Test
  public void filteredQueriesUseSameCriteriaAndStablePagesWithoutOtherUsers() {
    UserProfile first = profile("筛选用户");
    long time = day(LocalDate.now(), ZoneId.systemDefault()) + 12 * 60 * 60 * 1000;
    TrainingRecord older = completed(time, "trace", 70);
    repo.saveRecord(older);
    TrainingRecord newer = completed(time, "trace", 90);
    repo.saveRecord(newer);
    TrainingRecord circle = completed(time + 1, "trace", 99);
    circle.subtype = "circle";
    repo.saveRecord(circle);
    TrainingRecord normal = completed(time + 2, "trace", 98);
    normal.difficulty = 1;
    repo.saveRecord(normal);
    TrainingRecord incomplete = completed(time + 3, "trace", 0);
    incomplete.status = "incomplete";
    // 兼容旧异常记录：虽然有效标记为 true，但未完成的会话仍不能影响均分或推荐。
    incomplete.valid = true;
    incomplete.score = 99;
    repo.saveRecord(incomplete);
    TrainingRecord reaction = completed(time + 4, "reaction", 97);
    reaction.subtype = "random";
    repo.saveRecord(reaction);
    profile("其他档案");
    repo.saveRecord(completed(time + 5, "trace", 100));
    repo.setCurrentProfile(first.id);

    assertEquals(6, repo.recordCount("all", "all", null));
    assertEquals(3, repo.recordCount("trace", "line", 0));
    assertEquals(incomplete.id, repo.getRecords("trace", "line", 0, 1, 0).get(0).id);
    assertEquals(newer.id, repo.getRecords("trace", "line", 0, 1, 1).get(0).id);
    assertEquals(older.id, repo.getRecords("trace", "line", 0, 1, 2).get(0).id);
    assertTrue(repo.getRecords("trace", "line", 0, 1, 3).isEmpty());
    List<TrainingRecord> recent = repo.recentValid("trace", "line", 0, 5);
    assertEquals(2, recent.size());
    assertEquals(newer.id, recent.get(0).id);
    assertEquals("{}", recent.get(0).detailsJson);
    RehabRepository.Stats stats = repo.getStats("trace", "line", 0, 7);
    assertEquals(3, stats.count);
    assertEquals(2, stats.completed);
    assertEquals(3000, stats.durationMs);
    assertEquals(80, stats.averageScore, .0001);
    assertEquals(0, repo.recordCount("trace' OR 1=1 --", null, null));
    assertThrows(IllegalArgumentException.class, () -> repo.getRecords(null, null, null, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> repo.getRecords(null, null, null, 1, -1));
    assertThrows(IllegalArgumentException.class, () -> repo.recordCount(null, null, -1));
  }

  @Test
  public void staleUnsavedRecordAndPlanNeverRebindToAnotherUser() {
    UserProfile first = profile("原档案");
    TrainingRecord unsaved = completed(System.currentTimeMillis(), "trace", 80);
    unsaved.userId = first.id;
    TrainingPlan stale = new TrainingPlan();
    stale.userId = first.id;
    UserProfile current = profile("当前档案");
    assertThrows(IllegalArgumentException.class, () -> repo.saveRecord(unsaved));
    assertThrows(IllegalArgumentException.class, () -> repo.savePlan(stale));
    assertEquals(0, unsaved.id);
    assertEquals(first.id, unsaved.userId);
    assertEquals(0, stale.id);
    assertEquals(first.id, stale.userId);
    assertEquals(current.id, repo.getProfile().id);
    assertEquals(0, repo.recordCount(null, null, null));
    assertNull(repo.getPlan());
    repo.setCurrentProfile(first.id);
    assertTrue(repo.saveRecord(unsaved) > 0);
    assertTrue(repo.savePlan(stale) > 0);
    assertEquals(1, repo.recordCount(null, null, null));
  }

  @Test
  public void sqliteFailuresDoNotPublishSavedIdentityAndSameObjectsCanRetry() {
    assertNull(repo.getProfile()); // Ensure the schema exists before installing test triggers.
    try (SQLiteDatabase raw =
        context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null)) {
      UserProfile p = new UserProfile();
      failWrites(raw, "profiles", "INSERT");
      assertThrows(SQLiteException.class, () -> repo.saveProfile(p));
      assertEquals(0, p.id);
      assertEquals(0, p.createdAt);
      assertNull(repo.getProfile());
      raw.execSQL("DROP TRIGGER fail_write");
      repo.saveProfile(p);

      TrainingRecord record = completed(System.currentTimeMillis(), "trace", 88);
      failWrites(raw, "records", "INSERT");
      assertThrows(SQLiteException.class, () -> repo.saveRecord(record));
      assertEquals(0, record.id);
      assertEquals(0, record.userId);
      assertEquals(0, repo.recordCount(null, null, null));
      raw.execSQL("DROP TRIGGER fail_write");
      long id = repo.saveRecord(record);
      assertEquals(id, repo.saveRecord(record));
      assertEquals(1, repo.recordCount(null, null, null));

      TrainingPlan plan = new TrainingPlan();
      failWrites(raw, "plans", "INSERT");
      assertThrows(SQLiteException.class, () -> repo.savePlan(plan));
      assertEquals(0, plan.id);
      assertEquals(0, plan.userId);
      assertNull(repo.getPlan());
      raw.execSQL("DROP TRIGGER fail_write");
      repo.savePlan(plan);
      failWrites(raw, "plans", "UPDATE");
      plan.dailyGoal = 7;
      assertThrows(SQLiteException.class, () -> repo.savePlan(plan));
      assertEquals(3, repo.getPlan().dailyGoal);
      raw.execSQL("DROP TRIGGER fail_write");
      repo.savePlan(plan);
      assertEquals(7, repo.getPlan().dailyGoal);
    }
  }

  @Test
  public void invalidMetricsAndNegativeCountsCannotPoisonStatistics() {
    profile("边界用户");
    List<Consumer<TrainingRecord>> invalid =
        List.of(
            r -> r.score = Double.NaN,
            r -> r.score = Double.POSITIVE_INFINITY,
            r -> r.score = -1,
            r -> r.score = 100.01,
            r -> r.accuracy = 1.1,
            r -> r.coverage = -.1,
            r -> r.stability = Double.NaN,
            r -> r.averageReactionMs = -1d,
            r -> r.fastestReactionMs = Double.NaN,
            r -> r.averageIntervalMs = Double.POSITIVE_INFINITY,
            r -> r.meanDeviation = -1d,
            r -> r.durationMs = -1,
            r -> r.errors = -1,
            r -> r.difficulty = 3,
            r -> r.status = "unknown");
    for (Consumer<TrainingRecord> mutation : invalid) {
      TrainingRecord record = completed(System.currentTimeMillis(), "trace", 50);
      mutation.accept(record);
      assertThrows(IllegalArgumentException.class, () -> repo.saveRecord(record));
      assertEquals(0, record.id);
      assertEquals(0, record.userId);
    }
    assertEquals(0, repo.getStats(null, 7).count);
    assertNull(repo.getStats(null, 7).averageScore);
    TrainingRecord edge = completed(System.currentTimeMillis(), "trace", 0);
    edge.accuracy = 0d;
    edge.coverage = 1d;
    edge.stability = 0d;
    edge.averageReactionMs = 0d;
    edge.fastestReactionMs = 0d;
    edge.durationMs = 0;
    repo.saveRecord(edge);
    assertEquals(Double.valueOf(0), repo.getRecord(edge.id).accuracy);
    assertEquals(Double.valueOf(1), repo.getRecord(edge.id).coverage);
  }

  @Test
  public void invalidProfileAndPlanLeaveStoredDataAndSelectionIntact() {
    UserProfile saved = profile("保留档案");
    UserProfile invalid = new UserProfile();
    invalid.dailyGoal = 0;
    assertThrows(IllegalArgumentException.class, () -> repo.saveProfile(invalid));
    assertEquals(0, invalid.id);
    assertEquals(0, invalid.createdAt);
    invalid.dailyGoal = 3;
    invalid.age = 0;
    assertThrows(IllegalArgumentException.class, () -> repo.saveProfile(invalid));
    invalid.age = null;
    invalid.nickname = " ";
    assertThrows(IllegalArgumentException.class, () -> repo.saveProfile(invalid));
    assertEquals(saved.id, repo.getProfile().id);
    assertEquals(1, repo.getProfiles().size());
    long createdAt = saved.createdAt;
    saved.createdAt = 0;
    repo.saveProfile(saved);
    assertEquals(createdAt, saved.createdAt);
    TrainingPlan plan = new TrainingPlan();
    repo.savePlan(plan);
    plan.dailyGoal = 21;
    assertThrows(IllegalArgumentException.class, () -> repo.savePlan(plan));
    plan.dailyGoal = 3;
    plan.type = "unexpected";
    assertThrows(IllegalArgumentException.class, () -> repo.savePlan(plan));
    assertEquals("all", repo.getPlan().type);
    assertEquals(3, repo.getPlan().dailyGoal);
  }

  @Test
  public void versionTwoUpgradeAddsTaskIndexWithoutChangingSamplesOrMetrics() {
    UserProfile p = profile("旧版用户");
    TrainingRecord record = completed(System.currentTimeMillis(), "trace", 90);
    record.detailsJson = "{\"samples\":[{\"x\":0.4,\"y\":0.6,\"t\":10}]}";
    record.meanDeviation = .025;
    repo.saveRecord(record);
    repo.close();
    try (SQLiteDatabase raw =
        context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null)) {
      raw.execSQL("DROP INDEX records_user_task_time");
      raw.setVersion(2);
    }
    repo = RehabRepository.get(context);
    assertEquals(p.id, repo.getProfile().id);
    TrainingRecord preserved = repo.getRecord(record.id);
    assertEquals(record.detailsJson, preserved.detailsJson);
    assertEquals(record.meanDeviation, preserved.meanDeviation);
    try (SQLiteDatabase raw =
            context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null);
        Cursor c =
            raw.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND"
                    + " name='records_user_task_time'",
                null)) {
      assertTrue(c.moveToFirst());
      assertEquals(3, raw.getVersion());
    }
  }

  private void failWrites(SQLiteDatabase db, String table, String operation) {
    db.execSQL(
        "CREATE TRIGGER fail_write BEFORE "
            + operation
            + " ON "
            + table
            + " BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END");
  }

  private UserProfile profile(String nickname) {
    UserProfile p = new UserProfile();
    p.nickname = nickname;
    repo.saveProfile(p);
    return p;
  }

  private TrainingRecord completed(long time, String type, double score) {
    TrainingRecord r = new TrainingRecord();
    r.type = type;
    r.startedAt = time;
    r.endedAt = time + 1000;
    r.durationMs = 1000;
    r.score = score;
    r.status = "completed";
    r.valid = true;
    return r;
  }

  private long day(LocalDate date, ZoneId zone) {
    return date.atStartOfDay(zone).toInstant().toEpochMilli();
  }
}
