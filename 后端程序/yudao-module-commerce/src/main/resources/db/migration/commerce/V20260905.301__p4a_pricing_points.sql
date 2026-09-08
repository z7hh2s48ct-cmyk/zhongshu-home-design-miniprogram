-- V20260905.301：P4A 计价规则、设计点账户、只追加流水与人工调点单
-- 归属：commerce 模块（pricing / points / adjustment 领域包）
-- 约定：主键为应用侧雪花 ID（IdWorker），Redis 不参与余额判定；余额列必须非负 CHECK。

CREATE TABLE generation_price_rule
(
    id              BIGINT      NOT NULL,
    stage           VARCHAR(16) NOT NULL,
    unit_point_cost BIGINT      NOT NULL,
    min_count       INT         NOT NULL DEFAULT 1,
    max_count       INT         NOT NULL DEFAULT 4,
    effective_at    TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NULL, -- NULL 表示长期有效
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT      NOT NULL DEFAULT 1,
    tenant_id       BIGINT      NOT NULL DEFAULT 0,
    creator         VARCHAR(64) NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater         VARCHAR(64) NULL DEFAULT '',
    update_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_generation_price_rule PRIMARY KEY (id),
    CONSTRAINT ck_generation_price_rule_stage CHECK (stage IN ('FLAT', 'ELEVATION')),
    CONSTRAINT ck_generation_price_rule_cost CHECK (unit_point_cost > 0),
    CONSTRAINT ck_generation_price_rule_count CHECK (min_count >= 1 AND max_count >= min_count),
    CONSTRAINT ck_generation_price_rule_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);
CREATE INDEX idx_generation_price_rule_stage ON generation_price_rule (stage, status, effective_at DESC);

CREATE TABLE design_point_account
(
    id               BIGINT      NOT NULL,
    user_id          BIGINT      NOT NULL,
    available_points BIGINT      NOT NULL DEFAULT 0,
    reserved_points  BIGINT      NOT NULL DEFAULT 0,
    version          BIGINT      NOT NULL DEFAULT 0,
    tenant_id        BIGINT      NOT NULL DEFAULT 0,
    creator          VARCHAR(64) NULL DEFAULT '',
    create_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater          VARCHAR(64) NULL DEFAULT '',
    update_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_point_account PRIMARY KEY (id),
    CONSTRAINT uk_design_point_account_user UNIQUE (user_id),
    CONSTRAINT ck_design_point_account_available CHECK (available_points >= 0),
    CONSTRAINT ck_design_point_account_reserved CHECK (reserved_points >= 0)
);

-- 只追加流水：任何已写入行不得 UPDATE/DELETE（纠错只能新增反向流水）
CREATE TABLE design_point_ledger
(
    id              BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    type            VARCHAR(40)  NOT NULL,
    delta           BIGINT       NOT NULL,
    available_after BIGINT       NOT NULL,
    reserved_after  BIGINT       NOT NULL,
    biz_type        VARCHAR(64)  NULL,
    biz_id          VARCHAR(64)  NULL,
    idempotency_key VARCHAR(128) NULL,
    operator_id     VARCHAR(64)  NULL,
    reason          VARCHAR(512) NULL,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    creator         VARCHAR(64)  NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL DEFAULT '',
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_point_ledger PRIMARY KEY (id),
    CONSTRAINT uk_design_point_ledger_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_design_point_ledger_delta CHECK (delta <> 0),
    CONSTRAINT ck_design_point_ledger_type CHECK (type IN
            ('RECHARGE_BASE_CREDIT', 'RECHARGE_BONUS_CREDIT', 'FLAT_GENERATION_DEBIT',
             'ELEVATION_GENERATION_DEBIT', 'TASK_SETTLEMENT_REFUND', 'MANUAL_CREDIT', 'MANUAL_DEBIT')),
    CONSTRAINT ck_design_point_ledger_available CHECK (available_after >= 0),
    CONSTRAINT ck_design_point_ledger_reserved CHECK (reserved_after >= 0)
);
CREATE INDEX idx_design_point_ledger_user ON design_point_ledger (user_id, create_time DESC, id DESC);

CREATE TABLE manual_point_adjustment
(
    id              BIGINT       NOT NULL,
    target_user_id  BIGINT       NOT NULL,
    delta           BIGINT       NOT NULL,
    reason          VARCHAR(512) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    maker_user_id   BIGINT       NOT NULL,
    checker_user_id BIGINT       NULL,
    checker_comment VARCHAR(512) NULL,
    ledger_id       BIGINT       NULL,
    executed_at     TIMESTAMPTZ  NULL,
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    creator         VARCHAR(64)  NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL DEFAULT '',
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_manual_point_adjustment PRIMARY KEY (id),
    CONSTRAINT ck_manual_point_adjustment_delta CHECK (delta <> 0),
    CONSTRAINT ck_manual_point_adjustment_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'EXECUTED', 'REJECTED'))
);
CREATE INDEX idx_manual_point_adjustment_status ON manual_point_adjustment (status, create_time);
