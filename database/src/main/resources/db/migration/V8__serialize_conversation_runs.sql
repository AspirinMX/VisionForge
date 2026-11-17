-- A conversation advances one AgentRun at a time.
--
-- Message sequence numbers are allocated with max(sequence_no) + 1, so two concurrent runs in
-- the same conversation would either collide on uk_message_sequence or interleave assistant
-- replies. The partial unique index makes the invariant explicit at the storage layer; the
-- service additionally takes a row lock on conversation before allocating a sequence.

CREATE UNIQUE INDEX uk_agent_run_active_conversation
    ON agent_run (conversation_id)
    WHERE status IN ('PENDING', 'RUNNING');
