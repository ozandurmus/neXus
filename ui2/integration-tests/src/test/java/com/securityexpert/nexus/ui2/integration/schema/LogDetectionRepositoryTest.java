package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.logdetect.JooqLogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.EventRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingQuery;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.IndexState;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;

/**
 * The log detection engine's tables (V272) on PostgreSQL: the P1 switch is off by default, evidence rows are idempotent
 * per chunk, findings round trip with their pointers, an acknowledgement is audited with its note redacted, and no new
 * table has a column that could hold a log line. Synthetic values only.
 */
class LogDetectionRepositoryTest {
    private static final String HEX = "a".repeat(64);
    private static final String DEV = "dev-synthetic";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    /** Every text column of the new tables is an id, a closed vocabulary word or a bounded token; a new one fails here. */
    private static final Set<String> TEXT_COLUMNS = Set.of(
            "log_detection_settings.settings_id", "log_detection_settings.updated_by",
            "log_index_state.chunk_id", "log_index_state.masking_rule_set", "log_index_state.marker_list_version",
            "log_index_state.device_id", "log_index_state.source_key", "log_index_state.job_id", "log_index_state.status",
            "log_event_count.event_id", "log_event_count.chunk_id", "log_event_count.device_id", "log_event_count.source_key",
            "log_event_count.marker_id", "log_event_count.key_token", "log_event_count.time_basis",
            "log_coverage.device_id", "log_coverage.source_key", "log_coverage.job_id", "log_coverage.state", "log_coverage.reason",
            "log_finding.finding_id", "log_finding.device_id", "log_finding.source_key", "log_finding.layer",
            "log_finding.rule_id", "log_finding.rule_version", "log_finding.kind", "log_finding.marker_id",
            "log_finding.key_token", "log_finding.time_basis", "log_finding.severity", "log_finding.severity_rule",
            "log_finding.provenance", "log_finding.incident_group", "log_finding.coverage_state",
            "log_finding.masking_rule_set", "log_finding.marker_list_version", "log_finding.config_version",
            "log_finding.review_state", "log_finding.review_by",
            "log_finding_action.action_id", "log_finding_action.finding_id", "log_finding_action.action",
            "log_finding_action.actor", "log_finding_action.note",
            // V273 (P2). Each is bounded by a CHECK in the migration: a closed set (phase, state, status, class, provenance,
            // segment, time_basis), an id pattern (catalog_version, template_id, marker_id, versions) or the closed-vocabulary
            // template character set with a length cap (template_text, match_form); none can hold a log line.
            "log_finding.phase", "log_finding.template_id", "log_finding.template_text", "log_finding.catalog_version",
            "log_finding.normaliser_version",
            "log_template_catalog.catalog_version", "log_template_catalog.state", "log_template_catalog.previous_version",
            "log_template_catalog.digest", "log_template_catalog.created_by", "log_template_catalog.frozen_by",
            "log_template.catalog_version", "log_template.template_id", "log_template.source_key", "log_template.template_text",
            "log_template.match_form", "log_template.marker_id", "log_template.template_class", "log_template.provenance",
            "log_template_device_window.catalog_version", "log_template_device_window.device_id",
            "log_template_device_window.source_key",
            "log_template_device_stat.catalog_version", "log_template_device_stat.device_id", "log_template_device_stat.source_key",
            "log_template_device_stat.template_id", "log_template_device_stat.segment",
            "log_template_index_state.chunk_id", "log_template_index_state.catalog_version",
            "log_template_index_state.masking_rule_set", "log_template_index_state.normaliser_version",
            "log_template_index_state.device_id", "log_template_index_state.source_key", "log_template_index_state.job_id",
            "log_template_index_state.status",
            "log_template_count.count_id", "log_template_count.chunk_id", "log_template_count.catalog_version",
            "log_template_count.device_id", "log_template_count.source_key", "log_template_count.template_id",
            "log_template_count.time_basis", "log_template_count.marker_id",
            "log_provisional_template.template_id", "log_provisional_template.source_key",
            "log_provisional_template.template_text");
    private static final Set<String> TABLES = Set.of("log_detection_settings", "log_index_state", "log_event_count", "log_coverage",
            "log_finding", "log_finding_action", "log_template_catalog", "log_template", "log_template_device_window",
            "log_template_device_stat", "log_template_index_state", "log_template_count", "log_provisional_template");

