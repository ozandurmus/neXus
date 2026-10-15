-- V149 -- policy change report noise (PO, 2026-10-15).
--
-- 1. Initial baseline capture. When a policy has no comparison baseline yet, every rule of its first
--    complete publication is recorded as 'added'. That is the policy's initial capture, not a change
--    anybody made. The trigger now marks those revisions explicitly; the change report excludes them by
--    default. No history row is deleted or rewritten other than this marker.
-- 2. Renumbering. A rule's position number shifts whenever any rule above it is inserted or removed, so
--    one insertion near the top of a large layer recorded a 'modified' revision for every rule below it.
--    A revision whose only difference is 'number' is no longer recorded when the rule kept its relative
--    order among the rules present in both publications of the same container/section/parent rule.
--    A real reorder still changes that relative order and is still recorded, and a rule changed in any
--    other field still records its number change alongside.
--
-- Idempotent and transactional (no CONCURRENTLY, no COMMIT): it can be dry-run in BEGIN/ROLLBACK.

ALTER TABLE policy_rule_history ADD COLUMN IF NOT EXISTS initial_capture BOOLEAN NOT NULL DEFAULT false;

-- Backfill, inferred from the rows themselves: a policy's earliest publication batch (one collected_at)
-- is its initial capture when every row in it is 'added' and it covers at least half of the rules in the
-- policy's current snapshot. Policies seeded with a baseline by V120 had real changes as their first
-- batch; the coverage guard keeps a small additions-only batch of such a policy visible as a change
-- (fail toward showing). Re-running marks the same rows.
UPDATE policy_rule_history h SET initial_capture = true
FROM (
    SELECT earliest.policy_id, earliest.collected_at
    FROM (SELECT policy_id, min(collected_at) AS collected_at FROM policy_rule_history GROUP BY policy_id) earliest
    JOIN policy_snapshot s ON s.policy_id = earliest.policy_id
    WHERE NOT EXISTS (SELECT 1 FROM policy_rule_history o
                      WHERE o.policy_id = earliest.policy_id AND o.collected_at = earliest.collected_at AND o.change_type <> 'added')
      AND 2 * (SELECT count(*) FROM policy_rule_history o
               WHERE o.policy_id = earliest.policy_id AND o.collected_at = earliest.collected_at)
          >= (SELECT coalesce(sum(jsonb_array_length(sec->'rules')), 0)
              FROM jsonb_array_elements(s.snapshot->'sections') sec)
) batch
WHERE h.policy_id = batch.policy_id AND h.collected_at = batch.collected_at AND NOT h.initial_capture;

-- The default report reads only real changes.
CREATE INDEX IF NOT EXISTS policy_changes_time_excluding_initial ON policy_rule_history
    ((coalesce(fn_policy_change_time(changed_on), collected_at)) DESC, revision_id DESC) WHERE NOT initial_capture;

-- V132's bounded capture, plus the initial-capture marker and the renumber-only filter. The relative
-- order is the rule's rank, in publication order, among the rules present exactly once in both
-- publications with the same container, section and parent rule. Window sorts spill at work_mem.
CREATE OR REPLACE FUNCTION fn_capture_policy_rule_history() RETURNS TRIGGER LANGUAGE plpgsql
SET work_mem = '4MB'
SET hash_mem_multiplier = 1
AS $$
DECLARE previous JSONB; first_capture BOOLEAN; before_objects JSONB; after_objects JSONB; item RECORD;
        before_fields JSONB; after_fields JSONB; diff JSONB;
