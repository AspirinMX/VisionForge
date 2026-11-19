-- The replay policy is no longer a user-facing three-state switch. What actually differs between
-- providers is how the chain of thought travels: OpenAI-style models return an encrypted carrier,
-- while models such as DeepSeek return plain reasoning_text that must be passed back verbatim.
--
-- Existing rows keep their effective behaviour: ENCRYPTED and NONE only ever replayed encrypted
-- carriers, so they map to encrypted = true; ALL also replayed plaintext, so it maps to false.

ALTER TABLE model_definition
    ADD COLUMN reasoning_encrypted BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE model_definition
   SET reasoning_encrypted = (reasoning_replay <> 'ALL');

ALTER TABLE model_definition
    DROP CONSTRAINT ck_model_definition_reasoning_replay;

ALTER TABLE model_definition
    DROP COLUMN reasoning_replay;
