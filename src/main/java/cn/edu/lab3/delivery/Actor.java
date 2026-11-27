package cn.edu.lab3.delivery;

import java.util.Map;

record Actor(long id, String account, String role) {
  static Actor from(Map<String, Object> row) {
    return new Actor(Db.num(row, "id"), (String) row.get("account"), (String) row.get("role"));
  }

  void require(String expected) {
    if (!role.equals(expected)) {
      throw Problem.denied();
    }
  }
}
