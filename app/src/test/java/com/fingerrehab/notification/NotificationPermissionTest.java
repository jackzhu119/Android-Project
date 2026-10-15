package com.fingerrehab.notification;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

/**
 * Exercises the real Android 13 POST_NOTIFICATIONS branch, separate from the app settings switch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NotificationPermissionTest {
  private Context context;
  private RehabRepository repository;
  private NotificationManager notifications;
  private AlarmManager alarms;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", Context.MODE_PRIVATE).edit().clear().commit();
    ReminderScheduler.preferences(context).edit().clear().commit();
    repository = RehabRepository.get(context);
    repository.saveProfile(new UserProfile());
    notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
  }

  @After
  public void tearDown() {
    ReminderScheduler.cancel(context);
    repository.close();
  }

  @Test
  public void denyingRuntimePermissionKeepsSavedPlanAndExplainsBlockedReminder() {
    PlanActivity activity = openAndSaveEnabledPlan();
    ShadowActivity.PermissionsRequest request =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    assertNotNull(request);
    assertArrayEquals(
        new String[] {Manifest.permission.POST_NOTIFICATIONS}, request.requestedPermissions);
    activity.onRequestPermissionsResult(
        request.requestCode,
        request.requestedPermissions,
        new int[] {PackageManager.PERMISSION_DENIED});
    assertTrue(repository.getPlan().reminderEnabled);
    assertFalse(ReminderScheduler.canNotify(context));
    assertTrue(containsText(content(activity), "计划已保存，但通知权限未允许"));
    assertEquals(View.VISIBLE, button(content(activity), "允许通知").getVisibility());
    assertEquals(View.VISIBLE, button(content(activity), "打开系统通知设置").getVisibility());
    new ReminderReceiver().onReceive(context, dueIntent());
    assertNull(Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
    assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    assertTrue(
        ReminderScheduler.preferences(context)
            .getStringSet(ReminderScheduler.DELIVERED_DAYS, java.util.Collections.emptySet())
            .isEmpty());
  }

  @Test
  public void grantingRuntimePermissionEnablesRealNotificationAndPlanDestination() {
    PlanActivity activity = openAndSaveEnabledPlan();
    ShadowActivity.PermissionsRequest request =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    activity.onRequestPermissionsResult(
        request.requestCode,
        request.requestedPermissions,
        new int[] {PackageManager.PERMISSION_GRANTED});
    assertTrue(ReminderScheduler.canNotify(context));
    assertEquals(View.GONE, button(content(activity), "允许通知").getVisibility());
    new ReminderReceiver().onReceive(context, dueIntent());
    android.app.Notification notification =
        Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID);
    assertNotNull(notification);
    Intent destination = Shadows.shadowOf(notification.contentIntent).getSavedIntent();
    assertEquals("plan", destination.getStringExtra(ReminderScheduler.DESTINATION_EXTRA));
    assertEquals("com.fingerrehab.ui.MainActivity", destination.getComponent().getClassName());
    assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
  }

  @Test
  public void applicationNotificationSwitchStillBlocksGrantedRuntimePermission() {
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    Shadows.shadowOf(notifications).setNotificationsEnabled(false);
    PlanActivity activity = openAndSaveEnabledPlan();
    assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    assertFalse(ReminderScheduler.canNotify(context));
    assertTrue(containsText(content(activity), "当前通知未获允许"));
    assertEquals(View.VISIBLE, button(content(activity), "打开系统通知设置").getVisibility());
    new ReminderReceiver().onReceive(context, dueIntent());
    assertNull(Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
  }

  @Test
  public void disabledChannelIsNotReenabledBySchedulerOrPermissionCallback() {
    notifications.createNotificationChannel(
        new NotificationChannel(
            ReminderScheduler.CHANNEL_ID, "每日康复训练提醒", NotificationManager.IMPORTANCE_NONE));
    PlanActivity activity = openAndSaveEnabledPlan();
    ShadowActivity.PermissionsRequest request =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    activity.onRequestPermissionsResult(
        request.requestCode,
        request.requestedPermissions,
        new int[] {PackageManager.PERMISSION_GRANTED});
    assertFalse(ReminderScheduler.canNotify(context));
    assertEquals(
        NotificationManager.IMPORTANCE_NONE,
        notifications.getNotificationChannel(ReminderScheduler.CHANNEL_ID).getImportance());
    assertTrue(containsText(content(activity), "系统通知或提醒渠道仍关闭"));
    new ReminderReceiver().onReceive(context, dueIntent());
    assertNull(Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
  }

  @Test
  public void permissionOnUnsavedDraftDoesNotSaveItOrClaimItWasSaved() {
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    findSwitch(content(activity)).setChecked(true);
    button(content(activity), "允许通知").performClick();
    ShadowActivity.PermissionsRequest request =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    activity.onRequestPermissionsResult(
        request.requestCode,
        request.requestedPermissions,
        new int[] {PackageManager.PERMISSION_GRANTED});
    assertNull(repository.getPlan());
    assertTrue(containsText(content(activity), "当前修改尚未保存"));
    assertFalse(containsText(content(activity), "已保存的计划将按设置提醒"));
    activity.onBackPressed();
    assertFalse(activity.isFinishing());
    assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing());
  }

  @Test
  public void repeatedSaveAndDuplicatePermissionCallbackDoNotRepromptOrReplaceDraftFeedback() {
    PlanActivity activity = openAndSaveEnabledPlan();
    ShadowActivity.PermissionsRequest first =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    button(content(activity), "保存训练计划").performClick();
    assertSame(first, Shadows.shadowOf(activity).getLastRequestedPermission());
    activity.onRequestPermissionsResult(
        first.requestCode,
        first.requestedPermissions,
        new int[] {PackageManager.PERMISSION_DENIED});
    button(content(activity), "保存训练计划").performClick();
    assertSame(first, Shadows.shadowOf(activity).getLastRequestedPermission());
    findEditText(content(activity)).setText("9");
    button(content(activity), "允许通知").performClick();
    ShadowActivity.PermissionsRequest second =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    assertNotSame(first, second);
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    activity.onRequestPermissionsResult(
        second.requestCode,
        second.requestedPermissions,
        new int[] {PackageManager.PERMISSION_GRANTED});
    assertTrue(containsText(content(activity), "当前修改尚未保存"));
    activity.onRequestPermissionsResult(
        second.requestCode,
        second.requestedPermissions,
        new int[] {PackageManager.PERMISSION_DENIED});
    assertTrue(containsText(content(activity), "通知权限已允许。当前修改尚未保存"));
    assertEquals("9", findEditText(content(activity)).getText().toString());
    assertEquals(3, repository.getPlan().dailyGoal);
  }

  @Test
  public void rotationWhilePermissionIsPendingKeepsDraftAndConsumesOnlyOneCallback() {
    ActivityController<PlanActivity> controller =
        Robolectric.buildActivity(PlanActivity.class).setup();
    PlanActivity activity = controller.get();
    findSwitch(content(activity)).setChecked(true);
    button(content(activity), "保存训练计划").performClick();
    ShadowActivity.PermissionsRequest pending =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    findEditText(content(activity)).setText("8");
    controller.recreate();
    activity = controller.get();
    assertEquals("8", findEditText(content(activity)).getText().toString());
    assertFalse(button(content(activity), "允许通知").isEnabled());
    button(content(activity), "允许通知").performClick();
    assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
    activity.onRequestPermissionsResult(
        pending.requestCode,
        pending.requestedPermissions,
        new int[] {PackageManager.PERMISSION_DENIED});
    assertTrue(containsText(content(activity), "当前修改尚未保存"));
    assertTrue(button(content(activity), "允许通知").isEnabled());
    assertEquals(3, repository.getPlan().dailyGoal);
  }

  @Test
  public void returningFromNotificationSettingsRefreshesFeedbackAndKeepsUnsavedDraft() {
    ActivityController<PlanActivity> controller =
        Robolectric.buildActivity(PlanActivity.class).setup();
    PlanActivity activity = controller.get();
    findSwitch(content(activity)).setChecked(true);
    button(content(activity), "保存训练计划").performClick();
    ShadowActivity.PermissionsRequest request =
        Shadows.shadowOf(activity).getLastRequestedPermission();
    activity.onRequestPermissionsResult(
        request.requestCode,
        request.requestedPermissions,
        new int[] {PackageManager.PERMISSION_DENIED});
    findEditText(content(activity)).setText("8");
    button(content(activity), "打开系统通知设置").performClick();
    Intent settings = Shadows.shadowOf(activity).getNextStartedActivity();
    assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    controller.pause().resume();
    assertTrue(containsText(content(activity), "系统通知已允许。当前修改尚未保存"));
    assertFalse(containsText(content(activity), "计划已保存，但通知权限未允许"));
    assertEquals("8", findEditText(content(activity)).getText().toString());
    assertEquals(3, repository.getPlan().dailyGoal);
    assertEquals(View.GONE, button(content(activity), "打开系统通知设置").getVisibility());
  }

  private PlanActivity openAndSaveEnabledPlan() {
    PlanActivity activity = Robolectric.buildActivity(PlanActivity.class).setup().get();
    findSwitch(content(activity)).setChecked(true);
    button(content(activity), "保存训练计划").performClick();
    assertNotNull(repository.getPlan());
    return activity;
  }

  private Intent dueIntent() {
    TrainingPlan plan = repository.getPlan();
    return ReminderScheduler.reminderIntent(context)
        .putExtra(
            ReminderScheduler.EXTRA_GENERATION,
            ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0))
        .putExtra(ReminderScheduler.EXTRA_USER_ID, plan.userId)
        .putExtra(ReminderScheduler.EXTRA_PLAN_ID, plan.id)
        .putExtra(ReminderScheduler.EXTRA_HOUR, plan.hour)
        .putExtra(ReminderScheduler.EXTRA_MINUTE, plan.minute)
        .putExtra(ReminderScheduler.EXTRA_GOAL, plan.dailyGoal)
        .putExtra(ReminderScheduler.EXTRA_TYPE, plan.type)
        .putExtra(ReminderScheduler.EXTRA_TRIGGER, System.currentTimeMillis() - 1);
  }

  private View content(PlanActivity activity) {
    return activity.findViewById(android.R.id.content);
  }

  private Switch findSwitch(View view) {
    if (view instanceof Switch) return (Switch) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Switch found = findSwitch(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private EditText findEditText(View view) {
    if (view instanceof EditText) return (EditText) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        EditText found = findEditText(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private Button button(View view, String title) {
    if (view instanceof Button && title.contentEquals(((Button) view).getText()))
      return (Button) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Button found = button(((ViewGroup) view).getChildAt(i), title);
        if (found != null) return found;
      }
    return null;
  }

  private boolean containsText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
      return true;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (containsText(((ViewGroup) view).getChildAt(i), text)) return true;
    return false;
  }
}
