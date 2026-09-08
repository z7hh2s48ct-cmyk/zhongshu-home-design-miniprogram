-- V20260905.402：P4C 任务扣点快照（ai_job 的 charge_id/unit_point_cost/total_point_cost 列已在 401 预留）
CREATE TABLE ai_task_charge
(
    id                 BIGINT       NOT NULL,
    job_id             BIGINT       NOT NULL,
    user_id            BIGINT       NOT NULL,
    price_rule_id      BIGINT       NOT NULL,
    price_rule_version BIGINT       NOT NULL,
    stage              VARCHAR(16)  NOT NULL,
    unit_point_cost    BIGINT       NOT NULL,
    requested_count    INT          NOT NULL,
    total_point_cost   BIGINT       NOT NULL,
    ledger_id          BIGINT       NOT NULL,
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    creator            VARCHAR(64)  NULL DEFAULT '',
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater            VARCHAR(64)  NULL DEFAULT '',
    update_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_ai_task_charge PRIMARY KEY (id),
    CONSTRAINT uk_ai_task_charge_job UNIQUE (job_id),
    CONSTRAINT ck_ai_task_charge_cost CHECK (unit_point_cost > 0 AND total_point_cost > 0)
);
