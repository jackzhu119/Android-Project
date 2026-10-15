package com.fingerrehab.algorithm;

import com.fingerrehab.algorithm.TrainingAlgorithms.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 无 Android 依赖的几何工具；坐标以固定绘图区边长归一化，禁止按用户路径包围盒缩放。 */
public final class Geometry {
  private Geometry() {}

  public static double distance(Point a, Point b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  public static boolean finite(Point p) {
    return p != null && Double.isFinite(p.x) && Double.isFinite(p.y);
  }

  public static List<Point> normalize(List<Point> points, double width, double height) {
    if (points == null
        || !Double.isFinite(width)
        || !Double.isFinite(height)
        || width <= 0
        || height <= 0) return Collections.emptyList();
    double scale = Math.min(width, height);
    List<Point> result = new ArrayList<>(points.size());
    for (Point p : points) {
      if (finite(p)) {
        Point normalized = new Point(p.x / scale, p.y / scale, p.timeMs, p.breakBefore);
        if (finite(normalized)) result.add(normalized);
      }
    }
    return result;
  }

  /** 多笔之间不连接；静止重复点不增加长度。 */
  public static double pathLength(List<Point> path) {
    if (path == null) return 0;
    double total = 0;
    Point previous = null;
    for (Point p : path) {
      if (!finite(p)) {
        previous = null;
        continue;
      }
      if (previous != null && !p.breakBefore) total += distance(previous, p);
      previous = p;
    }
    return total;
  }

  /** 按累计弧长均匀采样，保留首尾与插值时间；多笔之间无虚构连线。 */
  public static List<Point> resample(List<Point> input, int count) {
    if (input == null || count <= 0) return Collections.emptyList();
    List<Point> p = new ArrayList<>();
    List<Double> cumulative = new ArrayList<>();
    List<Integer> strokeNumbers = new ArrayList<>();
    double total = 0;
    boolean nextBreak = false;
    int stroke = 0;
    for (Point item : input) {
      if (!finite(item)) {
        nextBreak = true;
        continue;
      }
      Point point = new Point(item.x, item.y, item.timeMs, item.breakBefore || nextBreak);
      nextBreak = false;
      if (!p.isEmpty() && point.breakBefore) stroke++;
      if (!p.isEmpty() && !point.breakBefore) total += distance(p.get(p.size() - 1), point);
      p.add(point);
      cumulative.add(total);
      strokeNumbers.add(stroke);
    }
    if (p.isEmpty()) return Collections.emptyList();
    if (!Double.isFinite(total)) return Collections.emptyList();
    if (count == 1) return Collections.singletonList(p.get(0));
    if (total < 1e-12) return Collections.singletonList(p.get(0));
    List<Point> output = new ArrayList<>(count);
    int segment = 1;
    int previousStroke = strokeNumbers.get(0);
    for (int i = 0; i < count; i++) {
      if (i == 0) {
        output.add(p.get(0));
        continue;
      }
      if (i == count - 1) {
        Point last = p.get(p.size() - 1);
        output.add(
            new Point(
                last.x, last.y, last.timeMs, strokeNumbers.get(p.size() - 1) != previousStroke));
        continue;
      }
      double at = total * i / (count - 1d);
      while (segment < p.size() - 1
          && (cumulative.get(segment) < at
              || cumulative.get(segment) <= cumulative.get(segment - 1))) segment++;
      double start = cumulative.get(segment - 1);
      double end = cumulative.get(segment);
      Point a = p.get(segment - 1), b = p.get(segment);
      int currentStroke = strokeNumbers.get(segment);
      boolean crossedBreak = currentStroke != previousStroke;
      previousStroke = currentStroke;
      if (end <= start || b.breakBefore) {
        output.add(new Point(b.x, b.y, b.timeMs, crossedBreak));
        continue;
      }
      double fraction = Math.max(0, Math.min(1, (at - start) / (end - start)));
      long time = a.timeMs + Math.round((b.timeMs - (double) a.timeMs) * fraction);
      output.add(
          new Point(
              a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y), time, crossedBreak));
    }
    return output;
  }

  public static double pointToSegment(Point p, Point a, Point b) {
    double dx = b.x - a.x, dy = b.y - a.y;
    double square = dx * dx + dy * dy;
    if (square < 1e-20) return distance(p, a);
    double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / square));
    return Math.hypot(p.x - a.x - t * dx, p.y - a.y - t * dy);
  }

  /** 查询到各真实笔画线段的最近距离，绝不跨 ACTION_UP 连接。 */
  public static double distanceToPath(Point point, List<Point> path) {
    if (path == null || path.isEmpty() || !finite(point)) return Double.POSITIVE_INFINITY;
    double closest = Double.POSITIVE_INFINITY;
    Point previous = null;
    for (Point p : path) {
      if (!finite(p)) {
        previous = null;
        continue;
      }
      closest = Math.min(closest, distance(point, p));
      if (previous != null && !p.breakBefore)
        closest = Math.min(closest, pointToSegment(point, previous, p));
      previous = p;
    }
    return closest;
  }
}
