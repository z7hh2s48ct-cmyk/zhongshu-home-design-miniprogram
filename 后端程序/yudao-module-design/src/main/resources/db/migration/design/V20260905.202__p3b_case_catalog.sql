-- V20260905.202：P3B 公司案例、不可变版本、资产关系、发布事实与收藏
-- 归属：design 模块（catalog 领域包）；AI 案例复用同组表（P7A 投稿审核后发布）

CREATE TABLE design_case
(
    id                 BIGINT      NOT NULL,
    source_type        VARCHAR(16) NOT NULL,
    creator_user_id    BIGINT      NOT NULL,
    current_version_id BIGINT      NULL,
    publication_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    tenant_id          BIGINT      NOT NULL DEFAULT 0,
    creator            VARCHAR(64) NULL DEFAULT '',
    create_time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater            VARCHAR(64) NULL DEFAULT '',
    update_time        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_case PRIMARY KEY (id),
    CONSTRAINT ck_design_case_source CHECK (source_type IN ('COMPANY', 'AI')),
    CONSTRAINT ck_design_case_pub CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'OFFLINE'))
);
CREATE INDEX idx_design_case_pub ON design_case (publication_status);

CREATE TABLE design_case_version
(
    id            BIGINT       NOT NULL,
    case_id       BIGINT       NOT NULL,
    version       BIGINT       NOT NULL,
    title         VARCHAR(128) NOT NULL,
    description   VARCHAR(1024) NULL,
    style_code    VARCHAR(32)  NOT NULL,
    floor_count   INT          NOT NULL,
    building_area INT          NOT NULL,
    face_width    INT          NULL,
    depth         INT          NULL,
    rooms         JSONB        NULL,
    tags          JSONB        NULL,
    tenant_id     BIGINT       NOT NULL DEFAULT 0,
    creator       VARCHAR(64)  NULL DEFAULT '',
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater       VARCHAR(64)  NULL DEFAULT '',
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_case_version PRIMARY KEY (id),
    CONSTRAINT uk_design_case_version UNIQUE (case_id, version),
    CONSTRAINT ck_design_case_version_area CHECK (building_area > 0),
    CONSTRAINT ck_design_case_version_floor CHECK (floor_count >= 1)
);
CREATE INDEX idx_design_case_version_browse ON design_case_version (building_area, id);

CREATE TABLE design_case_asset
(
    id              BIGINT      NOT NULL,
    case_version_id BIGINT      NOT NULL,
    asset_id        BIGINT      NOT NULL,
    asset_role      VARCHAR(16) NOT NULL,
    floor_no        INT         NULL,
    tenant_id       BIGINT      NOT NULL DEFAULT 0,
    creator         VARCHAR(64) NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater         VARCHAR(64) NULL DEFAULT '',
    update_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_case_asset PRIMARY KEY (id),
    CONSTRAINT ck_design_case_asset_role CHECK (asset_role IN ('COVER', 'FLOOR_PLAN', 'ELEVATION', 'PDF'))
);
CREATE UNIQUE INDEX uk_design_case_asset ON design_case_asset
    (case_version_id, asset_role, COALESCE(floor_no, 0));

CREATE TABLE case_publication
(
    id                   BIGINT       NOT NULL,
    case_id              BIGINT       NOT NULL,
    action               VARCHAR(16)  NOT NULL,
    published_version_id BIGINT       NULL,
    reason               VARCHAR(512) NULL,
    operator_id          VARCHAR(64)  NOT NULL,
    tenant_id            BIGINT       NOT NULL DEFAULT 0,
    creator              VARCHAR(64)  NULL DEFAULT '',
    create_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater              VARCHAR(64)  NULL DEFAULT '',
    update_time          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_case_publication PRIMARY KEY (id),
    CONSTRAINT ck_case_publication_action CHECK (action IN ('PUBLISH', 'OFFLINE'))
);
CREATE INDEX idx_case_publication_case ON case_publication (case_id, create_time DESC);

CREATE TABLE case_favorite
(
    id          BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    case_id     BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL DEFAULT 0,
    creator     VARCHAR(64) NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater     VARCHAR(64) NULL DEFAULT '',
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted     BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_case_favorite PRIMARY KEY (id),
    CONSTRAINT uk_case_favorite UNIQUE (user_id, case_id)
);
CREATE INDEX idx_case_favorite_user ON case_favorite (user_id, id DESC);
