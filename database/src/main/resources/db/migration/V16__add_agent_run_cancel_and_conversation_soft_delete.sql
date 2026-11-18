-- Support cancelling AgentRun executions and soft-deleting conversations while preserving audit and billing lineage.

ALTER TABLE conversation
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_conversation_active_creator
    ON conversation (created_by, last_message_at DESC, created_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE agent_run
    DROP CONSTRAINT ck_agent_run_status;

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));
