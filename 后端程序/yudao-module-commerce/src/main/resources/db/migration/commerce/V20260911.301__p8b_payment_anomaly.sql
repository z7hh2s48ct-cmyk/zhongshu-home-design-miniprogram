-- T13-27：支付异常审计表（分派表 §3.2 ②）
-- 记录所有支付事实校验异常（金额不符 / 未知商户 / transactionId 缺失 / 实付金额回退等），
-- 供人工排查与对账。校验失败必须落审计，禁止静默吞掉。

CREATE TABLE IF NOT EXISTS payment_anomaly_audit
(
    id              BIGINT       NOT NULL,
    order_no        VARCHAR(32)  NOT NULL,
    event_id        VARCHAR(128) NULL,
    anomaly_type    VARCHAR(64)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'REJECT',
    expected_value  TEXT         NULL,
    actual_value    TEXT         NULL,
    detail          TEXT         NULL,
    channel         VARCHAR(16)  NOT NULL DEFAULT '',
    tenant_id       BIGINT       NOT NULL DEFAULT 0,
    creator         VARCHAR(64)  NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updater         VARCHAR(64)  NULL DEFAULT '',
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_payment_anomaly_audit PRIMARY KEY (id),
    CONSTRAINT ck_anomaly_severity CHECK (severity IN ('REJECT', 'AUDIT'))
);

COMMENT ON TABLE payment_anomaly_audit IS 'T13-27：支付事实校验异常审计（金额/商户/transactionId/实付回退）';
COMMENT ON COLUMN payment_anomaly_audit.anomaly_type IS
    'AMOUNT_MISMATCH / UNKNOWN_MERCHANT / TRANSACTION_ID_MISSING / CHANNEL_AMOUNT_FALLBACK / PAID_AT_MISSING';
COMMENT ON COLUMN payment_anomaly_audit.severity IS
    'REJECT = 硬拒绝（抛异常）；AUDIT = 软告警（回退 + 留痕）';

CREATE INDEX IF NOT EXISTS idx_anomaly_order ON payment_anomaly_audit (order_no, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_anomaly_type ON payment_anomaly_audit (anomaly_type, create_time DESC);
