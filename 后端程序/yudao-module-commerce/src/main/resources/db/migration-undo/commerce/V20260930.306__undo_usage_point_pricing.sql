-- 回滚前应先停止新业务收费，并确认没有待扣的 service_usage_charge 记录。
DROP TABLE IF EXISTS service_usage_charge;
DROP TABLE IF EXISTS service_usage_price_rule;
ALTER TABLE design_point_ledger DROP CONSTRAINT ck_design_point_ledger_type;
ALTER TABLE design_point_ledger ADD CONSTRAINT ck_design_point_ledger_type CHECK (type IN
    ('RECHARGE_BASE_CREDIT', 'RECHARGE_BONUS_CREDIT', 'FLAT_GENERATION_DEBIT',
     'ELEVATION_GENERATION_DEBIT', 'TASK_SETTLEMENT_REFUND', 'MANUAL_CREDIT', 'MANUAL_DEBIT',
     'RECHARGE_BASE_REVERSAL', 'RECHARGE_BONUS_REVERSAL'));
