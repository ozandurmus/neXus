package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * Contract §7 test 6 / C1 §3.5 "Test-enforced" fourth case, as amended by
 * {@code UI2_0_B1_ADJUDICATION_2026_09_12.md} finding F9, proved against a
 * real PostgreSQL 16 server.
 *
 * <p><b>F9, deliberately not "all eight tables":</b> the assertion is "every
 * mutation-bearing table in {@code information_schema.tables} carries
 * {@code trg_audit_<table>}, EXCEPT the documented exclusion list" — never a
 * hardcoded count and never a hardcoded expected table list. A future
 * migration that adds a mutation-bearing table without wiring its trigger
 * must fail this test the moment it lands.</p>
 */
class AuditCoverageCompletenessTest {

    /**
     * C3 §3.5 deliberately excludes {@code authz_decisions} and
     * {@code actor_authz_state} from the audit trigger set (adjudication
     * F9); {@code audit_log} excludes itself (C1 §3.5).
     *
     * <p>{@code flyway_schema_history} is excluded for a different reason
     * entirely: it is not a product table at all but Flyway's own
     * bookkeeping, written by {@code ui2_migrate} before any audit context
     * can exist. Wiring {@code fn_audit_capture} to it would make every
     * migration fail closed with {@code audit_context_missing}.</p>
     *
     * <p>{@code audit_redaction_policy} is excluded for exactly that second
     * reason, declared by {@code UI2_0_B1_02A_AUDIT_REDACTION_CONTRACT.md}
     * §5.6: it is seeded by {@code V5} as {@code ui2_migrate}, and
     * {@code ui2_app} holds no write grant on it at all, so its
     * tamper-evidence is the grant and the §4 coverage test rather than a
     * trigger.</p>
     */
    static final Set<String> CONTRACT_EXCLUSIONS = Set.of(
            "audit_log", "authz_decisions", "actor_authz_state", "flyway_schema_history",
            "audit_redaction_policy");

    /**
     * Tables audited by a purpose-built trigger with its own narrow or
     * event-specific capture instead of {@code trg_audit_<table>}. Each named
     * trigger must exist on its table ({@link #everyNamedAuditTriggerExists()}).
     */
    static final Map<String, Set<String>> AUDITED_BY_NAMED_TRIGGER = Map.of(
            "directory_profiles", Set.of("tr_audit_directory_profiles"),               // V26
            "module_runtime_control", Set.of("module_runtime_control_audit"),          // V128
            "endpoint_admission", Set.of("endpoint_quarantine_release_audit"),         // V131
            "runtime_task_lease", Set.of("task_quarantine_release_audit"),             // V131
            "failover_dispatch_intent", Set.of("failover_dispatch_audit"),             // V138
            "notification_settings", Set.of("trg_audit_notification_settings_config"), // V100
            "notification_routes", Set.of("trg_audit_notification_routes_config",      // V100
                    "trg_audit_notification_routes_row"));

    /**
     * Tables that are not operator- or configuration-mutation-bearing, each
     * with the reason its owning migration (or design) records. Collected,
     * derived and cached rows are written by the product's own jobs, whose
     * {@code jobs} rows are the audited record.
     */
    static final Map<String, String> NOT_MUTATION_BEARING = Map.ofEntries(
            Map.entry("discovery_run", "V14: discovery result rows carry device identities; the jobs row is audited"),
            Map.entry("discovery_candidate", "V14: discovery result rows carry device identities; the jobs row is audited"),
            Map.entry("backup_deviation_record", "V30: derived comparison of two backup artefacts"),
            Map.entry("backup_artefact_content_listing", "V41: derived cache; backup_artefact is the audited record"),
            Map.entry("backup_artefact_entry", "V41: derived cache; backup_artefact is the audited record"),
            Map.entry("cp_device_archive", "V45: product bookkeeping of device-side files; the jobs row is audited"),
            Map.entry("device_platform_facts", "V46: collected facts, never operator-written; the jobs row is audited"),
            Map.entry("cluster_member_diff", "V52: server-side recomputed summary of collected texts"),
            Map.entry("pseudonym_registry", "V53: derived AIView pseudonym claims, keyed by HMAC"),
            Map.entry("compliance_evaluation", "V59: derived from the configuration run, recomputable"),
            Map.entry("device_policy_install", "V60: collected nightly read; the jobs row is audited"),
            Map.entry("grid_member", "V72: collected per inventory run; the jobs row is audited"),
            Map.entry("infoblox_grid_summary", "V92: collected per inventory run; the jobs row is audited"),
            Map.entry("policy_snapshot", "V115: latest parsed collected policy; the jobs row is audited"),
            Map.entry("policy_collection_request", "V116: opaque request references; jobs is the audited admission path"),
            Map.entry("policy_collection_domain", "V116: collection attempt timestamps"),
            Map.entry("policy_rule_history", "V120: parsed rule revisions recorded by snapshot publishers"),
            Map.entry("policy_rule_history_baseline", "V120: parsed rule revisions recorded by snapshot publishers"),
            Map.entry("cp_policy_object_inventory", "V124: collected policy object inventory"),
            Map.entry("cp_policy_domain_run", "V125: per-run collected domain snapshots"),
            Map.entry("cp_policy_json_chunk", "V126: storage chunks of collected policy JSON"),
            Map.entry("policy_unit_checkpoint", "V128: collection resume checkpoints"),
            Map.entry("runtime_instance_heartbeat", "V131: process liveness telemetry"),
            Map.entry("notification_state", "V100: delivery cursors written by the notification router"),
            Map.entry("root_identity", "V19: insert-once first-boot marker (ui2_app: SELECT/INSERT only); the "
                    + "root local identity's creation is audited on local_credentials"),
            Map.entry("failover_schedules", "V31/V135: every transition is appended, in the same transaction, to "
                    + "the hash-chained failover_schedule_ledger"),
            Map.entry("failover_schedule_ledger", "V31/V135: append-only hash-chained transition ledger, itself "
                    + "the audit record of failover_schedules"),
            Map.entry("failover_grant_consumption", "V31/V135: append-only single-use grant ledger"),
            Map.entry("failover_quarantine", "V136: failover_incident_guard keeps incidents immutable except the "
                    + "release, whose operator, approver, time and notes the row itself retains"));

