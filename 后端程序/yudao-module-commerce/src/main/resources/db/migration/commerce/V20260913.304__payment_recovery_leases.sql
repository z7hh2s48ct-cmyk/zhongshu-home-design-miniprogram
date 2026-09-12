-- RG1/R04/R05: persistent prepay attempts and a fair, leased recovery queue.
ALTER TABLE recharge_order ADD COLUMN payer_openid VARCHAR(128);
ALTER TABLE recharge_order ADD COLUMN payment_channel VARCHAR(32);
ALTER TABLE recharge_order ADD COLUMN payment_merchant VARCHAR(128);
ALTER TABLE recharge_order ADD COLUMN prepay_lease_token VARCHAR(36);
ALTER TABLE recharge_order ADD COLUMN prepay_lease_until TIMESTAMPTZ;
ALTER TABLE recharge_order ADD COLUMN prepay_expires_at TIMESTAMPTZ;
ALTER TABLE recharge_order ADD COLUMN recovery_after TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE recharge_order ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_recharge_recovery ON recharge_order (recovery_after, id)
    WHERE deleted = FALSE AND fulfillment_state <> 'CREDITED'
      AND payment_state IN ('CREATED','PENDING','UNKNOWN','SUCCEEDED');
-- Historic confirmed transactions provide authoritative channel ownership. Unconfirmed historic
-- orders must be assigned from verified merchant records before unattended channel recovery.
UPDATE recharge_order o SET payment_channel=t.channel, payment_merchant=t.merchant_id
FROM payment_transaction t WHERE t.order_no=o.order_no AND t.deleted=FALSE;
