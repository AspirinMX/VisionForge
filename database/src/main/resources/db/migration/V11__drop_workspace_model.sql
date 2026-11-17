-- Replace the workspace model with per-user isolation.
--
-- Pre-release data is not migrated: the old storage tables are dropped here and recreated as
-- conversation-level files in V12. Derived context summaries are cleared because they are keyed by
-- workspace and are cheap to regenerate.

DROP TABLE IF EXISTS conversation_item;
DROP TABLE IF EXISTS workspace_item_version;
DROP TABLE IF EXISTS workspace_item;
DROP TABLE IF EXISTS workspace_member;

ALTER TABLE audit_log DROP COLUMN IF EXISTS workspace_id;
ALTER TABLE tool_invocation DROP COLUMN IF EXISTS workspace_id;
ALTER TABLE message DROP COLUMN IF EXISTS workspace_id;
ALTER TABLE agent_run DROP COLUMN IF EXISTS workspace_id;
ALTER TABLE agent_run DROP COLUMN IF EXISTS platform_admin;
ALTER TABLE conversation DROP COLUMN IF EXISTS workspace_id;

DELETE FROM context_summary;
ALTER TABLE context_summary DROP COLUMN IF EXISTS workspace_id;
ALTER TABLE context_summary ADD COLUMN user_id UUID NOT NULL;
ALTER TABLE context_summary
    ADD CONSTRAINT fk_context_summary_user FOREIGN KEY (user_id)
        REFERENCES user_account (id);

DROP TABLE workspace;

CREATE INDEX idx_conversation_owner
    ON conversation (created_by, last_message_at DESC);
