package com.securityexpert.nexus.ui2.jobs.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.discovery.DiscoveryRunReadPort;
import com.securityexpert.nexus.ui2.jobs.discovery.DiscoveryRunSnapshot;

/**
 * 14F DR-1: {@link JobAdmissionService#submitForRun} admits a discovery
 * capability against a {@code discovery_run} row -- device-target checks
 * (F4/F6) are untouched (this test never supplies a working {@link
 * DeviceEnrollmentReadPort}, and admission still succeeds), and a run that
 * is not {@code REQUESTED} is refused, mirroring {@link JobAdmissionServiceTest}'s
 * evaluation-order proof for the device path.
 */
class DiscoveryRunAdmissionTest {

    private static Capability discoveryCapability() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "check_point",
                "cp_multi_domain_server", TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect),
                List.of(), "UNKNOWN", List.of(), false);
        return new CapabilityRegistryLoader(key -> List.of()).load(spec);
    }

    private static DeviceEnrollmentReadPort neverCalled() {
        return deviceId -> {
            throw new AssertionError("submitForRun must never consult DeviceEnrollmentReadPort (14F DR-1)");
        };
    }

    private static final class InMemoryAdmissionRepository implements JobAdmissionRepository {
        private final Map<String, String> jobsByIdempotencyKey = new HashMap<>();

        @Override
        public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey, String capabilityId,
                String targetDeviceId, String actionClassId, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Optional<String> createRequestedIfAbsentForRun(String jobId, String idempotencyKey,
                String capabilityId, String targetRunId, String actionClassId, String actorFingerprint,
                String actionId) {
            if (jobsByIdempotencyKey.containsKey(idempotencyKey)) {
                return Optional.empty();
            }
            jobsByIdempotencyKey.put(idempotencyKey, jobId);
            return Optional.of(jobId);
        }

        @Override
        public Optional<String> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(jobsByIdempotencyKey.get(idempotencyKey));
        }
    }

    @Test
    void aRequestedRunIsAdmitted() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(discoveryCapability()));
        DiscoveryRunReadPort requestedRun = runId -> Optional.of(new DiscoveryRunSnapshot(runId, "REQUESTED"));
        JobAdmissionService admission = new JobAdmissionService(registry, neverCalled(), requestedRun,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submitForRun(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "run-1", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Admitted, "expected Admitted, got " + result);
    }

    @Test
    void aMissingRunIsRefused() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(discoveryCapability()));
        DiscoveryRunReadPort noSuchRun = runId -> Optional.empty();
        JobAdmissionService admission = new JobAdmissionService(registry, neverCalled(), noSuchRun,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submitForRun(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "run-none",
                null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DISCOVERY_RUN_NOT_FOUND", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void aRunThatAlreadyHasAJobIsRefused() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(discoveryCapability()));
        DiscoveryRunReadPort runningRun = runId -> Optional.of(new DiscoveryRunSnapshot(runId, "RUNNING"));
        JobAdmissionService admission = new JobAdmissionService(registry, neverCalled(), runningRun,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submitForRun(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "run-running",
                null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DISCOVERY_RUN_NOT_ELIGIBLE", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void anUnknownCapabilityIsRefusedBeforeAnyRunCheck() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of());
        DiscoveryRunReadPort neverCalledRun = runId -> {
            throw new AssertionError("the run must not be looked up when the capability is unknown");
        };
        JobAdmissionService admission = new JobAdmissionService(registry, neverCalled(), neverCalledRun,
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submitForRun("does-not-exist", "run-1", null, "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("CAPABILITY_UNKNOWN", ((AdmissionResult.Refused) result).code());
    }

    @Test
    void theLegacyThreeArgConstructorRefusesEveryRunRatherThanThrowing() {
        CapabilityRegistry registry = CapabilityRegistry.of(List.of(discoveryCapability()));
        JobAdmissionService admission = new JobAdmissionService(registry, neverCalled(),
                new InMemoryAdmissionRepository());

        AdmissionResult result = admission.submitForRun(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "run-1", null,
                "actor", "action");

        assertTrue(result instanceof AdmissionResult.Refused);
        assertEquals("DISCOVERY_RUN_NOT_FOUND", ((AdmissionResult.Refused) result).code());
    }
}
