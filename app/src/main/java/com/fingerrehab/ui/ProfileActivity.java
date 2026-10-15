package com.fingerrehab.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.model.UserProfile;
import com.fingerrehab.repository.RehabRepository;

/** 首次引导与可编辑本地档案；独立保留原档案、原始基线及未保存草稿。 */
public class ProfileActivity extends Activity {
  private RehabRepository repository;
  private UserProfile profile;
  private Bundle baseline;
  private boolean guide, saving, saved;
  private EditText nickname, age, goal, daily;
  private Spinner hand;
  private Button saveButton;
  private AlertDialog activeDialog;
  private String dialogKind;

  @Override
  @SuppressWarnings("deprecation")
  public void onCreate(Bundle state) {
    super.onCreate(state);
    repository = RehabRepository.get(this);
    // 旋转后不能以数据库当前值替换原档案，否则会丢失 dirty 基线或误写已切换的档案。
    profile =
        state != null && state.getBoolean("original_profile_known")
            ? (UserProfile) state.getSerializable("original_profile")
            : repository.getProfile();
    baseline = state == null ? null : state.getBundle("baseline");
    if (baseline == null) baseline = initialDraft();
    guide = state == null ? profile == null : state.getBoolean("guide", profile == null);
    if (guide) showGuide();
    else showForm(state == null ? null : state.getBundle("draft"));
    restorePosition(state);
    if (!guide && state != null) {
      String pending = state.getString("dialog");
      if ("unsaved".equals(pending)) confirmUnsaved();
      else if ("save_error".equals(pending)) showSaveError();
      else if ("owner_changed".equals(pending)) showOwnerChanged();
    }
  }

  private Bundle initialDraft() {
    Bundle draft = new Bundle();
    draft.putString("nickname", profile == null ? "" : text(profile.nickname));
    draft.putString("age", profile == null || profile.age == null ? "" : profile.age.toString());
    draft.putString("goal", profile == null ? "提升手指运动的稳定与协调" : text(profile.goal));
    draft.putString("daily", profile == null ? "3" : String.valueOf(profile.dailyGoal));
    draft.putInt(
        "hand",
        profile == null ? 0 : "左手".equals(profile.hand) ? 1 : "双手".equals(profile.hand) ? 2 : 0);
    return draft;
  }

  private void showGuide() {
    guide = true;
    nickname = age = goal = daily = null;
    hand = null;
    saveButton = null;
    LinearLayout content = Ui.screen(this, "欢迎使用");
    Ui.gap(content, 26);
    TextView mark = Ui.bold(this, "＋", 64, Ui.BLUE);
    mark.setGravity(17);
    Ui.add(content, mark);
    Ui.gap(content, 20);
    Ui.title(content, "让每一次练习\n都有迹可循", "指尖康复 · " + getString(R.string.app_english_name));
    LinearLayout card = Ui.card(this);
    Ui.add(card, Ui.bold(this, "从指尖开始，记录自己的节奏", 19, Ui.INK));
    Ui.gap(card, 14);
    Ui.add(
        card,
        Ui.text(this, "01  描绘路径，练习稳定控制\n\n02  点击目标，记录反应与协调\n\n03  查看趋势，制定每日小目标", 16, Ui.MUTED));
    Ui.add(content, card);
    Ui.add(
        content,
        Ui.text(
            this, "本应用用于课程设计与辅助训练。评分是软件定义的工程指标，不能诊断疾病或替代专业康复治疗。训练不适时请停止，并咨询专业人员。", 13, Ui.MUTED));
    Ui.gap(content, 24);
    Button start = Ui.button(this, "创建我的本地档案");
    Ui.add(content, start);
    start.setOnClickListener(v -> showForm(null));
  }

