package com.fingerrehab.notification;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/** Non-exact, one-shot reminders. Each delivery schedules the following local calendar day. */
public final class ReminderScheduler {
  public static final String CHANNEL_ID = "daily_training";
  public static final String DESTINATION_EXTRA = "destination";
  public static final String DESTINATION_PLAN = "plan";
  static final String ACTION_REMINDER = "com.fingerrehab.action.DAILY_REMINDER";
  static final String PREFS = "daily_reminder_state";
  static final String GENERATION = "generation";
  static final String DELIVERED_DAYS = "delivered_days";
  static final String PLAN_FINGERPRINT = "plan_fingerprint";
  static final String SCHEDULED_SIGNATURE = "scheduled_signature";
  static final String SCHEDULED_AT = "scheduled_at";
  static final String EXTRA_GENERATION = "generation";
  static final String EXTRA_USER_ID = "user_id";
  static final String EXTRA_PLAN_ID = "plan_id";
  static final String EXTRA_TRIGGER = "trigger_at";
  static final String EXTRA_HOUR = "hour";
  static final String EXTRA_MINUTE = "minute";
  static final String EXTRA_TYPE = "type";
  static final String EXTRA_GOAL = "daily_goal";
  static final int REQUEST_CODE = 4091;
  static final int NOTIFICATION_ID = 4092;

  private ReminderScheduler() {}

  /** Safe to call after saving/deleting a plan, on startup, and after a clock change. */
  public static synchronized void sync(Context context) {
    syncAt(context, System.currentTimeMillis());
  }

  /** Deterministic wall-clock anchor for deferred-delivery and date-boundary regression tests. */
  static synchronized void syncAt(Context context, long nowMillis) {
    syncInternal(context, false, nowMillis);
  }

  /**
   * System alarms are lost at reboot; clock/zone changes must recalculate their wall-clock time.
   */
  public static synchronized void restore(Context context) {
    syncInternal(context, true, System.currentTimeMillis());
  }

  private static void syncInternal(Context context, boolean forceReschedule, long nowMillis) {
    Context app = context.getApplicationContext();
    ensureChannel(app);
    RehabRepository repository = RehabRepository.get(app);
    UserProfile profile = repository.getProfile();
    TrainingPlan plan = repository.getPlan();
    if (!isUsable(profile, plan)) {
      cancel(app);
      return;
    }

    SharedPreferences prefs = preferences(app);
    String fingerprint =
        profile.id
            + ":"
            + plan.id
            + ":"
            + plan.dailyGoal
            + ":"
            + plan.type
            + ":"
            + plan.hour
            + ":"
            + plan.minute;
    String signature = fingerprint + ":" + ZoneId.systemDefault().getId();
    PendingIntent existing =
        PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            reminderIntent(app),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
    if (!forceReschedule
        && existing != null
        && signature.equals(prefs.getString(SCHEDULED_SIGNATURE, null))) {
      // A non-exact alarm can already be due but awaiting OS delivery. Cancelling it on every
      // Activity.onResume would silently skip today's reminder and move it to tomorrow.
      return;
    }
    boolean changedPlan = !fingerprint.equals(prefs.getString(PLAN_FINGERPRINT, null));
    cancelAlarm(app);
    if (changedPlan) dismissNotification(app);

    long generation = preferences(app).getLong(GENERATION, 0);
    long triggerAt = nextTrigger(nowMillis, ZoneId.systemDefault(), plan.hour, plan.minute);
    Intent intent =
        reminderIntent(app)
            .putExtra(EXTRA_GENERATION, generation)
            .putExtra(EXTRA_USER_ID, profile.id)
            .putExtra(EXTRA_PLAN_ID, plan.id)
            .putExtra(EXTRA_TRIGGER, triggerAt)
            .putExtra(EXTRA_HOUR, plan.hour)
            .putExtra(EXTRA_MINUTE, plan.minute)
            .putExtra(EXTRA_TYPE, plan.type)
            .putExtra(EXTRA_GOAL, plan.dailyGoal);
    PendingIntent pending =
        PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
    if (alarms != null) {
      // Intentionally non-exact: Android can defer this alarm for battery conservation.
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
      prefs
          .edit()
          .putString(PLAN_FINGERPRINT, fingerprint)
          .putString(SCHEDULED_SIGNATURE, signature)
          .putLong(SCHEDULED_AT, triggerAt)
          .commit();
    }
  }

