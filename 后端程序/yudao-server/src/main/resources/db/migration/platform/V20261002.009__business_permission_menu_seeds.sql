-- 业务权限种子补全：为管理端全部自研 @PreAuthorize 权限码建立可授予的菜单树行。
--
-- 背景：V004 的 9 个页面行 permission 为空串，9510-9514 只覆盖两类价格与授权码导出；
-- 其余权限码（design:case:*、design:budget:*、commerce:points:* 等）没有菜单行承载，
-- 自定义角色无法在「角色管理 → 菜单权限树」中勾选授予，业务权限实际仅超级管理员可用。
--
-- 约定（与 V004/006/007/008 一致）：
-- * 页面行（type 2）permission 携带该页的查询权限码（对齐 9510/9513 先例）；
--   V004 既有页面（9502/9503/9505/9507/9508/9509）保持空权限不动，查询码以按钮行补挂其下；
-- * 按钮行（type 3）visible/keep_alive/always_show 为 FALSE，仅作为权限树授予粒度；
-- * 工作台不经菜单表（V004 注释），design:dashboard:query 以「工作台统计」按钮挂在 9524 目录下；
-- * ID 使用 9520-9549 独立段：V004 重放会 DELETE 9500-9519，本段不受重放影响；
-- * role_menu 绑定 id = 959000 + menu_id，仅默认授予超级管理员 role_id=1；
-- * 守卫只检查 system_menu/system_role_menu（对齐 006）：不依赖 system_role，
--   在仅建最小 system 表的合同测试夹具中同样生效。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，跳过业务权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id BETWEEN 9520 AND 9549;
  DELETE FROM system_menu WHERE id BETWEEN 9520 AND 9549;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  -- 预算管理分组（组件名必须与 V004 三个 /zs 目录互异，避免 vue-router 按名替换）
  (9520, '预算管理', '', 1, 5, 0, '/zs', '', NULL, 'ZsBudgetGroup', 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9521, '预算配置', 'design:budget:query', 2, 1, 9520, 'budget', 'ep:money', 'zs/budget/index',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9522, '项目预算', 'design:budget:query', 2, 2, 9520, 'budget-estimates', 'ep:document', 'zs/budget/estimates',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  -- 用户与授权分组补充（挂 9504）
  (9523, 'C端用户', 'identity:account:query', 2, 2, 9504, 'account', 'ep:user', 'zs/user/index',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9528, '隐私申请', 'identity:privacy:manage', 2, 3, 9504, 'privacy', 'ep:lock', 'zs/privacy/index',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  -- 运营与审计分组
  (9524, '运营与审计', '', 1, 6, 0, '/zs', '', NULL, 'ZsOpsGroup', 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9525, 'AI任务', 'aiorchestration:job:query', 2, 1, 9524, 'ai-job', 'ep:cpu', 'zs/job/index',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9526, '数据导出', 'design:export:manage', 2, 2, 9524, 'export', 'ep:download', 'zs/export/index',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9527, '审计事件', 'design:audit:query', 2, 3, 9524, 'audit', 'ep:document-checked', 'zs/audit/index',
   NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  -- 按钮行：V004 既有页面下补查询/操作权限
  (9529, '查看案例', 'design:case:query', 3, 1, 9502, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9530, '新增案例', 'design:case:create', 3, 2, 9502, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9531, '编辑案例', 'design:case:update', 3, 3, 9502, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9532, '上下架案例', 'design:case:publish', 3, 4, 9502, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9533, '审核投稿', 'design:submission:review', 3, 1, 9503, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9534, '管理授权码', 'identity:access-code:manage', 3, 1, 9505, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9535, '查看充值方案', 'commerce:recharge-plan:query', 3, 1, 9507, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9536, '管理充值方案', 'commerce:recharge-plan:manage', 3, 2, 9507, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9537, '查看充值订单', 'commerce:recharge-order:query', 3, 1, 9508, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9538, '支付对账与退款', 'commerce:payment:reconcile', 3, 2, 9508, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9539, '查看点数流水', 'commerce:points:query', 3, 1, 9509, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9540, '人工调点制单', 'commerce:points:adjust', 3, 2, 9509, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9541, '点数导出', 'commerce:points:export', 3, 3, 9509, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  -- 按钮行：预算页操作权限
  (9542, '目录与选项配置', 'design:budget:configure', 3, 1, 9521, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9543, '基准价格发布', 'design:budget:price-publish', 3, 2, 9521, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9544, '预算修订', 'design:budget:edit', 3, 1, 9522, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9545, '报价制单', 'design:budget:quote', 3, 2, 9522, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9546, '报价发布与撤回', 'design:budget:quote-publish', 3, 3, 9522, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0),
  (9547, '工作台统计', 'design:dashboard:query', 3, 4, 9524, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id BETWEEN 9520 AND 9549;
END $$;
