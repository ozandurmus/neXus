-- Keep revision semantics, but extract large object dictionaries once and insert revisions as a set.
-- Re-evaluating NEW.snapshot->'objects' for every rule repeatedly walks/detoasts the whole package.
CREATE OR REPLACE FUNCTION fn_capture_policy_rule_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE previous JSONB; before_objects JSONB; after_objects JSONB;
BEGIN
    IF coalesce(jsonb_array_length(NEW.snapshot->'failures'), 0) <> 0 THEN RETURN NEW; END IF;
    SELECT snapshot INTO previous FROM policy_rule_history_baseline WHERE policy_id = NEW.policy_id FOR UPDATE;
    before_objects := previous->'objects';
    after_objects := NEW.snapshot->'objects';
    -- Snapshot row is already locked by the publisher, including first publication.
    WITH before_rules AS MATERIALIZED (
        SELECT rule_key, min(rule::text)::jsonb AS rule, count(*) AS occurrences
        FROM fn_policy_history_rules(previous) GROUP BY rule_key
    ), after_rules AS MATERIALIZED (
        SELECT rule_key, min(rule::text)::jsonb AS rule, count(*) AS occurrences
        FROM fn_policy_history_rules(NEW.snapshot) GROUP BY rule_key
    ), fields AS MATERIALIZED (
        SELECT coalesce(b.rule_key, a.rule_key) AS key, b.rule AS before_rule, a.rule AS after_rule,
               CASE WHEN b.rule IS NOT NULL THEN fn_policy_history_fields(b.rule, before_objects) END AS before_fields,
               CASE WHEN a.rule IS NOT NULL THEN fn_policy_history_fields(a.rule, after_objects) END AS after_fields
        FROM before_rules b FULL JOIN after_rules a USING (rule_key)
        -- Repeated inline occurrences/ambiguous names remain excluded.
        WHERE coalesce(b.occurrences, 1) = 1 AND coalesce(a.occurrences, 1) = 1
    )
    INSERT INTO policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type,
                collected_at, changed_on, changed_by, changes)
    SELECT NEW.policy_id, key, coalesce(after_rule->>'id', before_rule->>'id'),
           key LIKE 'name:%', CASE WHEN before_rule IS NULL THEN 'added'
               WHEN after_rule IS NULL THEN 'removed' ELSE 'modified' END,
           NEW.collected_at, after_rule->'extras'->'last-modified'->>0,
           after_rule->'extras'->'last-modifier'->>0,
           (SELECT coalesce(jsonb_agg(jsonb_build_object('field', field, 'before', before_fields->field,
                       'after', after_fields->field) ORDER BY field), '[]'::jsonb)
            FROM (SELECT jsonb_object_keys(coalesce(before_fields, '{}'::jsonb)
                         || coalesce(after_fields, '{}'::jsonb)) AS field) changed_fields
            WHERE before_fields->field IS DISTINCT FROM after_fields->field)
    FROM fields WHERE before_fields IS DISTINCT FROM after_fields;
    INSERT INTO policy_rule_history_baseline VALUES (NEW.policy_id, NEW.snapshot)
    ON CONFLICT (policy_id) DO UPDATE SET snapshot = excluded.snapshot;
    RETURN NEW;
END $$;
