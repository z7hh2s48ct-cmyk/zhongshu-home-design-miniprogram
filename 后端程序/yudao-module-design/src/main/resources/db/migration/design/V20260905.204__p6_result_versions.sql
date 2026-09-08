-- V20260905.204：P6 不可变最终结果版本与调整请求
CREATE TABLE design_result_version
(
    id                    BIGINT       NOT NULL,
    project_id            BIGINT       NOT NULL,
    version               BIGINT       NOT NULL,
    flat_selection_id     BIGINT       NOT NULL,
    elevation_selection_id BIGINT      NULL,
    flat_candidate_ids    JSONB        NOT NULL,
    elevation_candidate_id BIGINT      NULL,
    config_snapshot       JSONB        NULL,
    notes                 VARCHAR(512) NULL,
    superseded            BOOLEAN      NOT NULL DEFAULT FALSE,
    tenant_id             BIGINT       NOT NULL DEFAULT 0,
    creator               VARCHAR(64)  NULL DEFAULT '',
    create_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater               VARCHAR(64)  NULL DEFAULT '',
    update_time           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted               BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_result_version PRIMARY KEY (id),
    CONSTRAINT uk_design_result_version UNIQUE (project_id, version)
);

CREATE TABLE design_revision_request
(
    id               BIGINT       NOT NULL,
    project_id       BIGINT       NOT NULL,
    from_version_id  BIGINT       NOT NULL,
    reason           VARCHAR(512) NOT NULL,
    config_updates   JSONB        NULL,
    new_job_id       BIGINT       NULL,
    new_version_id   BIGINT       NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_design_revision_request PRIMARY KEY (id),
    CONSTRAINT ck_design_revision_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX idx_design_revision_project ON design_revision_request (project_id, create_time DESC);
