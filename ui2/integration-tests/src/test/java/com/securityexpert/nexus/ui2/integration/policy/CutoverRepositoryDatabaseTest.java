package com.securityexpert.nexus.ui2.integration.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.CutoverRepository;

/**
 * V212 over real rows (ACI segmentation step 1): segments carry revisions, assessments and their flow rows are written
 * once in an audited transaction and never updated, raw-content keys are refused by the database, and a segment delete
 * removes its assessments. Synthetic documentation ranges only. Needs PostgreSQL (excluded from the default build).
 */
class CutoverRepositoryDatabaseTest {
    static final String SEGMENT = "00000000-0000-4000-8000-0000000000a1";
    static final String ACTOR = "synthetic-actor";

    @Test
    void segmentsAssessmentsAndFlowsRoundTripUnderTheAuditedAction() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cutover_assessment")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            }
            var repository = new CutoverRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));

            assertTrue(repository.createSegment(SEGMENT, "SEG-ALPHA", "[\"192.0.2.0/24\"]", "[\"198.51.100.0/24\"]", ACTOR));
            assertFalse(repository.createSegment("00000000-0000-4000-8000-0000000000a2", "SEG-ALPHA", "[\"203.0.113.0/24\"]", "[\"198.51.100.0/24\"]", ACTOR));
            assertEquals(CutoverRepository.UpdateOutcome.REVISION_CONFLICT,
                repository.updateSegment(SEGMENT, 7, "SEG-ALPHA", "[\"192.0.2.0/24\"]", "[\"198.51.100.0/24\"]", ACTOR));
            assertEquals(CutoverRepository.UpdateOutcome.UPDATED,
                repository.updateSegment(SEGMENT, 1, "SEG-BRAVO", "[\"192.0.2.0/25\"]", "[\"198.51.100.0/24\"]", ACTOR));
            assertTrue(repository.findSegment(SEGMENT).orElseThrow().contains("\"revision\": 2"));
            assertEquals(1, repository.listSegments().size());
            assertTrue(repository.deviceVendor(device).isPresent()); assertTrue(repository.deviceVendor("absent-device").isEmpty());

            String flow = "{\"kind\":\"FLOW\",\"ordinal\":0,\"decision\":\"WOULD_DROP\",\"flow\":{\"flowSource\":\"192.0.2.9\",\"decision\":\"WOULD_DROP\"}}";
            String allow = "{\"kind\":\"FLOW\",\"ordinal\":1,\"decision\":\"WOULD_ALLOW\",\"flow\":{\"flowSource\":\"192.0.2.8\",\"decision\":\"WOULD_ALLOW\"}}";
            String missing = "{\"kind\":\"MISSING_RULE\",\"ordinal\":0,\"decision\":\"MISSING_RULE\",\"flow\":{\"flowDestination\":\"198.51.100.8\"}}";
            String summary = "{\"counts\":{\"tuples\":2},\"coverage\":[{\"code\":\"LOG_PERIOD\"}],\"uploads\":[],\"headline\":\"h\"}";
            repository.saveAssessment(new CutoverRepository.Assessment("a1", SEGMENT, 2, ACTOR, "OPERATOR_OVERRIDE", device, null, "p1", "2026-11-13T00:00:00Z", null, null,
                "cutover-1", "COMPLETE", "BLOCKERS_FOUND", summary, List.of(flow, allow, missing)));
            assertTrue(repository.findAssessment("a1").orElseThrow().contains("\"readiness\": \"BLOCKERS_FOUND\""));
            assertEquals(2, repository.flowCount("a1", "FLOW", null)); assertEquals(1, repository.flowCount("a1", "FLOW", "WOULD_DROP"));
            assertEquals(1, repository.flows("a1", "MISSING_RULE", null, 0).size());
            assertTrue(repository.flows("a1", "FLOW", "WOULD_ALLOW", 0).get(0).contains("192.0.2.8"));
            assertTrue(repository.flows("a1", "FLOW", null, 1).isEmpty());
            assertEquals(1, repository.listAssessments(SEGMENT).size());
            assertTrue(repository.listAssessments(SEGMENT).get(0).contains("\"targetSource\": \"OPERATOR_OVERRIDE\""));

            // Raw-content keys are refused by the database itself (ACI-5), and nothing of the refused write remains.
            assertThrows(RuntimeException.class, () -> repository.saveAssessment(new CutoverRepository.Assessment("a2", SEGMENT, 2, ACTOR, "PATH", null,
                "topology-synthetic-01", null, null, null, null, "cutover-1", "COMPLETE", "NO_EVIDENCE",
                "{\"counts\":{},\"coverage\":[],\"uploads\":[],\"rows\":[\"192.0.2.8,198.51.100.8,443,tcp\"]}", List.of(flow))));
            assertTrue(repository.findAssessment("a2").isEmpty());
            // A routed-path assessment must cite its topology snapshot; an override must name its device.
            for (var bad : List.of(new String[] {"PATH", null, null}, new String[] {"OPERATOR_OVERRIDE", null, null}, new String[] {"PATH", device, "t1"}))
                assertThrows(RuntimeException.class, () -> repository.saveAssessment(new CutoverRepository.Assessment("a3", SEGMENT, 2, ACTOR, bad[0], bad[1],
                    bad[2], null, null, null, null, "cutover-1", "COMPLETE", "NO_EVIDENCE", summary, List.of())));
            repository.saveAssessment(new CutoverRepository.Assessment("a4", SEGMENT, 2, ACTOR, "PATH", null, "topology-synthetic-01", null, null,
                null, null, "cutover-1", "COMPLETE", "NO_EVIDENCE", summary, List.of()));
            assertTrue(repository.findAssessment("a4").orElseThrow().contains("\"targetSource\": \"PATH\""));
            try (Connection db = fixture.migrateConnection()) {
                assertEquals(1L, Ui2Rows.count(db, "SELECT count(*) FROM audit_log WHERE table_name = 'cutover_assessment' AND row_pk = 'a1'"));
                assertEquals(2L, Ui2Rows.count(db, "SELECT count(*) FROM audit_log WHERE table_name = 'cutover_segment'"));
                db.setAutoCommit(false);
                Ui2Rows.setAuditContext(db, ACTOR, CutoverRepository.ACTION);
                try (var statement = db.createStatement()) {
                    assertThrows(java.sql.SQLException.class, () -> statement.execute("UPDATE cutover_assessment SET readiness = 'NO_EVIDENCE' WHERE assessment_id = 'a1'"));
                }
                db.rollback();
                db.setAutoCommit(true);
            }

            assertTrue(repository.deleteSegment(SEGMENT, ACTOR));
            assertTrue(repository.findAssessment("a1").isEmpty()); assertEquals(0, repository.flowCount("a1", "FLOW", null));
        }
    }

    /**
     * Q7 retention (PO decision record 2026-11-14): flow rows of an assessment older than 90 days are removed one assessment
     * per transaction; the assessment row, verdict and summary stay; a younger assessment is never touched even when named;
     * the read side then says the rows expired; the flow guard still refuses UPDATE; and the expired read honours its bound.
     */
    @Test
    void flowsOlderThanNinetyDaysAreRemovedInBoundedPassesAndTheVerdictStays() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("cutover_flow_retention")) {
            fixture.runFlyway();
            var repository = new CutoverRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            assertTrue(repository.createSegment(SEGMENT, "SEG-ALPHA", "[\"192.0.2.0/24\"]", "[\"198.51.100.0/24\"]", ACTOR));
            try (Connection db = fixture.migrateConnection()) {
                db.setAutoCommit(false);
                Ui2Rows.setAuditContext(db, ACTOR, CutoverRepository.ACTION);
                insertAged(db, "old-91", 91, 3); insertAged(db, "old-92", 92, 2); insertAged(db, "old-93", 93, 4);
                insertAged(db, "young-89", 89, 3); insertAged(db, "never-had-flows", 120, 0);
                db.commit();
                db.setAutoCommit(true);
            }
            String janitor = "system:cutover-flow-retention";

            // The bound holds: oldest first, never more than asked; an assessment that never had flows is not listed.
            assertEquals(List.of("old-93", "old-92"), repository.expiredAssessmentsWithFlows(90, 2));
            assertEquals(List.of("old-93", "old-92", "old-91"), repository.expiredAssessmentsWithFlows(90, 50));
            assertFalse(repository.flowsExpired("old-91", 90), "rows still present: not expired yet");

            // 89 days: nothing is removed even when the id is named directly.
            assertEquals(0, repository.deleteExpiredFlows("young-89", 90, janitor));
            assertEquals(3, repository.flowCount("young-89", "FLOW", null)); assertEquals(1, repository.flowCount("young-89", "MISSING_RULE", null));
            assertFalse(repository.flowsExpired("young-89", 90));

            // 91 days: every flow and missing-rule row goes; the row, its verdict and its summary counts stay.
            assertEquals(4, repository.deleteExpiredFlows("old-91", 90, janitor));
            assertEquals(0, repository.flowCount("old-91", "FLOW", null)); assertEquals(0, repository.flowCount("old-91", "MISSING_RULE", null));
            String kept = repository.findAssessment("old-91").orElseThrow();
            assertTrue(kept.contains("\"readiness\": \"BLOCKERS_FOUND\""), kept); assertTrue(kept.contains("\"tuples\": 3"), kept);
            assertTrue(repository.flowsExpired("old-91", 90));
            assertEquals(0, repository.deleteExpiredFlows("old-91", 90, janitor), "a second pass removes nothing");
            assertEquals(List.of("old-93", "old-92"), repository.expiredAssessmentsWithFlows(90, 50));

            // Counts that positively say "no flows" are not read as expired; an unknown id is not expired either.
            assertFalse(repository.flowsExpired("never-had-flows", 90)); assertFalse(repository.flowsExpired("absent", 90));

            try (Connection db = fixture.migrateConnection()) {
                assertEquals(5L, Ui2Rows.count(db, "SELECT count(*) FROM cutover_assessment"));
                assertEquals(0L, Ui2Rows.count(db, "SELECT count(*) FROM audit_log WHERE table_name = 'cutover_assessment' AND operation = 'DELETE'"));
                // The guard still refuses UPDATE of a flow row, even inside the audited action.
                db.setAutoCommit(false);
                Ui2Rows.setAuditContext(db, ACTOR, CutoverRepository.ACTION);
                try (var statement = db.createStatement()) {
                    assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE cutover_assessment_flow SET decision = 'WOULD_ALLOW' WHERE assessment_id = 'young-89'"));
                }
                db.rollback();
                db.setAutoCommit(true);
            }
            assertEquals(3, repository.flowCount("young-89", "FLOW", null));
        }
    }

    /** One assessment created {@code days} days ago with {@code flows} FLOW rows and, when any, one MISSING_RULE row. */
    private static void insertAged(Connection db, String id, int days, int flows) throws SQLException {
        String summary = "{\"counts\":{\"tuples\":" + flows + ",\"missingRuleGroups\":" + (flows > 0 ? 1 : 0)
            + "},\"coverage\":[{\"code\":\"LOG_PERIOD\"}],\"uploads\":[],\"headline\":\"h\"}";
        try (var statement = db.prepareStatement("INSERT INTO cutover_assessment(assessment_id, segment_id, segment_revision, created_at, actor, "
                + "target_source, topology_snapshot_id, engine_version, status, readiness, summary) VALUES (?, ?, 1, now() - make_interval(days => ?), ?, "
                + "'PATH', 'topology-synthetic-01', 'cutover-1', 'COMPLETE', ?, ?::jsonb)")) {
            statement.setString(1, id); statement.setString(2, SEGMENT); statement.setInt(3, days); statement.setString(4, ACTOR);
            statement.setString(5, flows > 0 ? "BLOCKERS_FOUND" : "NO_EVIDENCE"); statement.setString(6, summary);
            statement.executeUpdate();
        }
        try (var statement = db.prepareStatement("INSERT INTO cutover_assessment_flow(assessment_id, kind, ordinal, decision, flow) VALUES (?, ?, ?, ?, ?::jsonb)")) {
            for (int i = 0; i < flows; i++) {
                statement.setString(1, id); statement.setString(2, "FLOW"); statement.setInt(3, i); statement.setString(4, "WOULD_DROP");
                statement.setString(5, "{\"flowSource\":\"192.0.2." + (10 + i) + "\",\"decision\":\"WOULD_DROP\"}");
                statement.addBatch();
            }
            if (flows > 0) {
                statement.setString(1, id); statement.setString(2, "MISSING_RULE"); statement.setInt(3, 0); statement.setString(4, "MISSING_RULE");
                statement.setString(5, "{\"flowDestination\":\"198.51.100.8\"}");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
