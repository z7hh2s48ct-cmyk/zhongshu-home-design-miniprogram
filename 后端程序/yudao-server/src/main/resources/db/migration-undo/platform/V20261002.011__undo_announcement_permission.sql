-- 逆向脚本：回滚 V20261002.011__announcement_permission.sql
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，无需回滚公告发送权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id = 9551;
  DELETE FROM system_menu WHERE id = 9551;
END $$;
