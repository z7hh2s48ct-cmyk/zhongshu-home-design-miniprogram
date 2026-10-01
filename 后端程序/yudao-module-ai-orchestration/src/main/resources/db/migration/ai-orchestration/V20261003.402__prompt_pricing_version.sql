-- Pre-upgrade jobs keep their original quote and never acquire a newly introduced fee.
ALTER TABLE ai_job ADD COLUMN prompt_pricing_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_job ADD CONSTRAINT ck_prompt_pricing_version CHECK (prompt_pricing_version IN (0,1));
DO $$ BEGIN
    IF to_regclass('service_usage_charge') IS NOT NULL THEN
        UPDATE ai_job j SET prompt_pricing_version=1 WHERE EXISTS (
            SELECT 1 FROM service_usage_charge c WHERE c.product='AI_PROMPT'
              AND c.biz_type='ai_job' AND c.biz_id=j.id::text AND c.user_id=j.user_id AND c.deleted=FALSE
        );
    END IF;
END $$;
