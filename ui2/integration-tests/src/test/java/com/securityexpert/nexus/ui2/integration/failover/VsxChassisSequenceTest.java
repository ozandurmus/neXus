package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.integration.support.JobWindowTestPolicy;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * "Fail over all virtual systems" of a VSLS chassis (PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md option A, V256):
 * one chassis approval, one derived approval and one ordinary run per VS, the next VS only after the previous run
 * SUCCEEDED, stopping at the first that did not or at the first refused start. Synthetic rows only; no executor.
 */
class VsxChassisSequenceTest {
    private static final String CLUSTER = "CLS-TEST-VSLS";
    private static final String ACTOR = Ui2Rows.ACTOR;

    private record Unit(JooqCpFailoverRepository repository, JooqTransactionBoundary boundary, String device, String peer) {}

    private static Unit unit(Ui2PostgresFixture fixture) throws Exception {
        String device, peer;
        try (var connection = fixture.appConnection()) {
            device = Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
            Ui2Rows.insertEndpoint(connection, device);
            peer = Ui2Rows.insertDevice(connection, Ui2Rows.insertCredentialReference(connection), "ENROLLED");
            Ui2Rows.insertEndpoint(connection, peer);
            new AuditedTransactionBoundary(new JooqTransactionBoundary(DSL.using(connection, SQLDialect.POSTGRES)))
                .inTransaction(ACTOR, "synthetic_enrollment", dsl -> dsl.execute(
                    "update devices set vendor_hint='check_point',role='gateway',cluster_member_ref={0}", CLUSTER));
        }
        var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        return new Unit(new JooqCpFailoverRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE), boundary, device, peer);
    }

    private static String unitRef(String vs) {
        return UUID.nameUUIDFromBytes((CLUSTER + ":" + vs).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static final List<JooqCpFailoverRepository.SequenceStep> STEPS = List.of(
        new JooqCpFailoverRepository.SequenceStep("12", unitRef("12")), new JooqCpFailoverRepository.SequenceStep("13", unitRef("13")));

    private static JooqCpFailoverRepository.RequestApproval chassisApproval(Unit u, String operation) {
        return u.repository().createRequestApproval(UUID.randomUUID().toString(), CLUSTER, null,
            UUID.nameUUIDFromBytes(CLUSTER.getBytes(StandardCharsets.UTF_8)).toString(), Set.of(u.device(), u.peer()),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic approval", ACTOR, "check_point",
            JooqCpFailoverRepository.ADMIN_SINGLE, operation);
    }
    private static JooqCpFailoverRepository.Decision start(Unit u, JooqCpFailoverRepository.RequestApproval a) {
        return u.repository().requestSequence(CLUSTER, ACTOR, u.device(), Set.of(u.device(), u.peer()), a.approval().id(),
            a.revision(), a.executionNonce(), true, a.policy(), STEPS, null, false);
    }
    private static String text(Unit u, String sql, Object... binds) {
        return u.boundary().inTransaction(dsl -> dsl.fetchOne(sql, binds).get(0, String.class));
    }
    private String advance(Unit u, String sequence) {
        return u.repository().advanceSequence(sequence, STEPS, u.device());
    }

    @Test
    void everyVirtualSystemRunsAsItsOwnRunOneAfterAnotherAndTheSequenceStopsAtTheFirstFailure() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("vsx_chassis_sequence")) {
            var u = unit(fixture);
            var approval = chassisApproval(u, JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS);
            // A sequence approval never authorizes one chassis run (option B).
            assertEquals("REQUEST_CHANGED", u.repository().requestBound(CLUSTER, null, Instant.now(), ACTOR, u.device(), true,
                "check_point", Set.of(u.device(), u.peer()), approval.approval().id(), approval.revision(),
                approval.executionNonce(), true, approval.policy()).code());

            var first = start(u, approval);
            assertEquals("ADMITTED", first.code());
            assertEquals(first.runId(), start(u, approval).runId(), "a replay returns the current run, no second sequence");
            String sequence = text(u, "select sequence_id from failover_run where run_id={0}", first.runId());
            assertEquals("12", text(u, "select vs_id from failover_run where run_id={0}", first.runId()));
            String child = text(u, "select approval_id from failover_run where run_id={0}", first.runId());
            assertEquals(approval.approval().id(), text(u, "select parent_approval_id from failover_approval where approval_id={0}", child));
            assertEquals("FAILOVER", text(u, "select operation from failover_approval where approval_id={0}", child));
            assertEquals(ACTOR, text(u, "select approved_by from failover_approval where approval_id={0}", child));
            assertNotNull(text(u, "select job_id from failover_run where run_id={0}", first.runId()), "the first VS run is admitted at once");
            // Check 16 rows of a chassis run are admitted by V256.
            u.repository().check(first.runId(), "post", u.device(), null, 16, "PASS", "{\"virtualSystems\":2}");

            assertEquals("IN_PROGRESS", advance(u, sequence));
            u.repository().state(first.runId(), "DONE", "DONE", "SUCCEEDED", null, "NO_PROBLEMS_FOUND");
            assertEquals("ADMITTED", advance(u, sequence));
            String second = u.repository().latestSequence(CLUSTER).orElseThrow().currentRunId();
            assertNotEquals(first.runId(), second);
            assertEquals("13", text(u, "select vs_id from failover_run where run_id={0}", second));
            assertEquals(2, u.repository().latestSequence(CLUSTER).orElseThrow().nextIndex());

            u.repository().state(second, "STOPPED", "STOPPED", "VSX_VIRTUAL_SYSTEM_NOT_MOVED", "1", "FAILOVER_TIMEOUT");
            assertEquals(JooqCpFailoverRepository.SEQUENCE_RUN_NOT_SUCCEEDED, advance(u, sequence));
            var ended = u.repository().latestSequence(CLUSTER).orElseThrow();
            assertEquals("STOPPED", ended.state());
            assertEquals("13", ended.failedVsId());
            assertEquals("NOT_RUNNING", advance(u, sequence));
            // A terminal sequence is immutable.
            assertThrows(RuntimeException.class, () -> u.boundary().inTransaction(dsl ->
                dsl.execute("update failover_sequence set state='RUNNING',finished_at=null where sequence_id={0}", sequence)));
        }
    }

    @Test
    void aRefusedNextStartStopsTheSequenceAndTheLastSuccessEndsIt() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("vsx_chassis_sequence_fence")) {
            var u = unit(fixture);
            var first = start(u, chassisApproval(u, JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS));
            assertEquals("ADMITTED", first.code());
            String sequence = text(u, "select sequence_id from failover_run where run_id={0}", first.runId());
            u.repository().state(first.runId(), "DONE", "DONE", "SUCCEEDED", null, "NO_PROBLEMS_FOUND");
            // The step fence: an open chassis readiness refuses the next VS start, and the sequence stops (never waits).
            var readiness = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            assertEquals("ADMITTED", readiness.code());
            assertEquals(JooqCpFailoverRepository.CHASSIS_RUN_ACTIVE, advance(u, sequence));
            assertEquals("13", u.repository().latestSequence(CLUSTER).orElseThrow().failedVsId());
            u.repository().state(readiness.runId(), "DONE", "DONE", "READY", null, "READY");

            // A new sequence on the same chassis; both runs succeed: DONE.
            var again = start(u, chassisApproval(u, JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS));
            assertEquals("ADMITTED", again.code());
            String next = text(u, "select sequence_id from failover_run where run_id={0}", again.runId());
            u.repository().state(again.runId(), "DONE", "DONE", "SUCCEEDED", null, "NO_PROBLEMS_FOUND");
            assertEquals("ADMITTED", advance(u, next));
            u.repository().state(u.repository().latestSequence(CLUSTER).orElseThrow().currentRunId(), "DONE", "DONE", "SUCCEEDED",
                null, "NO_PROBLEMS_FOUND");
            assertEquals(JooqCpFailoverRepository.SEQUENCE_ALL_DONE, advance(u, next));
            assertEquals("DONE", u.repository().latestSequence(CLUSTER).orElseThrow().state());
            // A plain FAILOVER approval cannot start a sequence.
            assertEquals("REQUEST_CHANGED", start(u, chassisApproval(u, JooqCpFailoverRepository.OPERATION_FAILOVER)).code());
        }
    }

    /** Memory "migration dry-run before deploy": V255 and V256 re-run twice inside one rolled-back transaction. */
    @Test
    void v255AndV256AreRepeatableInsideARolledBackTransaction() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("vsx_chassis_dry_run");
                var migrate = fixture.migrateConnection(); var statement = migrate.createStatement()) {
            migrate.setAutoCommit(false);
            for (String file : List.of("V255__vsx_chassis_failover_gates.sql", "V256__vsx_chassis_failover_sequence.sql")) {
                String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../service/src/main/resources/db/migration", file));
                statement.execute(sql);
                statement.execute(sql);
            }
            try (var rows = statement.executeQuery("select count(*) from gate_registry where gate_id in "
                    + "('cp_failover_down_vsenv0','cp_failover_up_vsenv0') and action_class='operational-state-change' "
                    + "and sign_off_state='SIGNED_OFF'")) {
                rows.next();
                assertEquals(2, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("select max_frequency from gate_registry where gate_id='cp_inventory_vsid_cphaprob_stat'")) {
                rows.next();
                String frequency = rows.getString(1);
                assertEquals(frequency.indexOf("VS-moved verification"), frequency.lastIndexOf("VS-moved verification"),
                    "the guarded amendment is appended once");
            }
            migrate.rollback();
        }
    }

    /**
     * V265 (PO 2026-11-23, VS0 independent of every VS): after the full migration the two VS0 writes carry the widened
     * text, every VS0 read of the run carries the widened purpose clause once and no HA-only clause, the commands are
     * unchanged; re-running V265 twice changes nothing.
     */
    @Test
    void v265WidensTheVs0RowsAndIsRepeatable() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("vsx_vs0_independent");
                var migrate = fixture.migrateConnection(); var statement = migrate.createStatement()) {
            migrate.setAutoCommit(false);
            String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../service/src/main/resources/db/migration",
                "V265__vsx_vs0_independent_failover_gates.sql"));
            String before = vs0Rows(statement);
            statement.execute(sql);
            statement.execute(sql);
            assertEquals(before, vs0Rows(statement), "V265 is a no-op when re-run");
            try (var rows = statement.executeQuery("select gate_id, max_frequency, canonical_command_key from gate_registry "
                    + "where gate_id in ('cp_failover_down_vsenv0','cp_failover_up_vsenv0') order by gate_id")) {
                rows.next();
                assertEquals(com.securityexpert.nexus.ui2.capability.GateRow.CP_CHASSIS_DOWN_MAX_FREQUENCY, rows.getString(2));
                assertEquals(com.securityexpert.nexus.ui2.capability.GateRow.CP_CHASSIS_DOWN_COMMAND, rows.getString(3));
                rows.next();
                assertEquals(com.securityexpert.nexus.ui2.capability.GateRow.CP_CHASSIS_UP_MAX_FREQUENCY, rows.getString(2));
                assertEquals(com.securityexpert.nexus.ui2.capability.GateRow.CP_CHASSIS_UP_COMMAND, rows.getString(3));
            }
            try (var rows = statement.executeQuery("select count(*) from gate_registry where gate_id like '%vsenv0' "
                    + "and max_frequency like '%VSX HA-mode chassis%'")) {
                rows.next();
                assertEquals(0, rows.getInt(1), "no HA-only clause is left on a VS0 row");
            }
            try (var rows = statement.executeQuery("select count(*) from gate_registry where max_frequency like '%"
                    + com.securityexpert.nexus.ui2.capability.GateRow.CP_VS0_RUN_READ_PURPOSE + "%'")) {
                rows.next();
                assertEquals(13, rows.getInt(1), "the eleven V177 reads and the two inventory VS0 reads");
            }
            migrate.rollback();
        }
    }

    private static String vs0Rows(java.sql.Statement statement) throws java.sql.SQLException {
        StringBuilder out = new StringBuilder();
        try (var rows = statement.executeQuery("select gate_id, canonical_command_key, max_frequency from gate_registry "
                + "where gate_id like '%vsenv0' order by gate_id")) {
            while (rows.next()) out.append(rows.getString(1)).append('|').append(rows.getString(2)).append('|')
                .append(rows.getString(3)).append('\n');
        }
        return out.toString();
    }
}
