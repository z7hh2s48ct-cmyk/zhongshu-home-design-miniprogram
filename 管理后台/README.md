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
- pnpm：`11.19.0`（由 `package.json#packageManager` 固定）

项目级 `.npmrc` 固定使用 npm 官方 registry；`pnpm-workspace.yaml` 仅允许锁文件中 3 个明确登记的传递依赖执行安装脚本。

## 环境配置

复制 `.env.example` 为本地环境文件，并按实际环境填写：

```dotenv
NODE_ENV=development
VITE_APP_TITLE=众墅之家设计管理后台
VITE_BASE_PATH=/
VITE_BASE_URL=
VITE_API_URL=/admin-api
VITE_UPLOAD_TYPE=server
VITE_APP_TENANT_ENABLE=false
VITE_APP_CAPTCHA_ENABLE=true
VITE_APP_DOCALERT_ENABLE=false
VITE_OPEN=false
VITE_PORT=5173
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

`build:prod` 使用已入库的 `.env.prod` 安全默认值；部署环境可按目标域名覆盖公开的 Vite 构建变量。不要把令牌、密钥或其它机密放入 `VITE_*` 变量。

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
