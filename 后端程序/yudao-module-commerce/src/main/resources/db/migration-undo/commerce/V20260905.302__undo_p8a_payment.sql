-- 逆向脚本：回退 V20260905.302（P8A 支付域表；生产执行前必须核对无在途订单与退款）
DROP TABLE IF EXISTS refund_order;
DROP TABLE IF EXISTS recharge_credit;
DROP TABLE IF EXISTS payment_transaction;
DROP TABLE IF EXISTS payment_notification_inbox;
DROP TABLE IF EXISTS recharge_order;
DROP TABLE IF EXISTS recharge_plan;
ALTER TABLE design_point_ledger DROP CONSTRAINT ck_design_point_ledger_type;
ALTER TABLE design_point_ledger ADD CONSTRAINT ck_design_point_ledger_type CHECK (type IN
    ('RECHARGE_BASE_CREDIT', 'RECHARGE_BONUS_CREDIT', 'FLAT_GENERATION_DEBIT',
     'ELEVATION_GENERATION_DEBIT', 'TASK_SETTLEMENT_REFUND', 'MANUAL_CREDIT', 'MANUAL_DEBIT'));
