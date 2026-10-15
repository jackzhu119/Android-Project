package com.fingerrehab.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import com.fingerrehab.model.TrainingRecord;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** 损坏的历史数据不能被展示成满分、零分或实际未记录的手指移动。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewDataIntegrityTest {
  @Test
  public void invalidScoresRemainMissingWhileRealBoundaryScoresRemainVisible() {
    Context context = RuntimeEnvironment.getApplication();
    for (double score :
        new double[] {
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 100.1
        }) {
      ScoreView view = new ScoreView(context, score);
      assertEquals("尚无评分", view.getContentDescription());
      render(view).recycle();
    }
    assertEquals("综合评分 0.0 分", new ScoreView(context, 0).getContentDescription());
    assertEquals("综合评分 100.0 分", new ScoreView(context, 100).getContentDescription());
    assertEquals("综合评分 82.5 分", new ScoreView(context, 82.5).getContentDescription());
  }

  @Test
  public void nullAndMalformedDetailsRenderWithoutInventingAnActualTrace() {
    for (String details :
        new String[] {null, "", "not JSON", "[]", "{\"samples\":[", "{\"samples\":null}"}) {
      Bitmap bitmap = render(preview(details));
      assertEquals(Color.WHITE, pixel(bitmap, .5, .1));
      bitmap.recycle();
    }
  }

  @Test
  public void corruptSamplesBreakTheTraceAndLaterRealSegmentsAreStillVisible() {
    for (String invalid :
        new String[] {
          "null",
          "\"not a point\"",
          "{}",
          "{\"x\":null,\"y\":0.1}",
          "{\"x\":\"NaN\",\"y\":0.1}",
          "{\"x\":0.5,\"y\":\"Infinity\"}",
          "{\"x\":1.2,\"y\":0.1}"
        }) {
      String details =
          "{\"samples\":[{\"x\":0.1,\"y\":0.1},{\"x\":0.25,\"y\":0.1},"
              + invalid
              + ",{\"x\":0.75,\"y\":0.1},{\"x\":0.9,\"y\":0.1}]}";
      Bitmap bitmap = render(preview(details));
      assertEquals(invalid, Ui.TEAL, pixel(bitmap, .175, .1));
      assertEquals(invalid, Color.WHITE, pixel(bitmap, .5, .1));
      assertEquals(invalid, Ui.TEAL, pixel(bitmap, .825, .1));
      bitmap.recycle();
    }
  }

  @Test
  public void realContinuousSegmentsAndExplicitStrokeBreaksArePreserved() {
    Bitmap continuous =
        render(preview("{\"samples\":[{\"x\":0.1,\"y\":0.1},{\"x\":0.9,\"y\":0.1}]}"));
    assertEquals(Ui.TEAL, pixel(continuous, .5, .1));
    continuous.recycle();
    Bitmap broken =
        render(
            preview(
                "{\"samples\":[{\"x\":0.1,\"y\":0.1},{\"x\":0.25,\"y\":0.1},"
                    + "{\"x\":0.75,\"y\":0.1,\"breakBefore\":true},{\"x\":0.9,\"y\":0.1}]}"));
    assertEquals(Color.WHITE, pixel(broken, .5, .1));
    assertEquals(Ui.TEAL, pixel(broken, .825, .1));
    broken.recycle();
  }

  private TracePreviewView preview(String details) {
    TrainingRecord record = new TrainingRecord();
    record.detailsJson = details;
    return new TracePreviewView(RuntimeEnvironment.getApplication(), record);
  }

  private Bitmap render(View view) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(230, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Color.WHITE);
    view.draw(canvas);
    return bitmap;
  }

  private int pixel(Bitmap bitmap, double x, double y) {
    float side = Math.min(bitmap.getWidth(), bitmap.getHeight()) - 18;
    float left = (bitmap.getWidth() - side) / 2, top = (bitmap.getHeight() - side) / 2;
    return bitmap.getPixel(Math.round(left + (float) x * side), Math.round(top + (float) y * side));
  }
}
