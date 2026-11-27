package cn.edu.lab3.delivery;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderFlow {
  record Command(String action, long version, String requestNo, String reason, Long riderId,
      Long taskId) {}

  record Result(String status, long version) {}

  private enum Edge {
    ACCEPT("MERCHANT", "WAITING", "ACCEPTED"),
    REJECT("MERCHANT", "WAITING", "CANCELLED"),
    CANCEL("CUSTOMER", "WAITING", "CANCELLED"),
    TIMEOUT("SYSTEM", "WAITING", "CANCELLED"),
    REQUEST_CANCEL("CUSTOMER", "ACCEPTED", "ACCEPTED"),
    APPROVE("MERCHANT", "ACCEPTED", "CANCELLED"),
    DECLINE("MERCHANT", "ACCEPTED", "ACCEPTED"),
    COOK("MERCHANT", "ACCEPTED", "COOKING"),
    READY("MERCHANT", "COOKING", "READY"),
    ASSIGN("ADMIN", "READY", "READY"),
    PICK("RIDER", "READY", "DELIVERING"),
    DELIVER("RIDER", "DELIVERING", "DELIVERED"),
    EXCEPTION("RIDER", "DELIVERING", "EXCEPTION"),
    RESTORE("ADMIN", "EXCEPTION", "READY"),
    ABORT("ADMIN", "COOKING READY DELIVERING EXCEPTION", "CANCELLED"),
    CONFIRM("CUSTOMER", "DELIVERED", "COMPLETED"),
    RETRY_REFUND("ADMIN", "CANCELLED", "CANCELLED");

    final String role;
    final String[] from;
    final String to;

    Edge(String role, String from, String to) {
      this.role = role;
      this.from = from.split(" ");
      this.to = to;
    }

    static Edge parse(String action) {
      try {
        return valueOf(action == null ? "" : action);
      } catch (IllegalArgumentException unknown) {
        throw new Problem(400, "未知订单操作");
      }
    }
  }

  private final Db db;
  private final Audit audit;
  private final Clock clock;

  OrderFlow(Db db, Audit audit, Clock clock) {
    this.db = db;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  Result apply(Actor actor, long id, Command cmd) {
    Edge edge = Edge.parse(cmd.action());
    actor.require(edge.role);
    String number = Problem.requestNo(cmd.requestNo());
    var order = db.one("SELECT * FROM orders WHERE id=? FOR UPDATE", id);
    long shop = Db.num(order, "shop_id");
    authorize(actor, id, order, cmd.taskId());
    String hash = Checkout.digest(id + "/" + cmd);
    var previous = db.rows("SELECT * FROM action_receipt WHERE operator_id=? AND request_no=?",
        actor.id(), number);
    if (!previous.isEmpty()) {
      var row = previous.getFirst();
      if (!hash.equals(row.get("digest"))) {
        throw Problem.conflict("操作请求号冲突");
      }
      return new Result((String) row.get("result_status"), Db.num(row, "result_version"));
    }
    String status = (String) order.get("status");
    long version = Db.num(order, "version");
    if (version != cmd.version() || !Arrays.asList(edge.from).contains(status)) {
      throw new Problem(409, "订单状态已变化，请刷新", Map.of("status", status, "version", version));
    }
    long now = clock.millis();
    String reason = cmd.reason() == null ? "" : cmd.reason().strip();
    Problem.check(reason.length() <= 240, "说明最多 240 字");
    switch (edge) {
      case ACCEPT -> {
        if (now >= Db.num(order, "deadline")) {
          throw Problem.conflict("已超过接单截止时间");
        }
      }
      case TIMEOUT -> {
        if (now < Db.num(order, "deadline")) {
          throw Problem.conflict("尚未到接单截止时间");
        }
        reason = "商家超时未接单";
      }
      case REJECT, ABORT -> Problem.field(reason, 240, "取消原因");
      case REQUEST_CANCEL -> db.update("""
          INSERT INTO cancel_request(order_id,status,reason,created_at) VALUES(?,'PENDING',?,?)
          """, id, Problem.field(reason, 240, "取消原因"), now);
      case APPROVE, DECLINE -> Catalog.changed(db.update("""
          UPDATE cancel_request SET status=?,handled_by=?,reply=? WHERE pending_order=?
          """, edge == Edge.APPROVE ? "APPROVED" : "DECLINED", actor.id(),
          edge == Edge.DECLINE ? Problem.field(reason, 240, "驳回原因") : reason, id));
      case COOK -> {
        releaseStock(id, false);
        db.update("UPDATE cancel_request SET status='EXPIRED' WHERE pending_order=?", id);
      }
      case ASSIGN -> assign(id, cmd.riderId(), now);
      case PICK, DELIVER, EXCEPTION -> updateTask(edge, id, cmd.taskId(), reason, now);
      case RETRY_REFUND -> Catalog.changed(db.update("""
          UPDATE refund SET status='PROCESSING',reason='',attempts=attempts+1,updated_at=?
          WHERE order_id=? AND status='FAILED'
          """, now, id));
      default -> {
        // READY, CANCEL, RESTORE and CONFIRM only need the common transition below.
      }
    }
    if (edge.to.equals("CANCELLED") && !status.equals("CANCELLED")) {
      if (!Db.flag(order, "consumed")) {
        releaseStock(id, true);
      }
      revoke(id, now);
      db.update("""
          INSERT INTO refund(order_id,refund_no,amount,status,updated_at) VALUES(?,?,?,'PENDING',?)
          """, id, UUID.randomUUID().toString(), order.get("total"), now);
    }
    Catalog.changed(db.update("""
        UPDATE orders SET status=?,reason=?,consumed=(consumed OR ?),version=version+1
        WHERE id=? AND version=?
        """, edge.to, reason.isBlank() ? order.get("reason") : reason, edge == Edge.COOK, id,
        version));
    audit.record(actor, "order/" + id, id, shop, edge.name(), status, edge.to, "OK", reason,
        number);
    if (actor.id() != 0) {
      db.update("""
          INSERT INTO action_receipt(operator_id,request_no,order_id,digest,
            result_status,result_version)
          VALUES(?,?,?,?,?,?)
          """, actor.id(), number, id, hash, edge.to, version + 1);
    }
    return new Result(edge.to, version + 1);
  }

  private void authorize(Actor actor, long id, Map<String, Object> order, Long task) {
    boolean allowed = switch (actor.role()) {
      case "CUSTOMER" -> actor.id() == Db.num(order, "customer_id");
      case "MERCHANT" -> db.number("SELECT owner_id FROM shop WHERE id=?", order.get("shop_id"))
          == actor.id();
      case "RIDER" -> task != null && db.number("""
          SELECT COUNT(*) FROM delivery_task WHERE id=? AND order_id=? AND rider_id=?
          """, task, id, actor.id()) == 1;
      default -> true;
    };
    if (!allowed) {
      throw Problem.denied();
    }
  }

  private void releaseStock(long order, boolean restore) {
    for (var item : db.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY dish_id", order)) {
      long qty = Db.num(item, "quantity");
      Catalog.changed(db.update("""
          UPDATE dish SET held=held-?,stock=stock+?,version=version+1 WHERE id=? AND held>=?
          """, qty, restore ? qty : 0, item.get("dish_id"), qty));
    }
  }

  private void revoke(long order, long now) {
    db.update("UPDATE delivery_task SET status='REVOKED',ended_at=? WHERE active_order=?",
        now, order);
  }

  private void assign(long order, Long rider, long now) {
    Problem.check(rider != null, "请选择配送员");
    var user = db.one("SELECT role,enabled FROM users WHERE id=? FOR UPDATE", rider);
    Problem.check("RIDER".equals(user.get("role")) && Db.flag(user, "enabled"), "配送员无效或已停用");
    revoke(order, now);
    db.update("""
        INSERT INTO delivery_task(order_id,rider_id,status,assigned_at) VALUES(?,?,'ASSIGNED',?)
        """, order, rider, now);
  }

  private void updateTask(Edge edge, long order, Long task, String reason, long now) {
    if (edge == Edge.PICK) {
      Catalog.changed(db.update("""
          UPDATE delivery_task SET status='PICKED',picked_at=?
          WHERE id=? AND active_order=? AND status='ASSIGNED'
          """, now, task, order));
    } else {
      String note = edge == Edge.EXCEPTION ? Problem.field(reason, 240, "异常原因") : "";
      Catalog.changed(db.update("""
          UPDATE delivery_task SET status=?,reason=?,ended_at=?
          WHERE id=? AND active_order=? AND status='PICKED'
          """, edge == Edge.EXCEPTION ? "EXCEPTION" : "DELIVERED", note, now, task, order));
    }
  }
}
