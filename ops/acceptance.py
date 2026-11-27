"""HTTP and MySQL integration checks for the disposable project created by ops/verify."""

import concurrent.futures
import http.cookiejar
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BASE = os.environ["DELIVERY_TEST_BASE"] + "/api"
PASSWORD = os.environ["DELIVERY_INITIAL_PASSWORD"]


def sql(statement):
    process = subprocess.run(
        ["docker", "compose", "exec", "-T", "db", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B delivery'],
        input=statement, text=True, capture_output=True, check=True,
    )
    return process.stdout.strip()


class Client:
    def __init__(self, name=None, base=BASE):
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if name:
            self.call("/auth/login", {"account": name, "password": PASSWORD})

    def call(self, path, data=None, *, expect=(200,), method=None):
        req = urllib.request.Request(
            self.base + path, None if data is None else json.dumps(data).encode(),
            method=method, headers={"Content-Type": "application/json", "X-Requested-With": "delivery"})
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            body = json.loads(raw) if raw else None
            assert response.status in expect, (path, response.status, body)
            return body


def parallel(fn, count=20):
    with concurrent.futures.ThreadPoolExecutor(max_workers=count) as pool:
        return list(pool.map(fn, range(count)))


def eventually(fn, seconds=30):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = fn()
        if result:
            return result
        time.sleep(0.25)
    raise AssertionError("timed out waiting for persisted state")


def main():
    assert os.environ["COMPOSE_PROJECT_NAME"].startswith("delivery-test-")
    customer, merchant, admin, rider, second = map(Client, ["customer", "merchant", "admin", "rider", "rider2"])
    guest = Client()
    guest.call("/me", expect=(401,))
    guest.call("/auth/register", {"account": "new_customer", "password": PASSWORD, "role": "ADMIN"})
    other = Client("new_customer")
    assert other.call("/me")["role"] == "CUSTOMER"
    other.call("/admin/users", expect=(403,))
    for path in ["/shops", "/dishes", "/orders", "/statistics", "/admin/users"]:
        rider.call(path, expect=(403,))
    shops = merchant.call("/shops")
    shop = shops[0]
    dish, another = merchant.call("/dishes?shop=" + str(shop["id"]))[:2]
    address = customer.call("/addresses")[0]
    users = {u["account"]: u["id"] for u in admin.call("/admin/users")}

    def command(item=dish, buyer=address):
        return {"requestNo": uuid.uuid4().hex, "addressId": buyer["id"],
                "items": [{"dishId": item["id"], "quantity": 1, "price": item["price"]}],
                "confirmedFee": shop["fee"], "confirmedTotal": item["price"] + shop["fee"]}

    def place(item=dish):
        return customer.call("/orders", command(item), expect=(201,))

    def detail(order):
        return customer.call("/orders/" + str(order["id"]))

    def action(who, order, name, *, expect=(200,), version=None, **extra):
        if version is None:
            version = detail(order)["version"]
        data = {"requestNo": uuid.uuid4().hex, "action": name, "version": version, **extra}
        return who.call(f"/orders/{order['id']}/actions", data, expect=expect)

    customer.call(f"/cart/{dish['id']}", {"quantity": 1, "replace": False})
    foreign_dish = merchant.call("/dishes?shop=" + str(shops[1]["id"]))[0]
    assert customer.call(f"/cart/{foreign_dish['id']}", {"quantity": 1, "replace": False},
                         expect=(409,))["code"] == "SWITCH_SHOP"
    assert customer.call("/cart")[0]["id"] == dish["id"]
    changed = command()
    changed["confirmedTotal"] -= 1
    assert customer.call("/orders", changed, expect=(409,))["code"] == "REQUOTE"

    # Payment failure comes after stock deduction and line creation. The transaction must unwind both.
    broken = command()
    sql("CREATE TRIGGER payment_fault BEFORE INSERT ON payment FOR EACH ROW "
        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected payment failure'")
    try:
        customer.call("/orders", broken, expect=(503,))
        assert sql(f"SELECT stock FROM dish WHERE id={dish['id']}") == str(dish["stock"])
        assert sql("SELECT COUNT(*) FROM orders") == "0"
    finally:
        sql("DROP TRIGGER payment_fault")
    order = customer.call("/orders", broken, expect=(201,))
    repeated = parallel(lambda _: customer.call("/orders", broken))
    assert all(r["id"] == order["id"] for r in repeated)
    customer.call("/orders", {**broken, "confirmedTotal": 1}, expect=(409,))
    assert customer.call("/orders/requests/" + broken["requestNo"])["id"] == order["id"]
    other.call("/orders/" + str(order["id"]), expect=(403,))
    assert not other.call("/orders")
    action(merchant, order, "READY", expect=(409,))
    action(merchant, order, "ACCEPT")
    action(customer, order, "REQUEST_CANCEL", reason="地址有误")
    action(merchant, order, "COOK")
    assert detail(order)["requests"][0]["status"] == "EXPIRED"
    action(merchant, order, "READY")
    action(admin, order, "ASSIGN", riderId=users["rider"])
    task = rider.call("/tasks")[0]
    action(second, order, "PICK", taskId=task["id"], expect=(403,))
    action(rider, order, "PICK", taskId=task["id"])
    action(rider, order, "EXCEPTION", taskId=task["id"], reason="无法联系收件人")
    action(admin, order, "RESTORE")
    action(admin, order, "ASSIGN", riderId=users["rider2"])
    replacement = second.call("/tasks")[0]
    action(second, order, "PICK", taskId=replacement["id"])
    action(rider, order, "DELIVER", taskId=task["id"], expect=(409,))
    action(second, order, "DELIVER", taskId=replacement["id"])
    action(customer, order, "CONFIRM")
    assert detail(order)["status"] == "COMPLETED"
    assert len(detail(order)["tasks"]) == 2
    action(customer, order, "CANCEL", expect=(409,))

    # Simultaneous first submissions of the same key produce one payment.
    same = command()
    many = parallel(lambda _: customer.call("/orders", same, expect=(200, 201, 202)))
    ids = {r["id"] for r in many if "id" in r}
    assert len(ids) == 1
    cancelled = {"id": ids.pop()}
    change = {"version": 0, "requestNo": uuid.uuid4().hex, "action": "CANCEL"}
    parallel(lambda _: customer.call(f"/orders/{cancelled['id']}/actions", change), 10)
    assert detail(cancelled)["status"] == "CANCELLED"
    eventually(lambda: detail(cancelled)["refund_status"] == "FAILED")
    refund_no = detail(cancelled)["refund_no"]
    action(admin, cancelled, "RETRY_REFUND")
    eventually(lambda: detail(cancelled)["refund_status"] == "REFUNDED")
    assert detail(cancelled)["refund_no"] == refund_no

    merchant.call(f"/shops/{shop['id']}/dishes/{another['id']}", {**another, "stock": 1})
    winners = parallel(lambda _: customer.call("/orders", command(another), expect=(201, 400)))
    assert sum("id" in row for row in winners) == 1
    last = next(row for row in winners if "id" in row)
    race = parallel(lambda i: action(merchant if i else customer, last, "ACCEPT" if i else "CANCEL",
                                     version=0, expect=(200, 409)), 2)
    assert sum("message" not in row for row in race) == 1
    if detail(last)["status"] == "ACCEPTED":
        action(customer, last, "REQUEST_CANCEL", reason="更改地址")
        action(merchant, last, "APPROVE")
    assert sql(f"SELECT stock FROM dish WHERE id={another['id']}") == "1"

    expiry = place()
    sql(f"UPDATE orders SET deadline=1 WHERE id={expiry['id']}")
    action(merchant, expiry, "ACCEPT", expect=(409,))
    eventually(lambda: detail(expiry)["status"] == "CANCELLED")
    abort = place()
    action(merchant, abort, "ACCEPT")
    action(merchant, abort, "COOK")
    before = sql(f"SELECT stock FROM dish WHERE id={dish['id']}")
    action(admin, abort, "ABORT", reason="设备故障")
    assert sql(f"SELECT stock FROM dish WHERE id={dish['id']}") == before

    customer.call(f"/addresses/{address['id']}", {**address, "detail": "新地址"})
    assert "新地址" not in detail(order)["address"]
    stats = admin.call("/statistics")
    assert sum(int(g["orders_count"]) for g in stats) == int(sql("SELECT COUNT(*) FROM orders"))
    assert sum(int(g["net"]) for g in stats) == int(sql(
        "SELECT SUM(amount)-(SELECT COALESCE(SUM(amount),0) FROM refund WHERE status='REFUNDED') FROM payment"))
    assert admin.call("/logs?order=" + str(order["id"]))
    admin.call(f"/admin/users/{users['rider']}/access", {"enabled": False, "reason": "测试停用"})
    rider.call("/tasks", expect=(403,))
    assert sql(Path("ops/integrity.sql").read_text()) == "0"

    subprocess.run(["docker", "compose", "restart", "app"], check=True)
    # Docker may reassign the ephemeral host port when the container restarts.
    address_after_restart = subprocess.check_output(
        ["docker", "compose", "port", "app", "8080"], text=True).strip()
    restored_api = f"http://{address_after_restart}/api"

    def recovered():
        try:
            return Client("customer", restored_api).call("/orders/" + str(order["id"]))["status"] == "COMPLETED"
        except (OSError, AssertionError):
            return False
    eventually(recovered, 90)
    assert sql(Path("ops/integrity.sql").read_text()) == "0"
    print("通过：权限、购物车、报价、下单回滚与并发、九态履约、配送重派、退款、统计及重启一致性")


if __name__ == "__main__":
    main()
