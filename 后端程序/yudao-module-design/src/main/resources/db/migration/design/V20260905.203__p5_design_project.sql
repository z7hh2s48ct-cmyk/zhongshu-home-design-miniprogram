-- V20260905.203：P5 设计项目、需求快照、平面候选与选择
CREATE TABLE design_project
(
    id                BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    source_type       VARCHAR(16)  NOT NULL,
    ref_case_id       BIGINT       NULL,
    ref_version_id    BIGINT       NULL,
    rights_grant_id   BIGINT       NULL,
    rights_version    BIGINT       NULL,
    rights_snapshot   JSONB        NULL, -- 审查遗留：多资产逐资产授权快照 [{assetId,grantId,rightsVersion}]
    stage             VARCHAR(16)  NOT NULL DEFAULT 'FLAT',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    tenant_id         BIGINT       NOT NULL DEFAULT 0,
    creator           VARCHAR(64)  NULL DEFAULT '',
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater           VARCHAR(64)  NULL DEFAULT '',
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_project PRIMARY KEY (id),
    CONSTRAINT ck_design_project_source CHECK (source_type IN ('CASE_REFERENCE', 'SELF_UPLOAD')),
    CONSTRAINT ck_design_project_stage CHECK (stage IN ('FLAT', 'ELEVATION')),
    CONSTRAINT ck_design_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE design_requirement_snapshot
(
    id              BIGINT      NOT NULL,
    project_id      BIGINT      NOT NULL,
    input_version   INT         NOT NULL DEFAULT 1,
    inputs          JSONB       NULL,
    sketch_asset_id BIGINT      NULL,
    tenant_id       BIGINT      NOT NULL DEFAULT 0,
    creator         VARCHAR(64) NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater         VARCHAR(64) NULL DEFAULT '',
    update_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_requirement_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_design_req_snapshot UNIQUE (project_id, input_version)
);

CREATE TABLE design_candidate
(
    id               BIGINT      NOT NULL,
    project_id       BIGINT      NOT NULL,
    job_id           BIGINT      NOT NULL,
    slot_no          INT         NOT NULL,
    asset_id         BIGINT      NOT NULL,
    ai_result_id     BIGINT      NOT NULL,
    tenant_id        BIGINT      NOT NULL DEFAULT 0,
    creator          VARCHAR(64) NULL DEFAULT '',
    create_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater          VARCHAR(64) NULL DEFAULT '',
    update_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted          BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_candidate PRIMARY KEY (id),
    CONSTRAINT uk_design_candidate_job_slot UNIQUE (job_id, slot_no)
);
CREATE INDEX idx_design_candidate_project ON design_candidate (project_id);

CREATE TABLE design_selection
(
    id              BIGINT      NOT NULL,
    project_id      BIGINT      NOT NULL,
    stage           VARCHAR(16) NOT NULL,
    candidate_id    BIGINT      NOT NULL,
    selection_seq   BIGINT      NOT NULL DEFAULT 1,
    selected_by     BIGINT      NOT NULL,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    tenant_id       BIGINT      NOT NULL DEFAULT 0,
    creator         VARCHAR(64) NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater         VARCHAR(64) NULL DEFAULT '',
    update_time     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_selection PRIMARY KEY (id),
    CONSTRAINT ck_design_selection_stage CHECK (stage IN ('FLAT', 'ELEVATION'))
);
-- 每项目每阶段最多一条有效选择
CREATE UNIQUE INDEX uk_design_selection_active ON design_selection (project_id, stage) WHERE active = TRUE;
