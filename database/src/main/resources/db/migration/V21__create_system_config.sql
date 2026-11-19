-- Business runtime configuration moves from Nacos into PostgreSQL so it is
-- persisted, backed up and audited with the rest of the application data.
CREATE TABLE system_config (
    data_id     TEXT        NOT NULL,
    content     JSONB       NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 1,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_system_config PRIMARY KEY (data_id),
    CONSTRAINT fk_system_config_operator FOREIGN KEY (updated_by)
        REFERENCES user_account (id) ON DELETE SET NULL,
    CONSTRAINT ck_system_config_version CHECK (version > 0)
);
