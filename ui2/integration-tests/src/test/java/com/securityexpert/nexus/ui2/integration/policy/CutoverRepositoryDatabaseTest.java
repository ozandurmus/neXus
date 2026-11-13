package com.securityexpert.nexus.ui2.integration.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
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
}
