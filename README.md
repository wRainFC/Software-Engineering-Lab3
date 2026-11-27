# 实验三开发环境

在 WSL 的 lab3 目录执行以下命令。需要 Docker Engine、Compose、curl 和 OpenSSL，首次构建需要能下载容器镜像与 Maven 依赖。

```sh
./dev-up
```

脚本为本地数据库生成随机凭据，保存在权限为 600 的 .env 中。重复运行沿用同一凭据和数据卷。请将 .env 与本机数据卷配套保留。MySQL 8.4 初始化后，应用容器连接 delivery 数据库。健康检查成功时终端显示 UP 和访问地址，默认地址为 http://127.0.0.1:8080/actuator/health 。需要换端口时可用 APP_PORT=8081 ./dev-up。

## 开发与构建

Java 源码位于 src/main/java，资源位于 src/main/resources，后续测试可放在 src/test/java。Dockerfile 使用 JDK 21 和 Maven 3.9.11 执行 clean verify，包括编译、运行已有测试、打包及 Google Checkstyle 检查。修改代码后再运行 ./dev-up 即可重新构建。当前启动类用于验证环境，尚无业务接口。

```sh
docker compose ps
docker compose logs app db
docker compose exec db mysql -u delivery -p delivery
docker compose stop
```

MySQL 客户端提示输入口令时使用 .env 的 MYSQL_PASSWORD。数据库仅在 Compose 网络内提供服务。数据库与应用均可用时，Actuator 健康端点应返回 HTTP 200。

若终端提示无法连接 Docker 守护进程，先在同一 WSL 终端运行 docker info。使用 Docker Desktop 时，确认已启动并为当前 WSL 发行版启用集成；使用 WSL 内的 Docker Engine 时，检查服务状态和当前账号的访问权限。
