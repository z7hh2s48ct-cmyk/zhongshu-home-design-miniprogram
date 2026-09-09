# P1B：PostgreSQL 迁移与约束基线（冻结记录）

> 冻结日期：2026-09-05
> 状态：随 P1B 提交生效；后续变更须走迁移评审并更新本文件

## 1. 版本与工具合同

| 项 | 冻结值 |
|---|---|
| 数据库 | PostgreSQL 17 优先（16 可接受）；测试夹具固定 `postgres:17-alpine` |
| 迁移工具 | Flyway（版本随 Spring Boot 3.5 BOM）+ `flyway-database-postgresql` |
| 驱动 | `org.postgresql:postgresql`（runtime scope） |
| 测试夹具 | Testcontainers `junit-jupiter` + `postgresql`（test scope，真实 PG，禁止 H2/SQLite 冒充） |
| 启用方式 | 默认关闭（`spring.flyway.enabled=false`）；`pg` profile 启用（`application-pg.yaml`），连接串走 `ZS_PG_URL/ZS_PG_USERNAME/ZS_PG_PASSWORD` 环境变量 |

### 1.1 pg profile 配置合同（评审后冻结）

- 底座数据源由 dynamic-datasource 接管：pg profile 必须覆盖 `spring.datasource.dynamic.datasource.master.*`（普通 `spring.datasource.*` 键不生效）。
- Flyway 必须显式给 `spring.flyway.url/user/password`，否则 FlywayAutoConfiguration 回退到路由数据源（=master），会对非 PG 库执行 PG 方言迁移。该两条键由 `ZhongshuPgProfileConfigContractTest` 守卫。
- 部署形态：整库 PostgreSQL（D-08）。底座 system/infra 在 PG 上的初始化与回归属于平台阶段 1A/1B 门禁（T00 登记 §4 待办）；完整服务器 PG 启动的 `@SpringBootTest` 装配验证在平台门禁通过后补证，当前不以配置文件存在冒充启动验收。

## 2. 目录与命名

- 迁移目录按归属分包（Flyway 多 location）：
  - `yudao-server/src/main/resources/db/migration/platform/`（平台/工程级：Outbox、审计、基线探针等）
  - `yudao-module-identity/src/main/resources/db/migration/identity/`（后续包迁移放各自模块）
  - design / commerce / ai-orchestration 同理
- 文件命名：`V<yyyyMMdd>.<模块段号NNN>__<snake_case_描述>.sql`，如 `V20260905.001__zhongshu_baseline.sql`、`V20260905.301__p4a_pricing_points.sql`；禁止修改已发布迁移，纠错一律新迁移。
- Flyway 版本号全局唯一（跨 location）：NNN 按模块分段——platform 000–099、identity 100–199、design 200–299、commerce 300–399、ai-orchestration 400–499；段内按提交顺序递增（每个迁移一张表变更群，一天内不够用就顺延次日日期）。
- 逆向脚本：`yudao-server/src/main/resources/db/migration-undo/platform/`（各模块同理），与正向迁移同版本号、`__undo_` 前缀；Flyway 社区版不自动执行，回滚走受控人工/工具流程，执行前核对数据。
- 多实例部署：`out-of-order=false`；发布顺序遵循「先滚动应用后迁移」或使用并行兼容迁移（加可空列 → 回填 → 加约束）。

## 3. 领域表通用约定

- 主键 `id BIGINT`：应用侧雪花 ID（MyBatis-Plus ASSIGN_ID），不用自增暴露量级。
- 通用列：`tenant_id BIGINT DEFAULT 0`（底座租户插件约定，业务语义待 D-09 冻结）、`creator/updater VARCHAR(64) DEFAULT ''`、`create_time/update_time TIMESTAMPTZ NOT NULL DEFAULT now()`、`deleted BOOLEAN NOT NULL DEFAULT FALSE`。
- 点数/金额：一律 `BIGINT` 存最小单位（设计点用「点」、金额用「分」）；余额列必须 `CHECK (col >= 0)`，禁止负余额语义。
- 状态列：`VARCHAR(32)` + `CHECK (status IN (...))`；新增状态值走新迁移放开 CHECK，不用 PG enum。
- 约束命名：`pk_<表>`、`uk_<表>_<语义>`、`ck_<表>_<规则>`、`fk_<表>_<引用>`、`idx_<表>_<列>`；部分唯一索引用于「仅 ACCEPTED 候选占槽位」这类条件唯一。
- 表名不带全局前缀，直接使用架构文档对象名（account、wechat_identity、design_case、design_point_account、ai_job、recharge_order…）；各模块迁移只允许创建/修改本模块的表。

## 4. 验证合同（ZhongshuFlywayPostgresContractTest）

| 场景 | 断言 |
|---|---|
| 全新库升级 | 2 个迁移全部执行，探针表与索引存在，history=2 |
| 逐步升级 | `target(20260905.001)` 只应用 V001；再 migrate 补齐 V002 |
| 约束红灯 | 负点数被 CHECK 拒绝；重复业务键被 UNIQUE 拒绝 |
| 回滚演练 | 执行 undo 脚本后表消失；清 history 后可重复正向升级 |
| 并发夹具 | 10 线程（每线程独立 JDBC 连接）互异插入全部成功；10 线程同业务键恰好 1 条成功 |
| pg 配置守卫 | pg profile 必含 spring.flyway.url 与 dynamic master PG 覆盖；locations 覆盖五个目录 |

## 5. 明确不做

- 不用 H2/SQLite 跑任何迁移或约束测试；
- 不在业务迁移外手工改库（修复必须走新迁移）；
- 不使用 PG enum / 触发器承载业务状态机（状态机在应用层事务内）；
- 不把 undo 脚本接入自动执行。

## 6. T10 增量登记（2026-09-08）

新增设计模块 `V20260908.207__t10_itemized_budget.sql`，旧迁移不改写。复用预算主表，增加分项/价格/修订/报价约束；采用具名CHECK/FK/UK和延迟外键，无PG扩展或业务触发器。数据库验收待本机Docker虚拟化环境恢复，具体升级/恢复边界见[T10-02预算迁移与恢复](T10-02-预算迁移与恢复.md)，不得作为已通过的冻结基线证据。

同日18:39续记：用户重启并恢复WSL/Docker后，上述真实PG17.11迁移/约束用例已执行通过，四业务模块根回归133/133通过。原环境阻碍解除；独立评审、实际旧库备份副本与远端CI仍待验，不改写原P1B历史签署。证据见[重启恢复与参数验收](../../../项目文档/T10-重启恢复与参数实现验收-2026-09-08.md)。
