-- Identity authentication, RBAC and audit logging.

ALTER TABLE user_account
    ADD COLUMN password_hash TEXT,
    ADD COLUMN role TEXT NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_user_account_role CHECK (role IN ('ADMIN', 'USER'));

CREATE TABLE refresh_token (
    id                   UUID        NOT NULL,
    user_id              UUID        NOT NULL,
    token_hash           TEXT        NOT NULL,
    issued_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    revoked_at           TIMESTAMPTZ,
    replaced_by_token_id UUID,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_token_user ON refresh_token (user_id);
CREATE INDEX idx_refresh_token_expires ON refresh_token (expires_at);

CREATE TABLE audit_log (
    id            UUID        NOT NULL,
    type          TEXT        NOT NULL,
    sub_type      TEXT        NOT NULL,
    biz_no        TEXT,
    operator_id   UUID,
    operator_name TEXT,
    workspace_id  UUID,
    success       BOOLEAN     NOT NULL,
    detail        TEXT,
    extra         JSONB,
    trace_id      TEXT,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_log_biz_no ON audit_log (biz_no);
CREATE INDEX idx_audit_log_operator ON audit_log (operator_id);
CREATE INDEX idx_audit_log_workspace ON audit_log (workspace_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX idx_audit_log_trace ON audit_log (trace_id);
