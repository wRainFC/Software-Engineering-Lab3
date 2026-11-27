package cn.edu.lab3.delivery;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class Accounts {
  record Credentials(String account, String password, String role) {}

  record Access(boolean enabled, String reason) {}

  private final Db db;
  private final BCryptPasswordEncoder passwords;
  private final Audit audit;

  Accounts(Db db, BCryptPasswordEncoder passwords, Audit audit) {
    this.db = db;
    this.passwords = passwords;
    this.audit = audit;
  }

  @PostMapping("/auth/login")
  Actor login(@RequestBody Credentials input, HttpServletRequest request) {
    var rows = db.rows("SELECT * FROM users WHERE account=?", input.account());
    if (rows.isEmpty() || input.password() == null
        || !passwords.matches(input.password(), (String) rows.getFirst().get("password_hash"))) {
      throw new Problem(401, "账号或密码错误");
    }
    var actor = Actor.from(rows.getFirst());
    request.setAttribute("actor", actor);
    if (!Db.flag(rows.getFirst(), "enabled")) {
      throw new Problem(403, "账号已停用");
    }
    if (request.getSession(false) != null) {
      request.getSession(false).invalidate();
    }
    request.getSession(true).setAttribute("userId", actor.id());
    return actor;
  }

  @PostMapping("/auth/register")
  @Transactional
  Map<String, Long> register(@RequestBody Credentials input) {
    return create(null, input, "CUSTOMER");
  }

  @PostMapping("/admin/users")
  @Transactional
  Map<String, Long> provision(@RequestAttribute Actor actor, @RequestBody Credentials input) {
    actor.require("ADMIN");
    Problem.check("MERCHANT".equals(input.role()) || "RIDER".equals(input.role()), "角色无效");
    return create(actor, input, input.role());
  }

  private Map<String, Long> create(Actor actor, Credentials input, String role) {
    String account = Problem.field(input.account(), 64, "账号");
    String password = input.password();
    Problem.check(password != null && password.length() >= 8
        && password.getBytes(StandardCharsets.UTF_8).length <= 72, "密码至少 8 位，最多 72 字节");
    long id = db.insert("INSERT INTO users(account,password_hash,role) VALUES(?,?,?)",
        account, passwords.encode(password), role);
    audit.record(actor, "user/" + id, null, null, "CREATE_ACCOUNT", "", role, "OK", "", "");
    return Map.of("id", id);
  }

  @GetMapping("/me")
  Actor me(@RequestAttribute Actor actor) {
    return actor;
  }

  @PostMapping("/auth/logout")
  void logout(HttpServletRequest request) {
    request.getSession(false).invalidate();
  }

  @GetMapping("/admin/users")
  List<Map<String, Object>> users(@RequestAttribute Actor actor) {
    actor.require("ADMIN");
    return db.rows("SELECT id,account,role,enabled FROM users ORDER BY id");
  }

  @PostMapping("/admin/users/{id}/access")
  @Transactional
  void access(@RequestAttribute Actor actor, @PathVariable long id, @RequestBody Access input) {
    actor.require("ADMIN");
    Problem.check(id != actor.id() || input.enabled(), "不能停用当前管理员");
    var old = db.one("SELECT enabled FROM users WHERE id=? FOR UPDATE", id);
    String reason = input.enabled() ? "" : Problem.field(input.reason(), 240, "停用原因");
    db.update("UPDATE users SET enabled=? WHERE id=?", input.enabled(), id);
    audit.record(actor, "user/" + id, null, null, "ACCOUNT_ACCESS", old.get("enabled").toString(),
        String.valueOf(input.enabled()), "OK", reason, "");
  }
}
