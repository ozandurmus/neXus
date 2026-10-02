-- Parsed rule revisions only, atomically recorded by every existing snapshot publisher.
CREATE TABLE policy_rule_history (
    revision_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_id TEXT NOT NULL REFERENCES policy_snapshot(policy_id) ON DELETE CASCADE,
    rule_key TEXT NOT NULL,
    rule_id TEXT NOT NULL,
    identity_fallback BOOLEAN NOT NULL,
    change_type TEXT NOT NULL CHECK (change_type IN ('added', 'removed', 'modified')),
    collected_at TIMESTAMPTZ NOT NULL,
    changed_on TEXT,
    changed_by TEXT,
    changes JSONB NOT NULL
);
CREATE INDEX policy_rule_history_lookup ON policy_rule_history(policy_id, collected_at DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_rule_history TO ui2_app;

-- Partial checkpoints must not manufacture removals or replace the comparison baseline.
CREATE TABLE policy_rule_history_baseline (
    policy_id TEXT PRIMARY KEY REFERENCES policy_snapshot(policy_id) ON DELETE CASCADE,
    snapshot JSONB NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_rule_history_baseline TO ui2_app;
INSERT INTO policy_rule_history_baseline SELECT policy_id, snapshot FROM policy_snapshot
WHERE coalesce(jsonb_array_length(snapshot->'failures'), 0) = 0;

CREATE FUNCTION fn_policy_history_rules(payload JSONB) RETURNS TABLE(rule_key TEXT, rule JSONB)
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN coalesce(r->>'uuid', '') <> '' THEN 'uuid:' || (r->>'uuid')
                ELSE 'name:' || jsonb_build_array(s->>'source', s->>'name', r->>'name')::text END,
           r || jsonb_build_object('container', s->>'source', 'section', s->>'name', 'parentRuleId', s->'parentRuleId')
    FROM jsonb_array_elements(payload->'sections') s,
         jsonb_array_elements(s->'rules') r
$$;

CREATE FUNCTION fn_policy_history_fields(rule JSONB, objects JSONB) RETURNS JSONB
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result JSONB := rule - 'id' - 'uuid'; cell TEXT; refs JSONB; extras JSONB; extra TEXT;
BEGIN
    FOREACH cell IN ARRAY ARRAY['source', 'destination', 'service', 'application'] LOOP
        SELECT coalesce(jsonb_agg(jsonb_build_object('id', ref, 'name', coalesce(objects->ref->>'name', 'Unresolved object')) ORDER BY ref), '[]'::jsonb)
        INTO refs FROM jsonb_array_elements_text(rule->cell->'refs') ref;
        result := jsonb_set(result, ARRAY[cell, 'refs'], refs);
    END LOOP;
    extras := coalesce(result->'extras', '{}'::jsonb) - 'last-modified' - 'last-modifier';
    FOREACH extra IN ARRAY ARRAY['time', 'schedule', 'install-on', 'vpn', 'content', 'inline-layer'] LOOP
        IF extras ? extra THEN
            SELECT coalesce(jsonb_agg(jsonb_build_object('id', ref, 'name', coalesce(objects->ref->>'name', 'Unresolved object')) ORDER BY ref), '[]'::jsonb)
            INTO refs FROM jsonb_array_elements_text(extras->extra) ref;
            extras := jsonb_set(extras, ARRAY[extra], refs);
        END IF;
    END LOOP;
    RETURN jsonb_set(result, ARRAY['extras'], extras);
END $$;

CREATE FUNCTION fn_capture_policy_rule_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE previous JSONB; item RECORD; before_fields JSONB; after_fields JSONB; diff JSONB;
BEGIN
    IF coalesce(jsonb_array_length(NEW.snapshot->'failures'), 0) <> 0 THEN RETURN NEW; END IF;
    SELECT snapshot INTO previous FROM policy_rule_history_baseline WHERE policy_id = NEW.policy_id FOR UPDATE;
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
        before_fields := fn_policy_history_fields(item.before_rule, previous->'objects');
        after_fields := fn_policy_history_fields(item.after_rule, NEW.snapshot->'objects');
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
CREATE TRIGGER policy_rule_history_capture AFTER INSERT OR UPDATE ON policy_snapshot
FOR EACH ROW EXECUTE FUNCTION fn_capture_policy_rule_history();
