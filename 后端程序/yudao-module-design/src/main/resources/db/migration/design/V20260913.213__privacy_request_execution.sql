ALTER TABLE data_subject_request ADD COLUMN file_key VARCHAR(512);
ALTER TABLE data_subject_request ADD COLUMN file_sha256 VARCHAR(64);
ALTER TABLE data_subject_request ADD COLUMN file_expires_at TIMESTAMPTZ;
ALTER TABLE data_subject_request ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE data_subject_request ADD COLUMN lease_expires_at TIMESTAMPTZ;
ALTER TABLE data_subject_request ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE data_subject_request ADD COLUMN error_code VARCHAR(64);
ALTER TABLE data_subject_request ADD COLUMN decision_reason VARCHAR(512);
ALTER TABLE data_subject_request ADD COLUMN retention_policy_version VARCHAR(64);
ALTER TABLE data_subject_request ADD COLUMN reviewed_by BIGINT;
-- Preserve duplicate consent rows as history, while enforcing one effective fact per version.
UPDATE privacy_consent p SET deleted=TRUE WHERE deleted=FALSE AND EXISTS
 (SELECT 1 FROM privacy_consent first WHERE first.user_id=p.user_id AND first.policy_type=p.policy_type
  AND first.version=p.version AND first.deleted=FALSE AND first.id<p.id);
CREATE UNIQUE INDEX uk_privacy_consent_version ON privacy_consent(user_id,policy_type,version) WHERE deleted=FALSE;
-- Preserve historical duplicate requests and an explicit reason; retain the first open request.
UPDATE data_subject_request r SET status='REJECTED', completed_at=now(), error_code='DUPLICATE_OPEN_REQUEST'
WHERE deleted=FALSE AND status IN ('PENDING','PROCESSING') AND EXISTS
 (SELECT 1 FROM data_subject_request first WHERE first.user_id=r.user_id AND first.request_type=r.request_type
  AND first.deleted=FALSE AND first.status IN ('PENDING','PROCESSING') AND first.id<r.id);
CREATE UNIQUE INDEX uk_privacy_open_request ON data_subject_request(user_id,request_type)
WHERE deleted=FALSE AND status IN ('PENDING','PROCESSING');
