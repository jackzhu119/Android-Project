package com.fingerrehab.ui;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.*;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.statistics.ChartView;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** 覆盖正常导航、真实数据刷新与用户操作，不把实现细节当作行为断言。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UserFlowTest {
  private Context context;
  private RehabRepository repo;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", 0).edit().clear().commit();
    context.getSharedPreferences("rehab_ui", 0).edit().clear().commit();
    repo = RehabRepository.get(context);
  }

  @After
  public void close() {
    repo.close();
  }

  private UserProfile user() {
    UserProfile p = new UserProfile();
    p.nickname = "测试用户";
    repo.saveProfile(p);
    return p;
  }

  private TrainingRecord record() {
    TrainingRecord r = new TrainingRecord();
    r.startedAt = System.currentTimeMillis();
    r.endedAt = r.startedAt + 5000;
    r.durationMs = 5000;
    r.type = "reaction";
    r.subtype = "random";
    r.score = 82.5;
    r.valid = true;
    r.status = "completed";
    r.accuracy = .9;
    r.averageReactionMs = 420.0;
    r.fastestReactionMs = 200.0;
    r.successes = 8;
    repo.saveRecord(r);
    return r;
  }

  @Test
  public void firstRunOpensGuideAndCreatesPersistentProfile() {
    ProfileActivity a = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    assertNotNull(text(a, "让每一次练习"));
    button(a, "创建我的本地档案").performClick();
    List<EditText> fields = fields(a);
    fields.get(0).setText("小林");
    fields.get(1).setText("");
    fields.get(2).setText("规律练习");
    fields.get(3).setText("3");
    button(a, "保存档案").performClick();
    assertTrue(a.isFinishing());
    repo.close();
    repo = RehabRepository.get(context);
    assertEquals("小林", repo.getProfile().nickname);
    assertNull(repo.getProfile().age);
  }

  @Test
  public void invalidProfileInputDoesNotCreateData() {
    ProfileActivity a = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    button(a, "创建我的本地档案").performClick();
    button(a, "保存档案").performClick();
    assertNull(repo.getProfile());
    assertNotNull(fields(a).get(0).getError());
    fields(a).get(0).setText("小林");
    fields(a).get(1).setText("999");
    button(a, "保存档案").performClick();
    assertNull(repo.getProfile());
    assertNotNull(fields(a).get(1).getError());
  }

  @Test
  public void unsavedProfileDraftSurvivesRecreation() {
    user();
    ActivityController<ProfileActivity> c =
        Robolectric.buildActivity(ProfileActivity.class).setup();
    fields(c.get()).get(0).setText("草稿昵称");
    fields(c.get()).get(3).setText("5");
    Bundle state = new Bundle();
    c.saveInstanceState(state).pause().stop().destroy();
    ProfileActivity a =
        Robolectric.buildActivity(ProfileActivity.class).create(state).start().resume().get();
    assertEquals("草稿昵称", fields(a).get(0).getText().toString());
    assertEquals("5", fields(a).get(3).getText().toString());
  }

  @Test
  public void allFiveNavigationPagesAreReachableAndEmptyDataIsHonest() {
    user();
    MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
    LinearLayout nav = a.findViewById(R.id.bottom_nav);
    assertNotNull(text(a, "还没有训练记录"));
    nav.getChildAt(1).performClick();
    assertNotNull(button(a, "开始直线描绘"));
    assertNotNull(button(a, "开始随机目标"));
    assertNotNull(button(a, "连续点击"));
    nav.getChildAt(2).performClick();
    assertNotNull(text(a, "暂无可展示的训练数据"));
    assertNotNull(text(a, "有效记录不足2次"));
    nav.getChildAt(3).performClick();
    assertNotNull(button(a, "创建每日训练计划"));
    nav.getChildAt(4).performClick();
    assertNotNull(button(a, "编辑个人档案"));
    a.onBackPressed();
    assertNotNull(text(a, "今日训练目标"));
  }

  @Test
  public void dashboardUsesSavedRecordAndPlanProgress() {
    user();
    TrainingRecord r = record();
    TrainingPlan p = new TrainingPlan();
    p.dailyGoal = 4;
    p.type = "reaction";
    repo.savePlan(p);
    MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
    assertNotNull(text(a, "1 / 4 次"));
    assertNotNull(text(a, "82.5 分"));
    assertNull(text(a, "还没有训练记录"));
  }

  @Test
  public void incompleteLatestAttemptIsNotPresentedAsAnEffectiveScore() {
    user();
    TrainingRecord r = new TrainingRecord();
    r.type = "trace";
    r.subtype = "line";
    r.startedAt = System.currentTimeMillis();
    r.endedAt = r.startedAt + 500;
    r.durationMs = 500;
    r.score = 88;
    r.status = "incomplete";
    repo.saveRecord(r);
    MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
    assertNotNull(text(a, "未计有效成绩"));
    assertNull(text(a, "88.0 分"));
    assertNotNull(text(a, "1 次"));
  }

  @Test
  public void statisticsSeparateTaskAndDifficultyAndRestoreSelectedScope() {
    user();
    TrainingRecord line = scoredRecord("line", 0, 61);
    scoredRecord("circle", 0, 92);
    scoredRecord("line", 2, 97);
    MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
    ((LinearLayout) a.findViewById(R.id.bottom_nav)).getChildAt(2).performClick();
    assertNotNull(text(a, "选择同一任务和难度后查看成绩趋势"));
    assertNull(findChart(root(a), "历史成绩折线图"));
    selectFilter(a, "训练类型", 1);
    ChartView chart = findChart(root(a), "历史成绩折线图");
    assertNotNull(chart);
    String description = chart.getContentDescription().toString();
    assertTrue(description.contains("61.0"));
    assertTrue(description.contains("#" + line.id));
    assertFalse(description.contains("92.0"));
    assertFalse(description.contains("97.0"));
    selectFilter(a, "训练任务", 2);
    description = findChart(root(a), "历史成绩折线图").getContentDescription().toString();
    assertTrue(description.contains("92.0"));
    assertFalse(description.contains("61.0"));
    selectFilter(a, "训练任务", 1);
    selectFilter(a, "训练难度", 3);
    description = findChart(root(a), "历史成绩折线图").getContentDescription().toString();
    assertTrue(description.contains("97.0"));
    assertFalse(description.contains("61.0"));
    Bundle saved = new Bundle();
    a.onSaveInstanceState(saved);
    a = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().get();
    assertEquals(1, findSpinner(root(a), "训练类型").getSelectedItemPosition());
    assertEquals(1, findSpinner(root(a), "训练任务").getSelectedItemPosition());
    assertEquals(3, findSpinner(root(a), "训练难度").getSelectedItemPosition());
    assertTrue(findChart(root(a), "历史成绩折线图").getContentDescription().toString().contains("97.0"));
  }

  @Test
  public void chartPreservesRealZeroAndTreatsCorruptAndMissingValuesAsAbsent() {
    List<String> labels = new ArrayList<>(Arrays.asList("同日 #1", "同日 #2", "同日 #3", "同日 #4"));
    List<Double> values = new ArrayList<>(Arrays.asList(0.0, Double.NaN, 101.0));
    ChartView chart = new ChartView(context, labels, values, true, 100);
    labels.set(0, "被修改");
    values.set(0, 88.0);
    String description = chart.getContentDescription().toString();
    assertTrue(description.contains("同日 #1：0.0"));
    assertTrue(description.contains("同日 #2：无有效数据"));
    assertTrue(description.contains("同日 #3：无有效数据"));
    assertTrue(description.contains("同日 #4：无有效数据"));
    assertFalse(description.contains("NaN"));
    chart.measure(
        View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    chart.layout(0, 0, chart.getMeasuredWidth(), chart.getMeasuredHeight());
    chart.draw(
        new Canvas(
            Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888)));
    assertTrue(
        chart.onKeyDown(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
    assertEquals("图表，暂无数据", new ChartView(context).getContentDescription());
  }

  @Test
  public void tabsAndReturningFromAnotherScreenPreserveScrollAndRefreshSavedData() {
    user();
    ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity a = controller.get();
    layoutMain(a);
    ScrollView scroll = a.findViewById(R.id.page_scroll);
    scroll.scrollTo(0, 320);
    int homePosition = scroll.getScrollY();
    assertTrue(homePosition > 0);
    LinearLayout nav = a.findViewById(R.id.bottom_nav);
    nav.getChildAt(1).performClick();
    layoutMain(a);
    assertEquals(0, scroll.getScrollY());
    scroll.scrollTo(0, 200);
    int trainingPosition = scroll.getScrollY();
    assertTrue(trainingPosition > 0);
    nav.getChildAt(0).performClick();
    layoutMain(a);
    assertEquals(homePosition, scroll.getScrollY());
    controller.pause();
    record();
    controller.resume();
    layoutMain(a);
    assertEquals(homePosition, scroll.getScrollY());
    assertNotNull(text(a, "82.5 分"));
    nav.getChildAt(1).performClick();
    layoutMain(a);
    assertEquals(trainingPosition, scroll.getScrollY());
    Bundle saved = new Bundle();
    controller.saveInstanceState(saved).pause().stop().destroy();
    a = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().get();
    layoutMain(a);
    assertNotNull(text(a, "指尖训练室"));
    assertEquals(trainingPosition, ((ScrollView) a.findViewById(R.id.page_scroll)).getScrollY());
    ((LinearLayout) a.findViewById(R.id.bottom_nav)).getChildAt(0).performClick();
    layoutMain(a);
    assertEquals(homePosition, ((ScrollView) a.findViewById(R.id.page_scroll)).getScrollY());
  }

  @Test
  public void trainingDifficultyAndStatisticsScopeSurviveNormalRelaunch() {
    user();
    ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity a = controller.get();
    ((LinearLayout) a.findViewById(R.id.bottom_nav)).getChildAt(1).performClick();
    selectFilter(a, "本次训练难度", 2);
    ((LinearLayout) a.findViewById(R.id.bottom_nav)).getChildAt(2).performClick();
    selectFilter(a, "训练类型", 3);
    selectFilter(a, "训练任务", 2);
    selectFilter(a, "训练难度", 2);
    controller.pause().stop().destroy();
    a = Robolectric.buildActivity(MainActivity.class).setup().get();
    ((LinearLayout) a.findViewById(R.id.bottom_nav)).getChildAt(1).performClick();
    assertEquals(2, findSpinner(root(a), "本次训练难度").getSelectedItemPosition());
    ((LinearLayout) a.findViewById(R.id.bottom_nav)).getChildAt(2).performClick();
    assertEquals(3, findSpinner(root(a), "训练类型").getSelectedItemPosition());
    assertEquals(2, findSpinner(root(a), "训练任务").getSelectedItemPosition());
    assertEquals(2, findSpinner(root(a), "训练难度").getSelectedItemPosition());
  }

  @Test
  public void notificationDestinationDoesNotOverrideThePageChosenBeforeRecreation() {
    user();
    Intent intent = new Intent(context, MainActivity.class).putExtra("destination", "plan");
    ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class, intent).setup();
    assertNotNull(button(controller.get(), "创建每日训练计划"));
    ((LinearLayout) controller.get().findViewById(R.id.bottom_nav)).getChildAt(1).performClick();
    Bundle saved = new Bundle();
    controller.saveInstanceState(saved).pause().stop().destroy();
    MainActivity restored =
        Robolectric.buildActivity(MainActivity.class, intent).create(saved).start().resume().get();
    assertNotNull(text(restored, "指尖训练室"));
  }

  @Test
  public void accessibilityActionsReadMissingPointsAndKeyboardCanLeaveChartEnds() {
    ChartView chart =
        new ChartView(
            context,
            Arrays.asList("第一次", "第二次", "第三次"),
            Arrays.asList(0.0, null, Double.NaN),
            false,
            100);
    assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
    assertTrue(selectedChartPoint(chart).contains("第1/3项，第一次：0.0"));
    assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
    assertTrue(selectedChartPoint(chart).contains("第2/3项，第二次：无有效数据"));
    KeyEvent right = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
    assertTrue(chart.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, right));
    assertTrue(selectedChartPoint(chart).contains("第三次：无有效数据"));
    assertFalse(chart.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, right));
    assertFalse(
        chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
    assertTrue(
        chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
    assertTrue(
        chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
    assertFalse(
        chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
    MotionEvent down = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 10, 10, 0);
    MotionEvent cancel = MotionEvent.obtain(1, 2, MotionEvent.ACTION_CANCEL, 10, 10, 0);
    MotionEvent up = MotionEvent.obtain(1, 3, MotionEvent.ACTION_UP, 10, 10, 0);
    chart.onTouchEvent(down);
    chart.onTouchEvent(cancel);
    assertFalse(chart.isPressed());
    assertFalse(chart.onTouchEvent(up));
    down.recycle();
    cancel.recycle();
    up.recycle();
    assertTrue(selectedChartPoint(chart).contains("第一次：0.0"));
    ChartView empty = new ChartView(context);
    assertFalse(empty.isClickable());
    assertFalse(empty.performClick());
    assertFalse(
        empty.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
  }

  private static String selectedChartPoint(ChartView chart) {
    AccessibilityNodeInfo node = chart.createAccessibilityNodeInfo();
    String text = String.valueOf(node.getText());
    node.recycle();
    return text;
  }

  private static void layoutMain(MainActivity activity) {
    View root = activity.findViewById(R.id.root);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 640);
    activity.findViewById(R.id.page_scroll).getViewTreeObserver().dispatchOnGlobalLayout();
  }

  private TrainingRecord scoredRecord(String subtype, int difficulty, double score) {
    TrainingRecord r = new TrainingRecord();
    r.startedAt = System.currentTimeMillis();
    r.endedAt = r.startedAt + 5000;
    r.durationMs = 5000;
    r.type = "trace";
    r.subtype = subtype;
    r.difficulty = difficulty;
    r.score = score;
    r.valid = true;
    r.status = "completed";
    repo.saveRecord(r);
    return r;
  }

  private static void selectFilter(Activity activity, String label, int position) {
    Spinner spinner = findSpinner(root(activity), label);
    assertNotNull(spinner);
    spinner.setSelection(position);
    spinner.getOnItemSelectedListener().onItemSelected(spinner, null, position, position);
  }

  private static Spinner findSpinner(View view, String description) {
    if (view instanceof Spinner && description.contentEquals(view.getContentDescription()))
      return (Spinner) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Spinner result = findSpinner(group.getChildAt(i), description);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static ChartView findChart(View view, String title) {
    if (view instanceof ChartView && view.getContentDescription().toString().startsWith(title))
      return (ChartView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        ChartView result = findChart(group.getChildAt(i), title);
        if (result != null) return result;
      }
    }
    return null;
  }

  @Test
  public void historyDeletionRequiresUserConfirmation() {
    user();
    TrainingRecord r = record();
    HistoryActivity a = Robolectric.buildActivity(HistoryActivity.class).setup().get();
    button(a, "删除这条记录").performClick();
    assertNotNull(repo.getRecord(r.id));
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNotNull(repo.getRecord(r.id));
    button(a, "删除这条记录").performClick();
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNull(repo.getRecord(r.id));
    assertNotNull(text(a, "还没有这类训练记录"));
  }

  @Test
  public void resultDisplaysMeasuredMetricsAndUnavailableMetricsStayAbsent() {
    user();
    TrainingRecord r = record();
    Intent i = new Intent(context, ResultActivity.class).putExtra("record_id", r.id);
    ResultActivity a = Robolectric.buildActivity(ResultActivity.class, i).setup().get();
    assertNotNull(text(a, "420.0 ms"));
    assertNotNull(text(a, "200.0 ms"));
    assertNull(text(a, "平均轨迹偏差"));
    assertNotNull(button(a, "再练一次 · 简单"));
  }

  @Test
  public void deletedRecordReportShowsSafeEmptyState() {
    user();
    ResultActivity a =
        Robolectric.buildActivity(
                ResultActivity.class,
                new Intent(context, ResultActivity.class).putExtra("record_id", 999L))
            .setup()
            .get();
    assertNotNull(text(a, "记录不存在"));
  }

  private static View root(Activity a) {
    return a.findViewById(android.R.id.content);
  }

  private static TextView text(Activity a, String s) {
    return findText(root(a), s, false);
  }

  private static Button button(Activity a, String s) {
    TextView t = findText(root(a), s, true);
    assertNotNull("按钮不存在: " + s, t);
    return (Button) t;
  }

  private static TextView findText(View v, String s, boolean button) {
    if (v instanceof TextView && (!button || v instanceof Button)) {
      String value = ((TextView) v).getText().toString();
      if (button ? value.equals(s) : value.contains(s)) return (TextView) v;
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        TextView t = findText(g.getChildAt(i), s, button);
        if (t != null) return t;
      }
    }
    return null;
  }

  private static List<EditText> fields(Activity a) {
    List<EditText> r = new ArrayList<>();
    collect(root(a), r);
    return r;
  }

  private static void collect(View v, List<EditText> r) {
    if (v instanceof EditText) r.add((EditText) v);
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), r);
    }
  }
}
