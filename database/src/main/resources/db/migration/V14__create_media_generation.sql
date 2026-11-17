-- First stage of the media pipeline: model capabilities, durable generation tasks, message
-- attachments and the minimal credit ledger. Binary payloads stay in object storage; these tables
-- hold identity, state, provenance and billing references only.

ALTER TABLE model_definition
    ALTER COLUMN max_input_tokens DROP NOT NULL,
    ALTER COLUMN max_output_tokens DROP NOT NULL,
    ADD COLUMN capability_type TEXT NOT NULL DEFAULT 'CHAT',
    ADD COLUMN protocol TEXT NOT NULL DEFAULT 'OPENAI_RESPONSES',
    ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'INLINE_RESULT',
    ADD COLUMN price_units BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN supports_image_input BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE model_definition
    ADD CONSTRAINT ck_model_definition_capability
        CHECK (capability_type IN ('CHAT', 'IMAGE', 'VIDEO')),
    ADD CONSTRAINT ck_model_definition_protocol
        CHECK (protocol IN (
            'OPENAI_RESPONSES', 'OPENAI_IMAGES', 'ARK_IMAGES', 'ARK_VIDEO_TASKS',
            'DASHSCOPE_IMAGE_MESSAGES', 'DASHSCOPE_T2I', 'DASHSCOPE_VIDEO',
            'GATEWAY_CUSTOM', 'STUB')),
    ADD CONSTRAINT ck_model_definition_execution
        CHECK (execution_mode IN ('INLINE_RESULT', 'REMOTE_TASK')),
    ADD CONSTRAINT ck_model_definition_price CHECK (price_units >= 0),
    ADD CONSTRAINT ck_model_definition_chat_tokens
        CHECK (capability_type <> 'CHAT'
            OR (max_input_tokens IS NOT NULL AND max_input_tokens > 0
                AND max_output_tokens IS NOT NULL AND max_output_tokens > 0));

DROP INDEX uk_model_definition_default;
CREATE UNIQUE INDEX uk_model_definition_default
    ON model_definition (capability_type)
    WHERE default_model;

-- Start requests now cover both AgentRun and media generation workflows. business_id is the
-- AgentRun id or the generation task id; the compensator dispatches on workflow_type only.
ALTER TABLE workflow_start_request
    ALTER COLUMN agent_run_id DROP NOT NULL,
    ADD COLUMN workflow_type TEXT NOT NULL DEFAULT 'AGENT_RUN',
    ADD COLUMN business_id UUID,
    ADD COLUMN input_schema_version INTEGER NOT NULL DEFAULT 1;

UPDATE workflow_start_request
SET business_id = agent_run_id
WHERE business_id IS NULL;

ALTER TABLE workflow_start_request
    ALTER COLUMN business_id SET NOT NULL,
    ADD CONSTRAINT ck_workflow_start_type
        CHECK (workflow_type IN ('AGENT_RUN', 'MEDIA_GENERATION'));

CREATE UNIQUE INDEX uk_workflow_start_business
    ON workflow_start_request (workflow_type, business_id);

