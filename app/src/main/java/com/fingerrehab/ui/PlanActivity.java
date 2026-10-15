package com.fingerrehab.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import com.fingerrehab.model.TrainingPlan;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.notification.ReminderScheduler;
import com.fingerrehab.repository.RehabRepository;
import java.util.Locale;

/** Single local daily plan editor. Denying notifications never prevents saving a training goal. */
public final class PlanActivity extends Activity {
  private static final int REQUEST_NOTIFICATIONS = 710;
  private static final String[] TYPES = {"all", "trace", "reaction", "coordination"};
  private RehabRepository repository;
  private TrainingPlan plan;
  private long editingUserId;
  private EditText dailyGoal;
  private Spinner type;
  private Switch reminder;
  private Button timeButton, permissionButton, settingsButton, deleteButton;
  private TextView notificationStatus, saveStatus;
  private int hour = 19, minute = 0;
  private String savedDraft;
  private boolean permissionRequestInFlight, permissionRequestedThisSession, returningFromSettings;
  private boolean deleting;
  private AlertDialog exitDialog, deleteDialog;
  private TimePickerDialog timeDialog;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    repository = RehabRepository.get(this);
    LinearLayout content = Ui.screen(this, "训练计划");
    UserProfile profile = repository.getProfile();
    if (profile == null) {
      Ui.add(content, Ui.text(this, "请先在首页建立个人档案，再设置每日训练计划。", 16, Ui.INK));
      Button back = Ui.button(this, "返回首页");
      back.setOnClickListener(v -> finish());
      Ui.add(content, back);
      ReminderScheduler.cancel(this);
      return;
    }
    editingUserId = profile.id;
    boolean restoreDraft =
        savedInstanceState != null
            && savedInstanceState.getLong("editing_user", editingUserId) == editingUserId;
    plan = repository.getPlan();
    if (plan != null) {
      hour = plan.hour;
      minute = plan.minute;
    }
    if (restoreDraft) {
      hour = savedInstanceState.getInt("hour", hour);
      minute = savedInstanceState.getInt("minute", minute);
      permissionRequestInFlight = savedInstanceState.getBoolean("permission_in_flight");
      permissionRequestedThisSession = savedInstanceState.getBoolean("permission_asked");
      returningFromSettings = savedInstanceState.getBoolean("returning_settings");
    }
    Ui.add(content, Ui.text(this, "把练习安排进日常，按自己的节奏完成。目标次数代表完成的有效训练会话数。", 15, Ui.MUTED));
    Ui.gap(content, 16);
    LinearLayout goalCard = Ui.card(this);
    Ui.title(goalCard, "每日目标", "每天完成 1–20 次有效训练");
    dailyGoal = new EditText(this);
    dailyGoal.setId(View.generateViewId());
    dailyGoal.setTextSize(22);
    dailyGoal.setTextColor(Ui.INK);
    dailyGoal.setInputType(InputType.TYPE_CLASS_NUMBER);
    dailyGoal.setSingleLine(true);
    dailyGoal.setSelectAllOnFocus(true);
    dailyGoal.setContentDescription("每日训练目标次数，1到20次");
    dailyGoal.setText(
        !restoreDraft
            ? String.valueOf(
                plan == null ? Math.max(1, Math.min(20, profile.dailyGoal)) : plan.dailyGoal)
            : savedInstanceState.getString("goal", "3"));
    Ui.add(goalCard, dailyGoal);
    Ui.gap(goalCard, 8);
    Ui.add(goalCard, Ui.text(this, "训练模式", 15, Ui.INK));
    type = new Spinner(this);
    type.setId(View.generateViewId());
    type.setContentDescription("训练计划模式");
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[] {"全部训练", "轨迹训练", "反应训练", "协调训练"});
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    type.setAdapter(adapter);
    for (int i = 0; i < TYPES.length; i++)
      if (plan != null && TYPES[i].equals(plan.type)) type.setSelection(i);
    if (restoreDraft)
      type.setSelection(
          Math.max(0, Math.min(TYPES.length - 1, savedInstanceState.getInt("type", 0))));
    Ui.add(goalCard, type);
    Ui.add(content, goalCard);
    Ui.gap(content, 16);
    LinearLayout reminderCard = Ui.card(this);
    Ui.title(reminderCard, "每日提醒", "每天按本地时间提醒一次");
    reminder = new Switch(this);
    reminder.setId(View.generateViewId());
    reminder.setText("开启每日训练提醒");
    reminder.setTextSize(16);
    reminder.setTextColor(Ui.INK);
    reminder.setChecked(
        !restoreDraft
            ? plan != null && plan.reminderEnabled
            : savedInstanceState.getBoolean("enabled", false));
    Ui.add(reminderCard, reminder);
    timeButton = Ui.button(this, "");
    updateTime();
    timeButton.setOnClickListener(v -> showTimePicker(null));
    Ui.add(reminderCard, timeButton);
    Ui.add(
        reminderCard,
        Ui.text(this, "提醒采用系统非精确闹钟，省电模式下可能延后。更改时区后会按新的本地时间安排；设备重启后自动恢复。", 13, Ui.MUTED));
    notificationStatus = Ui.text(this, "", 14, Ui.MUTED);
    Ui.add(reminderCard, notificationStatus);
    permissionButton = Ui.button(this, "允许通知");
    permissionButton.setOnClickListener(v -> requestNotificationPermission());
    Ui.add(reminderCard, permissionButton);
    settingsButton = Ui.button(this, "打开系统通知设置");
    settingsButton.setOnClickListener(v -> openNotificationSettings());
    Ui.add(reminderCard, settingsButton);
    reminder.setOnCheckedChangeListener((button, checked) -> updatePermissionStatus());
    Ui.add(content, reminderCard);
    Ui.gap(content, 16);
    saveStatus = Ui.text(this, "", 14, Ui.TEAL);
    saveStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    Ui.add(content, saveStatus);
    Button save = Ui.button(this, "保存训练计划");
    save.setOnClickListener(v -> savePlan());
    Ui.add(content, save);
    deleteButton = Ui.button(this, "删除训练计划");
    deleteButton.setOnClickListener(v -> confirmDelete());
    deleteButton.setVisibility(plan == null ? View.GONE : View.VISIBLE);
    Ui.add(content, deleteButton);
    Ui.add(content, Ui.text(this, "练习建议：保持轻松、不勉强。若出现疼痛或明显不适，请停止并咨询专业人员。", 13, Ui.MUTED));
    savedDraft =
        restoreDraft ? savedInstanceState.getString("saved_draft", draftKey()) : draftKey();
    if (restoreDraft) {
      saveStatus.setText(savedInstanceState.getCharSequence("save_status", ""));
      dailyGoal.setError(savedInstanceState.getCharSequence("goal_error"));
    } else if (savedInstanceState != null) {
      saveStatus.setText("个人档案已改变，已载入当前档案的计划，请重新确认后编辑。");
    }
    updatePermissionStatus();
    if (restoreDraft) {
      if (savedInstanceState.getBoolean("exit_dialog")) confirmDiscard();
      else if (savedInstanceState.getBoolean("delete_dialog")) confirmDelete();
      else if (savedInstanceState.containsKey("time_dialog"))
        showTimePicker(savedInstanceState.getBundle("time_dialog"));
    }
  }

  private String draftKey() {
    if (dailyGoal == null) return "";
    return dailyGoal.getText().toString().trim()
        + ":"
        + type.getSelectedItemPosition()
        + ":"
        + hour
        + ":"
        + minute
        + ":"
        + reminder.isChecked();
  }

  private boolean hasUnsavedChanges() {
    return savedDraft != null && !savedDraft.equals(draftKey());
  }

  @Override
  public void onBackPressed() {
    if (isFinishing()) return;
    if (hasUnsavedChanges()) confirmDiscard();
    else super.onBackPressed();
  }

  private void confirmDiscard() {
    if (!hasUnsavedChanges() || (exitDialog != null && exitDialog.isShowing())) return;
    boolean[] handled = {false};
    exitDialog =
        new AlertDialog.Builder(this)
            .setTitle("放弃未保存的修改？")
            .setMessage("返回将丢弃尚未保存的目标、模式或提醒修改。")
            .setNegativeButton("继续编辑", (dialog, which) -> handled[0] = true)
            .setNeutralButton(
                "保存并返回",
                (dialog, which) -> {
                  if (handled[0] || isFinishing()) return;
                  handled[0] = true;
                  savePlan(false);
                  if (plan != null && !hasUnsavedChanges()) finish();
                })
            .setPositiveButton(
                "放弃修改",
                (dialog, which) -> {
                  if (handled[0] || isFinishing()) return;
                  handled[0] = true;
                  finish();
                })
            .create();
    exitDialog.setOnDismissListener(
        dialog -> {
          handled[0] = true;
          exitDialog = null;
        });
    exitDialog.show();
  }

  private void showTimePicker(Bundle state) {
    if (timeDialog != null && timeDialog.isShowing()) return;
    timeDialog =
        new TimePickerDialog(
            this,
            (picker, h, m) -> {
              hour = h;
              minute = m;
              updateTime();
            },
            hour,
            minute,
            true);
    if (state != null) timeDialog.onRestoreInstanceState(state);
    timeDialog.setOnDismissListener(dialog -> timeDialog = null);
    timeDialog.show();
  }

  private void updateTime() {
    timeButton.setText(String.format(Locale.CHINA, "提醒时间  %02d:%02d", hour, minute));
    timeButton.setContentDescription(
        String.format(Locale.CHINA, "设置每日提醒时间，当前%02d点%02d分", hour, minute));
  }

  private void savePlan() {
    savePlan(true);
  }

  private void savePlan(boolean askNotificationPermission) {
    int goal;
    try {
      goal = Integer.parseInt(dailyGoal.getText().toString().trim());
    } catch (NumberFormatException invalid) {
      goal = -1;
    }
    if (goal < 1 || goal > 20) {
      dailyGoal.setError("请输入 1–20 之间的整数");
      dailyGoal.requestFocus();
      return;
    }
    dailyGoal.setError(null);
    TrainingPlan updated = new TrainingPlan();
    updated.id = plan == null ? 0 : plan.id;
    updated.userId = editingUserId;
    updated.dailyGoal = goal;
    updated.type = TYPES[type.getSelectedItemPosition()];
    updated.hour = hour;
    updated.minute = minute;
    updated.reminderEnabled = reminder.isChecked();
    try {
      UserProfile profile = repository.getProfile();
      if (profile == null || profile.id != editingUserId) {
        saveStatus.setText("个人档案已改变，请返回首页后重新设置计划。");
        return;
      }
      updated.id = repository.savePlan(updated);
    } catch (SQLiteException | IllegalStateException | IllegalArgumentException failed) {
      saveStatus.setText("计划保存失败，尚未保存本次修改。请检查设备存储空间后重试。");
      return;
    }
    plan = updated;
    savedDraft = draftKey();
    deleteButton.setVisibility(View.VISIBLE);
    try {
      ReminderScheduler.sync(this);
    } catch (SQLiteException | IllegalStateException | SecurityException failed) {
      saveStatus.setText("训练计划已保存，但系统暂时无法安排提醒。请稍后重新保存计划。");
      updatePermissionStatus();
      return;
    }
    saveStatus.setText(
        "训练计划已保存。"
            + (!updated.reminderEnabled
                ? "每日提醒已关闭。"
                : ReminderScheduler.canNotify(this) ? "每天将按计划安排提醒。" : "当前通知未获允许，暂时无法显示提醒。"));
    updatePermissionStatus();
    if (updated.reminderEnabled
        && askNotificationPermission
        && !permissionRequestedThisSession
        && Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestNotificationPermission();
    }
  }

  private void confirmDelete() {
    if (plan == null
        || deleting
        || isFinishing()
        || (deleteDialog != null && deleteDialog.isShowing())) return;
    deleteDialog =
        new AlertDialog.Builder(this)
            .setTitle("删除训练计划？")
            .setMessage("将删除每日目标并取消训练提醒。已保存的训练记录会继续保留。")
            .setNegativeButton("保留计划", null)
            .setPositiveButton(
                "删除",
                (dialog, which) -> {
                  if (plan == null || deleting || isFinishing()) return;
                  deleting = true;
                  try {
                    UserProfile current = repository.getProfile();
                    if (current == null || current.id != editingUserId) {
                      saveStatus.setText("个人档案已改变，请返回首页后重新操作。");
                      return;
                    }
                    repository.deletePlan();
                  } catch (SQLiteException | IllegalStateException failed) {
                    saveStatus.setText("计划删除失败，原计划仍保留。请稍后重试。");
                    return;
                  } finally {
                    deleting = false;
                  }
                  plan = null;
                  ReminderScheduler.cancel(this);
                  finish();
                })
            .create();
    deleteDialog.setOnDismissListener(dialog -> deleteDialog = null);
    deleteDialog.show();
  }

  private void requestNotificationPermission() {
    if (permissionRequestInFlight) return;
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      permissionRequestInFlight = true;
      permissionRequestedThisSession = true;
      updatePermissionStatus();
      requestPermissions(
          new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    } else {
      openNotificationSettings();
    }
  }

  private void updatePermissionStatus() {
    if (notificationStatus == null) return;
    boolean enabled = reminder.isChecked();
    boolean allowed = ReminderScheduler.canNotify(this);
    timeButton.setEnabled(enabled);
    notificationStatus.setText(
        !enabled
            ? "提醒已关闭，保存后生效。"
            : allowed
                ? "通知权限已允许。保存后将按每日计划提醒。"
                : "当前无法显示提醒通知。计划仍可保存并记录每日目标；请允许通知，并在系统设置中开启本应用及“每日康复训练提醒”渠道。");
    permissionButton.setVisibility(
        enabled
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ? View.VISIBLE
            : View.GONE);
    permissionButton.setEnabled(!permissionRequestInFlight);
    settingsButton.setVisibility(enabled && !allowed ? View.VISIBLE : View.GONE);
  }

  private void openNotificationSettings() {
    if (returningFromSettings || permissionRequestInFlight) return;
    Intent settings =
        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
    try {
      returningFromSettings = true;
      startActivity(settings);
    } catch (ActivityNotFoundException unavailable) {
      try {
        startActivity(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
      } catch (ActivityNotFoundException alsoUnavailable) {
        returningFromSettings = false;
        saveStatus.setText("无法自动打开系统设置，请在设备设置中找到本应用并开启通知。");
      }
    }
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != REQUEST_NOTIFICATIONS
        || !permissionRequestInFlight
        || permissions.length != 1
        || !Manifest.permission.POST_NOTIFICATIONS.equals(permissions[0])) return;
    permissionRequestInFlight = false;
    boolean granted =
        grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    if (saveStatus != null) {
      boolean unsaved = hasUnsavedChanges() || plan == null;
      saveStatus.setText(
          granted
              ? (!ReminderScheduler.canNotify(this)
                      ? "通知权限已允许，但系统通知或提醒渠道仍关闭，请到系统设置开启。"
                      : "通知权限已允许。")
                  + (unsaved ? "当前修改尚未保存，请保存训练计划。" : "训练计划已保存。")
              : (unsaved ? "通知权限未允许，无法显示提醒。当前修改尚未保存，请保存训练计划。" : "计划已保存，但通知权限未允许，无法显示提醒。")
                  + "您仍可正常训练；可再次授权或到系统设置开启通知。");
    }
    updatePermissionStatus();
  }

  @Override
  protected void onResume() {
    super.onResume();
    updatePermissionStatus();
    if (returningFromSettings && saveStatus != null) {
      returningFromSettings = false;
      saveStatus.setText(
          (ReminderScheduler.canNotify(this) ? "系统通知已允许。" : "系统通知仍未开启，暂时无法显示提醒。")
              + (hasUnsavedChanges() || plan == null ? "当前修改尚未保存，请保存训练计划。" : "训练计划已保存。"));
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putInt("hour", hour);
    outState.putInt("minute", minute);
    outState.putLong("editing_user", editingUserId);
    outState.putBoolean("permission_in_flight", permissionRequestInFlight);
    outState.putBoolean("permission_asked", permissionRequestedThisSession);
    outState.putBoolean("returning_settings", returningFromSettings);
    outState.putBoolean("exit_dialog", exitDialog != null && exitDialog.isShowing());
    outState.putBoolean("delete_dialog", deleteDialog != null && deleteDialog.isShowing());
    if (timeDialog != null && timeDialog.isShowing())
      outState.putBundle("time_dialog", timeDialog.onSaveInstanceState());
    if (dailyGoal != null) {
      outState.putString("goal", dailyGoal.getText().toString());
      outState.putInt("type", type.getSelectedItemPosition());
      outState.putBoolean("enabled", reminder.isChecked());
      outState.putString("saved_draft", savedDraft);
      outState.putCharSequence("save_status", saveStatus.getText());
      outState.putCharSequence("goal_error", dailyGoal.getError());
    }
  }

  @Override
  protected void onDestroy() {
    if (exitDialog != null) exitDialog.dismiss();
    if (deleteDialog != null) deleteDialog.dismiss();
    if (timeDialog != null) timeDialog.dismiss();
    super.onDestroy();
  }
}
