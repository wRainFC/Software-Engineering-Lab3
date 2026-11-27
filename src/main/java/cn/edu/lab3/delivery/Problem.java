package cn.edu.lab3.delivery;

import java.util.Map;

/** A business error safe to display to the current user. */
class Problem extends RuntimeException {
  final int status;
  final Map<String, Object> details;

  Problem(int status, String message) {
    this(status, message, Map.of());
  }

  Problem(int status, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.details = details;
  }

  static Problem missing() {
    return new Problem(404, "记录不存在");
  }

  static Problem denied() {
    return new Problem(403, "当前账号无权执行此操作");
  }

  static Problem conflict(String message) {
    return new Problem(409, message);
  }

  static void check(boolean valid, String message) {
    if (!valid) {
      throw new Problem(400, message);
    }
  }

  static String field(String value, int max, String label) {
    check(value != null && !value.isBlank() && value.strip().length() <= max, label + "无效");
    return value.strip();
  }

  static String phone(String value) {
    check(value != null && value.matches("1[3-9][0-9]{9}"), "请输入 11 位大陆手机号");
    return value;
  }

  static String requestNo(String value) {
    check(value != null && value.matches("[A-Za-z0-9_-]{1,64}"), "请求号无效");
    return value;
  }
}
