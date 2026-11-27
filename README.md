# 实验三 · 速达外卖点餐与配送系统

Java 21、Spring Boot、Spring JDBC 和 MySQL 8.4 实现实验二的七张 UML 图。应用在同一地址提供中文 Web 界面和 JSON API。顾客、商家、配送员、管理员共用一个订单状态机。

## 启动与演示

在 WSL 的 `lab3` 目录执行以下命令。需要 Docker Engine、Compose、curl 和 OpenSSL，首次构建会下载镜像与 Maven 依赖。

```sh
./dev-up
```

浏览器打开 [速达外卖](http://127.0.0.1:8080/)。需要更换端口时使用 `APP_PORT=8081 ./dev-up`。脚本为本机生成随机数据库凭据与初始登录密码，保存在权限为 600 的 `.env` 中。五个本地演示账号首次登录均使用其中的 `DELIVERY_INITIAL_PASSWORD`。数据库卷保留订单和交易记录，重复启动沿用已有数据。首次启动时设置 `DELIVERY_DEMO=false` 可只预置管理员。

| 账号 | 浏览器演示步骤 |
| --- | --- |
| `customer` | 在“老街牛肉面”加购，进入购物车，选择预置的仙林校区地址并提交。记下订单号。也可以自行注册顾客并添加地址。 |
| `merchant` | 在订单履约看板打开该订单，依次接单、开始制作、备餐完成。店铺与菜品页可修改配送费、库存和上下架状态。 |
| `admin` | 在配送与退款页打开待配送订单，分配给 `rider`。账号与店铺页可开通骑手和商家、停用账号、登记关店原因。 |
| `rider` | 在我的配送任务中取餐、送达。演示异常时，取餐后填写异常原因，管理员恢复待配送并改派给 `rider2`，由新骑手继续取送。 |
| `customer` | 在我的订单中查看成交快照和履约时间线，点击确认收货。商家和管理员可核对资金统计与审计日志。 |

可以在一个浏览器中依次退出并切换账号，也可以用独立浏览器配置同时登录。点击“刷新”读取其他角色刚提交的结果。

待接单可直接取消。已接单要由顾客申请、商家审核。制作前取消回补库存，制作后由管理员按履约失败原因取消。模拟退款由后台推进，取消页面显示独立退款状态。用 `DELIVERY_REFUND_FAIL_FIRST=true ./dev-up` 可演示首次模拟退款失败，管理员沿用原退款号重试。

## 代码与接口

- `src/main/resources/db/migration` 保存 Flyway 迁移。唯一索引约束请求号、付款、退款、有效任务和待处理取消申请。各店服务区以逗号或顿号分隔，和地址区域逐项匹配。金额单位为分。
- `src/main/java/cn/edu/lab3/delivery` 中的 `Checkout` 对应下单活动图和时序图，`OrderFlow` 定义状态图中的合法转换与并发保护，`Timers` 运行超时取消与模拟退款。JDBC 事务将状态、库存、资金、配送任务和成功审计一起提交。
- `src/main/resources/static` 提供四角色页面。接口使用会话 Cookie，写请求附带 `X-Requested-With: delivery`，JSON 请求体使用 `application/json`。身份、角色、店铺及订单归属由服务端校验。

| 接口 | 用途 |
| --- | --- |
| `POST /api/auth/register`、`POST /api/auth/login`、`GET /api/me` | 顾客注册、四角色登录、读取当前身份。登录字段为 `account`、`password`。 |
| `/api/shops`、`/api/dishes`、`/api/cart`、`/api/addresses` | 浏览、维护店铺菜品，管理单店购物车和顾客地址。 |
| `POST /api/orders` | 提交 `requestNo`、`addressId`、`items[{dishId,quantity,price}]`、`confirmedFee`、`confirmedTotal`。首次成功为 201，同内容重放为 200，处理中为 202，异内容复用请求号及重新报价为 409。 |
| `GET /api/orders/requests/{requestNo}` | 网络中断后按原请求查询，租约到期且无成交结果时允许同号原样重试。新报价经确认后使用新号。 |
| `GET /api/orders`、`GET /api/orders/{id}` | 授权范围内的组合查询、成交快照、取消申请、任务及时间线。 |
| `POST /api/orders/{id}/actions` | 提交 `action`、页面读到的 `version`、本次操作的 `requestNo`。分配携带 `riderId`，骑手操作携带 `taskId`，拒单、异常、终止履约等操作携带 `reason`。 |
| `GET /api/tasks`、`GET /api/statistics`、`GET /api/logs` | 本人配送任务、按订单状态分组的资金汇总、授权审计记录。订单与日志列表每页 50 条，通过 `offset` 翻页。 |

统计使用下单时间，北京时间区间左闭右开，API 时间值为 Unix 毫秒。净额为模拟实收减成功退款。待退款和退款中计入待完成退款，退款失败单列。日志和成交快照持久保留。

## 验证与备份

```sh
./ops/verify
```

验证脚本需要 Python 3，在独立 Compose 项目中使用随机密码、临时端口、数据库卷与备份目录。Docker 构建执行 `mvn clean verify`，包含可控时钟边界测试和 Google Checkstyle。随后通过真实 HTTP 和 MySQL 执行付款写入失败回滚、20 路并发、取消审核、旧骑手操作、退款重试、权限隔离、统计核账与应用重启测试。最后将备份导入独立数据库并核对一致性，退出时清理本次测试项目。

运行中的 `backup` 服务每 24 小时生成一次一致性 SQL 备份，失败后一分钟重试。文件位于 `backups/`。手动备份与恢复校验可用以下命令。

```sh
docker compose exec -T backup sh /ops/backup.sh once
./ops/restore-check "$(ls -t backups/delivery-*.sql | head -1)"
```

恢复脚本创建并保留独立的 `delivery_restore_*` 数据库，输出库名并核对订单、明细、库存、付款、退款及任务。运行数据位于 Compose 的 `mysql-data` 卷。保留 `.env`、数据卷及备份文件，日常停止服务使用 `docker compose stop`。健康检查入口为 [/actuator/health](http://127.0.0.1:8080/actuator/health)。