CREATE TABLE generation_task (
    id                  UUID        NOT NULL,
    user_id             UUID        NOT NULL,
    conversation_id     UUID        NOT NULL,
    agent_run_id        UUID,
    tool_invocation_id  UUID,
    type                TEXT        NOT NULL,
    model_definition_id UUID        NOT NULL,
    protocol            TEXT        NOT NULL,
    execution_mode      TEXT        NOT NULL,
    prompt              TEXT        NOT NULL,
    parameters          TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    provider_status     TEXT,
    delivery_status     TEXT        NOT NULL,
    billing_status      TEXT        NOT NULL,
    cancel_status       TEXT        NOT NULL,
    price_units         BIGINT      NOT NULL,
    budget_units        BIGINT      NOT NULL,
    idempotency_key     TEXT,
    request_fingerprint TEXT,
    source_task_id      UUID,
    workflow_id         TEXT        NOT NULL,
    output_file_id      UUID,
    error_message       TEXT,
    state_version       INTEGER     NOT NULL DEFAULT 0,
    cancel_requested_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    CONSTRAINT pk_generation_task PRIMARY KEY (id),
    CONSTRAINT fk_generation_task_user FOREIGN KEY (user_id)
        REFERENCES user_account (id),
    CONSTRAINT fk_generation_task_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_generation_task_run FOREIGN KEY (agent_run_id)
        REFERENCES agent_run (id) ON DELETE SET NULL,
    CONSTRAINT fk_generation_task_model FOREIGN KEY (model_definition_id)
        REFERENCES model_definition (id),
    CONSTRAINT fk_generation_task_source FOREIGN KEY (source_task_id)
        REFERENCES generation_task (id),
    CONSTRAINT ck_generation_task_type CHECK (type IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_generation_task_status CHECK (status IN (
        'PENDING', 'SUBMITTING', 'RUNNING', 'ARCHIVING', 'PROCESSING',
        'SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN',
        'ARCHIVE_FAILED', 'DELIVERY_FAILED')),
    CONSTRAINT ck_generation_task_delivery CHECK (delivery_status IN (
        'PENDING', 'ARCHIVED', 'PUBLISHED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_generation_task_billing CHECK (billing_status IN (
        'RESERVED', 'PENDING_RECONCILIATION', 'SETTLED', 'RELEASED')),
    CONSTRAINT ck_generation_task_cancel CHECK (cancel_status IN (
        'NONE', 'REQUESTED', 'CONFIRMED', 'UNSUPPORTED', 'TOO_LATE', 'UNKNOWN')),
    CONSTRAINT ck_generation_task_price CHECK (price_units >= 0),
    CONSTRAINT ck_generation_task_budget CHECK (budget_units >= 0),
    CONSTRAINT ck_generation_task_version CHECK (state_version >= 0),
    CONSTRAINT uk_generation_task_workflow UNIQUE (workflow_id)
);

CREATE UNIQUE INDEX uk_generation_task_idempotency
    ON generation_task (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_generation_task_conversation
    ON generation_task (conversation_id, created_at DESC);

CREATE INDEX idx_generation_task_user
    ON generation_task (user_id, created_at DESC);

CREATE INDEX idx_generation_task_status
    ON generation_task (status, created_at);

CREATE TABLE generation_attempt (
    id                  UUID        NOT NULL,
    task_id             UUID        NOT NULL,
    attempt_no          INTEGER     NOT NULL,
    provider_model_id   TEXT        NOT NULL,
    request_id          TEXT,
    remote_task_id      TEXT,
    status              TEXT        NOT NULL,
    usage_payload       TEXT,
    error_message       TEXT,
    submitted_at        TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_generation_attempt PRIMARY KEY (id),
    CONSTRAINT fk_generation_attempt_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id) ON DELETE CASCADE,
    CONSTRAINT ck_generation_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_generation_attempt_status CHECK (status IN (
        'SUBMITTING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    CONSTRAINT uk_generation_attempt_task_no UNIQUE (task_id, attempt_no)
);

CREATE INDEX idx_generation_attempt_task
    ON generation_attempt (task_id, attempt_no DESC);

CREATE TABLE generation_input (
    id              UUID        NOT NULL,
    task_id         UUID        NOT NULL,
    asset_id        UUID        NOT NULL,
    file_version_id UUID,
    role            TEXT        NOT NULL,
    ordinal         INTEGER     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_generation_input PRIMARY KEY (id),
    CONSTRAINT fk_generation_input_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_generation_input_asset FOREIGN KEY (asset_id)
        REFERENCES asset (id),
    CONSTRAINT fk_generation_input_version FOREIGN KEY (file_version_id)
        REFERENCES conversation_file_version (id) ON DELETE SET NULL,
    CONSTRAINT ck_generation_input_role CHECK (role IN (
        'REFERENCE', 'INIT_IMAGE', 'MASK', 'FIRST_FRAME', 'LAST_FRAME')),
    CONSTRAINT ck_generation_input_ordinal CHECK (ordinal >= 0),
    CONSTRAINT uk_generation_input_ordinal UNIQUE (task_id, ordinal)
);

CREATE TABLE generation_output (
    id              UUID        NOT NULL,
    task_id         UUID        NOT NULL,
    attempt_id      UUID,
    output_index    INTEGER     NOT NULL,
    asset_id        UUID,
    file_id         UUID,
    file_version_id UUID,
    status          TEXT        NOT NULL,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_generation_output PRIMARY KEY (id),
    CONSTRAINT fk_generation_output_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_generation_output_attempt FOREIGN KEY (attempt_id)
        REFERENCES generation_attempt (id) ON DELETE SET NULL,
    CONSTRAINT fk_generation_output_asset FOREIGN KEY (asset_id)
        REFERENCES asset (id) ON DELETE SET NULL,
    CONSTRAINT fk_generation_output_file FOREIGN KEY (file_id)
        REFERENCES conversation_file (id) ON DELETE SET NULL,
    CONSTRAINT fk_generation_output_version FOREIGN KEY (file_version_id)
        REFERENCES conversation_file_version (id) ON DELETE SET NULL,
    CONSTRAINT ck_generation_output_index CHECK (output_index >= 0),
    CONSTRAINT ck_generation_output_status CHECK (status IN (
        'PENDING', 'ARCHIVING', 'ARCHIVED', 'PUBLISHED', 'FAILED')),
    CONSTRAINT uk_generation_output_index UNIQUE (task_id, output_index)
);

CREATE INDEX idx_generation_output_task
    ON generation_output (task_id, output_index);

-- Assets explicitly attached to a user message. The main model sees them as input_image content
-- blocks for the current turn; the binary itself stays in object storage.
CREATE TABLE message_part (
    id                 UUID        NOT NULL,
    message_id         UUID        NOT NULL,
    part_type          TEXT        NOT NULL,
    ordinal            INTEGER     NOT NULL,
    content            TEXT,
    asset_id           UUID,
    generation_task_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_message_part PRIMARY KEY (id),
    CONSTRAINT fk_message_part_message FOREIGN KEY (message_id)
        REFERENCES message (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_part_asset FOREIGN KEY (asset_id)
        REFERENCES asset (id) ON DELETE CASCADE,
    CONSTRAINT ck_message_part_type CHECK (part_type IN (
        'TEXT', 'ASSET_REFERENCE', 'GENERATION_TASK')),
    CONSTRAINT ck_message_part_ordinal CHECK (ordinal >= 0),
    CONSTRAINT uk_message_part_ordinal UNIQUE (message_id, ordinal)
);

CREATE INDEX idx_message_part_message
    ON message_part (message_id, ordinal);

CREATE TABLE credit_account (
    user_id        UUID        NOT NULL,
    balance_units  BIGINT      NOT NULL DEFAULT 0,
    reserved_units BIGINT      NOT NULL DEFAULT 0,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_credit_account PRIMARY KEY (user_id),
    CONSTRAINT fk_credit_account_user FOREIGN KEY (user_id)
        REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_credit_account_balance CHECK (balance_units >= 0),
    CONSTRAINT ck_credit_account_reserved
        CHECK (reserved_units >= 0 AND reserved_units <= balance_units)
);

CREATE TABLE credit_reservation (
    id           UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    business_key TEXT        NOT NULL,
    task_id      UUID,
    amount_units BIGINT      NOT NULL,
    status       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at   TIMESTAMPTZ,
    CONSTRAINT pk_credit_reservation PRIMARY KEY (id),
    CONSTRAINT fk_credit_reservation_user FOREIGN KEY (user_id)
        REFERENCES credit_account (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_credit_reservation_amount CHECK (amount_units >= 0),
    CONSTRAINT ck_credit_reservation_status
        CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED')),
    CONSTRAINT uk_credit_reservation_business UNIQUE (user_id, business_key)
);

CREATE INDEX idx_credit_reservation_task
    ON credit_reservation (task_id)
    WHERE task_id IS NOT NULL;

CREATE TABLE credit_ledger (
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    reservation_id UUID,
    entry_type     TEXT        NOT NULL,
    amount_units   BIGINT      NOT NULL,
    business_key   TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_credit_ledger PRIMARY KEY (id),
    CONSTRAINT fk_credit_ledger_user FOREIGN KEY (user_id)
        REFERENCES credit_account (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_credit_ledger_reservation FOREIGN KEY (reservation_id)
        REFERENCES credit_reservation (id) ON DELETE SET NULL,
    CONSTRAINT ck_credit_ledger_type
        CHECK (entry_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'GRANT')),
    CONSTRAINT uk_credit_ledger_business UNIQUE (business_key)
);

CREATE INDEX idx_credit_ledger_user
    ON credit_ledger (user_id, created_at DESC);
