-- Reasoning items returned by the model, kept per turn for stateless replay.
--
-- context_epoch ties a turn to the compaction epoch it was produced under. Reasoning items are
-- only valid against the input prefix that produced them, so a turn from an older epoch is never
-- replayed after compaction.

ALTER TABLE agent_run_turn
    ADD COLUMN reasoning TEXT,
    ADD COLUMN context_epoch INTEGER NOT NULL DEFAULT 0;

ALTER TABLE model_definition
    ADD COLUMN reasoning_replay TEXT NOT NULL DEFAULT 'ENCRYPTED';

ALTER TABLE model_definition
    ADD CONSTRAINT ck_model_definition_reasoning_replay
        CHECK (reasoning_replay IN ('ENCRYPTED', 'ALL', 'NONE'));
