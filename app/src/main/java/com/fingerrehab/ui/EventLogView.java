package com.fingerrehab.ui;

import android.content.Context;
import android.widget.*;
import com.fingerrehab.utils.Labels;
import org.json.*;

/** 分页读取已经保存的原始事件；单条损坏不会阻断其它事件查看。 */
public final class EventLogView extends LinearLayout {
  static final int PAGE_SIZE = 30;
  private final JSONArray events;
  private final String type;
  private int page;
  private final TextView position, body;
  private final Button previous, next;

  public EventLogView(Context context, JSONArray events, String type, int initialPage) {
    super(context);
    setOrientation(VERTICAL);
    this.events = events;
    this.type = type;
    page = Math.max(0, Math.min(initialPage, (events.length() - 1) / PAGE_SIZE));
    position = Ui.text(context, "", 13, Ui.MUTED);
    position.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    Ui.add(this, position);
    Ui.gap(this, 8);
    body = Ui.text(context, "", 13, Ui.MUTED);
    body.setTextIsSelectable(true);
    Ui.add(this, body);
    Ui.gap(this, 8);
    LinearLayout controls = Ui.row(context);
    previous = Ui.secondary(context, "上一组事件");
    next = Ui.secondary(context, "下一组事件");
    controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
    controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.add(this, controls);
    previous.setOnClickListener(v -> move(-1));
    next.setOnClickListener(v -> move(1));
    render();
  }

  public int getPage() {
    return page;
  }

  private void move(int offset) {
    int last = Math.max(0, (events.length() - 1) / PAGE_SIZE);
    page = Math.max(0, Math.min(last, page + offset));
    render();
    Ui.afterLayout(
        position,
        () ->
            position.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, position.getWidth(), position.getHeight()), true));
  }

  private void render() {
    int start = page * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, events.length());
    position.setText("事件 " + (start + 1) + "～" + end + " / 共 " + events.length() + " 条");
    StringBuilder text = new StringBuilder();
    for (int i = start; i < end; i++) {
      text.append(i + 1).append(". ");
      JSONObject event = events.optJSONObject(i);
      if (event == null) {
        text.append("此条事件暂不可读");
      } else {
        text.append(label(event.optString("outcome", "")));
        Double time = number(event, "reaction".equals(type) ? "reactionMs" : "timeMs");
        if (time != null) {
          if ("reaction".equals(type))
            text.append(" · 目标出现后 ").append(Labels.number(time)).append(" ms");
          else if ("coordination".equals(type) && time < Long.MAX_VALUE)
            text.append(" · 训练开始后 ").append(Labels.duration(time.longValue()));
        }
      }
      text.append('\n');
    }
    body.setText(text.toString().trim());
    previous.setEnabled(page > 0);
    next.setEnabled(end < events.length());
  }

  private static Double number(JSONObject event, String key) {
    Object raw = event.opt(key);
    if (!(raw instanceof Number)) return null;
    double value = ((Number) raw).doubleValue();
    return Double.isFinite(value) && value >= 0 ? value : null;
  }

  private static String label(String outcome) {
    switch (outcome) {
      case "hit":
        return "成功";
      case "miss":
        return "点击错误";
      case "timeout":
        return "目标超时";
      case "too_fast":
        return "过快，未计入有效点击";
      case "anticipation":
        return "提前点击，当前轮次未推进";
      default:
        return "未识别事件";
    }
  }
}
