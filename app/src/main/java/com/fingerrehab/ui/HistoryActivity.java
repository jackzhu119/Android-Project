package com.fingerrehab.ui;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.fingerrehab.R;
import com.fingerrehab.model.TrainingRecord;
import com.fingerrehab.repository.RehabRepository;
import com.fingerrehab.utils.Labels;
import java.util.*;

/** 可筛选、查看与确认删除的真实训练历史，分批展示避免大量记录阻塞布局。 */
public class HistoryActivity extends Activity {
  private LinearLayout content, records;
  private RehabRepository repo;
  private static final int PAGE_SIZE = 30;
  private int filter = 0, pageIndex = 0;
  private int scrollY, scrollGeneration;
  private AlertDialog deleteDialog;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    repo = RehabRepository.get(this);
    if (state != null) {
      filter = Math.max(0, Math.min(3, state.getInt("filter")));
      pageIndex = Math.max(0, state.getInt("page", 0));
      scrollY = Math.max(0, state.getInt("scroll", 0));
    }
    build();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (records != null) showRecords();
  }

  @Override
  protected void onPause() {
    scrollY = ((ScrollView) findViewById(R.id.screen_scroll)).getScrollY();
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("filter", filter);
    out.putInt("page", pageIndex);
    out.putInt("scroll", ((ScrollView) findViewById(R.id.screen_scroll)).getScrollY());
  }

  private void build() {
    content = Ui.screen(this, "训练历史");
    Ui.title(content, "每一次练习，都被记住", "按训练时间倒序 · 点击卡片查看详细报告");
    Spinner spinner = new Spinner(this);
    spinner.setMinimumHeight(Ui.dp(this, 52));
    spinner.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"全部训练类型", "轨迹描绘", "反应速度", "手指协调"}));
    spinner.setSelection(filter);
    Ui.add(content, spinner);
    Ui.gap(content, 14);
    records = Ui.column(this);
    Ui.add(content, records);
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            if (filter != position) {
              filter = position;
              pageIndex = 0;
              scrollY = 0;
              showRecords();
            }
          }
        });
    showRecords();
  }

  private void showRecords() {
    records.removeAllViews();
    String[] types = {null, "trace", "reaction", "coordination"};
    int total = repo.recordCount(types[filter], null, null);
    int oldPage = pageIndex;
    pageIndex = Math.min(pageIndex, Math.max(0, (total - 1) / PAGE_SIZE));
    if (oldPage != pageIndex) scrollY = 0;
    List<TrainingRecord> list =
        repo.getRecords(types[filter], null, null, PAGE_SIZE, pageIndex * PAGE_SIZE);
    if (list.isEmpty()) {
      Ui.empty(records, "还没有这类训练记录", "完成一次训练后，真实成绩会自动保存在这里。中止与未完成记录也会保留，并明确标记。");
      scrollToSavedPosition();
      return;
    }
    Ui.add(
        records,
        Ui.text(
            this,
            "共 "
                + total
                + " 条记录 · 第 "
                + (pageIndex + 1)
                + "/"
                + ((total + PAGE_SIZE - 1) / PAGE_SIZE)
                + " 页",
            13,
            Ui.MUTED));
    Ui.gap(records, 12);
    for (int i = 0; i < list.size(); i++) {
      TrainingRecord r = list.get(i);
      LinearLayout card = Ui.card(this);
      Ui.add(
          card,
          Ui.bold(
              this,
              Labels.subtype(r.subtype)
                  + " · "
                  + (r.isEffectiveCompletion()
                      ? Labels.number(r.score) + " 分"
                      : Labels.status(r.status)),
              19,
              Ui.INK));
      Ui.gap(card, 8);
      Ui.add(
          card,
          Ui.text(
              this,
              Labels.date(r.startedAt) + "  ·  " + Labels.difficulty(r.difficulty),
              13,
              Ui.MUTED));
      Ui.gap(card, 4);
      Ui.add(
          card,
          Ui.text(
              this,
              Labels.duration(r.durationMs)
                  + "  ·  "
                  + Labels.status(r.status)
                  + (r.isEffectiveCompletion() ? "" : " · 不计入有效成绩趋势"),
              13,
              Ui.MUTED));
      Ui.gap(card, 12);
      Button detail = Ui.secondary(this, "查看报告");
      Ui.add(card, detail);
      detail.setOnClickListener(v -> open(r));
      card.setOnClickListener(v -> open(r));
      Ui.gap(card, 8);
      Button delete = Ui.secondary(this, "删除这条记录");
      delete.setTextColor(0xFFB34D50);
      Ui.add(card, delete);
      delete.setOnClickListener(
          v -> {
            if (deleteDialog != null && deleteDialog.isShowing()) return;
            long owner = currentOwner();
            deleteDialog =
                new AlertDialog.Builder(this)
                    .setTitle("删除训练记录？")
                    .setMessage("将永久删除这次" + Labels.subtype(r.subtype) + "的成绩与原始训练数据，统计和计划进度也会更新。")
                    .setNegativeButton("保留", null)
                    .setPositiveButton(
                        "删除",
                        (dialog, which) -> {
                          try {
                            if (currentOwner() != owner) {
                              Toast.makeText(this, "当前档案已变化，请重新选择记录", Toast.LENGTH_LONG).show();
                              showRecords();
                              return;
                            }
                            scrollY = ((ScrollView) findViewById(R.id.screen_scroll)).getScrollY();
                            repo.deleteRecord(r.id);
                            showRecords();
                          } catch (RuntimeException ex) {
                            Toast.makeText(this, "删除失败，请稍后重试", Toast.LENGTH_LONG).show();
                          }
                        })
                    .show();
            deleteDialog.setOnDismissListener(dialog -> deleteDialog = null);
          });
      Ui.add(records, card);
    }
    if (pageIndex > 0) {
      Button previous = Ui.secondary(this, "上一页");
      Ui.add(records, previous);
      previous.setOnClickListener(
          v -> {
            pageIndex--;
            showRecords();
            scrollToStart();
          });
      Ui.gap(records, 8);
    }
    if ((pageIndex + 1) * PAGE_SIZE < total) {
      Button next = Ui.button(this, "下一页");
      Ui.add(records, next);
      next.setOnClickListener(
          v -> {
            pageIndex++;
            showRecords();
            scrollToStart();
          });
    }
    scrollToSavedPosition();
  }

  private void scrollToStart() {
    scrollY = 0;
    scrollToSavedPosition();
  }

  private void scrollToSavedPosition() {
    ScrollView scroll = findViewById(R.id.screen_scroll);
    int generation = ++scrollGeneration;
    Ui.afterLayout(
        scroll,
        () -> {
          if (generation == scrollGeneration) scroll.scrollTo(0, scrollY);
        });
  }

  @Override
  protected void onDestroy() {
    if (deleteDialog != null) deleteDialog.dismiss();
    super.onDestroy();
  }

  private void open(TrainingRecord r) {
    startActivity(new Intent(this, ResultActivity.class).putExtra("record_id", r.id));
  }

  private long currentOwner() {
    com.fingerrehab.model.UserProfile profile = repo.getProfile();
    return profile == null ? 0 : profile.id;
  }
}
