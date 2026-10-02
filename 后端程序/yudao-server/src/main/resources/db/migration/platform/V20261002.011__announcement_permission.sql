-- 公告发送按钮权限：挂「运营与审计」目录（9524）。
-- design:announcement:send 用于管理端向全部激活用户 fan-out 站内消息。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，跳过公告发送权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id = 9551;
  DELETE FROM system_menu WHERE id = 9551;

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted)
  VALUES (9551, '发送公告', 'design:announcement:send', 3, 4, 9524, '', '', NULL,
          NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id = 9551;
END $$;
