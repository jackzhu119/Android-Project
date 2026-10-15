package com.fingerrehab.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.fingerrehab.R;

/** 原生轻量设计组件：统一字号、留白、触控尺寸和安全区域。 */
public final class Ui {
  public static final int INK = 0xFF182843,
      MUTED = 0xFF718096,
      BLUE = 0xFF246BFD,
      TEAL = 0xFF159F92,
      BG = 0xFFF4F7FC,
      WHITE = Color.WHITE;

  private Ui() {}

  public static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  public static TextView text(Context c, String value, float size, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(c, 3), 1f);
    t.setIncludeFontPadding(true);
    return t;
  }

  public static TextView bold(Context c, String value, float size, int color) {
    TextView t = text(c, value, size, color);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  public static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  public static LinearLayout row(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  public static GradientDrawable shape(int color, float radius, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  public static LinearLayout card(Context c) {
    LinearLayout v = column(c);
    v.setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18));
    v.setBackground(shape(WHITE, 22, c));
    v.setElevation(dp(c, 1));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(c, 14);
    v.setLayoutParams(lp);
    return v;
  }

  public static Button button(Context c, String title) {
    Button b = new Button(c);
    b.setText(title);
    b.setTextSize(15);
    b.setTextColor(
        new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {MUTED, WHITE}));
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setMinHeight(dp(c, 52));
    b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
    b.setBackgroundTintList(null);
    b.setBackground(buttonBackground(c, BLUE, 0xFFDAE2EF, 15));
    return b;
  }

  public static Button secondary(Context c, String title) {
    Button b = button(c, title);
    b.setTextColor(
        new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {MUTED, BLUE}));
    b.setBackground(buttonBackground(c, 0xFFEAF1FF, 0xFFEDF0F5, 15));
    return b;
  }

  private static RippleDrawable buttonBackground(
      Context c, int normal, int disabled, float radius) {
    GradientDrawable background = shape(normal, radius, c);
    background.setColor(
        new ColorStateList(
            new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {disabled, normal}));
    return new RippleDrawable(
        ColorStateList.valueOf(0x30246BFD), background, shape(WHITE, radius, c));
  }

  public static void gap(LinearLayout parent, int size) {
    View v = new View(parent.getContext());
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(parent.getContext(), size)));
  }

  public static void add(LinearLayout parent, View v) {
    if (v.getLayoutParams() == null) parent.addView(v, new LinearLayout.LayoutParams(-1, -2));
    else parent.addView(v);
  }

  public static void title(LinearLayout parent, String title, String subtitle) {
    Context c = parent.getContext();
    add(parent, bold(c, title, 28, INK));
    if (subtitle != null && !subtitle.isEmpty()) {
      gap(parent, 4);
      add(parent, text(c, subtitle, 13, MUTED));
    }
    gap(parent, 22);
  }

  public static LinearLayout screen(Activity a, String title) {
    a.setContentView(R.layout.activity_screen);
    a.findViewById(R.id.screen_scroll).setSaveEnabled(false);
    insets(a.findViewById(R.id.root));
    ((TextView) a.findViewById(R.id.screen_title)).setText(title);
    a.findViewById(R.id.back).setOnClickListener(v -> a.onBackPressed());
    return a.findViewById(R.id.screen_content);
  }

  /** 等待新内容完成测量，避免过早恢复滚动时被 ScrollView 夹回零。 */
  public static void afterLayout(View view, Runnable action) {
    view.getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                if (view.getWidth() == 0 || view.getHeight() == 0) return;
                if (view.getViewTreeObserver().isAlive())
                  view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                action.run();
              }
            });
  }

  public static void insets(View root) {
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else {
            v.setPadding(
                in.getSystemWindowInsetLeft(),
                in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight(),
                in.getSystemWindowInsetBottom());
          }
          return in;
        });
    root.requestApplyInsets();
  }

  public static void metric(LinearLayout parent, String label, String value) {
    Context c = parent.getContext();
    LinearLayout r = row(c);
    r.setPadding(0, dp(c, 9), 0, dp(c, 9));
    TextView l = text(c, label, 14, MUTED);
    // 两侧均有明确宽度约束，窄屏或大字体时允许自然换行，避免长值挤没标签。
    LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
    labelParams.setMarginEnd(dp(c, 12));
    r.addView(l, labelParams);
    TextView val = bold(c, value, 17, INK);
    val.setGravity(Gravity.END);
    r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.15f));
    r.setContentDescription(label + "：" + value);
    r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    l.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    val.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    add(parent, r);
  }

  public static void empty(LinearLayout parent, String title, String description) {
    Context c = parent.getContext();
    LinearLayout box = card(c);
    add(box, bold(c, title, 18, INK));
    gap(box, 8);
    add(box, text(c, description, 14, MUTED));
    add(parent, box);
  }
}
