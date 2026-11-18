-- Admin credit adjustment ledger entries, ledger remarks, and redemption code (CDK) tables.

ALTER TABLE credit_ledger
    ADD COLUMN remark TEXT;

ALTER TABLE credit_ledger
    DROP CONSTRAINT ck_credit_ledger_type;

ALTER TABLE credit_ledger
    ADD CONSTRAINT ck_credit_ledger_type
        CHECK (entry_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'GRANT', 'ADJUST', 'REDEEM'));

CREATE TABLE credit_redemption_code (
    id              UUID        NOT NULL,
    batch_name      TEXT        NOT NULL,
    code            TEXT        NOT NULL,
    amount_units    BIGINT      NOT NULL,
    max_uses        INTEGER     NOT NULL DEFAULT 1,
    redeemed_count  INTEGER     NOT NULL DEFAULT 0,
    per_user_limit  INTEGER     NOT NULL DEFAULT 1,
    status          TEXT        NOT NULL,
    expires_at      TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_credit_redemption_code PRIMARY KEY (id),
    CONSTRAINT uk_credit_redemption_code UNIQUE (code),
    CONSTRAINT fk_credit_redemption_creator FOREIGN KEY (created_by)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT ck_credit_redemption_amount CHECK (amount_units > 0),
    CONSTRAINT ck_credit_redemption_uses CHECK (max_uses >= 1 AND redeemed_count >= 0 AND redeemed_count <= max_uses),
    CONSTRAINT ck_credit_redemption_per_user CHECK (per_user_limit >= 1),
    CONSTRAINT ck_credit_redemption_status CHECK (status IN ('ACTIVE', 'VOIDED'))
);

CREATE INDEX idx_credit_redemption_batch
    ON credit_redemption_code (batch_name, created_at DESC);

CREATE INDEX idx_credit_redemption_status
    ON credit_redemption_code (status, created_at DESC);

CREATE TABLE credit_redemption_record (
    id           UUID        NOT NULL,
    code_id      UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    ledger_id    UUID        NOT NULL,
    amount_units BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_credit_redemption_record PRIMARY KEY (id),
    CONSTRAINT fk_redemption_record_code FOREIGN KEY (code_id)
        REFERENCES credit_redemption_code (id) ON DELETE CASCADE,
    CONSTRAINT fk_redemption_record_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_redemption_record_ledger FOREIGN KEY (ledger_id)
        REFERENCES credit_ledger (id),
    CONSTRAINT ck_redemption_record_amount CHECK (amount_units > 0)
);

CREATE INDEX idx_redemption_record_code_user
    ON credit_redemption_record (code_id, user_id);
