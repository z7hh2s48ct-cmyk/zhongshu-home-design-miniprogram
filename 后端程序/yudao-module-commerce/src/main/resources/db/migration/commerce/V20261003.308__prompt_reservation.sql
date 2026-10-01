ALTER TABLE service_usage_charge DROP CONSTRAINT service_usage_charge_state_check;
ALTER TABLE service_usage_charge ADD CONSTRAINT service_usage_charge_state_check
    CHECK (state IN ('PENDING','RESERVED','UNKNOWN','CHARGED'));
ALTER TABLE service_usage_charge ADD COLUMN reservation_token BIGINT;
ALTER TABLE service_usage_charge ADD COLUMN reservation_expires_at TIMESTAMPTZ;
CREATE INDEX idx_prompt_reconciliation ON service_usage_charge (state, update_time)
    WHERE product='AI_PROMPT' AND state IN ('RESERVED','UNKNOWN') AND deleted=FALSE;
