-- Why an AI call failed or fell back, as the provider or the budget reported it (shown in the admin AI call log).
ALTER TABLE ai_call ADD COLUMN error text;

-- The AI call log lists newest first, often filtered to failures.
CREATE INDEX ix_ai_call_failures ON ai_call (created_at DESC) WHERE outcome IN ('ERROR', 'TIMEOUT', 'INVALID_JSON');
