-- V20260905.401：P4B AI 任务域——任务、尝试、结果事件 Inbox、隔离结果、结算单
-- 归属：ai-orchestration 模块（job / lease / attempt / callback / settlement 领域包）

CREATE TABLE ai_job
(
    id                BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    project_ref       VARCHAR(64)  NULL, -- P5 接入设计项目后的关联引用
    phase             VARCHAR(16)  NOT NULL,
    status            VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
    requested_count   INT          NOT NULL,
    accepted_count    INT          NOT NULL DEFAULT 0,
    progress          INT          NOT NULL DEFAULT 0,
    decision_seq      BIGINT       NOT NULL DEFAULT 0,
    cancel_seq        BIGINT       NULL,
    cancel_requested_at TIMESTAMPTZ NULL,
    output_prefix     VARCHAR(128) NOT NULL,
    claimed_by        VARCHAR(128) NULL,
    claim_expires_at  TIMESTAMPTZ  NULL,
    fencing_token     BIGINT       NOT NULL DEFAULT 0,
    idempotency_key   VARCHAR(128) NULL,
    unit_point_cost   BIGINT       NULL, -- P4C 接通后填充
    total_point_cost  BIGINT       NULL,
    charge_id         BIGINT       NULL,
    tenant_id         BIGINT       NOT NULL DEFAULT 0,
    creator           VARCHAR(64)  NULL DEFAULT '',
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater           VARCHAR(64)  NULL DEFAULT '',
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_ai_job PRIMARY KEY (id),
    CONSTRAINT ck_ai_job_phase CHECK (phase IN ('FLAT', 'ELEVATION')),
    CONSTRAINT ck_ai_job_status CHECK (status IN
            ('QUEUED', 'RUNNING', 'VALIDATING', 'SETTLING', 'SUCCEEDED',
             'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED')),
    CONSTRAINT ck_ai_job_count CHECK (requested_count >= 1 AND requested_count <= 8)
);
CREATE UNIQUE INDEX uk_ai_job_idem ON ai_job (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ai_job_claim_idx ON ai_job (status, claim_expires_at, id);

CREATE TABLE ai_job_attempt
(
    id                  BIGINT       NOT NULL,
    job_id              BIGINT       NOT NULL,
    attempt_no          INT          NOT NULL,
    worker_id           VARCHAR(128) NOT NULL,
    fencing_token       BIGINT       NOT NULL,
    provider_request_id VARCHAR(128) NULL,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ  NULL,
    last_error          TEXT         NULL,
    tenant_id           BIGINT       NOT NULL DEFAULT 0,
    creator             VARCHAR(64)  NULL DEFAULT '',
    create_time         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater             VARCHAR(64)  NULL DEFAULT '',
    update_time         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_ai_job_attempt PRIMARY KEY (id),
    CONSTRAINT uk_ai_job_attempt UNIQUE (job_id, attempt_no)
);

-- 结果事件 durable inbox：(provider_code, source_event_id) 全局唯一，重放幂等
CREATE TABLE ai_result_event_inbox
(
    id              BIGINT       NOT NULL,
    job_id          BIGINT       NOT NULL,
    attempt_no      INT          NOT NULL,
    provider_code   VARCHAR(32)  NOT NULL,
    source_event_id VARCHAR(128) NOT NULL,
    event_kind      VARCHAR(16)  NOT NULL,
    payload         JSONB        NOT NULL,
    received_seq    BIGINT       NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    process_status  VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    creator         VARCHAR(64)  NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL DEFAULT '',
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_ai_result_event_inbox PRIMARY KEY (id),
    CONSTRAINT uk_ai_inbox_source UNIQUE (provider_code, source_event_id),
    CONSTRAINT ck_ai_inbox_kind CHECK (event_kind IN ('RESULT', 'FAILURE', 'PROGRESS')),
    CONSTRAINT ck_ai_inbox_status CHECK (process_status IN
            ('RECEIVED', 'VALIDATED', 'REJECTED', 'SUPERSEDED'))
);
CREATE INDEX idx_ai_inbox_job ON ai_result_event_inbox (job_id);

-- 隔离结果：仅 ACCEPTED 候选占槽位（部分唯一）；Provider 输出 ID 与任务内内容 Hash 去重
CREATE TABLE ai_job_result
(
    id                 BIGINT       NOT NULL,
    job_id             BIGINT       NOT NULL,
    attempt_no         INT          NOT NULL,
    provider_code      VARCHAR(32)  NOT NULL,
    candidate_slot_no  INT          NOT NULL,
    provider_output_id VARCHAR(128) NULL,
    content_sha256     VARCHAR(64)  NOT NULL,
    object_key         VARCHAR(256) NOT NULL,
    mime_type          VARCHAR(64)  NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    validation_state   VARCHAR(24)  NOT NULL DEFAULT 'OUTPUT_QUARANTINED',
    reject_reason      VARCHAR(256) NULL,
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    creator            VARCHAR(64)  NULL DEFAULT '',
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater            VARCHAR(64)  NULL DEFAULT '',
    update_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_ai_job_result PRIMARY KEY (id),
    CONSTRAINT ck_ai_job_result_state CHECK (validation_state IN
            ('RECEIVED', 'OUTPUT_QUARANTINED', 'VALIDATING', 'ACCEPTED', 'REJECTED'))
);
CREATE UNIQUE INDEX uk_ai_job_result_slot ON ai_job_result (job_id, candidate_slot_no)
    WHERE validation_state = 'ACCEPTED';
CREATE UNIQUE INDEX uk_ai_job_result_output ON ai_job_result (provider_code, provider_output_id)
    WHERE provider_output_id IS NOT NULL AND validation_state = 'ACCEPTED';
CREATE UNIQUE INDEX uk_ai_job_result_content ON ai_job_result (job_id, content_sha256)
    WHERE validation_state = 'ACCEPTED';
CREATE INDEX idx_ai_job_result_job ON ai_job_result (job_id);

CREATE TABLE ai_job_settlement
(
    id                      BIGINT       NOT NULL,
    job_id                  BIGINT       NOT NULL,
    settlement_version      BIGINT       NOT NULL,
    requested_count         INT          NOT NULL,
    accepted_billable_count INT          NOT NULL,
    unit_point_cost         BIGINT       NOT NULL,
    original_debit          BIGINT       NOT NULL,
    refunded_points         BIGINT       NOT NULL DEFAULT 0,
    reason                  VARCHAR(64)  NOT NULL,
    tenant_id               BIGINT       NOT NULL DEFAULT 0,
    creator                 VARCHAR(64)  NULL DEFAULT '',
    create_time             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater                 VARCHAR(64)  NULL DEFAULT '',
    update_time             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted                 BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_ai_job_settlement PRIMARY KEY (id),
    CONSTRAINT uk_ai_job_settlement UNIQUE (job_id, settlement_version, reason),
    CONSTRAINT ck_ai_job_settlement_nonneg CHECK (refunded_points >= 0 AND original_debit >= 0)
);
