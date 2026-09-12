package com.securityexpert.nexus.ui2.jobs.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * Contract §8 test 10 (F4): "a capability with one UNKNOWN gate; fails if
 * it does not load/pass parser tests, or is ever claimed across 1000
 * polls." Adjudication F4 realigns this to a <b>pre-REQUESTED</b> refusal:
 * a submission naming this capability never reaches {@code REQUESTED} at
 * all, proven here by calling {@link JobAdmissionService#submit} 1000
 * times and asserting every one refuses, and separately that the
 * capability itself loaded successfully (offline-buildable, C4 §3.5).
 */
class GateUnresolvedCapabilityNeverExecutesTest {

    @Test
    void aCapabilityWithOneUnresolvedGateLoadsButNeverAdmitsAJob() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        // No gate_registry row exists for this command -> UNKNOWN: requires gate entry (C4 §3.3 step 4).
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show unregistered-command", false,
                Optional.empty(), Optional.of("^.*$"), Optional.of(30));

        CapabilitySpec spec = new CapabilitySpec("test_unresolved_gate", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect, exec), List.of(), "UNKNOWN",
                List.of(), false);

        GateRegistryPort emptyRegistry = key -> List.of(); // zero rows for every key -- nothing is ever SIGNED_OFF
        Capability capability = new CapabilityRegistryLoader(emptyRegistry).load(spec);

        // "loads/passes parser tests" -- the capability object itself
        // exists and is well-formed; nothing about loading it threw.
        assertEquals("test_unresolved_gate", capability.id());
        assertFalse(capability.executionEligible(), "a capability with any UNKNOWN-resolved step must never be "
                + "execution-eligible (C4 §3.5)");

        CapabilityRegistry registry = CapabilityRegistry.of(List.of(capability));
        assertTrue(registry.find("test_unresolved_gate").isPresent(),
                "the capability is still present in the full spec-level registry (C4 §3.5)");
        assertFalse(registry.executionEligibleIds().contains("test_unresolved_gate"));

        DeviceEnrollmentReadPort enrolledDevice = deviceId ->
                Optional.of(new DeviceEnrollmentSnapshot(deviceId, DeviceEnrollmentState.ENROLLED, false));
        JobAdmissionRepository neverCalledIfRefusedFirst = new JobAdmissionRepository() {
            @Override
            public Optional<String> createRequestedIfAbsent(String jobId, String idempotencyKey,
                    String capabilityId, String targetDeviceId, String actionClassId, String actorFingerprint,
                    String actionId) {
                throw new AssertionError("admission must refuse before ever attempting to create a REQUESTED row");
            }

            @Override
            public Optional<String> findByIdempotencyKey(String idempotencyKey) {
                throw new AssertionError("admission must refuse before ever querying for a REQUESTED row");
            }
        };

        JobAdmissionService admission = new JobAdmissionService(registry, enrolledDevice, neverCalledIfRefusedFirst);

        // "never claimed across 1000 polls" -- realized here as 1000
        // independent submission attempts, every one refused before a job
        // row is ever created (F4: refused BEFORE REQUESTED, not merely
        // never claimed once REQUESTED).
        for (int i = 0; i < 1000; i++) {
            AdmissionResult result = admission.submit("test_unresolved_gate", "device-1", null, "actor-1",
                    "action-1");
            assertTrue(result instanceof AdmissionResult.Refused, "poll " + i + " must refuse, got " + result);
            assertEquals("CAPABILITY_NOT_EXECUTION_ELIGIBLE", ((AdmissionResult.Refused) result).code());
        }
    }
}
