# 众墅之家设计小程序

[![三端基线验证](https://github.com/phlong026/zhongshu-home-design-miniprogram/actions/workflows/verify.yml/badge.svg)](https://github.com/phlong026/zhongshu-home-design-miniprogram/actions/workflows/verify.yml)

本仓库归档“众墅之家设计小程序”当前三端源码，作为后续协作、联调和版本管理的统一入口。

## 目录

- `前端程序/`：微信原生小程序，包含页面、组件、品牌素材与自动化测试。
- `管理后台/`：Vue 管理后台。
- `后端程序/`：Java 后端及众墅之家业务模块。
- `项目文档/`：项目根目录中的架构、联调、部署与进度文档。

## 本次源码基线

- 小程序前端：`fix/activation-feature-gate`，`be57fed0cac613e7272cdb7dba24c557304f3361`
- 管理后台：`main`，`a6969959bc41a8bb087bd95c4aa58fadd518ad11`
- Java 后端：`feat/zhongshu-design-v1`，`2bf21e528e8a73481c98d35a061eae60ad897f02`

归档日期：2026-09-08。

## 安全说明

仓库不包含本地 `.env`、密钥、依赖目录、编译产物或自动化工具运行状态。部署前请按实际环境单独配置变量和凭据，不要把真实密钥提交到 Git。

各端的安装、运行和验证方式见对应目录内的 `README.md` 与 `package.json` / `pom.xml`。

## 统一验证

先安装管理端依赖：

```bash
cd 管理后台
pnpm install --frozen-lockfile
cd ..
```

然后在仓库根目录执行：

```bash
node scripts/verify.mjs doctor
node scripts/verify.mjs all
node scripts/verify.mjs e2e
```

`all` 固定按“小程序测试 → 管理端测试/类型检查/Lint/生产构建 → 后端普通单元与集成测试（*Test）”执行；后端包含所选业务模块及 Maven `-am` 依赖模块，默认排除 `real-acceptance` 标签，不触发真实渠道消费。`test-baseline.json` 登记小程序、管理端及五个众墅模块的测试源码数量，实际运行/跳过数量以测试报告为准。`e2e` 在已启动的 `local,pg,zsdev` 后端上执行 54 个三端全链检查点；`full` 依次执行两组验证。后端与 E2E 需要 JDK 17、Docker；Windows 可用 `scripts/verify-isolated-e2e.ps1` 创建独立数据库、验证重复启动并在结束后清理该测试项目。也可用 `mini`、`admin`、`backend` 参数单独复跑，详见 [`e2e/README.md`](e2e/README.md)。

环境自检只输出敏感变量的“已设置/未设置”状态，不打印变量值。CI 与本地复用这组命令。

根级 GitHub Actions 在 `main` 的 push、面向 `main` 的 pull request 及手动触发时运行四个独立检查：`mini`、`admin`、`backend`、`e2e`。Surefire 报告和 E2E 报告/日志无论成功或失败都会保留 14 天。工作流首次在远端全绿后，应按 `CONTRIBUTING.md` 启用分支保护。

## 项目治理

- [源码来源与归档完整性](SOURCE_PROVENANCE.md)
- [T00 仓库基线审计](项目文档/T00-仓库基线审计-2026-09-08.md)
- [正式任务拆分与执行顺序 V1.0](项目文档/T01-正式任务拆分与执行顺序-V1.0.md)
- [T10 预算模块最终修改方案](项目文档/T10-预算模块最终修改方案-V1.0.md)
- [T10 预算工作清单与验收标准](项目文档/T10-预算模块任务清单与验收标准-V1.0.md)
- [T10 真实三端预算 E2E 验收](项目文档/T10-真实三端预算E2E实现验收-2026-09-08.md)
- [项目实时状态](项目文档/项目状态看板.md)
- [T14 激活用户当地单价覆盖方案](项目文档/T14-激活用户当地单价覆盖方案-V1.0.md)
- [T14 任务清单与验收标准](项目文档/T14-激活用户当地单价覆盖任务清单与验收标准-V1.0.md)
- [贡献与提交规范](CONTRIBUTING.md)
