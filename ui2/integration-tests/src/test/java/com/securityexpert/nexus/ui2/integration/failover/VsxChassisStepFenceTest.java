package com.securityexpert.nexus.ui2.integration.failover;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
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
 * VSX: the chassis step (vs_id null) and a VS step of the same cluster never overlap a failover
 * (docs/design/VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md §4). Synthetic rows only; no executor or transport.
 */
class VsxChassisStepFenceTest {
    private static final String CLUSTER = "CLS-TEST-VSX";
    private static final String ACTOR = Ui2Rows.ACTOR;

    private record Unit(JooqCpFailoverRepository repository, String device, String peer) {}

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
        return new Unit(new JooqCpFailoverRepository(boundary, true, JobWindowTestPolicy.PERMISSIVE), device, peer);
    }

    private static JooqCpFailoverRepository.Decision startVs(Unit u, String vsId) {
        var members = Set.of(u.device(), u.peer());
        var approval = u.repository().createRequestApproval(UUID.randomUUID().toString(), CLUSTER, vsId,
            UUID.nameUUIDFromBytes((CLUSTER + ":" + vsId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
            members, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic approval", ACTOR,
            "check_point", JooqCpFailoverRepository.ADMIN_SINGLE);
        return u.repository().requestBound(CLUSTER, vsId, Instant.now(), ACTOR, u.device(), true, "check_point",
            members, approval.approval().id(), approval.revision(), approval.executionNonce(), true, approval.policy());
    }

    @Test
    void anOpenChassisReadinessRefusesAVsFailoverAndAnOpenVsFailoverRefusesChassisReadiness() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("vsx_chassis_step_fence")) {
            var u = unit(fixture);
            var chassis = u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point");
            assertEquals("ADMITTED", chassis.code());
            assertEquals(JooqCpFailoverRepository.CHASSIS_RUN_ACTIVE, startVs(u, "12").code());
            assertEquals("ADMITTED", u.repository().requestReadiness(CLUSTER, "12", ACTOR, u.device(), "check_point").code(),
                "A VS readiness never conflicts with a chassis readiness");

            u.repository().state(chassis.runId(), "DONE", "DONE", "UNKNOWN", null, "VSX_CHASSIS_COMMANDS_NOT_APPROVED");
            var vsReadiness = u.repository().readinessStatuses(CLUSTER);
            assertTrue(vsReadiness.stream().anyMatch(s -> s.vsId() == null), "The chassis result is stored under vs_id null");
            for (var open : u.repository().runs(CLUSTER, "12"))
                if (!Set.of("DONE", "STOPPED").contains(open.state()))
                    u.repository().state(open.id(), "DONE", "DONE", "READY", null, "READY");

            var vs = startVs(u, "13");
            assertEquals("ADMITTED", vs.code());
            assertEquals(JooqCpFailoverRepository.VS_RUN_ACTIVE,
                u.repository().requestReadiness(CLUSTER, null, ACTOR, u.device(), "check_point").code());
        }
    }
}
