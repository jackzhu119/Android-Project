package com.fingerrehab.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.model.TrainingRecord;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.training.TrainingActivity;
import com.fingerrehab.utils.Labels;
import org.json.*;

/** 统一结果报告；只从已经持久化的记录加载，所有可选指标保留真实缺失状态。 */
public class ResultActivity extends Activity {
  private EventLogView eventLog;
  private int eventPage;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    eventPage = state == null ? 0 : state.getInt("event_page", 0);
    LinearLayout content = Ui.screen(this, "训练报告");
    TrainingRecord r =
        RehabRepository.get(this).getRecord(getIntent().getLongExtra("record_id", 0));
    if (r == null) {
      Ui.empty(content, "记录不存在", "该训练可能已被删除，返回历史记录可查看其他练习。");
      return;
    }
    Ui.title(
        content,
        Labels.subtype(r.subtype),
        Labels.difficulty(r.difficulty)
            + "难度 · "
            + Labels.date(r.startedAt)
            + " · "
            + Labels.status(r.status));
    LinearLayout score = Ui.card(this);
    Ui.add(score, new ScoreView(this, r.score));
    Ui.add(
        score,
        Ui.text(
            this,
            r.isEffectiveCompletion() ? "本次训练已保存 · 有效工程评分" : "本次过程已保存 · 未满足有效训练条件",
            14,
            r.isEffectiveCompletion() ? Ui.TEAL : 0xFFB46C26));
    Ui.add(content, score);
    LinearLayout metrics = Ui.card(this);
    Ui.add(metrics, Ui.bold(this, "本次表现", 19, Ui.INK));
    Ui.metric(metrics, "训练用时", Labels.duration(r.durationMs));
    Ui.metric(metrics, "点击 / 路径准确率", Labels.percent(r.accuracy));
    Ui.metric(metrics, "错误次数", r.errors + " 次");
    if ("trace".equals(r.type)) {
      Ui.metric(
          metrics,
          "平均轨迹偏差",
          r.meanDeviation == null ? "—" : Labels.number(r.meanDeviation * 100) + "% 绘图区边长");
      Ui.metric(metrics, "目标路径覆盖率", Labels.percent(r.coverage));
      Ui.metric(metrics, "操作稳定性", Labels.percent(r.stability));
    }
    if ("reaction".equals(r.type)) {
      Ui.metric(
          metrics,
          "平均反应时间",
          r.averageReactionMs == null ? "—" : Labels.number(r.averageReactionMs) + " ms");
      Ui.metric(
          metrics,
          "最快反应时间",
          r.fastestReactionMs == null ? "—" : Labels.number(r.fastestReactionMs) + " ms");
      Ui.metric(metrics, "成功点击", r.successes + " 次");
      Ui.metric(metrics, "目标超时", r.timeouts + " 次");
    }
    if ("coordination".equals(r.type)) {
      Ui.metric(metrics, "有效点击", r.successes + " 次");
      Ui.metric(
          metrics,
          "平均点击间隔",
          r.averageIntervalMs == null ? "不适用 / 样本不足" : Labels.number(r.averageIntervalMs) + " ms");
      Ui.metric(metrics, "节奏稳定性", r.stability == null ? "不适用 / 样本不足" : Labels.percent(r.stability));
    }
    Ui.add(content, metrics);
    LinearLayout feedback = Ui.card(this);
    Ui.add(feedback, Ui.bold(this, "练习反馈", 18, Ui.INK));
    Ui.gap(feedback, 10);
    Ui.add(feedback, Ui.text(this, feedback(r), 15, Ui.INK));
    try {
      JSONObject detail = new JSONObject(r.detailsJson == null ? "{}" : r.detailsJson);
      String reason = detail.optString("assessmentReason", "");
      if (!reason.isEmpty()) {
        Ui.gap(feedback, 8);
        Ui.add(feedback, Ui.text(this, "评分依据：" + reason, 13, Ui.MUTED));
      }
    } catch (JSONException ignored) {
    }
    Ui.gap(feedback, 10);
    Ui.add(feedback, Ui.text(this, "以上是软件定义的工程训练指标，不能用于疾病诊断。比较时请保持训练类型、子类型和难度一致。", 12, Ui.MUTED));
    Ui.add(content, feedback);
    addDetails(content, r);
    Button retry = Ui.button(this, "再练一次 · " + Labels.difficulty(r.difficulty));
    Ui.add(content, retry);
    retry.setOnClickListener(
        v -> {
          startActivity(
              new Intent(this, TrainingActivity.class)
                  .putExtra("type", r.type)
                  .putExtra("subtype", r.subtype)
                  .putExtra("difficulty", r.difficulty));
          finish();
        });
    Ui.gap(content, 12);
    Button back = Ui.secondary(this, "返回");
    Ui.add(content, back);
    back.setOnClickListener(v -> finish());
    if (state != null) {
      ScrollView scroll = findViewById(R.id.screen_scroll);
      int y = state.getInt("report_scroll", 0);
      Ui.afterLayout(scroll, () -> scroll.scrollTo(0, y));
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("event_page", eventLog == null ? 0 : eventLog.getPage());
    ScrollView scroll = findViewById(R.id.screen_scroll);
    if (scroll != null) out.putInt("report_scroll", scroll.getScrollY());
  }