  /** Invalidates an already-dispatched old broadcast as well as cancelling its PendingIntent. */
  public static synchronized void cancel(Context context) {
    Context app = context.getApplicationContext();
    cancelAlarm(app);
    preferences(app).edit().remove(PLAN_FINGERPRINT).commit();
    dismissNotification(app);
  }

  private static void cancelAlarm(Context app) {
    SharedPreferences prefs = preferences(app);
    prefs
        .edit()
        .putLong(GENERATION, prefs.getLong(GENERATION, 0) + 1)
        .remove(SCHEDULED_SIGNATURE)
        .remove(SCHEDULED_AT)
        .commit();
    PendingIntent pending =
        PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            reminderIntent(app),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
    if (pending != null) {
      AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
      if (alarms != null) alarms.cancel(pending);
      pending.cancel();
    }
  }

  private static void dismissNotification(Context app) {
    NotificationManager notifications =
        (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
    if (notifications != null) notifications.cancel(NOTIFICATION_ID);
  }

  /**
   * Mark an alarm consumed before scheduling its successor; a PendingIntent can outlive delivery.
   */
  static void consume(Context context) {
    preferences(context).edit().remove(SCHEDULED_SIGNATURE).remove(SCHEDULED_AT).commit();
  }

  static Intent reminderIntent(Context context) {
    return new Intent(context, ReminderReceiver.class).setAction(ACTION_REMINDER);
  }

  static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static boolean isUsable(UserProfile profile, TrainingPlan plan) {
    return profile != null
        && plan != null
        && plan.userId == profile.id
        && plan.reminderEnabled
        && plan.hour >= 0
        && plan.hour <= 23
        && plan.minute >= 0
        && plan.minute <= 59
        && plan.dailyGoal >= 1
        && plan.dailyGoal <= 20
        && validType(plan.type);
  }

  static boolean validType(String type) {
    return "all".equals(type)
        || "trace".equals(type)
        || "reaction".equals(type)
        || "coordination".equals(type);
  }

  /** Uses local calendar arithmetic (not +24h), including spring-forward and fall-back days. */
  static long nextTrigger(long nowMillis, ZoneId zone, int hour, int minute) {
    ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
    LocalDate date = now.toLocalDate();
    ZonedDateTime next = LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone);
    if (!next.toInstant().isAfter(now.toInstant())) {
      next = LocalDateTime.of(date.plusDays(1), java.time.LocalTime.of(hour, minute)).atZone(zone);
    }
    return next.toInstant().toEpochMilli();
  }

  static boolean wasDelivered(Context context, long userId, LocalDate date) {
    return preferences(context)
        .getStringSet(DELIVERED_DAYS, java.util.Collections.emptySet())
        .contains(userId + ":" + date);
  }

  static void markDelivered(Context context, long userId, LocalDate date) {
    SharedPreferences prefs = preferences(context);
    Set<String> dates =
        new HashSet<>(prefs.getStringSet(DELIVERED_DAYS, java.util.Collections.emptySet()));
    dates.add(userId + ":" + date);
    // The small durable set also prevents a duplicate after a timezone change or app restart.
    prefs.edit().putStringSet(DELIVERED_DAYS, dates).commit();
  }

  public static void ensureChannel(Context context) {
    NotificationManager manager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) return;
    NotificationChannel channel =
        new NotificationChannel(CHANNEL_ID, "每日康复训练提醒", NotificationManager.IMPORTANCE_DEFAULT);
    channel.setDescription("按训练计划提醒您完成每日手指康复练习；提醒时间可能因系统省电策略而延后。");
    manager.createNotificationChannel(channel);
  }

  public static boolean canNotify(Context context) {
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
    NotificationManager manager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null || !manager.areNotificationsEnabled()) return false;
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }
}
