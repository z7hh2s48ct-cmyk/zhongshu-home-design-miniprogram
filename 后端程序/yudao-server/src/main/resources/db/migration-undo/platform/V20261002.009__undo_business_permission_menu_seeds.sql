-- 逆向脚本：回滚 V20261002.009__business_permission_menu_seeds.sql
-- 移除业务权限种子菜单行（9520-9549）及超级管理员绑定。
DO $$
BEGIN
  IF to_regclass('public.system_menu') IS NULL OR to_regclass('public.system_role_menu') IS NULL THEN
    RAISE NOTICE 'system_menu/system_role_menu 不存在，无需回滚业务权限种子';
    RETURN;
  END IF;

  DELETE FROM system_role_menu WHERE menu_id BETWEEN 9520 AND 9549;
  DELETE FROM system_menu WHERE id BETWEEN 9520 AND 9549;
END $$;
