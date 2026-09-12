-- 重提回执附着现有不可变轮次，不修改历史迁移。
ALTER TABLE submission_revision ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE submission_revision ADD COLUMN response_snapshot JSONB;
CREATE UNIQUE INDEX uk_submission_revision_command ON submission_revision (submission_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
