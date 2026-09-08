-- V20260905.302：P8A 充值方案、订单（支付/到账双状态）、通知 Inbox、渠道交易、到账事实、退款单
-- 归属：commerce 模块（recharge / payment 领域包）；流水类型扩展冲正两类（同步放宽 CHECK）

ALTER TABLE design_point_ledger DROP CONSTRAINT ck_design_point_ledger_type;
ALTER TABLE design_point_ledger ADD CONSTRAINT ck_design_point_ledger_type CHECK (type IN
    ('RECHARGE_BASE_CREDIT', 'RECHARGE_BONUS_CREDIT', 'FLAT_GENERATION_DEBIT',
     'ELEVATION_GENERATION_DEBIT', 'TASK_SETTLEMENT_REFUND', 'MANUAL_CREDIT', 'MANUAL_DEBIT',
     'RECHARGE_BASE_REVERSAL', 'RECHARGE_BONUS_REVERSAL'));

CREATE TABLE recharge_plan
(
    id          BIGINT       NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    amount_cents BIGINT      NOT NULL,
    base_points BIGINT       NOT NULL,
    bonus_points BIGINT      NOT NULL DEFAULT 0,
    recommended BOOLEAN      NOT NULL DEFAULT FALSE,
    sort        INT          NOT NULL DEFAULT 0,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 1,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    creator     VARCHAR(64)  NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater     VARCHAR(64)  NULL DEFAULT '',
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_recharge_plan PRIMARY KEY (id),
    CONSTRAINT ck_recharge_plan_amount CHECK (amount_cents > 0),
    CONSTRAINT ck_recharge_plan_points CHECK (base_points >= 0 AND bonus_points >= 0)
);

CREATE TABLE recharge_order
(
    id                    BIGINT       NOT NULL,
    order_no              VARCHAR(32)  NOT NULL,
    user_id               BIGINT       NOT NULL,
    plan_id               BIGINT       NOT NULL,
    plan_snapshot         JSONB        NOT NULL,
    amount_cents          BIGINT       NOT NULL,
    base_points           BIGINT       NOT NULL,
    bonus_points          BIGINT       NOT NULL,
    payment_state         VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    fulfillment_state     VARCHAR(16)  NOT NULL DEFAULT 'NOT_READY',
    channel_transaction_id VARCHAR(64) NULL,
    idempotency_key       VARCHAR(128) NULL,
    tenant_id             BIGINT       NOT NULL DEFAULT 0,
    creator               VARCHAR(64)  NULL DEFAULT '',
    create_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater               VARCHAR(64)  NULL DEFAULT '',
    update_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_recharge_order PRIMARY KEY (id),
    CONSTRAINT uk_recharge_order_no UNIQUE (order_no),
    CONSTRAINT ck_recharge_order_payment CHECK (payment_state IN
            ('CREATED', 'PENDING', 'SUCCEEDED', 'CLOSED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_recharge_order_fulfillment CHECK (fulfillment_state IN
            ('NOT_READY', 'PENDING', 'CREDITED', 'FAILED'))
);
CREATE UNIQUE INDEX uk_recharge_order_idem ON recharge_order (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_recharge_order_user ON recharge_order (user_id, create_time DESC);

CREATE TABLE payment_notification_inbox
(
    id                 BIGINT       NOT NULL,
    channel            VARCHAR(16)  NOT NULL,
    event_id           VARCHAR(128) NOT NULL,
    request_headers    JSONB        NULL,
    encrypted_body     BYTEA        NULL,
    body_hash          VARCHAR(64)  NOT NULL,
    normalized_payload JSONB        NULL,
    verify_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    process_status     VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    retry_count        INT          NOT NULL DEFAULT 0,
    last_error         TEXT         NULL,
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    creator            VARCHAR(64)  NULL DEFAULT '',
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater            VARCHAR(64)  NULL DEFAULT '',
    update_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_payment_notification_inbox PRIMARY KEY (id),
    CONSTRAINT uk_payment_inbox_event UNIQUE (channel, event_id),
    CONSTRAINT ck_payment_inbox_verify CHECK (verify_status IN ('PENDING', 'PASSED', 'FAILED')),
    CONSTRAINT ck_payment_inbox_status CHECK (process_status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

CREATE TABLE payment_transaction
(
    id                    BIGINT       NOT NULL,
    channel               VARCHAR(16)  NOT NULL,
    merchant_id           VARCHAR(32)  NOT NULL,
    channel_transaction_id VARCHAR(64) NOT NULL,
    order_no              VARCHAR(32)  NOT NULL,
    amount_cents          BIGINT       NOT NULL,
    paid_at               TIMESTAMPTZ  NOT NULL,
    tenant_id             BIGINT       NOT NULL DEFAULT 0,
    creator               VARCHAR(64)  NULL DEFAULT '',
    create_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater               VARCHAR(64)  NULL DEFAULT '',
    update_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_payment_transaction PRIMARY KEY (id),
    CONSTRAINT uk_payment_transaction UNIQUE (channel, merchant_id, channel_transaction_id)
);

CREATE TABLE recharge_credit
(
    id              BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    base_points     BIGINT       NOT NULL,
    bonus_points    BIGINT       NOT NULL,
    ledger_base_id  BIGINT       NOT NULL,
    ledger_bonus_id BIGINT       NULL,
    credited_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    creator         VARCHAR(64)  NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL DEFAULT '',
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_recharge_credit PRIMARY KEY (id),
    CONSTRAINT uk_recharge_credit_order UNIQUE (order_id)
);

CREATE TABLE refund_order
(
    id                   BIGINT       NOT NULL,
    order_id             BIGINT       NOT NULL,
    refund_request_key   VARCHAR(128) NOT NULL,
    amount_cents         BIGINT       NOT NULL,
    channel_refund_id    VARCHAR(64)  NULL,
    channel_state        VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
    point_reversal_state VARCHAR(16)  NOT NULL DEFAULT 'NOT_RESERVED',
    reserved_base        BIGINT       NOT NULL DEFAULT 0,
    reserved_bonus       BIGINT       NOT NULL DEFAULT 0,
    operator_id          VARCHAR(64)  NOT NULL,
    reason               VARCHAR(512) NULL,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    creator              VARCHAR(64)  NULL DEFAULT '',
    create_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater              VARCHAR(64)  NULL DEFAULT '',
    update_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_refund_order PRIMARY KEY (id),
    CONSTRAINT uk_refund_request_key UNIQUE (refund_request_key),
    CONSTRAINT ck_refund_channel_state CHECK (channel_state IN
            ('CREATED', 'PROCESSING', 'SUCCEEDED', 'UNKNOWN', 'FAILED')),
    CONSTRAINT ck_refund_reversal_state CHECK (point_reversal_state IN
            ('NOT_RESERVED', 'RESERVED', 'PENDING', 'REVERSED', 'FAILED', 'RELEASED')),
    CONSTRAINT ck_refund_amount CHECK (amount_cents > 0)
);
