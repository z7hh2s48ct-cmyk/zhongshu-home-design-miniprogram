DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL OR to_regclass('public.system_role') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，跳过业务积分价格菜单种子';
    RETURN;
  END IF;
  DELETE FROM system_role_menu WHERE menu_id IN (9513, 9514);
  DELETE FROM system_menu WHERE id IN (9513, 9514);
  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  (9513, '业务积分价格', 'commerce:usage-price:query', 2, 5, 9506, 'usage-pricing',
   'ep:coin', 'zs/usage-pricing/index', NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9514, '管理业务积分价格', 'commerce:usage-price:manage', 3, 1, 9513, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);
  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id IN (9513, 9514);
END $$;
