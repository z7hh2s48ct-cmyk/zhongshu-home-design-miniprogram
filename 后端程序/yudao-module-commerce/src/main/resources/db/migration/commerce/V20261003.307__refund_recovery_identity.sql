-- Persist merchant refund identity before any channel call, including pre-upgrade rows.
UPDATE refund_order SET channel_refund_id = 'refund-' || id::text
WHERE channel_refund_id IS NULL OR btrim(channel_refund_id) = '';
ALTER TABLE refund_order ALTER COLUMN channel_refund_id SET NOT NULL;
CREATE UNIQUE INDEX uk_refund_channel_identity ON refund_order (channel_refund_id);
ALTER TABLE refund_order DROP CONSTRAINT ck_refund_channel_state;
ALTER TABLE refund_order ADD CONSTRAINT ck_refund_channel_state CHECK
    (channel_state IN ('CREATED','PROCESSING','SUCCEEDED','UNKNOWN','FAILED','ABNORMAL'));
ALTER TABLE refund_order ADD COLUMN next_reconcile_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE refund_order ADD COLUMN reconcile_attempts INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_refund_due ON refund_order (next_reconcile_at, id)
WHERE point_reversal_state = 'RESERVED' AND deleted = FALSE;
