DO $$
BEGIN
  IF to_regclass('public.system_role_menu') IS NOT NULL THEN DELETE FROM system_role_menu WHERE menu_id IN (9513, 9514); END IF;
  IF to_regclass('public.system_menu') IS NOT NULL THEN DELETE FROM system_menu WHERE id IN (9513, 9514); END IF;
END $$;
