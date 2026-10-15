package com.fingerrehab.notification;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.ui.PlanActivity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlanActivityTest {
  private Context context;
  private RehabRepository repository;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", Context.MODE_PRIVATE).edit().clear().commit();
    ReminderScheduler.preferences(context).edit().clear().commit();
    repository = RehabRepository.get(context);
  }

  @After
  public void tearDown() {
    ReminderScheduler.cancel(context);
    repository.close();
  }

  @Test
  public void absentProfileOpensSafeExplanationInsteadOfPlanEditor() {
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    assertNotNull(button(activity, "返回首页"));
    assertNull(find(activity.findViewById(android.R.id.content), EditText.class));
    assertNull(repository.getPlan());
  }

  @Test
  public void invalidGoalsCannotSaveAndValidGoalPersists() {
    repository.saveProfile(new UserProfile());
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    EditText input = find(activity.findViewById(android.R.id.content), EditText.class);
    Button save = button(activity, "保存训练计划");
    input.setText("0");
    save.performClick();
    assertNull(repository.getPlan());
    assertNotNull(input.getError());
    input.setText("21");
    save.performClick();
    assertNull(repository.getPlan());
    input.setText("5");
    save.performClick();
    assertEquals(5, repository.getPlan().dailyGoal);
    assertFalse(repository.getPlan().reminderEnabled);
    assertNull(input.getError());
  }

  @Test
  public void deletingRequiresConfirmationAndNegativeChoicePreservesPlan() {
    repository.saveProfile(new UserProfile());
    TrainingPlan plan = new TrainingPlan();
    repository.savePlan(plan);
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    button(activity, "删除训练计划").performClick();
    assertNotNull(repository.getPlan());
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNotNull(repository.getPlan());
    button(activity, "删除训练计划").performClick();
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNull(repository.getPlan());
    assertTrue(activity.isFinishing());
  }

  @Test
  public void failedDatabaseSaveExplainsFailureAndKeepsTheEditorOpen() {
    repository.saveProfile(new UserProfile());
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    find(activity.findViewById(android.R.id.content), EditText.class).setText("7");
    try (SQLiteDatabase database = context.openOrCreateDatabase(RehabDatabase.NAME, 0, null)) {
      database.execSQL(
          "CREATE TRIGGER reject_plan_insert BEFORE INSERT ON plans BEGIN SELECT RAISE(ABORT, 'test"
              + " full disk'); END");
      button(activity, "保存训练计划").performClick();
      assertNull(repository.getPlan());
      assertFalse(activity.isFinishing());
      assertTrue(containsText(activity.findViewById(android.R.id.content), "计划保存失败"));
      database.execSQL("DROP TRIGGER reject_plan_insert");
    }
    button(activity, "保存训练计划").performClick();
    assertNotNull(repository.getPlan());
    assertEquals(7, repository.getPlan().dailyGoal);
    assertNull(find(activity.findViewById(android.R.id.content), EditText.class).getError());
    activity.onBackPressed();
    assertTrue(activity.isFinishing());
  }

  @Test
  public void backConfirmsUnsavedDraftAndContinueEditingKeepsIt() {
    repository.saveProfile(new UserProfile());
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    EditText input = find(activity.findViewById(android.R.id.content), EditText.class);
    input.setText("8");
    activity.onBackPressed();
    AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(confirmation.isShowing());
    activity.onBackPressed();
    assertSame(confirmation, ShadowAlertDialog.getLatestAlertDialog());
    confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertFalse(activity.isFinishing());
    assertEquals("8", input.getText().toString());
    assertNull(repository.getPlan());
    activity.onBackPressed();
    ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertTrue(activity.isFinishing());
    assertNull(repository.getPlan());
  }

  @Test
  public void exitCanSaveDraftBeforeReturningAndIgnoresRepeatedDialogCallback() {
    repository.saveProfile(new UserProfile());
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    find(activity.findViewById(android.R.id.content), EditText.class).setText("6");
    activity.onBackPressed();
    Button saveAndReturn =
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL);
    saveAndReturn.performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertEquals(6, repository.getPlan().dailyGoal);
    assertTrue(activity.isFinishing());
    find(activity.findViewById(android.R.id.content), EditText.class).setText("9");
    saveAndReturn.performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertEquals(6, repository.getPlan().dailyGoal);
  }

  @Test
  public void rotatingRetainsEveryDraftFieldAndOriginalSavedBaseline() {
    repository.saveProfile(new UserProfile());
    repository.savePlan(new TrainingPlan());
    ActivityController<PlanActivity> controller =
        Robolectric.buildActivity(PlanActivity.class).setup();
    PlanActivity activity = controller.get();
    View content = activity.findViewById(android.R.id.content);
    find(content, EditText.class).setText("7");
    find(content, Spinner.class).setSelection(2);
    find(content, Switch.class).setChecked(true);
    button(activity, "提醒时间  20:00").performClick();
    TimePickerDialog picker = (TimePickerDialog) ShadowDialog.getLatestDialog();
    picker.updateTime(8, 45);
    picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    controller.recreate();
    activity = controller.get();
    content = activity.findViewById(android.R.id.content);
    assertEquals("7", find(content, EditText.class).getText().toString());
    assertEquals(2, find(content, Spinner.class).getSelectedItemPosition());
    assertTrue(find(content, Switch.class).isChecked());
    assertNotNull(button(activity, "提醒时间  08:45"));
    assertEquals(3, repository.getPlan().dailyGoal);
    assertFalse(repository.getPlan().reminderEnabled);
    activity.onBackPressed();
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    assertFalse(activity.isFinishing());
  }

  @Test
  public void rotationRestoresOpenTimePickerWithoutCommittingOrLosingItsSelection() {
    repository.saveProfile(new UserProfile());
    ActivityController<PlanActivity> controller =
        Robolectric.buildActivity(PlanActivity.class).setup();
    PlanActivity activity = controller.get();
    find(activity.findViewById(android.R.id.content), Switch.class).setChecked(true);
    button(activity, "提醒时间  19:00").performClick();
    TimePickerDialog picker = (TimePickerDialog) ShadowDialog.getLatestDialog();
    picker.updateTime(9, 15);
    button(activity, "提醒时间  19:00").performClick();
    assertSame(picker, ShadowDialog.getLatestDialog());
    controller.recreate();
    TimePickerDialog restored = (TimePickerDialog) ShadowDialog.getLatestDialog();
    assertNotSame(picker, restored);
    assertTrue(restored.isShowing());
    restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNotNull(button(controller.get(), "提醒时间  09:15"));
    assertNull(repository.getPlan());
  }

  @Test
  public void repeatedDeleteClicksUseOneConfirmationAndOneDeletion() {
    repository.saveProfile(new UserProfile());
    repository.savePlan(new TrainingPlan());
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    Button delete = button(activity, "删除训练计划");
    delete.performClick();
    AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
    delete.performClick();
    assertSame(confirmation, ShadowAlertDialog.getLatestAlertDialog());
    Button confirm = confirmation.getButton(AlertDialog.BUTTON_POSITIVE);
    confirm.performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    confirm.performClick();
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNull(repository.getPlan());
    assertTrue(activity.isFinishing());
  }

  @Test
  public void failedDatabaseDeleteExplainsFailureAndPreservesThePlan() {
    repository.saveProfile(new UserProfile());
    repository.savePlan(new TrainingPlan());
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    try (SQLiteDatabase database = context.openOrCreateDatabase(RehabDatabase.NAME, 0, null)) {
      database.execSQL(
          "CREATE TRIGGER reject_plan_delete BEFORE DELETE ON plans BEGIN SELECT RAISE(ABORT, 'test"
              + " full disk'); END");
      button(activity, "删除训练计划").performClick();
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .performClick();
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      assertNotNull(repository.getPlan());
      assertFalse(activity.isFinishing());
      assertTrue(containsText(activity.findViewById(android.R.id.content), "计划删除失败"));
    }
  }

  private boolean containsText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
      return true;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (containsText(((ViewGroup) view).getChildAt(i), text)) return true;
    return false;
  }

  private Button button(PlanActivity activity, String title) {
    return findButton(activity.findViewById(android.R.id.content), title);
  }

  private Button findButton(View view, String title) {
    if (view instanceof Button && title.contentEquals(((Button) view).getText()))
      return (Button) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = findButton(group.getChildAt(i), title);
        if (found != null) return found;
      }
    }
    return null;
  }

  private <T extends View> T find(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        T found = find(group.getChildAt(i), type);
        if (found != null) return found;
      }
    }
    return null;
  }
}
