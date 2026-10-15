package com.fingerrehab.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.*;
import com.fingerrehab.repository.RehabRepository;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ResultDetailsTest {
  private Context context;
  private RehabRepository repo;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", 0).edit().clear().commit();
    repo = RehabRepository.get(context);
    repo.saveProfile(new UserProfile());
  }

  @After
  public void close() {
    repo.close();
  }

  private TrainingRecord record(String details) {
    TrainingRecord r = new TrainingRecord();
    r.type = "coordination";
    r.subtype = "tapping";
    r.startedAt = System.currentTimeMillis();
    r.endedAt = r.startedAt + 5000;
    r.durationMs = 5000;
    r.score = 82;
    r.valid = true;
    r.status = "completed";
    r.detailsJson = details;
    repo.saveRecord(r);
    return r;
  }

  private Intent intent(long id) {
    return new Intent(context, ResultActivity.class).putExtra("record_id", id);
  }

  @Test
  public void everySavedEventCanBeReadAndPageSurvivesRecreation() throws Exception {
    JSONArray events = new JSONArray();
    for (int i = 0; i < 125; i++)
      events.put(new JSONObject().put("outcome", "hit").put("timeMs", i * 500));
    TrainingRecord r = record(new JSONObject().put("events", events).toString());
    var controller = Robolectric.buildActivity(ResultActivity.class, intent(r.id)).setup();
    ResultActivity a = controller.get();
    assertNotNull(text(a, "事件 1～30 / 共 125 条"));
    for (int i = 0; i < 4; i++) button(a, "下一组事件").performClick();
    assertNotNull(text(a, "125. 成功"));
    assertFalse(button(a, "下一组事件").isEnabled());
    layout(a);
    ((ScrollView) a.findViewById(R.id.screen_scroll)).scrollTo(0, 500);
    Bundle state = new Bundle();
    controller.saveInstanceState(state).pause().stop().destroy();
    try (var restored =
        Robolectric.buildActivity(ResultActivity.class, intent(r.id))
            .create(state)
            .start()
            .resume()
            .visible()) {
      layout(restored.get());
      assertEquals(
          500, ((ScrollView) restored.get().findViewById(R.id.screen_scroll)).getScrollY());
      assertNotNull(text(restored.get(), "事件 121～125 / 共 125 条"));
      button(restored.get(), "上一组事件").performClick();
      assertNotNull(text(restored.get(), "事件 91～120 / 共 125 条"));
    }
  }

  @Test
  public void malformedEventsAndTimesDoNotBecomeFalseErrorsOrZeroTimes() {
    TrainingRecord r =
        record(
            "{\"events\":[null,{\"outcome\":\"future\",\"timeMs\":\"unknown\"},"
                + "{\"outcome\":\"hit\",\"timeMs\":-1},{\"outcome\":\"miss\",\"timeMs\":1500}]}");
    try (var controller = Robolectric.buildActivity(ResultActivity.class, intent(r.id)).setup()) {
      ResultActivity a = controller.get();
      assertNotNull(text(a, "1. 此条事件暂不可读"));
      assertNotNull(text(a, "2. 未识别事件"));
      assertNotNull(text(a, "4. 点击错误 · 训练开始后 1.5 秒"));
      assertNull(text(a, "训练开始后 0.0 秒"));
      assertEquals(
          "综合评分 82.0 分", score(a.findViewById(android.R.id.content)).getContentDescription());
    }
  }

  @Test
  public void malformedDetailsPreserveReportSummary() {
    TrainingRecord r = record("not-json");
    try (var controller = Robolectric.buildActivity(ResultActivity.class, intent(r.id)).setup()) {
      assertNotNull(text(controller.get(), "原始详情暂不可读"));
      assertEquals(
          "综合评分 82.0 分",
          score(controller.get().findViewById(android.R.id.content)).getContentDescription());
      assertNotNull(button(controller.get(), "再练一次 · 简单"));
    }
  }

  @Test
  public void invalidLegacyScoreIsExcludedInBothHistoryAndReport() {
    TrainingRecord r = record("{}");
    try (SQLiteDatabase db =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(RehabDatabase.NAME).getPath(),
            null,
            SQLiteDatabase.OPEN_READWRITE)) {
      db.execSQL("UPDATE records SET score=150 WHERE id=?", new Object[] {r.id});
    }
    try (var history = Robolectric.buildActivity(HistoryActivity.class).setup();
        var result = Robolectric.buildActivity(ResultActivity.class, intent(r.id)).setup()) {
      assertNotNull(text(history.get(), "不计入有效成绩趋势"));
      assertNull(text(history.get(), "150.0 分"));
      assertNotNull(text(result.get(), "未满足有效训练条件"));
      assertNotNull(text(result.get(), "成绩不计入有效平均分"));
      assertNull(text(result.get(), "有效工程评分"));
    }
  }

  @Test
  public void filteredSamplesDoNotClaimTheStorageLimitWasReached() {
    TrainingRecord r = record("{\"samples\":[],\"observedSamples\":3}");
    try (SQLiteDatabase db =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(RehabDatabase.NAME).getPath(),
            null,
            SQLiteDatabase.OPEN_READWRITE)) {
      db.execSQL("UPDATE records SET type='trace',subtype='line' WHERE id=?", new Object[] {r.id});
    }
    try (var result = Robolectric.buildActivity(ResultActivity.class, intent(r.id)).setup()) {
      assertNotNull(text(result.get(), "无效、过密或超限采样已过滤或压缩"));
      assertNull(text(result.get(), "超出上限的采样已压缩"));
    }
  }

  private static ScoreView score(View v) {
    if (v instanceof ScoreView) return (ScoreView) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        ScoreView found = score(g.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void layout(Activity a) {
    View root = a.findViewById(R.id.root);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 640);
    root.getViewTreeObserver().dispatchOnGlobalLayout();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private static Button button(Activity a, String value) {
    TextView text = find(a.findViewById(android.R.id.content), value, true);
    assertNotNull(value, text);
    return (Button) text;
  }

  private static TextView text(Activity a, String value) {
    return find(a.findViewById(android.R.id.content), value, false);
  }

  private static TextView find(View view, String value, boolean button) {
    if (view instanceof TextView && (!button || view instanceof Button)) {
      String actual = ((TextView) view).getText().toString();
      if (button ? actual.equals(value) : actual.contains(value)) return (TextView) view;
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = find(group.getChildAt(i), value, button);
        if (found != null) return found;
      }
    }
    return null;
  }
}
