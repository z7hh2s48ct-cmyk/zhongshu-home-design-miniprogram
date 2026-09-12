-- RG1/R03: each validation attempt owns a token; accepted object keys are server-only.
ALTER TABLE asset ADD COLUMN validation_token VARCHAR(36);
COMMENT ON COLUMN asset.validation_token IS 'Validation attempt fencing token; stale scanners cannot publish a newer attempt';
