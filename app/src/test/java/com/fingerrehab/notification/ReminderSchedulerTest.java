package com.fingerrehab.notification;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReminderSchedulerTest {
  private Context context;
  private RehabRepository repository;
  private AlarmManager alarms;
  private NotificationManager notifications;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", Context.MODE_PRIVATE).edit().clear().commit();
    ReminderScheduler.preferences(context).edit().clear().commit();
    repository = RehabRepository.get(context);
    alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
  }

  @After
  public void tearDown() {
    ReminderScheduler.cancel(context);
    repository.close();
  }

  @Test
  public void nextTriggerUsesCalendarDaysAcrossDstAndSkipsPastTime() {
    ZoneId zone = ZoneId.of("America/New_York");
    long springNow = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, zone).toInstant().toEpochMilli();
    long springNext = ReminderScheduler.nextTrigger(springNow, zone, 20, 0);
    assertEquals(23 * 60 * 60 * 1000L, springNext - springNow);
    long fallNow = ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, zone).toInstant().toEpochMilli();
    long fallNext = ReminderScheduler.nextTrigger(fallNow, zone, 20, 0);
    assertEquals(25 * 60 * 60 * 1000L, fallNext - fallNow);
    long beforeTime =
        ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    assertEquals(
        ZonedDateTime.of(2026, 10, 15, 19, 30, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli(),
        ReminderScheduler.nextTrigger(beforeTime, ZoneId.of("Asia/Shanghai"), 19, 30));
  }

  @Test
  public void missingProfileIsSafeAndCreatesNoAlarm() {
    ReminderScheduler.sync(context);
    assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
  }

  @Test
  public void editingReplacesSingleAlarmAndDisablingOrDeletingCancelsIt() {
    TrainingPlan plan = saveEnabledPlan();
    ReminderScheduler.sync(context);
    assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    long oldGeneration =
        ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0);
    plan.hour = (plan.hour + 1) % 24;
    repository.savePlan(plan);
    ReminderScheduler.sync(context);
    assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    assertTrue(
        ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0)
            > oldGeneration);
    plan.reminderEnabled = false;
    repository.savePlan(plan);
    ReminderScheduler.sync(context);
    assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    plan.reminderEnabled = true;
    repository.savePlan(plan);
    ReminderScheduler.sync(context);
    repository.deletePlan();
    ReminderScheduler.sync(context);
    assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
  }

  @Test
  public void receiverPostsOnceAndImmediatelySchedulesFollowingDay() {
    TrainingPlan plan = saveEnabledPlan();
    ReminderScheduler.sync(context);
    Intent due = dueIntent(plan);
    new ReminderReceiver().onReceive(context, due);
    Notification first =
        Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID);
    assertNotNull(first);
    assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    LocalDate today =
        Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .toLocalDate();
    assertTrue(ReminderScheduler.wasDelivered(context, plan.userId, today));
    // A repeated delivery of the old PendingIntent is rejected by its invalidated generation.
    new ReminderReceiver().onReceive(context, due);
    assertSame(
        first, Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
    // Even a newly scheduled delivery cannot post a second notification on the same local date.
    new ReminderReceiver().onReceive(context, dueIntent(plan));
    assertSame(
        first, Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
    assertEquals(
        1,
        ReminderScheduler.preferences(context)
            .getStringSet(ReminderScheduler.DELIVERED_DAYS, java.util.Collections.emptySet())
            .size());
  }

  @Test
  public void foregroundSyncPreservesAnOverdueAlarmWaitingForSystemDelivery() {
    saveEnabledPlan();
    ShadowAlarmManager.setAutoSchedule(false);
    ReminderScheduler.sync(context);
    ShadowAlarmManager.ScheduledAlarm original =
        Shadows.shadowOf(alarms).getScheduledAlarms().get(0);
    long generation =
        ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0);
    ReminderScheduler.syncAt(context, original.triggerAtTime + 600_000);
    assertSame(original, Shadows.shadowOf(alarms).getScheduledAlarms().get(0));
    assertEquals(
        generation,
        ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0));
  }

  @Test
  public void routineSyncAndClockRestoreKeepDeliveredNotificationUntilPlanIsDisabled() {
    TrainingPlan plan = saveEnabledPlan();
    ReminderScheduler.sync(context);
    new ReminderReceiver().onReceive(context, dueIntent(plan));
    Notification first =
        Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID);
    assertNotNull(first);
    ReminderScheduler.sync(context);
    assertSame(
        first, Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
    new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIMEZONE_CHANGED));
    assertSame(
        first, Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
    plan.reminderEnabled = false;
    repository.savePlan(plan);
    ReminderScheduler.sync(context);
    assertNull(Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
  }

  @Test
  public void timezoneChangeRecalculatesTheActualTriggerInTheNewLocalZone() {
    java.util.TimeZone original = java.util.TimeZone.getDefault();
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
      TrainingPlan plan = saveEnabledPlan();
      ReminderScheduler.sync(context);
      long before = Shadows.shadowOf(alarms).getScheduledAlarms().get(0).triggerAtTime;
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
      new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIMEZONE_CHANGED));
      long after = Shadows.shadowOf(alarms).getScheduledAlarms().get(0).triggerAtTime;
      assertNotEquals(before, after);
      assertEquals(
          ReminderScheduler.nextTrigger(
              System.currentTimeMillis(), ZoneId.systemDefault(), plan.hour, plan.minute),
          after);
      assertEquals(19, Instant.ofEpochMilli(after).atZone(ZoneId.systemDefault()).getHour());
      assertEquals(30, Instant.ofEpochMilli(after).atZone(ZoneId.systemDefault()).getMinute());
    } finally {
      java.util.TimeZone.setDefault(original);
    }
  }

  @Test
  public void disabledNotificationsDoNotMarkDeliveredAndTomorrowIsStillScheduled() {
    TrainingPlan plan = saveEnabledPlan();
    ReminderScheduler.sync(context);
    Shadows.shadowOf(notifications).setNotificationsEnabled(false);
    new ReminderReceiver().onReceive(context, dueIntent(plan));
    assertNull(Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
    assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    assertTrue(
        ReminderScheduler.preferences(context)
            .getStringSet(ReminderScheduler.DELIVERED_DAYS, java.util.Collections.emptySet())
            .isEmpty());
  }

  @Test
  public void staleBroadcastAfterPlanDeletionCannotRestoreAlarm() {
    TrainingPlan plan = saveEnabledPlan();
    ReminderScheduler.sync(context);
    Intent due = dueIntent(plan);
    repository.deletePlan();
    ReminderScheduler.sync(context);
    new ReminderReceiver().onReceive(context, due);
    assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    assertNull(Shadows.shadowOf(notifications).getNotification(ReminderScheduler.NOTIFICATION_ID));
  }

  @Test
  public void clockAndBootEventsRestoreOnlyOneCurrentAlarm() {
    saveEnabledPlan();
    BootReceiver receiver = new BootReceiver();
    for (String action :
        new String[] {
          Intent.ACTION_BOOT_COMPLETED,
          Intent.ACTION_TIME_CHANGED,
          Intent.ACTION_TIMEZONE_CHANGED,
          Intent.ACTION_MY_PACKAGE_REPLACED
        }) {
      long before = ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0);
      receiver.onReceive(context, new Intent(action));
      assertEquals(1, Shadows.shadowOf(alarms).getScheduledAlarms().size());
      assertTrue(
          ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0) > before);
    }
  }

  private TrainingPlan saveEnabledPlan() {
    UserProfile user = new UserProfile();
    user.nickname = "提醒测试";
    repository.saveProfile(user);
    TrainingPlan plan = new TrainingPlan();
    plan.userId = user.id;
    plan.hour = 19;
    plan.minute = 30;
    plan.dailyGoal = 3;
    plan.type = "all";
    plan.reminderEnabled = true;
    repository.savePlan(plan);
    return plan;
  }

  private Intent dueIntent(TrainingPlan plan) {
    return ReminderScheduler.reminderIntent(context)
        .putExtra(
            ReminderScheduler.EXTRA_GENERATION,
            ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0))
        .putExtra(ReminderScheduler.EXTRA_USER_ID, plan.userId)
        .putExtra(ReminderScheduler.EXTRA_PLAN_ID, plan.id)
        .putExtra(ReminderScheduler.EXTRA_HOUR, plan.hour)
        .putExtra(ReminderScheduler.EXTRA_MINUTE, plan.minute)
        .putExtra(ReminderScheduler.EXTRA_TYPE, plan.type)
        .putExtra(ReminderScheduler.EXTRA_GOAL, plan.dailyGoal)
        .putExtra(ReminderScheduler.EXTRA_TRIGGER, System.currentTimeMillis() - 1);
  }
}
