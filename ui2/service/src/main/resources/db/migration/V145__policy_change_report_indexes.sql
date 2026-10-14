-- Only timestamps with an explicit timezone are evidence of an instant.
CREATE OR REPLACE FUNCTION fn_policy_change_time(value TEXT) RETURNS TIMESTAMPTZ
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
BEGIN
    IF value !~ '^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$' THEN
        RETURN NULL;
    END IF;
    RETURN value::timestamptz;
EXCEPTION WHEN data_exception THEN
    RETURN NULL;
END $$;
CREATE INDEX IF NOT EXISTS policy_changes_time ON policy_rule_history
    ((coalesce(fn_policy_change_time(changed_on), collected_at)) DESC, revision_id DESC);
CREATE INDEX IF NOT EXISTS policy_changes_policy_time ON policy_rule_history
    (policy_id, (coalesce(fn_policy_change_time(changed_on), collected_at)) DESC, revision_id DESC);
CREATE INDEX IF NOT EXISTS policy_changes_rule_revision ON policy_rule_history
    (policy_id, rule_key, revision_id DESC);
CREATE INDEX IF NOT EXISTS policy_changes_fields ON policy_rule_history USING gin (changes jsonb_path_ops);
CREATE INDEX IF NOT EXISTS policy_changes_targets ON policy_snapshot USING gin ((metadata->'targets') jsonb_path_ops);
