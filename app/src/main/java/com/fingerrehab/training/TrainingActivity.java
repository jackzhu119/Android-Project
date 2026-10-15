package com.fingerrehab.training;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.fingerrehab.R;
import com.fingerrehab.model.TrainingRecord;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.ui.ProfileActivity;
import com.fingerrehab.ui.ResultActivity;
import com.fingerrehab.ui.Ui;
import com.fingerrehab.utils.Labels;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Locale;

/** 六种训练共享的生命周期控制：暂停、恢复、确认退出与幂等保存。 */
public final class TrainingActivity extends Activity implements TrainingCanvasView.Listener {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private static final String ACTION_REPORT = "report",
      ACTION_RESTART = "restart",
      ACTION_EXIT = "exit";
  private static final long DISPLAY_INTERVAL_MS = 50;
  private static final String RECOVERY_PREFS = "training_recovery";
  private TrainingSession session;
  private TrainingCanvasView canvas;
  private TextView timer, progressLabel, feedback, liveMetrics;
  private ProgressBar progress;
  private Button startButton;
  private Button restartButton, endButton;
  private long lastDisplayRefreshMs = Long.MIN_VALUE;
  private boolean leaving, promptOnResume, initialized;
  private AlertDialog dialog;
  private View viewportView;
  private ViewTreeObserver.OnGlobalLayoutListener viewportListener;
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (session == null || !session.running || leaving || isFinishing()) return;
          if (session.tick(SystemClock.uptimeMillis())) {
            completeAndShow();
            return;
          }
          refresh();
          handler.postDelayed(this, 50);
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    UserProfile profile = RehabRepository.get(this).getProfile();
    if (profile == null) {
      Toast.makeText(this, "请先创建个人档案，再开始训练", Toast.LENGTH_LONG).show();
      leaving = true;
      startActivity(new Intent(this, ProfileActivity.class));
      finish();
      return;
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    String recoveryId =
        state == null
            ? getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
                .getString("pending_" + profile.id, null)
            : state.getString("session_file");
    if (recoveryId != null) session = readSnapshot(recoveryId);
    if (session != null && session.ownerUserId == 0) session.ownerUserId = profile.id;
    // 兼容旧版本留下的空快照：浏览过说明不等于开始过一次训练。
    if (session != null && !session.started) {
      clearSnapshot(session);
      session = null;
    }
    boolean coldRecovery = state == null && session != null;
    if (state == null && recoveryId != null && session == null)
      getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
          .edit()
          .remove("pending_" + profile.id)
          .commit();
    if (session == null) session = createSession();
    if (session.ownerUserId == 0) session.ownerUserId = profile.id;
    if (session.saved) {
      completePendingAction();
      return;
    }
    setupViews();
    if (session.finished && session.pendingAction == null) session.pendingAction = ACTION_REPORT;
    if (coldRecovery) {
      showRecoveryChoice();
      return;
    }
    if (session.finished) {
      savePending();
      return;
    }
    if (state != null && session.started) {
      promptOnResume = true;
      session.feedback = "已恢复上次训练，当前处于暂停状态";
      refresh();
    }
  }

