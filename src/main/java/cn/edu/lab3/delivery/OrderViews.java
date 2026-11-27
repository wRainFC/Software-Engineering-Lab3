package cn.edu.lab3.delivery;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class OrderViews {
  private static final String JOINS = """
       FROM orders o JOIN shop s ON s.id=o.shop_id
       JOIN payment p ON p.order_id=o.id LEFT JOIN refund r ON r.order_id=o.id
      """;
  private static final String SUMMARY = """
      SELECT o.id,o.order_no,o.status,o.total,o.shop_id,o.created_at,o.deadline,o.version,
        s.name shop_name,r.status refund_status,
        EXISTS(SELECT 1 FROM cancel_request c WHERE c.pending_order=o.id) pending_cancel
      """;
  private final Db db;
  private final OrderFlow flow;

  OrderViews(Db db, OrderFlow flow) {
    this.db = db;
    this.flow = flow;
  }

  @PostMapping("/orders/{id}/actions")
  OrderFlow.Result action(@RequestAttribute Actor actor, @PathVariable long id,
      @RequestBody OrderFlow.Command input, HttpServletRequest req) {
    req.setAttribute("auditOrder", id);
    req.setAttribute("auditRequest", input.requestNo());
    return flow.apply(actor, id, input);
  }

  private Filters orderFilters(Actor actor, Map<String, String> query) {
    var f = new Filters();
    switch (actor.role()) {
      case "CUSTOMER" -> f.add("o.customer_id=?", actor.id());
      case "MERCHANT" -> f.add("s.owner_id=?", actor.id());
      case "ADMIN" -> {
        // Administrators see all shops.
      }
      default -> throw Problem.denied();
    }
    f.optional(query, "number", "o.order_no");
    f.optional(query, "shop", "o.shop_id");
    f.optional(query, "status", "o.status");
    f.range(query, "from", "until", "o.created_at", true);
    f.range(query, "min", "max", "o.total", false);
    return f;
  }

  private String page(Map<String, String> query) {
    Long offset = Filters.numeric(query.get("offset"));
    return " LIMIT 50 OFFSET " + (offset == null ? 0L : offset);
  }

  @GetMapping("/orders")
  List<Map<String, Object>> orders(@RequestAttribute Actor actor,
      @RequestParam Map<String, String> q) {
    var f = orderFilters(actor, q);
    return db.rows(SUMMARY + JOINS + f.where() + " ORDER BY o.created_at DESC,o.id DESC" + page(q),
        f.args());
  }

  @GetMapping("/orders/{id}")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Map<String, Object> detail(@RequestAttribute Actor actor, @PathVariable long id,
      HttpServletRequest request) {
    request.setAttribute("auditOrder", id);
    var f = orderFilters(actor, Map.of());
    f.add("o.id=?", id);
    var rows = db.rows("""
        SELECT o.*,s.name shop_name,p.pay_no,r.status refund_status,
          r.refund_no,r.reason refund_reason
        """ + JOINS + f.where(), f.args());
    if (rows.isEmpty()) {
      throw Problem.denied();
    }
    var result = new LinkedHashMap<>(rows.getFirst());
    result.put("items", db.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY dish_id", id));
    result.put("requests",
        db.rows("SELECT * FROM cancel_request WHERE order_id=? ORDER BY id", id));
    result.put("tasks", db.rows("""
        SELECT t.id,t.status,t.reason,t.assigned_at,t.picked_at,t.ended_at,u.account rider
        FROM delivery_task t JOIN users u ON u.id=t.rider_id WHERE t.order_id=? ORDER BY t.id
        """, id));
    result.put("events", db.rows("""
        SELECT at_time,action,before_state,after_state,reason FROM operation_log
        WHERE order_id=? AND result='OK' ORDER BY id
        """, id));
    return result;
  }

  @GetMapping("/tasks")
  List<Map<String, Object>> tasks(@RequestAttribute Actor actor) {
    actor.require("RIDER");
    return db.rows("""
        SELECT t.id,t.order_id,t.status,t.reason,t.assigned_at,t.picked_at,t.ended_at,
          o.order_no,o.version,s.name shop_name,
          CASE WHEN t.active_order IS NOT NULL THEN s.phone END shop_phone,
          CASE WHEN t.active_order IS NOT NULL THEN o.contact END contact,
          CASE WHEN t.active_order IS NOT NULL THEN o.address END address,
          CASE WHEN t.active_order IS NOT NULL THEN o.phone END phone
        FROM delivery_task t JOIN orders o ON o.id=t.order_id JOIN shop s ON s.id=o.shop_id
        WHERE t.rider_id=? ORDER BY t.id DESC
        """, actor.id());
  }

  @GetMapping("/statistics")
  List<Map<String, Object>> statistics(@RequestAttribute Actor actor,
      @RequestParam Map<String, String> q) {
    if (!actor.role().equals("ADMIN") && !actor.role().equals("MERCHANT")) {
      throw Problem.denied();
    }
    var f = orderFilters(actor, q);
    return db.rows("""
        SELECT o.status,COUNT(*) orders_count,SUM(o.total) receivable,SUM(p.amount) paid,
          SUM(CASE WHEN r.status='REFUNDED' THEN r.amount ELSE 0 END) refunded,
          SUM(CASE WHEN r.status IN ('PENDING','PROCESSING') THEN r.amount ELSE 0 END) pending,
          SUM(CASE WHEN r.status='FAILED' THEN r.amount ELSE 0 END) failed,
          SUM(p.amount)-SUM(CASE WHEN r.status='REFUNDED' THEN r.amount ELSE 0 END) net
        """ + JOINS + f.where() + " GROUP BY o.status", f.args());
  }

  @GetMapping("/logs")
  List<Map<String, Object>> logs(@RequestAttribute Actor actor,
      @RequestParam Map<String, String> q) {
    var f = new Filters();
    switch (actor.role()) {
      case "MERCHANT" -> f.add("(s.owner_id=? OR l.operator_id=" + actor.id() + ")", actor.id());
      case "RIDER" -> f.add("l.operator_id=?", actor.id());
      case "CUSTOMER" -> {
        f.add("o.customer_id=?", actor.id());
        f.add("l.result=?", "OK");
      }
      case "ADMIN" -> {
        // Includes denied and failed operations.
      }
      default -> throw Problem.denied();
    }
    f.optional(q, "operator", "l.operator_id");
    f.optional(q, "order", "l.order_id");
    f.optional(q, "action", "l.action");
    f.range(q, "from", "until", "l.at_time", true);
    String columns = actor.role().equals("CUSTOMER")
        ? "l.order_id,l.at_time,l.action,l.before_state,l.after_state,l.reason" : "l.*";
    return db.rows("SELECT " + columns + """
         FROM operation_log l LEFT JOIN orders o ON o.id=l.order_id
         LEFT JOIN shop s ON s.id=COALESCE(l.shop_id,o.shop_id)
        """ + f.where() + " ORDER BY l.id DESC" + page(q), f.args());
  }
}
