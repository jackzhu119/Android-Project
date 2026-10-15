package com.fingerrehab.ui;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** 验证档案草稿的用户操作、旋转与 SQLite 失败，不修改或替代正式仓储。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileDraftTest {
  private Context context;
  private RehabRepository repository;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", 0).edit().clear().commit();
    repository = RehabRepository.get(context);
  }

  @After
  public void close() {
    repository.close();
  }

  private UserProfile user() {
    UserProfile p = new UserProfile();
    p.nickname = "原昵称";
    p.goal = "原训练目标";
    p.hand = "右手";
    repository.saveProfile(p);
    return p;
  }

  @Test
  public void unchangedExistingProfileReturnsWithoutConfirmation() {
    user();
    ProfileActivity activity = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    activity.onBackPressed();
    assertTrue(activity.isFinishing());
    AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(latest == null || !latest.isShowing());
  }

  @Test
  public void dirtyBackUsesOneDialogAndContinueEditingPreservesDraft() {
    user();
    ProfileActivity activity = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    fields(activity).get(0).setText("未保存昵称");
    activity.onBackPressed();
    AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(confirmation.isShowing());
    activity.onBackPressed();
    assertSame(confirmation, ShadowAlertDialog.getLatestAlertDialog());
    confirmation.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
    idle();
    assertFalse(activity.isFinishing());
    assertEquals("未保存昵称", fields(activity).get(0).getText().toString());
    assertEquals("原昵称", repository.getProfile().nickname);
    activity.onBackPressed();
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    idle();
    assertTrue(activity.isFinishing());
    assertEquals("原昵称", repository.getProfile().nickname);
  }

  @Test
  public void backConfirmationSavePersistsOnceAndFinishes() {
    UserProfile original = user();
    ProfileActivity activity = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    fields(activity).get(0).setText("新昵称");
    activity.onBackPressed();
    Button save = ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE);
    save.performClick();
    save.performClick();
    idle();
    assertTrue(activity.isFinishing());
    assertEquals(original.id, repository.getProfile().id);
    assertEquals("新昵称", repository.getProfile().nickname);
    assertEquals(1, repository.getProfiles().size());
  }

  @Test
  public void untouchedNewFormReturnsToGuideAndThenExitsWithoutCreatingProfile() {
    ProfileActivity activity = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    button(activity, "创建我的本地档案").performClick();
    assertEquals("3", fields(activity).get(3).getText().toString());
    activity.onBackPressed();
    assertFalse(activity.isFinishing());
    assertNotNull(button(activity, "创建我的本地档案"));
    assertNull(repository.getProfile());
    activity.onBackPressed();
    assertTrue(activity.isFinishing());
  }

  @Test
  public void rotationRestoresEveryRawDraftFieldAndOriginalBaseline() {
    user();
    ActivityController<ProfileActivity> controller =
        Robolectric.buildActivity(ProfileActivity.class).setup();
    ProfileActivity activity = controller.get();
    List<EditText> edits = fields(activity);
    edits.get(0).setText(" 草稿昵称 ");
    edits.get(1).setText("45");
    edits.get(2).setText(" 草稿目标 ");
    edits.get(3).setText("7");
    spinner(activity).setSelection(2);
    controller.recreate();
    activity = controller.get();
    edits = fields(activity);
    assertEquals(" 草稿昵称 ", edits.get(0).getText().toString());
    assertEquals("45", edits.get(1).getText().toString());
    assertEquals(" 草稿目标 ", edits.get(2).getText().toString());
    assertEquals("7", edits.get(3).getText().toString());
    assertEquals(2, spinner(activity).getSelectedItemPosition());
    activity.onBackPressed();
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
    idle();
    edits.get(0).setText("原昵称");
    edits.get(1).setText("");
    edits.get(2).setText("原训练目标");
    edits.get(3).setText("3");
    spinner(activity).setSelection(0);
    activity.onBackPressed();
    assertTrue(activity.isFinishing());
    assertEquals("原昵称", repository.getProfile().nickname);
  }

  @Test
  public void rotationRestoresAnOpenConfirmationWithoutSavingAnything() {
    user();
    ActivityController<ProfileActivity> controller =
        Robolectric.buildActivity(ProfileActivity.class).setup();
    fields(controller.get()).get(0).setText("旋转前草稿");
    controller.get().onBackPressed();
    AlertDialog original = ShadowAlertDialog.getLatestAlertDialog();
    controller.recreate();
    AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
    assertNotSame(original, restored);
    assertFalse(original.isShowing());
    assertTrue(restored.isShowing());
    assertEquals("原昵称", repository.getProfile().nickname);
    restored.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
    idle();
    assertFalse(controller.get().isFinishing());
    assertEquals("旋转前草稿", fields(controller.get()).get(0).getText().toString());
  }

  @Test
  public void failedNewProfileSaveKeepsDraftAndStillReturnsToFirstRunGuideAfterRotation() {
    ActivityController<ProfileActivity> controller =
        Robolectric.buildActivity(ProfileActivity.class).setup();
    ProfileActivity activity = controller.get();
    button(activity, "创建我的本地档案").performClick();
    fields(activity).get(0).setText("首次草稿");
    try (SQLiteDatabase database = context.openOrCreateDatabase(RehabDatabase.NAME, 0, null)) {
      database.execSQL(
          "CREATE TRIGGER reject_profile_insert BEFORE INSERT ON profiles BEGIN SELECT RAISE(ABORT,"
              + " 'test full disk'); END");
      button(activity, "保存档案").performClick();
      assertFalse(activity.isFinishing());
      assertNull(repository.getProfile());
      assertEquals("首次草稿", fields(activity).get(0).getText().toString());
      assertTrue(button(activity, "保存档案").isEnabled());
      controller.recreate();
      activity = controller.get();
      AlertDialog failure = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(failure.isShowing());
      failure.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      idle();
      assertEquals("首次草稿", fields(activity).get(0).getText().toString());
      activity.onBackPressed();
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_NEGATIVE)
          .performClick();
      idle();
      assertFalse(activity.isFinishing());
      assertNotNull(button(activity, "创建我的本地档案"));
      assertNull(repository.getProfile());
      database.execSQL("DROP TRIGGER reject_profile_insert");
    }
    button(activity, "创建我的本地档案").performClick();
    fields(activity).get(0).setText("重试成功");
    Button save = button(activity, "保存档案");
    save.performClick();
    save.performClick();
    assertEquals(1, repository.getProfiles().size());
    assertEquals("重试成功", repository.getProfile().nickname);
  }

  @Test
  public void failedExistingUpdateCanBeRetriedWithoutLosingItsOriginalOwner() {
    UserProfile original = user();
    ProfileActivity activity = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    fields(activity).get(0).setText("待重试昵称");
    try (SQLiteDatabase database = context.openOrCreateDatabase(RehabDatabase.NAME, 0, null)) {
      database.execSQL(
          "CREATE TRIGGER reject_profile_update BEFORE UPDATE ON profiles BEGIN SELECT RAISE(ABORT,"
              + " 'test full disk'); END");
      button(activity, "保存档案").performClick();
      assertEquals("原昵称", repository.getProfile().nickname);
      assertEquals("待重试昵称", fields(activity).get(0).getText().toString());
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .performClick();
      idle();
      database.execSQL("DROP TRIGGER reject_profile_update");
    }
    button(activity, "保存档案").performClick();
    assertTrue(activity.isFinishing());
    assertEquals(original.id, repository.getProfile().id);
    assertEquals("待重试昵称", repository.getProfile().nickname);
  }

  @Test
  public void currentOwnerChangeCannotOverwriteEitherProfileEvenAfterRotation() {
    UserProfile original = user();
    ActivityController<ProfileActivity> controller =
        Robolectric.buildActivity(ProfileActivity.class).setup();
    fields(controller.get()).get(0).setText("旧档案草稿");
    UserProfile other = new UserProfile();
    other.nickname = "另一档案";
    repository.saveProfile(other);
    controller.recreate();
    ProfileActivity activity = controller.get();
    assertEquals("旧档案草稿", fields(activity).get(0).getText().toString());
    button(activity, "保存档案").performClick();
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    assertFalse(activity.isFinishing());
    assertEquals(other.id, repository.getProfile().id);
    assertEquals("另一档案", repository.getProfile().nickname);
    repository.setCurrentProfile(original.id);
    assertEquals("原昵称", repository.getProfile().nickname);
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    idle();
    button(activity, "保存档案").performClick();
    assertEquals("旧档案草稿", repository.getProfile().nickname);
    assertEquals(2, repository.getProfiles().size());
  }

  @Test
  public void fieldLimitsLabelsAndImeActionsSupportTheCompleteForm() {
    ProfileActivity activity = Robolectric.buildActivity(ProfileActivity.class).setup().get();
    button(activity, "创建我的本地档案").performClick();
    List<EditText> edits = fields(activity);
    edits.get(0).setText("名".repeat(30));
    edits.get(1).setText("1234");
    edits.get(2).setText("目标".repeat(80));
    edits.get(3).setText("123");
    assertEquals(24, edits.get(0).length());
    assertEquals(3, edits.get(1).length());
    assertEquals(120, edits.get(2).length());
    assertEquals(2, edits.get(3).length());
    assertEquals(edits.get(0).getId(), text(activity, "昵称 *").getLabelFor());
    assertEquals(edits.get(2).getId(), text(activity, "训练目标 *").getLabelFor());
    edits.get(0).onEditorAction(EditorInfo.IME_ACTION_NEXT);
    assertTrue(edits.get(1).hasFocus());
    edits.get(1).onEditorAction(EditorInfo.IME_ACTION_NEXT);
    assertTrue(edits.get(2).hasFocus());
    edits.get(2).onEditorAction(EditorInfo.IME_ACTION_NEXT);
    assertTrue(edits.get(3).hasFocus());
    edits.get(1).setText("45");
    edits.get(3).setText("5");
    edits.get(3).onEditorAction(EditorInfo.IME_ACTION_DONE);
    assertTrue(activity.isFinishing());
    assertEquals(24, repository.getProfile().nickname.length());
    assertEquals(120, repository.getProfile().goal.length());
    assertEquals(Integer.valueOf(45), repository.getProfile().age);
  }

  @Test
  public void rotationRetainsEditorScrollAndTextSelection() {
    user();
    ActivityController<ProfileActivity> controller =
        Robolectric.buildActivity(ProfileActivity.class).setup();
    ProfileActivity activity = controller.get();
    fields(activity).get(2).setText("保留输入位置的训练目标");
    EditText goal = fields(activity).get(2);
    goal.requestFocus();
    goal.setSelection(2, 5);
    layout(activity);
    ScrollView scroll = activity.findViewById(R.id.screen_scroll);
    scroll.scrollTo(0, 180);
    int position = scroll.getScrollY();
    assertTrue(position > 0);
    Bundle state = new Bundle();
    controller.saveInstanceState(state).pause().stop().destroy();
    activity =
        Robolectric.buildActivity(ProfileActivity.class)
            .create(state)
            .start()
            .resume()
            .visible()
            .get();
    layout(activity);
    assertEquals(position, ((ScrollView) activity.findViewById(R.id.screen_scroll)).getScrollY());
    goal = fields(activity).get(2);
    assertTrue(goal.hasFocus());
    assertEquals(2, goal.getSelectionStart());
    assertEquals(5, goal.getSelectionEnd());
  }

  private void layout(ProfileActivity activity) {
    View root = activity.findViewById(R.id.root);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 480);
    root.getViewTreeObserver().dispatchOnGlobalLayout();
    idle();
  }

  private void idle() {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private List<EditText> fields(ProfileActivity activity) {
    List<EditText> result = new ArrayList<>();
    collect(activity.findViewById(android.R.id.content), result);
    return result;
  }

  private void collect(View view, List<EditText> result) {
    if (view instanceof EditText) result.add((EditText) view);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        collect(((ViewGroup) view).getChildAt(i), result);
  }

  private Spinner spinner(ProfileActivity activity) {
    return findSpinner(activity.findViewById(android.R.id.content));
  }

  private Spinner findSpinner(View view) {
    if (view instanceof Spinner) return (Spinner) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Spinner result = findSpinner(((ViewGroup) view).getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private Button button(ProfileActivity activity, String label) {
    TextView view = text(activity, label);
    assertTrue("缺少按钮：" + label, view instanceof Button);
    return (Button) view;
  }

  private TextView text(ProfileActivity activity, String label) {
    return findText(activity.findViewById(android.R.id.content), label);
  }

  private TextView findText(View view, String label) {
    if (view instanceof TextView && label.contentEquals(((TextView) view).getText()))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView result = findText(((ViewGroup) view).getChildAt(i), label);
        if (result != null) return result;
      }
    return null;
  }
}
