package cn.edu.lab3.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class Bootstrap implements ApplicationRunner {
  private final Db db;
  private final BCryptPasswordEncoder passwords;
  private final boolean demo;
  private final String initialPassword;

  Bootstrap(Db db, BCryptPasswordEncoder passwords, @Value("${delivery.demo}") boolean demo,
      @Value("${delivery.bootstrap-password}") String initialPassword) {
    this.db = db;
    this.passwords = passwords;
    this.demo = demo;
    this.initialPassword = initialPassword;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.number("SELECT COUNT(*) FROM users WHERE role='ADMIN'") == 0) {
      account("admin", "ADMIN");
    }
    if (!demo || db.update("INSERT IGNORE INTO bootstrap_marker(name) VALUES('demo-v1')") == 0) {
      return;
    }
    long customer = account("customer", "CUSTOMER");
    account("rider", "RIDER");
    account("rider2", "RIDER");
    db.update("""
        INSERT INTO address(customer_id,contact,phone,region,detail) VALUES(?,?,?,?,?)
        """, customer, "王同学", "13800000001", "仙林校区", "学生宿舍 3 栋门口");
    long merchant = account("merchant", "MERCHANT");
    long shop = shop(merchant, "老街牛肉面", 300);
    dish(shop, "红烧牛肉面", "面食", 2200, 30);
    dish(shop, "番茄鸡蛋面", "面食", 1600, 18);
    dish(shop, "冰豆浆", "饮品", 400, 12);
    dish(shop, "牛肉炒刀削", "面食", 2000, 0);
    dish(shop(merchant, "江南小厨房", 200), "香菇鸡饭", "盖饭", 1800, 25);
  }

  private long account(String name, String role) {
    if (initialPassword.length() < 8) {
      throw new IllegalStateException("首次启动请设置 DELIVERY_INITIAL_PASSWORD（至少 8 位）");
    }
    return db.insert("INSERT INTO users(account,role,password_hash) VALUES(?,?,?)",
        name, role, passwords.encode(initialPassword));
  }

  private long shop(long owner, String name, int fee) {
    return db.insert("""
        INSERT INTO shop(owner_id,name,phone,areas,fee,status) VALUES(?,?,?,?,?,'OPEN')
        """, owner, name, "13800000002", "仙林校区,仙林街道", fee);
  }

  private void dish(long shop, String name, String category, int price, int stock) {
    db.update("INSERT INTO dish(shop_id,name,category,price,stock,shelf) VALUES(?,?,?,?,?,TRUE)",
        shop, name, category, price, stock);
  }
}
