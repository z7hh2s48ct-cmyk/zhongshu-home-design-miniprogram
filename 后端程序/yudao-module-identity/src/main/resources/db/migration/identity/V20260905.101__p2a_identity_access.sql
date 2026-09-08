-- V20260905.101：P2A 身份域——账号、微信身份、授权码批次/授权码、兑换事实、使用权、用户会话
-- 归属：identity 模块（account / wechat / accesscode / session 领域包）
-- D-09 已确认：一个账号一个身份；员工后台权限走底座 system_users，不在此建模。

CREATE TABLE account
(
    id          BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    nickname    VARCHAR(64) NULL,
    avatar      VARCHAR(512) NULL,
    preferences JSONB       NULL,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'DISABLED', 'CLOSED'))
);

CREATE TABLE wechat_identity
(
    id          BIGINT      NOT NULL,
    appid       VARCHAR(32) NOT NULL,
    openid      VARCHAR(64) NOT NULL,
    unionid     VARCHAR(64) NULL,
    account_id  BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_wechat_identity PRIMARY KEY (id),
    CONSTRAINT uk_wechat_identity_appid_openid UNIQUE (appid, openid)
);
CREATE INDEX idx_wechat_identity_account ON wechat_identity (account_id);

CREATE TABLE design_access_code_batch
(
    id                 BIGINT       NOT NULL,
    quantity           INT          NOT NULL,
    delivery_mode      VARCHAR(16)  NOT NULL,
    exposed_count      INT          NOT NULL DEFAULT 0,
    issued_by          VARCHAR(64)  NOT NULL,
    purpose_note       VARCHAR(512) NULL,
    expires_at         TIMESTAMPTZ  NULL,
    -- TICKET 模式：明文仅存于该加密制品（AES-256-GCM，nonce 内嵌于字节流头部）；首次交付后置 NULL 永久销毁
    encrypted_artifact BYTEA        NULL,
    artifact_nonce     BYTEA        NULL,
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    creator            VARCHAR(64)  NULL DEFAULT '',
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater            VARCHAR(64)  NULL DEFAULT '',
    update_time        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_access_code_batch PRIMARY KEY (id),
    CONSTRAINT ck_design_access_code_batch_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_design_access_code_batch_mode CHECK (delivery_mode IN ('INLINE', 'TICKET'))
);

CREATE TABLE design_access_code
(
    id                BIGINT      NOT NULL,
    batch_id          BIGINT      NOT NULL,
    code_hash         VARCHAR(64) NOT NULL,
    pepper_version    VARCHAR(16) NOT NULL,
    code_mask         VARCHAR(32) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at       TIMESTAMPTZ NULL,
    secret_exposed_at TIMESTAMPTZ NULL,
    expires_at        TIMESTAMPTZ NULL,
    tenant_id         BIGINT      NOT NULL DEFAULT 0,
    creator           VARCHAR(64) NULL DEFAULT '',
    create_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater           VARCHAR(64) NULL DEFAULT '',
    update_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_access_code PRIMARY KEY (id),
    CONSTRAINT uk_design_access_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_design_access_code_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'DISABLED'))
);
CREATE INDEX idx_design_access_code_batch ON design_access_code (batch_id);

CREATE TABLE access_code_redemption
(
    id          BIGINT      NOT NULL,
    code_id     BIGINT      NOT NULL,
    account_id  BIGINT      NOT NULL,
    appid       VARCHAR(32) NOT NULL,
    openid      VARCHAR(64) NOT NULL,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_access_code_redemption PRIMARY KEY (id),
    CONSTRAINT uk_access_code_redemption_code UNIQUE (code_id)
);
CREATE INDEX idx_access_code_redemption_account ON access_code_redemption (account_id);

CREATE TABLE design_access_grant
(
    id          BIGINT      NOT NULL,
    account_id  BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ NULL,
    revoked_by  VARCHAR(64) NULL,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_access_grant PRIMARY KEY (id),
    CONSTRAINT ck_design_access_grant_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);
-- 同一账号同时最多一条有效授权；撤销后重新兑换=新行
CREATE UNIQUE INDEX uk_design_access_grant_active ON design_access_grant (account_id) WHERE status = 'ACTIVE';

CREATE TABLE user_session
(
    id                BIGINT      NOT NULL,
    account_id        BIGINT      NOT NULL,
    appid             VARCHAR(32) NOT NULL,
    openid            VARCHAR(64) NOT NULL,
    token_hash        VARCHAR(64) NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL,
    device_digest     VARCHAR(64) NULL,
    restricted        BOOLEAN     NOT NULL DEFAULT TRUE,
    expires_at        TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ NULL,
    tenant_id         BIGINT      NOT NULL DEFAULT 0,
    creator           VARCHAR(64) NULL DEFAULT '',
    create_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater           VARCHAR(64) NULL DEFAULT '',
    update_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_user_session PRIMARY KEY (id),
    CONSTRAINT uk_user_session_token UNIQUE (token_hash),
    CONSTRAINT uk_user_session_refresh UNIQUE (refresh_token_hash)
);
CREATE INDEX idx_user_session_account ON user_session (account_id);
