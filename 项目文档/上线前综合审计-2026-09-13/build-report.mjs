import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
const out=path.dirname(fileURLToPath(import.meta.url)), root=path.resolve(out,'../..');
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
const json=n=>JSON.parse(fs.readFileSync(path.join(out,n),'utf8'));
const files=[...new Set(execFileSync('git',['ls-files','-co','--exclude-standard','-z'],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean))].filter(p=>!p.startsWith('.claude/')&&!p.includes('上线前综合审计-2026-09-13'));
const file=name=>{const a=files.filter(p=>p===name||p.endsWith('/'+name));if(a.length!==1)throw Error('Ambiguous source '+name+': '+a);return a[0];};
const link=(p,label,line)=>`[${label}](<${path.resolve(root,p).replaceAll('\\','/')}${line?':'+line:''}>)`;
const artifact=(name,label=name)=>link(path.relative(root,path.join(out,name)),label);
const evidence=[];
function ev(id,name,needle,description){const p=file(name),ls=read(p).split(/\r?\n/),idx=needle?ls.findIndex(l=>l.includes(needle)):0;if(idx<0)throw Error('Missing evidence '+id+' '+needle);const e={id,path:p,line:idx+1,description};evidence.push(e);return e;}
function cite(...ids){return ids.map(id=>{const e=evidence.find(e=>e.id===id);if(!e)throw Error('Unknown evidence '+id);return link(e.path,id,e.line);}).join('、');}
ev('E01','RealServiceWiringPolicy.java','private static final List<String> PRODUCTION_PROFILES','生产识别、真实端口与禁止 profile');
ev('E02','StubContentModerationAdapter.java','class StubContentModerationAdapter','目前唯一内容审核实现');
ev('E03','AssetService.java','public String completeUpload','上传扫描、净化与资产状态推进');
ev('E04','CosObjectStorageAdapter.java','presignUploadUrl(','预签名 PUT 与固定对象键');
ev('E05','AiProviderConfiguration.java','本仓库 ai-orchestration 无出站生成引擎','AI 配置不等于运行时实现');
ev('E06','AiJobInternalController.java','"stub"','任务领取时 provider 标识');
ev('E07','ZhongshuJobDriver.java','private int settleReadyJobs','结果结算与超时处理覆盖范围');
ev('E08','RechargePaymentService.java','public OrderSnapshot createOrder','预下单建单与重试');
ev('E09','RechargePaymentService.java','public int recoverHangingOrders','支付补偿扫描');
ev('E10','RechargePaymentService.java','private void confirmReversal','退款冲正及事件载荷');
ev('E11','MessageService.java','public void deliver','站内信消费者与用户标识');
ev('E12','application-local.yaml','mock-enable: true','local 开启后台模拟身份');
ev('E13','TokenAuthenticationFilter.java','private LoginUser mockLoginUser','模拟后台身份路径');
ev('E14','前端程序/miniprogram/utils/config.js','apiBase','小程序当前后端地址和环境');
ev('E15','前端程序/miniprogram/utils/assets.js','config.env','资产上传根据环境分支');
ev('E16','DevDataSeeder.java','seedDemoCase','干净环境案例种子');
ev('E17','CaseCatalogService.java','void requireCompanyPublishable','公司案例发布要求');
ev('E18','PrivacyService.java','createSubjectRequest','数据主体请求只登记');
ev('E19','前端程序/miniprogram/utils/api.js','getPrivacyConsents','隐私同意及数据请求 API 封装');
ev('E20','前端程序/miniprogram/pages/profile/services.js','contact()','客服入口只弹提示');
ev('E21','ZhongshuExportWorker.java','filter_snapshot','导出筛选快照未进入生成逻辑');
ev('E22','CacheRequestBodyWrapper.java','ServletUtils.getBodyBytes','控制器之前读取完整请求体');
ev('E23','scripts/verify.mjs','-Dtest=*ContractTest','默认后端门禁只选合同测试');
ev('E24','ZhongshuErrorCodeRegistryTest.java','registryMatchesStableNamesExactly','错误码注册表精确匹配测试');
ev('E25','前端程序/scripts/check-release.js',null,'小程序发布静态检查');
ev('E26','前端程序/miniprogram/pages/ai-design/result.js','adjust()','调整入口实际为重新生成');
ev('E27','PricingPort.java','requireConfirmed','价格确认规则');
ev('E28','UserSessionService.java','revokeAllForAccount','会话吊销能力存在，但缺业务关闭编排');
ev('E29','RefundRecoveryJob.java','refund-recovery-enabled:false','退款补偿默认关闭');
ev('E30','管理后台/src/router/modules/zs.ts',null,'管理端业务路由');
ev('E31','前端程序/miniprogram/app.json',null,'小程序 28 个主包页面');
ev('E32','RealWechatIdentityAdapter.java',null,'真实微信 code2session 适配器');
ev('E33','WechatPaymentAdapter.java',null,'真实微信支付、查单、退款及验签适配器');
ev('E34','AccessCodeService.java',null,'授权码生成、状态与交付');
ev('E35','AccessCodeRedemptionService.java',null,'兑换并绑定账号');
ev('E36','AccessGrantService.java',null,'授权查询与撤销');
ev('E37','AppProfileController.java',null,'用户资料白名单更新');
ev('E38','DesignProjectService.java',null,'设计项目、候选选择与结果版本');
ev('E39','AiJobOrchestrationService.java',null,'任务准入、租约、隔离结果');
ev('E40','AiJobSettlementService.java',null,'槽位结算与点数退回');
ev('E41','BudgetCatalogService.java',null,'地区、物料、价目表与套餐');
ev('E42','ItemizedBudgetService.java',null,'明细预算计算与保存');
ev('E43','AdminBudgetRevisionService.java',null,'预算补项、修订与冻结');
ev('E44','BudgetQuoteService.java',null,'报价发布、撤回与客户端查询');
ev('E45','BudgetService.java',null,'兼容旧区间预算');
ev('E46','SubmissionReviewService.java',null,'投稿、审核、退修、重提、发布');
ev('E47','RightsGrantService.java',null,'公开展示和生成引用授权');
ev('E48','PointAccountService.java',null,'点数账本、预留与冲正');
ev('E49','PointAdminController.java',null,'调点审批和权限');
ev('E50','ExportAuditAdminController.java',null,'导出建单、短票据和审计查询');
ev('E51','AccountAdminController.java',null,'C 端用户只读管理');
ev('E52','AiJobAdminController.java',null,'AI 任务管理接口');
ev('E53','DashboardAdminController.java',null,'运营工作台统计');
ev('E54','AppSupportEntryController.java',null,'客服配置接口');
ev('E55','AppDataSubjectRequestController.java',null,'数据导出与关闭账号请求');
ev('E56','OutboxDispatcherService.java',null,'至少一次投递、重试及死信');
ev('E57','SensitiveLoggingPolicy.java',null,'敏感日志配置守卫');
ev('E58','.github/workflows/verify.yml',null,'当前 CI 四条验证链');
ev('E59','前端程序/miniprogram/pages/ai-design/generating.wxml',null,'进度页面静态内容');
ev('E60','前端程序/miniprogram/pages/ai-design/publish.wxml',null,'投稿页面静态图片与校验数量');

