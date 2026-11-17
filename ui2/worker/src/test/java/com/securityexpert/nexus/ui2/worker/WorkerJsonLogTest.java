package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentSnapshot;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.logging.JobLogContext;
import com.securityexpert.nexus.ui2.platform.logging.JsonLogFormatter;
import com.securityexpert.nexus.ui2.worker.compliance.server.Ui2ComplianceServer;
import com.securityexpert.nexus.ui2.worker.configuration.server.Ui2ConfigurationServer;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmCapabilityExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmJobExecutor;
import com.securityexpert.nexus.ui2.worker.confirm.ConfirmRequest;
import com.securityexpert.nexus.ui2.worker.confirm.PeerFollowResolver;
import com.securityexpert.nexus.ui2.worker.confirm.WorkerClaimLoop;
import com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisClaimLoop;
import com.securityexpert.nexus.ui2.worker.packetanalysis.PacketAnalysisJobExecutor;
import com.securityexpert.nexus.ui2.worker.policy.PolicyCollectionJobExecutor;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The worker's JSON lines parse with a real JSON parser, round-trip hostile text, and carry job/request MDC keys. */
class WorkerJsonLogTest {

    @Test
    void hostileTextRoundTripsAndStaysOnOneLine() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StreamHandler handler = new StreamHandler(bytes, new JsonLogFormatter());
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        String hostile = "quote\" back\\ nl\n cr\r tab\t ctl\u0001\u001f uml\u00fc euro\u20ac emoji\uD83D\uDE00";
        try (var scope = JobLogContext.open("42")) {
            logger.log(Level.INFO, "{0}", hostile);
            logger.log(Level.SEVERE, "boom 10.20.30.40", new IllegalStateException("bad " + hostile));
        }
        handler.flush();
        String[] lines = bytes.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(lines[0]);
        assertEquals(hostile, first.get("message").asText());
        assertEquals("INFO", first.get("level").asText());
        assertEquals("job:42", first.get("correlation_id").asText());
        assertEquals("42", first.get("job_id").asText());
        assertTrue(first.has("@timestamp") && first.has("logger_name") && first.has("thread_name"));
        JsonNode second = mapper.readTree(lines[1]);
        assertEquals("boom <ip>", second.get("message").asText());
        assertEquals("ERROR", second.get("level").asText());
        assertEquals("job:42", second.get("correlation_id").asText());
        String trace = second.get("stack_trace").asText();
        assertTrue(trace.startsWith("java.lang.IllegalStateException: bad quote\" back\\ nl\n"), trace);
        assertTrue(trace.contains("WorkerJsonLogTest"), trace);
        assertFalse(second.get("message").asText().contains("10.20.30.40"));
    }

    @Test
    void jobExecutorAndClaimLoopsSetMdcAndSystemLoggerJsonFieldsDuringExecutionAndClearAfter() throws Exception {
        String loggerName = "test.worker.corrid." + UUID.randomUUID();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StreamHandler handler = new StreamHandler(bytes, new JsonLogFormatter());
        Logger julLogger = Logger.getLogger(loggerName);
        julLogger.setUseParentHandlers(false);
        julLogger.addHandler(handler);

        try {
            // 1. Policy / general WorkerClaimLoop execution
            JobLeaseRepository leases = mock(JobLeaseRepository.class);
            JobRecordDao jobs = mock(JobRecordDao.class);
            DeviceRepository devices = mock(DeviceRepository.class);
            PolicyCollectionJobExecutor policyExecutor = mock(PolicyCollectionJobExecutor.class);

            when(leases.claimNext(anyString(), anyList(), any())).thenReturn(Optional.of(new ClaimedJob("job-101", 1L)));
            when(jobs.find("job-101")).thenReturn(Optional.of(new JobRow(
                    "job-101", "cp_policy_collect", null, "read", "CLAIMED", "policy-1", 1L, null, null,
                    "discovery_run", "run-1")));

            AtomicReference<String> policyCorr = new AtomicReference<>();
            AtomicReference<String> policyJobId = new AtomicReference<>();
            AtomicReference<String> policyJobKind = new AtomicReference<>();
            when(policyExecutor.execute("job-101", 1L, "run-1")).thenAnswer(inv -> {
                policyCorr.set(MDC.get("correlation_id"));
                policyJobId.set(MDC.get("job_id"));
                policyJobKind.set(MDC.get("job_kind"));
                System.getLogger(loggerName).log(System.Logger.Level.INFO, "executing policy job");
                return new JobOutcome.Completed();
            });

            WorkerClaimLoop policyLoop = WorkerClaimLoop.policy(
                    leases, jobs, devices, policyExecutor, "policy-1", "cp", "pan", false);
            assertTrue(policyLoop.claimAndExecuteOnce());
            assertEquals("job:job-101", policyCorr.get());
            assertEquals("job-101", policyJobId.get());
            assertEquals("cp_policy_collect", policyJobKind.get());
            assertNull(MDC.get("correlation_id"));
            assertNull(MDC.get("job_id"));
            assertNull(MDC.get("job_kind"));
            assertNull(JobLogContext.current());

            // 2. PacketAnalysisClaimLoop execution
            JobLeaseRepository paLeases = mock(JobLeaseRepository.class);
            JobRecordDao paJobs = mock(JobRecordDao.class);
            PacketAnalysisJobExecutor paExecutor = mock(PacketAnalysisJobExecutor.class);
            when(paLeases.claimNext(anyString(), anyList(), any())).thenReturn(Optional.of(new ClaimedJob("job-202", 2L)));
            when(paJobs.find("job-202")).thenReturn(Optional.of(new JobRow(
                    "job-202", "packet_analysis_run", null, "read", "CLAIMED", "packet_analysis-1", 2L, null, null,
                    "packet_analysis_case", "case-1")));

            AtomicReference<String> paCorr = new AtomicReference<>();
            AtomicReference<String> paKind = new AtomicReference<>();
            when(paExecutor.execute("job-202", 2L, "case-1")).thenAnswer(inv -> {
                paCorr.set(MDC.get("correlation_id"));
                paKind.set(MDC.get("job_kind"));
                System.getLogger(loggerName).log(System.Logger.Level.INFO, "executing packet analysis job");
                return JobState.COMPLETED;
            });

            PacketAnalysisClaimLoop paLoop = new PacketAnalysisClaimLoop(
                    paLeases, paJobs, paExecutor, "packet_analysis-1", Duration.ofMinutes(10));
            assertTrue(paLoop.claimAndExecuteOnce());
            assertEquals("job:job-202", paCorr.get());
            assertEquals("packet_analysis_run", paKind.get());
            assertNull(MDC.get("correlation_id"));
            assertNull(MDC.get("job_id"));
            assertNull(MDC.get("job_kind"));

            // 3. Direct ConfirmJobExecutor execution
            JobLeaseRepository confirmLeases = mock(JobLeaseRepository.class);
            JobStepAttemptRepository confirmAttempts = mock(JobStepAttemptRepository.class);
            DeviceEnrollmentReadPort enrollmentPort = mock(DeviceEnrollmentReadPort.class);
            DeviceRepository confirmDevices = mock(DeviceRepository.class);
            ConfirmCapabilityExecutor capExecutor = mock(ConfirmCapabilityExecutor.class);
            PeerFollowResolver peerResolver = mock(PeerFollowResolver.class);

            AtomicReference<String> directCorr = new AtomicReference<>();
            AtomicReference<String> directJobId = new AtomicReference<>();
            AtomicReference<String> directKind = new AtomicReference<>();
            when(enrollmentPort.findEnrollment("dev-1")).thenAnswer(inv -> {
                directCorr.set(MDC.get("correlation_id"));
                directJobId.set(MDC.get("job_id"));
                directKind.set(MDC.get("job_kind"));
                System.getLogger(loggerName).log(System.Logger.Level.INFO, "direct confirm executor");
                return Optional.of(new DeviceEnrollmentSnapshot("dev-1", DeviceEnrollmentState.DRAFT, true));
            });
            when(confirmLeases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), anyString()))
                    .thenReturn(true);

            ConfirmJobExecutor confirmJobExecutor = new ConfirmJobExecutor(
                    confirmLeases, confirmAttempts, enrollmentPort, confirmDevices, capExecutor, peerResolver);
            confirmJobExecutor.execute("job-303", 1L, "dev-1",
                    ConfirmRequest.checkPoint(new ConnectionTarget("ep-1", String.join(".", "192", "0", "2", "10"), 22), "cred-1", "trust-1"),
                    addr -> null, false);

            assertEquals("job:job-303", directCorr.get());
            assertEquals("job-303", directJobId.get());
            assertEquals("device_confirm_check_point", directKind.get());
            assertNull(MDC.get("correlation_id"));
            assertNull(MDC.get("job_id"));
            assertNull(MDC.get("job_kind"));

            handler.flush();
            String[] lines = bytes.toString(StandardCharsets.UTF_8).trim().split("\n");
            assertEquals(3, lines.length);
            ObjectMapper mapper = new ObjectMapper();

            JsonNode line0 = mapper.readTree(lines[0]);
            assertEquals("job:job-101", line0.get("correlation_id").asText());
            assertEquals("job-101", line0.get("job_id").asText());
            assertEquals("cp_policy_collect", line0.get("job_kind").asText());

            JsonNode line1 = mapper.readTree(lines[1]);
            assertEquals("job:job-202", line1.get("correlation_id").asText());
            assertEquals("job-202", line1.get("job_id").asText());
            assertEquals("packet_analysis_run", line1.get("job_kind").asText());

            JsonNode line2 = mapper.readTree(lines[2]);
            assertEquals("job:job-303", line2.get("correlation_id").asText());
            assertEquals("job-303", line2.get("job_id").asText());
            assertEquals("device_confirm_check_point", line2.get("job_kind").asText());
        } finally {
            julLogger.removeHandler(handler);
        }
    }

    @Test
    void configurationAndComplianceHttpServersEchoAndGenerateRequestCorrelationId() throws Exception {
        Ui2ConfigurationServer configServer = new Ui2ConfigurationServer(0);
        Ui2ComplianceServer complianceServer = new Ui2ComplianceServer(0);
        // Find free ports via ServerSocket
        int configPort;
        int compliancePort;
        try (var s1 = new java.net.ServerSocket(0); var s2 = new java.net.ServerSocket(0)) {
            configPort = s1.getLocalPort();
            compliancePort = s2.getLocalPort();
        }
        configServer = new Ui2ConfigurationServer(configPort);
        complianceServer = new Ui2ComplianceServer(compliancePort);
        configServer.start();
        complianceServer.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> r1 = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + configPort + "/healthz"))
                    .header("X-Request-Id", "valid-config-req-01")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r1.statusCode());
            assertEquals(Optional.of("valid-config-req-01"), r1.headers().firstValue("X-Request-Id"));

            HttpResponse<String> r2 = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + compliancePort + "/healthz"))
                    .header("X-Request-Id", "bad")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r2.statusCode());
            String generated = r2.headers().firstValue("X-Request-Id").orElseThrow();
            assertNotEquals("bad", generated);
            assertNotNull(UUID.fromString(generated));
        } finally {
            configServer.stop();
            complianceServer.stop();
        }
    }
}
