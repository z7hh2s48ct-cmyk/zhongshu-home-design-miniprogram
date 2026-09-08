-- V20260905.206：P7B 预算规则/测算、站内消息与已读、协议同意与数据主体申请
CREATE TABLE budget_rule_version
(
    id               BIGINT       NOT NULL,
    region_code      VARCHAR(32)  NOT NULL,
    structure_type   VARCHAR(32)  NOT NULL,
    material_grade   VARCHAR(32)  NOT NULL,
    low_cents_per_sqm BIGINT      NOT NULL,
    high_cents_per_sqm BIGINT     NOT NULL,
    effective_at     TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_budget_rule_version PRIMARY KEY (id),
    CONSTRAINT ck_budget_rule_range CHECK (low_cents_per_sqm > 0 AND high_cents_per_sqm >= low_cents_per_sqm)
);

CREATE TABLE budget_estimate
(
    id               BIGINT       NOT NULL,
    project_id       BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    rule_version_id  BIGINT       NOT NULL,
    result_version_id BIGINT      NULL, -- 关联的设计结果版本（审查遗留：合同 §6.8 要求与 result_version 关联）
    input_snapshot   JSONB        NOT NULL,
    total_low_cents  BIGINT       NOT NULL,
    total_high_cents BIGINT       NOT NULL,
    disclaimer       VARCHAR(512) NOT NULL DEFAULT '仅供参考，不构成报价或结算依据',
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_budget_estimate PRIMARY KEY (id)
);
CREATE INDEX idx_budget_estimate_project ON budget_estimate (project_id, create_time DESC);

CREATE TABLE user_message
(
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    title       VARCHAR(128) NOT NULL,
    content     VARCHAR(1024) NULL,
    biz_type    VARCHAR(64)  NULL,
    biz_id      VARCHAR(64)  NULL,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    creator     VARCHAR(64)  NULL DEFAULT '',
    updater     VARCHAR(64)  NULL DEFAULT '',
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_user_message PRIMARY KEY (id)
);
CREATE INDEX idx_user_message_user ON user_message (user_id, id DESC);

-- 已读回执：user+message 唯一，重复投递幂等
CREATE TABLE message_receipt
(
    id          BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    message_id  BIGINT      NOT NULL,
    read_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_message_receipt PRIMARY KEY (id),
    CONSTRAINT uk_message_receipt UNIQUE (user_id, message_id)
);

CREATE TABLE privacy_consent
(
    id          BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    policy_type VARCHAR(32) NOT NULL,
    version     VARCHAR(16) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_privacy_consent PRIMARY KEY (id)
);

CREATE TABLE data_subject_request
(
    id           BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    request_type VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    download_ticket VARCHAR(64) NULL,
    completed_at TIMESTAMPTZ  NULL,
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    creator      VARCHAR(64)  NULL DEFAULT '',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater      VARCHAR(64)  NULL DEFAULT '',
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_data_subject_request PRIMARY KEY (id),
    CONSTRAINT ck_dsr_type CHECK (request_type IN ('EXPORT', 'CLOSE_ACCOUNT')),
    CONSTRAINT ck_dsr_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'REJECTED'))
);
