-- Files of a conversation's own work area. A file belongs to exactly one conversation and one
-- user; there is no cross-conversation or cross-user sharing.

CREATE TABLE conversation_file (
    id              UUID        NOT NULL,
    conversation_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    kind            TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    path            TEXT,
    mime_type       TEXT,
    storage_kind    TEXT        NOT NULL,
    current_version INTEGER     NOT NULL,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT pk_conversation_file PRIMARY KEY (id),
    CONSTRAINT fk_conversation_file_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_file_user FOREIGN KEY (user_id)
        REFERENCES user_account (id),
    CONSTRAINT ck_conversation_file_kind
        CHECK (kind IN ('SCRIPT', 'STORYBOARD', 'PROMPT', 'IMAGE', 'VIDEO', 'OTHER')),
    CONSTRAINT ck_conversation_file_storage_kind
        CHECK (storage_kind IN ('DB_TEXT', 'OBJECT_STORAGE')),
    CONSTRAINT ck_conversation_file_status
        CHECK (status IN ('DRAFT', 'READY', 'ARCHIVED')),
    CONSTRAINT ck_conversation_file_version CHECK (current_version >= 1)
);

CREATE UNIQUE INDEX uk_conversation_file_path
    ON conversation_file (conversation_id, path)
    WHERE deleted_at IS NULL AND path IS NOT NULL;

CREATE INDEX idx_conversation_file_conversation
    ON conversation_file (conversation_id, updated_at DESC);

CREATE TABLE conversation_file_version (
    id                 UUID        NOT NULL,
    file_id            UUID        NOT NULL,
    version            INTEGER     NOT NULL,
    content            TEXT,
    asset_id           UUID,
    object_key         TEXT,
    size_bytes         BIGINT,
    checksum           TEXT,
    source             TEXT        NOT NULL,
    created_by         UUID        NOT NULL,
    tool_invocation_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_conversation_file_version PRIMARY KEY (id),
    CONSTRAINT fk_conversation_file_version_file FOREIGN KEY (file_id)
        REFERENCES conversation_file (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_file_version_creator FOREIGN KEY (created_by)
        REFERENCES user_account (id),
    CONSTRAINT ck_conversation_file_version_number CHECK (version >= 1),
    CONSTRAINT ck_conversation_file_version_size
        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_conversation_file_version_source
        CHECK (source IN ('USER_UPLOAD', 'AGENT', 'TOOL', 'GENERATED')),
    CONSTRAINT uk_conversation_file_version UNIQUE (file_id, version)
);

CREATE INDEX idx_conversation_file_version_file
    ON conversation_file_version (file_id, version DESC);
