package com.fingerrehab.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

/** 清晰说明本地数据、工程指标与通知状态。 */
public class SettingsActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout content = Ui.screen(this, "设置与关于");
    String version = "";
    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
    }
    Ui.title(content, "安心练习，清晰记录", "指尖康复 " + version);
    LinearLayout privacy = Ui.card(this);
    Ui.add(privacy, Ui.bold(this, "隐私与本地存储", 18, Ui.INK));
    Ui.gap(privacy, 10);
    Ui.add(
        privacy,
        Ui.text(
            this,
            "无需注册、不请求联网权限。档案、训练触摸坐标、成绩与计划保存在本机 SQLite 数据库中。系统备份已关闭；卸载应用会移除这些数据。可在历史记录中逐条删除训练。",
            14,
            Ui.MUTED));
    Ui.add(content, privacy);
    LinearLayout score = Ui.card(this);
    Ui.add(score, Ui.bold(this, "如何理解训练评分", 18, Ui.INK));
    Ui.gap(score, 10);
    Ui.add(
        score,
        Ui.text(
            this,
            "评分由轨迹偏差、覆盖度、稳定性或点击准确率与耗时等工程规则计算。只适合对同类型、同子类型和同难度的个人练习作参考；不能用作临床量表或疾病诊断。",
            14,
            Ui.MUTED));
    Ui.add(content, score);
    LinearLayout reminder = Ui.card(this);
    Ui.add(reminder, Ui.bold(this, "训练提醒", 18, Ui.INK));
    Ui.gap(reminder, 10);
    Ui.add(
        reminder,
        Ui.text(
            this,
            "每日提醒采用系统非精确调度，节电模式或厂商后台限制可能导致延迟。Android 13 及以上需要通知权限；强行停止应用后请重新打开应用以恢复提醒。",
            14,
            Ui.MUTED));
    Ui.gap(reminder, 14);
    Button notification = Ui.secondary(this, "打开系统通知设置");
    Ui.add(reminder, notification);
    notification.setOnClickListener(
        v -> {
          Intent i =
              new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                  .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
          try {
            startActivity(i);
          } catch (RuntimeException ex) {
            Toast.makeText(this, "请在系统应用设置中管理通知", Toast.LENGTH_SHORT).show();
          }
        });
    Ui.add(content, reminder);
    LinearLayout safe = Ui.card(this);
    Ui.add(safe, Ui.bold(this, "练习提示", 18, Ui.INK));
    Ui.gap(safe, 10);
    Ui.add(
        safe, Ui.text(this, "保持舒适坐姿，避免用力挤压屏幕。出现疼痛、麻木或明显疲劳时请停止训练。请遵从专业人员建议安排训练强度。", 14, Ui.MUTED));
    Ui.add(content, safe);
  }
}
