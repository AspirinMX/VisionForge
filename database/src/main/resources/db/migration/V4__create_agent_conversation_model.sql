-- Text model configuration and the minimal conversation / AgentRun loop.

CREATE TABLE model_definition (
    id                  UUID        NOT NULL,
    display_name        TEXT        NOT NULL,
    base_url            TEXT        NOT NULL,
    api_key_ciphertext  TEXT        NOT NULL,
    model_id            TEXT        NOT NULL,
    reasoning_effort    TEXT,
    max_input_tokens    INTEGER     NOT NULL,
    max_output_tokens   INTEGER     NOT NULL,
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    default_model       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_model_definition PRIMARY KEY (id),
    CONSTRAINT ck_model_definition_reasoning
        CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_model_definition_max_input CHECK (max_input_tokens > 0),
    CONSTRAINT ck_model_definition_max_output CHECK (max_output_tokens > 0)
);

CREATE UNIQUE INDEX uk_model_definition_default
    ON model_definition (default_model)
    WHERE default_model;

CREATE TABLE conversation (
    id                  UUID        NOT NULL,
    workspace_id        UUID        NOT NULL,
    title               TEXT,
    created_by          UUID        NOT NULL,
    model_definition_id UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at     TIMESTAMPTZ,
    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT fk_conversation_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_creator FOREIGN KEY (created_by)
        REFERENCES user_account (id),
    CONSTRAINT fk_conversation_model FOREIGN KEY (model_definition_id)
        REFERENCES model_definition (id) ON DELETE SET NULL
);

CREATE INDEX idx_conversation_workspace
    ON conversation (workspace_id, last_message_at DESC);

CREATE TABLE message (
    id              UUID        NOT NULL,
    conversation_id UUID        NOT NULL,
    workspace_id    UUID        NOT NULL,
    sender_user_id  UUID,
    role            TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    sequence_no     BIGINT      NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'COMPLETED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_message PRIMARY KEY (id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_user_id)
        REFERENCES user_account (id),
    CONSTRAINT ck_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    CONSTRAINT ck_message_status CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT uk_message_sequence UNIQUE (conversation_id, sequence_no)
);

CREATE INDEX idx_message_conversation
    ON message (conversation_id, sequence_no);

CREATE TABLE agent_run (
    id                   UUID        NOT NULL,
    workspace_id         UUID        NOT NULL,
    conversation_id      UUID        NOT NULL,
    user_message_id      UUID        NOT NULL,
    assistant_message_id UUID,
    model_definition_id  UUID,
    status               TEXT        NOT NULL,
    error_message        TEXT,
    workflow_id          TEXT        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    CONSTRAINT pk_agent_run PRIMARY KEY (id),
    CONSTRAINT fk_agent_run_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_user_message FOREIGN KEY (user_message_id)
        REFERENCES message (id),
    CONSTRAINT fk_agent_run_assistant_message FOREIGN KEY (assistant_message_id)
        REFERENCES message (id),
    CONSTRAINT fk_agent_run_model FOREIGN KEY (model_definition_id)
        REFERENCES model_definition (id) ON DELETE SET NULL,
    CONSTRAINT ck_agent_run_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE UNIQUE INDEX uk_agent_run_workflow ON agent_run (workflow_id);
CREATE INDEX idx_agent_run_conversation ON agent_run (conversation_id, created_at DESC);
CREATE INDEX idx_agent_run_status ON agent_run (status);

CREATE TABLE model_invocation (
    id                  UUID        NOT NULL,
    agent_run_id        UUID        NOT NULL,
    model_definition_id UUID        NOT NULL,
    status              TEXT        NOT NULL,
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    latency_ms          BIGINT,
    finish_reason       TEXT,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_model_invocation PRIMARY KEY (id),
    CONSTRAINT fk_model_invocation_run FOREIGN KEY (agent_run_id)
        REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_model_invocation_model FOREIGN KEY (model_definition_id)
        REFERENCES model_definition (id),
    CONSTRAINT ck_model_invocation_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_model_invocation_run ON model_invocation (agent_run_id);

CREATE TABLE workflow_start_request (
    id           UUID        NOT NULL,
    agent_run_id UUID        NOT NULL,
    workflow_id  TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_workflow_start_request PRIMARY KEY (id),
    CONSTRAINT fk_workflow_start_request_run FOREIGN KEY (agent_run_id)
        REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_start_request_run UNIQUE (agent_run_id),
    CONSTRAINT ck_workflow_start_request_status
        CHECK (status IN ('PENDING', 'STARTED', 'FAILED'))
);

CREATE INDEX idx_workflow_start_request_status
    ON workflow_start_request (status, updated_at);
