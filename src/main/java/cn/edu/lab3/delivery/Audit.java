package cn.edu.lab3.delivery;

import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
class Audit {
  private final Db db;
  private final Clock clock;

  Audit(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  void record(Actor who, String target, Long order, Long shop, String action, String before,
      String after, String result, String reason, String request) {
    db.update("""
        INSERT INTO operation_log(operator_id,role,at_time,target,order_id,shop_id,action,
          before_state,after_state,result,reason,request_no) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
        """, who == null || who.id() == 0 ? null : who.id(), who == null ? "GUEST" : who.role(),
        clock.millis(), cut(target, 160), order, shop, cut(action, 80), cut(before, 500),
        cut(after, 500), result, cut(reason, 240), cut(request, 64));
  }

  private String cut(String value, int max) {
    return value == null ? "" : value.substring(0, Math.min(value.length(), max));
  }
}
