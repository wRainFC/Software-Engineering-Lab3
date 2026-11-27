# 速达外卖点餐与配送系统

速达是软件工程实验三的课程项目，将实验二的 UML 设计实现为一个可在浏览器中操作的外卖系统。顾客、商家、管理员和配送员可以接力完成同一笔订单，也可以重现商家超时、骑手改派和退款失败，观察每一步如何影响订单、库存和模拟资金。

## 快速开始

在 Linux 或 WSL 终端中准备好 Git、Docker Engine、Docker Compose、curl 和 OpenSSL。JDK 21、Maven 和 MySQL 8.4 由容器提供。首次构建需要联网下载镜像和 Maven 依赖。

```sh
git clone https://github.com/wRainFC/Software-Engineering-Lab3.git
cd Software-Engineering-Lab3
./dev-up
```

已有本地副本时，在包含 `compose.yaml` 的目录执行 `./dev-up` 即可。终端显示 `UP` 后，打开 [本地登录页](http://127.0.0.1:8080/)。首次登录使用账号 `customer`，密码见仓库根目录 `.env` 中的 `DELIVERY_INITIAL_PASSWORD`。该密码由启动脚本随机生成，供下列演示账号共用。

## 走完一笔订单

首次启动会创建两家店铺、示例菜品和收货地址。按下表依次退出并切换账号，或在独立浏览器配置中分别登录。每次交接时点击页面上的“刷新”，即可看到上一位操作者提交的结果。

| 步骤 | 登录账号 | 页面操作 | 预期订单状态 |
| --- | --- | --- | --- |
| 1 | `customer` | 在“老街牛肉面”选一份红烧牛肉面，去结算，选择仙林校区地址并提交。初始菜价 22 元，配送费 3 元，合计 25 元。 | 待接单 |
| 2 | `merchant` | 在“订单履约看板”打开该单，5 分钟内接单，再点击“开始制作”和“备餐完成”。 | 待配送 |
| 3 | `admin` | 在“配送与退款”打开该单，将配送员指定为 `rider`。 | 待配送，已分配骑手 |
| 4 | `rider` | 在“我的配送任务”中依次确认取餐和送达。 | 已送达 |
| 5 | `customer` | 在“我的订单”打开该单，点击“确认收货”，查看成交明细和履约时间线。 | 已完成 |

同一组账号也能验证以下异常流程。

- **超时与取消**　待接单时顾客可直接取消，超过 5 分钟仍未接单会自动取消。商家接单后、开始制作前，顾客可申请取消，由商家审核。制作前取消会回补库存并创建全额模拟退款。
- **配送异常**　让 `rider` 取餐后上报异常，再由 `admin` 恢复配送并改派给 `rider2`。新骑手接手，订单详情保留两次任务记录。
- **退款重试**　用 `DELIVERY_REFUND_FAIL_FIRST=true ./dev-up` 启用模拟故障，再取消一笔新订单。管理员可以在首次退款失败后沿用原退款号重试，并在“统计与日志”核对结果。

## 开发与验收

后端使用 Spring Boot 和 Spring JDBC，Flyway 管理数据库迁移。阅读代码时，可以从实验二的设计图直接找到实现入口。

| 要了解的行为 | 代码入口 |
| --- | --- |
| 下单时校验报价、处理重复请求，并在同一事务中扣库存、记付款 | [下单服务](src/main/java/cn/edu/lab3/delivery/Checkout.java) |
| 按角色和当前状态处理接单、取消、配送与收货 | [订单状态机](src/main/java/cn/edu/lab3/delivery/OrderFlow.java) |
| 扫描超时订单、推进模拟退款 | [定时任务](src/main/java/cn/edu/lab3/delivery/Timers.java) |
| 查询实体关系、唯一约束和索引 | [MySQL 建表迁移](src/main/resources/db/migration/V1__delivery.sql) |
| 查看四个角色的页面和 API 调用 | [Web 前端](src/main/resources/static/) |

<details>
<summary>直接调用 JSON API</summary>

用 `POST /api/auth/login` 提交 `account` 和 `password`，后续请求沿用登录会话的 Cookie。写请求带上 `X-Requested-With: delivery`，JSON 请求体使用 `Content-Type: application/json`。金额单位为分。

| 接口 | 调用约定 |
| --- | --- |
| `POST /api/orders` | 提交 `requestNo`、`addressId`、`items`、`confirmedFee` 和 `confirmedTotal`。每项菜品带 `dishId`、`quantity`、`price`。新订单返回 201，同内容重放返回 200，处理中返回 202，请求号冲突或需要重新报价时返回 409。 |
| `GET /api/orders/requests/{requestNo}` | 下单断线后用原请求号查询结果。顾客确认新报价后，用新请求号重新提交。 |
| `POST /api/orders/{id}/actions` | 提交动作 `action`、当前订单版本 `version` 和本次操作的 `requestNo`。分配配送员时传 `riderId`，骑手操作时传 `taskId`。 |
| `GET /api/statistics`、`GET /api/logs` | 按角色查看资金汇总和审计记录。时间参数为 Unix 毫秒，查询区间包含起点、排除终点。净额等于模拟实收减去已成功退款的金额。 |

</details>

使用 Python 3 和 Docker，在仓库根目录运行完整验收。

```sh
./ops/verify
```

验收脚本创建独立的 Compose 项目和临时数据库。构建阶段执行 Maven 单元测试和 Google Checkstyle，随后通过 HTTP 和真实 MySQL 验证 20 路并发下单、付款失败回滚、权限隔离、配送重派、退款重试及重启后数据一致性。最后将备份导入独立数据库，校验订单、明细、库存和资金记录。[验收用例](ops/acceptance.py) · [恢复校验 SQL](ops/integrity.sql)

## 本地运行与备份

| 需要做什么 | 命令或设置 |
| --- | --- |
| 更换访问端口 | `APP_PORT=8081 ./dev-up` |
| 在空数据库中仅预置管理员 | 首次运行 `DELIVERY_DEMO=false ./dev-up` |
| 检查启动状态 | `docker compose ps`，或打开[健康检查](http://127.0.0.1:8080/actuator/health) |
| 排查启动失败 | 先用 `docker info` 确认守护进程可访问，再运行 `docker compose logs app db` |
| 停止服务并保留订单 | `docker compose stop`，下次用 `./dev-up` 启动 |

运行中的备份服务每 24 小时将一致性 SQL 备份写入 `backups/`。也可以手动生成备份，并验证最近一次备份能否恢复。

```sh
docker compose exec -T backup sh /ops/backup.sh once
./ops/restore-check "$(ls -t backups/delivery-*.sql | head -1)"
```

恢复脚本会创建并保留名为 `delivery_restore_*` 的独立数据库，成功时输出库名。日常运行请配套保留 `.env` 和 `mysql-data` 数据卷。

## 帮助与维护

遇到问题时，请附上所用角色、复现步骤、预期结果和实际结果。启动问题可附相关容器日志片段，请隐去密码与个人信息。[提交问题](https://github.com/wRainFC/Software-Engineering-Lab3/issues) · [仓库维护者 @wRainFC](https://github.com/wRainFC)
