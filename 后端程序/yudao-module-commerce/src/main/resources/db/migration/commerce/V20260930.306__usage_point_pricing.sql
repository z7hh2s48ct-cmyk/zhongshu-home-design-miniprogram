-- 独立业务调用计价与扣点事实（预算测算、AI 提示词生成）。
ALTER TABLE design_point_ledger DROP CONSTRAINT ck_design_point_ledger_type;
ALTER TABLE design_point_ledger ADD CONSTRAINT ck_design_point_ledger_type CHECK (type IN
    ('RECHARGE_BASE_CREDIT', 'RECHARGE_BONUS_CREDIT', 'FLAT_GENERATION_DEBIT',
     'ELEVATION_GENERATION_DEBIT', 'BUDGET_ESTIMATE_DEBIT', 'AI_PROMPT_DEBIT',
     'TASK_SETTLEMENT_REFUND', 'MANUAL_CREDIT', 'MANUAL_DEBIT',
     'RECHARGE_BASE_REVERSAL', 'RECHARGE_BONUS_REVERSAL'));

CREATE TABLE service_usage_price_rule (
    id BIGINT NOT NULL PRIMARY KEY,
    product VARCHAR(32) NOT NULL CHECK (product IN ('BUDGET_ESTIMATE', 'AI_PROMPT')),
    point_cost BIGINT NOT NULL CHECK (point_cost >= 1 AND point_cost <= 1000000000),
    version INT NOT NULL DEFAULT 1 CHECK (version > 0),
    effective_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    creator VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    updater VARCHAR(64) NOT NULL DEFAULT '',
    update_time TIMESTAMP NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_usage_price_period CHECK (expires_at IS NULL OR expires_at > effective_at)
);
CREATE INDEX idx_usage_price_current ON service_usage_price_rule(product, status, effective_at DESC, id DESC)
    WHERE deleted = FALSE;

CREATE TABLE service_usage_charge (
    id BIGINT NOT NULL PRIMARY KEY,
    product VARCHAR(32) NOT NULL CHECK (product IN ('BUDGET_ESTIMATE', 'AI_PROMPT')),
    user_id BIGINT NOT NULL,
    biz_type VARCHAR(48) NOT NULL,
    biz_id VARCHAR(64) NOT NULL,
    price_rule_id BIGINT NOT NULL,
    price_rule_version INT NOT NULL,
    point_cost BIGINT NOT NULL CHECK (point_cost >= 1),
    ledger_id BIGINT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'CHARGED')),
    creator VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT now(),
    updater VARCHAR(64) NOT NULL DEFAULT '',
    update_time TIMESTAMP NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_service_usage_charge UNIQUE(product, biz_type, biz_id)
);
