-- 逆向 V20260930.007：移除授权码复制权限种子
DO $$
BEGIN
  IF to_regclass('public.system_role_menu') IS NOT NULL THEN
    DELETE FROM system_role_menu WHERE menu_id = 9512;
  END IF;
  IF to_regclass('public.system_menu') IS NOT NULL THEN
    DELETE FROM system_menu WHERE id = 9512;
  END IF;
END $$;
