package com.fingerrehab.training;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import com.fingerrehab.algorithm.TrainingAlgorithms;
import com.fingerrehab.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 正方形归一化训练画布：历史采样、单指手势、暂停状态与结果轨迹预览。 */
public final class TrainingCanvasView extends View {
  public interface Listener {
    void onTrainingChanged(boolean finished);
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF plot = new RectF();
  private final RectF backgroundRect = new RectF();
  private final Path actualPath = new Path();
  private final Path referencePath = new Path();
  private List<TrainingAlgorithms.Point> cachedReference;
  private static final int[] ARROW_POINTS = {48, 120, 192};
  private TrainingSession session;
  private Listener listener;
  private int activePointerId = -1, cachedSamples;
  private boolean blockedGesture, preview;
  private int previewDifficulty;
  private List<TrainingAlgorithms.Point> previewGuide = new ArrayList<>();
  private List<TrainingAlgorithms.Point> previewPoints = new ArrayList<>();

  public TrainingCanvasView(Context context) {
    this(context, null);
  }

  public TrainingCanvasView(Context context, AttributeSet attributes) {
    super(context, attributes);
    setFocusable(true);
    setContentDescription("训练触摸画布");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  public void bind(TrainingSession value, Listener callback) {
    session = value;
    listener = callback;
    preview = false;
    cachedSamples = 0;
    actualPath.reset();
    activePointerId = -1;
    cachedReference = null;
    invalidate();
  }

  /** 只读结果组件，数据来自保存的真实采样，不运行任何计时器。 */
  public void bindPreview(String detailsJson, String subtype, int difficulty) {
    preview = true;
    session = null;
    listener = null;
    previewDifficulty = difficulty;
    cachedReference = null;
    previewGuide = TrainingAlgorithms.tracePath(subtype, difficulty);
    previewPoints = new ArrayList<>();
    try {
      JSONArray samples =
          new JSONObject(detailsJson == null ? "{}" : detailsJson).optJSONArray("samples");
      if (samples != null)
        for (int i = 0; i < samples.length(); i++) {
          JSONObject sample = samples.getJSONObject(i);
          previewPoints.add(
              new TrainingAlgorithms.Point(
                  sample.getDouble("x"),
                  sample.getDouble("y"),
                  sample.optLong("timeMs"),
                  sample.optBoolean("breakBefore")));
        }
    } catch (Exception ignored) {
      previewPoints.clear();
    }
    setContentDescription("蓝色为目标路径，青色为本次实际手指轨迹");
    invalidate();
  }

  public RectF getPlotBounds() {
    return new RectF(plot);
  }

  /** 暂停或绘图区尺寸变化后，必须从新的 ACTION_DOWN 开始，不能续用旧指针。 */
  public void resetInput() {
    activePointerId = -1;
    blockedGesture = false;
    if (session != null) session.beginStroke();
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    int desired = width == 0 ? Ui.dp(getContext(), 320) : width;
    setMeasuredDimension(resolveSize(desired, widthSpec), resolveSize(desired, heightSpec));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    resetInput();
    float margin = Ui.dp(getContext(), 15);
    float side = Math.max(1, Math.min(w, h) - 2 * margin);
    plot.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
    backgroundRect.set(0, 0, w, h);
    cachedReference = null;
    cachedSamples = 0;
    actualPath.reset();
  }

  private float px(double x) {
    return plot.left + (float) x * plot.width();
  }

  private float py(double y) {
    return plot.top + (float) y * plot.height();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xFFF7FAFF);
    canvas.drawRoundRect(backgroundRect, Ui.dp(getContext(), 20), Ui.dp(getContext(), 20), paint);
    paint.setStrokeWidth(Ui.dp(getContext(), .7f));
    paint.setColor(0xFFE3EBF6);
    for (int i = 0; i <= 10; i++) {
      canvas.drawLine(px(i / 10d), plot.top, px(i / 10d), plot.bottom, paint);
      canvas.drawLine(plot.left, py(i / 10d), plot.right, py(i / 10d), paint);
    }
    if (preview) {
      drawTrace(canvas, previewGuide, previewPoints);
      return;
    }
    if (session == null) return;
    if (session.isTrace()) drawTrace(canvas, session.guide(), null);
    else if ("sequence".equals(session.subtype)) drawSequence(canvas);
    else
      drawTarget(
          canvas,
          session.targetX,
          session.targetY,
          session.targetRadius(),
          "tapping".equals(session.subtype)
              ? String.valueOf(Math.max(0, session.expectedTargets() - session.successes))
              : "点我",
          false,
          true);
    long elapsed = session.elapsed(SystemClock.uptimeMillis());
    long feedbackAge = elapsed - session.feedbackAtMs;
    if (feedbackAge >= 0 && feedbackAge < 260 && !session.isTrace()) {
      paint.setColor(session.feedbackGood ? 0xFF159F92 : 0xFFE36A62);
      paint.setAlpha((int) (180 * (1 - feedbackAge / 260f)));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Ui.dp(getContext(), 3));
      canvas.drawCircle(
          px(session.feedbackX),
          py(session.feedbackY),
          Ui.dp(getContext(), 13) + feedbackAge / 15f,
          paint);
      paint.setAlpha(255);
      paint.setStyle(Paint.Style.FILL);
      if (session.running) postInvalidateOnAnimation();
    }
    if (!session.running && !session.finished) {
      paint.setColor(0xD9F7FAFF);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawRoundRect(plot, Ui.dp(getContext(), 16), Ui.dp(getContext(), 16), paint);
      drawText(canvas, session.started ? "训练已暂停" : "准备开始", px(.5), py(.46), 23, Ui.INK);
      drawText(canvas, session.started ? "点击下方「继续」" : "点击下方「开始」", px(.5), py(.56), 14, Ui.MUTED);
    }
  }