  private void showForm(Bundle draft) {
    guide = false;
    LinearLayout content = Ui.screen(this, profile == null ? "创建个人档案" : "编辑个人档案");
    Ui.title(content, "认识你的训练目标", "资料与训练记录仅保存在本机");
    LinearLayout form = Ui.card(this);
    nickname = field(form, "昵称 *", "例如：小林", InputType.TYPE_CLASS_TEXT, 24);
    age = field(form, "年龄（选填）", "1～120", InputType.TYPE_CLASS_NUMBER, 3);
    TextView handLabel = Ui.text(this, "惯用手", 14, Ui.MUTED);
    Ui.add(form, handLabel);
    hand = new Spinner(this);
    hand.setId(View.generateViewId());
    hand.setSaveEnabled(false);
    hand.setContentDescription("惯用手");
    handLabel.setLabelFor(hand.getId());
    hand.setAdapter(
        new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, new String[] {"右手", "左手", "双手"}));
    hand.setMinimumHeight(Ui.dp(this, 52));
    Ui.add(form, hand);
    Ui.gap(form, 12);
    goal = field(form, "训练目标 *", "例如：建立规律的手指练习习惯", InputType.TYPE_CLASS_TEXT, 120);
    daily = field(form, "每日目标次数 *", "1～20", InputType.TYPE_CLASS_NUMBER, 2);
    Ui.add(content, form);
    Bundle values = draft == null ? baseline : draft;
    nickname.setText(values.getString("nickname", ""));
    age.setText(values.getString("age", ""));
    goal.setText(values.getString("goal", ""));
    daily.setText(values.getString("daily", "3"));
    hand.setSelection(Math.max(0, Math.min(2, values.getInt("hand", 0))));
    nickname.setNextFocusForwardId(age.getId());
    age.setNextFocusForwardId(goal.getId());
    goal.setNextFocusForwardId(daily.getId());
    daily.setImeOptions(EditorInfo.IME_ACTION_DONE);
    nickname.setOnEditorActionListener((v, action, event) -> moveNext(action, age));
    age.setOnEditorActionListener((v, action, event) -> moveNext(action, goal));
    goal.setOnEditorActionListener((v, action, event) -> moveNext(action, daily));
    daily.setOnEditorActionListener(
        (v, action, event) -> {
          if (action != EditorInfo.IME_ACTION_DONE) return false;
          save();
          return true;
        });
    saveButton = Ui.button(this, "保存档案");
    Ui.add(content, saveButton);
    saveButton.setOnClickListener(v -> save());
    Ui.gap(content, 16);
    Ui.add(content, Ui.text(this, "每日次数是个人练习目标，训练计划可另外指定模式。请根据自身情况和专业建议合理安排。", 13, Ui.MUTED));
  }

  private EditText field(LinearLayout box, String label, String hint, int input, int maxLength) {
    TextView title = Ui.text(this, label, 14, Ui.MUTED);
    Ui.add(box, title);
    EditText edit = new EditText(this);
    edit.setId(View.generateViewId());
    edit.setSaveEnabled(false);
    title.setLabelFor(edit.getId());
    edit.setTextSize(16);
    edit.setSingleLine(true);
    edit.setHint(hint);
    edit.setInputType(input);
    edit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maxLength)});
    edit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
    edit.setMinimumHeight(Ui.dp(this, 52));
    Ui.add(box, edit);
    Ui.gap(box, 12);
    return edit;
  }

  private boolean moveNext(int action, EditText next) {
    if (action != EditorInfo.IME_ACTION_NEXT) return false;
    next.requestFocus();
    return true;
  }

  private Bundle currentDraft() {
    Bundle draft = new Bundle();
    draft.putString("nickname", nickname.getText().toString());
    draft.putString("age", age.getText().toString());
    draft.putString("goal", goal.getText().toString());
    draft.putString("daily", daily.getText().toString());
    draft.putInt("hand", hand.getSelectedItemPosition());
    return draft;
  }

  private boolean isDirty() {
    if (guide || nickname == null) return false;
    Bundle draft = currentDraft();
    for (String key : new String[] {"nickname", "age", "goal", "daily"})
      if (!draft.getString(key, "").equals(baseline.getString(key, ""))) return true;
    return draft.getInt("hand") != baseline.getInt("hand");
  }

  private void save() {
    if (guide || saving || saved || isFinishing() || isDestroyed() || dialogShowing()) return;
    for (EditText edit : new EditText[] {nickname, age, goal, daily}) edit.setError(null);
    String name = nickname.getText().toString().trim(), target = goal.getText().toString().trim();
    if (name.isEmpty() || name.length() > 24) {
      invalid(nickname, "请输入1～24字昵称");
      return;
    }
    if (target.isEmpty() || target.length() > 120) {
      invalid(goal, "请输入1～120字训练目标");
      return;
    }
    Integer years = null;
    int dailyGoal;
    try {
      String a = age.getText().toString().trim();
      if (!a.isEmpty()) {
        years = Integer.parseInt(a);
        if (years < 1 || years > 120) throw new NumberFormatException();
      }
    } catch (NumberFormatException ex) {
      invalid(age, "请输入1～120，或留空");
      return;
    }
    try {
      dailyGoal = Integer.parseInt(daily.getText().toString().trim());
      if (dailyGoal < 1 || dailyGoal > 20) throw new NumberFormatException();
    } catch (NumberFormatException ex) {
      invalid(daily, "请输入1～20");
      return;
    }
    UserProfile candidate = new UserProfile();
    candidate.id = profile == null ? 0 : profile.id;
    candidate.createdAt = profile == null ? 0 : profile.createdAt;
    candidate.nickname = name;
    candidate.age = years;
    candidate.hand = hand.getSelectedItem().toString();
    candidate.goal = target;
    candidate.dailyGoal = dailyGoal;
    saving = true;
    saveButton.setEnabled(false);
    try {
      // 比对与写入使用仓储同一把锁，避免其他页面或线程切换 owner 后覆盖旧档案。
      synchronized (repository) {
        UserProfile current = repository.getProfile();
        long currentId = current == null ? 0 : current.id;
        if (currentId != candidate.id) {
          showOwnerChanged();
          return;
        }
        repository.saveProfile(candidate);
      }
      profile = candidate;
      baseline = currentDraft();
      saved = true;
      Toast.makeText(this, "档案已保存在本机", Toast.LENGTH_SHORT).show();
      finish();
    } catch (RuntimeException ex) {
      showSaveError();
    } finally {
      saving = false;
      if (!saved && saveButton != null) saveButton.setEnabled(true);
    }
  }

  private void invalid(EditText edit, String message) {
    edit.setError(message);
    edit.requestFocus();
  }

  private boolean dialogShowing() {
    return activeDialog != null && activeDialog.isShowing();
  }

  private void showDialog(AlertDialog dialog, String kind) {
    if (dialogShowing() || isFinishing() || isDestroyed()) return;
    activeDialog = dialog;
    dialogKind = kind;
    dialog.setOnDismissListener(
        ignored -> {
          if (activeDialog == dialog) {
            activeDialog = null;
            dialogKind = null;
          }
        });
    dialog.show();
  }

  private void showSaveError() {
    showDialog(
        new AlertDialog.Builder(this)
            .setTitle("档案未保存")
            .setMessage("保存失败，请检查设备存储空间后重试。已填写的内容仍保留在本页。")
            .setPositiveButton("知道了", null)
            .create(),
        "save_error");
  }

  private void showOwnerChanged() {
    showDialog(
        new AlertDialog.Builder(this)
            .setTitle("当前档案已改变")
            .setMessage("为避免覆盖其他档案，本页未保存。请返回后重新打开个人档案；已填写的内容仍保留在本页。")
            .setPositiveButton("知道了", null)
            .create(),
        "owner_changed");
  }

  private void confirmUnsaved() {
    if (dialogShowing()) return;
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("保存档案修改？")
            .setMessage("还有未保存的内容，可保存后退出，或继续编辑。")
            .setPositiveButton("保存", null)
            .setNegativeButton("放弃", null)
            .setNeutralButton("继续编辑", null)
            .create();
    showDialog(dialog, "unsaved");
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              if (!dialog.isShowing()) return;
              dialog.dismiss();
              save();
            });
    dialog
        .getButton(AlertDialog.BUTTON_NEGATIVE)
        .setOnClickListener(
            v -> {
              if (!dialog.isShowing()) return;
              dialog.dismiss();
              leaveForm();
            });
  }

  private void leaveForm() {
    if (profile == null) {
      // 新建保存失败仍属首次引导；只有其他页面已创建档案时才直接返回。
      try {
        if (repository.getProfile() != null) {
          finish();
          return;
        }
      } catch (RuntimeException ignored) {
      }
      baseline = initialDraft();
      showGuide();
    } else finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putBoolean("original_profile_known", true);
    out.putSerializable("original_profile", profile);
    out.putBoolean("guide", guide);
    out.putBundle("baseline", new Bundle(baseline));
    if (!guide && nickname != null) {
      out.putBundle("draft", currentDraft());
      EditText[] fields = {nickname, age, goal, daily};
      for (int i = 0; i < fields.length; i++)
        if (fields[i].hasFocus()) {
          out.putInt("focused", i);
          out.putInt("selection_start", fields[i].getSelectionStart());
          out.putInt("selection_end", fields[i].getSelectionEnd());
        }
    }
    ScrollView scroll = findViewById(R.id.screen_scroll);
    if (scroll != null) out.putInt("scroll", scroll.getScrollY());
    if (dialogShowing()) out.putString("dialog", dialogKind);
    super.onSaveInstanceState(out);
  }

  private void restorePosition(Bundle state) {
    if (state == null) return;
    ScrollView scroll = findViewById(R.id.screen_scroll);
    if (scroll == null) return;
    Ui.afterLayout(
        scroll,
        () -> {
          if (!guide && nickname != null && state.containsKey("focused")) {
            EditText[] fields = {nickname, age, goal, daily};
            int index = state.getInt("focused", -1);
            if (index >= 0 && index < fields.length) {
              EditText field = fields[index];
              field.requestFocus();
              int length = field.length();
              int start = Math.max(0, Math.min(length, state.getInt("selection_start", length)));
              int end = Math.max(start, Math.min(length, state.getInt("selection_end", start)));
              field.setSelection(start, end);
            }
          }
          scroll.scrollTo(0, state.getInt("scroll", 0));
        });
  }

  @Override
  protected void onDestroy() {
    if (activeDialog != null) activeDialog.dismiss();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (saving || saved || isFinishing() || dialogShowing()) return;
    if (guide) finishAffinity();
    else if (isDirty()) confirmUnsaved();
    else leaveForm();
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
