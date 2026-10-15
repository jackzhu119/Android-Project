package com.fingerrehab.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

/** 成绩环形图：仅显示 0～100 的真实有限分数；缺测与异常分数不冒充有效成绩。 */
public class ScoreView extends View {
  private final double score;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String value;

  public ScoreView(Context c) {
    this(c, Double.NaN);
  }

  public ScoreView(Context c, AttributeSet attributes) {
    this(c, Double.NaN);
  }

  public ScoreView(Context c, double measuredScore) {
    super(c);
    score =
        Double.isFinite(measuredScore) && measuredScore >= 0 && measuredScore <= 100
            ? measuredScore
            : Double.NaN;
    value = Double.isFinite(score) ? String.format(Locale.CHINA, "%.1f", score) : "—";
    setContentDescription(Double.isFinite(score) ? "综合评分 " + value + " 分" : "尚无评分");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  @Override
  protected void onMeasure(int w, int h) {
    setMeasuredDimension(
        resolveSize(Ui.dp(getContext(), 210), w), resolveSize(Ui.dp(getContext(), 210), h));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float x = getWidth() / 2f, y = getHeight() / 2f;
    float radius =
        Math.max(
            0,
            Math.min(
                Ui.dp(getContext(), 77),
                Math.min(getWidth(), getHeight()) / 2f - Ui.dp(getContext(), 10)));
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Ui.dp(getContext(), 11));
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setColor(0xFFEAF0F8);
    canvas.drawCircle(x, y, radius, paint);
    if (Double.isFinite(score)) {
      paint.setColor(Ui.BLUE);
      canvas.drawArc(
          x - radius, y - radius, x + radius, y + radius, -90, (float) (score * 3.6), false, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    paint.setColor(Ui.INK);
    fittedText(canvas, value, x, y - Ui.dp(getContext(), 9), 42, radius * 1.65f, radius * .72f);
    paint.setColor(Ui.MUTED);
    fittedText(
        canvas,
        Double.isFinite(score) ? "综合评分 / 100" : "暂无有效评分",
        x,
        y + Ui.dp(getContext(), 32),
        12,
        radius * 1.7f,
        radius * .25f);
  }

  private void fittedText(
      Canvas c, String text, float x, float centerY, float size, float maxWidth, float maxHeight) {
    paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
    float width = paint.measureText(text), height = paint.descent() - paint.ascent();
    float scale =
        Math.min(1, Math.min(maxWidth / Math.max(1, width), maxHeight / Math.max(1, height)));
    paint.setTextSize(paint.getTextSize() * scale);
    c.drawText(text, x, centerY - (paint.ascent() + paint.descent()) / 2, paint);
  }
}