  private void drawTrace(
      Canvas canvas, List<TrainingAlgorithms.Point> target, List<TrainingAlgorithms.Point> points) {
    if (target.isEmpty()) return;
    if (cachedReference != target) {
      referencePath.reset();
      referencePath.moveTo(px(target.get(0).x), py(target.get(0).y));
      for (int i = 1; i < target.size(); i++)
        referencePath.lineTo(px(target.get(i).x), py(target.get(i).y));
      cachedReference = target;
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(
        (float)
            (plot.width()
                * TrainingAlgorithms.config(
                        session == null ? previewDifficulty : session.difficulty)
                    .tolerance
                * 2));
    paint.setColor(0x19246BFD);
    canvas.drawPath(referencePath, paint);
    paint.setStrokeWidth(Ui.dp(getContext(), 3));
    paint.setColor(Ui.BLUE);
    canvas.drawPath(referencePath, paint);
    if (preview) {
      actualPath.reset();
      for (int i = 0; i < points.size(); i++) {
        TrainingAlgorithms.Point point = points.get(i);
        if (i == 0 || point.breakBefore) actualPath.moveTo(px(point.x), py(point.y));
        else actualPath.lineTo(px(point.x), py(point.y));
      }
    } else {
      if (cachedSamples > session.samples.size()) {
        cachedSamples = 0;
        actualPath.reset();
      }
      for (int i = cachedSamples; i < session.samples.size(); i++) {
        TrainingSession.Sample point = session.samples.get(i);
        if (i == 0 || point.breakBefore) actualPath.moveTo(px(point.x), py(point.y));
        else actualPath.lineTo(px(point.x), py(point.y));
      }
      cachedSamples = session.samples.size();
    }
    paint.setStrokeWidth(Ui.dp(getContext(), 4));
    paint.setColor(Ui.TEAL);
    canvas.drawPath(actualPath, paint);
    paint.setStyle(Paint.Style.FILL);
    TrainingAlgorithms.Point first = target.get(0), last = target.get(target.size() - 1);
    drawTarget(canvas, last.x, last.y, .022, "", false, false);
    paint.setColor(Ui.BLUE);
    canvas.drawCircle(px(last.x), py(last.y), Ui.dp(getContext(), 5), paint);
    paint.setColor(Ui.TEAL);
    canvas.drawCircle(px(first.x), py(first.y), Ui.dp(getContext(), 8), paint);
    paint.setColor(Color.WHITE);
    canvas.drawCircle(px(first.x), py(first.y), Ui.dp(getContext(), 3), paint);
    // 箭头随目标点顺序绘制；圆与螺旋也能清楚分辨方向。
    for (int k : ARROW_POINTS)
      if (k + 2 < target.size()) {
        TrainingAlgorithms.Point a = target.get(k), b = target.get(k + 2);
        double angle = Math.atan2(b.y - a.y, b.x - a.x);
        float x = px(a.x), y = py(a.y), size = Ui.dp(getContext(), 7);
        paint.setColor(Ui.BLUE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        canvas.drawLine(
            x,
            y,
            x - (float) Math.cos(angle - .6) * size,
            y - (float) Math.sin(angle - .6) * size,
            paint);
        canvas.drawLine(
            x,
            y,
            x - (float) Math.cos(angle + .6) * size,
            y - (float) Math.sin(angle + .6) * size,
            paint);
      }
  }

  private void drawSequence(Canvas canvas) {
    for (int i = 0; i < session.sequence.size(); i++) {
      TrainingSession.Target target = session.sequence.get(i);
      drawTarget(
          canvas,
          target.x,
          target.y,
          session.targetRadius(),
          i < session.successes ? "✓" : String.valueOf(i + 1),
          i < session.successes,
          i == session.successes);
    }
  }

  private void drawTarget(
      Canvas canvas,
      double x,
      double y,
      double radius,
      String label,
      boolean completed,
      boolean current) {
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(completed ? 0xFFDCE9E9 : current ? 0x22159F92 : 0x18246BFD);
    canvas.drawCircle(
        px(x), py(y), (float) (radius * plot.width()) + Ui.dp(getContext(), 5), paint);
    paint.setColor(completed ? 0xFF9CBAB6 : current ? Ui.TEAL : Ui.BLUE);
    canvas.drawCircle(px(x), py(y), (float) (radius * plot.width()), paint);
    if (!label.isEmpty())
      drawText(canvas, label, px(x), py(y), radius > .12 ? 28 : 17, Color.WHITE);
  }

  private void drawText(Canvas canvas, String text, float x, float y, float sizeSp, int color) {
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(color);
    paint.setTextSize(sizeSp * getResources().getDisplayMetrics().scaledDensity);
    paint.setTextAlign(Paint.Align.CENTER);
    canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2, paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (preview || session == null || !session.running || session.finished || plot.width() <= 0)
      return false;
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        blockedGesture = false;
        activePointerId = event.getPointerId(0);
        if (session.isTrace()) {
          session.beginStroke();
          appendSamples(event, 0);
        } else session.tap(nx(event.getX()), ny(event.getY()), event.getEventTime());
        changed();
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        session.cancelGesture();
        activePointerId = -1;
        blockedGesture = true;
        changed();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (blockedGesture || activePointerId == -1) return true;
        int moveIndex = event.findPointerIndex(activePointerId);
        if (moveIndex < 0) {
          session.cancelGesture();
          blockedGesture = true;
          return true;
        }
        if (session.isTrace()) appendSamples(event, moveIndex);
        changed();
        return true;
      case MotionEvent.ACTION_UP:
        if (!blockedGesture && activePointerId != -1 && session.isTrace()) {
          int upIndex = event.findPointerIndex(activePointerId);
          if (upIndex >= 0) {
            appendSamples(event, upIndex);
            session.endStroke(event.getEventTime());
          }
        }
        activePointerId = -1;
        blockedGesture = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        performClick();
        changed();
        return true;
      case MotionEvent.ACTION_CANCEL:
        session.cancelGesture();
        activePointerId = -1;
        blockedGesture = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        changed();
        return true;
      default:
        return true;
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    resetInput();
    super.onDetachedFromWindow();
  }

  private void appendSamples(MotionEvent event, int index) {
    for (int h = 0; h < event.getHistorySize(); h++)
      session.addSample(
          nx(event.getHistoricalX(index, h)),
          ny(event.getHistoricalY(index, h)),
          event.getHistoricalEventTime(h));
    session.addSample(nx(event.getX(index)), ny(event.getY(index)), event.getEventTime());
  }

  private double nx(float x) {
    return (x - plot.left) / plot.width();
  }

  private double ny(float y) {
    return (y - plot.top) / plot.height();
  }

  private void changed() {
    invalidate();
    if (listener != null) listener.onTrainingChanged(session.finished);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
