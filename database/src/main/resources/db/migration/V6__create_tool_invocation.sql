-- Durable tool invocation records used for idempotency and audit.

CREATE TABLE tool_invocation (
    id              UUID        NOT NULL,
    workspace_id    UUID        NOT NULL,
    agent_run_id    UUID        NOT NULL,
    conversation_id UUID,
    tool_call_id    TEXT        NOT NULL,
    tool_name       TEXT        NOT NULL,
    arguments       TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    result          TEXT,
    error_code      TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT pk_tool_invocation PRIMARY KEY (id),
    CONSTRAINT fk_tool_invocation_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_tool_invocation_run FOREIGN KEY (agent_run_id)
        REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_tool_invocation_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE SET NULL,
    CONSTRAINT ck_tool_invocation_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT uk_tool_invocation_call UNIQUE (agent_run_id, tool_call_id)
);

CREATE INDEX idx_tool_invocation_run
    ON tool_invocation (agent_run_id, created_at);
CREATE INDEX idx_tool_invocation_workspace
    ON tool_invocation (workspace_id, created_at DESC);
CREATE INDEX idx_tool_invocation_status
    ON tool_invocation (status, created_at);
