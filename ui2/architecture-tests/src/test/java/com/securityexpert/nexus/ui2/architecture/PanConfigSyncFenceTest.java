package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.service.failover.PanConfigSyncService;
import com.securityexpert.nexus.ui2.worker.failover.PanConfigSyncJobExecutor;

/**
 * PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §3: the service, the repository and the worker each honour the mutation
 * switch, and the worker's one write is prepared by the fenced repository call before the transport is invoked.
 */
class PanConfigSyncFenceTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter().importClasses(
        PanConfigSyncService.class, PanConfigSyncJobExecutor.class, JooqPanConfigSyncRepository.class);

    private boolean calls(Class<?> owner, String method, Class<?> target, String targetMethod) {
        JavaClass type = classes.get(owner);
        return type.getMethods().stream().filter(m -> m.getName().equals(method))
            .anyMatch(m -> m.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTarget().getOwner().getName().equals(target.getName()) && call.getTarget().getName().equals(targetMethod)));
    }

    @Test void everyLayerChecksTheSwitchAndTheWriteIsPreparedBeforeItIsSent() {
        assertTrue(calls(PanConfigSyncService.class, "request", FailoverMutationSwitch.class, "enabled"));
        assertTrue(calls(PanConfigSyncService.class, "request", JooqPanConfigSyncRepository.class, "request"));
        assertTrue(calls(PanConfigSyncJobExecutor.class, "execute", FailoverMutationSwitch.class, "enabled"));
        assertTrue(calls(PanConfigSyncJobExecutor.class, "gate", FailoverMutationSwitch.class, "enabled"));
        assertTrue(calls(PanConfigSyncJobExecutor.class, "execute", JooqPanConfigSyncRepository.class, "admission"));
        assertTrue(calls(PanConfigSyncJobExecutor.class, "execute", JooqPanConfigSyncRepository.class, "prepareSend"));
        assertTrue(calls(PanConfigSyncJobExecutor.class, "execute", PanConfigSyncJobExecutor.class, "afterSend"));
        // The write and its bookkeeping live in afterSend, reached from execute only after prepareSend admitted it
        // (Argon P0-3: outside the pre-send handling).
        long senders = classes.get(PanConfigSyncJobExecutor.class).getMethods().stream()
            .filter(m -> m.getMethodCallsFromSelf().stream().anyMatch(call -> call.getTarget().getName().equals("afterSend")
                && call.getTarget().getOwner().getName().equals(PanConfigSyncJobExecutor.class.getName())))
            .count();
        assertEquals(1, senders);
        assertTrue(calls(PanConfigSyncJobExecutor.class, "afterSend", JooqPanConfigSyncRepository.class, "recordReply"));
        assertTrue(calls(PanConfigSyncJobExecutor.class, "afterSend", JooqPanConfigSyncRepository.class, "finish"));
    }

    /** Argon P0-1: a per-endpoint configuration-write purpose, never the estate-wide failover singleton. */
    @Test void theConfigSyncNeverTakesTheFleetFailoverLease() {
        assertEquals("CONFIGURATION_WRITE", com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop.purposeOf(
            JooqPanConfigSyncRepository.JOB_TYPE));
        assertEquals("FAILOVER_EXECUTION", com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop.purposeOf("pan_cluster_failover"));
        assertEquals("FAILOVER_READINESS", com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop.purposeOf("pan_failover_readiness"));
        assertEquals("CONFIGURATION", com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop.purposeOf("pan_configuration_collect"));
        // acquireFleet is a no-op for this purpose: no repository is installed here, so any fleet acquisition would fail.
        var owner = new com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner("job-" + 1, 1, null, 0,
            "general", "general-" + "synthetic", 1, "CONFIGURATION_WRITE", false);
        var scope = com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.open(owner);
        try { assertDoesNotThrow(scope::acquireFleet); }
        finally { com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.attach(null); }
    }

    @Test void aDisabledWorkerRefusesTheWriteGateEvenWhenCalledDirectly() throws Exception {
        var worker = new PanConfigSyncJobExecutor(null, null, null, null, null, null, null, d -> {},
            java.time.Duration.ZERO, new FailoverMutationSwitch(false));
        var gate = PanConfigSyncJobExecutor.class.getDeclaredMethod("gate", String.class,
            com.securityexpert.nexus.ui2.platform.ActionClass.class, String.class);
        gate.setAccessible(true);
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> gate.invoke(worker,
            com.securityexpert.nexus.ui2.capability.GateRow.PAN_CONFIG_SYNC_COMMAND,
            com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_3_CONFIGURATION_WRITE, "pan_failover_config_sync"));
        assertEquals(FailoverMutationSwitch.DISABLED, error.getCause().getMessage());
    }
}