  private void showRecoveryChoice() {
    TrainingSession recovered = session;
    boolean[] choiceMade = {false};
    AlertDialog recovery =
        new AlertDialog.Builder(this)
            .setTitle(R.string.training_recover_title)
            .setMessage(
                getString(
                    session.finished
                        ? R.string.training_recover_finished_message
                        : R.string.training_recover_message,
                    Labels.subtype(session.subtype),
                    Labels.subtype(getIntent().getStringExtra("subtype"))))
            .setPositiveButton(
                session.finished
                    ? R.string.training_recover_save_result
                    : R.string.training_recover_previous,
                (d, w) -> {
                  if (choiceMade[0] || session != recovered || leaving) return;
                  choiceMade[0] = true;
                  getIntent()
                      .putExtra("type", session.type)
                      .putExtra("subtype", session.subtype)
                      .putExtra("difficulty", session.difficulty);
                  if (session.finished) savePending();
                  else if (session.started) resume();
                  else refresh();
                })
            .setNegativeButton(
                session.finished
                    ? R.string.training_recover_save_and_start
                    : R.string.training_recover_start_requested,
                (d, w) -> {
                  if (choiceMade[0] || session != recovered || leaving) return;
                  choiceMade[0] = true;
                  if (!session.finished) session.pendingStatus = "aborted";
                  session.pendingAction = ACTION_RESTART;
                  session.finish(SystemClock.uptimeMillis());
                  savePending();
                })
            .setNeutralButton(
                R.string.training_keep_and_return,
                (d, w) -> {
                  if (choiceMade[0] || session != recovered || leaving) return;
                  choiceMade[0] = true;
                  returnWithSnapshot();
                })
            .setOnCancelListener(d -> returnWithSnapshot())
            .create();
    displayDialog(recovery);
  }

