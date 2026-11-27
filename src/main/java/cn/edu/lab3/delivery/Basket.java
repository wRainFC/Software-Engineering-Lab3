package cn.edu.lab3.delivery;

import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class Basket {
  record Address(String contact, String phone, String region, String detail) {}

  record Change(int quantity, boolean replace) {}

  private final Db db;

  Basket(Db db) {
    this.db = db;
  }

  @GetMapping("/addresses")
  List<Map<String, Object>> addresses(@RequestAttribute Actor actor) {
    actor.require("CUSTOMER");
    return db.rows("SELECT * FROM address WHERE customer_id=? ORDER BY id", actor.id());
  }

  @PostMapping({"/addresses", "/addresses/{id}"})
  @Transactional
  void address(@RequestAttribute Actor actor, @PathVariable(required = false) Long id,
      @RequestBody Address input) {
    actor.require("CUSTOMER");
    String contact = Problem.field(input.contact(), 80, "联系人");
    String phone = Problem.phone(input.phone());
    String region = Problem.field(input.region(), 100, "区域");
    String detail = Problem.field(input.detail(), 240, "详细地址");
    if (id == null) {
      db.update("INSERT INTO address(contact,phone,region,detail,customer_id) VALUES(?,?,?,?,?)",
          contact, phone, region, detail, actor.id());
    } else {
      db.one("SELECT id FROM address WHERE id=? AND customer_id=? FOR UPDATE", id, actor.id());
      db.update("UPDATE address SET contact=?,phone=?,region=?,detail=? WHERE id=?",
          contact, phone, region, detail, id);
    }
  }

  @DeleteMapping("/addresses/{id}")
  void removeAddress(@RequestAttribute Actor actor, @PathVariable long id) {
    actor.require("CUSTOMER");
    if (db.update("DELETE FROM address WHERE id=? AND customer_id=?", id, actor.id()) == 0) {
      throw Problem.missing();
    }
  }

  @GetMapping("/cart")
  List<Map<String, Object>> cart(@RequestAttribute Actor actor) {
    actor.require("CUSTOMER");
    return db.rows("""
        SELECT d.id,d.shop_id,d.name,d.price,d.stock,d.shelf,s.fee,s.name shop_name,s.areas,
          s.status shop_status,c.quantity FROM cart_item c JOIN dish d ON d.id=c.dish_id
          JOIN shop s ON s.id=d.shop_id WHERE c.customer_id=? ORDER BY d.id
        """, actor.id());
  }

  @GetMapping("/cart/similar")
  List<Map<String, Object>> similar(@RequestAttribute Actor actor) {
    actor.require("CUSTOMER");
    return db.rows("""
        SELECT o.id,o.order_no FROM orders o WHERE o.customer_id=?
        AND o.status NOT IN ('COMPLETED','CANCELLED')
        AND EXISTS (SELECT 1 FROM cart_item WHERE customer_id=o.customer_id)
        AND NOT EXISTS (SELECT 1 FROM cart_item c WHERE c.customer_id=o.customer_id
          AND NOT EXISTS (SELECT 1 FROM order_item i WHERE i.order_id=o.id
            AND i.dish_id=c.dish_id AND i.quantity=c.quantity))
        AND NOT EXISTS (SELECT 1 FROM order_item i WHERE i.order_id=o.id
          AND NOT EXISTS (SELECT 1 FROM cart_item c WHERE c.customer_id=o.customer_id
            AND c.dish_id=i.dish_id AND c.quantity=i.quantity))
        ORDER BY o.id DESC LIMIT 1
        """, actor.id());
  }

  @PostMapping("/cart/{dishId}")
  @Transactional
  void set(@RequestAttribute Actor actor, @PathVariable long dishId, @RequestBody Change input) {
    actor.require("CUSTOMER");
    Problem.check(input.quantity() >= 0 && input.quantity() <= 99, "数量须为 1 至 99，移除时传 0");
    db.one("SELECT id FROM users WHERE id=? FOR UPDATE", actor.id());
    if (input.quantity() == 0) {
      db.update("DELETE FROM cart_item WHERE dish_id=? AND customer_id=?", dishId, actor.id());
      return;
    }
    var dish = db.one(Catalog.DISHES + " WHERE d.id=?", dishId);
    Problem.check(Db.flag(dish, "sellable"), "菜品当前不可售");
    if (db.number("""
        SELECT COUNT(*) FROM cart_item c JOIN dish d ON d.id=c.dish_id
        WHERE c.customer_id=? AND d.shop_id<>?
        """, actor.id(), dish.get("shop_id")) > 0) {
      if (!input.replace()) {
        throw new Problem(409, "换店将清空原购物车", Map.of("code", "SWITCH_SHOP"));
      }
      db.update("DELETE FROM cart_item WHERE customer_id=?", actor.id());
    }
    db.update("""
        INSERT INTO cart_item(customer_id,dish_id,quantity) VALUES(?,?,?)
        ON DUPLICATE KEY UPDATE quantity=?
        """, actor.id(), dishId, input.quantity(), input.quantity());
  }
}
