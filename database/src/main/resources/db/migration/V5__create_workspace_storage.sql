-- Workspace storage library: items are owned by a workspace and bound to conversations.

CREATE TABLE workspace_item (
    id                    UUID        NOT NULL,
    workspace_id          UUID        NOT NULL,
    kind                  TEXT        NOT NULL,
    title                 TEXT        NOT NULL,
    path                  TEXT,
    mime_type             TEXT,
    storage_kind          TEXT        NOT NULL,
    current_version       INTEGER     NOT NULL,
    status                TEXT        NOT NULL,
    visibility            TEXT        NOT NULL,
    created_by            UUID        NOT NULL,
    origin_conversation_id UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT pk_workspace_item PRIMARY KEY (id),
    CONSTRAINT fk_workspace_item_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_item_creator FOREIGN KEY (created_by)
        REFERENCES user_account (id),
    CONSTRAINT fk_workspace_item_origin FOREIGN KEY (origin_conversation_id)
        REFERENCES conversation (id) ON DELETE SET NULL,
    CONSTRAINT ck_workspace_item_kind
        CHECK (kind IN ('SCRIPT', 'STORYBOARD', 'PROMPT', 'IMAGE', 'VIDEO', 'OTHER')),
    CONSTRAINT ck_workspace_item_storage_kind
        CHECK (storage_kind IN ('DB_TEXT', 'OBJECT_STORAGE')),
    CONSTRAINT ck_workspace_item_status
        CHECK (status IN ('DRAFT', 'READY', 'ARCHIVED')),
    CONSTRAINT ck_workspace_item_visibility
        CHECK (visibility IN ('PRIVATE', 'WORKSPACE')),
    CONSTRAINT ck_workspace_item_version CHECK (current_version >= 1)
);

CREATE UNIQUE INDEX uk_workspace_item_path
    ON workspace_item (workspace_id, path)
    WHERE deleted_at IS NULL AND path IS NOT NULL;

CREATE INDEX idx_workspace_item_workspace
    ON workspace_item (workspace_id, updated_at DESC);
CREATE INDEX idx_workspace_item_creator
    ON workspace_item (created_by, updated_at DESC);
CREATE INDEX idx_workspace_item_kind
    ON workspace_item (workspace_id, kind, updated_at DESC);

CREATE TABLE workspace_item_version (
    id                  UUID        NOT NULL,
    item_id             UUID        NOT NULL,
    version             INTEGER     NOT NULL,
    content             TEXT,
    asset_id            UUID,
    object_key          TEXT,
    size_bytes          BIGINT,
    checksum            TEXT,
    source              TEXT        NOT NULL,
    created_by          UUID        NOT NULL,
    tool_invocation_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_workspace_item_version PRIMARY KEY (id),
    CONSTRAINT fk_workspace_item_version_item FOREIGN KEY (item_id)
        REFERENCES workspace_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_item_version_creator FOREIGN KEY (created_by)
        REFERENCES user_account (id),
    CONSTRAINT ck_workspace_item_version_number CHECK (version >= 1),
    CONSTRAINT ck_workspace_item_version_size
        CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_workspace_item_version_source
        CHECK (source IN ('USER_UPLOAD', 'AGENT', 'TOOL', 'GENERATED')),
    CONSTRAINT uk_workspace_item_version UNIQUE (item_id, version)
);

CREATE INDEX idx_workspace_item_version_item
    ON workspace_item_version (item_id, version DESC);

CREATE TABLE conversation_item (
    id              UUID        NOT NULL,
    workspace_id    UUID        NOT NULL,
    conversation_id UUID        NOT NULL,
    item_id         UUID        NOT NULL,
    role            TEXT        NOT NULL,
    pinned_version  INTEGER,
    added_by        UUID        NOT NULL,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_conversation_item PRIMARY KEY (id),
    CONSTRAINT fk_conversation_item_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_item_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_item_item FOREIGN KEY (item_id)
        REFERENCES workspace_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_item_adder FOREIGN KEY (added_by)
        REFERENCES user_account (id),
    CONSTRAINT ck_conversation_item_role
        CHECK (role IN ('WORKING', 'ATTACHMENT', 'REFERENCE', 'OUTPUT')),
    CONSTRAINT ck_conversation_item_pinned_version
        CHECK (pinned_version IS NULL OR pinned_version >= 1),
    CONSTRAINT uk_conversation_item UNIQUE (conversation_id, item_id)
);

CREATE INDEX idx_conversation_item_conversation
    ON conversation_item (conversation_id, added_at);
CREATE INDEX idx_conversation_item_item
    ON conversation_item (item_id);
