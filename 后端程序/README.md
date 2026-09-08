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

目录中还保留 BPM、CRM、ERP、Mall、IoT 等上游模块源码，但它们不属于当前 Maven reactor，也不计入众墅业务交付范围。保留/裁剪决策由 T05-04 处理。

## 众墅业务模块

| 模块 | 责任 |
|---|---|
| `yudao-module-identity` | 微信身份合同、账号、授权码、访问授权、会话和个人资料 |
| `yudao-module-design` | 资产、案例、设计项目、候选/结果、预算、投稿、审核和消息 |
| `yudao-module-commerce` | 定价、设计点账本、充值订单、支付和退款领域 |
| `yudao-module-ai-orchestration` | AI 任务、租约、回调、结果隔离、结算和取消 |
| `yudao-module-infra` | Outbox、审计、异步导出和一次性交付能力 |

## 环境要求

- JDK 17
- Maven 3.8+；T02-02 将加入 Maven Wrapper
- PostgreSQL 16/17
- Redis 6+
- Docker 为推荐的本地依赖运行方式

## Profiles

- `local`：底座本地配置。
- `pg`：PostgreSQL 与生产安全默认；不包含密钥默认值，开发便利开关默认关闭。
- `zsdev`：仅本机 Stub 联调；包含公开占位密钥、种子数据、本地资产内容端点和后台驱动器。

本地 Stub 联调使用：

```bash
mvn spring-boot:run -pl yudao-server \
  -Dspring-boot.run.profiles=local,pg,zsdev
```

生产环境不得启用 `zsdev`，并必须通过受控环境变量或密钥托管提供所需配置。

## Build and test

当前 Maven Wrapper 和统一本地编排尚在 T02-02 范围内。工具链可用时执行：

```bash
mvn -B -DskipTests package
mvn -B test
mvn -B -pl yudao-server -am test
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