BEGIN
    IF coalesce(jsonb_array_length(NEW.snapshot->'failures'), 0) <> 0 THEN RETURN NEW; END IF;
    SELECT snapshot INTO previous FROM policy_rule_history_baseline WHERE policy_id = NEW.policy_id FOR UPDATE;
    first_capture := NOT FOUND;
    before_objects := previous->'objects';
    after_objects := NEW.snapshot->'objects';
    -- Snapshot row is already locked by the publisher's INSERT/UPDATE, including first publication.
    FOR item IN
        WITH before_rules AS (SELECT rule_key, min(rule::text)::jsonb AS rule, count(*) AS occurrences, min(ordinal) AS ordinal
                              FROM fn_policy_history_rules(previous) WITH ORDINALITY AS r(rule_key, rule, ordinal) GROUP BY rule_key),
             after_rules AS (SELECT rule_key, min(rule::text)::jsonb AS rule, count(*) AS occurrences, min(ordinal) AS ordinal
                             FROM fn_policy_history_rules(NEW.snapshot) WITH ORDINALITY AS r(rule_key, rule, ordinal) GROUP BY rule_key),
             pairs AS (SELECT coalesce(b.rule_key, a.rule_key) AS key, b.rule AS before_rule, a.rule AS after_rule,
                              b.ordinal AS before_ordinal, a.ordinal AS after_ordinal,
                              (b.rule IS NOT NULL AND a.rule IS NOT NULL
                               AND b.rule->'container' IS NOT DISTINCT FROM a.rule->'container'
                               AND b.rule->'section' IS NOT DISTINCT FROM a.rule->'section'
                               AND b.rule->'parentRuleId' IS NOT DISTINCT FROM a.rule->'parentRuleId') AS same_group
                       FROM before_rules b FULL JOIN after_rules a USING (rule_key)
                       -- Repeated inline occurrences/ambiguous names are not guessed into a unique identity.
                       WHERE coalesce(b.occurrences, 1) = 1 AND coalesce(a.occurrences, 1) = 1)
        SELECT key, before_rule, after_rule,
               same_group AND
               row_number() OVER (PARTITION BY same_group, before_rule->'container', before_rule->'section',
                                  before_rule->'parentRuleId' ORDER BY before_ordinal)
               = row_number() OVER (PARTITION BY same_group, after_rule->'container', after_rule->'section',
                                    after_rule->'parentRuleId' ORDER BY after_ordinal) AS order_kept
        FROM pairs
    LOOP
        before_fields := CASE WHEN item.before_rule IS NOT NULL THEN fn_policy_history_fields(item.before_rule, before_objects) END;
        after_fields := CASE WHEN item.after_rule IS NOT NULL THEN fn_policy_history_fields(item.after_rule, after_objects) END;
        IF before_fields IS NOT DISTINCT FROM after_fields THEN CONTINUE; END IF;
        -- Renumbered only because rules elsewhere were inserted or removed: not a change of this rule.
        IF item.order_kept AND (before_fields - 'number') IS NOT DISTINCT FROM (after_fields - 'number') THEN CONTINUE; END IF;
        SELECT coalesce(jsonb_agg(jsonb_build_object('field', field, 'before', before_fields->field,
                      'after', after_fields->field) ORDER BY field), '[]'::jsonb) INTO diff
        FROM (SELECT jsonb_object_keys(coalesce(before_fields, '{}'::jsonb) || coalesce(after_fields, '{}'::jsonb)) AS field) fields
        WHERE before_fields->field IS DISTINCT FROM after_fields->field;
        INSERT INTO policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type,
                    collected_at, changed_on, changed_by, changes, initial_capture)
        VALUES (NEW.policy_id, item.key, coalesce(item.after_rule->>'id', item.before_rule->>'id'),
                item.key LIKE 'name:%', CASE WHEN item.before_rule IS NULL THEN 'added'
                    WHEN item.after_rule IS NULL THEN 'removed' ELSE 'modified' END,
                NEW.collected_at,
                item.after_rule->'extras'->'last-modified'->>0,
                item.after_rule->'extras'->'last-modifier'->>0, diff, first_capture);
    END LOOP;
    INSERT INTO policy_rule_history_baseline VALUES (NEW.policy_id, NEW.snapshot)
    ON CONFLICT (policy_id) DO UPDATE SET snapshot = excluded.snapshot;
    RETURN NEW;
END $$;