const flows=[];
function flow(group,name,chain,state,assessment,refs,risks=''){flows.push({id:'B'+String(flows.length+1).padStart(2,'0'),group,name,chain,state,assessment,refs,risks});}
flow('身份与授权','访客与功能准入','进入首页/户型库 → 会话检查 → 受限功能跳授权页','A','前端准入与后端授权查询均有接线；不能仅依赖前端跳转控制权限。',['E31','E36']);
flow('身份与授权','微信登录','wx.login → code2session → 账号解析 → 发放会话','D','真实适配器和失败分类、并发账号测试已具备；本轮未使用真实微信临时 code，真机登录待验收。',['E32','E28']);
flow('身份与授权','管理端发码与交付','批量发码 → 加密制品 → 管理员领取 → 用户接收','A','制品下载、状态管理与后台页面已接；实际带外交付给用户是运营动作。',['E34','E30']);
flow('身份与授权','兑换、授权与撤销','输入授权码 → 幂等兑换 → 绑定账号 → 管理员撤销','A','绑定与重复兑换有事务/合同约束；授权撤销后服务重新校验，不只改页面显示。',['E35','E36']);
flow('身份与授权','刷新会话与切换身份','access 过期 → refresh 旋转 → 重放请求 → 清理旧身份页面状态','A','独立刷新期限、会话归属和页面异步竞态已有实现与测试；注销账号的全量吊销编排另见 B47。',['E28']);
flow('身份与授权','昵称、头像和个人偏好','编辑资料 → 上传头像 → 完成扫描 → 白名单更新 → 回显','B','资料读写已接；头像依赖共享资产安全链，受审核实现缺失和覆盖风险影响。',['E37','E03','E15'],'R01,R03');
flow('案例与资产','公司案例生产与上下架','管理端新增 → 上传封面/平面/立面 → 授权 → 发布/换版/下架','B','核心服务与管理表单已接；当前全新种子遗漏 PUBLIC_DISPLAY，且真实审核、资产安全尚不合格。',['E17','E16','E47'],'R01,R03,R08');
flow('案例与资产','首页、列表筛选和详情','首页聚合 → 条件筛选/分页 → 案例详情 → 图片加载','A','客户端请求已接目录服务，公开可见性由后端控制；仍需在修好启动和正式资产配置后跑 HTTP 与真机。',['E17','E31']);
flow('案例与资产','收藏与取消收藏','详情收藏 → 唯一关系持久化 → 我的收藏 → 取消','A','正反向接口、记录页与归属查询已接；本轮合同/客户端测试不代替真机操作。',['E17','E31']);
flow('案例与资产','从户型库引用设计','可引用案例 → GENERATION_REFERENCE 校验 → 创建项目','A','公开展示与生成引用授权分开，能限制可看不可引用的案例；后续生成受 B14 阻断。',['E47','E38']);
flow('案例与资产','用户/管理端资产安全交付','领上传票据 → 直传 → 扫描净化 → ACCEPTED → 下载票据','B','存储适配器已具备，MinIO 正常链可跑；同一上传 URL 可覆盖已验收文件，且无真实内容审核。',['E03','E04'],'R01,R03');
flow('AI 设计','需求采集与项目建档','填写需求/上传草图/引用案例 → 创建设计项目','A','页面与建档服务已接；资料/图片成功落库不代表已经得到真实设计结果。',['E38','E31']);
flow('AI 设计','生成报价、确认与扣点建单','获取生成报价 → 确认 → 幂等创建平面任务 → 点数扣减','A','主生成入口有报价与建单链，任务和账本事务配套；调整入口价格确认缺口见 B18。',['E27','E38','E39','E48']);
flow('AI 设计','真实平面生成','QUEUED → Runtime 领取 → Provider 生成 → 上传 → 结果回写','C','本仓库仅配置、内部协议及编排；外部 Runtime 未提供可运行实现或验收证据，不能认定已生成真实平面。',['E05','E06','E39'],'R02');
flow('AI 设计','平面候选选择','结果隔离校验 → 槽位结算 → 候选晋升 → 用户选平面','B','接收已有合格结果后的内环齐备；前置真实生成缺失，轮询驱动存在提前结算风险。',['E38','E39','E40','E07'],'R02,R18');
flow('AI 设计','立面配置、生成和选择','选定平面 → 风格/张数 → 扣点 → 立面结果 → 选定','B','客户端和领域状态流已接；真实立面生成同样缺 Runtime，不能以静态示例图判定打通。',['E38','E05','E31'],'R02,R17');
flow('AI 设计','结果冻结、历史和再次查看','选平面/立面 → 冻结版本 → 我的方案 → 查看指定版本','A','版本、资产关联、历史只读与指定版本查看已有代码链；以前置存在有效候选为条件。',['E38','E26']);
flow('AI 设计','调整与重新生成','结果页调整 → 新立面任务 → 保留旧版本','B','“调整”实际复用重新生成，未提供细粒度修改；未显示本次准确点数，修订请求未携带价格确认。',['E26','E27'],'R02,R17');
flow('AI 设计','取消、失败与退点','取消申请/失败 → 结算已完成槽位 → 退回未完成点数','B','槽位结算内核有幂等与合同测试；QUEUED 无 Runtime 时未被超时扫描覆盖，正常自动收尾不足。',['E40','E07'],'R02,R18');
flow('AI 设计','挂起任务自动恢复','任务租约到期/进程恢复 → 重领/结算 → 消息/账本对齐','B','租约、fencing、结果去重已建；生产驱动开关与运行时未验收，单轮耦合、排队超时及渐进结果需补。',['E39','E07','E56'],'R02,R18');
flow('预算与报价','设计参数转预算输入','选择结果版本 → 拉取预算参数 → 检查缺失项','A','项目/结果版本关联及缺项提示已接；缺项不会直接当 0 完整报价。',['E38','E42']);
flow('预算与报价','地区和主体/外立面选项','选地区 → 拉取生效目录 → 选择主体与外立面组合','A','地区目录、约束、前端选择状态与错误提示具备；需运营补齐正式地区和单价。',['E41','E42','E31']);
flow('预算与报价','后台预算基础配置','维护地区/物料/价格模板/套餐 → 校验 → 发布可选目录','A','后台预算页面与目录命令已接，合同测试覆盖；正式价目版本、税费/口径需业务审核。',['E41','E30']);
flow('预算与报价','明细预算计算','项目参数+目录版本 → 数量/单价/系数 → 主体/外立面明细','A','金额用整数分/BigDecimal、上限和版本快照已有约束；工程造价准确性需要专业样例校验。',['E42']);
flow('预算与报价','保存、历史和指定修订查看','试算 → 幂等保存 → 历史分页 → 回看不可变快照','A','小程序结果/历史和后端保存、查询接口已接；旧版不能覆盖新冻结事实。',['E42','E43','E31']);
flow('预算与报价','管理员人工补项和修订','项目预算 → 修订草稿 → 调整缺项 → 校验/冻结','A','管理端表单、白名单、跨项目/租户 actor 校验和修订接口具备；该页面过大影响维护。',['E43','E30']);
flow('预算与报价','对外报价发布与撤回','冻结预算修订 → 报价发布 → 用户查看 → 管理员撤回','A','管理端发布/撤回与用户端报价列表/详情已接；须验收发布后快照、撤回后可见性。',['E44','E30']);
flow('预算与报价','旧区间预算兼容','旧入口/记录 → 旧计算模型 → 区间结果','A','兼容服务和页面仍在；不能把旧区间预算当成新明细预算的工程量结果。',['E45','E31']);
flow('投稿与通知','投稿前校验与授权','选择冻结版本 → 公开展示授权 → publication-validations','B','服务端校验和授权设计存在；页面封面/材料数量/校验总数仍含静态展示。',['E46','E47','E60'],'R17');
flow('投稿与通知','提交投稿和查询状态','校验通过 → 幂等 submissions → 我的投稿详情','A','建单、状态读取与记录入口已接；不应承诺提交后立即进入公共户型库。',['E46','E19','E31']);
flow('投稿与通知','审核、退修和重提','管理员审核 → 通过/拒绝/退修 → 用户读原因 → 重提','A','三端接口、审核页面和退修重提逻辑存在；通知消费者质量需单独修复。',['E46','E30'],'R12');
flow('投稿与通知','审核后独立发布','审核通过 → 运营发布 → 案例入库 → 公众可见','A','审核与发布是两个动作，服务层已有分离；小程序文案应与之保持一致。',['E46','E17','E60'],'R17');
flow('投稿与通知','站内消息完整闭环','业务 Outbox → 投递 → 消息列表/未读 → 已读 → 业务跳转','B','读取、已读归属和常见跳转有代码；重复事件不幂等、退款事件缺 userId，退款业务跳转也未覆盖。',['E11','E10','E56'],'R12');
flow('充值与积分','充值方案维护与展示','管理员方案管理 → 上下架 → 小程序套餐列表','A','方案读写、价格和点数快照存在；正式套餐上线前应由业务确认。',['E08','E30']);
flow('充值与积分','充值下单与微信拉起支付','选择套餐 → 建充值单 → 真实预下单 → wx.requestPayment','B','真实支付 Adapter 和客户端调用已接；首次预下单超时后同 key 重试无法恢复。真实资金未验收。',['E08','E33'],'R04');
flow('充值与积分','取消支付、返回订单和继续支付','中断支付 → 我的充值记录 → 原单恢复 → 重领有效支付参数','B','订单查询/结果页有接线；现有记录入口偏查询，服务仅返回存量参数，不能刷新缺失或过期参数。',['E08','E31'],'R04');
flow('充值与积分','支付回调、查单与积分到账','验签/事实校验 → Inbox 去重 → 订单成功 → 点数到账 → 补偿','B','正常通知和重复通知处理有测试；固定前 50 条旧单使补偿饥饿，不能保证丢回调后的最终到账。',['E09','E33','E48'],'R05');
flow('充值与积分','整单退款与积分冲正','管理员申请 → 预留点数 → 渠道退款/查单 → 冲正/释放 → 通知','B','整单退款与冲正内核已实现；默认补偿未启用、真实渠道未验收，成功通知断。已消费后拒绝退款属于当前保守业务规则。',['E10','E29','E33'],'R12,R18');
flow('充值与积分','积分查询与双人调点','用户钱包/流水 → 管理员发起调点 → 另一管理员审批 → 账本','A','账本查询、人工调点和禁止同人审批有服务端权限约束；不替代订单退款。',['E48','E49']);
flow('运营管理','工作台经营概览','管理员登录 → 用户/任务/订单等统计 → 跳转处理','A','统计接口和工作台已接；看板可显示业务数据不代表后台驱动持续健康。',['E53','E30']);
flow('运营管理','C 端用户管理','后台用户列表 → 详情/授权状态 → 相关记录','A','查询链具备；当前不是完整用户停用/注销执行台，缺失能力见 B47。',['E51','E30']);
flow('运营管理','AI 任务运营查看','后台任务列表/详情 → 状态与故障信息','B','可观察任务，但不能用查询页代替真实 Runtime、可靠重试和完整人工恢复流程。',['E52','E07'],'R02,R18');
flow('运营管理','账本/审计异步导出','带筛选创建导出 → Worker → CSV → 票据领取 → 下载','B','支持 POINT_LEDGER/AUDIT_EVENTS；生成时忽略筛选、全量内存处理、RUNNING 无重领及 CSV 公式风险。',['E21','E50'],'R13');
flow('运营管理','业务审计追踪','关键业务操作 → 审计落库 → 后台查询/导出','A','业务审计能力存在；还需验证生产留存、访问授权和外部告警，导出问题单列。',['E50','E57'],'R13,R18');
flow('隐私与客服','阅读政策与版本化同意','用户阅读政策 → 明确同意 → 记录版本 → 受限业务准入','C','后端记录和 API 封装存在；没有完整页面调用链，hasAcceptedCurrentVersion 未接关键业务门禁。',['E18','E19'],'R10');
flow('隐私与客服','用户个人数据导出','用户提出请求 → 身份复核 → 汇总本人数据 → 安全交付','C','只能登记/查询 PENDING，无执行器和交付闭环；与运营 CSV 导出不是同一能力。',['E18','E55'],'R10');
flow('隐私与客服','账号关闭、停用与会话撤销','申请关闭 → 审核/执行 → 处理数据和授权 → 吊销全部会话','C','有请求登记和底层吊销函数，未找到关闭执行编排或后台受理流程。',['E18','E28','E51','E55'],'R10');
flow('隐私与客服','客服咨询与异常申诉','我的/服务 → 客服入口配置 → 可用联系渠道 → 处理反馈','C','服务端配置接口有；客户端仅弹出“联系授权管理员”，未消费配置或发起联系。',['E20','E54'],'R11');
flow('平台底座（抽查）','管理员登录和角色权限','管理端登录 → token → 角色菜单 → 服务端权限校验','B','复用 yudao 安全底座；业务 @PreAuthorize 存在，但误用 local 可开启模拟身份，需有效生产配置验收。',['E12','E13','E30'],'R06');
flow('平台底座（抽查）','系统配置和文件服务管理','管理端系统/基础设施模块 → 配置存储/字典/文件','B','底座仍保留，未逐一认证所有上游模块；隐藏菜单不消除后台接口，样例 SQL 和编辑器依赖需清理。',['E30','E50'],'R15,R16');
flow('平台底座（抽查）','定时处理、异常告警与人工接管','任务/支付/退款/导出 Worker → 重试/死信 → 告警 → 接管','B','有调度和审计代码，但开关默认关闭、执行轮耦合、恢复边界不足；未获得生产告警到人的证据。',['E07','E21','E29','E56'],'R05,R13,R18');
flow('平台底座（抽查）','发布、迁移、备份和回滚','锁定制品 → 环境校验 → 迁移 → 健康检查 → 回滚/数据恢复','D','23 条迁移在隔离库通过，后端可打包；当前干净服务启动失败，无生产恢复演练、负载和正式制品验收证据。',['E16','E23','E58'],'R07,R08,R09,R18');

