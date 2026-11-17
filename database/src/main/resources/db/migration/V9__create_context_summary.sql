-- Local compaction: a structured summary replaces the covered message range in later inputs.
-- Original messages are never deleted; the summary is only an additional context artifact.

ALTER TABLE model_invocation
    ADD COLUMN purpose TEXT NOT NULL DEFAULT 'CHAT';

ALTER TABLE model_invocation
    ADD CONSTRAINT ck_model_invocation_purpose
        CHECK (purpose IN ('CHAT', 'COMPACT'));

CREATE TABLE context_summary (
    id                  UUID        NOT NULL,
    workspace_id        UUID        NOT NULL,
    conversation_id     UUID        NOT NULL,
    epoch               INTEGER     NOT NULL,
    from_seq            BIGINT      NOT NULL,
    to_seq              BIGINT      NOT NULL,
    content             TEXT        NOT NULL,
    source_digest       TEXT        NOT NULL,
    model_definition_id UUID,
    prompt_version      TEXT        NOT NULL,
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_context_summary PRIMARY KEY (id),
    CONSTRAINT fk_context_summary_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_context_summary_model FOREIGN KEY (model_definition_id)
        REFERENCES model_definition (id) ON DELETE SET NULL,
    CONSTRAINT uk_context_summary_epoch UNIQUE (conversation_id, epoch),
    CONSTRAINT ck_context_summary_range CHECK (from_seq > 0 AND to_seq >= from_seq),
    CONSTRAINT ck_context_summary_epoch CHECK (epoch > 0)
);
