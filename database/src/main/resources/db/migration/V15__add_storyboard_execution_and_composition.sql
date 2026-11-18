-- Storyboard execution semantics, awaited tool calls and the composition task skeleton.
-- A generation can be attached to one shot of a storyboard file; the storyboard content itself
-- remains an immutable conversation-file version.

ALTER TABLE generation_task
    ADD COLUMN shot_id TEXT,
    ADD COLUMN storyboard_file_id UUID,
    ADD COLUMN storyboard_version INTEGER;

ALTER TABLE generation_task
    ADD CONSTRAINT fk_generation_task_storyboard FOREIGN KEY (storyboard_file_id)
        REFERENCES conversation_file (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_generation_task_storyboard_version
        CHECK (storyboard_version IS NULL OR storyboard_version >= 1);

CREATE INDEX idx_generation_task_shot
    ON generation_task (conversation_id, storyboard_file_id, shot_id)
    WHERE shot_id IS NOT NULL;

ALTER TABLE tool_invocation DROP CONSTRAINT ck_tool_invocation_status;
ALTER TABLE tool_invocation
    ADD CONSTRAINT ck_tool_invocation_status
        CHECK (status IN ('RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED'));

ALTER TABLE workflow_start_request DROP CONSTRAINT ck_workflow_start_type;
ALTER TABLE workflow_start_request
    ADD CONSTRAINT ck_workflow_start_type
        CHECK (workflow_type IN ('AGENT_RUN', 'MEDIA_GENERATION', 'VIDEO_COMPOSITION'));

CREATE TABLE composition_task (
    id                  UUID        NOT NULL,
    user_id             UUID        NOT NULL,
    conversation_id     UUID        NOT NULL,
    storyboard_file_id  UUID        NOT NULL,
    storyboard_version  INTEGER     NOT NULL,
    output_spec         TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    workflow_id         TEXT        NOT NULL,
    multipart_upload_id TEXT,
    output_asset_id     UUID,
    output_file_id      UUID,
    output_version_id   UUID,
    error_message       TEXT,
    state_version       INTEGER     NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT pk_composition_task PRIMARY KEY (id),
    CONSTRAINT fk_composition_task_user FOREIGN KEY (user_id)
        REFERENCES user_account (id),
    CONSTRAINT fk_composition_task_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_composition_task_storyboard FOREIGN KEY (storyboard_file_id)
        REFERENCES conversation_file (id),
    CONSTRAINT ck_composition_task_status CHECK (status IN (
        'PENDING', 'VALIDATING', 'COMPOSING', 'UPLOADING', 'PUBLISHING',
        'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_composition_task_version CHECK (storyboard_version >= 1),
    CONSTRAINT ck_composition_task_state CHECK (state_version >= 0),
    CONSTRAINT uk_composition_task_workflow UNIQUE (workflow_id)
);

CREATE INDEX idx_composition_task_conversation
    ON composition_task (conversation_id, created_at DESC);

CREATE INDEX idx_composition_task_status
    ON composition_task (status, created_at);

CREATE TABLE composition_input (
    id                  UUID        NOT NULL,
    task_id             UUID        NOT NULL,
    shot_id             TEXT        NOT NULL,
    generation_task_id  UUID        NOT NULL,
    asset_id            UUID        NOT NULL,
    file_version_id     UUID,
    start_seconds       NUMERIC(12,3) NOT NULL,
    end_seconds         NUMERIC(12,3) NOT NULL,
    ordinal             INTEGER     NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_composition_input PRIMARY KEY (id),
    CONSTRAINT fk_composition_input_task FOREIGN KEY (task_id)
        REFERENCES composition_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_composition_input_generation FOREIGN KEY (generation_task_id)
        REFERENCES generation_task (id),
    CONSTRAINT fk_composition_input_asset FOREIGN KEY (asset_id)
        REFERENCES asset (id),
    CONSTRAINT fk_composition_input_version FOREIGN KEY (file_version_id)
        REFERENCES conversation_file_version (id) ON DELETE SET NULL,
    CONSTRAINT ck_composition_input_range
        CHECK (start_seconds >= 0 AND end_seconds > start_seconds),
    CONSTRAINT ck_composition_input_ordinal CHECK (ordinal >= 0),
    CONSTRAINT uk_composition_input_ordinal UNIQUE (task_id, ordinal)
);

CREATE INDEX idx_composition_input_task
    ON composition_input (task_id, ordinal);
