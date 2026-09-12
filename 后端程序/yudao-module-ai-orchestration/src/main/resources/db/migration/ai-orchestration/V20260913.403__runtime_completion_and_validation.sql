ALTER TABLE ai_job ADD COLUMN runtime_completed_at TIMESTAMPTZ;
ALTER TABLE ai_job ADD COLUMN input_snapshot JSONB;
ALTER TABLE ai_job_result ADD COLUMN validation_token VARCHAR(36);
ALTER TABLE ai_job_result ADD COLUMN validation_expires_at TIMESTAMPTZ;
ALTER TABLE ai_job_result ADD COLUMN validation_due_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE ai_job_result ADD COLUMN scan_evidence VARCHAR(512);
CREATE INDEX idx_ai_result_validation_due ON ai_job_result (validation_due_at, id) WHERE validation_state='OUTPUT_QUARANTINED';
CREATE INDEX idx_ai_job_deadline ON ai_job (create_time, id) WHERE status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED');
