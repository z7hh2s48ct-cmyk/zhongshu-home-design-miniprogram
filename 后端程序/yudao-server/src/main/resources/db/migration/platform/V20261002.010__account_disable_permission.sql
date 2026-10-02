-- 停用账号按钮权限：挂「C端用户」页（9523，V009 建）之下。
-- ID 取 9550：V009 重放的 DELETE 覆盖 9520-9549，新行必须落在该区间之外。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，跳过停用账号权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id = 9550;
  DELETE FROM system_menu WHERE id = 9550;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted)
  VALUES (9550, '停用账号', 'identity:account:disable', 3, 1, 9523, '', '', NULL,
          NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id = 9550;
END $$;
