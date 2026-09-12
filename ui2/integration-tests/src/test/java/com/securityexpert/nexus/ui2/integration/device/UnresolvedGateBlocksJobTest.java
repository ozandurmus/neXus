package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.admission.PersistenceJobAdmissionRepository;
import com.securityexpert.nexus.ui2.jobs.device.PersistenceDeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;

/**
 * B1-4b contract §8 test 5 ({@code unresolved_gate_blocks_job}), proved
 * against a real PostgreSQL 16 server.
 *
 * <p>This test was previously blocked on two things: a real database, and
 * B1-4's gate-resolution/job-admission code. Both now exist
 * ({@code JobAdmissionService}, {@code CapabilityRegistry#isExecutionEligible},
 * {@code V4}'s {@code gate_registry}), so the database-backed half is proved
 * here: an {@code ENROLLED}, not-disabled device plus a capability whose gate
 * resolution is {@code UNKNOWN} is refused at admission, and <b>no job row is
 * ever created</b> — so nothing is ever claimable, and device execution is
 * unreachable by construction rather than by a later guard.</p>
 *
 * <p>Two refusals, not one: the gate refusal is asserted against an
 * {@code ENROLLED} device (so it cannot be the enrollment refusal wearing
 * another name), and the enrollment refusal is asserted against an
 * execution-eligible capability, with distinct reason codes. Neither ever
 * substitutes for the other.</p>
 *
 * <p><b>Not proved here:</b> the executor-level "{@code DeviceTransport.connect}
 * was never called" assertion for a step whose gate is {@code UNKNOWN} —
 * that needs a transport double inside the step executor and is proved
 * without a database by {@code job-engine}'s
 * {@code GateUnresolvedCapabilityNeverExecutesTest}. What this class adds is
 * that the refusal holds against real schema state, and that the refusal
 * leaves the {@code jobs} table empty.</p>
 */
class UnresolvedGateBlocksJobTest {

    private static final String UNRESOLVED_GATE_CAPABILITY = "cp.harness.unresolved_gate";
    private static final String RESOLVED_GATE_CAPABILITY = "cp.harness.resolved_gate";

    private static Ui2PostgresFixture fixture;
    private static JobAdmissionService admission;
    private static String enrolledDeviceId;
    private static String draftDeviceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("unresolved_gate");

        TransactionBoundary boundary =
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        JooqDeviceRepository devices = new JooqDeviceRepository(boundary);

        CapabilityRegistry registry = CapabilityRegistry.of(List.of(
                capability(UNRESOLVED_GATE_CAPABILITY, false),
                capability(RESOLVED_GATE_CAPABILITY, true)));

        admission = new JobAdmissionService(registry,
                new PersistenceDeviceEnrollmentReadPort(devices),
                new PersistenceJobAdmissionRepository(new JooqJobRecordDao(boundary)));

        try (Connection app = fixture.appConnection()) {
            String credentialReferenceId = Ui2Rows.insertCredentialReference(app);
            enrolledDeviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "ENROLLED");
            draftDeviceId = Ui2Rows.insertDevice(app, credentialReferenceId, "DRAFT");
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aStepWithUnresolvedGateNeverReachesDeviceExecutionAgainstAnEnrolledDevice() throws SQLException {
        AdmissionResult result = admission.submit(UNRESOLVED_GATE_CAPABILITY, enrolledDeviceId,
                "harness-unresolved-gate", Ui2Rows.ACTOR, "job.submit");

        AdmissionResult.Refused refused = assertInstanceOf(AdmissionResult.Refused.class, result,
                "an UNKNOWN-gated capability must never be admitted, even against an ENROLLED device");
        assertEquals("CAPABILITY_NOT_EXECUTION_ELIGIBLE", refused.code());

        try (Connection app = fixture.appConnection()) {
            // No row in ANY state references the unresolved capability --
            // not a REJECTED one, none. Nothing claimable ever exists for
            // it, so device execution is unreachable by construction rather
            // than by a later guard. (Scoped to this capability, never to
            // "the jobs table is empty": a sibling test in this class
            // deliberately admits a job, and a global count would then
            // assert test ordering instead of the rule.)
            assertEquals(0L, Ui2Rows.count(app,
                    "SELECT count(*) FROM jobs WHERE capability_id = '" + UNRESOLVED_GATE_CAPABILITY
                            + "' OR job_type = '" + UNRESOLVED_GATE_CAPABILITY + "'"),
                    "a refused submission must create no job row at all -- not a REJECTED one, none");
        }
    }

    @Test
    void theSameEnrolledDeviceAdmitsAnExecutionEligibleCapability() throws SQLException {
        // Proves the refusal above is about the gate, not about this device.
        AdmissionResult result = admission.submit(RESOLVED_GATE_CAPABILITY, enrolledDeviceId,
                "harness-resolved-gate", Ui2Rows.ACTOR, "job.submit");

        AdmissionResult.Admitted admitted = assertInstanceOf(AdmissionResult.Admitted.class, result);

        try (Connection app = fixture.appConnection()) {
            assertEquals(1L, Ui2Rows.count(app, "SELECT count(*) FROM jobs WHERE job_id = '"
                    + admitted.jobId() + "' AND state = 'REQUESTED' AND target_device_id = '"
                    + enrolledDeviceId + "'"));
            assertEquals(1L, Ui2Rows.countAuditRows(app, "jobs", admitted.jobId()),
                    "an admitted job is a mutation and must carry its audit row");
        }
    }

    @Test
    void theEnrollmentRefusalIsADifferentRefusalWithItsOwnCode() {
        // B1-4b's own DRAFT refusal, asserted against an execution-eligible
        // capability so the two refusals are visibly independent.
        AdmissionResult result = admission.submit(RESOLVED_GATE_CAPABILITY, draftDeviceId,
                "harness-draft-device", Ui2Rows.ACTOR, "job.submit");

        AdmissionResult.Refused refused = assertInstanceOf(AdmissionResult.Refused.class, result);
        assertEquals("DEVICE_NOT_ELIGIBLE", refused.code());
        assertNotEquals("CAPABILITY_NOT_EXECUTION_ELIGIBLE", refused.code(),
                "the enrollment refusal must never be reported as the gate refusal");
    }

    private static Capability capability(String id, boolean executionEligible) {
        return new Capability(id, "harness-vendor", "harness-scope", TransportKind.SSH_EXEC,
                MaturityState.CAP_SPEC, List.of(), List.of(), Map.of(), executionEligible);
    }
}
