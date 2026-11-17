-- Binary assets and direct-to-object-storage uploads. The database row is the permanent identity;
-- temporary presigned URLs are never stored as the asset address.

CREATE TABLE asset (
    id                 UUID        NOT NULL,
    user_id            UUID        NOT NULL,
    conversation_id    UUID        NOT NULL,
    kind               TEXT        NOT NULL,
    storage_backend    TEXT        NOT NULL,
    bucket             TEXT        NOT NULL,
    object_key         TEXT        NOT NULL,
    mime_type          TEXT        NOT NULL,
    size_bytes         BIGINT      NOT NULL,
    checksum           TEXT,
    width              INTEGER,
    height             INTEGER,
    duration_ms        BIGINT,
    source             TEXT        NOT NULL,
    generation_task_id UUID,
    status             TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT pk_asset PRIMARY KEY (id),
    CONSTRAINT fk_asset_user FOREIGN KEY (user_id)
        REFERENCES user_account (id),
    CONSTRAINT fk_asset_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_kind
        CHECK (kind IN ('IMAGE', 'VIDEO', 'DOCUMENT', 'OTHER')),
    CONSTRAINT ck_asset_storage_backend
        CHECK (storage_backend IN ('S3', 'LOCAL')),
    CONSTRAINT ck_asset_mime_type CHECK (length(mime_type) > 0),
    CONSTRAINT ck_asset_object_key CHECK (length(object_key) > 0),
    CONSTRAINT ck_asset_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_asset_width CHECK (width IS NULL OR width > 0),
    CONSTRAINT ck_asset_height CHECK (height IS NULL OR height > 0),
    CONSTRAINT ck_asset_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_asset_source
        CHECK (source IN ('UPLOAD', 'GENERATION', 'DERIVED')),
    CONSTRAINT ck_asset_status
        CHECK (status IN ('PENDING', 'UPLOADING', 'READY', 'FAILED', 'DELETED')),
    CONSTRAINT uk_asset_object UNIQUE (bucket, object_key)
);

CREATE INDEX idx_asset_conversation
    ON asset (conversation_id, created_at DESC);

CREATE INDEX idx_asset_user
    ON asset (user_id, created_at DESC);

CREATE INDEX idx_asset_generation_task
    ON asset (generation_task_id)
    WHERE generation_task_id IS NOT NULL;

CREATE TABLE upload_session (
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    conversation_id UUID        NOT NULL,
    asset_id        UUID        NOT NULL,
    kind            TEXT        NOT NULL,
    bucket          TEXT        NOT NULL,
    object_key      TEXT        NOT NULL,
    mime_type       TEXT        NOT NULL,
    expected_size   BIGINT      NOT NULL,
    status          TEXT        NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_upload_session PRIMARY KEY (id),
    CONSTRAINT fk_upload_session_user FOREIGN KEY (user_id)
        REFERENCES user_account (id),
    CONSTRAINT fk_upload_session_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_upload_session_asset FOREIGN KEY (asset_id)
        REFERENCES asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_upload_session_kind
        CHECK (kind IN ('IMAGE', 'VIDEO', 'DOCUMENT', 'OTHER')),
    CONSTRAINT ck_upload_session_expected_size CHECK (expected_size > 0),
    CONSTRAINT ck_upload_session_status
        CHECK (status IN ('CREATED', 'UPLOADED', 'VERIFIED', 'FAILED', 'EXPIRED', 'ABORTED')),
    CONSTRAINT uk_upload_session_object UNIQUE (bucket, object_key)
);

CREATE INDEX idx_upload_session_conversation
    ON upload_session (conversation_id, created_at DESC);

CREATE INDEX idx_upload_session_expiry
    ON upload_session (status, expires_at);

CREATE TABLE asset_variant (
    id          UUID        NOT NULL,
    asset_id    UUID        NOT NULL,
    variant     TEXT        NOT NULL,
    bucket      TEXT        NOT NULL,
    object_key  TEXT        NOT NULL,
    mime_type   TEXT        NOT NULL,
    size_bytes  BIGINT      NOT NULL,
    width       INTEGER,
    height      INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_asset_variant PRIMARY KEY (id),
    CONSTRAINT fk_asset_variant_asset FOREIGN KEY (asset_id)
        REFERENCES asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_variant_kind
        CHECK (variant IN ('THUMBNAIL', 'PREVIEW', 'COVER', 'PLAYBACK')),
    CONSTRAINT ck_asset_variant_size CHECK (size_bytes >= 0),
    CONSTRAINT uk_asset_variant UNIQUE (asset_id, variant),
    CONSTRAINT uk_asset_variant_object UNIQUE (bucket, object_key)
);

CREATE INDEX idx_asset_variant_asset
    ON asset_variant (asset_id);
