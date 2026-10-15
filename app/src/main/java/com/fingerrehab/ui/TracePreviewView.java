package com.fingerrehab.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import com.fingerrehab.algorithm.TrainingAlgorithms;
import com.fingerrehab.algorithm.TrainingAlgorithms.Point;
import com.fingerrehab.model.TrainingRecord;
import java.util.*;
import org.json.*;

/** 缓存并还原数据库内的真实采样；多次笔画之间不绘制虚构连线。 */
public class TracePreviewView extends View {
  private final Paint paint = new Paint(3);
  private final Path guide = new Path(), actual = new Path();
  private final List<Point> target;
  private final List<Point> samples = new ArrayList<>();
  private float side, left, top;

  public TracePreviewView(Context context) {
    this(context, (TrainingRecord) null);
  }

  public TracePreviewView(Context context, AttributeSet attributes) {
    this(context, (TrainingRecord) null);
  }

  public TracePreviewView(Context context, TrainingRecord record) {
    super(context);
    setContentDescription("已保存的目标与实际触摸路径");
    if (record == null) {
      target = Collections.emptyList();
      setContentDescription("尚无训练轨迹");
      return;
    }
    target = TrainingAlgorithms.tracePath(record.subtype, record.difficulty);
    if (record.detailsJson == null) return;
    try {
      JSONArray data = new JSONObject(record.detailsJson).optJSONArray("samples");
      if (data == null) return;
      boolean nextBreak = true;
      for (int i = 0; i < data.length(); i++) {
        try {
          JSONObject p = data.getJSONObject(i);
          double x = p.getDouble("x"), y = p.getDouble("y");
          if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
            nextBreak = true;
            continue;
          }
          samples.add(new Point(x, y, 0, nextBreak || p.optBoolean("breakBefore")));
          nextBreak = false;
        } catch (JSONException invalidSample) {
          // 缺失或损坏的点表示未知运动，恢复读取后也不能跨过它虚构连线。
          nextBreak = true;
        }
      }
    } catch (JSONException ignored) {
    }
  }

  @Override
  protected void onMeasure(int w, int h) {
    setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 230));
  }

  @Override
  protected void onSizeChanged(int w, int h, int ow, int oh) {
    side = Math.max(1, Math.min(w, h) - Ui.dp(getContext(), 18));
    left = (w - side) / 2;
    top = (h - side) / 2;
    guide.reset();
    actual.reset();
    build(guide, target);
    build(actual, samples);
  }

  private void build(Path path, List<Point> points) {
    for (int i = 0; i < points.size(); i++) {
      Point p = points.get(i);
      float x = left + (float) p.x * side, y = top + (float) p.y * side;
      if (i == 0 || p.breakBefore) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Ui.dp(getContext(), 4));
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setColor(0xFFBAD1FF);
    canvas.drawPath(guide, paint);
    paint.setColor(Ui.TEAL);
    paint.setStrokeWidth(Ui.dp(getContext(), 2));
    canvas.drawPath(actual, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Ui.TEAL);
    if (target.isEmpty()) return;
    Point start = target.get(0);
    canvas.drawCircle(
        left + (float) start.x * side, top + (float) start.y * side, Ui.dp(getContext(), 5), paint);
  }
}
