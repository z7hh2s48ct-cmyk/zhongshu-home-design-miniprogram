-- 将授权码明文复制权限加入后台角色权限树；只给超级管理员默认授权。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，跳过授权码导出权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id = 9512;
  DELETE FROM system_menu WHERE id = 9512;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted)
  VALUES (9512, '复制授权码', 'identity:access-code:export', 3, 2, 9505, '', '', NULL,
          NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 968512, 1, 9512, '', now(), '', now(), 0, 0
  WHERE EXISTS (SELECT 1 FROM system_role WHERE id = 1);
END $$;
