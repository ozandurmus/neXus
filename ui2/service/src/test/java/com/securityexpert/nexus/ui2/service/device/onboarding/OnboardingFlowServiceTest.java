package com.securityexpert.nexus.ui2.service.device.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceOnboarding;
import com.securityexpert.nexus.ui2.persistence.device.DeviceOnboardingRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.onboarding.OnboardingFlowService.Admission;

/** docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md: identity, then inventory, then configuration; stop with a reason. */
class OnboardingFlowServiceTest {

    static final class MemoryOnboarding implements DeviceOnboardingRepository {
        final Map<String, DeviceOnboarding> rows = new HashMap<>();

        @Override
        public void start(String deviceId, String source, String confirmJobId, String actor, String action) {
            rows.put(deviceId, new DeviceOnboarding(deviceId, source, DeviceOnboarding.RUNNING, DeviceOnboarding.IDENTITY,
                    Optional.of(confirmJobId), Optional.empty(), "", Instant.EPOCH, Instant.EPOCH, Optional.empty()));
        }

        @Override
        public Optional<DeviceOnboarding> find(String deviceId) {
            return Optional.ofNullable(rows.get(deviceId));
        }

        @Override
        public List<DeviceOnboarding> findRunning() {
            return rows.values().stream().filter(r -> DeviceOnboarding.RUNNING.equals(r.state())).toList();
        }

        @Override
        public List<DeviceOnboarding> findOpen() {
            return rows.values().stream().filter(r -> !DeviceOnboarding.COMPLETED.equals(r.state())).toList();
        }

        @Override
        public boolean advance(String deviceId, String expectedStep, Optional<String> expectedJobId, String state,
                String step, Optional<String> stepJobId, Optional<String> reason, String skipped, String actor,
                String action) {
            DeviceOnboarding r = rows.get(deviceId);
            if (r == null || !r.step().equals(expectedStep) || !r.stepJobId().equals(expectedJobId)) {
                return false;
            }
            rows.put(deviceId, new DeviceOnboarding(deviceId, r.source(), state, step, stepJobId, reason, skipped,
                    r.startedAt(), Instant.EPOCH,
                    DeviceOnboarding.COMPLETED.equals(state) ? Optional.of(Instant.EPOCH) : Optional.empty()));
            return true;
        }
    }

    static final class Jobs implements JobRecordDao {
        final Map<String, JobRow> rows = new HashMap<>();

        void put(String jobId, String state, String reason) {
            rows.put(jobId, new JobRow(jobId, "cap", "d1", "CLASS_0_READ", state, null, 0, null, reason, "device", "d1"));
        }

        @Override
        public Optional<JobRow> find(String jobId) {
            return Optional.ofNullable(rows.get(jobId));
        }

        @Override
        public Optional<String> insertRequestedIfAbsent(String a, String b, String c, String d, String e, String f,
                String g, String h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> insertRequestedIfAbsentForRun(String a, String b, String c, String d, String e,
                String f, String g, String h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> findJobIdByIdempotencyKey(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<JobRow> findMostRecentByTargetDeviceId(String deviceId) {
            return Optional.empty();
        }
    }

    final MemoryOnboarding onboarding = new MemoryOnboarding();
    final Jobs jobs = new Jobs();
    final Map<String, Admission> admissionByStep = new HashMap<>();
    final List<String> admitted = new ArrayList<>();
    DeviceEnrollmentState enrollment = DeviceEnrollmentState.ENROLLED;

    OnboardingFlowService service() {
        return new OnboardingFlowService(onboarding, id -> Optional.of(enrollment), jobs, (id, step, actor) -> {
            admitted.add(step + "@" + actor);
            return admissionByStep.getOrDefault(step, new Admission.Admitted(step + "-job"));
        }, (id, actor) -> new OnboardingFlowService.RetryOutcome.Retried(DeviceOnboarding.IDENTITY, "confirm-2"));
    }

    @Test
    void runsIdentityInventoryConfigurationInOrderAndCompletes() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        OnboardingFlowService flows = service();

        jobs.put("confirm-1", "EXECUTING", null);
        assertEquals(0, flows.advanceAll(), "nothing moves while the confirm runs");

        jobs.put("confirm-1", "COMPLETED", null);
        flows.advanceAll();
        assertEquals(DeviceOnboarding.INVENTORY, onboarding.rows.get("d1").step());
        assertEquals(Optional.of("inventory-job"), onboarding.rows.get("d1").stepJobId());

        jobs.put("inventory-job", "COMPLETED", null);
        flows.advanceAll();
        assertEquals(DeviceOnboarding.CONFIGURATION, onboarding.rows.get("d1").step());

        jobs.put("configuration-job", "COMPLETED", null);
        flows.advanceAll();
        assertEquals(DeviceOnboarding.COMPLETED, onboarding.rows.get("d1").state());
        assertEquals(List.of("inventory@system:onboarding", "configuration@system:onboarding"), admitted);
    }

    @Test
    void aFailedStepStopsTheFlowWithTheJobsReasonAndAdmitsNothingElse() {
        onboarding.start("d1", "discovery_import", "confirm-1", "a", "x");
        jobs.put("confirm-1", "COMPLETED", null);
        OnboardingFlowService flows = service();
        flows.advanceAll();
        jobs.put("inventory-job", "FAILED", "auth_failed: the credential was refused");
        flows.advanceAll();

        DeviceOnboarding row = onboarding.rows.get("d1");
        assertEquals(DeviceOnboarding.STOPPED, row.state());
        assertEquals(DeviceOnboarding.INVENTORY, row.step());
        assertTrue(row.reason().orElseThrow().contains("auth_failed"));
        assertEquals(List.of("inventory@system:onboarding"), admitted);
    }

    @Test
    void aStepTheVendorHasNoReadForIsSkippedWithItsCodeNotFailed() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        jobs.put("confirm-1", "COMPLETED", null);
        admissionByStep.put(DeviceOnboarding.CONFIGURATION, new Admission.Refused("VENDOR_UNSUPPORTED", "no read"));
        OnboardingFlowService flows = service();
        flows.advanceAll();
        jobs.put("inventory-job", "COMPLETED", null);
        flows.advanceAll();

        DeviceOnboarding row = onboarding.rows.get("d1");
        assertEquals(DeviceOnboarding.COMPLETED, row.state());
        assertEquals("configuration:VENDOR_UNSUPPORTED", row.skipped());
    }