const risks=[];
function risk(id,priority,title,kind,impact,detail,refs,fix,accept,owner){risks.push({id,priority,title,kind,impact,detail,refs,fix,accept,owner});}
risk('R01','P0','真实内容审核缺实现，完整生产装配不成立','静态确认 + 装配策略测试','所有正式图片上传、AI 产物与公开案例发布的生产准入受阻。',
'生产 profile 的装配守卫禁止 moderation=stub；当前只有 StubContentModerationAdapter，审核结果恒为通过。选择 real 又没有 ContentModerationPort 实现可注入 AssetService。这里缺的是实现，不只是待填写配置。某些任务文档将审核列为后续批次，这能解释进度安排，但不能作为整产品上线的依据。',
['E01','E02','E03'],'补齐真实审核适配器、超时/拒绝/重试状态和审核证据，保持生产禁止 stub 的守卫。','以完整生产配置成功启动；合规样本、不允许内容、超时、供应商异常分别走预期状态；未经审核的资产不能公开或进入候选。','后端资产负责人 + 内容审核接入负责人');
risk('R02','P0','真实 AI Runtime 未交付，付费生成主流程未闭环','代码与配置声明确认；未获得外部运行时证据','核心产品“扣设计点后获得真实方案”无法据当前仓库保证。',
'AiProviderConfiguration/Properties 明确只是前向配置，没有出站生成客户端或业务消费者；内部 claim 仍写入 provider="stub"。编排模块已有租约、fencing、回写和结算，但这些能力不等于生成引擎。本轮没有真实 Provider 产出验收；E2E 的模拟图片注入即使通过，也只能证明接收与结算协议。',
['E05','E06','E39','E40'],'交付独立 Runtime 的源码/制品、版本和部署说明；对齐 Provider 标识、输入/输出合同、鉴权、轮询或回调、重试与任务幂等。','用真实需求完整跑平面→选择→立面→选择→冻结结果；覆盖生成失败、部分成功、取消、超时、重启、迟到重复结果，核对实际成本和点数。','AI Runtime 负责人 + 后端编排负责人');
risk('R03','P0','原上传 URL 可覆盖已扫描通过的资产','本轮隔离 PostgreSQL + MinIO 动态复现','绕过资产内容、类型、SHA 校验及净化；ACCEPTED 状态可能对应未审核的实际字节。',
'上传和净化后的文件共用 objectKey；客户端原预签名 PUT 在有效期内仍然可用。探针先传合法 PNG 并完成扫描，再用同一 URL PUT 无害非图片标记，HTTP 返回 200；之后正常下载票据取回标记，而数据库仍为 ACCEPTED。真实腾讯 COS 未被调用；复现验证的是现有 S3 兼容适配器与应用对象生命周期组合。',
['E03','E04'],'将客户端可写的原始上传键与服务端独占的已审核键分开，或绑定不可变对象版本；下载和引用只指向该净化版本。配套写权限、过期、校验和、大小限制及孤儿对象清理。','旧上传 URL 重放、扫描同时覆盖、完成后覆盖都不能改变已审核下载内容；ACCEPTED 记录的摘要与可下载字节持续一致。','后端资产负责人');
risk('R04','P0','首次预下单失败后原充值单无法恢复','本轮故障注入动态复现','用户遇到网络/渠道超时可能留下无支付参数的 CREATED 订单，继续支付无法完成。',
'createOrder 先提交 CREATED 订单，再事务外请求渠道；同幂等键重试提前返回既有订单，不重做预下单。getPayParams 只取现有参数，recoverHangingOrders 不扫 CREATED。探针首次模拟超时，重试及恢复后仍 CREATED，渠道调用次数为 1，支付参数为空。已有参数过期也没有重新预下单刷新链；记录页主要进入查询结果页。',
['E08'],'将“幂等建单”和“确保存在有效预支付参数”分离；持久化预下单尝试与恢复状态，按稳定 orderNo 安全重试。补齐原单继续支付入口和参数有效期处理。','首次超时、响应丢失、客户端重开、参数过期、并发重试均恢复同一订单；不创建重复应收、不重复扣款；真实微信客户端能够继续支付。','支付后端负责人 + 小程序负责人');
risk('R05','P0','支付补偿被前 50 条旧订单长期占满','本轮动态复现','丢失成功回调的较新订单可能长期已付款却不到账。',
'recoverHangingOrders 固定按 id 取前 50 条 PENDING/UNKNOWN；reconcile 对渠道非 SUCCEEDED 返回而不持久化 CLOSED/FAILED 等终态。探针放入 50 条渠道已关闭但本地仍 PENDING 的旧单，再放入已支付的新单，连续两轮扫描后新单仍 PENDING/NOT_READY。即使未来打开驱动，也不能解决该队列饥饿。',
['E09'],'持久化渠道终态；实现公平游标/可重试时间、退避与单笔故障隔离；同时覆盖 CREATED 和已支付未履约等修复状态，并提供异常待办。','至少 50 条旧终态/毒丸订单在前时，后续成功订单仍在规定补偿时间内到账且只到账一次；重启和并发扫描结果相同。','支付后端负责人');
risk('R06','P1','local 配置包含模拟后台身份，生产守卫未覆盖有效安全配置','静态确认，风险取决于实际部署配置','若生产混入 local 且 mock 开关生效，可绕过正常凭据构造后台登录身份。',
'application.yaml 默认 local；application-local.yaml 开启 yudao.security.mock-enable。TokenAuthenticationFilter 在开关打开时接受 mockSecret+用户编号。生产策略仅识别 prod/production 并禁止 zsdev，没有强制 mock-enable=false 或禁止 local。local 还暴露全部 Actuator 端点类型；pg 对敏感配置值有隐藏，不应误报所有值都公开。未检查线上网关、实际 profile 或公网可达性。',
['E01','E12','E13'],'制作独立生产配置，启动守卫验证最终生效的 mock、种子、开发内容端点及敏感端点开关；仅暴露需要的健康指标并限制访问。','以发布用完整环境启动并保存脱敏配置证据；模拟 token 和开发端点请求失败；真实管理员权限回归通过。','平台后端负责人 + 运维');
risk('R07','P1','小程序仍为开发地址，正式发布配置和包体未验收','发布静态检查失败 + 源文件体积统计','当前制品不能作为正式小程序上线制品；地址、环境分支或包体会阻断真实使用。',
'config.js 仍为 env=dev、http://localhost:48080；本轮 check:release 报开发地址及明文 HTTP 两项。AppID 已统一为正式值，不是 touristappid。仅改 URL 为 HTTPS 而不改 env，资产工具仍可能走开发内容端点。28 个页面都在主包；直接静态引用的 11 个 v12 图资源共 3,760,189 字节，首页大图约 1.96 MB；源目录总计 7,203,822 字节。以上不是微信编译上传包大小，不能直接断言上传一定失败。',
['E14','E15','E25','E31'],'提供构建时环境配置，正式域名和合法域名白名单，发布门禁同时验证 env 与资产路径；压缩大图、拆分必要分包，取得开发工具真实包分析和真机记录。','正式制品 check:release 通过；开发工具可上传；生产登录/上传/下载/支付各域名正常；冷启动与弱网符合约定性能目标。','小程序负责人 + 运维');
risk('R08','P1','干净环境种子数据与新发布规则冲突，HTTP 验收无法启动','本轮全新隔离环境实测失败','新同事初始化、CI E2E 或空库联调可能启动即失败，旧数据环境会掩盖回归。',
'使用本轮后端 jar 和专属 PostgreSQL/Redis，启动 local,pg,zsdev 时，DevDataSeeder 创建示例资产和案例后调用 publish；缺 PUBLIC_DISPLAY 授权，被 CaseCatalogService.requireCompanyPublishable 拒绝，Spring 初始化失败。因此登记的 54 项 HTTP 检查本轮执行 0 项，不能沿用历史 54/54。测试容器及卷已清理。生产不应开启种子，因此这不是生产种子运行实测。',
['E16','E17'],'按真实业务要求给种子建立完整授权和资产关系，或让种子保持合法草稿状态；确保空库/已有库都能幂等启动。','从零创建数据库和对象目录启动成功，再完成全部 54 项 HTTP 检查；二次启动不重复造数，不降低发布校验。','后端集成负责人');
risk('R09','P1','默认质量门禁漏测，当前扩展验证和样式检查非全绿','本轮测试/检查 + CI 脚本静态确认','“合同测试通过”可能掩盖真实适配器测试未运行、注册表漂移及发布配置未校验。',
'默认后端只选 *ContractTest；本轮扩大到 *Test 共 528 例，527 通过，1 个错误码名称注册表测试失败。329 个合同用例全部通过。真实验收的 real-acceptance 默认排除应保留；问题是普通新增测试也被命名过滤漏掉。管理端 Stylelint 10 项错误，Prettier 1 文件不合规。当前 pnpm 10.34.5 与要求 11.19.0 不同，doctor 未通过；本轮直接调用已有依赖完成等价子检查，未证明冻结安装/标准总门禁成功。',
['E23','E24','E25','E58'],'默认选入所有无需真实消费的单元/集成测试；单独受控运行真实验收。同步错误码合同、修复格式，统一 pnpm/JDK，发布流水线强制正式配置检查和干净启动。','规定的 pnpm 11.19.0、JDK17 环境冻结安装与标准 full 门禁全绿；无隐式跳过普通安全/适配器测试；发布分支规则可核验。','三端负责人 + CI 负责人');
risk('R10','P1','隐私同意、个人数据导出和账号关闭只有部分基础设施','静态全链追踪确认','用户权利和账号生命周期未形成可操作、可完成的业务流程。',
'PrivacyService 有版本同意记录以及 EXPORT/CLOSE_ACCOUNT 请求登记/查询，但没有完成请求的执行编排；hasAcceptedCurrentVersion 没有接入关键业务校验。小程序仅有 API 封装，未发现相应页面调用。revokeAllForAccount 存在而无关闭业务调用，后台用户控制器主要只读。不能把运营流水 CSV 导出当成用户个人数据导出。此项是产品与技术完整性判断，不构成法律合规认证。',
['E18','E19','E28','E51','E55'],'提供政策展示和同意版本链、个人请求入口、运营受理/自动执行、身份复核、安全交付、数据保留与删除规则及全量会话吊销。','用户可申请并看到最终状态；导出仅包含本人数据；账号关闭后旧 access/refresh 及授权失效，保留/删除行为有可追踪记录。','产品负责人 + 身份后端 + 小程序');
risk('R11','P2','客服配置接口未被客户端实际使用','静态前后端追踪确认','充值、授权或生成异常时，用户没有已接通的产品内联系入口。',
'后端 AppSupportEntryController 和 api.getSupportEntry 已存在，但 profile/services 的 contact 仅弹“请联系授权管理员”，首页个人区同类行为也没有真正使用客服配置。接口存在不能视为客服流程完成。',
['E20','E54'],'消费客服入口配置，提供已配置的真实联系渠道；未配置时明确处理，并提供业务单号用于定位。','从未授权、已授权及订单异常三个场景可进入有效客服渠道，运营能凭业务编号处理反馈。','小程序负责人 + 运营');
risk('R12','P1','消息重复投递不幂等，退款成功通知缺用户标识','本轮使用真实业务事件动态复现','用户可能收到重复到账通知，成功退款却收不到站内通知。',
'MessageService.deliver 每次生成新消息 id，没有 source_event_id 唯一约束。同一个真实 ORDER_CREDITED Outbox 事件投递两次，生成两条消息。真实 requestRefund→confirmReversal 生成的 ORDER_REFUND_REVERSED payload 只有 refundId/orderId，没有 userId，消费者直接抛异常；探针确认 point_reversal_state=REVERSED，退款本身成功，通知失败。退款 biz_type 的前端跳转也需补齐。',
['E10','E11','E56'],'按源事件+接收人做数据库幂等约束；统一事件载荷合同补齐 userId，补消息文案、业务跳转和死信重放。','到账/退款/审核/任务等事件重复和乱序投递只产生预期消息；退款成功后用户可读并跳转到正确业务记录。','后端事件负责人 + 小程序');
risk('R13','P1','运营导出忽略筛选，崩溃恢复及 CSV 安全不足','静态数据流确认','导出范围与操作意图不一致，大数据量占用内存，任务可能挂起，表格打开存在公式解释风险。',
'Worker 读出 filter_snapshot 但未传入 process；账本和审计查询直接取全表并在内存拼 CSV。PENDING→RUNNING 有 CAS，但没有租约和 RUNNING 过期重领。CSV 仅处理逗号、引号、换行，没有处理用户可控文本前导 =、+、-、@。已有下载票据归属与过期控制，不将本项误报为任意用户直接下载他人导出。',
['E21','E50'],'按授权后的筛选快照查询，分页/流式导出；增加租约、重试、失败终态和清理；对文本单元格做防公式处理。','按用户/时间筛选的行集准确；中断后能恢复；大数据量内存受控；危险前导文本作为文本显示；下载归属/过期测试通过。','管理后端负责人');
risk('R14','P1','请求体限制晚于完整缓存，资源限额需要前置','静态路径确认，未进行破坏性压测','匿名大 JSON 可在控制器限额生效前占用堆内存；上传和高成本任务也需要独立资源治理。',
'CacheRequestBodyFilter 先构造 Wrapper，Wrapper 使用 ServletUtils.getBodyBytes 读取完整 JSON。即使微信通知控制器后续检查 64 KiB，也无法阻止此前缓存。现有登录、兑换等局部限流值得保留，但不证明上传、生成、预算全局并发及存储配额齐备。生产网关限额未核验。',
['E22','E03','E39'],'在网关和最前置读取层实施有界请求体，按接口类型设置上传/回调限额；为用户、任务和存储配置并发与总量上限。','超限请求在完整读入前被拒，普通请求不受影响；受控负载验证内存、连接池、队列和费用上限。','平台后端 + 运维');
risk('R15','P1','底座 SQL 含疑似云凭据字面值','当前跟踪文件高置信格式扫描；未核验有效性','若为仍有效凭据，会造成云资源访问风险；无效样例也会污染初始化配置和密钥扫描。',
'扫描 2,027 个当前跟踪文本文件，8 份数据库底座 SQL 命中 AKID 格式；PostgreSQL ruoyi-vue-pro.sql 第 468 行 infra_file_config JSON 同时有 accessKey/accessSecret 字段。本报告不披露值、不尝试登录云账号、不判定凭据仍然有效；未覆盖 Git 历史、所有本地未跟踪文件或运行环境的密钥审计。',
[],'确认来源和归属，清理样例配置为明确占位；如果仍有效，立即吊销/轮换并评估历史传播。将密钥扫描纳入提交与制品门禁。','当前源码、初始化 SQL、发布制品不携带实际凭据；有效密钥已完成轮换，历史风险有责任人和处理记录。','安全/运维 + 仓库维护者');
risk('R16','P1','管理端生产依赖命中 12 条安全公告','本轮 pnpm 生产依赖审计 + 官方公告适用条件核对','存在需要治理的已知依赖风险，但公告命中不等于已证明产品可被利用。',
'605 个生产依赖审计命中 2 高危、10 中危，集中于 wangeditor 4.7.15、axios 1.16.0、fast-xml-parser 4.5.7。旧 wangeditor 来自 form-create/designer 传递依赖，不能只看直接引入了新编辑器就认为已移除。Axios 高危明确针对 Node HTTP adapter 且有原型污染和配置克隆等前置条件，本轮管理 SPA 的浏览器可利用性未被证明。后端 Maven、容器镜像和小程序 vendored 依赖未完成等价 SCA，不可称全栈无漏洞。',
[],'升级受影响且可升级的包并回归；替换或排除旧编辑器传递链，核对实际可达路径；建立 Maven/镜像/小程序组件的 SBOM 与持续审计。','修复公告命中或提供有期限的不可达性证据；版本变更后类型、构建、富文本和网络请求回归通过。','管理端负责人 + 安全/依赖维护者');
risk('R17','P2','部分页面展示仍为样稿，调整动作缺准确价格确认','静态页面与接口核对','用户可能误解当前生成阶段、投稿材料和实际调整能力；付费操作价格提示不完整。',
'generating.wxml 含固定阶段、平面/风格及示例图；publish.wxml 含静态封面、材料数量和校验总数。结果页 adjust 直接调用 regenerate，新建两个立面候选，无细粒度编辑；价格确认对象未随修订请求传递，PricingPort 对空 confirmation 兼容放行。这是产品呈现和价格确认缺口，不应误写成接口必然报错。',
['E26','E27','E59','E60'],'进度、候选和投稿清单均绑定服务端事实；明确“重新生成”名称与能力范围，获取本次报价并确认后扣点，后台校验报价版本。','不同项目/风格/候选数展示真实；部分成功和失败不显示完成；用户确认准确点数后才建立新收费任务；审核通过与正式发布文案一致。','小程序负责人 + 产品负责人');
risk('R18','P1','生产调度与恢复边界未达到可靠运行要求','静态确认 + 部分隔离复现；生产运维未验收','关闭或异常的处理器会导致任务、消息、支付、退款和导出停在中间状态。',
'pg 的 driver-enabled 默认 false，退款 recovery 默认 false 且未见仓库配置主动打开；导出 Worker 默认也为 false。ZhongshuJobDriver 将校验/结算/晋升/投递/支付恢复放在一个 try 内，前序异常可跳过后续步骤。结算扫描不含 QUEUED，且有任一 ACCEPTED 就可结算，真实 Runtime 分批写结果时可能提前按部分成功结算。日志/审计和 Outbox 重试存在，但没有本轮生产告警、值守、容量和恢复演练证据。',
['E07','E21','E29','E56'],'将职责和失败隔离，明确各状态超时与每槽终态合同；发布配置显式启用所需 Worker，增加队列年龄/卡单/死信告警及人工恢复手册。','无 Runtime、分批结果、毒丸事件、重启、多实例、渠道短故障均最终收敛；定时器健康和业务积压能告警到责任人，恢复时不重复扣点/到账/退款。','后端编排/支付/运维共同负责');

