DO $privacy_permission$
DECLARE privacy_menu_id BIGINT;
BEGIN
  -- Platform permission metadata exists in the complete server; isolated domain tests omit it.
  IF to_regclass('system_menu') IS NOT NULL THEN
    LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;
    IF NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='identity:privacy:manage' AND deleted=0) THEN
      SELECT COALESCE(MAX(id),0)+1 INTO privacy_menu_id FROM system_menu;
      INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,visible,keep_alive,always_show)
      VALUES(privacy_menu_id,'隐私申请处理','identity:privacy:manage',3,99,0,'',FALSE,FALSE,FALSE);
    END IF;
  END IF;
END $privacy_permission$;
