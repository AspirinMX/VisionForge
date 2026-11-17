-- Model turns of a single AgentRun, the upstream response identifier, and the acting user.
--
-- agent_run_turn is the durable model transcript for one run: every Responses API call
-- produces exactly one turn holding the assistant text and the function calls it requested.
-- Tool results stay in tool_invocation and are joined back by call_id when the next request
-- rebuilds the full explicit Responses input.

ALTER TABLE model_invocation
    ADD COLUMN response_id TEXT;

ALTER TABLE agent_run
    ADD COLUMN user_id UUID,
    ADD COLUMN platform_admin BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_run
    ADD CONSTRAINT fk_agent_run_user FOREIGN KEY (user_id)
        REFERENCES user_account (id);

CREATE TABLE agent_run_turn (
    id             UUID        NOT NULL,
    agent_run_id   UUID        NOT NULL,
    turn_no        INTEGER     NOT NULL,
    assistant_text TEXT,
    function_calls TEXT,
    response_id    TEXT,
    status         TEXT        NOT NULL DEFAULT 'COMPLETED',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_agent_run_turn PRIMARY KEY (id),
    CONSTRAINT fk_agent_run_turn_run FOREIGN KEY (agent_run_id)
        REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT uk_agent_run_turn_run_no UNIQUE (agent_run_id, turn_no),
    CONSTRAINT ck_agent_run_turn_no CHECK (turn_no >= 0),
    CONSTRAINT ck_agent_run_turn_status CHECK (status IN ('COMPLETED', 'INCOMPLETE'))
);
