package cn.edu.lab3.delivery;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
class Catalog {
  record ShopInput(String name, String phone, String areas, int fee, String status, long version) {}

  record DishInput(String name, String category, int price, int stock, boolean shelf,
      long version) {}

  record Note(String reason) {}

  static final String DISHES = """
      SELECT d.*,s.name shop_name,s.status shop_status,s.fee,
        (d.stock>0 AND d.shelf AND s.status='OPEN') sellable
      FROM dish d JOIN shop s ON s.id=d.shop_id
      """;
  private final Db db;
  private final Audit audit;

  Catalog(Db db, Audit audit) {
    this.db = db;
    this.audit = audit;
  }

  @GetMapping("/shops")
  List<Map<String, Object>> shops(@RequestAttribute Actor actor) {
    if (actor.role().equals("MERCHANT")) {
      return db.rows("SELECT * FROM shop WHERE owner_id=? ORDER BY id", actor.id());
    }
    return db.rows("SELECT * FROM shop ORDER BY id");
  }

  @GetMapping("/dishes")
  List<Map<String, Object>> dishes(@RequestAttribute Actor actor,
      @RequestParam Map<String, String> q) {
    var f = new Filters();
    if (actor.role().equals("MERCHANT")) {
      f.add("s.owner_id=?", actor.id());
    }
    f.optional(q, "shop", "s.id");
    f.optional(q, "category", "d.category");
    f.optional(q, "status", "s.status");
    f.range(q, "min", "max", "d.price", false);
    if (!q.getOrDefault("keyword", "").isBlank()) {
      f.add("LOCATE(?,d.name)>0", q.get("keyword"));
    }
    if (!q.getOrDefault("sellable", "").isBlank()) {
      f.add("(d.stock>0 AND d.shelf AND s.status='OPEN')=?",
          Boolean.parseBoolean(q.get("sellable")));
    }
    return db.rows(DISHES + f.where() + " ORDER BY d.id", f.args());
  }

  private Map<String, Object> own(Actor actor, long id) {
    actor.require("MERCHANT");
    var shop = db.one("SELECT * FROM shop WHERE id=? FOR UPDATE", id);
    if (Db.num(shop, "owner_id") != actor.id()) {
      throw Problem.denied();
    }
    return shop;
  }

  @PostMapping({"/shops", "/shops/{id}"})
  @Transactional
  Map<String, Long> saveShop(@RequestAttribute Actor actor, @PathVariable(required = false) Long id,
      @RequestBody ShopInput input) {
    actor.require("MERCHANT");
    String name = Problem.field(input.name(), 100, "店名");
    String phone = Problem.phone(input.phone());
    String areas = Problem.field(input.areas(), 500, "服务区");
    Problem.check(Arrays.stream(areas.split("[,，、]", -1)).noneMatch(String::isBlank), "服务区不能为空");
    Problem.check(input.fee() >= 0 && ("OPEN".equals(input.status())
        || "PAUSED".equals(input.status())), "配送费或营业状态无效");
    String old = "";
    if (id == null) {
      id = db.insert("INSERT INTO shop(owner_id,name,phone,areas,fee,status) VALUES(?,?,?,?,?,?)",
          actor.id(), name, phone, areas, input.fee(), input.status());
    } else {
      var shop = own(actor, id);
      old = shop.toString();
      if ("CLOSED".equals(shop.get("status"))) {
        throw Problem.conflict("平台已关闭此店铺");
      }
      changed(db.update("""
          UPDATE shop SET name=?,phone=?,areas=?,fee=?,status=?,version=version+1
          WHERE id=? AND version=?
          """, name, phone, areas, input.fee(), input.status(), id, input.version()));
    }
    audit.record(actor, "shop/" + id, null, id, "SAVE_SHOP", old, input.toString(), "OK", "", "");
    return Map.of("id", id);
  }

  @PostMapping("/shops/{id}/close")
  @Transactional
  void close(@RequestAttribute Actor actor, @PathVariable long id, @RequestBody Note note) {
    actor.require("ADMIN");
    String reason = Problem.field(note.reason(), 240, "关店原因");
    var old = db.one("SELECT status FROM shop WHERE id=? FOR UPDATE", id);
    db.update("UPDATE shop SET status='CLOSED',reason=?,version=version+1 WHERE id=?", reason, id);
    audit.record(actor, "shop/" + id, null, id, "CLOSE", (String) old.get("status"), "CLOSED",
        "OK", reason, "");
  }

  @PostMapping({"/shops/{shop}/dishes", "/shops/{shop}/dishes/{id}"})
  @Transactional
  Map<String, Long> saveDish(@RequestAttribute Actor actor, @PathVariable long shop,
      @PathVariable(required = false) Long id, @RequestBody DishInput input) {
    own(actor, shop);
    String name = Problem.field(input.name(), 100, "菜名");
    String category = Problem.field(input.category(), 80, "分类");
    Problem.check(input.price() >= 1 && input.price() <= 99999, "单价范围为 1 至 99999 分");
    Problem.check(input.stock() >= 0 && input.stock() <= 9999, "库存范围为 0 至 9999");
    String old = "";
    if (id == null) {
      id = db.insert(
          "INSERT INTO dish(shop_id,name,category,price,stock,shelf) VALUES(?,?,?,?,?,?)",
          shop, name, category, input.price(), input.stock(), input.shelf());
    } else {
      var dish = db.one("SELECT * FROM dish WHERE id=? AND shop_id=? FOR UPDATE", id, shop);
      old = dish.toString();
      Problem.check(Db.num(dish, "held") + input.stock() <= 9999, "请为制作前订单的库存回补预留容量");
      changed(db.update("""
          UPDATE dish SET name=?,category=?,price=?,stock=?,shelf=?,version=version+1
          WHERE id=? AND version=?
          """, name, category, input.price(), input.stock(), input.shelf(), id, input.version()));
    }
    audit.record(actor, "dish/" + id, null, shop, "SAVE_DISH", old, input.toString(), "OK", "", "");
    return Map.of("id", id);
  }

  static void changed(int count) {
    if (count != 1) {
      throw Problem.conflict("数据已变更，请刷新后重试");
    }
  }

  static boolean covers(String areas, String region) {
    return Arrays.stream(areas.split("[,，、]")).map(String::strip).anyMatch(region::equals);
  }
}
