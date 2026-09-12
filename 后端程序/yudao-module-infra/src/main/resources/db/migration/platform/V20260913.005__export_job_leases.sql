ALTER TABLE export_job ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE export_job ADD COLUMN lease_expires_at TIMESTAMPTZ;
ALTER TABLE export_job ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE export_job ALTER COLUMN file_asset_id TYPE VARCHAR(512);
CREATE INDEX idx_export_recovery ON export_job(status, lease_expires_at, id);
