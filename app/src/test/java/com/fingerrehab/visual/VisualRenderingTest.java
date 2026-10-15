package com.fingerrehab.visual;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import com.fingerrehab.R;
import com.fingerrehab.algorithm.TrainingAlgorithms;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.training.TrainingActivity;
import com.fingerrehab.training.TrainingCanvasView;
import com.fingerrehab.ui.MainActivity;
import com.fingerrehab.ui.ResultActivity;
import com.fingerrehab.ui.Ui;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSystemClock;

/** 使用原生Skia离屏渲染实际View，生成可复查预览，并验证窄屏长指标不裁切。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VisualRenderingTest {
  private RehabRepository repository;

  @Before
  public void prepare() {
    Context c = RuntimeEnvironment.getApplication();
    RehabRepository.get(c).close();
    c.deleteDatabase(RehabDatabase.NAME);
    c.getSharedPreferences("rehab_profile", 0).edit().clear().commit();
    repository = RehabRepository.get(c);
    UserProfile user = new UserProfile();
    user.nickname = "体验者";
    repository.saveProfile(user);
  }

  @After
  public void close() {
    repository.close();
  }

  @Test
  public void realScreensAndCompletedTraceRenderWithNativeCanvas() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity main = controller.get();
      render(main, "home-empty.png", 393, 852);
      LinearLayout nav = main.findViewById(R.id.bottom_nav);
      nav.getChildAt(1).performClick();
      render(main, "training-selection.png", 393, 852);
      nav.getChildAt(2).performClick();
      render(main, "data-empty.png", 393, 852);
      assertEquals(0, repository.getRecords(null).size());
    }
    Intent i =
        new Intent(RuntimeEnvironment.getApplication(), TrainingActivity.class)
            .putExtra("type", "trace")
            .putExtra("subtype", "spiral")
            .putExtra("difficulty", 0);
    try (ActivityController<TrainingActivity> controller =
        Robolectric.buildActivity(TrainingActivity.class, i).setup()) {
      TrainingActivity a = controller.get();
      render(a, "training-ready.png", 393, 852);
      ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(0).performClick();
      render(a, "training-spiral.png", 393, 852);
      TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
      android.graphics.RectF plot = canvas.getPlotBounds();
      java.util.List<TrainingAlgorithms.Point> guide = a.getSession().guide();
      long down = SystemClock.uptimeMillis();
      for (int index = 0; index < guide.size(); index++) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(20));
        TrainingAlgorithms.Point point = guide.get(index);
        MotionEvent event =
            MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                index == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE,
                plot.left + (float) point.x * plot.width(),
                plot.top + (float) point.y * plot.height(),
                0);
        canvas.onTouchEvent(event);
        event.recycle();
      }
      ShadowSystemClock.advanceBy(Duration.ofMillis(20));
      TrainingAlgorithms.Point last = guide.get(guide.size() - 1);
      MotionEvent up =
          MotionEvent.obtain(
              down,
              SystemClock.uptimeMillis(),
              MotionEvent.ACTION_UP,
              plot.left + (float) last.x * plot.width(),
              plot.top + (float) last.y * plot.height(),
              0);
      canvas.onTouchEvent(up);
      up.recycle();
      assertEquals(1, repository.getRecords(null).size());
      assertTrue(repository.getRecords(null).get(0).valid);
    }
    long id = repository.getRecords(null).get(0).id;
    Intent report =
        new Intent(RuntimeEnvironment.getApplication(), ResultActivity.class)
            .putExtra("record_id", id);
    try (ActivityController<ResultActivity> controller =
        Robolectric.buildActivity(ResultActivity.class, report).setup()) {
      render(controller.get(), "result-real-trace.png", 393, 852);
    }
  }

  @Test
  public void longMetricKeepsBothLabelsReadableAtCompactWidth() {
    Context c = RuntimeEnvironment.getApplication();
    LinearLayout box = Ui.column(c);
    Ui.metric(box, "平均轨迹偏差", "100.0% 绘图区边长");
    int width = Ui.dp(c, 244);
    box.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    box.layout(0, 0, width, box.getMeasuredHeight());
    LinearLayout row = (LinearLayout) box.getChildAt(0);
    View label = row.getChildAt(0), value = row.getChildAt(1);
    assertTrue("标签应至少容纳4个字", label.getWidth() >= Ui.dp(c, 56));
    assertTrue(value.getWidth() > 0);
    assertTrue(value.getRight() <= width);
  }

  private void render(Activity activity, String name, int widthDp, int heightDp) throws Exception {
    View root = activity.findViewById(android.R.id.content);
    int width = Ui.dp(activity, widthDp), height = Ui.dp(activity, heightDp);
    for (int i = 0; i < 4; i++) {
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, width, height);
      root.getViewTreeObserver().dispatchOnGlobalLayout();
    }
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(Ui.BG);
    root.draw(canvas);
    File folder = new File("build/outputs/previews");
    assertTrue(folder.isDirectory() || folder.mkdirs());
    try (FileOutputStream stream = new FileOutputStream(new File(folder, name))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
    }
    bitmap.recycle();
  }
}