    @Test
    void bothReadsNotApplicableCompletesStraightAfterIdentity() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        jobs.put("confirm-1", "COMPLETED", null);
        admissionByStep.put(DeviceOnboarding.INVENTORY, new Admission.Refused("MANAGEMENT_SERVER_UNGATED", "n/a"));
        admissionByStep.put(DeviceOnboarding.CONFIGURATION, new Admission.Refused("MANAGEMENT_SERVER_UNGATED", "n/a"));
        service().advanceAll();

        DeviceOnboarding row = onboarding.rows.get("d1");
        assertEquals(DeviceOnboarding.COMPLETED, row.state());
        assertEquals("inventory:MANAGEMENT_SERVER_UNGATED,configuration:MANAGEMENT_SERVER_UNGATED", row.skipped());
    }

    @Test
    void anOtherAdmissionRefusalStopsAtThatStep() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        jobs.put("confirm-1", "COMPLETED", null);
        admissionByStep.put(DeviceOnboarding.INVENTORY, new Admission.Refused("GATE_CLOSED", "gate closed"));
        service().advanceAll();

        DeviceOnboarding row = onboarding.rows.get("d1");
        assertEquals(DeviceOnboarding.STOPPED, row.state());
        assertEquals(DeviceOnboarding.INVENTORY, row.step());
        assertEquals(Optional.empty(), row.stepJobId());
    }

    @Test
    void retryReadmitsOnlyTheStoppedStep() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        jobs.put("confirm-1", "COMPLETED", null);
        admissionByStep.put(DeviceOnboarding.INVENTORY, new Admission.Refused("GATE_CLOSED", "gate closed"));
        OnboardingFlowService flows = service();
        flows.advanceAll();

        admissionByStep.remove(DeviceOnboarding.INVENTORY);
        var outcome = flows.retry("d1", "user:po");
        assertInstanceOf(OnboardingFlowService.RetryOutcome.Retried.class, outcome);
        DeviceOnboarding row = onboarding.rows.get("d1");
        assertEquals(DeviceOnboarding.RUNNING, row.state());
        assertEquals(DeviceOnboarding.INVENTORY, row.step());
        assertEquals(List.of("inventory@system:onboarding", "inventory@user:po"), admitted);
    }

    @Test
    void retryOfARunningFlowIsRefused() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        assertInstanceOf(OnboardingFlowService.RetryOutcome.NotStopped.class, service().retry("d1", "user:po"));
        assertInstanceOf(OnboardingFlowService.RetryOutcome.NotFound.class, service().retry("d2", "user:po"));
    }

    @Test
    void aCompletedConfirmOnADeviceThatIsNotEnrolledStops() {
        onboarding.start("d1", "manual_registration", "confirm-1", "a", "x");
        jobs.put("confirm-1", "COMPLETED", null);
        enrollment = DeviceEnrollmentState.DRAFT;
        service().advanceAll();
        assertEquals(DeviceOnboarding.STOPPED, onboarding.rows.get("d1").state());
        assertFalse(admitted.stream().anyMatch(s -> s.startsWith("inventory")));
    }
}
