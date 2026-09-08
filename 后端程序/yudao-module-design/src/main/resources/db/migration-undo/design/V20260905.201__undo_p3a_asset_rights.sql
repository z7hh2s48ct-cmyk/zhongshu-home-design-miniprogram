-- 逆向脚本：回退 V20260905.201（P3A 资产域表；生产执行前必须核对无未交付资产）
DROP TABLE IF EXISTS asset_rights_grant;
DROP TABLE IF EXISTS asset_scan_result;
DROP TABLE IF EXISTS asset;
