-- Keep history capture on every SQL publisher and in the snapshot transaction.
-- V122 materializes both rule sets plus before/after normalized fields for all rules.
-- The inlined SQL rule extractor can also carry the entire section (including its
-- rules array) through the lateral rule expansion. Neither full-document expansion
-- nor per-rule field diffs should remain live across the entire history insert.
-- Extract section metadata once; the non-inlined SRF's tuplestore spills at work_mem.
CREATE OR REPLACE FUNCTION fn_policy_history_rules(payload JSONB) RETURNS TABLE(rule_key TEXT, rule JSONB)
LANGUAGE plpgsql IMMUTABLE
SET work_mem = '4MB'
AS $$
DECLARE section JSONB; section_source TEXT; section_name TEXT; context JSONB; item JSONB;
BEGIN
    FOR section IN SELECT value FROM jsonb_array_elements(payload->'sections') LOOP
        section_source := section->>'source';
        section_name := section->>'name';
        context := jsonb_build_object('container', section_source, 'section', section_name,
                                     'parentRuleId', section->'parentRuleId');
        FOR item IN SELECT value FROM jsonb_array_elements(section->'rules') LOOP
            rule_key := CASE WHEN coalesce(item->>'uuid', '') <> '' THEN 'uuid:' || (item->>'uuid')
                             ELSE 'name:' || jsonb_build_array(section_source, section_name, item->>'name')::text END;
            rule := item || context;
            RETURN NEXT;
        END LOOP;
    END LOOP;
END $$;

-- Group/join only raw rules in a spillable cursor; normalize and insert one revision
-- at a time, releasing per-rule expression memory between statements. Cache the two
-- object dictionaries once, preserving V122's avoidance of per-rule extraction.
-- work_mem is per executor operation, not a process/RSS cap. Full snapshot JSONB
-- values and string_agg's output still require memory proportional to payload size.
CREATE OR REPLACE FUNCTION fn_capture_policy_rule_history() RETURNS TRIGGER LANGUAGE plpgsql
SET work_mem = '4MB'
SET hash_mem_multiplier = 1
AS $$
DECLARE previous JSONB; before_objects JSONB; after_objects JSONB; item RECORD; before_fields JSONB; after_fields JSONB; diff JSONB;
BEGIN
    IF coalesce(jsonb_array_length(NEW.snapshot->'failures'), 0) <> 0 THEN RETURN NEW; END IF;
    SELECT snapshot INTO previous FROM policy_rule_history_baseline WHERE policy_id = NEW.policy_id FOR UPDATE;
    before_objects := previous->'objects';
    after_objects := NEW.snapshot->'objects';
    -- Snapshot row is already locked by the publisher's INSERT/UPDATE, including first publication.
    FOR item IN
        WITH before_rules AS (SELECT rule_key, min(rule::text)::jsonb AS rule, count(*) AS occurrences
                              FROM fn_policy_history_rules(previous) GROUP BY rule_key),
             after_rules AS (SELECT rule_key, min(rule::text)::jsonb AS rule, count(*) AS occurrences
                             FROM fn_policy_history_rules(NEW.snapshot) GROUP BY rule_key)
        SELECT coalesce(b.rule_key, a.rule_key) AS key, b.rule AS before_rule, a.rule AS after_rule
        FROM before_rules b FULL JOIN after_rules a USING (rule_key)
        -- Repeated inline occurrences/ambiguous names are not guessed into a unique identity.
        WHERE coalesce(b.occurrences, 1) = 1 AND coalesce(a.occurrences, 1) = 1
    LOOP
        before_fields := CASE WHEN item.before_rule IS NOT NULL THEN fn_policy_history_fields(item.before_rule, before_objects) END;
        after_fields := CASE WHEN item.after_rule IS NOT NULL THEN fn_policy_history_fields(item.after_rule, after_objects) END;
        IF before_fields IS NOT DISTINCT FROM after_fields THEN CONTINUE; END IF;
        SELECT coalesce(jsonb_agg(jsonb_build_object('field', field, 'before', before_fields->field,
                      'after', after_fields->field) ORDER BY field), '[]'::jsonb) INTO diff
        FROM (SELECT jsonb_object_keys(coalesce(before_fields, '{}'::jsonb) || coalesce(after_fields, '{}'::jsonb)) AS field) fields
        WHERE before_fields->field IS DISTINCT FROM after_fields->field;
        INSERT INTO policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type,
                    collected_at, changed_on, changed_by, changes)
        VALUES (NEW.policy_id, item.key, coalesce(item.after_rule->>'id', item.before_rule->>'id'),
                item.key LIKE 'name:%', CASE WHEN item.before_rule IS NULL THEN 'added'
                    WHEN item.after_rule IS NULL THEN 'removed' ELSE 'modified' END,
                NEW.collected_at,
                item.after_rule->'extras'->'last-modified'->>0,
                item.after_rule->'extras'->'last-modifier'->>0, diff);
    END LOOP;
    INSERT INTO policy_rule_history_baseline VALUES (NEW.policy_id, NEW.snapshot)
    ON CONFLICT (policy_id) DO UPDATE SET snapshot = excluded.snapshot;
    RETURN NEW;
END $$;
