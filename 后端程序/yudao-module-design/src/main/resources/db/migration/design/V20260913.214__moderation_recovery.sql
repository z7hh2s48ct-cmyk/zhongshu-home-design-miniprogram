ALTER TABLE asset ADD COLUMN moderation_retry_at TIMESTAMPTZ;
ALTER TABLE asset ADD COLUMN moderation_attempts INT NOT NULL DEFAULT 0;
CREATE INDEX idx_asset_moderation_retry ON asset (moderation_retry_at, id) WHERE upload_status IN ('PENDING','VALIDATING') AND deleted=FALSE;
CREATE TABLE moderation_daily_usage (usage_day DATE PRIMARY KEY, calls INT NOT NULL CHECK (calls >= 0));
ALTER TABLE asset_scan_result DROP CONSTRAINT ck_asset_scan_result_status;
ALTER TABLE asset_scan_result ADD CONSTRAINT ck_asset_scan_result_status CHECK (status IN ('PENDING','PASSED','REJECTED'));
