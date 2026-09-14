-- T14-02: account-scoped unit price overrides over the fixed baseline catalog (Wuhan reference).
-- Additive only; baseline budget_item_price rows and every frozen estimate/quote stay untouched.
CREATE TABLE budget_account_price (
    id BIGINT CONSTRAINT pk_budget_account_price PRIMARY KEY,
    account_id BIGINT NOT NULL,
    option_id BIGINT NOT NULL,
    unit_price_cents BIGINT NOT NULL CONSTRAINT ck_budget_account_price_unit_price_cents CHECK (unit_price_cents BETWEEN 1 AND 100000000),
    reason VARCHAR(500) CONSTRAINT ck_budget_account_price_reason CHECK (reason IS NULL OR length(trim(reason)) > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CONSTRAINT ck_budget_account_price_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_budget_account_price_version CHECK (version > 0),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_account_price_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_budget_account_price_option_id FOREIGN KEY (tenant_id, option_id) REFERENCES budget_option (tenant_id, id)
);
-- At most one effective override per (tenant, account, option); resets become REMOVED history rows.
CREATE UNIQUE INDEX uk_budget_account_price_active ON budget_account_price (tenant_id, account_id, option_id)
    WHERE status = 'ACTIVE' AND deleted = FALSE;
CREATE INDEX idx_budget_account_price_account ON budget_account_price (tenant_id, account_id) WHERE deleted = FALSE;
