package com.fingerrehab.ui;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;

/** 随训练类型绘制的矢量插图，不含演示成绩。 */
public class TrainingIllustration extends View {
  private final String type;
  private final Paint p = new Paint(3);

  private final Path tracePath = new Path();
  private static final float[][] POSITIONS = {{23, 25}, {57, 27}, {39, 56}};
  private static final String[] NUMBERS = {"1", "2", "3"};

  public TrainingIllustration(Context c) {
    this(c, "trace");
  }

  public TrainingIllustration(Context c, AttributeSet attributes) {
    this(c, "trace");
  }

  public TrainingIllustration(Context c, String type) {
    super(c);
    this.type = type;
    tracePath.moveTo(16, 55);
    tracePath.cubicTo(20, 18, 58, 67, 63, 23);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float w = getWidth(), h = getHeight();
    c.save();
    c.scale(w / 80, h / 80);
    p.setColor(0xFFEAF1FF);
    c.drawRoundRect(0, 0, 80, 80, 20, 20, p);
    p.setStrokeWidth(3);
    p.setStrokeCap(Paint.Cap.ROUND);
    if ("trace".equals(type)) {
      p.setStyle(Paint.Style.STROKE);
      p.setColor(Ui.BLUE);
      c.drawPath(tracePath, p);
      p.setStyle(Paint.Style.FILL);
      p.setColor(Ui.TEAL);
      c.drawCircle(16, 55, 5, p);
      c.drawCircle(63, 23, 5, p);
    } else if ("reaction".equals(type)) {
      p.setStyle(Paint.Style.STROKE);
      p.setColor(Ui.BLUE);
      c.drawCircle(40, 40, 23, p);
      c.drawCircle(40, 40, 13, p);
      p.setStyle(Paint.Style.FILL);
      p.setColor(Ui.TEAL);
      c.drawCircle(40, 40, 5, p);
    } else {
      float[][] pos = POSITIONS;
      for (int i = 0; i < 3; i++) {
        p.setColor(i == 0 ? Ui.TEAL : Ui.BLUE);
        c.drawCircle(pos[i][0], pos[i][1], 12, p);
        p.setColor(Ui.WHITE);
        p.setTextSize(13);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(NUMBERS[i], pos[i][0], pos[i][1] + 5, p);
      }
    }
    c.restore();
  }
}
