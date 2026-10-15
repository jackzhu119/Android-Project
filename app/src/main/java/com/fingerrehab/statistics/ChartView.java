package com.fingerrehab.statistics;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.fingerrehab.ui.Ui;
import java.util.*;

/** 数据库查询结果的轻量图表；缺测断线，零分保留，触摸与方向键可读取单项。 */
public class ChartView extends View {
  private final List<String> labels = new ArrayList<>();
  private final List<Double> values = new ArrayList<>();
  private boolean bars;
  private double max = 100;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private int selected = -1;
  private float plotLeft, plotRight;

  public ChartView(Context context) {
    this(context, (AttributeSet) null);
  }

  /** XML / Android Studio 预览使用真实空状态，不注入示例成绩。 */
  public ChartView(Context context, AttributeSet attributes) {
    super(context, attributes);
    setMinimumHeight(Ui.dp(context, 230));
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setFocusable(false);
    setClickable(false);
    setContentDescription(describe());
  }

  public ChartView(
      Context context, List<String> labels, List<Double> values, boolean bars, double max) {
    this(context);
    this.bars = bars;
    this.max = Double.isFinite(max) && max > 0 ? max : 100;
    if (labels != null)
      for (int i = 0; i < labels.size(); i++) {
        this.labels.add(labels.get(i) == null ? "第" + (i + 1) + "项" : labels.get(i));
        Double value = values == null || i >= values.size() ? null : values.get(i);
        this.values.add(
            value != null && Double.isFinite(value) && value >= 0 && value <= this.max
                ? value
                : null);
      }
    setContentDescription(describe());
    setFocusable(!this.labels.isEmpty());
    setClickable(!this.labels.isEmpty());
  }

  private String describe() {
    if (labels.isEmpty()) return "图表，暂无数据";
    StringBuilder description = new StringBuilder("图表。轻点或用左右方向键读取单项。");
    for (int i = 0; i < labels.size(); i++) description.append(pointDescription(i)).append("；");
    return description.toString();
  }

  private String pointDescription(int index) {
    Double value = values.get(index);
    return labels.get(index).replace('\n', ' ')
        + "："
        + (value == null ? "无有效数据" : String.format(Locale.CHINA, "%.1f", value));
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    float fontScale = getResources().getConfiguration().fontScale;
    int preferred = Ui.dp(getContext(), 230 + Math.max(0, fontScale - 1) * 60);
    setMeasuredDimension(
        resolveSize(Ui.dp(getContext(), 280), widthSpec), resolveSize(preferred, heightSpec));
  }

  private float d(float value) {
    return Ui.dp(getContext(), value);
  }

  @SuppressWarnings("deprecation")
  private float textSize() {
    return 11 * getResources().getDisplayMetrics().scaledDensity;
  }

  private float pointX(int index, float left, float right) {
    int count = labels.size();
    if (bars) return left + (right - left) * (index + .5f) / Math.max(1, count);
    return count == 1 ? (left + right) / 2 : left + (right - left) * index / Math.max(1, count - 1);
  }

  private float labelWidth(String label) {
    float width = 0;
    for (String line : label.split("\\n")) width = Math.max(width, paint.measureText(line));
    return width;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(textSize());
    float lineHeight = paint.getFontSpacing();
    float left =
        Math.max(d(32), paint.measureText(String.format(Locale.CHINA, "%.0f", max)) + d(10));
    float right = getWidth() - d(10), top = lineHeight + d(20);
    float bottom = getHeight() - lineHeight * 2 - d(18);
    float width = right - left, height = bottom - top;
    plotLeft = left;
    plotRight = right;
    if (width <= 0 || height <= 0) return;
    paint.setStrokeWidth(d(1));
    for (int i = 0; i <= 4; i++) {
      float y = bottom - height * i / 4;
      paint.setColor(0xFFE7EDF5);
      canvas.drawLine(left, y, right, y, paint);
      paint.setColor(Ui.MUTED);
      paint.setTextAlign(Paint.Align.RIGHT);
      canvas.drawText(
          String.format(Locale.CHINA, "%.0f", max * i / 4), left - d(7), y + lineHeight / 3, paint);
    }
    paint.setColor(0xFFADBCCD);
    canvas.drawLine(left, top, left, bottom, paint);
    canvas.drawLine(left, bottom, right, bottom, paint);
    int count = labels.size();
    if (count == 0) return;
    if (selected >= 0) {
      float x = pointX(selected, left, right);
      paint.setColor(0xFFB8CDF9);
      canvas.drawLine(x, top, x, bottom, paint);
      paint.setColor(Ui.INK);
      paint.setTextAlign(Paint.Align.LEFT);
      String detail = pointDescription(selected);
      int length = paint.breakText(detail, true, getWidth() - d(8), null);
      if (length < detail.length()) detail = detail.substring(0, Math.max(0, length - 1)) + "…";
      canvas.drawText(detail, d(2), lineHeight, paint);
    }
    drawLabels(canvas, left, right, bottom, lineHeight);
    path.reset();
    boolean linked = false;
    float step = width / Math.max(1, bars ? count : count - 1);
    for (int i = 0; i < count; i++) {
      Double value = values.get(i);
      if (value == null) {
        linked = false;
        continue;
      }
      float x = pointX(i, left, right), y = bottom - (float) (value / max) * height;
      if (bars) {
        paint.setColor(i % 2 == 0 ? Ui.BLUE : Ui.TEAL);
        float half = Math.min(d(23), step * .28f);
        // 真实零值使用底部标记，不能把有效零分显示成缺测。
        if (value == 0) canvas.drawLine(x - half, bottom - d(1), x + half, bottom - d(1), paint);
        else canvas.drawRoundRect(x - half, y, x + half, bottom, d(4), d(4), paint);
        String number = String.format(Locale.CHINA, "%.1f", value);
        if (paint.measureText(number) + d(6) <= step) {
          paint.setTextAlign(Paint.Align.CENTER);
          canvas.drawText(number, x, Math.max(top + lineHeight, y - d(6)), paint);
        }
      } else {
        if (linked) path.lineTo(x, y);
        else path.moveTo(x, y);
        linked = true;
      }
    }
    if (!bars) {
      paint.setColor(Ui.BLUE);
      paint.setStrokeWidth(d(2.5f));
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawPath(path, paint);
      paint.setStyle(Paint.Style.FILL);
      for (int i = 0; i < count; i++) {
        Double value = values.get(i);
        if (value == null) continue;
        float x = pointX(i, left, right), y = bottom - (float) (value / max) * height;
        paint.setColor(Ui.WHITE);
        canvas.drawCircle(x, y, d(i == selected ? 6 : 5), paint);
        paint.setColor(Ui.BLUE);
        canvas.drawCircle(x, y, d(i == selected ? 4 : 3), paint);
      }
    }
  }

