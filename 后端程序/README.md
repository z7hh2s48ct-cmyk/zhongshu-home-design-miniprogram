# 众墅之家设计平台后端

众墅之家设计小程序和管理后台的 Java 后端，基于 RuoYi-Vue-Pro JDK 17 维护线扩展，采用 Spring Boot、PostgreSQL、Flyway 和 Redis。

## 实际构建面

父 POM 当前启用以下模块：

- `yudao-dependencies`
- `yudao-framework`
- `yudao-server`
- `yudao-module-system`
- `yudao-module-infra`
- `yudao-module-identity`
- `yudao-module-design`
- `yudao-module-commerce`
- `yudao-module-ai-orchestration`

2026-09-09 已移出 16 个未启用的上游业务模块及后端内嵌旧 `yudao-ui/`。当前管理端位于根目录 `管理后台/`。来源、归档、验证和后续配置见 [清理执行记录](../项目文档/后端匹配审计-2026-09-09/清理执行记录.md)；原始许可证和来源文件保留。

微信、支付、内容审核的 Stub 及本地对象存储仅在 `zsdev` 且未同时启用 `prod` / `production` 时装配。正式环境需要真实适配器；当前只填写密钥不会自动获得真实登录、支付、云存储或 AI 生成能力。不得把开发环境的通过记录当作真实渠道验收。

## 众墅业务模块

| 模块 | 责任 |
|---|---|
| `yudao-module-identity` | 微信身份合同、账号、授权码、访问授权、会话和个人资料 |
| `yudao-module-design` | 资产、案例、设计项目、候选/结果、预算、投稿、审核和消息 |
| `yudao-module-commerce` | 定价、设计点账本、充值订单、支付和退款领域 |
| `yudao-module-ai-orchestration` | AI 任务、租约、回调、结果隔离、结算和取消 |
| `yudao-module-infra` | Outbox、审计、异步导出和一次性交付能力 |

## 环境要求

- JDK 17（`.java-version` 已声明；启动脚本会拒绝其它主版本）
- Maven 3.9.16（通过 Maven Wrapper 3.3.4 自动获取并校验，不要求全局安装 Maven）
- Docker Engine / Docker Desktop 与 Compose
- 本地编排固定 PostgreSQL 16.15、Redis 7.4.11

Wrapper 的脚本和 JAR 来自 Apache Maven 官方 3.3.4 发行包，Maven 发行包与 Wrapper JAR 的 SHA-256 已固定在 `.mvn/wrapper/maven-wrapper.properties`。

## Windows 一键本地启动

在本目录执行：

```powershell
.\script\dev\start-local.ps1
```

脚本会依次完成：

1. 检查 JDK 17 与 Docker daemon；
2. 首次运行时从 `.env.example` 创建被 Git 忽略的 `.env`；
3. 启动并等待 PostgreSQL、Redis 健康；
4. 通过 Maven Wrapper 编译全部启用模块；
5. 以 `local,pg,zsdev` 启动后端，执行 Flyway 迁移并等待 `/actuator/health` 返回 `UP`。

成功后 PID 与日志位于 `target/local-dev/`。停止服务与依赖：

```powershell
.\script\dev\stop-local.ps1
```

只有确认要清空本机开发数据库和 Redis 数据时才执行：

```powershell
.\script\dev\stop-local.ps1 -DeleteData
```

`-DeleteData` 会删除 Docker 开发卷，数据不可恢复。

## macOS / Linux 手动启动

```bash
cp .env.example .env
docker compose --env-file .env -f compose.dev.yml up -d --wait
./mvnw -B -DskipTests package
./mvnw -pl yudao-server -am spring-boot:run \
  -Dspring-boot.run.profiles=local,pg,zsdev
```

另一个终端执行 `curl --fail http://127.0.0.1:48080/actuator/health` 验证健康状态。

## Profiles

- `local`：底座本地配置。
- `pg`：PostgreSQL 与生产安全默认；不包含密钥默认值，开发便利开关默认关闭。
- `zsdev`：仅本机 Stub 联调；包含公开占位密钥、种子数据、本地资产内容端点和后台驱动器。

本地 Stub 联调使用：

```bash
./mvnw spring-boot:run -pl yudao-server \
  -Dspring-boot.run.profiles=local,pg,zsdev
```

生产环境不得启用 `zsdev`，并必须通过受控环境变量或密钥托管提供所需配置。

`.env` 中可通过 `ZS_PG_PORT` / `ZS_PG_URL` 和 `ZS_REDIS_HOST` / `ZS_REDIS_PORT` 调整本机端口；修改 PostgreSQL 端口时需同步更新 JDBC URL。

## Build and test

```bash
./mvnw -B -DskipTests package
./mvnw -B test
./mvnw -B -pl yudao-server -am test
```

众墅专项测试大量依赖 PostgreSQL/Testcontainers。测试结果必须来自当前提交和可取回的 CI runner 报告。

## Database migrations

- 平台：`yudao-module-infra/src/main/resources/db/migration/platform/`
- 身份：`yudao-module-identity/src/main/resources/db/migration/identity/`
- 设计：`yudao-module-design/src/main/resources/db/migration/design/`
- 商业：`yudao-module-commerce/src/main/resources/db/migration/commerce/`
- AI 编排：`yudao-module-ai-orchestration/src/main/resources/db/migration/ai-orchestration/`
- 管理菜单：`yudao-server/src/main/resources/db/migration/platform/`

已发布迁移不得原地修改。新增迁移必须包含升级验证和恢复说明。

全新本地 PostgreSQL 卷会先按 `sql/postgresql/ruoyi-vue-pro.sql` 与 `quartz.sql` 初始化底座表；后端第一次启动时，再由 Flyway 执行五个众墅迁移目录。复用已有卷不会重复执行初始化 SQL。

## 当前真实集成边界

- 微信身份：Stub，待 T06-01。
- 微信支付与回调：Stub/领域合同，待 T06-02。
- 对象存储：本地实现，COS 待 T07-01。
- 内容审核：直通 Stub，待 T07-03。
- AI Runtime/Provider：未接真实供应商，待 T08。

Stub 通过只用于本地合同联调，不等于生产验收。

## Documentation and governance

- 受控后端合同：[`docs/zhongshu-design/README.md`](docs/zhongshu-design/README.md)
- 统一任务状态：[`项目状态看板`](../项目文档/项目状态看板.md)
- 源码来源：[`SOURCE_PROVENANCE.md`](../SOURCE_PROVENANCE.md)
- 贡献规范：[`CONTRIBUTING.md`](../CONTRIBUTING.md)
- 上游许可证：[`LICENSE`](LICENSE)
