-- Standardize reasoning effort tiers to NONE/LOW/MEDIUM/HIGH/XHIGH/MAX and persist per-model
-- supported reasoning efforts.

ALTER TABLE model_definition
    DROP CONSTRAINT IF EXISTS ck_model_definition_reasoning;

UPDATE model_definition
SET reasoning_effort = 'LOW'
WHERE reasoning_effort = 'MINIMAL';

ALTER TABLE model_definition
    ADD CONSTRAINT ck_model_definition_reasoning
        CHECK (reasoning_effort IS NULL
            OR reasoning_effort IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'));

ALTER TABLE model_definition
    ADD COLUMN supported_reasoning_efforts TEXT NOT NULL DEFAULT '[]';

UPDATE model_definition
SET supported_reasoning_efforts = '["' || reasoning_effort || '"]'
WHERE capability_type = 'CHAT'
  AND reasoning_effort IS NOT NULL;
