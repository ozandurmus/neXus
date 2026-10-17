package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;

/** The diagnostic worker records the numeric summary of a cp_syslog_measure_* read next to the masked output. */
class CpSyslogMeasureExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HostnameFingerprint KEY = HostnameFingerprint.fromBase64Key(
            Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)));
    private static final String STAT_OUTPUT = "Last login for FW-TANGO-04 from 192.0.2.77\r\n131075 48210 1760699400\r\n"
            + "262201 20971520 1760650000\r\n524300 0 1760000000\r\n786433 8123456 1760699999\r\n12 66520 1760690000\r\n";

    private final JobLeaseRepository leases = mock(JobLeaseRepository.class);
    private final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final JobRecordDao jobs = mock(JobRecordDao.class);
    private final DeviceTransport transport = mock(DeviceTransport.class);
    private final ArtefactStore store = mock(ArtefactStore.class);
    private final SystemLogMeasurementRepository measurements = mock(SystemLogMeasurementRepository.class);
    private final java.io.ByteArrayOutputStream stored = new java.io.ByteArrayOutputStream();

    private void execute(String gateId, String output, int exit, boolean configured, boolean recordResult) throws Exception {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        String command = rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow().canonicalCommandKey();
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point", "manual",
                Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
        when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                new EndpointRecord("endpoint-1", "device-1", "ssh_exec", "192.0.2.10", Instant.now())));
        when(jobs.findDiagnostic("job-1")).thenReturn(Optional.of(new JobRecordDao.DiagnosticJob("job-1", "device-1", null,
                "CLAIMED", null, false, null, null, null, command, "synthetic-actor", Instant.now(), null, gateId)));
        when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
        when(attempts.findByJobAndStep("job-1", 0)).thenReturn(List.of());
        when(attempts.insertPreContact("job-1", 1, 0, gateId, "read", 1)).thenReturn("attempt-1");
        when(attempts.markBoundaryCrossed("attempt-1", 1)).thenReturn(true);
        when(attempts.writeOutcome(anyString(), anyLong(), anyString(), nullable(String.class), anyBoolean(),
                nullable(Long.class), nullable(Long.class), nullable(String.class))).thenReturn(true);
        var handle = mock(ArtefactStore.ArtefactHandle.class);
        when(store.open("device-1", "job-1", "check_point", false)).thenReturn(handle);
        when(handle.sink()).thenReturn(stored);
        when(handle.finish()).thenReturn(new ArtefactStore.ArtefactMetadata(new ArtefactRef("opaque-output"),
                "synthetic-digest", 0, "synthetic-digest", 0, "none", "synthetic-key", new byte[0]));
        when(jobs.writeDiagnosticOutput(anyString(), anyString(), any(), anyInt(), anyInt())).thenReturn(true);
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(mock(TransportSession.class)));
        when(transport.exec(any(), any(), any())).thenReturn(new ExecResult.Completed(output, exit));
        when(measurements.record(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(recordResult);
        var executor = new DiagnosticJobExecutor(leases, attempts, devices, jobs, transport, gates, store);
        if (configured) executor.withSyslogMeasurement(measurements, KEY,
                Clock.fixed(Instant.ofEpochSecond(1_760_700_000L), ZoneOffset.UTC));
        executor.execute("job-1", 1, "device-1", "192.0.2.10", 22, "credential-ref");
    }

    @Test @SuppressWarnings("unchecked") void aMeasureReadRecordsItsNumericSummaryFromTheRawOutput() throws Exception {
        execute("cp_syslog_measure_stat", STAT_OUTPUT, 0, true, true);
        var summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(measurements).record(eq("job-1"), eq(1L), eq("device-1"), eq("check_point"),
                eq("cp_syslog_measure_stat"), summary.capture());
        Map<String, Object> parsed = JSON.readValue(summary.getValue(), Map.class);
        assertEquals("MEASURED", parsed.get("status"));
        assertEquals(1_760_700_000, ((Number) parsed.get("measuredAtEpoch")).longValue());
        var files = (List<Map<String, Object>>) parsed.get("files");
        assertEquals(8123456, ((Number) files.get(3).get("sizeBytes")).longValue());
        assertEquals("cpd.elg", files.get(3).get("class"));
        assertFalse(summary.getValue().contains("FW-TANGO") || summary.getValue().contains("192.0.2")
                || summary.getValue().contains("131075"));
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.COMPLETED,
                "system:worker", "diagnostic_finished", "OUTPUT_RECORDED");
    }

    @Test void theStoredDiagnosticOutputIsUnchangedByTheSummary() throws Exception {
        execute("cp_syslog_measure_stat", STAT_OUTPUT, 0, true, true);
        assertEquals(com.securityexpert.nexus.ui2.platform.DiagnosticText.scrubSecrets(STAT_OUTPUT),
                stored.toString(StandardCharsets.UTF_8));
    }

    @Test void aNonZeroExitStillSummarisesWhatWasPrinted() throws Exception {
        execute("cp_syslog_measure_stat_ext", "/var/log/hcp.elg 5 1760000000\nstat: cannot stat '/x/fwk.elg': No such file or directory\n",
                1, true, true);
        verify(measurements).record(eq("job-1"), eq(1L), eq("device-1"), eq("check_point"),
                eq("cp_syslog_measure_stat_ext"), contains("\"present\":false"));
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.FAILED,
                "system:worker", "diagnostic_finished", "COMMAND_EXIT_NONZERO");
    }

    @Test void aSummaryThatCannotBeRecordedFailsTheJobVisibly() throws Exception {
        execute("cp_syslog_measure_stat", STAT_OUTPUT, 0, true, false);
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.FAILED,
                "system:worker", "diagnostic_finished", "MEASUREMENT_NOT_RECORDED");
    }

    @Test void withoutTheCollaboratorsNothingIsRecordedAndTheReadStillCompletes() throws Exception {
        execute("cp_syslog_measure_stat", STAT_OUTPUT, 0, false, true);
        verifyNoInteractions(measurements);
        verify(leases).transitionState("job-1", 1, JobState.EXECUTING, JobState.COMPLETED,
                "system:worker", "diagnostic_finished", "OUTPUT_RECORDED");
    }

    @Test void anotherDiagnosticReadRecordsNoSummary() throws Exception {
        execute("cp_failover_syncstat", "Sync status: ok\n", 0, true, true);
        verifyNoInteractions(measurements);
    }
}
