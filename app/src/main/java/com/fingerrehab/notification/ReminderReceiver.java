package com.fingerrehab.notification;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.ui.MainActivity;
import com.fingerrehab.utils.Labels;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Validates persisted state before posting; stale/cancelled alarms can never resurrect a plan. */
public final class ReminderReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !ReminderScheduler.ACTION_REMINDER.equals(intent.getAction())) return;
    synchronized (ReminderScheduler.class) {
      if (intent.getLongExtra(ReminderScheduler.EXTRA_GENERATION, -1)
          != ReminderScheduler.preferences(context).getLong(ReminderScheduler.GENERATION, 0))
        return;
      RehabRepository repository = RehabRepository.get(context);
      UserProfile profile = repository.getProfile();
      TrainingPlan plan = repository.getPlan();
      if (!ReminderScheduler.isUsable(profile, plan)) {
        ReminderScheduler.cancel(context);
        return;
      }
      if (intent.getLongExtra(ReminderScheduler.EXTRA_USER_ID, -1) != profile.id
          || intent.getLongExtra(ReminderScheduler.EXTRA_PLAN_ID, -1) != plan.id
          || intent.getIntExtra(ReminderScheduler.EXTRA_HOUR, -1) != plan.hour
          || intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE, -1) != plan.minute
          || intent.getIntExtra(ReminderScheduler.EXTRA_GOAL, -1) != plan.dailyGoal
          || !Objects.equals(intent.getStringExtra(ReminderScheduler.EXTRA_TYPE), plan.type))
        return;

      long now = System.currentTimeMillis();
      long scheduledAt = intent.getLongExtra(ReminderScheduler.EXTRA_TRIGGER, Long.MAX_VALUE);
      // Also handles a backward clock adjustment racing a broadcast delivery.
      if (now < scheduledAt) {
        ReminderScheduler.sync(context);
        return;
      }
      LocalDate date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
      boolean alreadyDelivered = ReminderScheduler.wasDelivered(context, profile.id, date);
      ReminderScheduler.consume(context);
      ReminderScheduler.sync(context);
      if (alreadyDelivered || !ReminderScheduler.canNotify(context)) return;

      Intent openPlan =
          new Intent(context, MainActivity.class)
              .putExtra(ReminderScheduler.DESTINATION_EXTRA, ReminderScheduler.DESTINATION_PLAN)
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent open =
          PendingIntent.getActivity(
              context,
              ReminderScheduler.REQUEST_CODE,
              openPlan,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      String mode = "all".equals(plan.type) ? "手指康复" : Labels.type(plan.type);
      Notification notification =
          new Notification.Builder(context, ReminderScheduler.CHANNEL_ID)
              .setSmallIcon(com.fingerrehab.R.drawable.ic_training_reminder)
              .setContentTitle("该做今天的康复训练了")
              .setContentText("今日目标：完成 " + plan.dailyGoal + " 次" + mode + "训练。请量力而行。")
              .setStyle(
                  new Notification.BigTextStyle()
                      .bigText(
                          "今日目标：完成 " + plan.dailyGoal + " 次" + mode + "训练。保持舒适节奏，感到疼痛或不适时请停止练习。"))
              .setContentIntent(open)
              .setAutoCancel(true)
              .setCategory(Notification.CATEGORY_REMINDER)
              .build();
      NotificationManager manager =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager == null) return;
      try {
        manager.notify(ReminderScheduler.NOTIFICATION_ID, notification);
        ReminderScheduler.markDelivered(context, profile.id, date);
      } catch (SecurityException denied) {
        // Permission can be revoked between canNotify and notify; keep tomorrow scheduled.
      }
    }
  }
}
