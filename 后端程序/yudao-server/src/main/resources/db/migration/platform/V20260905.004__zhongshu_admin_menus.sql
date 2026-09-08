-- 平台段 P1B 补充：众墅之家管理后台导航菜单（底座 system_menu / system_role_menu 种子数据）
--
-- 全新环境引导顺序：① 底座 PostgreSQL 基线（sql/postgresql/ruoyi-vue-pro.sql，建 system/infra 表）
--                   ② 本 Flyway 基线（platform + 各业务段）。
-- system 表缺失的环境（如仅加载 platform 迁移目录的合同测试容器）本迁移安全空转，不阻断迁移基线。
--
-- 菜单约定：
-- * 顶级 path 必须以 / 开头（vue-router 4 顶级路由硬性要求，违例会导致 router 启动失败）；
-- * 子级 path 相对拼接，最终 URL 必须与管理端静态路由 router/modules/zs.ts 一致
--   （/zs/case、/zs/review、/zs/access-code、/zs/recharge-plan、/zs/recharge-order、/zs/point-ledger）；
-- * 隐藏详情页（新增公司案例、审核详情、批量生成授权码、编辑充值方案）不入菜单表，由静态路由承载；
-- * 工作台不经菜单表：管理端 remaining.ts 的首页（/index）直接指向 zs/dashboard，
--   与效果图一致作为侧边栏扁平首项（菜单表顶级单页会被强制包一层嵌套，外观不符）；
-- * 系统设置为效果图占位项，待设置类页面落地后再补菜单行。

DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在（仅迁移基线环境），跳过管理端菜单种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id BETWEEN 9500 AND 9519;
  DELETE FROM system_menu WHERE id BETWEEN 9500 AND 9519;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name, status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted) VALUES
  -- 户型库分组。component_name 必须各不相同：三个顶级目录 path 同为 /zs，
  -- generateRoute 缺省用 path 生成路由 name，同名记录会被 vue-router addRoute 按名替换（后组吞前组）
  (9501, '户型库', '', 1, 2, 0, '/zs', '', NULL, 'ZsCaseGroup', 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9502, '户型库管理', '', 2, 1, 9501, 'case', 'ep:office-building', 'zs/case/index', NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9503, 'AI案例审核', '', 2, 2, 9501, 'review', 'ep:view', 'zs/review/index', NULL, 0, TRUE, FALSE, TRUE, '', now(), '', now(), 0),
  -- 用户与授权分组
  (9504, '用户与授权', '', 1, 3, 0, '/zs', '', NULL, 'ZsAuthGroup', 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9505, '授权码管理', '', 2, 1, 9504, 'access-code', 'ep:key', 'zs/accesscode/index', NULL, 0, TRUE, FALSE, TRUE, '', now(), '', now(), 0),
  -- 设计点与支付分组
  (9506, '设计点与支付', '', 1, 4, 0, '/zs', '', NULL, 'ZsPayGroup', 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9507, '充值方案', '', 2, 1, 9506, 'recharge-plan', 'ep:coin', 'zs/recharge/plan/index', NULL, 0, TRUE, FALSE, TRUE, '', now(), '', now(), 0),
  (9508, '充值订单', '', 2, 2, 9506, 'recharge-order', 'ep:tickets', 'zs/recharge/order/index', NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9509, '设计点流水', '', 2, 3, 9506, 'point-ledger', 'ep:wallet', 'zs/points/ledger/index', NULL, 0, TRUE, FALSE, TRUE, '', now(), '', now(), 0);

  -- 超级管理员（role_id = 1）默认可见
  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id BETWEEN 9501 AND 9509;
END $$;
