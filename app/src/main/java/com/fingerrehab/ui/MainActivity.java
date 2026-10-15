package com.fingerrehab.ui;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.algorithm.DifficultyRecommender;
import com.fingerrehab.model.*;
import com.fingerrehab.notification.ReminderScheduler;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.repository.RehabRepository.*;
import com.fingerrehab.statistics.ChartView;
import com.fingerrehab.training.TrainingActivity;
import com.fingerrehab.utils.Labels;
import java.text.SimpleDateFormat;
import java.util.*;

/** 五个主要页面的导航壳；训练、评分和数据库均在独立模块中。 */
public class MainActivity extends Activity {
  private RehabRepository repo;
  private LinearLayout content, nav;
  private ScrollView scroll;
  private SharedPreferences selections;
  private int page = 0, difficulty = 0, filter = 0, subtypeFilter = 0, statsDifficulty = 1;
  private int renderedPage = -1, renderVersion;
  private final int[] scrollPositions = new int[5];
  private boolean openingProfile, refreshOnResume, scrollRestorePending;
  private final String[] types = {null, "trace", "reaction", "coordination"};

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    repo = RehabRepository.get(this);
    setContentView(R.layout.activity_main);
    Ui.insets(findViewById(R.id.root));
    content = findViewById(R.id.page_content);
    nav = findViewById(R.id.bottom_nav);
    scroll = findViewById(R.id.page_scroll);
    // 由每个标签自己的位置恢复滚动，避免单个 ScrollView 的自动状态覆盖其他标签。
    scroll.setSaveEnabled(false);
    selections = getSharedPreferences("rehab_ui", MODE_PRIVATE);
    difficulty = selections.getInt("difficulty", 0);
    filter = selections.getInt("filter", 0);
    subtypeFilter = selections.getInt("subtype_filter", 0);
    statsDifficulty = selections.getInt("stats_difficulty", 1);
    if (saved != null) {
      page = saved.getInt("page");
      difficulty = saved.getInt("difficulty");
      filter = saved.getInt("filter");
      subtypeFilter = saved.getInt("subtype_filter");
      statsDifficulty = saved.getInt("stats_difficulty", 1);
      int[] positions = saved.getIntArray("scroll_positions");
      if (positions != null)
        for (int i = 0; i < Math.min(positions.length, scrollPositions.length); i++)
          scrollPositions[i] = Math.max(0, positions[i]);
    }
    page = Math.max(0, Math.min(4, page));
    difficulty = Math.max(0, Math.min(2, difficulty));
    filter = Math.max(0, Math.min(types.length - 1, filter));
    statsDifficulty = Math.max(0, Math.min(3, statsDifficulty));
    if (saved == null && "plan".equals(getIntent().getStringExtra("destination"))) page = 3;
    buildNav();
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (repo.getProfile() == null) {
      if (!openingProfile) {
        openingProfile = true;
        startActivity(new Intent(this, ProfileActivity.class));
      }
    } else {
      openingProfile = false;
      if (refreshOnResume) {
        refreshOnResume = false;
        render();
      }
      ReminderScheduler.sync(this);
    }
  }

  @Override
  protected void onPause() {
    rememberScroll();
    persistSelections();
    refreshOnResume = true;
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if ("plan".equals(intent.getStringExtra("destination"))) {
      navigateTo(3);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    rememberScroll();
    super.onSaveInstanceState(out);
    out.putInt("page", page);
    out.putInt("difficulty", difficulty);
    out.putInt("filter", filter);
    out.putInt("subtype_filter", subtypeFilter);
    out.putInt("stats_difficulty", statsDifficulty);
    out.putIntArray("scroll_positions", scrollPositions.clone());
  }

  @Override
  public void onBackPressed() {
    if (page != 0) {
      navigateTo(0);
    } else super.onBackPressed();
  }

  private void buildNav() {
    String[] names = {"首页", "训练", "数据", "计划", "我的"};
    for (int i = 0; i < 5; i++) {
      final int index = i;
      LinearLayout tab = Ui.column(this);
      tab.setGravity(Gravity.CENTER);
      tab.setMinimumHeight(Ui.dp(this, 48));
      tab.setContentDescription(names[i]);
      tab.setFocusable(true);
      StateListDrawable states = new StateListDrawable();
      states.addState(new int[] {android.R.attr.state_focused}, Ui.shape(0xFFDCEAFF, 0, this));
      states.addState(new int[] {android.R.attr.state_selected}, Ui.shape(0xFFEAF1FF, 0, this));
      states.addState(new int[] {}, Ui.shape(Ui.WHITE, 0, this));
      tab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33246BFD), states, null));
      NavigationIconView icon = new NavigationIconView(this, i);
      TextView label = Ui.text(this, names[i], 11, Ui.MUTED);
      label.setGravity(Gravity.CENTER);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tab.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
      tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
      nav.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
      tab.setOnClickListener(v -> navigateTo(index));
    }
  }

  private void render() {
    if (content == null) return;
    rememberScroll();
    content.removeAllViews();
    for (int i = 0; i < nav.getChildCount(); i++) {
      LinearLayout tab = (LinearLayout) nav.getChildAt(i);
      tab.setSelected(i == page);
      tab.getChildAt(0).setSelected(i == page);
      tab.getChildAt(0).invalidate();
      ((TextView) tab.getChildAt(1)).setTextColor(i == page ? Ui.BLUE : Ui.MUTED);
    }
    UserProfile profile = repo.getProfile();
    if (profile == null) {
      Ui.title(content, "指尖康复", "创建个人档案后开始记录");
      restoreScrollAfterLayout();
      return;
    }
    switch (page) {
      case 1:
        training();
        break;
      case 2:
        data();
        break;
      case 3:
        plan();
        break;
      case 4:
        mine(profile);
        break;
      default:
        home(profile);
    }
    restoreScrollAfterLayout();
  }

  private void navigateTo(int destination) {
    if (page == destination) return;
    page = destination;
    render();
  }

  private void rememberScroll() {
    if (scroll != null && renderedPage >= 0 && !scrollRestorePending)
      scrollPositions[renderedPage] = Math.max(0, scroll.getScrollY());
  }

  private void restoreScrollAfterLayout() {
    renderedPage = page;
    scrollRestorePending = true;
    final int version = ++renderVersion, destination = page;
    scroll
        .getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                if (scroll.getViewTreeObserver().isAlive())
                  scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (version != renderVersion || page != destination) return;
                scroll.scrollTo(0, scrollPositions[destination]);
                scrollRestorePending = false;
              }
            });
  }

  private void persistSelections() {
    if (selections == null) return;
    selections
        .edit()
        .putInt("difficulty", difficulty)
        .putInt("filter", filter)
        .putInt("subtype_filter", subtypeFilter)
        .putInt("stats_difficulty", statsDifficulty)
        .apply();
  }

  private void heading(String title, String subtitle) {
    Ui.title(content, title, subtitle);
  }

  private void section(String title) {
    Ui.gap(content, 6);
    Ui.add(content, Ui.bold(this, title, 19, Ui.INK));
    Ui.gap(content, 12);
  }

  private void home(UserProfile user) {
    String date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date());
    heading("你好，" + user.nickname, "今天也为自己留一点练习时间 · " + date);
    TrainingPlan plan = repo.getPlan();
    int target = plan == null ? user.dailyGoal : plan.dailyGoal;
    int done = repo.completedToday(plan == null ? null : plan.type);
    LinearLayout hero = Ui.card(this);
    GradientDrawable gradient =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF246BFD, 0xFF3985F7});
    gradient.setCornerRadius(Ui.dp(this, 24));
    hero.setBackground(gradient);
    Ui.add(
        hero,
        Ui.text(
            this, "今日训练目标" + (plan == null ? "" : " · " + Labels.type(plan.type)), 14, 0xFFDCEAFF));
    Ui.gap(hero, 10);
    Ui.add(hero, Ui.bold(this, done + " / " + target + " 次", 34, Ui.WHITE));
    Ui.gap(hero, 10);
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgress(Math.min(100, Math.round(done * 100f / target)));
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF77E6D4));
    Ui.add(hero, progress);
    Ui.gap(hero, 8);
    Ui.add(
        hero,
        Ui.text(
            this,
            done >= target
                ? "今日目标已完成，记得适度休息"
                : "已完成 " + Labels.number(Math.min(100, done * 100.0 / target)) + "% · 按自己的节奏继续",
            13,
            Ui.WHITE));
    Ui.gap(hero, 18);
    Button start = Ui.button(this, "开始今日训练  →");
    start.setTextColor(Ui.BLUE);
    start.setBackground(Ui.shape(Ui.WHITE, 14, this));
    Ui.add(hero, start);
    start.setOnClickListener(v -> navigateTo(1));
    Ui.add(content, hero);
    List<TrainingRecord> records = repo.getRecords(null, null, null, 1, 0);
    TrainingRecord recent = records.isEmpty() ? null : records.get(0);
    LinearLayout metrics = Ui.card(this);
    Ui.metric(
        metrics,
        "最近一次成绩",
        recent != null && isValidCompletion(recent) ? Labels.number(recent.score) + " 分" : "—");
    if (recent != null && !isValidCompletion(recent))
      Ui.add(
          metrics,
          Ui.text(this, "最近一次" + Labels.status(recent.status) + "，未计入有效成绩。", 12, Ui.MUTED));
    Ui.metric(metrics, "累计训练", repo.recordCount(null, null, null) + " 次");
    Ui.add(content, metrics);
    section("选择你的练习");
    trainingCard("trace", "把每一次移动，练得更稳", "沿着蓝色路径描绘，观察偏差与覆盖度。", false);
    trainingCard("reaction", "跟随目标，找到自己的节奏", "等待目标出现后准确点击，记录真实反应时间。", false);
    trainingCard("coordination", "一步一步，练习指尖协作", "依次点击数字，或在有效区域连续点击。", false);
    section("最近训练");
    if (recent == null) Ui.empty(content, "还没有训练记录", "完成第一次训练后，这里会出现你的成绩与练习时间。");
    else {
      addRecord(content, recent);
      Button history = Ui.secondary(this, "查看全部训练记录");
      Ui.add(content, history);
      history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
    }
    section("今日练习建议");
    LinearLayout advice = Ui.card(this);
    Ui.add(advice, Ui.text(this, recommendationText(), 14, Ui.INK));
    Ui.gap(advice, 10);
    Ui.add(advice, Ui.text(this, "训练评分仅用于观察软件任务表现。保持舒适、适量练习；不适时停止。", 12, Ui.MUTED));
    Ui.add(content, advice);
  }

  private void training() {
    heading("指尖训练室", "选择一种练习，从简单难度开始");
    LinearLayout setting = Ui.card(this);
    Ui.add(setting, Ui.bold(this, "本次训练难度", 17, Ui.INK));
    Ui.gap(setting, 8);
    Spinner s = new Spinner(this);
    s.setContentDescription("本次训练难度");
    s.setMinimumHeight(Ui.dp(this, 48));
    s.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"简单 · 更宽容的目标", "普通 · 适中的挑战", "困难 · 更精细的控制"}));
    s.setSelection(difficulty);
    Ui.add(setting, s);
    s.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            if (difficulty == position) return;
            difficulty = position;
            persistSelections();
          }
        });
    Ui.add(setting, Ui.text(this, "难度由你决定，系统只会提供建议，不会自动变更。", 12, Ui.MUTED));
    Ui.add(content, setting);
    trainingCard("trace", "轨迹描绘训练", "从绿色起点沿路径描绘，覆盖整条路线后结束。", true);
    trainingCard("reaction", "反应速度训练", "目标出现后点击圆心区域；点错与超时分别记录。", true);
    trainingCard("coordination", "手指协调性训练", "顺序模式依次点击数字；连续模式在指定区域点击。", true);
    Ui.add(content, Ui.text(this, recommendationText(), 13, Ui.MUTED));
  }

  private void trainingCard(String type, String title, String detail, boolean buttons) {
    LinearLayout box = Ui.card(this), top = Ui.row(this);
    TrainingIllustration icon = new TrainingIllustration(this, type);
    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(this, 66), Ui.dp(this, 66));
    il.rightMargin = Ui.dp(this, 14);
    top.addView(icon, il);
    LinearLayout words = Ui.column(this);
    Ui.add(words, Ui.bold(this, title, 17, Ui.INK));
    Ui.gap(words, 5);
    Ui.add(words, Ui.text(this, detail, 13, Ui.MUTED));
    top.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.add(box, top);
    Ui.gap(box, 16);
    if (buttons) {
      String[] sub =
          "trace".equals(type)
              ? new String[] {"line", "circle", "spiral"}
              : "reaction".equals(type)
                  ? new String[] {"random"}
                  : new String[] {"sequence", "tapping"};
      for (int i = 0; i < sub.length; i++) {
        final String subtype = sub[i];
        Button b =
            i == 0
                ? Ui.button(this, "开始" + Labels.subtype(subtype))
                : Ui.secondary(this, Labels.subtype(subtype));
        Ui.add(box, b);
        if (i < sub.length - 1) Ui.gap(box, 8);
        b.setOnClickListener(v -> launch(type, subtype));
      }
    } else {
      Button b = Ui.secondary(this, "进入" + Labels.type(type) + "  →");
      Ui.add(box, b);
      b.setOnClickListener(v -> navigateTo(1));
    }
    Ui.add(content, box);
  }

  private void launch(String type, String subtype) {
    startActivity(
        new Intent(this, TrainingActivity.class)
            .putExtra("type", type)
            .putExtra("subtype", subtype)
            .putExtra("difficulty", difficulty));
  }

  private String recommendationText() {
    List<TrainingRecord> recent = repo.recentValid(null, null, null, 1);
    if (recent.isEmpty()) return "先完成一次练习，慢慢建立适合自己的训练节奏。同任务、同难度累计5次有效训练后，将提供难度建议。";
    TrainingRecord latest = recent.get(0);
    List<TrainingRecord> all = repo.recentValid(latest.type, latest.subtype, latest.difficulty, 5);
    List<DifficultyRecommender.Session> sessions = new ArrayList<>();
    for (TrainingRecord r : all)
      sessions.add(
          new DifficultyRecommender.Session(
              r.type, r.difficulty, r.score, r.valid, "completed".equals(r.status), r.endedAt));
    DifficultyRecommender.Recommendation recommendation =
        DifficultyRecommender.recommend(sessions, latest.type, latest.difficulty);
    return Labels.subtype(latest.subtype)
        + " · "
        + Labels.difficulty(latest.difficulty)
        + "："
        + recommendation.reason.replace("同类型", "同任务")
        + (recommendation.averageScore == null
            ? "（" + recommendation.sampleCount + "/5次）"
            : "（平均 " + Labels.number(recommendation.averageScore) + " 分）")
        + "。你可以在训练页自行选择。";
  }

  private void data() {
    heading("每一点进步，都有记录", "统计来自本机真实训练 · 工程分非医学指标");
    String[] subtypes = selectedSubtypes();
    subtypeFilter = Math.max(0, Math.min(subtypes.length - 1, subtypeFilter));
    LinearLayout filters = Ui.card(this);
    Ui.add(filters, Ui.bold(this, "选择统计范围", 17, Ui.INK));
    addFilter(
        filters,
        "训练类型",
        new String[] {"全部类型", "轨迹描绘", "反应速度", "手指协调"},
        filter,
        pos -> {
          if (filter == pos) return;
          filter = pos;
          subtypeFilter = pos == 0 ? 0 : 1;
          persistSelections();
          render();
        });
    String[] subtypeLabels = new String[subtypes.length];
    subtypeLabels[0] = "全部任务";
    for (int i = 1; i < subtypes.length; i++) subtypeLabels[i] = Labels.subtype(subtypes[i]);
    Spinner taskFilter =
        addFilter(
            filters,
            "训练任务",
            subtypeLabels,
            subtypeFilter,
            pos -> {
              if (subtypeFilter == pos) return;
              subtypeFilter = pos;
              persistSelections();
              render();
            });
    taskFilter.setEnabled(filter != 0);
    addFilter(
        filters,
        "训练难度",
        new String[] {"全部难度", "简单", "普通", "困难"},
        statsDifficulty,
        pos -> {
          if (statsDifficulty == pos) return;
          statsDifficulty = pos;
          persistSelections();
          render();
        });
    Ui.add(content, filters);
    String type = types[filter], subtype = subtypes[subtypeFilter];
    Integer level = statsDifficulty == 0 ? null : statsDifficulty - 1;
    String range =
        Labels.type(type)
            + " · "
            + (subtype == null ? "全部任务" : Labels.subtype(subtype))
            + " · "
            + (level == null ? "全部难度" : Labels.difficulty(level));
    boolean comparable =
        type != null && (subtype != null || "reaction".equals(type)) && level != null;
    Stats stats = repo.getStats(type, subtype, level, 30);
    LinearLayout totals = Ui.card(this);
    Ui.add(totals, Ui.bold(this, "最近30天概览", 18, Ui.INK));
    Ui.add(totals, Ui.text(this, range, 12, Ui.MUTED));
    Ui.metric(totals, "训练次数", stats.count + " 次");
    Ui.metric(
        totals,
        "平均有效成绩",
        stats.averageScore == null ? "—" : Labels.number(stats.averageScore) + " 分");
    Ui.metric(
        totals, "最佳有效成绩", stats.bestScore == null ? "—" : Labels.number(stats.bestScore) + " 分");
    Ui.metric(totals, "训练总时长", Labels.duration(stats.durationMs));
    Ui.metric(
        totals,
        "有效完成率",
        stats.count == 0 ? "—" : Labels.number(stats.completed * 100.0 / stats.count) + "%");
    Ui.add(totals, Ui.text(this, "有效成绩仅纳入有效且已完成的训练；次数与时长包含未完成和中止会话。", 12, Ui.MUTED));
    Ui.add(content, totals);
    List<TrainingRecord> valid = repo.recentValid(type, subtype, level, 20);
    Collections.reverse(valid);
    SimpleDateFormat dayFormat = new SimpleDateFormat("M/d", Locale.CHINA);
    List<String> xs = new ArrayList<>();
    List<Double> ys = new ArrayList<>();
    if (comparable) {
      SimpleDateFormat timeFormat = new SimpleDateFormat("yy/M/d\nHH:mm:ss", Locale.CHINA);
      for (TrainingRecord r : valid) {
        xs.add(timeFormat.format(new Date(r.startedAt)) + " #" + r.id);
        ys.add(r.score);
      }
      chart(
          "历史成绩折线图",
          xs,
          ys,
          false,
          100,
          "" + range + "；全部日期最近20次有效完成，按时间排序。日期、时刻与记录编号区分每次训练。纵轴：分。");
    } else {
      Ui.empty(content, "选择同一任务和难度后查看成绩趋势", "不同任务或难度的成绩不连成趋势线。请先选择具体训练类型、任务和难度；下方保留所选范围的真实汇总与次数。");
    }
    List<String> tx = new ArrayList<>();
    List<Double> ty = new ArrayList<>();
    for (int i = 1; i < types.length; i++) {
      Stats st = repo.getStats(types[i], null, level, 30);
      tx.add(Labels.type(types[i]));
      ty.add(st.averageScore);
    }
    chart(
        "不同训练类型的成绩对比",
        tx,
        ty,
        true,
        100,
        "最近30天 · "
            + (level == null ? "全部难度混合" : Labels.difficulty(level))
            + " · 每类包含全部任务，不应用任务筛选。柱形为有效完成的平均工程分，仅供任务表现参考。纵轴：分。");
    Stats week = repo.getStats(type, subtype, level, 7);
    xs = new ArrayList<>();
    ys = new ArrayList<>();
    double maxCount = 1;
    if (week.count > 0)
      for (DayStat d : week.days) {
        xs.add(dayFormat.format(new Date(d.dayStart)));
        ys.add((double) d.count);
        maxCount = Math.max(maxCount, d.count);
      }
    chart("最近7天训练次数", xs, ys, true, Math.ceil(maxCount / 4) * 4, range + "；本地日期，纵轴：次。包含中止与未完成会话。");
    if (comparable) {
      xs = new ArrayList<>();
      ys = new ArrayList<>();
      for (DayStat d : stats.days) {
        xs.add(dayFormat.format(new Date(d.dayStart)));
        ys.add(d.averageScore);
      }
      chart("最近30天训练表现趋势", xs, ys, false, 100, range + "；按本地日期聚合有效完成的平均分，无有效成绩的日期断开。纵轴：分。");
    }
    if (valid.size() < 2)
      Ui.add(content, Ui.text(this, "有效记录不足2次，暂不足以判断趋势。继续练习后再观察。", 13, Ui.MUTED));
    Ui.gap(content, 12);
    Button history = Ui.button(this, "查看训练历史与详细报告");
    Ui.add(content, history);
    history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
  }

  private String[] selectedSubtypes() {
    if (filter == 1) return new String[] {null, "line", "circle", "spiral"};
    if (filter == 2) return new String[] {null, "random"};
    if (filter == 3) return new String[] {null, "sequence", "tapping"};
    return new String[] {null};
  }

  private interface FilterChange {
    void select(int position);
  }

  private Spinner addFilter(
      LinearLayout parent, String label, String[] values, int selected, FilterChange change) {
    Ui.gap(parent, 10);
    TextView caption = Ui.text(this, label, 13, Ui.MUTED);
    Spinner spinner = new Spinner(this);
    spinner.setId(View.generateViewId());
    caption.setLabelFor(spinner.getId());
    spinner.setContentDescription(label);
    spinner.setMinimumHeight(Ui.dp(this, 48));
    spinner.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
    spinner.setSelection(selected);
    Ui.add(parent, caption);
    Ui.add(parent, spinner);
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            change.select(position);
          }
        });
    return spinner;
  }

  private void chart(
      String title, List<String> x, List<Double> y, boolean bars, double max, String note) {
    LinearLayout box = Ui.card(this);
    Ui.add(box, Ui.bold(this, title, 17, Ui.INK));
    Ui.gap(box, 6);
    boolean any = false;
    for (Double d : y)
      if (d != null && Double.isFinite(d) && d >= 0 && d <= max) {
        any = true;
        break;
      }
    if (any) {
      ChartView chart = new ChartView(this, x, y, bars, max);
      chart.setContentDescription(title + "。" + chart.getContentDescription());
      Ui.add(box, chart);
      Ui.add(box, Ui.text(this, "轻点图表查看单项数据；也可用方向键切换。", 12, Ui.MUTED));
    } else {
      Ui.gap(box, 18);
      Ui.add(box, Ui.text(this, "暂无可展示的训练数据", 15, Ui.MUTED));
      Ui.gap(box, 18);
    }
    Ui.add(box, Ui.text(this, note, 12, Ui.MUTED));
    Ui.add(content, box);
  }

  private static boolean isValidCompletion(TrainingRecord r) {
    return r.isEffectiveCompletion();
  }

  private void plan() {
    heading("把练习，放进日常", "小目标，也值得认真完成");
    TrainingPlan plan = repo.getPlan();
    if (plan == null) {
      Ui.empty(content, "还没有训练计划", "设置每日次数、练习类型和提醒时间，让练习更有规律。");
    } else {
      LinearLayout card = Ui.card(this);
      Ui.add(card, Ui.bold(this, "今日计划", 20, Ui.INK));
      Ui.metric(card, "训练模式", Labels.type(plan.type));
      int completed = repo.completedToday(plan.type);
      Ui.metric(card, "目标次数", plan.dailyGoal + " 次");
      Ui.metric(card, "已完成", completed + " 次");
      Ui.metric(
          card, "完成进度", Labels.number(Math.min(100, completed * 100.0 / plan.dailyGoal)) + "%");
      Ui.metric(card, "计划时间", String.format(Locale.CHINA, "%02d:%02d", plan.hour, plan.minute));
      Ui.metric(card, "每日提醒", plan.reminderEnabled ? "已请求开启" : "已关闭");
      Ui.add(content, card);
    }
    Button edit = Ui.button(this, plan == null ? "创建每日训练计划" : "编辑计划与提醒");
    Ui.add(content, edit);
    edit.setOnClickListener(v -> startActivity(new Intent(this, PlanActivity.class)));
    Ui.gap(content, 18);
    Ui.add(content, Ui.text(this, "完成进度只计入今天已完成且有效的对应类型训练。提醒采用非精确系统调度，实际提醒时间可能延迟。", 13, Ui.MUTED));
  }

  private void mine(UserProfile user) {
    heading("我的训练档案", "关于自己的每一次坚持");
    LinearLayout profile = Ui.card(this);
    Ui.add(profile, Ui.bold(this, user.nickname, 25, Ui.INK));
    Ui.gap(profile, 8);
    Ui.metric(profile, "惯用手", user.hand);
    Ui.metric(profile, "年龄", user.age == null ? "未填写" : user.age + " 岁");
    Ui.metric(profile, "每日档案目标", user.dailyGoal + " 次");
    Ui.gap(profile, 8);
    Ui.add(profile, Ui.text(this, "训练目标\n" + user.goal, 15, Ui.MUTED));
    Ui.add(content, profile);
    Button edit = Ui.button(this, "编辑个人档案");
    Ui.add(content, edit);
    edit.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
    Ui.gap(content, 12);
    Button history = Ui.secondary(this, "训练历史");
    Ui.add(content, history);
    history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
    Ui.gap(content, 12);
    Button settings = Ui.secondary(this, "设置、隐私与使用说明");
    Ui.add(content, settings);
    settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    Ui.gap(content, 24);
    Ui.add(content, Ui.text(this, "本项目为《移动医疗开发》课程设计。训练成绩属于软件工程指标，不能用于诊断或替代专业康复治疗。", 13, Ui.MUTED));
  }

  private void addRecord(LinearLayout parent, TrainingRecord r) {
    LinearLayout box = Ui.card(this);
    Ui.add(
        box,
        Ui.bold(
            this,
            Labels.subtype(r.subtype)
                + "  ·  "
                + (isValidCompletion(r) ? Labels.number(r.score) + " 分" : "未计有效成绩"),
            18,
            Ui.INK));
    Ui.gap(box, 7);
    Ui.add(
        box,
        Ui.text(
            this,
            Labels.date(r.startedAt)
                + "  ·  "
                + Labels.difficulty(r.difficulty)
                + "  ·  "
                + Labels.status(r.status),
            13,
            Ui.MUTED));
    Ui.add(parent, box);
    box.setOnClickListener(
        v -> startActivity(new Intent(this, ResultActivity.class).putExtra("record_id", r.id)));
    box.setFocusable(true);
    box.setContentDescription(
        "查看"
            + Labels.subtype(r.subtype)
            + "，"
            + Labels.difficulty(r.difficulty)
            + "，"
            + Labels.date(r.startedAt)
            + "，"
            + Labels.status(r.status)
            + "，"
            + (isValidCompletion(r) ? Labels.number(r.score) + "分" : "未计有效成绩")
            + "的训练报告");
  }

  /** 几何图标避免依赖系统字体里的特殊字符，所有导航按钮共享一套笔画。 */
  public static class NavigationIconView extends View {
    private int index;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public NavigationIconView(android.content.Context c) {
      this(c, null);
    }

    public NavigationIconView(android.content.Context c, AttributeSet attributes) {
      super(c, attributes);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    NavigationIconView(android.content.Context c, int index) {
      this(c);
      this.index = index;
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.scale(getWidth() / 26f, getHeight() / 26f);
      pen.setColor(isSelected() ? Ui.BLUE : Ui.MUTED);
      pen.setStrokeWidth(1.8f);
      pen.setStrokeCap(Paint.Cap.ROUND);
      pen.setStrokeJoin(Paint.Join.ROUND);
      pen.setStyle(Paint.Style.STROKE);
      path.reset();
      if (index == 0) {
        path.moveTo(3, 12);
        path.lineTo(13, 4);
        path.lineTo(23, 12);
        path.moveTo(6, 10);
        path.lineTo(6, 22);
        path.lineTo(10, 22);
        path.lineTo(10, 16);
        path.lineTo(16, 16);
        path.lineTo(16, 22);
        path.lineTo(20, 22);
        path.lineTo(20, 10);
        canvas.drawPath(path, pen);
      } else if (index == 1) {
        canvas.drawCircle(13, 13, 9, pen);
        canvas.drawCircle(13, 13, 4.5f, pen);
        canvas.drawLine(13, 2, 13, 6, pen);
        canvas.drawLine(20, 13, 24, 13, pen);
        canvas.drawLine(13, 20, 13, 24, pen);
        canvas.drawLine(2, 13, 6, 13, pen);
      } else if (index == 2) {
        path.moveTo(4, 4);
        path.lineTo(4, 22);
        path.lineTo(23, 22);
        path.moveTo(8, 17);
        path.lineTo(12, 12);
        path.lineTo(16, 14);
        path.lineTo(22, 6);
        canvas.drawPath(path, pen);
        pen.setStyle(Paint.Style.FILL);
        canvas.drawCircle(8, 17, 1.7f, pen);
        canvas.drawCircle(12, 12, 1.7f, pen);
        canvas.drawCircle(16, 14, 1.7f, pen);
        canvas.drawCircle(22, 6, 1.7f, pen);
      } else if (index == 3) {
        canvas.drawRoundRect(4, 6, 22, 23, 3, 3, pen);
        canvas.drawLine(4, 11, 22, 11, pen);
        canvas.drawLine(9, 3, 9, 8, pen);
        canvas.drawLine(17, 3, 17, 8, pen);
        path.moveTo(8, 17);
        path.lineTo(11, 20);
        path.lineTo(18, 14);
        canvas.drawPath(path, pen);
      } else {
        canvas.drawCircle(13, 8, 4.5f, pen);
        canvas.drawArc(4, 15, 22, 30, 180, 180, false, pen);
      }
      canvas.restore();
    }
  }
}
