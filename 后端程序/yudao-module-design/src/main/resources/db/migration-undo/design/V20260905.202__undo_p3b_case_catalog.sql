-- 逆向脚本：回退 V20260905.202（P3B 案例域表；生产执行前必须核对无已发布案例）
DROP TABLE IF EXISTS case_favorite;
DROP TABLE IF EXISTS case_publication;
DROP TABLE IF EXISTS design_case_asset;
DROP TABLE IF EXISTS design_case_version;
DROP TABLE IF EXISTS design_case;
