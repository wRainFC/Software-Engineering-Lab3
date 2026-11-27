package cn.edu.lab3.delivery;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class Checkout {
  record Item(long dishId, int quantity, Integer price) {}

  record Command(String requestNo, long addressId, List<Item> items, long confirmedTotal,
      Integer confirmedFee) {}

  record Line(long id, String name, int quantity, long price) {}

  private final Db db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Audit audit;

  Checkout(Db db, TransactionTemplate tx, Clock clock, Audit audit) {
    this.db = db;
    this.tx = tx;
    this.clock = clock;
    this.audit = audit;
  }

  static String digest(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @GetMapping("/requests/{number}")
  ResponseEntity<?> lookup(@RequestAttribute Actor actor, @PathVariable String number) {
    actor.require("CUSTOMER");
    var request = db.one("""
        SELECT lease_until FROM order_request WHERE customer_id=? AND request_no=?
        """, actor.id(), number);
    var order = existing(actor.id(), number);
    if (!order.isEmpty()) {
      return ResponseEntity.ok(order.getFirst());
    }
    boolean busy = Db.num(request, "lease_until") > clock.millis();
    return ResponseEntity.status(busy ? 202 : 200)
        .body(Map.of("state", busy ? "PROCESSING" : "RETRYABLE", "requestNo", number));
  }

  private List<Map<String, Object>> existing(long customer, String number) {
    return db.rows("""
        SELECT id,order_no,status,total FROM orders WHERE customer_id=? AND request_no=?
        """, customer, number);
  }

  @PostMapping
  ResponseEntity<?> submit(@RequestAttribute Actor actor, @RequestBody Command cmd,
      HttpServletRequest http) {
    actor.require("CUSTOMER");
    String number = Problem.requestNo(cmd.requestNo());
    http.setAttribute("auditRequest", number);
    Problem.check(cmd.items() != null && !cmd.items().isEmpty(), "请选择菜品");
    for (var i : cmd.items()) {
      Problem.check(i != null && i.dishId() > 0 && i.quantity() >= 1 && i.quantity() <= 99,
          "菜品或数量无效");
    }
    var items = cmd.items().stream().sorted(Comparator.comparingLong(Item::dishId)).toList();
    Problem.check(items.stream().map(Item::dishId).distinct().count() == items.size(), "菜品重复");
    String hash = digest(cmd.addressId() + "/" + cmd.confirmedTotal() + "/" + cmd.confirmedFee()
        + "/" + items);
    String token = UUID.randomUUID().toString();
    if (!claim(actor.id(), number, hash, token)) {
      return lookup(actor, number);
    }
    try {
      var result = tx.execute(st -> place(actor, cmd, items, token));
      return ResponseEntity.status(201).body(result);
    } catch (RuntimeException failure) {
      db.update("""
          UPDATE order_request SET lease_until=0 WHERE customer_id=? AND request_no=?
          AND owner_token=?
          """, actor.id(), number, token);
      throw failure;
    }
  }

  private boolean claim(long customer, String number, String hash, String token) {
    long now = clock.millis();
    var found = db.rows("SELECT * FROM order_request WHERE customer_id=? AND request_no=?",
        customer, number);
    if (found.isEmpty()) {
      try {
        db.update("""
            INSERT INTO order_request(customer_id,request_no,digest,lease_until,owner_token)
            VALUES(?,?,?,?,?)
            """, customer, number, hash, now + 30000, token);
        return true;
      } catch (DuplicateKeyException seen) {
        found = db.rows("SELECT * FROM order_request WHERE customer_id=? AND request_no=?",
            customer, number);
      }
    }
    var row = found.getFirst();
    if (!hash.equals(row.get("digest"))) {
      throw Problem.conflict("该请求号已用于不同的订单内容");
    }
    if (Db.num(row, "lease_until") > now || !existing(customer, number).isEmpty()) {
      return false;
    }
    return db.update("""
        UPDATE order_request SET owner_token=?,lease_until=? WHERE customer_id=? AND request_no=?
        AND lease_until<=? AND NOT EXISTS
        (SELECT 1 FROM orders WHERE customer_id=? AND request_no=?)
        """, token, now + 30000, customer, number, now, customer, number) == 1;
  }

  private Map<String, Object> place(Actor actor, Command cmd, List<Item> items, String token) {
    db.one("SELECT id FROM users WHERE id=? FOR UPDATE", actor.id());
    var claim = db.one("""
        SELECT owner_token FROM order_request WHERE customer_id=? AND request_no=? FOR UPDATE
        """, actor.id(), cmd.requestNo());
    if (!token.equals(claim.get("owner_token"))) {
      throw Problem.conflict("请求正在恢复，请按原请求号查询");
    }
    var address = db.one("SELECT * FROM address WHERE id=? AND customer_id=? FOR UPDATE",
        cmd.addressId(), actor.id());
    Problem.phone((String) address.get("phone"));
    Problem.field((String) address.get("contact"), 80, "联系人");
    Problem.field((String) address.get("detail"), 240, "地址");
    long shopId = db.number("SELECT COALESCE(MAX(shop_id),0) FROM dish WHERE id=?",
        items.getFirst().dishId());
    var shop = db.one("SELECT * FROM shop WHERE id=? FOR SHARE", shopId);
    Problem.check("OPEN".equals(shop.get("status")), "店铺当前未营业");
    Problem.check(Catalog.covers((String) shop.get("areas"), (String) address.get("region")),
        "地址超出服务区");
    long fee = Db.num(shop, "fee");
    long subtotal = 0;
    boolean changed = cmd.confirmedFee() != null && cmd.confirmedFee() != fee;
    var lines = new ArrayList<Line>();
    for (var item : items) {
      var d = db.one("SELECT * FROM dish WHERE id=? AND shop_id=? FOR UPDATE",
          item.dishId(), shopId);
      Problem.check(Db.flag(d, "shelf") && Db.num(d, "stock") >= item.quantity(),
          d.get("name") + " 已下架或库存不足");
      long price = Db.num(d, "price");
      subtotal += price * item.quantity();
      changed |= item.price() != null && item.price() != price;
      lines.add(new Line(item.dishId(), (String) d.get("name"), item.quantity(), price));
    }
    long total = subtotal + fee;
    if (changed || total != cmd.confirmedTotal()) {
      throw new Problem(409, "报价已变更，请确认新金额", Map.of("code", "REQUOTE",
          "oldTotal", cmd.confirmedTotal(), "total", total, "fee", fee, "items", lines));
    }
    long now = clock.millis();
    long id = db.insert("""
        INSERT INTO orders(order_no,customer_id,shop_id,request_no,status,items_total,fee,total,
          contact,phone,address,created_at,deadline) VALUES(?,?,?,?,'WAITING',?,?,?,?,?,?,?,?)
        """, UUID.randomUUID().toString(), actor.id(), shopId, cmd.requestNo(), subtotal, fee,
        total, address.get("contact"), address.get("phone"),
        address.get("region") + " " + address.get("detail"), now, now + 300000);
    for (Line line : lines) {
      Catalog.changed(db.update("""
          UPDATE dish SET stock=stock-?,held=held+?,version=version+1 WHERE id=? AND stock>=?
          """, line.quantity(), line.quantity(), line.id(), line.quantity()));
      db.update("INSERT INTO order_item(order_id,dish_id,name,price,quantity) VALUES(?,?,?,?,?)",
          id, line.id(), line.name(), line.price(), line.quantity());
      db.update("DELETE FROM cart_item WHERE customer_id=? AND dish_id=? AND quantity=?",
          actor.id(), line.id(), line.quantity());
    }
    db.update("INSERT INTO payment(order_id,pay_no,amount,paid_at) VALUES(?,?,?,?)",
        id, UUID.randomUUID().toString(), total, now);
    db.update("UPDATE order_request SET lease_until=0 WHERE customer_id=? AND request_no=?",
        actor.id(), cmd.requestNo());
    audit.record(actor, "order/" + id, id, shopId, "PLACE", "", "WAITING", "OK", "",
        cmd.requestNo());
    return existing(actor.id(), cmd.requestNo()).getFirst();
  }
}
