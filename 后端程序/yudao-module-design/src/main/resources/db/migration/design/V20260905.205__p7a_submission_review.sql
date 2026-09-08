-- V20260905.205：P7A 投稿、不可变 revision、审核任务与决定（发布复用 P3B case_publication + design_case）
CREATE TABLE case_submission
(
    id               BIGINT       NOT NULL,
    project_id       BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    result_version_id BIGINT      NOT NULL,
    current_round    INT          NOT NULL DEFAULT 0,
    status           VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    latest_revision_id BIGINT     NULL,
    published_case_id BIGINT   NULL,
    public_display_granted  BOOLEAN NOT NULL DEFAULT FALSE,
    generation_reference_granted BOOLEAN NOT NULL DEFAULT FALSE,
    idempotency_key  VARCHAR(128) NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_case_submission PRIMARY KEY (id),
    CONSTRAINT ck_case_submission_status CHECK (status IN
            ('DRAFT', 'VALIDATED', 'SUBMITTED', 'IN_REVIEW', 'CHANGES_REQUESTED',
             'RESUBMITTED', 'APPROVED', 'REJECTED'))
);
CREATE UNIQUE INDEX uk_case_submission_idem ON case_submission (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_case_submission_status ON case_submission (status, create_time);

CREATE TABLE submission_revision
(
    id               BIGINT       NOT NULL,
    submission_id    BIGINT       NOT NULL,
    round_no         INT          NOT NULL,
    result_version_id BIGINT      NOT NULL,
    rights_snapshot  JSONB        NOT NULL,
    content_snapshot JSONB        NULL,
    submitted_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_submission_revision PRIMARY KEY (id),
    CONSTRAINT uk_submission_revision UNIQUE (submission_id, round_no)
);

CREATE TABLE review_task
(
    id               BIGINT       NOT NULL,
    submission_id    BIGINT       NOT NULL,
    round_no         INT          NOT NULL,
    reviewer_id      VARCHAR(64)  NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    deadline_at      TIMESTAMPTZ  NULL,
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_review_task PRIMARY KEY (id),
    CONSTRAINT uk_review_task UNIQUE (submission_id, round_no),
    CONSTRAINT ck_review_task_status CHECK (status IN ('PENDING', 'DECIDED'))
);

CREATE TABLE review_decision
(
    id               BIGINT       NOT NULL,
    review_task_id   BIGINT       NOT NULL,
    reviewer_user_id BIGINT       NOT NULL,
    decision         VARCHAR(24)  NOT NULL,
    comment          VARCHAR(1024) NULL,
    decided_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    tenant_id        BIGINT       NOT NULL DEFAULT 0,
    creator          VARCHAR(64)  NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater          VARCHAR(64)  NULL DEFAULT '',
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_review_decision PRIMARY KEY (id),
    CONSTRAINT ck_review_decision CHECK (decision IN
            ('APPROVE', 'CHANGES_REQUESTED', 'REJECT'))
);
