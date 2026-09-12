# RG1：资产、支付、生产配置及质量门禁

分组：R03、R04、R05、R06、R08、R09。分支 task/RG1-risk-remediation；从本地 main 创建的 worktree 为 E:/众墅之家设计平台/zs-rg1。基线导入提交为 94503c9。

## 代码整改

|风险|改动与验证重点|剩余上线条件|
|---|---|---|
|R03|原上传键统一进入 uploads/；扫描通过字节写入服务端独占 accepted/assetId/校验令牌键；数据库以校验令牌 CAS 指向胜出版本；读取有大小上限。真实 MinIO 签名 PUT 测试覆盖完成后重放、扫描中覆盖、旧租约回写、非法字节拒绝和摘要一致性。|正式 COS 的私有桶/写权限与生命周期验收；旧链接排空及存量核对；AI 产物晋升在 RG3 连同 Runtime 处理。|
|R04|幂等创建订单后确保预支付参数；记录可信会话 openid、商户/渠道、参数有效期和 60 秒请求租约；同商户订单号重试，缓存有效参数，不把渠道状态标记当支付参数。订单详情增加原单继续支付，防重复点击和身份切换。|真实微信重复预下单、参数到期、取消与成功回调验收。渠道明确返回订单存在但不返回 prepayId 时按原单可重试异常处理，不编造支付参数。|
|R05|CREATED 与已付款未到账均纳入恢复；每批按下次恢复时间领取并退避，使用 SKIP LOCKED 分摊；终态落库，单条失败隔离。覆盖 50 条旧 CLOSED/PENDING 订单、毒性记录、并发重复和到账幂等。|历史未记录商户的未确认订单需核对后赋值；生产驱动开关与巡检随 RG3 完成。|
|R06|新增 pg,prod 生产配置；禁止生产叠加 local/dev/zsdev；显式关闭模拟鉴权、示例数据、开发资产端点、Druid 管理页；限制 Actuator 暴露并禁止值回显。生产数据库、Redis、微信支付从部署环境取值。|需要正式凭据、域名、私钥挂载；真实审核实现随 RG3，缺少真实实现或凭据时保持启动拒绝。|
|R08|种子初始化用显式事务及数据库 advisory lock；样例发布前补齐展示授权；重复启动复用样例和发码批次；修复历史半初始化样例。新增隔离 E2E runner，测试全新数据库与重复启动。|只允许开发环境播种，正式环境禁止。|
|R09|门禁由仅 *ContractTest 扩展为全部普通 *Test（含依赖模块），保留真实消费标签排除；扩充五个众墅模块源码测试清单；登记 T10 四个已冻结错误码；修正管理端样式/格式及底座跨平台换行断言。|远端 CI 和分支保护需实际推送后确认；本轮只本地集成。|

## 代码复核

这是开发后单独进行的自查，不代表技术负责人、QA 或安全角色的独立审批。

- 授权：资产归属在领取前检查；支付按订单与用户联合查询，openid 来自认证会话；等待支付参数期间身份变化不拉起微信支付。
- 并发：资产通过唯一键与校验令牌隔离旧扫描；预下单短事务领取、事务外调用、令牌匹配写回；重复幂等键创建使用数据库唯一约束的 ON CONFLICT。
- 支付事实：渠道“已支付”只触发原有查单校验和唯一到账链，不直接记账；参数只在有效期内下发；单条恢复失败不阻塞后续记录。
- 兼容：新增列不删除业务数据；既有已核验支付事实用于回填商户，未确认数据不猜测所属渠道；开发 profile 保留原联调行为。
- 复核中修正：有效参数重复预下单、身份失效后按钮残留、旧对象键无扩展名时异常、旧开发初始化缺少发码批次、Windows 换行断言。

## 迁移与回退

新增 Flyway：V20260913.211__asset_validation_fencing.sql、V20260913.304__payment_recovery_leases.sql。应用部署前先备份数据库并确认 Flyway 成功；本轮所有测试仅迁移隔离数据库。

核对 SQL（只读）：

```sql
SELECT payment_channel, payment_merchant, payment_state, count(*)
FROM recharge_order WHERE deleted=FALSE GROUP BY 1,2,3;
SELECT count(*) FROM recharge_order
WHERE payment_channel IS NULL AND payment_state IN ('CREATED','PENDING','UNKNOWN','SUCCEEDED')
  AND fulfillment_state <> 'CREDITED' AND deleted=FALSE;
SELECT upload_status, count(*) FROM asset WHERE deleted=FALSE GROUP BY 1;
SELECT count(*) FROM asset WHERE upload_status='ACCEPTED' AND object_key NOT LIKE 'accepted/%' AND deleted=FALSE;
```

旧版本签发的 PUT 链接最长有效 900 秒。正式切换需停止旧版本签发后至少排空 15 分钟，并核对已有 ACCEPTED 对象与 stored_sha256；发现不一致的资产必须隔离重审。不能仅改数据库摘要来“修复”未经扫描的内容。历史 accepted 对象无需批量清空；存量重键应以已核验字节复制和数据库 CAS 实施。

COS 生命周期应清理 uploads/ 原始对象（建议不短于一天，长于上传与扫描重试窗口），禁止客户端对 accepted/ 签发写权限。落库失败产生的 accepted/ 孤儿对象须按数据库引用核对后清理，不能按目录年龄直接删除仍被引用的作品。云端规则变更和存量核对列入最终人工清单。

回退优先向前修复。若需回退应用，不删除新增列及索引；旧应用没有不可变对象和支付租约保护，须先停上传/预下单写入并关闭相应消费者，保留所有账务事实后再执行版本回退。不要回滚已到账或已退款事实，不要重放人工未核验的跨商户订单。

## 验证记录

工具：Temurin JDK 17.0.20.1+1（官方包校验 SHA-256），Node 24.19.0，pnpm 11.19.0。管理端 frozen-lockfile 安装通过。

验证结果和本地合并信息见本目录执行总表；原始日志保留于 artifacts/risk-remediation（忽略提交）。全部普通测试使用隔离数据库/MinIO 与替身支付，未进行真实渠道消费。

实测结果：

- `node scripts/verify.mjs all`：通过。小程序 208/208；管理端 76/76、类型检查、Lint、生产构建通过；后端 135 份 Surefire 报告共 1356 项，0 失败、0 错误、11 跳过（保留底座既有跳过用例；真实消费标签不执行）。
- `./mvnw.cmd -B -o -Dtest=Payment*Test -Dsurefire.failIfNoSpecifiedTests=false -pl yudao-server -am test`：最终参数有效期修正后通过。
- `scripts/verify-isolated-e2e.ps1 -RunName rg1`：全新隔离数据库初始化、同库二次启动、54/54 HTTP E2E 通过；专属容器与卷清理完成。
- `node scripts/test-inventory.mjs --check`、`git diff --check`：通过。新增四项资产隔离、八项支付恢复及三项前端继续支付测试纳入门禁。

日志摘要 SHA-256 见 RG1-验证日志索引.json；这些记录证明本次本地回归，不代表真实支付、COS 或外部 Runtime 的上线验收。