  private void setupViews() {
    initialized = false;
    clearViewportListener();
    setContentView(R.layout.activity_training);
    Ui.insets(findViewById(R.id.training_root));
    canvas = findViewById(R.id.training_canvas);
    timer = findViewById(R.id.training_timer);
    progress = findViewById(R.id.training_progress);
    progressLabel = findViewById(R.id.training_progress_label);
    feedback = findViewById(R.id.training_feedback);
    liveMetrics = findViewById(R.id.training_live_metrics);
    findViewById(R.id.training_back).setOnClickListener(v -> onBackPressed());
    ((TextView) findViewById(R.id.training_heading)).setText(Labels.subtype(session.subtype));
    ((TextView) findViewById(R.id.training_difficulty))
        .setText(Labels.difficulty(session.difficulty));
    TextView instructionText = findViewById(R.id.training_instructions);
    instructionText.setText(instructions());
    instructionText.setContentDescription(
        getString(R.string.training_instructions_accessibility, instructions()));
    instructionText.setOnClickListener(
        v -> {
          if (dialog != null) return;
          boolean wasRunning = session.running;
          pause();
          dialog =
              new AlertDialog.Builder(this)
                  .setTitle("操作说明 · 计时已暂停")
                  .setMessage(instructions())
                  .setPositiveButton(
                      wasRunning ? "继续训练" : "知道了",
                      (d, w) -> {
                        if (wasRunning) resume();
                      })
                  .create();
          displayDialog(dialog);
        });
    LinearLayout actions = findViewById(R.id.training_actions);
    startButton = Ui.button(this, "开始");
    restartButton = Ui.secondary(this, "重开");
    endButton = Ui.secondary(this, "结束");
    Button[] buttons = {startButton, restartButton, endButton};
    for (int i = 0; i < buttons.length; i++) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) params.setMarginStart(Ui.dp(this, 8));
      buttons[i].setMinWidth(0);
      buttons[i].setTextSize(14);
      actions.addView(buttons[i], params);
    }
    startButton.setOnClickListener(
        v -> {
          if (session.finished) {
            savePending();
            return;
          }
          if (session.running) {
            pause();
            session.feedback = "已暂停，休息后可继续";
            refresh();
          } else resume();
        });
    restartButton.setOnClickListener(v -> confirmRestart());
    endButton.setOnClickListener(v -> confirmEnd());
    canvas.bind(session, this);
    initialized = true;
    fitCanvasToViewport();
    refresh();
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (!initialized || leaving || session.finished) return;
    // 使用同一会话和时钟，只重建适合方向的布局；旋转不能额外生成目标或扣时。
    session.beginStroke();
    setupViews();
  }

  /** 小屏保留完整可触摸区域，说明与指标先压缩；横屏使用独立双栏 XML。 */
  private void fitCanvasToViewport() {
    View viewport = findViewById(R.id.training_scroll);
    if (viewport == null) return;
    TrainingCanvasView ownedCanvas = canvas;
    viewportView = viewport;
    viewportListener =
        () -> {
          if (canvas != ownedCanvas || viewport.getHeight() <= 0) return;
          TextView instruction = findViewById(R.id.training_instructions);
          boolean compact = viewport.getHeight() < Ui.dp(this, 340);
          int maximumLines = compact ? 2 : Integer.MAX_VALUE;
          if (instruction.getMaxLines() != maximumLines) instruction.setMaxLines(maximumLines);
          int visibility = compact ? View.GONE : View.VISIBLE;
          if (liveMetrics.getVisibility() != visibility) liveMetrics.setVisibility(visibility);
          int reserved =
              instruction.getMeasuredHeight()
                  + (compact ? 0 : liveMetrics.getMeasuredHeight())
                  + Ui.dp(this, 20);
          int available = Math.max(Ui.dp(this, 80), viewport.getHeight() - reserved);
          int desired = Math.min(Math.max(Ui.dp(this, 80), canvas.getWidth()), available);
          ViewGroup.LayoutParams params = canvas.getLayoutParams();
          if (params.height != desired) {
            params.height = desired;
            canvas.setLayoutParams(params);
          }
        };
    viewport.getViewTreeObserver().addOnGlobalLayoutListener(viewportListener);
  }

  private void clearViewportListener() {
    if (viewportView != null
        && viewportListener != null
        && viewportView.getViewTreeObserver().isAlive())
      viewportView.getViewTreeObserver().removeOnGlobalLayoutListener(viewportListener);
    viewportView = null;
    viewportListener = null;
  }

  private TrainingSession createSession() {
    TrainingSession created =
        new TrainingSession(
            getIntent().getStringExtra("type"),
            getIntent().getStringExtra("subtype"),
            getIntent().getIntExtra("difficulty", 0),
            System.currentTimeMillis());
    UserProfile profile = RehabRepository.get(this).getProfile();
    created.ownerUserId = profile == null ? 0 : profile.id;
    return created;
  }

  private String instructions() {
    if (session.isTrace()) {
      String direction =
          "circle".equals(session.subtype)
              ? "从顶部绿点开始，顺时针绕行一周。"
              : "spiral".equals(session.subtype) ? "从中心绿点开始，沿箭头由内向外描绘。" : "从左下绿色起点，沿箭头描绘到右上终点。";
      return direction + "单指沿蓝线连续描绘；青色线是你的轨迹。可抬手后在原处续画，避免跳段。";
    }
    if ("random".equals(session.subtype))
      return "目标出现后点击青色圆，共 "
          + session.expectedTargets()
          + " 轮。点错只记错误，不跳过当前目标；每轮 "
          + String.format(Locale.CHINA, "%.1f", session.config().reactionTimeoutMs / 1000d)
          + " 秒内未命中记为超时。请每次抬起手指后再点击。";
    if ("sequence".equals(session.subtype))
      return "按 1 → " + session.expectedTargets() + " 顺序点击圆形数字。点错时当前正确目标不变；青色表示下一个目标，灰色表示已完成。";
    return "在青色圆内完成 "
        + session.expectedTargets()
        + " 次点击。每次抬起手指后再点，圆外点击记为错误。保持自然、稳定的节奏；50 毫秒内连续事件不计有效点击。";
  }

  private void resume() {
    if (session == null || session.finished || leaving) return;
    promptOnResume = false;
    session.resume(SystemClock.uptimeMillis());
    handler.removeCallbacks(ticker);
    handler.post(ticker);
    refresh();
  }

  private void pause() {
    handler.removeCallbacks(ticker);
    if (session != null) session.pause(SystemClock.uptimeMillis());
    if (canvas != null) canvas.resetInput();
    if (initialized) refresh();
  }

  private void refresh() {
    if (!initialized || session == null) return;
    lastDisplayRefreshMs = SystemClock.uptimeMillis();
    long remaining = session.remaining(SystemClock.uptimeMillis());
    long seconds = (remaining + 999) / 1000;
    setText(timer, getString(R.string.training_timer_format, seconds / 60, seconds % 60));
    String timerDescription =
        getString(
            "random".equals(session.subtype)
                ? R.string.training_round_remaining
                : R.string.training_session_remaining,
            seconds);
    if (!timerDescription.contentEquals(
        timer.getContentDescription() == null ? "" : timer.getContentDescription()))
      timer.setContentDescription(timerDescription);
    progress.setProgress((int) (session.progress() * 1000));
    if (session.isTrace())
      setText(
          progressLabel, getString(R.string.training_coverage_format, session.progress() * 100));
    else if ("random".equals(session.subtype))
      setText(
          progressLabel,
          getString(
              R.string.training_round_format,
              Math.min(session.expectedTargets(), session.round + 1),
              session.expectedTargets()));
    else
      setText(
          progressLabel,
          getString(
              R.string.training_completed_format, session.successes, session.expectedTargets()));
    setText(feedback, session.feedback);
    feedback.setTextColor(session.feedbackGood ? Ui.TEAL : 0xFFC35B4D);
    if (session.isTrace())
      setText(
          liveMetrics,
          session.liveDeviation() == null
              ? getString(R.string.training_trace_legend)
              : getString(
                  R.string.training_deviation_samples_format,
                  session.liveDeviation() * 100,
                  session.samples.size()));
    else
      setText(
          liveMetrics,
          "random".equals(session.subtype)
              ? getString(
                  R.string.training_reaction_summary_format,
                  session.successes,
                  session.errors,
                  session.timeouts)
              : getString(
                  R.string.training_click_summary_format, session.successes, session.errors));
    setText(
        startButton,
        session.finished ? "重试保存" : session.running ? "暂停" : session.started ? "继续" : "开始");
    setText(endButton, session.started ? "结束" : "返回");
    restartButton.setEnabled(!session.finished);
    endButton.setEnabled(!session.finished);
    canvas.invalidate();
  }

  @Override
  public void onTrainingChanged(boolean finished) {
    if (finished) {
      if (session != null && session.finished) completeAndShow();
    } else if (SystemClock.uptimeMillis() - lastDisplayRefreshMs >= DISPLAY_INTERVAL_MS) refresh();
  }

  private void completeAndShow() {
    if (leaving || session == null || session.saved) return;
    finalizeSession(null, ACTION_REPORT);
  }

  private void finalizeSession(String status, String action) {
    if (leaving
        || session == null
        || session.saved
        || !session.started
        || session.pendingAction != null) return;
    session.finish(SystemClock.uptimeMillis());
    handler.removeCallbacks(ticker);
    if (canvas != null) canvas.resetInput();
    if (session.pendingAction == null) {
      session.pendingStatus = status;
      session.pendingAction = action;
    }
    savePending();
  }

  private void savePending() {
    if (leaving
        || session == null
        || !session.started
        || !session.finished
        || session.pendingAction == null) return;
    if (session.saved) {
      completePendingAction();
      return;
    }
    try {
      UserProfile profile = RehabRepository.get(this).getProfile();
      if (profile == null)
        throw new IllegalStateException(getString(R.string.training_save_missing_profile));
      if (profile.id != session.ownerUserId)
        throw new IllegalStateException(getString(R.string.training_save_wrong_profile));
      TrainingRecord record =
          session.buildRecord(session.pendingStatus, System.currentTimeMillis());
      record.userId = session.ownerUserId;
      session.recordId = RehabRepository.get(this).saveRecord(record);
      if (session.recordId <= 0) throw new IllegalStateException("未取得保存编号");
      session.saved = true;
      clearSnapshot(session);
    } catch (RuntimeException error) {
      session.feedback = getString(R.string.training_record_pending);
      if (initialized) refresh();
      AlertDialog failure =
          new AlertDialog.Builder(this)
              .setTitle(R.string.training_save_failed_title)
              .setMessage(
                  error instanceof IllegalStateException
                      ? error.getMessage()
                      : getString(R.string.training_save_failed_message))
              .setPositiveButton(R.string.training_retry_save, (d, w) -> savePending())
              .setNegativeButton(
                  R.string.training_return_to_session,
                  (d, w) -> {
                    if (initialized) refresh();
                  })
              .setNeutralButton(R.string.training_keep_and_return, (d, w) -> returnWithSnapshot())
              .setOnCancelListener(d -> returnWithSnapshot())
              .create();
      displayDialog(failure);
      writeSnapshot();
      return;
    }
    completePendingAction();
  }

  private void completePendingAction() {
    if (ACTION_RESTART.equals(session.pendingAction)) {
      session = createSession();
      setupViews();
    } else if (ACTION_EXIT.equals(session.pendingAction)) {
      leaving = true;
      handler.removeCallbacks(ticker);
      finish();
    } else openResult();
  }

  private void returnWithSnapshot() {
    pause();
    if (!writeSnapshot()) {
      Toast.makeText(this, R.string.training_snapshot_failed, Toast.LENGTH_LONG).show();
      return;
    }
    leaving = true;
    finish();
  }

  /** 新弹框可能在旧弹框按钮回调中出现，旧 dismiss 不得清掉新弹框引用。 */
  private void displayDialog(AlertDialog value) {
    dialog = value;
    value.setOnDismissListener(
        d -> {
          if (dialog == value) dialog = null;
        });
    value.show();
  }

  private void setText(TextView view, String value) {
    if (!value.contentEquals(view.getText())) view.setText(value);
  }

  private void openResult() {
    leaving = true;
    handler.removeCallbacks(ticker);
    startActivity(new Intent(this, ResultActivity.class).putExtra("record_id", session.recordId));
    finish();
  }

  private void confirmRestart() {
    if (leaving || dialog != null) return;
    if (!session.started) {
      clearSnapshot(session);
      session = createSession();
      setupViews();
      return;
    }
    TrainingSession confirmed = session;
    boolean wasRunning = session.running;
    pause();
    dialog =
        new AlertDialog.Builder(this)
            .setTitle("重新开始？")
            .setMessage("当前尝试将保存为中断记录，随后开始一段新的训练。")
            .setPositiveButton(
                "保存并重开",
                (d, w) -> {
                  if (session == confirmed) finalizeSession("aborted", ACTION_RESTART);
                })
            .setNegativeButton(
                "继续当前训练",
                (d, w) -> {
                  if (wasRunning && session == confirmed) resume();
                })
            .setOnCancelListener(
                d -> {
                  if (wasRunning && session == confirmed) resume();
                })
            .create();
    displayDialog(dialog);
  }

  private void confirmEnd() {
    if (leaving || dialog != null) return;
    if (!session.started) {
      leaveUnusedSession();
      return;
    }
    TrainingSession confirmed = session;
    boolean wasRunning = session.running;
    pause();
    dialog =
        new AlertDialog.Builder(this)
            .setTitle("结束并查看报告？")
            .setMessage("将根据当前实际完成情况保存。未完成的训练不会计入有效成绩与每日目标。")
            .setPositiveButton(
                "结束并保存",
                (d, w) -> {
                  if (session == confirmed) completeAndShow();
                })
            .setNegativeButton(
                "继续训练",
                (d, w) -> {
                  if (wasRunning && session == confirmed) resume();
                })
            .setOnCancelListener(
                d -> {
                  if (wasRunning && session == confirmed) resume();
                })
            .create();
    displayDialog(dialog);
  }

  @Override
  public void onBackPressed() {
    if (!initialized || leaving) {
      super.onBackPressed();
      return;
    }
    if (dialog != null) return;
    if (!session.started) {
      leaveUnusedSession();
      return;
    }
    if (session.finished) {
      savePending();
      return;
    }
    boolean wasRunning = session.running;
    pause();
    dialog =
        new AlertDialog.Builder(this)
            .setTitle("退出本次训练？")
            .setMessage("当前进度将保存为中断记录，计时也会停止。")
            .setPositiveButton(
                "保存并退出",
                (d, w) -> {
                  finalizeSession("aborted", ACTION_EXIT);
                })
            .setNegativeButton(
                "继续训练",
                (d, w) -> {
                  if (wasRunning) resume();
                })
            .setOnCancelListener(
                d -> {
                  if (wasRunning) resume();
                })
            .create();
    displayDialog(dialog);
  }

  @Override
  protected void onPause() {
    if (initialized && !leaving && !session.saved) {
      promptOnResume = session.started && (promptOnResume || session.running);
      pause();
      if (session.started) writeSnapshot();
      else clearSnapshot(session);
    }
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (initialized
        && session.started
        && promptOnResume
        && !leaving
        && !session.finished
        && dialog == null) {
      promptOnResume = false;
      dialog =
          new AlertDialog.Builder(this)
              .setTitle("训练已暂停")
              .setMessage("离开应用的时间没有计入本次训练。准备好后继续，也可以结束保存当前进度。")
              .setPositiveButton("继续训练", (d, w) -> resume())
              .setNegativeButton("结束并保存", (d, w) -> completeAndShow())
              .create();
      displayDialog(dialog);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (session != null && session.started && !session.saved && !leaving) {
      promptOnResume = promptOnResume || session.running;
      pause();
      if (writeSnapshot()) out.putString("session_file", session.sessionId);
    }
    super.onSaveInstanceState(out);
  }

  private File snapshotFile(String id) {
    return new File(getFilesDir(), "training-" + id + ".state");
  }

  private void leaveUnusedSession() {
    handler.removeCallbacks(ticker);
    clearSnapshot(session);
    leaving = true;
    finish();
  }

  private void clearSnapshot(TrainingSession value) {
    snapshotFile(value.sessionId).delete();
    new File(snapshotFile(value.sessionId).getPath() + ".tmp").delete();
    if (value.sessionId.equals(
        getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            .getString("pending_" + value.ownerUserId, null)))
      getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
          .edit()
          .remove("pending_" + value.ownerUserId)
          .commit();
  }

  private boolean writeSnapshot() {
    if (session == null || session.saved || !session.started) return false;
    File destination = snapshotFile(session.sessionId);
    File temporary = new File(destination.getPath() + ".tmp");
    try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(temporary))) {
      stream.writeObject(session);
      stream.flush();
    } catch (Exception failure) {
      temporary.delete();
      return false;
    }
    if (!temporary.renameTo(destination)) {
      temporary.delete();
      return false;
    }
    getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
        .edit()
        .putString("pending_" + session.ownerUserId, session.sessionId)
        .commit();
    return true;
  }

  private TrainingSession readSnapshot(String id) {
    if (id == null || !id.matches("[a-fA-F0-9-]{36}")) return null;
    try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(snapshotFile(id)))) {
      TrainingSession restored = (TrainingSession) stream.readObject();
      restored.pause(SystemClock.uptimeMillis());
      return restored;
    } catch (Exception failure) {
      Toast.makeText(this, "上次训练状态无法读取，请重新开始", Toast.LENGTH_LONG).show();
      return null;
    }
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(ticker);
    clearViewportListener();
    if (dialog != null) {
      dialog.setOnDismissListener(null);
      dialog.dismiss();
      dialog = null;
    }
    super.onDestroy();
  }

  /** 供交互验证读取状态；界面仍通过真实 MotionEvent 驱动。 */
  public TrainingSession getSession() {
    return session;
  }
}
