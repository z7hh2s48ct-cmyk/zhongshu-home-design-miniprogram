# T02-05 三端全链 E2E

原 `/tmp/zs-e2e.mjs` 不在 Git 历史和当前机器中。本目录依据 `项目文档/10-开发进度与完成情况报告-V1.0.md` 的历史链路重建 37 个可追溯检查点，不声明与遗失脚本逐字一致。

## 运行

先以 `local,pg,zsdev` 启动后端，再从仓库根目录执行：

```bash
node e2e/zs-e2e.mjs
```

查看检查点或仅做离线结构校验：

```bash
node e2e/zs-e2e.mjs --list
node e2e/zs-e2e.mjs --validate
```

默认只允许访问 loopback，防止误写共享或生产环境。每次运行使用唯一微信 Stub code、幂等键和业务备注；Runtime 产生的 PNG 在结束时逐文件清理。数据库业务记录依照审计要求保留，不以 SQL 绕过领域删除。CI 使用全新 Docker 卷，并在结束时销毁该卷。

环境变量样例见 `.env.example`。默认资产目录为系统临时目录下的 `zhongshu-assets`，它必须与 Java 的 `zhongshu.design.asset.storage-root` 一致。报告写入 `artifacts/e2e/latest.{json,md}`，该目录不入 Git，由 CI 作为制品保存。

本链路只验证 Stub 微信、Stub 支付、本地对象存储和模拟 Runtime，证据等级上限为自动化集成验证，不代表真实微信、支付、COS、内容审核或 AI Provider 验收。
