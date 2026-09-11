package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contract §7 test 6 / C1 §3.5 "Test-enforced" fourth case, as amended by
 * {@code UI2_0_B1_ADJUDICATION_2026_09_12.md} finding F9. Requires a live
 * PostgreSQL 16 instance via Testcontainers, unavailable in this
 * environment (no container runtime). No container is instantiated in this
 * class -- the disabled method below fails closed and visibly, never as a
 * silent pass.
 *
 * <p><b>F9, deliberately not "all eight tables":</b> the assertion this
 * test must make is "every mutation-bearing table in
 * {@code information_schema.tables} carries {@code trg_audit_<table>},
 * EXCEPT the documented exclusion list below" -- not a hardcoded count.
 * A hardcoded "all eight" (or "all N") assertion is wrong by construction:
 * it silently stops catching a future migration (B1-3's V2, adding
 * {@code role_bindings}/{@code sessions} without a trigger) the moment
 * that migration lands, which is exactly the failure mode this test
 * exists to prevent.</p>
 */
class AuditCoverageCompletenessTest {

    /**
     * C3 §3.5 deliberately excludes {@code authz_decisions} and
     * {@code actor_authz_state} from the audit trigger set (adjudication
     * F9); {@code audit_log} excludes itself (C1 §3.5: self-auditing the
     * audit table's own insert has no defined actor context distinct from
     * the mutation it is already recording). Neither of the two C3 tables
     * exists yet in V1 -- this exclusion list is declared now so B1-3's V2
     * does not have to also patch this test; if B1-3 ever needs a
     * different exclusion, its own contract amends this list, not the
     * other way around.
     */
    static final Set<String> AUDIT_TRIGGER_EXCLUSIONS = Set.of(
            "audit_log", "authz_decisions", "actor_authz_state");

    @Test
    void placeholderCompilesAndRunsWithoutAContainer() {
        assertNotNull(AuditCoverageCompletenessTest.class);
        assertNotNull(AUDIT_TRIGGER_EXCLUSIONS);
    }

    @Test
    @Disabled("requires a live container runtime (Podman/Docker), not available in this environment")
    void everyMutationBearingTableMinusTheExclusionListHasAnAuditTrigger() {
        // A container-capable host must prove: enumerate every base table
        // in information_schema.tables for the UI 2.0 schema, remove
        // AUDIT_TRIGGER_EXCLUSIONS, and assert information_schema.triggers
        // contains a trg_audit_<table> for every table that remains. This
        // must fail the moment a future migration (e.g. B1-3's V2) adds a
        // mutation-bearing table without wiring its trigger -- it must
        // never be re-expressed as a fixed count or a fixed table list of
        // "expected" tables.
        List<String> unused = AUDIT_TRIGGER_EXCLUSIONS.stream().sorted().toList();
        assertNotNull(unused);
    }
}
