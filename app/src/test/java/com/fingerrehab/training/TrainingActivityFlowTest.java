package com.fingerrehab.training;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.RectF;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.database.RehabDatabase;
import com.fingerrehab.model.*;
import com.fingerrehab.repository.RehabRepository;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;

/** 从真实触摸事件到持久化结果，验证训练页关键生命周期与防重复行为。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TrainingActivityFlowTest {
  private Context context;
  private RehabRepository repo;
  private ActivityController<TrainingActivity> controller;

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    RehabRepository.get(context).close();
    context.deleteDatabase(RehabDatabase.NAME);
    context.getSharedPreferences("rehab_profile", 0).edit().clear().commit();
    context.getSharedPreferences("training_recovery", 0).edit().clear().commit();
    java.io.File[] snapshots =
        context.getFilesDir().listFiles((dir, name) -> name.startsWith("training-"));
    if (snapshots != null) for (java.io.File snapshot : snapshots) snapshot.delete();
    repo = RehabRepository.get(context);
    repo.saveProfile(new UserProfile());
  }

  @After
  public void close() {
    if (controller != null) {
      controller.pause().stop().destroy();
    }
    repo.close();
  }

  private TrainingActivity launch(String type, String sub) {
    Intent intent =
        new Intent(context, TrainingActivity.class)
            .putExtra("type", type)
            .putExtra("subtype", sub)
            .putExtra("difficulty", 0);
    controller = Robolectric.buildActivity(TrainingActivity.class, intent).setup();
    TrainingActivity a = controller.get();
    layout(a, 360, 760);
    return a;
  }

  private void layout(TrainingActivity a, int width, int height) {
    View root = a.findViewById(android.R.id.content);
    float d = a.getResources().getDisplayMetrics().density;
    int w = (int) (width * d), h = (int) (height * d);
    root.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, w, h);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private void begin(TrainingActivity a) {
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(0).performClick();
  }

  private void choose(int button) {
    AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(d);
    d.getButton(button).performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private void advance(long ms) {
    ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
  }

  private void touch(TrainingCanvasView canvas, int action, float x, float y) {
    long now = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
    canvas.onTouchEvent(event);
    event.recycle();
  }

  @Test
  @Config(sdk = 26)
  public void minimumAndroidVersionLaunchesTrainingAndCanSave() {
    TrainingActivity a = launch("coordination", "tapping");
    begin(a);
    advance(100);
    a.onBackPressed();
    choose(AlertDialog.BUTTON_POSITIVE);
    assertEquals(1, repo.getRecords(null).size());
  }

  @Test
  @Config(qualifiers = "w320dp-h568dp-port")
  public void compactPortraitKeepsWholeCanvasAndActionsVisible() {
    TrainingActivity a = launch("coordination", "sequence");
    for (int i = 0; i < 3; i++) {
      layout(a, 320, 568);
      a.findViewById(R.id.training_root).getViewTreeObserver().dispatchOnGlobalLayout();
    }
    layout(a, 320, 568);
    TrainingCanvasView c = a.findViewById(R.id.training_canvas);
    ViewGroup viewport = a.findViewById(R.id.training_scroll);
    android.graphics.Rect bounds = new android.graphics.Rect(0, 0, c.getWidth(), c.getHeight());
    viewport.offsetDescendantRectToMyCoords(c, bounds);
    assertTrue("画布应完整位于训练视口", bounds.top >= 0 && bounds.bottom <= viewport.getHeight());
    assertTrue(c.getHeight() > 0);
    assertTrue(
        a.findViewById(R.id.training_actions).getHeight()
            >= 48 * a.getResources().getDisplayMetrics().density);
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land")
  public void landscapeKeepsCanvasBesideControls() {
    TrainingActivity a = launch("trace", "spiral");
    layout(a, 640, 360);
    assertNull(a.findViewById(R.id.training_scroll));
    TrainingCanvasView c = a.findViewById(R.id.training_canvas);
    assertTrue(c.getWidth() > 0);
    assertTrue(c.getHeight() > 0);
    View actions = a.findViewById(R.id.training_actions);
    assertTrue(actions.getWidth() > 0);
    assertTrue(actions.getHeight() > 0);
  }

  @Test
  public void reactionCompletesThroughActualTouchesAndSavesExactlyOnce() {
    TrainingActivity a = launch("reaction", "random");
    begin(a);
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF plot = canvas.getPlotBounds();
    int total = a.getSession().expectedTargets();
    for (int i = 0; i < total; i++) {
      advance(350);
      float x = plot.left + (float) a.getSession().targetX * plot.width(),
          y = plot.top + (float) a.getSession().targetY * plot.height();
      touch(canvas, MotionEvent.ACTION_DOWN, x, y);
      advance(10);
      touch(canvas, MotionEvent.ACTION_UP, x, y);
    }
    assertTrue(a.isFinishing());
    assertEquals(1, repo.getRecords(null).size());
    TrainingRecord r = repo.getRecords(null).get(0);
    assertEquals("completed", r.status);
    assertTrue(r.valid);
    assertEquals(total, r.successes);
    assertNotNull(r.averageReactionMs);
    a.onTrainingChanged(true);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
    assertEquals(1, repo.getRecords(null).size());
  }

  @Test
  public void returningRequiresConfirmationAndAbortedRecordNeverCompletesGoal() {
    TrainingActivity a = launch("coordination", "tapping");
    begin(a);
    advance(400);
    a.onBackPressed();
    assertFalse(a.getSession().running);
    assertEquals(0, repo.getRecords(null).size());
    choose(AlertDialog.BUTTON_NEGATIVE);
    assertTrue(a.getSession().running);
    a.onBackPressed();
    choose(AlertDialog.BUTTON_POSITIVE);
    assertTrue(a.isFinishing());
    assertEquals("aborted", repo.getRecords(null).get(0).status);
    assertFalse(repo.getRecords(null).get(0).valid);
    assertEquals(0, repo.completedToday(null));
  }

  @Test
  public void backgroundPausesAndResumeRequiresUserAction() {
    TrainingActivity a = launch("reaction", "random");
    begin(a);
    advance(500);
    controller.pause();
    long elapsed = a.getSession().elapsed(SystemClock.uptimeMillis());
    advance(15000);
    assertEquals(elapsed, a.getSession().elapsed(SystemClock.uptimeMillis()));
    controller.resume();
    assertFalse(a.getSession().running);
    assertEquals("训练已暂停", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle());
    choose(AlertDialog.BUTTON_POSITIVE);
    assertTrue(a.getSession().running);
    assertEquals(0, a.getSession().timeouts);
  }

  @Test
  public void restartPersistsOldAttemptAndCreatesFreshSession() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    String old = a.getSession().sessionId;
    advance(300);
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    assertEquals(0, repo.getRecords(null).size());
    choose(AlertDialog.BUTTON_POSITIVE);
    assertNotEquals(old, a.getSession().sessionId);
    assertFalse(a.getSession().running);
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("aborted", repo.getRecords(null).get(0).status);
  }

  @Test
  public void stateRecreationRestoresRealSamplesAndRemainsPaused() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    touch(canvas, MotionEvent.ACTION_DOWN, p.left + p.width() * .14f, p.top + p.height() * .72f);
    advance(30);
    touch(canvas, MotionEvent.ACTION_MOVE, p.left + p.width() * .2f, p.top + p.height() * .68f);
    int samples = a.getSession().samples.size();
    String id = a.getSession().sessionId;
    Bundle state = new Bundle();
    controller.saveInstanceState(state).pause().stop().destroy();
    Intent intent = a.getIntent();
    advance(20000);
    controller =
        Robolectric.buildActivity(TrainingActivity.class, intent).create(state).start().resume();
    TrainingActivity restored = controller.get();
    assertEquals(id, restored.getSession().sessionId);
    assertEquals(samples, restored.getSession().samples.size());
    assertFalse(restored.getSession().running);
    assertEquals(0, repo.getRecords(null).size());
  }

  @Test
  public void motionHistoryIsRecordedAndCancelCreatesNewStroke() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    long down = SystemClock.uptimeMillis();
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX(), p.centerY());
    advance(10);
    MotionEvent move =
        MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_MOVE,
            p.centerX() + 5,
            p.centerY(),
            0);
    advance(10);
    move.addBatch(SystemClock.uptimeMillis(), p.centerX() + 10, p.centerY(), 1, 1, 0);
    canvas.onTouchEvent(move);
    move.recycle();
    assertEquals(3, a.getSession().samples.size());
    touch(canvas, MotionEvent.ACTION_CANCEL, p.centerX(), p.centerY());
    advance(10);
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX() + 12, p.centerY());
    assertTrue(a.getSession().samples.get(3).breakBefore);
    assertEquals(1, a.getSession().cancelledGestures);
  }

  @Test
  public void pausedGestureCannotContinueWithoutANewDown() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX(), p.centerY());
    begin(a); // 暂停。
    advance(1000);
    begin(a); // 继续。
    touch(canvas, MotionEvent.ACTION_MOVE, p.centerX() + 10, p.centerY());
    touch(canvas, MotionEvent.ACTION_UP, p.centerX() + 10, p.centerY());
    assertEquals(1, a.getSession().samples.size());
    advance(10);
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX() + 10, p.centerY());
    assertEquals(2, a.getSession().samples.size());
    assertTrue(a.getSession().samples.get(1).breakBefore);
  }

  @Test
  public void changingPlotSizeRequiresANewGesture() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX(), p.centerY());
    canvas.layout(0, 0, canvas.getWidth() + 20, canvas.getHeight() + 20);
    advance(20);
    touch(canvas, MotionEvent.ACTION_MOVE, p.centerX() + 10, p.centerY());
    assertEquals(1, a.getSession().samples.size());
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX(), p.centerY());
    assertEquals(2, a.getSession().samples.size());
    assertTrue(a.getSession().samples.get(1).breakBefore);
  }

  @Test
  public void denseMotionKeepsSamplesWhileTextUpdatesAreThrottled() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    int[] updates = {0};
    ((TextView) a.findViewById(R.id.training_live_metrics))
        .addTextChangedListener(
            new android.text.TextWatcher() {
              @Override
              public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

              @Override
              public void onTextChanged(CharSequence s, int start, int before, int count) {
                updates[0]++;
              }

              @Override
              public void afterTextChanged(android.text.Editable value) {}
            });
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX(), p.centerY());
    for (int i = 0; i < 100; i++) {
      advance(2);
      touch(canvas, MotionEvent.ACTION_MOVE, p.centerX() + i / 10f, p.centerY());
    }
    assertEquals(101, a.getSession().samples.size());
    assertTrue("文本不应按500Hz触摸频率重排", updates[0] > 0 && updates[0] <= 5);
  }

  @Test
  public void wrongProfileCannotReceiveTrainingAndRetryKeepsRestartAction() {
    long owner = repo.getProfile().id;
    TrainingActivity a = launch("trace", "line");
    begin(a);
    String original = a.getSession().sessionId;
    repo.saveProfile(new UserProfile()); // 模拟训练暂停期间换档案。
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    choose(AlertDialog.BUTTON_POSITIVE);
    assertTrue(a.getSession().finished);
    assertEquals(0, repo.getRecords(null).size());
    assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    repo.setCurrentProfile(owner);
    choose(AlertDialog.BUTTON_POSITIVE);
    assertFalse(a.isFinishing());
    assertNotEquals(original, a.getSession().sessionId);
    assertFalse(a.getSession().started);
    assertEquals(owner, a.getSession().ownerUserId);
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("aborted", repo.getRecords(null).get(0).status);
  }

  @Test
  public void failedExitSurvivesRecreationAndStillExitsWithoutOpeningReport() {
    long owner = repo.getProfile().id;
    TrainingActivity a = launch("coordination", "tapping");
    begin(a);
    repo.saveProfile(new UserProfile());
    a.onBackPressed();
    choose(AlertDialog.BUTTON_POSITIVE);
    Bundle state = new Bundle();
    controller.saveInstanceState(state).pause().stop().destroy();
    repo.setCurrentProfile(owner);
    controller =
        Robolectric.buildActivity(TrainingActivity.class, a.getIntent())
            .create(state)
            .start()
            .resume();
    TrainingActivity restored = controller.get();
    assertTrue(restored.isFinishing());
    assertNull(Shadows.shadowOf(restored).getNextStartedActivity());
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("aborted", repo.getRecords(null).get(0).status);
    assertEquals(0, repo.getRecords(null).get(0).score, 0);
  }

  @Test
  public void coldLaunchOffersRecoveryBeforeReplacingRequestedTask() {
    TrainingActivity previous = launch("trace", "circle");
    begin(previous);
    String previousId = previous.getSession().sessionId;
    advance(200);
    controller.pause().stop().destroy();
    TrainingActivity requested = launch("reaction", "random");
    assertEquals(previousId, requested.getSession().sessionId);
    assertFalse(requested.getSession().running);
    assertEquals("发现上次训练进度", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle());
    choose(AlertDialog.BUTTON_NEGATIVE);
    assertEquals("reaction", requested.getSession().type);
    assertNotEquals(previousId, requested.getSession().sessionId);
    assertEquals(
        "随机目标", ((TextView) requested.findViewById(R.id.training_heading)).getText().toString());
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("circle", repo.getRecords(null).get(0).subtype);
    assertEquals("aborted", repo.getRecords(null).get(0).status);
  }

  @Test
  public void recoveredPreviousTaskRestartsItsOwnMode() {
    TrainingActivity previous = launch("coordination", "tapping");
    begin(previous);
    String id = previous.getSession().sessionId;
    controller.pause().stop().destroy();
    TrainingActivity restored = launch("trace", "spiral");
    choose(AlertDialog.BUTTON_POSITIVE);
    assertEquals(id, restored.getSession().sessionId);
    assertTrue(restored.getSession().running);
    ((LinearLayout) restored.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    choose(AlertDialog.BUTTON_POSITIVE);
    assertEquals("tapping", restored.getSession().subtype);
    assertNotEquals(id, restored.getSession().sessionId);
  }

  @Test
  public void repeatedConfigurationChangesKeepTheSameSessionAndReplaceViewportListeners() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    TrainingSession session = a.getSession();
    TrainingCanvasView canvas = a.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    touch(canvas, MotionEvent.ACTION_DOWN, p.centerX(), p.centerY());
    for (int i = 0; i < 4; i++) {
      android.content.res.Configuration config =
          new android.content.res.Configuration(a.getResources().getConfiguration());
      boolean landscape = (i & 1) == 0;
      config.orientation =
          landscape
              ? android.content.res.Configuration.ORIENTATION_LANDSCAPE
              : android.content.res.Configuration.ORIENTATION_PORTRAIT;
      config.screenWidthDp = landscape ? 640 : 360;
      config.screenHeightDp = landscape ? 360 : 760;
      controller.configurationChange(config);
      a = controller.get();
      layout(a, config.screenWidthDp, config.screenHeightDp);
      assertSame(session, a.getSession());
      assertEquals(1, a.getSession().samples.size());
      assertTrue(a.getSession().running);
      assertTrue(a.findViewById(R.id.training_canvas).getWidth() > 0);
    }
  }

  @Test
  public void returningAfterFailedSaveKeepsProgressAvailableToItsOwner() {
    long owner = repo.getProfile().id;
    TrainingActivity a = launch("coordination", "tapping");
    begin(a);
    String original = a.getSession().sessionId;
    repo.saveProfile(new UserProfile());
    a.onBackPressed();
    choose(AlertDialog.BUTTON_POSITIVE);
    choose(AlertDialog.BUTTON_NEUTRAL);
    assertTrue(a.isFinishing());
    controller.pause().stop().destroy();
    repo.setCurrentProfile(owner);
    TrainingActivity recovered = launch("trace", "circle");
    assertEquals(original, recovered.getSession().sessionId);
    choose(AlertDialog.BUTTON_POSITIVE); // 恢复原来保存后退出的动作。
    assertTrue(recovered.isFinishing());
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("aborted", repo.getRecords(null).get(0).status);
    assertNull(
        context.getSharedPreferences("training_recovery", 0).getString("pending_" + owner, null));
  }

  @Test
  public void leavingBeforeStartDoesNotCreateARecordOrRecoveryPrompt() {
    TrainingActivity a = launch("trace", "line");
    a.onBackPressed();
    assertTrue(a.isFinishing());
    assertTrue(repo.getRecords(null).isEmpty());
    assertNull(
        context
            .getSharedPreferences("training_recovery", 0)
            .getString("pending_" + repo.getProfile().id, null));
    AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
    assertFalse(latest != null && latest.isShowing());
  }

  @Test
  public void restartingAndReturningBeforeStartOnlyResetsThePreview() {
    TrainingActivity a = launch("reaction", "random");
    String original = a.getSession().sessionId;
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    assertNotEquals(original, a.getSession().sessionId);
    assertFalse(a.getSession().started);
    assertTrue(repo.getRecords(null).isEmpty());
    assertEquals(
        "返回",
        ((Button) ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(2))
            .getText()
            .toString());
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(2).performClick();
    assertTrue(a.isFinishing());
    assertTrue(repo.getRecords(null).isEmpty());
  }

  @Test
  public void browsingThenBackgroundingDoesNotLeaveAnEmptyRecoveryTask() {
    TrainingActivity preview = launch("trace", "spiral");
    Bundle state = new Bundle();
    controller.saveInstanceState(state).pause().stop().destroy();
    assertFalse(state.containsKey("session_file"));
    assertNull(
        context
            .getSharedPreferences("training_recovery", 0)
            .getString("pending_" + repo.getProfile().id, null));
    TrainingActivity requested = launch("coordination", "sequence");
    assertEquals("sequence", requested.getSession().subtype);
    assertFalse(requested.getSession().started);
    assertTrue(repo.getRecords(null).isEmpty());
    AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
    assertFalse(latest != null && latest.isShowing());
  }

  @Test
  public void legacyUnstartedSnapshotIsRemovedInsteadOfBecomingAnAbortedRecord() throws Exception {
    TrainingSession legacy = new TrainingSession("trace", "circle", 0, System.currentTimeMillis());
    legacy.ownerUserId = repo.getProfile().id;
    java.io.File file =
        new java.io.File(context.getFilesDir(), "training-" + legacy.sessionId + ".state");
    try (java.io.ObjectOutputStream output =
        new java.io.ObjectOutputStream(new java.io.FileOutputStream(file))) {
      output.writeObject(legacy);
    }
    context
        .getSharedPreferences("training_recovery", 0)
        .edit()
        .putString("pending_" + legacy.ownerUserId, legacy.sessionId)
        .commit();
    TrainingActivity requested = launch("reaction", "random");
    assertEquals("random", requested.getSession().subtype);
    assertNotEquals(legacy.sessionId, requested.getSession().sessionId);
    assertFalse(file.exists());
    assertNull(
        context
            .getSharedPreferences("training_recovery", 0)
            .getString("pending_" + legacy.ownerUserId, null));
    assertTrue(repo.getRecords(null).isEmpty());
  }

  @Test
  public void repeatedRestartConfirmationCannotSaveTheFreshSession() {
    TrainingActivity a = launch("trace", "line");
    begin(a);
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    Button confirm =
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE);
    confirm.performClick();
    confirm.performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, repo.getRecords(null).size());
    assertEquals("aborted", repo.getRecords(null).get(0).status);
    assertFalse(a.getSession().started);
    assertFalse(a.isFinishing());
    assertNull(Shadows.shadowOf(a).getNextStartedActivity());
  }

  @Test
  public void repeatedSaveRetryCannotCompleteTheFreshSession() {
    long owner = repo.getProfile().id;
    TrainingActivity a = launch("coordination", "tapping");
    begin(a);
    repo.saveProfile(new UserProfile());
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    choose(AlertDialog.BUTTON_POSITIVE);
    repo.setCurrentProfile(owner);
    Button retry = ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE);
    retry.performClick();
    retry.performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, repo.getRecords(null).size());
    assertFalse(a.getSession().started);
    assertFalse(a.isFinishing());
  }

  @Test
  public void staleCompletionCallbackCannotEndANewOrPartialSession() {
    TrainingActivity a = launch("trace", "line");
    a.onTrainingChanged(true);
    assertTrue(repo.getRecords(null).isEmpty());
    assertFalse(a.getSession().finished);
    begin(a);
    advance(200);
    a.onTrainingChanged(true);
    assertTrue(repo.getRecords(null).isEmpty());
    assertTrue(a.getSession().running);
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(1).performClick();
    choose(AlertDialog.BUTTON_POSITIVE);
    String fresh = a.getSession().sessionId;
    a.onTrainingChanged(true);
    assertEquals(fresh, a.getSession().sessionId);
    assertFalse(a.getSession().finished);
    assertEquals(1, repo.getRecords(null).size());
  }

  @Test
  @Config(sdk = 26)
  public void saveBeforePauseStillRequiresResumeConfirmation() {
    TrainingActivity a = launch("reaction", "random");
    begin(a);
    advance(300);
    controller.saveInstanceState(new Bundle()).pause().resume();
    assertFalse(a.getSession().running);
    assertEquals("训练已暂停", Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle());
    choose(AlertDialog.BUTTON_POSITIVE);
    assertTrue(a.getSession().running);
    assertEquals(0, a.getSession().timeouts);
  }

  @Test
  public void startingWithoutTouchingStillPreservesAnActualIncompleteAttempt() {
    TrainingActivity a = launch("coordination", "tapping");
    begin(a);
    advance(500);
    ((LinearLayout) a.findViewById(R.id.training_actions)).getChildAt(2).performClick();
    choose(AlertDialog.BUTTON_POSITIVE);
    assertEquals(1, repo.getRecords(null).size());
    TrainingRecord record = repo.getRecords(null).get(0);
    assertEquals("incomplete", record.status);
    assertEquals(500, record.durationMs);
    assertNull(record.accuracy);
  }

  @Test
  public void startingRequestedTaskAfterSavingCompletedRecoveryPreservesTheScore() {
    long owner = repo.getProfile().id;
    TrainingActivity previous = launch("reaction", "random");
    begin(previous);
    TrainingCanvasView canvas = previous.findViewById(R.id.training_canvas);
    RectF p = canvas.getPlotBounds();
    int rounds = previous.getSession().expectedTargets();
    for (int i = 0; i < rounds; i++) {
      if (i == rounds - 1) repo.saveProfile(new UserProfile());
      advance(350);
      float x = p.left + (float) previous.getSession().targetX * p.width();
      float y = p.top + (float) previous.getSession().targetY * p.height();
      touch(canvas, MotionEvent.ACTION_DOWN, x, y);
      advance(10);
      touch(canvas, MotionEvent.ACTION_UP, x, y);
    }
    assertTrue(previous.getSession().finished);
    assertFalse(previous.getSession().saved);
    choose(AlertDialog.BUTTON_NEUTRAL);
    controller.pause().stop().destroy();
    repo.setCurrentProfile(owner);
    TrainingActivity requested = launch("trace", "circle");
    assertTrue(
        ((TextView) ShadowAlertDialog.getLatestAlertDialog().findViewById(android.R.id.message))
            .getText()
            .toString()
            .contains("已结束"));
    choose(AlertDialog.BUTTON_NEGATIVE);
    assertEquals("circle", requested.getSession().subtype);
    assertFalse(requested.getSession().started);
    assertEquals(1, repo.getRecords(null).size());
    TrainingRecord record = repo.getRecords(null).get(0);
    assertEquals("completed", record.status);
    assertTrue(record.valid);
    assertTrue(record.score > 0);
    assertEquals(1, repo.completedToday(null));
  }
}