  private String feedback(TrainingRecord r) {
    if (!r.isEffectiveCompletion()) return "本次未满足有效训练条件，成绩不计入有效平均分与难度推荐。可以阅读下方依据后重新练习。";
    if ("trace".equals(r.type)) {
      if (r.coverage != null
          && r.coverage >= .85
          && r.meanDeviation != null
          && r.meanDeviation > .025) return "本次路径覆盖较完整，可以尝试放慢一些，让手指更贴近目标路径。";
      return "本次覆盖率为"
          + Labels.percent(r.coverage)
          + "，稳定性为"
          + Labels.percent(r.stability)
          + "。下次可继续关注路径完整度与平稳移动。";
    }
    if (r.accuracy != null && r.accuracy >= .9) return "本次点击准确率较高。保持舒适节奏，逐步观察同类训练中的表现变化。";
    return "本次记录到" + r.errors + "次点击错误。先看清目标位置与操作顺序，再按自己的节奏点击。";
  }

  private void addDetails(LinearLayout parent, TrainingRecord r) {
    if (r.detailsUnavailable || r.detailsJson == null || r.detailsJson.trim().isEmpty()) {
      Ui.empty(parent, "原始详情暂不可读", "汇总指标已显示，原始记录仍保存在本机。");
      return;
    }
    try {
      JSONObject json = new JSONObject(r.detailsJson);
      LinearLayout box = Ui.card(this);
      Ui.add(box, Ui.bold(this, "过程记录", 18, Ui.INK));
      JSONArray samples = json.optJSONArray("samples");
      if ("trace".equals(r.type) && samples != null) {
        Ui.metric(box, "保存的触摸采样点", samples.length() + " 个");
        int observed = json.optInt("observedSamples", samples.length());
        if (observed > samples.length())
          Ui.add(box, Ui.text(this, "无效、过密或超限采样已过滤或压缩；评分与回放采用保存的样本。", 12, Ui.MUTED));
        TracePreviewView preview = new TracePreviewView(this, r);
        Ui.add(box, preview);
        Ui.add(box, Ui.text(this, "蓝色：目标路径　青绿：实际轨迹\n绿色为起点，空白间隔表示抬指或中断。", 12, Ui.MUTED));
      }
      JSONArray events = json.optJSONArray("events");
      if (events != null && events.length() > 0) {
        Ui.gap(box, 10);
        eventLog = new EventLogView(this, events, r.type, eventPage);
        Ui.add(box, eventLog);
      }
      Ui.add(parent, box);
    } catch (JSONException ignored) {
      Ui.empty(parent, "原始详情暂不可读", "汇总指标已显示，记录仍保存在本机。");
    }
  }
}