    @Test
    void noNewTableHasAColumnThatCouldHoldALogLine() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_schema")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection(); var st = db.createStatement();
                    ResultSet rs = st.executeQuery("select table_name, column_name, data_type from information_schema.columns "
                            + "where table_schema = 'public' and table_name like 'log\\_%' order by 1, 2")) {
                List<String> unexpected = new ArrayList<>();
                int seenTables = 0;
                String last = "";
                while (rs.next()) {
                    String table = rs.getString(1);
                    String column = rs.getString(2);
                    if (!TABLES.contains(table)) continue;
                    if (!table.equals(last)) {
                        seenTables++;
                        last = table;
                    }
                    String type = rs.getString(3);
                    if ((type.equals("text") || type.contains("char")) && !TEXT_COLUMNS.contains(table + "." + column)) {
                        unexpected.add(table + "." + column);
                    }
                    assertFalse(column.matches("line|text|raw|message|body|content|excerpt|snippet|payload|raw_line|log_line"),
                            "a column named like log text: " + table + "." + column);
                }
                assertEquals(TABLES.size(), seenTables);
                assertEquals(List.of(), unexpected, "a text column outside the reviewed closed-vocabulary list");
            }
        }
    }

    @Test
    void theMigrationRunsTwiceInsideOneRolledBackTransaction() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_dry_run")) {
            fixture.runFlyway();
            String sql = java.nio.file.Files.readString(Ui2PostgresFixture.migrationDirectory().resolve("V272__log_detection_p1.sql"));
            try (var db = fixture.migrateConnection()) {
                db.setAutoCommit(false);
                try (var st = db.createStatement()) {
                    st.execute(sql);
                    st.execute(sql);
                } finally {
                    db.rollback();
                    db.setAutoCommit(true);
                }
            }
        }
    }

    @Test
    void theSwitchIsOffByDefaultAndTheWriteIsAudited() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_settings")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertFalse(repository.settings().enabled());
            var written = repository.writeSettings("actor-synthetic", true, null);
            assertTrue(written.enabled());
            assertTrue(repository.settings().enabled());
            try (var db = fixture.migrateConnection(); var st = db.createStatement();
                    ResultSet rs = st.executeQuery("select count(*) from audit_log where table_name = 'log_detection_settings' "
                            + "and actor_fingerprint = 'actor-synthetic' and action_id = 'log_detection_settings_write'")) {
                rs.next();
                assertTrue(rs.getInt(1) >= 1);
            }
        }
    }

    @Test
    void evidenceRowsAreIdempotentPerChunkAndAggregateOverTheDay() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_events")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var state = new IndexState("c1", "logmask-v8", "markers-p1-1", DEV, "cp_messages", "job-1", DAY, "INDEXED", 100, 47, 0);
            String slot = "CPU_SrcID#1_MC#0_Chan#2_DIMM#0";
            var rows = List.of(
                    event("e1", "c1", "hw.edac.ce", slot, "2026-11-24T00:00:00", 10, "2026-11-24T00:30:00", "2026-11-24T00:54:00"),
                    event("e2", "c1", "hw.edac.ce", slot, "2026-11-24T01:00:00", 35, "2026-11-24T01:00:10", "2026-11-24T01:59:00"));
            repository.replaceChunkIndex(state, rows);
            repository.replaceChunkIndex(state, rows); // re-index: same rows, no duplicates
            var evidence = repository.dayEvidence(DEV, DAY);
            assertEquals(1, evidence.size());
            assertEquals(45, evidence.get(0).lineCount());
            assertEquals(35, evidence.get(0).peakBucketCount());
            assertEquals(slot, evidence.get(0).keyToken());
            assertEquals(LocalDateTime.parse("2026-11-24T00:30:00"), evidence.get(0).firstLineTime());
            assertEquals(LocalDateTime.parse("2026-11-24T01:59:00"), evidence.get(0).lastLineTime());
            assertEquals(1, evidence.get(0).pointers().size() > 0 ? 1 : 0);
            assertEquals(List.of(DAY), repository.daysOfJob(DEV, "job-1"));
            assertEquals(1, repository.historyDays(DEV, "cp_messages"));
            assertEquals(1, repository.prevalence("cp_messages", "hw.edac.ce", DAY));
            assertEquals(0, repository.prevalence("cp_messages", "hw.mce", DAY));
        }
    }

    @Test
    void baselineCountsUsableDaysAndSkipsDaysWithAGap() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_baseline")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            for (int i = 1; i <= 10; i++) {
                LocalDate d = DAY.minusDays(i);
                repository.replaceChunkIndex(new IndexState("b" + i, "logmask-v8", "markers-p1-1", DEV, "cp_messages", "job-b" + i, d,
                        "INDEXED", 10, 1, 0), i % 2 == 0 ? List.of() : List.of(
                        event("be" + i, "b" + i, "kern.oops", "", d + "T10:00:00", 2, d + "T10:00:00", d + "T10:00:30")));
            }
            var baseline = repository.baseline(DEV, "cp_messages", "kern.oops", DAY);
            assertEquals(10, baseline.usableDays());
            assertEquals(5, baseline.presenceDays());
            assertEquals(10, baseline.dayCounts().size());
            try (var db = fixture.migrateConnection()) {
                audited(db, "insert into system_log_gap(gap_id, device_id, source_key, job_id, kind, created_at) values "
                        + "('gap1', '" + DEV + "', 'cp_messages', 'job-b1', 'ROTATED_AWAY', timestamptz '" + DAY.minusDays(1)
                        + " 12:00:00+03')");
            }
            assertEquals(9, repository.baseline(DEV, "cp_messages", "kern.oops", DAY).usableDays(), "a day with a gap record is not usable");
        }
    }

    @Test
    void aFindingRoundTripsWithItsPointersAndAnAcknowledgementIsAuditedWithTheNoteRedacted() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_findings")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String id = "0123456789abcdef0123456789abcdef";
            repository.upsertFinding(finding(id, "hw.edac.ce", "ATTENTION"));
            var read = repository.finding(id).orElseThrow();
            assertEquals("ATTENTION", read.severity());
            assertEquals(45, read.eventCount());
            assertEquals(List.of("HISTORY_UNDER_7_DAYS"), read.labels());
            assertEquals(List.of(new Pointer("c1", 120, 160), new Pointer("c1", 400, 161)), read.pointers());
            assertEquals("OPEN", read.reviewState());

            var acked = repository.acknowledge("actor-synthetic", id, "known DIMM, case open").orElseThrow();
            assertEquals("ACKNOWLEDGED", acked.reviewState());
            assertTrue(repository.acknowledge("actor-synthetic", "ffffffffffffffffffffffffffffffff", null).isEmpty());
            assertEquals(1, repository.findings(new FindingQuery(DEV, null, "ACKNOWLEDGED", DAY.minusDays(1), 10)).size());

            try (var db = fixture.migrateConnection(); var st = db.createStatement();
                    ResultSet rs = st.executeQuery("select after_state::text from audit_log where table_name = 'log_finding_action' "
                            + "and operation = 'INSERT' and action_id = 'log_finding_acknowledge'")) {
                assertTrue(rs.next(), "the acknowledgement is audited");
                assertFalse(rs.getString(1).contains("known DIMM"), "operator free text never reaches the audit log in clear");
            }
        }
    }

    @Test
    void aReopenIsAnAuditedSystemActionAndTheFindingListPagesWithATotalOrder() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_reopen")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String id = "0123456789abcdef0123456789abcdef";
            repository.upsertFinding(finding(id, "hw.edac.ce", "INFO"));
            repository.acknowledge("actor-synthetic", id, "known");
            repository.reopenFinding("system:log-detection", finding(id, "hw.edac.ce", "ATTENTION"), "severity rose from INFO to ATTENTION");
            var read = repository.finding(id).orElseThrow();
            assertEquals("OPEN", read.reviewState());
            assertEquals("ATTENTION", read.severity());
            try (var db = fixture.migrateConnection(); var st = db.createStatement();
                    ResultSet rs = st.executeQuery("select count(*) from log_finding_action where finding_id = '" + id
                            + "' and action = 'REOPEN' and actor = 'system:log-detection'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "the reopen is recorded in log_finding_action");
            }
            try (var db = fixture.migrateConnection(); var st = db.createStatement();
                    ResultSet rs = st.executeQuery("select count(*) from audit_log where table_name = 'log_finding_action' "
                            + "and operation = 'INSERT' and action_id = 'log_finding_reopen'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "the reopen is audited");
            }

            for (int i = 1; i <= 5; i++) {
                // Distinct (device, marker, key, day) per finding: log_finding_unique allows one finding per key and day.
                repository.upsertFinding(finding(String.format("%032x", i), "kern.oops." + i, "ATTENTION"));
            }
            List<String> all = new ArrayList<>();
            for (int offset = 0; offset < 6; offset += 2) {
                for (var f : repository.findings(new FindingQuery(null, null, null, null, 2, offset))) all.add(f.findingId());
            }
            assertEquals(6, all.size());
            assertEquals(6, new java.util.HashSet<>(all).size(), "pages do not overlap or skip");
        }
    }

    @Test
    void anAcknowledgementBetweenTheEnginesReadAndItsUpsertIsNotOverwrittenAndOnlyAReopenClearsIt() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_ack_race")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            String id = "0123456789abcdef0123456789abcdef";
            repository.upsertFinding(finding(id, "hw.edac.ce", "ATTENTION"));
            FindingRow readByEngine = repository.finding(id).orElseThrow(); // the engine reads the finding: OPEN
            assertEquals("OPEN", readByEngine.reviewState());
            repository.acknowledge("actor-synthetic", id, "known DIMM"); // the operator acknowledges in between
            repository.upsertFinding(withEventCount(readByEngine, 60)); // the engine stores what it built from its read

            var after = repository.finding(id).orElseThrow();
            assertEquals(60, after.eventCount(), "the evidence part is updated");
            assertEquals("ACKNOWLEDGED", after.reviewState(), "the upsert never writes the review columns");
            assertEquals("actor-synthetic", after.reviewBy());
            assertTrue(after.reviewAt() != null);

            repository.reopenFinding("system:log-detection", finding(id, "hw.edac.ce", "CRITICAL"), "severity rose from ATTENTION to CRITICAL");
            var reopened = repository.finding(id).orElseThrow();
            assertEquals("OPEN", reopened.reviewState(), "a reopen is the one engine path that changes the review");
            assertEquals(null, reopened.reviewBy());
            assertEquals(null, reopened.reviewAt());
            assertEquals("CRITICAL", reopened.severity());
            try (var db = fixture.migrateConnection(); var st = db.createStatement();
                    ResultSet rs = st.executeQuery("select action from log_finding_action where finding_id = '" + id
                            + "' order by created_at, action")) {
                List<String> actions = new ArrayList<>();
                while (rs.next()) actions.add(rs.getString(1));
                assertEquals(List.of("ACKNOWLEDGE", "REOPEN"), actions, "the action log and the review state agree");
            }
        }
    }

    @Test
    void anEvaluationFailureMarksTheWindowsOfThatDeviceDayAndListsThemForTheNextPass() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_eval_failed")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            repository.replaceChunkIndex(new IndexState("f1", "logmask-v8", "markers-p1-1", DEV, "cp_messages", "job-f1", DAY,
                    "INDEXED", 5, 1, 0), List.of(event("fe1", "f1", "kern.oops", "", DAY + "T10:00:00", 1, DAY + "T10:00:00",
                            DAY + "T10:00:00")));
            repository.replaceChunkIndex(new IndexState("f2", "logmask-v8", "markers-p1-1", DEV, "cp_messages", "job-f2",
                    DAY.minusDays(1), "INDEXED", 5, 1, 0), List.of(event("fe2", "f2", "kern.oops", "", DAY.minusDays(1) + "T10:00:00",
                            1, DAY.minusDays(1) + "T10:00:00", DAY.minusDays(1) + "T10:00:00")));
            var coverage = new com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow(
                    DEV, "cp_messages", "job-f1", DAY, "EVALUATED", "LAYER_A_ONLY", 20);
            repository.upsertCoverage(coverage);
            repository.upsertCoverage(new com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow(
                    DEV, "cp_messages", "job-f2", DAY.minusDays(1), "EVALUATED", "LAYER_A_ONLY", 20));
            assertTrue(repository.failedEvaluations(10).isEmpty());

            assertEquals(1, repository.markEvaluationFailed(DEV, DAY), "only the window that feeds the failed day");
            var failed = repository.failedEvaluations(10);
            assertEquals(1, failed.size());
            assertEquals("job-f1", failed.get(0).jobId());
            assertEquals("EVALUATION_FAILED", failed.get(0).state());
            assertEquals("EVALUATION_FAILED", failed.get(0).reason());
            assertEquals(0, repository.cohortSize("cp_messages", DAY), "a failed window is not in the evaluable cohort");
            assertEquals(1, repository.markEvaluationFailed(null, DAY.minusDays(1)), "null device: every device of the day");

            repository.upsertCoverage(coverage); // the next pass writes the window again and evaluates the day
            assertEquals(List.of("job-f2"), repository.failedEvaluations(10).stream().map(c -> c.jobId()).toList());
        }
    }

    private static FindingRow withEventCount(FindingRow f, long count) {
        return new FindingRow(f.findingId(), f.deviceId(), f.sourceKey(), f.ruleId(), f.ruleVersion(), f.kind(), f.markerId(),
                f.keyToken(), f.windowDay(), f.windowStart(), f.windowEnd(), f.severity(), f.severityRule(), f.severityWeight(),
                f.provenance(), count, f.peakBucketCount(), f.historyDays(), f.baselineDays(), f.presenceDays(),
                f.cohortDevices(), f.prevalenceDevices(), f.chronic(), f.fleetBackground(), f.labels(), f.incidentGroup(),
                f.coverageState(), f.pointers(), f.maskingRuleSet(), f.markerListVersion(), f.configVersion(), f.reviewState(),
                f.reviewBy(), f.reviewAt(), f.firstRaisedAt(), f.updatedAt());
    }

    @Test
    void aKeyTokenOutsideTheStoredCharacterSetIsRefusedByTheDatabaseAndDevicesWithEvidenceAreListed() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_key")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var state = new IndexState("k1", "logmask-v8", "markers-p1-1", DEV, "cp_messages", "job-k1", DAY, "INDEXED", 5, 1, 0);
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> repository.replaceChunkIndex(state,
                    List.of(event("ke1", "k1", "kern.oom", "g++", DAY + "T10:00:00", 1, DAY + "T10:00:00", DAY + "T10:00:00"))));
            assertTrue(repository.devicesWithEvidence(DAY).isEmpty(), "the failed transaction left nothing behind");
            repository.replaceChunkIndex(state, List.of(event("ke2", "k1", "kern.oom", "k-0123456789ab", DAY + "T10:00:00", 1,
                    DAY + "T10:00:00", DAY + "T10:00:00")));
            assertEquals(List.of(DEV), repository.devicesWithEvidence(DAY));
        }
    }

    @Test
    void coverageUpsertsPerWindowAndTheSourceCohortIsCounted() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("log_detection_coverage")) {
            fixture.runFlyway();
            var repository = new JooqLogDetectionRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            repository.upsertCoverage(new com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow(
                    DEV, "cp_messages", "job-1", DAY, "EVALUATED", "LAYER_A_ONLY", 20));
            repository.upsertCoverage(new com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow(
                    DEV, "cp_messages", "job-1", DAY, "GAP", "GAP_ROTATED_AWAY", 20)); // recomputed: replaces, never duplicates
            repository.upsertCoverage(new com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow(
                    "dev-two", "cp_messages", "job-2", DAY, "EVALUATED", "LAYER_A_ONLY", 20));
            assertEquals(1, repository.cohortSize("cp_messages", DAY), "a GAP window is not in the evaluable cohort");
            var rows = repository.coverage(DEV, DAY);
            assertEquals(1, rows.size());
            assertEquals("GAP", rows.get(0).state());
            assertEquals(2, repository.devicesWithCoverage(DAY));
        }
    }

    private static EventRow event(String id, String chunk, String marker, String key, String bucket, int count, String first, String last) {
        return new EventRow(id, chunk, DEV, "cp_messages", marker, key, LocalDateTime.parse(bucket), 0.8, count,
                LocalDateTime.parse(first), LocalDateTime.parse(last), List.of(new Pointer(chunk, 120, 160)));
    }

    private static FindingRow finding(String id, String marker, String severity) {
        return new FindingRow(id, DEV, "cp_messages", "layer-a-severity", "p1-1", "MARKER", marker, "CPU_SrcID#1_MC#0_Chan#2_DIMM#0",
                DAY, DAY.atTime(0, 30), DAY.atTime(4, 54), severity, "WEIGHT_AT_LEAST_0_5", 0.8, "PROVISIONAL", 45, 10, 1, 0, 0, 0, 0,
                false, false, List.of("HISTORY_UNDER_7_DAYS"), "0123456789abcdef", "NOT_EVALUABLE",
                List.of(new Pointer("c1", 120, 160), new Pointer("c1", 400, 161)), "logmask-v8", "markers-p1-1", "p1-config-1",
                "OPEN", null, null, null, null);
    }

    private static void audited(Connection db, String sql) throws SQLException {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) { statement.execute(sql); }
            db.commit();
        } catch (SQLException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }
}
