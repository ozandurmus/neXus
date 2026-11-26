package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V267 zone-matrix runs and findings (docs/design/ZONE_MATRIX_CONTRACT.md sections 5.1, 5.9, 8.1 and 8.3, FROZEN
 * 2026-11-13; delivery step 4b-1) against a real PostgreSQL. Synthetic data only.
 */
class ZoneMatrixRunStorageTest {
    private static final String VERSION = "00000000-0000-4000-8000-0000000000a1";
    private static final String VERSION_TWO = "00000000-0000-4000-8000-0000000000a2";
    private static final String SHA = "a".repeat(64);
    private static final String SHA_TWO = "b".repeat(64);
    private static final Instant STARTED = Instant.parse("2026-11-23T09:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-23T09:00:05Z");
    private static Ui2PostgresFixture fixture;

    @BeforeAll static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("zone_matrix_runs");
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            s.execute("INSERT INTO zone_matrix_version (version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) VALUES ('" + VERSION + "', 1, 'ACTIVE', 1, "
                    + "'{}', '" + SHA + "', 'security-1', 'security-1', 'compliance-1', now())");
            s.execute("INSERT INTO zone_matrix_version (version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) VALUES ('" + VERSION_TWO + "', 2, 'RETIRED', 1, "
                    + "'{}', '" + SHA_TWO + "', 'security-1', 'security-1', 'compliance-1', now())");
        }
    }

    @AfterAll static void close() { if (fixture != null) fixture.close(); }

    private static NewRun run(String runId, String policy) {
        return new NewRun(runId, VERSION, 1, SHA, policy, "step3-v1", STARTED);
    }

    private static Finding finding(String policy, String ruleUid, String outcome) {
        return new Finding(policy, ruleUid, List.of("00Parent", "parent-B"), List.of("ZONE-A->ZONE-B"), "r-0007",
                7, "RULE-SYNTHETIC-7", "sec-1", "Pre rules", List.of("FW-TANGO-04"), outcome,
                "VIOLATION".equals(outcome) ? null : "unresolved object", "HIGH", List.of("TIME_IGNORED"), null,
                List.of(new Cell("zone-a", "zone-b", "ALLOW_LISTED", "HIGH", List.of("tcp/22"), "original",
                        new Witness("192.0.2.16/28", "198.51.100.77/32", "tcp/22", 3221225488L, 3325256781L))),
                List.of(), "original", FINISHED, null, FINISHED, null, null);
    }

    private static final Counts COUNTS = new Counts(1, 3, 1, 0, 2, 2);
    private static final Coverage COVERAGE = new Coverage("INCOMPLETE", List.of("PAN_LOCAL_RULES_NOT_COLLECTED"), 0, true,
            "PAN", Instant.parse("2026-11-23T08:00:00Z"), 7);

    private static long scalar(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static boolean bool(Statement s, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getBoolean(1);
        }
    }

    @Test void zm39_completeAndFindingsAreOneTransactionAndPartialFindingsNeverVisible() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-tx-" + UUID.randomUUID();
            String first = UUID.randomUUID().toString();
            repository.start(run(first, policy));
            assertEquals(List.of(), repository.findingsOf(first), "a RUNNING run shows no findings");
            List<Finding> findings = List.of(finding(policy, "uid-1", "VIOLATION"), finding(policy, "uid-2", "NOT_EVALUABLE"));
            assertTrue(repository.complete(first, FINISHED, 5_000L, COUNTS, COVERAGE, findings));
            Run complete = repository.latestComplete(policy).orElseThrow();
            assertEquals("COMPLETE", complete.state());
            assertEquals(COUNTS, complete.counts());
            assertEquals(List.of("PAN_LOCAL_RULES_NOT_COLLECTED"), complete.coverageItems());
            assertEquals(7, complete.snapshotRuleCount());
            assertEquals(findings, repository.findingsOf(first), "findings round-trip unchanged");

            // A write that fails on its second finding rolls back the first one too; the run stays RUNNING.
            String second = UUID.randomUUID().toString();
            repository.start(run(second, policy));
            List<Finding> broken = List.of(finding(policy, "uid-3", "VIOLATION"), finding(policy, "uid-4", "COMPLIANT"));
            assertThrows(RuntimeException.class,
                    () -> repository.complete(second, FINISHED, 1L, COUNTS, COVERAGE, broken));
            assertEquals(0, scalar(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + second + "'"));
            assertEquals(List.of(second), repository.runningRunOf(policy).stream().toList());
            assertEquals(first, repository.latestComplete(policy).orElseThrow().runId());
            assertTrue(repository.fail(second, FailureReason.STORE_ERROR, FINISHED));
            assertFalse(repository.complete(second, FINISHED, 1L, COUNTS, COVERAGE, findings),
                    "a FAILED run never gains findings");
            assertEquals(List.of(), repository.findingsOf(second));
            assertEquals(first, repository.latestComplete(policy).orElseThrow().runId());

            // The database itself refuses findings outside the completing transaction and any change to a finished run.
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO zone_matrix_finding (run_id, policy_source_id, "
                    + "rule_uid, inline_parent_path, violated_pairs, target_device_ids, outcome, flags, violated_cells, "
                    + "undecided_parts, first_observed_at, last_observed_at) VALUES ('" + first + "', '" + policy
                    + "', 'uid-9', '[]', '[]', '[]', 'VIOLATION', '[]', '[]', '[]', now(), now())"));
            assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE zone_matrix_run SET state = 'FAILED', failure_reason = 'ENGINE_ERROR' WHERE run_id = '" + first + "'"));
            assertThrows(SQLException.class, () -> s.execute(
                    "UPDATE zone_matrix_finding SET rule_name = 'changed' WHERE run_id = '" + first + "'"));
        }
    }

    @Test void zm39_oneRunningRunPerPolicyAndStartupRecovery() throws Exception {
        try (Connection db = fixture.appConnection()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-0042-Ab-" + UUID.randomUUID();
            String a = UUID.randomUUID().toString();
            repository.start(run(a, policy));
            RunningRunExists refused = assertThrows(RunningRunExists.class,
                    () -> repository.start(run(UUID.randomUUID().toString(), policy)));
            assertEquals(a, refused.existingRunId());
            // Differs by case only: a different opaque id (identity law), so it gets its own slot.
            String other = UUID.randomUUID().toString();
            repository.start(run(other, policy.toLowerCase(java.util.Locale.ROOT)));

            int interrupted = repository.failInterrupted(FINISHED);
            assertTrue(interrupted >= 2);
            for (String id : List.of(a, other)) {
                Run row = repository.runs(500).stream().filter(r -> r.runId().equals(id)).findFirst().orElseThrow();
                assertEquals("FAILED", row.state());
                assertEquals("INTERRUPTED_AT_STARTUP", row.failureReason());
            }
            assertTrue(repository.latestComplete(policy).isEmpty(), "an interrupted run is never read");
            assertTrue(repository.runningRunOf(policy).isEmpty(), "the slot is free again");
            String again = UUID.randomUUID().toString();
            repository.start(run(again, policy));
            assertEquals(List.of(again), repository.runningRunOf(policy).stream().toList());
        }
    }

    @Test void noDeleteGrantAndLifecycleColumnsOnlyAreUpdatable() throws Exception {
        try (Connection admin = fixture.adminDatabaseConnection(); Statement s = admin.createStatement()) {
            for (String role : List.of("ui2_app", "ui2_service")) {
                for (String table : List.of("zone_matrix_run", "zone_matrix_finding")) {
                    assertFalse(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'DELETE')"),
                            role + " " + table);
                    assertTrue(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'SELECT')"));
                    assertTrue(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'INSERT')"));
                }
                assertTrue(bool(s, "SELECT has_table_privilege('" + role + "', 'public.zone_matrix_run', 'UPDATE')"));
                assertFalse(bool(s, "SELECT has_table_privilege('" + role + "', 'public.zone_matrix_finding', 'UPDATE')"));
                for (String column : List.of("first_observed_any_version", "last_observed_at", "resolved_at",
                        "resolution_reason")) {
                    assertTrue(bool(s, "SELECT has_column_privilege('" + role + "', 'public.zone_matrix_finding', '"
                            + column + "', 'UPDATE')"), role + " " + column);
                }
                for (String column : List.of("run_id", "rule_uid", "outcome", "violated_cells", "first_observed_at")) {
                    assertFalse(bool(s, "SELECT has_column_privilege('" + role + "', 'public.zone_matrix_finding', '"
                            + column + "', 'UPDATE')"), role + " " + column);
                }
            }
            for (String role : Ui2PostgresFixture.MODULE_ROLES) {
                if (role.equals("ui2_service")) continue;
                for (String table : List.of("zone_matrix_run", "zone_matrix_finding")) {
                    assertFalse(bool(s, "SELECT has_table_privilege('" + role + "', 'public." + table + "', 'SELECT')"),
                            role + " " + table);
                }
            }
        }
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> s.execute("DELETE FROM zone_matrix_finding WHERE false"));
            assertEquals("42501", denied.getSQLState());
            denied = assertThrows(SQLException.class, () -> s.execute("DELETE FROM zone_matrix_run WHERE false"));
            assertEquals("42501", denied.getSQLState());
        }
    }

    @Test void identityTextIsStoredByteExact() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "00Pol-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
            String runId = UUID.randomUUID().toString();
            repository.start(run(runId, policy));
            String uid = "0001aBc-\"quoted\"\\path\u00e7";
            Finding exact = new Finding(policy, uid, List.of("007", "0007"), List.of("Zone-A->ZONE-a"), "0009",
                    9, "Rule \"nine\"", "00sec", "Pre rules", List.of("FW-TANGO-04", "fw-tango-04"), "UNKNOWN",
                    "analysis budget", null, List.of(), "000123", List.of(), List.of("IPv6 part"), null, FINISHED, null,
                    FINISHED, null, null);
            assertTrue(repository.complete(runId, FINISHED, 1L, new Counts(0, 0, 0, 1, 0, 1), COVERAGE, List.of(exact)));
            assertEquals(List.of(exact), repository.findingsOf(runId));
            assertEquals(1, scalar(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + runId
                    + "' AND rule_uid = '0001aBc-\"quoted\"\\path\u00e7' AND policy_source_id = '" + policy + "'"));
            assertEquals(0, scalar(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + runId
                    + "' AND (rule_uid = '1abc' OR policy_source_id = '" + policy.toLowerCase(java.util.Locale.ROOT) + "')"));
            assertEquals(policy, repository.latestComplete(policy).orElseThrow().policySourceId());
            assertTrue(repository.latestComplete(policy.toLowerCase(java.util.Locale.ROOT)).isEmpty());
        }
    }

    @Test void twoRunLifecycleUpdatesGrantedColumnsOnlyAndResolvesAtomically() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-lifecycle-" + UUID.randomUUID();
            Instant t1Start = Instant.parse("2026-11-23T10:00:00Z");
            Instant t1End = Instant.parse("2026-11-23T10:00:05Z");
            Instant snap1 = Instant.parse("2026-11-23T09:50:00Z");

            // Run 1 under VERSION (v1): uid-keep (VIOLATION) and uid-resolve (VIOLATION).
            String run1 = UUID.randomUUID().toString();
            repository.start(new NewRun(run1, VERSION, 1, SHA, policy, "step3-v1", t1Start, snap1));
            Finding keepV1 = new Finding(policy, "uid-keep", List.of("00Parent", "parent-B"), List.of("ZONE-A->ZONE-B"),
                    "r-1", 1, "RULE-KEEP", "sec-1", "Local rules", List.of("FW-TANGO-04"), "VIOLATION", null, "HIGH",
                    List.of(), null, List.of(), List.of(), "original", t1End, t1End, t1End, null, null);
            Finding resolveV1 = new Finding(policy, "uid-resolve", List.of(), List.of("ZONE-C->ZONE-D"),
                    "r-2", 2, "RULE-RESOLVE", "sec-1", "Local rules", List.of("FW-TANGO-04"), "VIOLATION", null,
                    "CRITICAL", List.of(), null, List.of(), List.of(), "original", t1End, t1End, t1End, null, null);
            Coverage completeCov1 = new Coverage("COMPLETE", List.of(), 0, true, "PAN", snap1, 2);
            assertTrue(repository.complete(run1, t1End, 5_000L, completeCov1,
                    history -> new CompletionPlan(new Counts(2, 0, 0, 0, 0, 2), List.of(keepV1, resolveV1), List.of())));

            // Mid-transaction failure in a subsequent run rolls back resolved_at updates on prior rows too!
            String failedRun = UUID.randomUUID().toString();
            Instant tFail = Instant.parse("2026-11-23T10:05:00Z");
            repository.start(new NewRun(failedRun, VERSION_TWO, 2, SHA_TWO, policy, "step3-v1", tFail, snap1));
            Finding invalidOutcome = new Finding(policy, "uid-bad", List.of(), List.of(), "r-9", 9, "BAD", "sec-1",
                    "Local rules", List.of(), "COMPLIANT", null, null, List.of(), null, List.of(), List.of(), null,
                    tFail, null, tFail, null, null);
            assertThrows(RuntimeException.class, () -> repository.complete(failedRun, tFail, 100L, completeCov1,
                    history -> new CompletionPlan(new Counts(0, 1, 0, 0, 0, 1), List.of(invalidOutcome),
                            List.of(new ResolutionUpdate("uid-resolve", List.of(), List.of("ZONE-C->ZONE-D"),
                                    tFail, "MATRIX_VERSION_CHANGED")))));
            assertTrue(repository.fail(failedRun, FailureReason.STORE_ERROR, tFail, snap1));
            assertEquals(snap1, repository.latestFinishedForVersion(policy, VERSION_TWO).orElseThrow().snapshotCollectedAt());
            assertTrue(repository.findingsOf(run1).stream().allMatch(f -> f.resolvedAt() == null),
                    "rolled-back complete() leaves prior open rows untouched");

            // Run 2 under VERSION_TWO (v2) as ui2_app: resolves uid-resolve with MATRIX_VERSION_CHANGED (updating only
            // V267 granted lifecycle columns) and reproduces uid-keep in one transaction.
            Instant t2Start = Instant.parse("2026-11-23T11:00:00Z");
            Instant t2End = Instant.parse("2026-11-23T11:00:04Z");
            Instant snap2 = Instant.parse("2026-11-23T10:55:00Z");
            String run2 = UUID.randomUUID().toString();
            repository.start(new NewRun(run2, VERSION_TWO, 2, SHA_TWO, policy, "step3-v1", t2Start, snap2));
            Coverage completeCov2 = new Coverage("COMPLETE", List.of(), 0, true, "PAN", snap2, 2);

            assertTrue(repository.complete(run2, t2End, 4_000L, completeCov2, history -> {
                assertEquals(run1, history.latestCompleteRunId());
                assertEquals(2, history.findings().size());
                assertTrue(history.findings().get(0).runCompleteScope());
                Finding reproducedKeep = new Finding(policy, "uid-keep", List.of("00Parent", "parent-B"),
                        List.of("ZONE-A->ZONE-B"), "r-1", 1, "RULE-KEEP", "sec-1", "Local rules",
                        List.of("FW-TANGO-04"), "VIOLATION", null, "HIGH", List.of("COVERED_BY_EARLIER_DENY"),
                        "r-deny", List.of(), List.of(), "original", t1End, t1End, t2End, null, null);
                return new CompletionPlan(
                        new Counts(1, 1, 0, 0, 0, 1),
                        List.of(reproducedKeep),
                        List.of(new ResolutionUpdate("uid-resolve", List.of(), List.of("ZONE-C->ZONE-D"),
                                t2End, "MATRIX_VERSION_CHANGED")));
            }));

            assertEquals(run2, repository.latestComplete(policy).orElseThrow().runId());
            assertEquals(run2, repository.latestRun(policy).orElseThrow().runId());
            assertEquals(snap2, repository.latestFinishedForVersion(policy, VERSION_TWO).orElseThrow().snapshotCollectedAt());

            List<Finding> run1After = repository.findingsOf(run1);
            Finding keepAfter = run1After.stream().filter(f -> "uid-keep".equals(f.ruleUid())).findFirst().orElseThrow();
            Finding resolvedAfter = run1After.stream().filter(f -> "uid-resolve".equals(f.ruleUid())).findFirst().orElseThrow();
            assertNull(keepAfter.resolvedAt());
            assertEquals(t2End, resolvedAfter.resolvedAt());
            assertEquals("MATRIX_VERSION_CHANGED", resolvedAfter.resolutionReason());

            List<Finding> run2Findings = repository.findingsOf(run2);
            assertEquals(1, run2Findings.size());
            assertEquals(t1End, run2Findings.get(0).firstObservedAt());
            assertEquals(t1End, run2Findings.get(0).firstObservedAnyVersion());
            assertEquals(t2End, run2Findings.get(0).lastObservedAt());

            List<ResolvedByVersionFinding> resolvedByVer = repository.resolvedByVersionChange(t2End.minusSeconds(60))
                    .stream().filter(r -> policy.equals(r.finding().policySourceId())).toList();
            assertEquals(1, resolvedByVer.size());
            assertEquals(VERSION, resolvedByVer.get(0).resolvedFromVersionId());
            assertEquals(1, resolvedByVer.get(0).resolvedFromVersionNumber());
            assertEquals(VERSION_TWO, resolvedByVer.get(0).resolvedInVersionId());
            assertEquals(2, resolvedByVer.get(0).resolvedInVersionNumber());
            assertEquals(t2End, resolvedByVer.get(0).resolvedAt());
        }
    }

    /**
     * ZM-32 (section 7.3): the findings resolved within a half-open period for every resolution reason, newest first,
     * with the version each was found against; nothing before the period, nothing at its exclusive end, and nothing
     * from a run that did not complete (its resolutions roll back with it).
     */
    @Test void zm32_findingsResolvedForAnyReasonAreReadByHalfOpenPeriodFromCompleteRunsOnly() throws Exception {
        try (Connection db = fixture.appConnection()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-period-" + UUID.randomUUID();
            Instant t1 = Instant.parse("2026-11-10T10:00:00Z");
            Coverage coverage = new Coverage("COMPLETE", List.of(), 0, true, "PAN", t1.minusSeconds(600), 5);
            List<String> uids = List.of("uid-before", "uid-changed", "uid-removed", "uid-version", "uid-after", "uid-failed");
            List<Finding> opened = uids.stream().map(uid -> new Finding(policy, uid, List.of(), List.of("ZONE-A->ZONE-B"),
                    "r-" + uid, 1, "RULE-" + uid, "sec-1", "Local rules", List.of("FW-TANGO-04"), "VIOLATION", null,
                    "HIGH", List.of(), null, List.of(), List.of(), "original", t1, t1, t1, null, null)).toList();
            String run1 = UUID.randomUUID().toString();
            repository.start(new NewRun(run1, VERSION, 1, SHA, policy, "step3-v1", t1.minusSeconds(5), t1.minusSeconds(600)));
            assertTrue(repository.complete(run1, t1, 1L, coverage,
                    history -> new CompletionPlan(new Counts(6, 0, 0, 0, 0, 6), opened, List.of())));

            Instant before = Instant.parse("2026-11-11T10:00:00Z");
            Instant changed = Instant.parse("2026-11-12T10:00:00Z");
            Instant removed = Instant.parse("2026-11-13T10:00:00Z");
            Instant version = Instant.parse("2026-11-14T10:00:00Z");
            Instant after = Instant.parse("2026-11-15T10:00:00Z");
            resolveOneInARun(repository, policy, VERSION_TWO, 2, SHA_TWO, coverage, "uid-before", "RULE_CHANGED", before);
            resolveOneInARun(repository, policy, VERSION_TWO, 2, SHA_TWO, coverage, "uid-changed", "RULE_CHANGED", changed);
            resolveOneInARun(repository, policy, VERSION_TWO, 2, SHA_TWO, coverage, "uid-removed",
                    "RULE_REMOVED_OR_DISABLED", removed);
            resolveOneInARun(repository, policy, VERSION_TWO, 2, SHA_TWO, coverage, "uid-version",
                    "MATRIX_VERSION_CHANGED", version);
            resolveOneInARun(repository, policy, VERSION_TWO, 2, SHA_TWO, coverage, "uid-after", "RULE_CHANGED", after);

            // A run that fails while completing rolls its resolution back: the finding stays open and is never listed.
            Instant failedAt = Instant.parse("2026-11-13T12:00:00Z");
            String failedRun = UUID.randomUUID().toString();
            repository.start(new NewRun(failedRun, VERSION_TWO, 2, SHA_TWO, policy, "step3-v1", failedAt.minusSeconds(5),
                    t1.minusSeconds(600)));
            Finding invalid = new Finding(policy, "uid-invalid", List.of(), List.of(), "r-9", 9, "BAD", "sec-1",
                    "Local rules", List.of(), "COMPLIANT", null, null, List.of(), null, List.of(), List.of(), null,
                    failedAt, null, failedAt, null, null);
            assertThrows(RuntimeException.class, () -> repository.complete(failedRun, failedAt, 1L, coverage,
                    history -> new CompletionPlan(new Counts(0, 1, 0, 0, 0, 1), List.of(invalid),
                            List.of(new ResolutionUpdate("uid-failed", List.of(), List.of("ZONE-A->ZONE-B"), failedAt,
                                    "RULE_CHANGED")))));
            assertTrue(repository.fail(failedRun, FailureReason.STORE_ERROR, failedAt));

            // [changed, version): the inclusive start, an interior instant, and the exclusive end left out.
            List<ResolvedFinding> period = ofPolicy(repository.resolvedInPeriod(changed, version), policy);
            assertEquals(List.of("uid-removed", "uid-changed"), period.stream().map(r -> r.finding().ruleUid()).toList(),
                    "newest resolution first; the finding resolved exactly at the end instant is out");
            assertEquals(List.of("RULE_REMOVED_OR_DISABLED", "RULE_CHANGED"),
                    period.stream().map(r -> r.finding().resolutionReason()).toList());
            assertEquals(List.of(removed, changed), period.stream().map(ResolvedFinding::resolvedAt).toList());
            assertEquals(List.of(removed, changed), period.stream().map(r -> r.finding().resolvedAt()).toList());
            assertTrue(period.stream().allMatch(r -> VERSION.equals(r.foundVersionId()) && r.foundVersionNumber() == 1),
                    "each row cites the version of the run it was found against");
            assertEquals(t1, period.get(0).finding().firstObservedAt());

            // Every reason, once each, in a period that holds the three.
            List<ResolvedFinding> all = ofPolicy(repository.resolvedInPeriod(changed, after), policy);
            assertEquals(List.of("uid-version", "uid-removed", "uid-changed"),
                    all.stream().map(r -> r.finding().ruleUid()).toList());
            assertEquals(List.of("MATRIX_VERSION_CHANGED", "RULE_REMOVED_OR_DISABLED", "RULE_CHANGED"),
                    all.stream().map(r -> r.finding().resolutionReason()).toList());

            // Unbounded sides, and a period holding nothing.
            assertEquals(5, ofPolicy(repository.resolvedInPeriod(null, null), policy).size());
            assertEquals(2, ofPolicy(repository.resolvedInPeriod(null, changed), policy).size());
            assertEquals(1, ofPolicy(repository.resolvedInPeriod(after, null), policy).size());
            assertTrue(ofPolicy(repository.resolvedInPeriod(after.plusSeconds(1), null), policy).isEmpty());
            assertTrue(ofPolicy(repository.resolvedInPeriod(before.plusSeconds(1), changed), policy).isEmpty());
            assertTrue(ofPolicy(repository.resolvedInPeriod(null, null), policy).stream()
                    .noneMatch(r -> "uid-failed".equals(r.finding().ruleUid())));
        }
    }

    private static List<ResolvedFinding> ofPolicy(List<ResolvedFinding> list, String policy) {
        return list.stream().filter(r -> policy.equals(r.finding().policySourceId())).toList();
    }

    /** One COMPLETE run on {@code versionId} finishing at {@code at}, resolving one open finding with {@code reason}. */
    private static void resolveOneInARun(ZoneMatrixRunRepository repository, String policy, String versionId,
            int versionNumber, String sha, Coverage coverage, String uid, String reason, Instant at) {
        String runId = UUID.randomUUID().toString();
        repository.start(new NewRun(runId, versionId, versionNumber, sha, policy, "step3-v1", at.minusSeconds(5),
                at.minusSeconds(600)));
        assertTrue(repository.complete(runId, at, 1L, coverage, history -> new CompletionPlan(
                new Counts(0, 0, 0, 0, 0, 0), List.of(),
                List.of(new ResolutionUpdate(uid, List.of(), List.of("ZONE-A->ZONE-B"), at, reason)))));
    }

    /**
     * Step 4b-2 (sections 7.1, 8.1): the audited witness read returns exactly the witness tuples and NAT view stored with the
     * finding, by the opaque id the summary carries; the id of a finding of a run that is not COMPLETE, or of no finding, reads
     * nothing. The read writes nothing (the audit row is the service's, written first).
     */
    @Test void theStoredWitnessOfOneFindingReadsBackByItsOpaqueId() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-witness-" + UUID.randomUUID();
            String runId = UUID.randomUUID().toString();
            repository.start(run(runId, policy));
            Finding withWitness = finding(policy, "uid-w1", "VIOLATION");
            Finding withoutWitness = new Finding(policy, "uid-w2", List.of(), List.of("zone-a->zone-b"), "r-0008", 8,
                    "RULE-SYNTHETIC-8", "sec-1", "Pre rules", List.of(), "VIOLATION", null, "HIGH", List.of(), null,
                    List.of(new Cell("zone-a", "zone-b", "DENY_ALL", "HIGH", List.of("any"), null, null)),
                    List.of(), null, FINISHED, null, FINISHED, null, null);
            assertTrue(repository.complete(runId, FINISHED, 5L, COUNTS, COVERAGE, List.of(withWitness, withoutWitness)));

            List<IdentifiedFinding> identified = repository.identifiedFindingsOf(runId);
            assertEquals(List.of(withWitness, withoutWitness), identified.stream().map(IdentifiedFinding::finding).toList(),
                    "identified findings are findingsOf with their ids, in the same order");
            long id = identified.get(0).findingId();
            assertEquals(id, scalar(s, "SELECT finding_id FROM zone_matrix_finding WHERE run_id = '" + runId
                    + "' AND rule_uid = 'uid-w1'"));
            long auditBefore = scalar(s, "SELECT count(*) FROM audit_log");

            StoredFindingWitness stored = repository.findingWitness(id).orElseThrow();
            assertEquals(id, stored.findingId());
            assertEquals(runId, stored.runId());
            assertEquals(VERSION, stored.versionId());
            assertEquals(1, stored.versionNumber());
            assertEquals("original", stored.natView());
            assertEquals(withWitness.violatedCells(), stored.violatedCells(), "the stored tuples read back unchanged");
            assertEquals("192.0.2.16/28", stored.violatedCells().get(0).witness().sourceRange());

            StoredFindingWitness none = repository.findingWitness(identified.get(1).findingId()).orElseThrow();
            assertNull(none.violatedCells().get(0).witness(), "a finding that stored no witness reads back none");
            assertNull(none.natView());

            assertTrue(repository.findingWitness(Long.MAX_VALUE).isEmpty(), "no finding has this id");
            String running = UUID.randomUUID().toString();
            repository.start(run(running, "pol-witness-running-" + UUID.randomUUID()));
            assertEquals(List.of(), repository.identifiedFindingsOf(running), "a RUNNING run shows no findings");
            assertEquals(auditBefore, scalar(s, "SELECT count(*) FROM audit_log"), "the repository read writes no row");
        }
    }

    @Test void migrationIsDryRunnableInsideATransaction() throws Exception {
        String sql;
        try (var resource = getClass().getResourceAsStream("/db/migration/V267__zone_matrix_runs_and_findings.sql")) {
            assertNotNull(resource);
            sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection db = fixture.migrateConnection(); Statement s = db.createStatement()) {
            db.setAutoCommit(false);
            s.execute("DROP TABLE zone_matrix_finding, zone_matrix_run");
            s.execute("DROP FUNCTION fn_zone_matrix_run_guard, fn_zone_matrix_finding_insert_guard");
            s.execute(sql);
            db.rollback();
            db.setAutoCommit(true);
            assertTrue(bool(s, "SELECT to_regclass('zone_matrix_finding') IS NOT NULL"));
        }
    }
}
