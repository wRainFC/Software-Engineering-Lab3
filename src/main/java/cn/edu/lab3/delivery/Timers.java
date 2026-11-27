package cn.edu.lab3.delivery;

import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class Timers {
  private static final Actor SYSTEM = new Actor(0, "系统时钟", "SYSTEM");
  private final Db db;
  private final OrderFlow flow;
  private final Clock clock;
  private final Audit audit;
  private final TransactionTemplate tx;
  private final boolean failFirst;

  Timers(Db db, OrderFlow flow, Clock clock, Audit audit, TransactionTemplate tx,
      @Value("${delivery.refund-fail-first}") boolean failFirst) {
    this.db = db;
    this.flow = flow;
    this.clock = clock;
    this.audit = audit;
    this.tx = tx;
    this.failFirst = failFirst;
  }

  @Scheduled(fixedDelay = 2000)
  void timeout() {
    for (var order : db.rows("""
        SELECT id,version,shop_id FROM orders WHERE status='WAITING' AND deadline<=?
        ORDER BY deadline
        """, clock.millis())) {
      long id = Db.num(order, "id");
      try {
        flow.apply(SYSTEM, id, new OrderFlow.Command("TIMEOUT", Db.num(order, "version"),
            "timeout-" + id, "", null, null));
      } catch (Problem raced) {
        audit.record(SYSTEM, "order/" + id, id, Db.num(order, "shop_id"), "TIMEOUT", "", "",
            "FAILED", raced.getMessage(), "timeout-" + id);
      }
    }
  }

  @Scheduled(fixedDelay = 2000)
  void refunds() {
    for (var row : db.rows("""
        SELECT r.*,o.shop_id FROM refund r JOIN orders o ON o.id=r.order_id
        WHERE r.status IN ('PENDING','PROCESSING') AND r.updated_at<=?
        """, clock.millis() - 1000)) {
      tx.executeWithoutResult(st -> advance(row));
    }
  }

  private void advance(Map<String, Object> row) {
    long id = Db.num(row, "order_id");
    String old = (String) row.get("status");
    String next = "PROCESSING";
    if (old.equals("PROCESSING")) {
      next = failFirst && Db.num(row, "attempts") == 1 ? "FAILED" : "REFUNDED";
    }
    String reason = next.equals("FAILED") ? "模拟退款通道首次处理失败，可沿原退款号重试" : "";
    if (db.update("""
        UPDATE refund SET status=?,reason=?,updated_at=?
        WHERE order_id=? AND status=? AND attempts=?
        """, next, reason, clock.millis(), id, old, row.get("attempts")) == 1) {
      audit.record(SYSTEM, "refund/" + id, id, Db.num(row, "shop_id"), "REFUND", old, next,
          "OK", reason, row.get("refund_no").toString());
    }
  }
}
