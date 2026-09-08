# 众墅之家设计管理后台

众墅之家设计平台的运营管理端，基于 Vue 3、Vite、TypeScript、Element Plus 和 RuoYi-Vue-Pro 管理端底座改造。

## 当前范围

业务代码集中在：

- `src/views/zs/`：工作台、案例、审核、授权码、充值方案、订单、点数流水、C 端用户、AI 任务、导出和审计。
- `src/api/zs/index.ts`：众墅管理端 API。
- `src/router/modules/zs.ts`：众墅静态业务路由。

底座的 system/infra 页面仍在仓库中，但众墅业务完成度只按上述 `zs` 代码面统计。

## 工具版本

- Node.js：`>= 20.19.0`
- pnpm：`>= 8.6.0`

精确 pnpm 版本将由 T02-01 在 `package.json#packageManager` 中冻结。在此之前不要绕过锁文件供应链校验。

## 环境配置

复制 `.env.example` 为本地环境文件，并按实际环境填写：

```dotenv
NODE_ENV=development
VITE_APP_TITLE=众墅之家设计管理后台
VITE_BASE_URL=/
VITE_API_URL=http://localhost:48080/admin-api
VITE_UPLOAD_TYPE=server
VITE_APP_TENANT_ENABLE=false
VITE_APP_CAPTCHA_ENABLE=true
VITE_APP_DOCALERT_ENABLE=false
VITE_OPEN=false
VITE_PORT=80
```

不要提交真实环境地址、账号、令牌或其它凭据。

## 常用命令

```bash
pnpm install --frozen-lockfile
pnpm dev
pnpm ts:check
pnpm lint
pnpm build:prod
```

当前归档基线的锁文件含镜像 tarball URL 元数据不一致问题；T02-01 完成前，冻结安装可能被供应链校验拒绝。以根级项目状态看板为准。

## 质量要求

- 冻结安装、TypeScript 检查、Lint 和生产构建必须由根级 CI 执行。
- 关键路由、API 参数、权限态、19 位 ID、金额和时间格式必须有自动化测试。
- 空数据、无权限、登录失效、网络错误和服务错误不得通过空 `catch` 静默吞掉。
- 视觉验收证据必须登记视口、fixture、提交号和可取回制品。

## 上游与许可证

- 管理端底座来源：RuoYi-Vue-Pro / vue-element-plus-admin 生态。
- 本目录的上游许可证见 [`LICENSE`](LICENSE)。
- 本项目源码来源与归档完整性见根目录 [`SOURCE_PROVENANCE.md`](../SOURCE_PROVENANCE.md)。
- 当前任务与验证状态见 [`项目状态看板`](../项目文档/项目状态看板.md)。
