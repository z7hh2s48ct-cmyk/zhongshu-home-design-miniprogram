-- V20260905.201：P3A 资产、扫描与权利授权
-- 归属：design 模块（asset / rights 领域包）；下载票据复用 P1C one_time_download_ticket

CREATE TABLE asset
(
    id                   BIGINT       NOT NULL,
    object_key           VARCHAR(256) NOT NULL,
    owner_user_id        BIGINT       NOT NULL,
    asset_type           VARCHAR(32)  NOT NULL,
    source_type          VARCHAR(16)  NOT NULL,
    parent_asset_id      BIGINT       NULL,
    provenance_type      VARCHAR(32)  NULL,
    provenance_ref       VARCHAR(128) NULL,
    sha256               VARCHAR(64)  NOT NULL,   -- 原始上传内容哈希（溯源）
    declared_mime        VARCHAR(64)  NOT NULL,
    size_bytes           BIGINT       NOT NULL,   -- 原始上传大小（溯源）
    stored_sha256        VARCHAR(64)  NULL,       -- 消毒后实际入库对象哈希（完整性锚点）
    stored_size          BIGINT       NULL,       -- 消毒后实际入库大小
    width                INT          NULL,
    height               INT          NULL,
    page_count           INT          NULL,
    upload_status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    security_scan_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    moderation_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    rejected_reason      VARCHAR(256) NULL,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    creator              VARCHAR(64)  NULL DEFAULT '',
    create_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater              VARCHAR(64)  NULL DEFAULT '',
    update_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_asset PRIMARY KEY (id),
    CONSTRAINT uk_asset_object_key UNIQUE (object_key),
    CONSTRAINT ck_asset_upload_status CHECK (upload_status IN
            ('PENDING', 'UPLOADED', 'VALIDATING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_asset_scan_status CHECK (security_scan_status IN ('PENDING', 'PASSED', 'REJECTED')),
    CONSTRAINT ck_asset_moderation_status CHECK (moderation_status IN ('PENDING', 'PASSED', 'REJECTED')),
    CONSTRAINT ck_asset_size CHECK (size_bytes > 0)
);
CREATE INDEX idx_asset_owner ON asset (owner_user_id, create_time DESC);

CREATE TABLE asset_scan_result
(
    id          BIGINT       NOT NULL,
    asset_id    BIGINT       NOT NULL,
    scan_type   VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    detail      JSONB        NULL,
    tenant_id   BIGINT       NOT NULL DEFAULT 0,
    creator     VARCHAR(64)  NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater     VARCHAR(64)  NULL DEFAULT '',
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_asset_scan_result PRIMARY KEY (id),
    CONSTRAINT ck_asset_scan_result_type CHECK (scan_type IN
            ('MAGIC_NUMBER', 'SIZE_LIMIT', 'PIXEL_GUARD', 'EXIF_STRIP', 'MALWARE', 'CONTENT_MODERATION', 'DECODE')),
    CONSTRAINT ck_asset_scan_result_status CHECK (status IN ('PASSED', 'REJECTED'))
);
CREATE INDEX idx_asset_scan_result_asset ON asset_scan_result (asset_id);

-- 唯一版本化权利授权：公开展示与生成参考是两种独立 scope（架构 §6.9）
CREATE TABLE asset_rights_grant
(
    id               BIGINT       NOT NULL,
    asset_id         BIGINT       NOT NULL,
    grantor_user_id  BIGINT       NOT NULL,
    rights_holder    VARCHAR(128) NOT NULL,
    scope            VARCHAR(32)  NOT NULL,
    territories      VARCHAR(128) NOT NULL DEFAULT '*',
    purposes         VARCHAR(128) NOT NULL DEFAULT '*',
    proof_asset_id   BIGINT       NULL,
    effective_at     TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    withdrawn_at     TIMESTAMPTZ  NULL,
    rights_version   BIGINT       NOT NULL DEFAULT 1,
    parent_grant_id  BIGINT       NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_asset_rights_grant PRIMARY KEY (id),
    CONSTRAINT ck_asset_rights_grant_scope CHECK (scope IN ('PUBLIC_DISPLAY', 'GENERATION_REFERENCE')),
    CONSTRAINT ck_asset_rights_grant_status CHECK (status IN ('ACTIVE', 'WITHDRAWN'))
);
CREATE INDEX idx_asset_rights_grant_asset ON asset_rights_grant (asset_id, scope, status);
