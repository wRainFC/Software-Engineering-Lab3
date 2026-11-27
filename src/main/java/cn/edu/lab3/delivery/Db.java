package cn.edu.lab3.delivery;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared JDBC access. Inserts returning an id must run in a transaction. */
@Repository
class Db {
  final JdbcTemplate jdbc;

  Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    if (rows.isEmpty()) {
      throw Problem.missing();
    }
    return rows.getFirst();
  }

  long number(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  long insert(String sql, Object... args) {
    update(sql, args);
    return number("SELECT LAST_INSERT_ID()");
  }

  static long num(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  static boolean flag(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
  }
}
