-- Email and Webhook alerting channels and delivery audit logs.

CREATE TABLE alert_channel (
    id                 UUID        NOT NULL,
    name               TEXT        NOT NULL,
    channel_type       TEXT        NOT NULL,
    target             TEXT        NOT NULL,
    secret_ciphertext  TEXT,
    subscribed_events  TEXT        NOT NULL DEFAULT '*',
    min_severity       TEXT        NOT NULL DEFAULT 'WARNING',
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by         UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_channel PRIMARY KEY (id),
    CONSTRAINT fk_alert_channel_creator FOREIGN KEY (created_by)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT ck_alert_channel_type CHECK (channel_type IN ('EMAIL', 'WEBHOOK')),
    CONSTRAINT ck_alert_channel_severity CHECK (min_severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE INDEX idx_alert_channel_enabled
    ON alert_channel (enabled, min_severity);

CREATE TABLE alert_delivery (
    id            UUID        NOT NULL,
    channel_id    UUID        NOT NULL,
    channel_name  TEXT        NOT NULL,
    channel_type  TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,
    severity      TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    summary       TEXT        NOT NULL,
    biz_no        TEXT,
    trace_id      TEXT,
    status        TEXT        NOT NULL,
    http_status   INTEGER,
    attempt_count INTEGER     NOT NULL DEFAULT 1,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_delivery PRIMARY KEY (id),
    CONSTRAINT fk_alert_delivery_channel FOREIGN KEY (channel_id)
        REFERENCES alert_channel (id) ON DELETE CASCADE,
    CONSTRAINT ck_alert_delivery_type CHECK (channel_type IN ('EMAIL', 'WEBHOOK')),
    CONSTRAINT ck_alert_delivery_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_alert_delivery_status CHECK (status IN ('SENT', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_alert_delivery_channel
    ON alert_delivery (channel_id, created_at DESC);

CREATE INDEX idx_alert_delivery_status
    ON alert_delivery (status, created_at DESC);

CREATE INDEX idx_alert_delivery_event
    ON alert_delivery (event_type, created_at DESC);