    static final Set<String> AUDIT_TRIGGER_EXCLUSIONS = union(CONTRACT_EXCLUSIONS,
            AUDITED_BY_NAMED_TRIGGER.keySet(), NOT_MUTATION_BEARING.keySet());

    private static Set<String> union(Set<String> first, Set<String> second, Set<String> third) {
        Set<String> all = new TreeSet<>(first);
        for (Set<String> more : List.of(second, third)) {
            for (String table : more) {
                if (!all.add(table)) {
                    throw new IllegalStateException("excluded twice: " + table);
                }
            }
        }
        return Set.copyOf(all);
    }

    private static Ui2PostgresFixture fixture;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("audit_coverage");
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void everyMutationBearingTableMinusTheExclusionListHasAnAuditTrigger() throws SQLException {
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> baseTables = baseTables(migrate);
            assertFalse(baseTables.isEmpty(), "no base table was enumerated -- a rule proved over zero "
                    + "tables proves nothing");

            Set<String> mustHaveTrigger = new TreeSet<>(baseTables);
            mustHaveTrigger.removeAll(AUDIT_TRIGGER_EXCLUSIONS);
            assertFalse(mustHaveTrigger.isEmpty(), "the exclusion list removed every table");

            Set<String> tablesWithAuditTrigger = tablesWithAuditTrigger(migrate);

            Set<String> missing = new TreeSet<>(mustHaveTrigger);
            missing.removeAll(tablesWithAuditTrigger);
            assertTrue(missing.isEmpty(),
                    "mutation-bearing table(s) with no trg_audit_<table>: " + missing
                            + " -- either wire the trigger, or amend this test's documented exclusion list "
                            + "from the owning contract");
        }
    }

    @Test
    void noExcludedTableCarriesAnAuditTriggerAfterAll() throws SQLException {
        // The exclusion list is a statement about the schema, so it must also
        // be checked in the other direction: an excluded table that silently
        // gained a trigger would mean the exclusion list is stale (and, for
        // flyway_schema_history, that migrations would fail closed).
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> withTrigger = tablesWithAuditTrigger(migrate);
            Set<String> unexpected = new TreeSet<>(AUDIT_TRIGGER_EXCLUSIONS);
            unexpected.retainAll(withTrigger);
            assertEquals(Set.of(), unexpected,
                    "a deliberately excluded table carries an audit trigger; the exclusion list is stale");
        }
    }

    @Test
    void everyNamedAuditTriggerExists() throws SQLException {
        // A table excluded because a purpose-built trigger audits it must
        // still carry that trigger -- otherwise the exclusion hides a gap.
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> missing = new TreeSet<>();
            for (Map.Entry<String, Set<String>> entry : AUDITED_BY_NAMED_TRIGGER.entrySet()) {
                for (String trigger : entry.getValue()) {
                    try (PreparedStatement statement = migrate.prepareStatement(
                            "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                                    + "WHERE c.relname = ? AND t.tgname = ? AND NOT t.tgisinternal")) {
                        statement.setString(1, entry.getKey());
                        statement.setString(2, trigger);
                        try (ResultSet rows = statement.executeQuery()) {
                            rows.next();
                            if (rows.getLong(1) != 1) {
                                missing.add(entry.getKey() + "/" + trigger);
                            }
                        }
                    }
                }
            }
            assertEquals(Set.of(), missing, "an exclusion names an audit trigger that does not exist");
        }
    }

    @Test
    void everyExcludedTableStillExists() throws SQLException {
        // A stale exclusion (its table dropped or renamed) must be removed, not carried.
        try (Connection migrate = fixture.migrateConnection()) {
            Set<String> stale = new TreeSet<>(AUDIT_TRIGGER_EXCLUSIONS);
            stale.removeAll(baseTables(migrate));
            assertEquals(Set.of(), stale, "exclusion list names a table the schema no longer has");
        }
    }

    private static Set<String> baseTables(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                                + "ORDER BY table_name")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    /**
     * Every table carrying a trigger named exactly {@code trg_audit_<table>}.
     * The name is matched against the table it is attached to, so a trigger
     * wired to the wrong table cannot satisfy this test.
     */
    private static Set<String> tablesWithAuditTrigger(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT DISTINCT event_object_table FROM information_schema.triggers "
                                + "WHERE trigger_schema = 'public' "
                                + "AND trigger_name = 'trg_audit_' || event_object_table")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }
}