const inv=json('inventory.json'), scope=json('scope-snapshot.json'), backend=json('backend-test-summary.json');
const statusNames={A:'代码链齐（有条件）',B:'部分打通',C:'缺执行链',D:'待外部验收'};
const counts=Object.fromEntries(Object.keys(statusNames).map(k=>[k,flows.filter(f=>f.state===k).length]));
const priorityCounts=Object.fromEntries(['P0','P1','P2'].map(k=>[k,risks.filter(r=>r.priority===k).length]));
const miniPages=JSON.parse(read('前端程序/miniprogram/app.json')).pages;
const adminRoutes=[...read('管理后台/src/router/modules/zs.ts').matchAll(/component:\s*\(\)\s*=>\s*import\('([^']+)'\)/g)].map(m=>m[1]);
const backendGroups=Object.fromEntries(['identity','commerce','ai-orchestration','design','server'].map(module=>{const rows=backend.rows.filter(r=>module==='server'?!r.name.includes('.module.'):r.name.includes('.module.'+module.replaceAll('-','')+'.'));return [module,{classes:rows.length,tests:rows.reduce((s,r)=>s+r.tests,0),failures:rows.reduce((s,r)=>s+r.failures,0)}];}));
const contractRows=backend.rows.filter(r=>r.name.endsWith('ContractTest'));
const verification={auditDate:'2026-09-13',head:scope.head,workingTree:true,
  environment:{node:'24.19.0',pnpmObserved:'10.34.5',pnpmRequired:'11.19.0',javaUsed:'OpenJDK 25.0.3 (Android Studio JBR)',javaTarget:'17',docker:'29.7.2'},
  mini:{files:18,passed:205,failed:0,log:'mini-tests.log'},admin:{files:10,passed:76,failed:0,typecheck:'pass',eslint:'pass',stylelintErrors:10,prettierFiles:1,build:'pass'},
  backend:{classes:backend.classes,passed:backend.tests-backend.failures,failed:backend.failures,errors:backend.errors,skipped:backend.skipped,contractClasses:contractRows.length,contractTests:contractRows.reduce((s,r)=>s+r.tests,0),modules:backendGroups,package:'pass'},
  e2e:{registered:54,executed:0,result:'blocked_before_http_suite',reason:'DevDataSeeder demo case missing PUBLIC_DISPLAY grant',isolatedResourcesRemoved:true},
  probes:{count:5,expectedDefectsReproduced:5,liveChannelsUsed:false,log:'risk-probes.log'},
  dependencyAudit:{productionDependencies:605,critical:0,high:2,moderate:10,scope:'admin pnpm only'},
  releaseCheck:{violations:2,reason:'localhost and plain HTTP'},
  standardFullGate:'not passed; pnpm mismatch, lint failures, expanded backend failure, clean startup failure',
  concurrentConfigurationFollowup:{classes:2,tests:34,passed:34,failed:0,log:'backend-config-followup.log',scope:'Later additive AI provider configuration only; original 528-test run and startup failure are retained as earlier evidence'},
  notExecuted:['real WeChat identity','real COS acceptance','real WeChat money flow','real AI Runtime','Mini Program developer-tool compilation/upload','real-device UI automation','load/penetration test','production backup restore','Maven/container SCA']};
fs.writeFileSync(path.join(out,'verification-summary.json'),JSON.stringify(verification,null,2)+'\n');
fs.writeFileSync(path.join(out,'业务流程矩阵.json'),JSON.stringify(flows,null,2)+'\n');
fs.writeFileSync(path.join(out,'风险清单.json'),JSON.stringify(risks,null,2)+'\n');
fs.writeFileSync(path.join(out,'source-evidence.json'),JSON.stringify(evidence,null,2)+'\n');
const csv=(rows,keys)=>'\uFEFF'+[keys.join(','),...rows.map(r=>keys.map(k=>'"'+String(r[k]??'').replaceAll('"','""')+'"').join(','))].join('\r\n')+'\r\n';
fs.writeFileSync(path.join(out,'业务流程矩阵.csv'),csv(flows.map(f=>({编号:f.id,分组:f.group,业务流程:f.name,处理链:f.chain,状态:statusNames[f.state],分析:f.assessment,风险:f.risks,源码证据:f.refs.join(',')})),['编号','分组','业务流程','处理链','状态','分析','风险','源码证据']));
fs.writeFileSync(path.join(out,'风险整改清单.csv'),csv(risks.map(r=>({编号:r.id,优先级:r.priority,问题:r.title,证据类型:r.kind,影响:r.impact,修复建议:r.fix,验收条件:r.accept,建议责任角色:r.owner,状态:'待修复或补齐证据'})),['编号','优先级','问题','证据类型','影响','修复建议','验收条件','建议责任角色','状态']));

const sourceAppendix='# 源码证据索引\n\n审计基于 2026-09-13 当前工作树；行号在生成报告时从对应源码定位。点击编号可打开代码。未包含疑似凭据原文。\n\n|编号|核查点|文件及行号|\n|---|---|---|\n'+evidence.map(e=>`|${e.id}|${e.description}|${link(e.path,e.path+':'+e.line,e.line)}|`).join('\n')+'\n';
fs.writeFileSync(path.join(out,'源码证据索引.md'),sourceAppendix);
const flowSections=[...new Set(flows.map(f=>f.group))].map(group=>`### ${group}\n\n|编号/流程|前端 → 后端 → 管理/结果|结论|逐项判断与断点|依据|\n|---|---|---|---|---|\n`+flows.filter(f=>f.group===group).map(f=>`|${f.id} ${f.name}|${f.chain}|${statusNames[f.state]}|${f.assessment}${f.risks?' 关联 '+f.risks+'。':''}|${cite(...f.refs)}|`).join('\n')).join('\n\n');
const deps=json('dependency-summary.json');
const riskSections=risks.map(r=>`### ${r.id} · ${r.priority} · ${r.title}\n\n**证据：${r.kind}。影响：${r.impact}**\n\n${r.detail}\n\n${r.refs.length?'源码：'+cite(...r.refs)+'。':r.id==='R15'?'证据：'+artifact('secret-format-scan.json','脱敏扫描结果')+'。':'证据：'+artifact('dependency-summary.json','依赖审计摘要')+'。'}\n\n修复方向：${r.fix}\n\n验收条件：${r.accept}\n\n建议责任角色：${r.owner}。`+
  (r.id==='R03'?'\n\nS3 官方说明预签名 URL 可在有效期内重复使用，同键上传会替换现有对象；这支持上述生命周期风险判断，但不替代腾讯 COS 生产验收。[AWS 预签名 URL 文档](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)。':'')+
  (r.id==='R14'?'\n\n本项以请求体、并发、内存和存储等限制检查资源消耗边界，参考 [OWASP API4:2023](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)。':'')+
  (r.id==='R16'?'\n\n公告依据：[wangeditor XSS（公告无修复版本）](https://github.com/advisories/GHSA-g7mw-5cq6-fv82)、[Axios Node HTTP adapter（修复版本 1.18.0）](https://github.com/advisories/GHSA-gcfj-64vw-6mp9)、[fast-xml-parser XMLBuilder（修复版本 5.7.0）](https://github.com/advisories/GHSA-gh4j-gqv2-49f6)。升级建议基于本轮锁文件和审计时公告，实施时应再次核对版本及兼容性。':'')).join('\n\n');

const report=`# 众墅之家设计平台：上线前综合分析报告

审计日期：2026-09-13（Asia/Shanghai）  
审计对象：小程序前端、业务后端、运营管理端及相关发布/数据/运行链。  
代码基线：分支 \`${scope.branch}\`，HEAD \`${scope.head}\`，**包含当时未提交变更**。  
结论：**当前不满足“完整小程序正式上线，尤其是付费 AI 设计业务”的准入条件。**

## 1. 决策摘要

项目已经形成可维护的三端业务骨架，大量页面、接口、事务和合同测试已存在。当前阶段更接近“主要业务内核已开发，正在接入真实服务和收敛异常链路”，尚不是“只剩部署”。预算配置、明细试算、修订与报价的代码链相对完整；微信身份、COS、微信支付真实适配器也已新增，不能再沿用早期看板将它们一概归为未开发。

决定上线结论的五个 P0 是：**真实内容审核实现缺失、真实 AI Runtime 未交付、上传后可覆盖已验收资产、预下单失败无法恢复、支付补偿队列饥饿**。其中后三项已经在本轮隔离环境动态复现。除此以外，还发现新环境启动失败、消息链路断点、隐私/注销流程缺执行器、开发配置发布风险和门禁漏测等问题。

本报告共登记 **${risks.length} 组问题（${priorityCounts.P0} 组 P0、${priorityCounts.P1} 组 P1、${priorityCounts.P2} 组 P2）**，拆解 **48 条产品业务流程 + 4 条平台运行流程**。流程统计为：代码链齐（有条件） ${counts.A} 条、部分打通 ${counts.B} 条、缺执行链 ${counts.C} 条、待外部验收 ${counts.D} 条。**这些计数不是完成率，业务复杂度不等权，“代码链齐”也不代表正式环境已经通过验收。**

|发布范围|本轮结论|理由|
|---|---|---|
|完整正式版 / 付费 AI 设计|不准入|核心真实执行链缺失，同时存在资产与支付可复现缺陷|
|内部隔离联调|修复干净启动后可继续|适合验证已实现领域逻辑；需明确使用替身和模拟产物|
|仅案例展示或仅预算的裁剪版本|未单独给出准入|可以另行确定发布范围，但仍需处理共享身份、资产、隐私、正式配置和该范围的真机验收，不能自动继承本报告放行|

## 2. 审计范围、方法与证据边界

本轮以当前源码为主，核对页面注册、客户端 API、管理路由、Controller、服务状态机、数据库迁移、后台 Worker、配置、测试和 CI。范围包含 ${miniPages.length} 个小程序页面、${adminRoutes.length} 条众墅管理业务页面路由、${inv.controllerFiles} 个业务 Controller 的 ${inv.endpoints.length} 个映射，以及 ${scope.migrations.length} 条前向迁移。系统/基础设施底座进行了关键安全与运行路径抽查，未声称逐行审阅全部上游模块。接口清单只表示映射存在，权限仍须结合类级及服务级校验。详见 ${artifact('接口清单.md')}、${artifact('源码证据索引.md')}。

初始工作树有 ${scope.status.length} 个 Git 状态条目，其中包括未跟踪的新代码、测试和历史文档；因此报告不能只由 HEAD 复现，必须结合 ${artifact('scope-snapshot.json','工作树快照与文件 SHA-256')}。没有把其他 .claude 工作树或历史报告当成本轮代码。

审计期间另有 4 个文件发生并行更新：后端 .env.example、application-pg.yaml、application-zsdev.yaml 和项目看板。核对后确认是新增 AI Provider 配置声明及冻结决策，未新增生成消费者。对更新后的配置另跑装配策略与 PG 配置测试 **34/34 通过**；原 528 用例和干净启动日志仍按较早配置保留，不冒称已全量复测更新后制品。变更摘要与最终哈希见 ${artifact('concurrent-config-review.json')}，补充日志见 ${artifact('backend-config-followup.log')}；这些更新不消除真实 Runtime/审核及已复现风险。

证据按强度区分：① 本轮执行日志与独立复现；② 源码直接确认；③ 已实现但真实环境待验收。历史看板、历史 54/54、模拟渠道成功和测试声明数量，只用于背景，不当成本轮验收结果。本轮只新增审计材料与隔离探针，没有修复业务代码，也没有调用真实微信/COS/支付/AI 服务或产生真实资金交易。

未覆盖的关键外部项：真实微信后台配置、商户/云资源权限、实际生产环境与网关、真机完整操作、渗透与容量压测、数据库备份恢复演练、远端 CI 分支保护、后端 Maven/镜像全量漏洞扫描。缺证据的项目一律列为待验收，不推定通过。

## 3. 技术架构与三端开发进度

### 3.1 当前实际架构

~~~mermaid
flowchart LR
  MP[微信小程序 原生 JS/WXML/SCSS] --> APP[App API]
  ADMIN[管理端 Vue3/TypeScript] --> ADM[Admin API 与权限]
  APP --> MONO[Spring Boot 模块化单体]
  ADM --> MONO
  MONO --> ID[identity 身份与授权]
  MONO --> DES[design 案例/资产/项目/预算/投稿]
  MONO --> COM[commerce 计价/点数/充值/退款]
  MONO --> AI[ai-orchestration 编排与结算]
  ID --> PG[(PostgreSQL 同一业务库)]
  DES --> PG
  COM --> PG
  AI --> PG
  MONO --> REDIS[(Redis 限流等)]
  DES --> COS[COS 适配器 已实现待真实验收]
  ID --> WX[微信身份 适配器已实现]
  COM --> PAY[微信支付 适配器已实现]
  AI -.内部签名协议.-> RT[外部 AI Runtime 未交付证据]
  DES -.审核端口.-> MOD[真实内容审核 未实现]
  PG --> WORK[定时驱动 / Outbox / 导出 / 补偿]
~~~

后端目标是 Java17、Spring Boot 3.5.15、Maven，复用 yudao/ruoyi 的安全、系统、基础设施底座。四个业务模块通过接口端口协作，在同一个 PostgreSQL 数据源上使用 JDBC、参数化 SQL、TransactionTemplate 和数据库约束。Outbox/Inbox 用于业务事件与外部通知可靠处理。**这是模块化单体，不是已拆分的微服务体系。** 当前结构适合该阶段，优先补齐可靠性和真实服务，无需为了上线先改造成微服务。

小程序是原生 JS/WXML/SCSS + TDesign，使用请求封装、授权保护、会话刷新、资产工具和页面状态组件。管理端是 Vue3 + TypeScript + Vite 8.1.4 + Element Plus/Pinia，众墅路由叠加在较完整的系统/基础设施底座之上。数据隔离应按当前单公司部署来评估：预算有专门的项目/actor/租户约束，但不能由底座有 tenant-id 就推断所有自有 JDBC 表支持通用多租户隔离。

### 3.2 代码规模与工程完整度

|范围|当前代码量（物理行）|实际进度判断|
|---|---|---|
|小程序自有源码|${inv.metrics.miniOwn.files} 文件 / ${inv.metrics.miniOwn.lines.toLocaleString('en-US')} 行；${miniPages.length} 页面|大多数页面已请求真实业务接口；发布配置、生成/投稿展示和权利客服流程未完成|
|管理端众墅 views/api|${inv.metrics.adminBusiness.files} 文件 / ${inv.metrics.adminBusiness.lines.toLocaleString('en-US')} 行；${adminRoutes.length} 页面路由|运营主模块、预算与支付操作面已搭建；导出、任务恢复和完整用户生命周期不足|
|四个后端业务模块 src/main|${inv.metrics.backendBusiness.files} 文件 / ${inv.metrics.backendBusiness.lines.toLocaleString('en-US')} 行|领域模型与事务实现较充分；真实 Runtime/审核、支付异常和生产调度仍有缺口|
|众墅基础设施实现|${inv.metrics.backendInfra.files} 文件 / ${inv.metrics.backendInfra.lines} 行|端口、审计、票据和可靠事件底座已存在；消费者幂等需收敛|
|聚合服务源码/迁移|${inv.metrics.backendServer.files} 文件 / ${inv.metrics.backendServer.lines} 行|启动装配、安全日志守卫和过渡 Worker 已存在；生产配置与干净启动未过|

物理行包括注释、SQL 和回滚 SQL；小程序不含 vendored TDesign；管理端只统计众墅 views/api，不含整个底座。行数只是规模，不等于可用性、质量或覆盖率。

|业务能力|小程序|后端|管理端|上线成熟度|
|---|---|---|---|---|
|身份与授权|登录/兑换/刷新/资料已接|真实微信 Adapter + 账号/授权事务|发码、授权管理、用户查询|代码链较齐，真实微信和关闭账号链待补|
|案例与资产|列表/详情/收藏/上传已接|目录、权益、扫描与 COS Adapter|创建、上下架、审核发布|共享资产安全和真实审核阻断|
|AI 设计|配置、轮询、候选、版本页面齐|编排、扣点、回写、结算齐；生成引擎缺|任务查询与案例审核齐|核心真实业务未闭环|
|预算与报价|输入/选项/明细/保存/历史齐|计算、快照、修订、报价齐|配置、补项、冻结、报价齐|相对最完整；需新环境、真机和专业造价样例验收|
|充值与积分|支付拉起/钱包/记录齐|真实微信支付和退款适配器齐|套餐、订单、退款、调点齐|正常路径有测试，异常恢复阻断|
|投稿与消息|投稿/记录/消息入口齐|审核发布齐；消息事件有断点|审核、退修、发布齐|部分打通|
|隐私与客服|缺完整入口/动作|部分只登记请求|缺受理/执行闭环|未完成|

## 4. 本轮验证结果

|检查|本轮结果|结论与限制|原始证据|
|---|---|---|---|
|小程序 Node 测试|18 文件，205/205 通过|逻辑测试；不是开发工具编译或真机 E2E|${artifact('mini-tests.log')}|
|管理端 Node 测试|10 文件，76/76 通过|表单/API/逻辑测试，不等于浏览器全流程|${artifact('admin-tests.log')}|
|管理端类型/ESLint|通过|直接调用当前已安装依赖的等价命令|${artifact('admin-types.log','类型日志')}、${artifact('admin-eslint.log','ESLint 日志')}|
|管理端 Stylelint|失败：10 项|集中在 case/create.vue、case/index.vue 的空行及属性顺序|${artifact('admin-stylelint.log')}|
|管理端 Prettier|失败：1 文件|src/api/zs/index.ts 不合规|${artifact('admin-prettier.log')}|
|管理端生产构建|通过|最大主 chunk 2,216.02 kB，触发大包警告；非整站实测加载耗时|${artifact('admin-build.log')}|
|后端扩展 *Test|47 类，527/528 通过；1 失败，0 error/skip|错误码注册表精确名称不一致；默认 real-acceptance 未开启|${artifact('backend-test-summary.json')}、${artifact('backend-tests-jbr25.log','完整日志')}|
|其中合同测试|35 类，329/329 通过|是上述 528 的子集，不能再相加|${artifact('backend-test-summary.json')}|
|后续配置专项复核|2 类，34/34 通过|针对审计期间新增配置；与原测试有重叠，不能计为 562 个独立用例|${artifact('backend-config-followup.log')}|
|后端打包|25 模块构建成功|skipTests 打包与测试分开评价|${artifact('backend-package.log')}|
|Flyway 空库迁移|23 条通过|隔离 PostgreSQL17，不能证明生产历史库升级/回滚演练通过|${artifact('risk-probes.log')}|
|全新环境 HTTP E2E|启动阻断；登记 54 项，执行 0 项|DevDataSeeder 缺公开授权，不存在本轮 54/54|${artifact('e2e-backend.stdout.log')}、${artifact('e2e-cleanup.log')}|
|独立风险复现|5/5 个预期缺陷被复现|探针 exit 0 表示缺陷被证实，绝不表示产品通过|${artifact('AuditRiskProbes.java')}、${artifact('risk-probes.log')}|
|管理端生产依赖审计|12 公告：2 高、10 中|605 生产依赖；只覆盖管理端依赖树|${artifact('dependency-summary.json')}|
|小程序 release check|失败：2 项|localhost + 明文 HTTP；开发期可以预期，发布必须修正|${artifact('mini-release-check.log')}|
|标准全量门禁|未通过|pnpm 版本不符，且 lint/扩展测试/干净启动有真实失败|${artifact('doctor.log')}、${artifact('verification-summary.json')}|

后端按模块统计：identity 71/71、commerce 109/109、ai-orchestration 23/23、design 195/195、server 129/130。注册表失败位于 ${cite('E24')}；不要通过删除断言或缩小测试范围掩盖合同漂移。

本地 Node 为 24.19.0；pnpm 是 10.34.5，仓库要求 11.19.0。PATH 没有 Java，本轮使用 Android Studio 自带 OpenJDK 25.0.3 完成离线测试/打包；目标发布运行时是 JDK17，因此仍需在规定环境复跑。Docker 29.7.2 可用，测试使用独立 PostgreSQL17 与 MinIO；HTTP 启动尝试使用专属 PostgreSQL/Redis 容器，未连接已有业务库。未更改依赖锁文件或安装工具链来伪造标准门禁通过。

测试资产脚本中的静态声明数量（小程序 210、管理端 70）与 Node 运行器实际 205/76 是不同口径，测试基线检查通过不代表运行用例数量一致。本报告统一以实际运行器结果为准。没有代码覆盖率报告，不能把 205、329 或 528 解释为百分比覆盖率。

### 4.1 五项动态复现的最小证据

|探针|输入/动作|观测结果|证明范围|
|---|---|---|---|
|COS_OVERWRITE|合法 PNG 完成扫描后，重放同上传 URL 写无害非图片标记|PUT 200；数据库 ACCEPTED；下载得到替换字节|现有适配器 + 资产生命周期安全缺陷，非生产云实测|
|PREPAY_RECOVERY|首次预下单故障；原 key 重试并运行恢复|CREATED；渠道调用 1 次；无 payParams|预下单恢复断点|
|PAYMENT_SCAN_STARVATION|50 条旧 CLOSED 渠道订单 + 1 条新已支付订单；扫描两轮|新单 PENDING/NOT_READY|补偿公平性和终态持久化缺陷|
|MESSAGE_REDELIVERY|同一真实业务到账事件调用消费者两次|生成 2 条用户消息|消息消费者不幂等|
|REFUND_NOTIFICATION|走实际退款冲正服务，再投递其事件|退款 REVERSED；消费者因缺 userId 拒绝|通知断点，退款冲正本身成功|

## 5. 逐业务流程打通分析

状态定义：**代码链齐（有条件）**＝已定位对应页面/接口/持久化/管理接线和相关逻辑测试，前置数据有效时内环具备；**部分打通**＝存在明确断点；**缺执行链**＝缺必要页面、执行器或运行时；**待外部验收**＝本仓库已有实现但平台真实验证未完成。所有 A 类仍受本轮干净 HTTP 启动失败与正式环境未验收的整体限制。

下表拆分到可验收业务单元：既分析用户主路径，也检查失败、返回、重复、退款、退修和运营处置。共享依赖问题以 R 编号引用，不将“仅显示页面”认定为完整打通。可筛选版本见 ${artifact('业务流程矩阵.csv')}。

${flowSections}

## 6. 关键缺陷与整改验收

优先级用于本项目上线决策，不等同 CVSS：P0 是核心正式能力/资产或资金一致性阻断；P1 是正式范围中必须解决或形成明确有效处置证据的问题；P2 是需要收敛的体验与产品完整性问题。本报告没有替任何责任人接受剩余风险。

${riskSections}

## 7. 代码质量与安全的综合评价

### 7.1 已建立的质量基础

- 身份会话使用随机 token，数据库存哈希；刷新采用锁与旋转，授权状态服务端重新校验。关键账号并发场景已有合同测试。
- 支付金额、商户、订单归属、通知验签和 Inbox 幂等已有实现；账本预留/冲正、扣点任务事务、重复通知等有测试，说明正常路径的资金模型已有基础。
- 资产有 MIME/大小/摘要/魔数/像素及净化检查，短期下载票据和所有者校验存在；公开展示与生成引用的权益分离是正确边界。R03 暴露的是验收后对象可变性问题。
- 预算计算采用明确金额单位、BigDecimal、快照/修订和缺项状态；管理员修订字段有白名单和关联校验。这部分比用页面拼一个估价结果成熟得多。
- 关键操作使用参数化 SQL、事务、唯一约束和条件更新；管理操作有服务端权限判断，双人调点不能由同一人自行批准。敏感日志配置守卫和真实服务装配守卫已存在。

上述判断来自对应实现和本轮测试，不表示整个系统不存在对象越权、注入或竞态；最终还需要跨账号/跨角色 HTTP 回归及真实部署边界验收。安全检查维度参考 [OWASP API Security Top 10 2023](https://owasp.org/API-Security/editions/2023/en/0x10-api-security-risks/)。

### 7.2 主要维护问题

RechargePaymentService 达 1,032 行，同时处理下单、预支付、通知、查单、恢复、退款和冲正；DesignProjectService 672 行；管理端预算详情 1,041 行、预算配置 666 行。优先围绕状态转换和外部调用边界拆分职责，保留事务合同与测试，不进行脱离缺陷的大规模重写。

领域模型与页面基本都有，但跨模块事件载荷、幂等语义和 Worker 状态收尾没有被统一的失败场景测试锁住。R03～R05、R12 已证明：大量合同测试通过仍可能遗漏系统组合缺陷。下一阶段最有价值的是端到端失败恢复测试，而不是继续增加只验证代码形状或静态文本的断言。

管理端保留了不少通用底座功能和依赖，大包构建警告与旧编辑器传递依赖同时出现。应审视真实运营范围，按路由懒加载并清理未用能力；单纯隐藏菜单不会移除依赖，也不会替代后端鉴权。

### 7.3 安全覆盖矩阵

|安全面|已见防护|本轮缺口/未验证项|
|---|---|---|
|身份与会话|会话哈希、刷新旋转、账号/授权归属|生产 mock 必须关闭，关闭账号全链缺失|
|对象与操作权限|资产 owner、项目/预算关联、管理 @PreAuthorize|未完成真实 HTTP 负向权限矩阵；不认证通用多租户|
|资产与内容|扫描、净化、短票据、权益区分|验收后覆盖实测成立；真实审核缺实现|
|支付与账本|验签、事实校验、Inbox、事务/幂等|预下单恢复和补偿饥饿；真实资金验收未做|
|事件与任务|租约/fencing、Outbox 重试/死信|消息不幂等、载荷缺字段、QUEUED 和渐进结果收尾|
|资源与导出|部分接口限流、导出票据|完整缓存前的限额、流式导出、筛选与公式处理|
|密钥与日志|配置脱敏和启动守卫|底座 SQL 疑似凭据；历史/运行环境未全面扫描|
|供应链|锁文件、管理端审计结果|12 条公告；Maven/镜像/vendored 组件尚待审计|
|部署与运维|Flyway、开发 Compose、CI、定时处理|正式配置、告警、备份恢复、压力/弱网和回滚未实证|

## 8. 上线前工作顺序与放行标准

### 8.1 按依赖安排整改

|批次|优先工作|产出和停止条件|
|---|---|---|
|第一批：恢复可信验证基线|R08 干净启动；R09 测试选择/工具链/格式；同步过时看板|空库启动 + 标准全量门禁可运行，不掩盖失败|
|第二批：资产与付费主链|R03 对象不可变性；R04 预支付恢复；R05 补偿公平性；R12 事件合同|五个探针对应缺陷修复为不可复现，并新增必要回归|
|第三批：补齐真实执行能力|R01 真实内容审核；R02 Runtime；R18 各 Worker 与状态收尾|真实平面/立面生成及资金/点数闭环有可追溯记录|
|第四批：正式产品与部署|R06/R07 正式配置；R10 权利流程；R11 客服；R13/R14/R15/R16 安全项；R17 页面事实|形成可供真实用户使用、可运营处理异常的发布候选|
|最终验收|真实平台、小程序真机、跨角色负向、重启恢复、容量、备份回滚|完成下表所有相关门禁后再出上线结论|

以上批次可按责任边界并行推进，但应先有可信验证基线。没有结合团队人数、真实服务资质与 Provider 交付计划估算日历工期，避免用无依据的“几天即可上线”替代依赖分析。

### 8.2 正式放行必须留存的证据

|门禁|最低验收内容|当前状态|
|---|---|---|
|制品可复现|锁定提交/未提交变更归档、冻结依赖、JDK17、正式配置、制品摘要|未完成|
|质量检查|小程序/管理/后端全部普通测试、类型/lint/build、干净启动 HTTP 全链|本轮非全绿|
|P0 缺陷|R01～R05 全部解决并回归，不能以 UI 隐藏或关闭守卫替代|未通过|
|身份/权限|真实微信登录；跨账号资产/项目/预算/消息/订单负向；跨角色管理操作|部分合同证据，真实端待验收|
|真实资产|正式私有桶，票据、不可变净化对象、审核拒绝/异常和下载权限|未通过|
|真实 AI|平面→立面→结果→预算/投稿；失败/取消/部分成功/迟到重放点数正确|未完成|
|真实支付|预下单→真实支付→回调/查单到账；丢回调/重复通知；退款及冲正/通知|未完成，本轮无真实资金交易|
|业务数据|正式套餐、授权码交付、地区材料价目、预算专业样例、案例权益|待业务验收|
|隐私与客服|版本同意、个人导出、安全关闭账号、实际客服处理路径|未完成|
|运维可恢复|Worker 显式配置、卡单/死信告警、备份还原、回滚、密钥轮换、容量边界|未完成|
|小程序发布|正式域名与平台权限、开发工具上传包、真机/弱网/前后台切换验收|未完成|

本报告建议将发布状态保持为“上线整改中”。代码主干已具备继续收敛的基础，但完整正式上线应以真实业务和故障恢复证据放行，不能用页面数、测试总数或历史看板完成标记代替。

## 9. 报告附件

- ${artifact('业务流程矩阵.csv')}：52 条逐流程判断，可筛选并分配负责人。
- ${artifact('风险整改清单.csv')}：18 组问题、优先级、整改方向、验收条件和建议责任角色。
- ${artifact('源码证据索引.md')}：60 个可点击源码定位。
- ${artifact('接口清单.md')}：144 个 Controller 映射；不等于 HTTP 验收清单。
- ${artifact('verification-summary.json')}：本轮实际执行结果与未执行项。
- ${artifact('scope-snapshot.json')}：工作树范围与源码摘要。
- ${artifact('复核说明.md')}：工具版本、复跑入口、原始日志及证据解释。

本报告只对上述工作树和审计时点成立。修复合入后，应保留本轮失败证据，再以新制品复跑受影响流程并更新准入结论。
`;
fs.writeFileSync(path.join(out,'上线前综合分析报告-2026-09-13.md'),report);

const miniManifest=miniPages.map((p,i)=>`|${i+1}|${p}|`).join('\n');
const adminManifest=adminRoutes.map((p,i)=>`|${i+1}|${p}|`).join('\n');
fs.writeFileSync(path.join(out,'页面范围清单.md'),'# 页面范围清单\n\n## 小程序（28 个注册页面）\n\n|序号|页面|\n|---|---|\n'+miniManifest+'\n\n## 管理端（19 个众墅业务页面路由）\n\n此处为 zs.ts 注册页面，不含整个 system/infra 底座，也不是全量 Vue 文件数。\n\n|序号|页面组件|\n|---|---|\n'+adminManifest+'\n');
fs.writeFileSync(path.join(out,'复核说明.md'),`# 审计复核说明

主报告：${artifact('上线前综合分析报告-2026-09-13.md')}。

本目录中的探针仅用于确认本轮发现，且断言的是“现有缺陷可以重现”。风险探针 exit 0 不表示产品安全；修复后应把预期改为安全行为，再纳入正常回归。探针不在业务 src/test 中，不会影响默认项目测试。本轮没有修改业务源码。

## 运行环境和限制

Node 24.19.0；本机 pnpm 10.34.5（项目要求 11.19.0）；后端使用 Android Studio JBR OpenJDK25.0.3（项目目标 JDK17）；Docker29.7.2。真实验收 real-acceptance 标签默认排除，本轮没有开启，也没有真实消费。

## 可复跑入口

以下均在仓库根目录运行，需先具备项目要求的已安装依赖和 Docker。它们不是要求操作者现在发起真实消费。

~~~powershell
# 文档/源码范围重建（只写本审计目录）
node './项目文档/上线前综合审计-2026-09-13/inventory.mjs'
node './项目文档/上线前综合审计-2026-09-13/collect-evidence.mjs'
node './项目文档/上线前综合审计-2026-09-13/build-report.mjs'

# 本地隔离缺陷探针：需要先运行当前后端测试以生成 classpath
& './项目文档/上线前综合审计-2026-09-13/run-probes.ps1'

# 本轮同样的隔离 HTTP 启动/E2E 尝试
& './项目文档/上线前综合审计-2026-09-13/run-isolated-e2e.ps1'

# 正式整改后，应在规定工具链下运行标准全量门禁
node scripts/verify.mjs full

# 正式发布配置应另跑；开发地址下失败是本轮预期发现
node './前端程序/scripts/check-release.js'

# 后端普通测试扩大到 *Test；不要置空真实验收排除标签
# 在“后端程序”目录运行，-o 仅适用于依赖已缓存的本地复核
./mvnw.cmd -B -o '-Dtest=*Test' '-Dsurefire.failIfNoSpecifiedTests=false' -pl yudao-module-identity,yudao-module-commerce,yudao-module-ai-orchestration,yudao-module-design,yudao-server -am test

# 管理端生产依赖审计（管理后台目录，使用规定 pnpm）
pnpm audit --prod --json
~~~

run-probes.ps1 使用 server Surefire XML 中的 classpath（仅解析该属性），不复制或打印完整测试 XML。它创建全新 PostgreSQL17 和 MinIO Testcontainers，并在退出时关闭。脚本中的账号仅用于新建本地容器。

run-isolated-e2e.ps1 固定专属 Compose 项目 zs-audit-20260913、PG55432、Redis56379、后端127.0.0.1:48113。只删除该项目的容器/卷，启动失败也执行清理。复跑前如这些端口已被占用，应为本次审计分配新的专属端口，不能停止其他业务服务来腾位。

## 日志解释

- mini-tests.log、admin-tests.log：实际 Node 测试用例结果。
- backend-tests-jbr25.log、backend-test-summary.json：本轮扩展后端测试；有 1 个错误码名称合同失败。
- admin-types.log、admin-eslint.log 为空是本轮命令 exit 0 且无输出；结果记于 verification-summary.json。Stylelint/Prettier 错误另有日志。
- backend-package.log：skipTests 的编译打包结果，不能当作测试通过。
- e2e-compose.log、e2e-backend.stdout.log、e2e-cleanup.log：容器健康、应用启动失败和专属资源清理；HTTP suite 未开始，因此不存在本轮 54 项通过报告。
- risk-probes.log：5 个无真实渠道的缺陷复现。最新一次运行补充了消息与退款事件两个探针。
- dependency-audit.json 是原始管理端依赖审计；dependency-summary.json 是精简版。漏洞库会变化，复跑可能得到不同数字。
- secret-format-scan.json 仅记录文件位置、格式类别；未输出疑似凭据。当前扫描不是 Git 历史和运行环境的完整密钥审计。
- scope-snapshot.json 记录相关源码配置 SHA-256 和原有工作树条目；不含审计目录本身。
- concurrent-config-review.json 记录审计期间新增 AI Provider 配置声明/看板更新的单独审阅，backend-config-followup.log 是更新后的 34 例配置专项复核；没有把它和 528 例相加或冒称后续全量测试通过。
- report-validation.json 校验源码快照差异、已记录的并行更新、文档链接和表格列数；未被记录的新改动会令校验失败。

业务流程的 A 状态不是已通过当前 HTTP E2E：本轮干净应用启动失败，所有流程都需要在修复后的发布候选上再做最终验收。
`);

// Verify numerical claims and all local source targets before publishing the report.
if(flows.length!==52||risks.length!==18||evidence.length!==60||miniPages.length!==28||adminRoutes.length!==19)throw Error('Manifest counts changed');
if(backend.tests!==528||contractRows.reduce((s,r)=>s+r.tests,0)!==329)throw Error('Test totals changed');
const allIds=new Set(risks.map(r=>r.id));for(const f of flows)for(const r of f.risks.split(',').filter(Boolean))if(!allIds.has(r))throw Error('Unknown risk '+r);
const targets=[...report.matchAll(/\]\(<([^>]+)>\)/g)].map(m=>m[1].replace(/:\d+$/,''));
for(const target of targets)if(!fs.existsSync(target))throw Error('Missing linked artifact '+target);
console.log(JSON.stringify({report:path.join(out,'上线前综合分析报告-2026-09-13.md'),flowCounts:counts,riskCounts:priorityCounts,pages:miniPages.length,adminRoutes:adminRoutes.length,evidence:evidence.length,localLinksValidated:targets.length,backendGroups},null,2));