  private void drawLabels(Canvas canvas, float left, float right, float bottom, float lineHeight) {
    paint.setColor(Ui.MUTED);
    paint.setTextAlign(Paint.Align.CENTER);
    float previousRight = -1;
    int last = labels.size() - 1;
    float lastHalf = labelWidth(labels.get(last)) / 2;
    float lastCenter =
        Math.max(
            lastHalf + d(2), Math.min(getWidth() - lastHalf - d(2), pointX(last, left, right)));
    float reservedLeft = lastCenter - lastHalf - d(8);
    for (int i = 0; i <= last; i++) {
      float half = labelWidth(labels.get(i)) / 2;
      float x = Math.max(half + d(2), Math.min(getWidth() - half - d(2), pointX(i, left, right)));
      if (x - half <= previousRight + d(8)) continue;
      if (i > 0 && i < last && x + half > reservedLeft) continue;
      String[] lines = labels.get(i).split("\\n");
      for (int line = 0; line < Math.min(2, lines.length); line++)
        canvas.drawText(lines[line], x, bottom + d(8) + lineHeight * (line + 1), paint);
      previousRight = x + half;
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled() || labels.isEmpty()) return super.onTouchEvent(event);
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      setPressed(true);
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      if (!isPressed()) return false;
      setPressed(false);
      int closest = 0;
      float distance = Float.MAX_VALUE;
      for (int i = 0; i < labels.size(); i++) {
        float delta = Math.abs(pointX(i, plotLeft, plotRight) - event.getX());
        if (delta < distance) {
          closest = i;
          distance = delta;
        }
      }
      selected = closest;
      performClick();
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) setPressed(false);
    return true;
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (isEnabled()
        && !labels.isEmpty()
        && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
      if (moveSelection(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) return true;
      // 到达端点后交回系统焦点导航，允许键盘用户离开图表。
    }
    return super.onKeyDown(keyCode, event);
  }

  private boolean moveSelection(int direction) {
    int next = selected < 0 ? (direction > 0 ? 0 : labels.size() - 1) : selected + direction;
    if (next < 0 || next >= labels.size()) return false;
    selectPoint(next);
    return true;
  }

  private void selectPoint(int index) {
    if (index < 0 || index >= labels.size()) return;
    selected = index;
    announceForAccessibility(pointDescription(index));
    invalidate();
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setScrollable(labels.size() > 1);
    if (selected >= 0)
      info.setText("第" + (selected + 1) + "/" + labels.size() + "项，" + pointDescription(selected));
    if (!labels.isEmpty())
      info.addAction(
          new AccessibilityNodeInfo.AccessibilityAction(
              AccessibilityNodeInfo.ACTION_CLICK, "读取所选数据"));
    if (labels.size() > 1 && selected < labels.size() - 1)
      info.addAction(
          new AccessibilityNodeInfo.AccessibilityAction(
              AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "下一项数据"));
    if (labels.size() > 1 && selected != 0)
      info.addAction(
          new AccessibilityNodeInfo.AccessibilityAction(
              AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "上一项数据"));
  }

  @Override
  public boolean performAccessibilityAction(int action, Bundle arguments) {
    if (isEnabled() && !labels.isEmpty()) {
      if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return moveSelection(1);
      if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return moveSelection(-1);
    }
    return super.performAccessibilityAction(action, arguments);
  }

  @Override
  public boolean performClick() {
    if (!isEnabled() || labels.isEmpty()) return false;
    super.performClick();
    selectPoint(selected < 0 ? 0 : selected);
    return true;
  }
}
