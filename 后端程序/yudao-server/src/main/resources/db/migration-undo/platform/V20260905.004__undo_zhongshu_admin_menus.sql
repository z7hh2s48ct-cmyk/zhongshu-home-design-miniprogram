-- 逆向 V20260905.004：移除众墅之家管理后台导航菜单种子
DO $$
BEGIN
  IF to_regclass('public.system_role_menu') IS NOT NULL THEN
    DELETE FROM system_role_menu WHERE menu_id BETWEEN 9500 AND 9519;
  END IF;
  IF to_regclass('public.system_menu') IS NOT NULL THEN
    DELETE FROM system_menu WHERE id BETWEEN 9500 AND 9519;
  END IF;
END $$;
