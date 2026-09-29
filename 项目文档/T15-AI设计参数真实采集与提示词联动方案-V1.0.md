# T15：AI 设计参数真实采集与提示词联动方案 V1.0

> 立项日期：2026-09-29。来源：业务线复审确认「AI 设计-户型设计/自主设计」页多数参数为静态展示或假控件，用户要求页面所有内容真实传至后端并由 AI 生图提示词体现。

## 1. 背景与问题清单

对 `pages/ai-design/index`（户型设计/自主设计）逐控件核实的结论：

| 控件 | 现状 | 证据 |
|---|---|---|
| 面宽/进深 | 写死 `12.6m/13.8m` 纯文字 | `index.wxml:15` 无绑定 |
| 层数 | 假下拉（有 › 无 bindtap） | `index.wxml:16`、`index.js:19` 固定 `'两层'` |
| 家庭需求 | 假下拉 | `index.wxml:17`、`index.js:19` 固定 `'5室3厅2卫'` |
| 补充需求 | 可填；但选参考案例后 `generate()` 仅发 `{sourceType, refCaseId}`，note 被丢弃 | `index.js:125-126` |
| 生成数量 | 真实（报价联动 + priceConfirmation） | 已正常，不动 |
| 自主-提示词/草图 | 真实 | 已正常，不动 |
| 需求标签行 | 纯装饰 | `index.wxml:26`（本期待办外，可选 F5） |
| 后端 CASE_REFERENCE 分支 | 仅冻结 `ref_version_id`，不落需求快照 | `DesignProjectService.createProject` |
| runtime 提示词 | `JSON.stringify(requirements)` 直塞 LLM，新键自动生效但可读性差 | `ai-runtime/src/runtime.mjs` `plan()` |

## 2. 目标

页面每个参数（面宽、进深、层数、家庭需求、补充需求、提示词、草图、数量）满足：
1. 界面真实可改；
2. 随创建请求进入后端需求快照（**含参考案例模式**）；
3. 出现在 ai-runtime 的 LLM 提示词中，直接影响生图。

## 3. 数据结构（requirementInputs 统一契约）

`requirementInputs` 为 jsonb Map，扩键无需改表。目标 schema（兼容旧键）：

```json
{
  "faceWidthM": 12.6,
  "depthM": 13.8,
  "floor": "两层",
  "floorCount": 2,
  "family": "5室3厅2卫",
  "rooms": { "bedroom": 5, "living": 3, "bath": 2 },
  "prompt": "…",
  "note": "…"
}
```

校验规则（B2，实施后定稿）：白名单 = faceWidthM/depthM（3~40，后端强制下界）+ floor/family/prompt/note（≤200 字，控制字符剔除而非拒绝）+ floorCount/count（1~4 整数）+ rooms（键 ⊆ bedroom/living/bath/kitchen，各值 1~20，0 值以省键表达）+ styleCode/roofType/material/color（`[A-Za-z0-9_]{1,32}`，立面任务配置键同经此白名单）；未知键拒绝（错误码 1_071_000_005）；序列化 UTF-8 ≤4KB（兜底防线，白名单边界内实际不可达）。快照 append-only、不可变红线不碰。

## 4. 改动清单

### 4.1 后端（yudao-module-design）
- B1 ~~CASE_REFERENCE 落快照~~ 实施时核实：快照插入本就对两种 sourceType 无条件执行（createProject:101-107），无需改动；实际落地为**案例元数据兜底**——CASE_REFERENCE 分支读取 `design_case_version` 的 floor_count/face_width/depth/style_code，对用户未显式给出的键做默认填充（用户输入优先），保证选案例时提示词也携带真实尺度。
- B2 `BudgetInputs.validateRequirementInputs`：新增设计键白名单（faceWidthM/depthM/floor/floorCount/family/rooms/prompt/note/styleCode），未知键拒绝（新错误码 1_071_000_005）；数值范围（面宽/进深 3~40、floorCount 1~4、rooms 各值 0~20）、文本 ≤200 字并拒绝控制字符、jsonb 总量 ≤4KB；budgetInputs 契约保持 T10 原样。
- B3 `runtimeInput` 组装：实施时核实本就全键透传快照（runtimeInput:574-578），零改动。
- B4 合同测试 `DesignRequirementInputsT15ContractTest`（真实 PG）：归一化落快照、未知/越界/超长/控制符拒绝、案例兜底与用户优先、旧形状与 budgetInputs 回归。
- B5 E2E：平面链新增「Runtime 输入快照携带设计需求」检查点（58→59），以 runtime 身份拉取 inputs 断言新键与 budgetInputs 同帧携带。

