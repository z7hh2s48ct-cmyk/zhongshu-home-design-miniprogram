-- Legacy range budgets remain readable. New billable writes use an account-scoped receipt
-- committed atomically with the estimate and usage charge.
CREATE TABLE budget_legacy_request (
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    estimate_id BIGINT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,request_key)
);
