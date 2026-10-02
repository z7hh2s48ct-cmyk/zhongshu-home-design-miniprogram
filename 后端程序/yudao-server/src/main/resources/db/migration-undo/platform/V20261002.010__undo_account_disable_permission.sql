-- 逆向脚本：回滚 V20261002.010__account_disable_permission.sql
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，无需回滚停用账号权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id = 9550;
  DELETE FROM system_menu WHERE id = 9550;
END $$;
