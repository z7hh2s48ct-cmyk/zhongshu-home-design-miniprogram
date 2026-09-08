-- 逆向脚本：回退 V20260905.301（P4A 计价/账本/调点表；生产执行前必须核对账务已结平）
DROP TABLE IF EXISTS manual_point_adjustment;
DROP TABLE IF EXISTS design_point_ledger;
DROP TABLE IF EXISTS design_point_account;
DROP TABLE IF EXISTS generation_price_rule;
