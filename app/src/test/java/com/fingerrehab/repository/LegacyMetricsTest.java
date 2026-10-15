package com.fingerrehab.repository;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.TrainingRecord;
import com.fingerrehab.model.UserProfile;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 直接插入旧数据库可包含、但新写入校验已拒绝的内容，验证读取兼容而非静默改写。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LegacyMetricsTest {
  private static final String DETAILS =
      "{\"legacy\":true,\"samples\":[{\"x\":0.2,\"y\":0.3,\"t\":17}]}";
  private Context context;
  private RehabRepository repo;
  private TimeZone originalZone;
  private long userId, time;
  private int sessionNumber;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", Context.MODE_PRIVATE).edit().clear().commit();
    originalZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    repo = RehabRepository.get(context);
    userId = repo.saveProfile(new UserProfile());
    time = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
  }

  @After
  public void tearDown() {
    repo.close();
    context.deleteDatabase(RehabDatabase.NAME);
    TimeZone.setDefault(originalZone);
  }

  @Test
  public void legacyInvalidScoresKeepHistoryButNeverAffectCompletionOrAverages() {
    long highScoreId, textScoreId = 0, zeroId, hundredId;
    try (SQLiteDatabase raw =
        context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null)) {
      highScoreId = insertLegacy(raw, 150d, true, "completed", DETAILS);
      for (Object score :
          new Object[] {
            -1d, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "NaN", "not-a-number"
          }) {
        long id = insertLegacy(raw, score, true, "completed", DETAILS);
        if ("NaN".equals(score)) textScoreId = id;
      }
      insertLegacy(raw, 80d, false, "completed", DETAILS);
      insertLegacy(raw, 99d, true, "incomplete", DETAILS);
      insertLegacy(raw, 95d, true, "aborted", DETAILS);
      zeroId = insertLegacy(raw, 0d, true, "completed", DETAILS);
      hundredId = insertLegacy(raw, 100d, true, "completed", DETAILS);
    }

    RehabRepository.Stats stats = repo.getStats("trace", "line", 0, 7);
    assertEquals(11, stats.count);
    assertEquals(11 * 1250L, stats.durationMs);
    assertEquals(2, stats.completed);
    assertEquals(50, stats.averageScore, 0);
    assertEquals(100, stats.bestScore, 0);
    assertEquals(11, stats.days.get(6).count);
    assertEquals(50, stats.days.get(6).averageScore, 0);
    assertEquals(2, repo.completedToday("trace"));
    assertEquals(2, repo.recentValid("trace", "line", 0, 20).size());
    assertEquals(2, repo.recentValid("trace", 0, 20).size());
    int effective = 0;
    for (TrainingRecord record : repo.getRecords(null)) {
      if (record.isEffectiveCompletion()) effective++;
    }
    assertEquals(2, effective);
    assertEquals(11, repo.recordCount(null, null, null));
    assertEquals(150, repo.getRecord(highScoreId).score, 0);
    assertEquals(DETAILS, repo.getRecord(highScoreId).detailsJson);
    assertTrue(Double.isNaN(repo.getRecord(textScoreId).score));
    assertFalse(repo.getRecord(textScoreId).isEffectiveCompletion());

    repo.deleteRecord(zeroId);
    repo.deleteRecord(hundredId);
    RehabRepository.Stats invalidOnly = repo.getStats(null, 7);
    assertEquals(9, invalidOnly.count);
    assertEquals(9 * 1250L, invalidOnly.durationMs);
    assertEquals(0, invalidOnly.completed);
    assertNull(invalidOnly.averageScore);
    assertNull(invalidOnly.bestScore);
    assertNull(invalidOnly.days.get(6).averageScore);
    assertEquals(0, repo.completedToday(null));
    assertTrue(repo.recentValid(null, null, null, 20).isEmpty());
    // 统计与模型映射没有把旧库中的高分改为 100，也没有把文本坏值改为 0。
    try (SQLiteDatabase raw =
            context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null);
        Cursor c =
            raw.rawQuery(
                "SELECT score,details_json FROM records WHERE id=?",
                new String[] {String.valueOf(highScoreId)})) {
      assertTrue(c.moveToFirst());
      assertEquals(150, c.getDouble(0), 0);
      assertEquals(DETAILS, c.getString(1));
    }
    try (SQLiteDatabase raw =
            context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null);
        Cursor c =
            raw.rawQuery(
                "SELECT score FROM records WHERE id=?",
                new String[] {String.valueOf(textScoreId)})) {
      assertTrue(c.moveToFirst());
      assertEquals("NaN", c.getString(0));
    }
  }

  @Test
  public void threeMiBLegacyDetailsLoadInChunksWithUnicodeIntact() {
    String details = "{\"note\":\"" + "x".repeat(3 * 1024 * 1024) + "康复👋\",\"samples\":[]}";
    long id;
    try (SQLiteDatabase raw =
        context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null)) {
      id = insertLegacy(raw, 80d, true, "completed", details);
    }
    TrainingRecord record = repo.getRecord(id);
    assertFalse(record.detailsUnavailable);
    assertEquals(details, record.detailsJson);
    assertTrue(record.isEffectiveCompletion());
    assertEquals(80, record.score, 0);
    assertEquals("{}", repo.getRecords(null).get(0).detailsJson);
    assertEquals(1, repo.getStats(null, 7).completed);
  }

  @Test
  public void oversizedLegacyDetailsKeepAggregatesAndSignalUnavailableWithoutRewriting() {
    String details =
        "{\"note\":\"" + "x".repeat(RehabRepository.MAX_DETAILS_CHARACTERS + 1) + "\"}";
    long id;
    try (SQLiteDatabase raw =
        context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null)) {
      id = insertLegacy(raw, 80d, true, "completed", details);
    }
    TrainingRecord record = repo.getRecord(id);
    assertNotNull(record);
    assertTrue(record.detailsUnavailable);
    assertEquals("{}", record.detailsJson);
    assertEquals(80, record.score, 0);
    assertEquals(1250, record.durationMs);
    assertTrue(record.isEffectiveCompletion());
    assertEquals(1, repo.completedToday(null));
    assertEquals(1, repo.getStats(null, 7).count);
    try (SQLiteDatabase raw =
            context.openOrCreateDatabase(RehabDatabase.NAME, Context.MODE_PRIVATE, null);
        Cursor c =
            raw.rawQuery(
                "SELECT length(details_json) FROM records WHERE id=?",
                new String[] {String.valueOf(id)})) {
      assertTrue(c.moveToFirst());
      assertEquals(details.length(), c.getLong(0));
    }
  }

  private long insertLegacy(
      SQLiteDatabase db, Object score, boolean valid, String status, String details) {
    ContentValues values = new ContentValues();
    values.put("user_id", userId);
    values.put("session_id", "legacy-" + sessionNumber++);
    values.put("type", "trace");
    values.put("subtype", "line");
    values.put("started_at", time);
    values.put("ended_at", time + 1250);
    values.put("duration_ms", 1250);
    values.put("status", status);
    values.put("valid", valid ? 1 : 0);
    if (score instanceof Number) values.put("score", ((Number) score).doubleValue());
    else values.put("score", score.toString());
    values.put("details_json", details);
    return db.insertOrThrow("records", null, values);
  }
}
