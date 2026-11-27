package cn.edu.lab3.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parameterized predicates shared by lists and aggregates. Columns are server constants. */
class Filters {
  private final List<String> predicates = new ArrayList<>();
  private final List<Object> values = new ArrayList<>();

  void add(String predicate, Object value) {
    predicates.add(predicate);
    values.add(value);
  }

  void optional(Map<String, String> query, String key, String column) {
    String value = query.get(key);
    if (value != null && !value.isBlank()) {
      add(column + " = ?", value);
    }
  }

  void range(Map<String, String> query, String low, String high, String column, boolean openEnd) {
    Long min = numeric(query.get(low));
    Long max = numeric(query.get(high));
    Problem.check(min == null || max == null || min <= max, "筛选范围倒置");
    if (min != null) {
      add(column + " >= ?", min);
    }
    if (max != null) {
      add(column + (openEnd ? " < ?" : " <= ?"), max);
    }
  }

  static Long numeric(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      long number = Long.parseLong(value);
      Problem.check(number >= 0, "筛选条件必须是非负整数");
      return number;
    } catch (NumberFormatException invalid) {
      throw new Problem(400, "筛选条件必须是整数");
    }
  }

  String where() {
    return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
  }

  Object[] args() {
    return values.toArray();
  }
}
