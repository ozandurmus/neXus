package com.securityexpert.nexus.ui2.worker.diagnostic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.device.inventory.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.platform.*;

class CpviewMeasurementTest {
    private final JobLeaseRepository leases = mock(JobLeaseRepository.class);
    private final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final JobRecordDao jobs = mock(JobRecordDao.class);
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final ArtefactStore store = mock(ArtefactStore.class);
    private final DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);

    private void execute(boolean signed, ExecResult result, String context) {
        var device = new DeviceRecord("device-1", "gateway", "check_point", "manual", Instant.now(), false,
                DeviceEnrollmentState.ENROLLED, false, "synthetic-reference");
        when(devices.find("device-1")).thenReturn(Optional.of(device));
        var summary = new DeviceSummaryRecord("device-1", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                Optional.empty(), Optional.of("invented-appliance"), Optional.empty(), Optional.empty(), Optional.of("opaque-cluster"));
        when(devices.findSummary("device-1")).thenReturn(Optional.of(summary));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                new EndpointRecord("endpoint-1", "device-1", "ssh_exec", "192.0.2.10", Instant.now())));
        when(inventory.findLatestRun("device-1")).thenReturn(Optional.of(new InventoryRun("run-1", "device-1", "old-job", Instant.now(), 1,
                List.of(new InventoryContext(context, List.of(), List.of())), List.of(), Optional.empty())));
        when(jobs.findDiagnostic("job-1")).thenReturn(Optional.of(new JobRecordDao.DiagnosticJob("job-1", "device-1", null,
                "CLAIMED", null, false, null, null, null, "cpview -p", "synthetic-actor", Instant.now(), null, DiagnosticRead.CPVIEW_GATE)));
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(attempts.findByJobAndStep("job-1", 0)).thenReturn(List.of());
        when(attempts.insertPreContact("job-1", 1, 0, DiagnosticRead.CPVIEW_GATE, "read", 1)).thenReturn("attempt-1");
        when(attempts.markBoundaryCrossed("attempt-1", 1)).thenReturn(true);
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(mock(TransportSession.class)));
        when(transport.exec(any(), any(), any())).thenReturn(result);
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).map(r -> signed
            ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                r.actionClass(), SignOffState.SIGNED_OFF, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        new DiagnosticJobExecutor(leases, attempts, devices, jobs, transport, gates, store, DevicePlatformFactsRepository.NONE, inventory)
                .execute("job-1", 1, "device-1", "192.0.2.10", 22, "synthetic-reference");
    }
    @Test void pendingGateRefusesBeforeAnyContact() {
        execute(false, new ExecResult.TimedOut(), "physical");
        verifyNoInteractions(transport, store, attempts);
        verify(leases).transitionState("job-1", 1, JobState.CLAIMED, JobState.REJECTED,
                "system:worker", "diagnostic_claim_check", "DIAGNOSTIC_UNAVAILABLE");
    }
    @Test void vsxContextRefusesEvenWithSignedGate() {
        execute(true, new ExecResult.TimedOut(), "001");
        verifyNoInteractions(transport, store, attempts);
    }
    @Test void timeoutRecordsTimeoutWithoutRetryOrRawStorage() {
        execute(true, new ExecResult.TimedOut(), "physical");
        verify(transport).exec(any(), eq(new ExecSpec("bash -lc 'cpview -p'", false, 0, 1024 * 1024)), eq(Duration.ofSeconds(30)));
        verify(transport, never()).execInteractive(any(), any(), any());
        verify(transport).disconnect(any());
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.FAILED,
                "system:worker", "diagnostic_finished", "TIMEOUT");
        verifyNoInteractions(store);
    }
    @Test void onlyProjectionIsPersisted() throws Exception {
        var handle = mock(ArtefactStore.ArtefactHandle.class);
        var sink = new java.io.ByteArrayOutputStream();
        when(store.open("device-1", "job-1", "check_point", false)).thenReturn(handle);
        when(handle.sink()).thenReturn(sink);
        when(handle.finish()).thenReturn(new ArtefactStore.ArtefactMetadata(
                new com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef("opaque-output"),
                "synthetic-digest", 0, "synthetic-digest", 0, "none", "synthetic-key", new byte[0]));
        when(jobs.writeDiagnosticOutput(anyString(), anyString(), any(), anyInt(), anyInt())).thenReturn(true);
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), nullable(String.class), anyBoolean(),
                nullable(Long.class), nullable(Long.class), nullable(String.class))).thenReturn(true);
        String raw = new String(getClass().getResourceAsStream("/diagnostic/cpview-synthetic.txt").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        execute(true, new ExecResult.Completed(raw, 0), "physical");
        assertEquals(CpviewProjection.project(raw), sink.toString(java.nio.charset.StandardCharsets.UTF_8));
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.COMPLETED,
                "system:worker", "diagnostic_finished", "OUTPUT_RECORDED");
    }
    @Test void oversizedCompletedOutputIsNotStored() {
        execute(true, new ExecResult.Completed("x".repeat(1024 * 1024 + 1), 0), "physical");
        verifyNoInteractions(store);
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.FAILED,
                "system:worker", "diagnostic_finished", "OUTPUT_LIMIT_EXCEEDED");
    }
}
