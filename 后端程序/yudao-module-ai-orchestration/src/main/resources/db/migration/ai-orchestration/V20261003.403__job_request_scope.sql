-- Existing merchant/job identities and historical ledger keys are never rewritten.
DROP INDEX uk_ai_job_idem;
CREATE UNIQUE INDEX uk_ai_job_user_idem ON ai_job(user_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
ALTER TABLE ai_job ADD COLUMN request_resolution VARCHAR(16);
UPDATE ai_job j SET request_resolution=COALESCE(j.input_snapshot->'imageOptions'->>'resolution','2K');
DO $$ BEGIN
    IF to_regclass('generation_price_rule') IS NOT NULL AND to_regclass('ai_task_charge') IS NOT NULL THEN
        UPDATE ai_job j SET request_resolution=p.resolution FROM ai_task_charge c
            JOIN generation_price_rule p ON p.id=c.price_rule_id
            WHERE c.job_id=j.id AND j.input_snapshot->'imageOptions'->>'resolution' IS NULL;
    END IF;
END $$;
ALTER TABLE ai_job ALTER COLUMN request_resolution SET DEFAULT '2K';
ALTER TABLE ai_job ALTER COLUMN request_resolution SET NOT NULL;
ALTER TABLE ai_job ADD CONSTRAINT ck_ai_job_request_resolution CHECK(request_resolution IN ('2K','4K'));
