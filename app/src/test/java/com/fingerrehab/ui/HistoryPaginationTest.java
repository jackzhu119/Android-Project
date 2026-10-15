package com.fingerrehab.ui;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.*;
import com.fingerrehab.repository.RehabRepository;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** 超过一页的真实记录仍可完整访问；删除末页记录后不会停留在假空页。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HistoryPaginationTest {
  private RehabRepository repository;

  @Before
  public void prepare() {
    Context c = RuntimeEnvironment.getApplication();
    RehabRepository.get(c).close();
    c.deleteDatabase(RehabDatabase.NAME);
    c.getSharedPreferences("rehab_profile", 0).edit().clear().commit();
    repository = RehabRepository.get(c);
    repository.saveProfile(new UserProfile());
    long now = System.currentTimeMillis();
    for (int i = 0; i < 31; i++) {
      TrainingRecord r = new TrainingRecord();
      r.startedAt = now - i * 60000;
      r.endedAt = r.startedAt + 5000;
      r.durationMs = 5000;
      r.type = "reaction";
      r.subtype = "random";
      r.status = "completed";
      r.valid = true;
      r.score = 70;
      repository.saveRecord(r);
    }
  }

  @After
  public void close() {
    repository.close();
  }

  @Test
  public void pagesLimitVisibleRowsAndCanNavigateBothDirections() {
    try (var controller = Robolectric.buildActivity(HistoryActivity.class).setup()) {
      HistoryActivity a = controller.get();
      assertEquals(30, count(a, "查看报告"));
      button(a, "下一页").performClick();
      assertEquals(1, count(a, "查看报告"));
      assertNull(find(a.findViewById(android.R.id.content), "下一页"));
      button(a, "上一页").performClick();
      assertEquals(30, count(a, "查看报告"));
    }
  }

  @Test
  public void deletingLastRecordOnFinalPageReturnsToRemainingPage() {
    try (var controller = Robolectric.buildActivity(HistoryActivity.class).setup()) {
      HistoryActivity a = controller.get();
      button(a, "下一页").performClick();
      button(a, "删除这条记录").performClick();
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(30, repository.recordCount(null, null, null));
      assertEquals(30, count(a, "查看报告"));
      assertNull(find(a.findViewById(android.R.id.content), "下一页"));
    }
  }

  @Test
  public void returningFromReportAndRecreationKeepHistoryPosition() {
    var controller = Robolectric.buildActivity(HistoryActivity.class).setup();
    HistoryActivity a = controller.get();
    layout(a);
    ScrollView scroll = a.findViewById(R.id.screen_scroll);
    scroll.scrollTo(0, 650);
    assertEquals(650, scroll.getScrollY());
    button(a, "查看报告").performClick();
    controller.pause().resume();
    layout(a);
    assertEquals(650, scroll.getScrollY());
    Bundle saved = new Bundle();
    controller.saveInstanceState(saved).pause().stop().destroy();
    try (var restored =
        Robolectric.buildActivity(HistoryActivity.class).create(saved).start().resume().visible()) {
      layout(restored.get());
      assertEquals(
          650, ((ScrollView) restored.get().findViewById(R.id.screen_scroll)).getScrollY());
      button(restored.get(), "下一页").performClick();
      layout(restored.get());
      assertEquals(0, ((ScrollView) restored.get().findViewById(R.id.screen_scroll)).getScrollY());
    }
  }

  @Test
  public void rapidDeleteTapsShareOneConfirmationAndCancelKeepsRecords() {
    try (var controller = Robolectric.buildActivity(HistoryActivity.class).setup()) {
      HistoryActivity a = controller.get();
      button(a, "删除这条记录").performClick();
      AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();
      button(a, "删除这条记录").performClick();
      assertSame(first, ShadowAlertDialog.getLatestAlertDialog());
      first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals(31, repository.recordCount(null, null, null));
    }
  }

  private void layout(HistoryActivity a) {
    View root = a.findViewById(R.id.root);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 640);
    root.getViewTreeObserver().dispatchOnGlobalLayout();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private Button button(HistoryActivity a, String text) {
    Button b = find(a.findViewById(android.R.id.content), text);
    assertNotNull(text, b);
    return b;
  }

  private Button find(View v, String text) {
    if (v instanceof Button && text.contentEquals(((Button) v).getText())) return (Button) v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        Button b = find(g.getChildAt(i), text);
        if (b != null) return b;
      }
    }
    return null;
  }

  private int count(HistoryActivity a, String text) {
    return count(a.findViewById(android.R.id.content), text);
  }

  private int count(View v, String text) {
    int n = v instanceof Button && text.contentEquals(((Button) v).getText()) ? 1 : 0;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) n += count(g.getChildAt(i), text);
    }
    return n;
  }
}
