-- App budget creation/save replay receipts only; no production data or historic amount changes.
CREATE TABLE budget_app_command (
    user_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response JSONB,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_budget_app_command PRIMARY KEY (user_id, operation, idempotency_key),
    CONSTRAINT ck_budget_app_command_user CHECK (user_id > 0),
    CONSTRAINT ck_budget_app_command_operation CHECK (operation IN ('CREATE', 'SAVE')),
    CONSTRAINT ck_budget_app_command_key CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT ck_budget_app_command_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_budget_app_command_response CHECK (response IS NULL OR jsonb_typeof(response) = 'object')
);
