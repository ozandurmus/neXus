-- V213 -- policy_change_evidence: close the NULL hole in V208's administrator-name scope check (PO decision D2,
-- docs/design/PO_DECISION_RECORD_2026_11_12_PAN_CHANGE_ACTOR_FROM_CONFIG_LOG.md).
--
-- V208 wrote the check as
--     admin_name IS NULL OR (evidence_kind = 'PAN_CONFIG_LOG' AND rule_ref_hmac IS NOT NULL
--         AND command_class IN (...) AND result_class = 'Succeeded')
-- When command_class or result_class is NULL, `IN (...)` / `=` yield NULL, the whole expression is NULL, and a CHECK
-- that evaluates to NULL passes: a row could carry an administrator name with no command class or no result, which D2
-- forbids. PolicyChangeActorLinkSchemaTest (integration-tests) proves it. The worker never writes such a row
-- (PanoramaConfigLogReader keeps a name only for a non-null change command with result Succeeded), so no existing row
-- is expected to fail the stricter check; the pre-deploy read-only count confirms that on the live database.
--
-- The constraint keeps its name, so PolicyCompletenessSchemaTest's constraint inventory is unchanged. evidence_kind is
-- NOT NULL (V163) and rule_ref_hmac is already tested with IS NOT NULL; only the two nullable comparisons are guarded.
-- Number: V213. V212 is taken by the ASA failover readiness branch already in the ship queue (2026-11-14).
-- Repeatable: DROP IF EXISTS then ADD, inside Flyway's transaction (V208's own guarded ADD is then a no-op on re-run).
ALTER TABLE policy_change_evidence DROP CONSTRAINT IF EXISTS policy_change_evidence_admin_name_scope_check;
ALTER TABLE policy_change_evidence ADD CONSTRAINT policy_change_evidence_admin_name_scope_check
    CHECK (admin_name IS NULL OR (evidence_kind = 'PAN_CONFIG_LOG' AND rule_ref_hmac IS NOT NULL
        AND command_class IS NOT NULL AND result_class IS NOT NULL
        AND command_class IN ('add', 'clone', 'delete', 'edit', 'move', 'rename', 'set') AND result_class = 'Succeeded'));