### 4.2 前端（miniprogram pages/ai-design）
- F1 面宽/进深 → `input type=digit`，3~40 校验；选中参考案例时用案例 faceWidth/depth 预填（`globalData.refCase` 扩容携带尺寸，`library/detail.js` 同步）。
- F2 层数 → 真实 picker（一层~四层），`floor` 文案 + `floorCount`。
- F3 家庭需求 → 多列 picker（室/厅/卫），`family` 文案 + `rooms` 结构化。
- F4 `generate()` 两种模式都带 `requirementInputs`。
- F6 抽 `buildRequirementInputs(mode, data)` 纯函数并补单测（模式×案例×字段矩阵）。
- F5（可选）需求标签可点选追加进 prompt——本期不做，登记待办。

### 4.3 生成引擎（ai-runtime）
- R1 `plan()`：已知键格式化为中文需求描述（`宅基地面宽12.6米、进深13.8米，两层，5室3厅2卫；补充需求：…`），未知键回退 raw JSON，缺键兼容旧项目；system 防注入提示词保留。

### 4.4 E2E
- B5 平面链检查点扩展：以 runtime 身份 HMAC 拉取 inputs，断言 `requirements` 含 `faceWidthM/depthM/floorCount/rooms/note` 且 `budgetInputs` 同帧携带。

## 4.5 实施结果（2026-09-29）

- 前端：`utils/design-inputs.js`（纯函数）+ `pages/ai-design/index.{wxml,js,scss}` 真实控件 + `pages/library/detail.js` refCase 扩容 + `tests/design-inputs.test.js`（5 用例）。
- 后端：`BudgetInputs` 设计键白名单校验、`DesignProjectService.withCaseDefaults` 案例兜底（越界案例值跳过而非拒绝创建）、错误码 `DESIGN_REQUIREMENT_INPUT_INVALID`（1_071_000_005）、`DesignRequirementInputsT15ContractTest`（6 用例，真实 PG）。
- 引擎：`ai-runtime/src/runtime.mjs` `formatRequirements` + `plan()` 接入；`test/format-requirements.test.mjs`（4 用例）。
- E2E：59 检查点（离线校验通过）；`test-baseline.json` 已同步（mini 30/286、backend 55/580、contract 37/349、e2e 59）。
- 测试结果：ai-runtime 13/13、小程序 280/280、e2e 离线校验通过、后端全量 verify 测试阶段通过。
- 评审修复（2026-09-29 独立评审）：P0 立面配置键（count/roofType/material/color）纳入白名单避免立面链被全量拒绝；P1 案例兜底越界值跳过；P1 案例详情 parameters 尺寸补进 caseDetail 投影使预填生效；P2 面宽/进深下界 3 后端强制、控制字符改剔除、4KB 按字节计、换案例强制刷新预填、runtime label() 原型链防护。

## 5. 实施顺序

后端契约收口（B1-B3）→ 前端（F1-F4/F6）→ runtime（R1）→ 测试（B4/B5）→ 联调 → 评审 → 提交。

## 6. 风险与红线

- 旧项目快照无新键：runtime 格式化兜底 + 回归用例。
- 用户自由文本为提示词注入面：system 防线保留 + 长度限制。
- 不动计价/幂等/快照不可变；小程序测试基线与后端合同测试全绿方可提交。

## 7. 验收标准（AC）

| # | 标准 |
|---|---|
| AC-01 | 户型设计页：面宽/进深可输入、层数/家庭需求可选择，值随请求上送 |
| AC-02 | 选参考案例时：尺寸按案例预填；补充需求仍随 CASE_REFERENCE 上送且入快照 |
| AC-03 | 自主设计页行为回归不变 |
| AC-04 | e2e runtime inputs 断言含新键 |
| AC-05 | ai-runtime plan 提示词含中文格式化需求且旧快照兼容 |
| AC-06 | 小程序测试、后端 design 合同测试全绿 |
