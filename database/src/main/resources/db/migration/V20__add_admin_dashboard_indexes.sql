-- Read paths for the admin operations dashboard: global time-range scans across
-- invocations, settled credits, agent runs and user signups.
CREATE INDEX idx_model_invocation_created_at
    ON model_invocation (created_at);

CREATE INDEX idx_credit_ledger_entry_created
    ON credit_ledger (entry_type, created_at);

CREATE INDEX idx_agent_run_created_at
    ON agent_run (created_at);

CREATE INDEX idx_user_account_created_at
    ON user_account (created_at);

CREATE INDEX idx_conversation_creator_activity
    ON conversation (created_by, last_message_at);
