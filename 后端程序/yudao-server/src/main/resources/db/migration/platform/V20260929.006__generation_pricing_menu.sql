-- 出图计价管理入口与可授予权限；仅在完整底座库存在时写入。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，跳过出图计价菜单种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id IN (9510, 9511);
  DELETE FROM system_menu WHERE id IN (9510, 9511);

  INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component,
                           component_name, status, visible, keep_alive, always_show,
                           creator, create_time, updater, update_time, deleted) VALUES
  (9510, '出图计价', 'commerce:generation-price:query', 2, 4, 9506, 'generation-pricing',
   'ep:picture', 'zs/generation-pricing/index', NULL, 0, TRUE, TRUE, TRUE, '', now(), '', now(), 0),
  (9511, '管理出图价格', 'commerce:generation-price:manage', 3, 1, 9510, '', '', NULL,
   NULL, 0, FALSE, FALSE, FALSE, '', now(), '', now(), 0);

  INSERT INTO system_role_menu (id, role_id, menu_id, creator, create_time, updater, update_time, deleted, tenant_id)
  SELECT 959000 + id, 1, id, '', now(), '', now(), 0, 0 FROM system_menu WHERE id IN (9510, 9511);
END $$;
