package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSyslogMeasure;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogMeasurementRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/** Scripted XML API only: no socket, host or device contact. Synthetic values only (FW-TANGO-04, 192.0.2.x). */
class PanSyslogMeasureJobExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Values that identify users, addresses, hosts, serials or moments; none may reach a stored summary. */
    private static final List<String> RAW = List.of("192.0.2.", "FW-TANGO-04", "synthetic-admin", "synthetic-user",
            "SYNTHSERIAL0001", "2026/10/15", "10:15:", "vsys1", "rule-alpha", "synthetic-pass", "synthetic-key",
            "Web", "Succeeded", "logged in");

    static String systemEntry(String time, String subtype, String severity, String eventid) {
        return "<entry logid=\"7000000000000000001\"><domain>1</domain><receive_time>" + time + "</receive_time>"
                + "<serial>SYNTHSERIAL0001</serial><seqno>101</seqno><type>SYSTEM</type><subtype>" + subtype + "</subtype>"
                + "<time_generated>" + time + "</time_generated><device_name>FW-TANGO-04</device_name>"
                + "<eventid>" + eventid + "</eventid><fmt>0</fmt><id>0</id><module>general</module>"
                + "<severity>" + severity + "</severity><opaque>User synthetic-user logged in from 192.0.2.15</opaque></entry>";
    }

    static String configEntry(String time, String cmd) {
        return "<entry logid=\"7000000000000000002\"><receive_time>" + time + "</receive_time><serial>SYNTHSERIAL0001</serial>"
                + "<type>CONFIG</type><subtype>0</subtype><host>192.0.2.20</host><cmd>" + cmd + "</cmd>"
                + "<admin>synthetic-admin</admin><client>Web</client><result>Succeeded</result>"
                + "<path> vsys  vsys1 rulebase security rules rule-alpha</path><device_name>FW-TANGO-04</device_name></entry>";
    }

    static String fin(String entries, int count) {
        return "<response status=\"success\"><result><job><tenq>2026/10/15 10:15:00</tenq><id>41</id>"
                + "<status>FIN</status></job><log><logs count=\"" + count + "\" progress=\"100\">" + entries
                + "</logs></log></result></response>";
    }

    static final String ACTIVE = "<response status=\"success\"><result><job><id>41</id><status>ACT</status></job>"
            + "<log><logs count=\"0\" progress=\"20\"/></log></result></response>";

    private final class Harness {
        final JobLeaseRepository leases = mock(JobLeaseRepository.class);
        final JobStepAttemptRepository attempts = mock(JobStepAttemptRepository.class);
        final DeviceRepository devices = mock(DeviceRepository.class);
        final DeviceTransport transport = mock(DeviceTransport.class);
        final List<String[]> recorded = new ArrayList<>();
        final List<XmlApiSpec> sent = new ArrayList<>();
        final AtomicInteger sleeps = new AtomicInteger();
        String finalState, finalReason;

        Harness(String role, Function<XmlApiSpec, XmlApiResult> script) {
            var device = new DeviceRecord("device-1", role, "palo_alto", "manual", Instant.now(), false,
                    DeviceEnrollmentState.ENROLLED, false, "credential-ref");
            when(devices.find("device-1")).thenReturn(Optional.of(device));
            when(devices.findEndpointByDeviceId("device-1")).thenReturn(Optional.of(
                    new EndpointRecord("endpoint-1", "device-1", "pan_xml_api", "192.0.2.1", Instant.now())));
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString())).thenReturn(true);
            when(leases.transitionState(anyString(), anyLong(), any(), any(), anyString(), anyString(), any())).thenAnswer(call -> {
                if (call.getArgument(2) == JobState.EXECUTING) {
                    finalState = call.getArgument(3).toString(); finalReason = call.getArgument(6);
                } else if (call.getArgument(3) == JobState.REJECTED) {
                    finalState = "REJECTED"; finalReason = call.getArgument(6);
                }
                return true;
            });
            when(attempts.findByJobAndStep(anyString(), anyInt())).thenReturn(List.of());
            AtomicInteger ids = new AtomicInteger();
            when(attempts.insertPreContact(anyString(), anyLong(), anyInt(), anyString(), anyString(), anyInt()))
                    .thenAnswer(call -> "attempt-" + ids.incrementAndGet());
            when(attempts.markBoundaryCrossed(anyString(), anyLong())).thenReturn(true);
            when(attempts.writeOutcome(anyString(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
            when(transport.xmlApiCall(any(), any(), any())).thenAnswer(call -> {
                XmlApiSpec spec = call.getArgument(1);
                sent.add(spec);
                if ("keygen".equals(spec.type()))
                    return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>synthetic-key</key></result></response>");
                return script.apply(spec);
            });
        }

        void run() {
            SystemLogMeasurementRepository store = new SystemLogMeasurementRepository() {
                @Override public boolean record(String jobId, long epoch, String deviceId, String vendor, String source, String summary) {
                    recorded.add(new String[] {jobId, deviceId, vendor, source, summary});
                    return true;
                }
                @Override public List<Measurement> list(Optional<String> deviceId, int limit) { return List.of(); }
            };
            var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                    .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
            GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
            new PanSyslogMeasureJobExecutor(leases, attempts, devices, transport,
                    ref -> new PanCredentialMaterial("synthetic-user", "synthetic-pass".toCharArray()), gates, store,
                    d -> sleeps.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC))
                    .execute("job-1", 1, "device-1");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> summary(String source) throws Exception {
            for (String[] row : recorded) if (row[3].equals(source)) return JSON.readValue(row[4], Map.class);
            throw new AssertionError("no summary for " + source);
        }
    }

    private static XmlApiResult answer(String body) {
        return new XmlApiResult.Completed(200, body);
    }

    private static boolean enqueue(XmlApiSpec spec) {
        return spec.formParams().containsKey("log-type");
    }

    @Test
    void finishedJobsAreSummarizedWithoutAnyRawValue() throws Exception {
        String systemEntries = systemEntry("2026/10/15 10:15:00", "auth", "medium", "auth-fail")
                + systemEntry("2026/10/15 04:15:00", "general", "informational", "general")
                + systemEntry("2026/10/14 22:15:00", "ha", "critical", "ha-state-change")
                + systemEntry("2026/10/14 10:15:00", "192.0.2.15", "synthetic-user", "FW-TANGO-04.example.invalid");
        String configEntries = configEntry("2026/10/15 10:15:00", "set") + configEntry("2026/10/15 09:15:00", "commit")
                + configEntry("2026/10/15 08:15:00", "set");
        AtomicInteger polls = new AtomicInteger();
        var harness = new Harness("gateway", spec -> {
            if (enqueue(spec)) return answer("<response status=\"success\"><result><msg><line>query job enqueued with jobid 41"
                    + "</line></msg><job>41</job></result></response>");
            if (polls.incrementAndGet() % 2 == 1) return answer(ACTIVE);
            return answer(polls.get() <= 2 ? fin(systemEntries, 4) : fin(configEntries, 3));
        });
        harness.run();

        assertEquals("COMPLETED", harness.finalState);
        assertEquals("MEASUREMENT_RECORDED", harness.finalReason);
        assertEquals(2, harness.recorded.size());
        var system = harness.summary("pan_system");
        assertEquals("MEASURED", system.get("status"));
        assertEquals(4, system.get("entries"));
        assertEquals(4, system.get("reportedCount"));
        assertEquals(false, system.get("capped"));
        assertEquals(86400, ((Number) system.get("spanSeconds")).intValue());
        assertEquals(4.0, ((Number) system.get("entriesPerDayEstimate")).doubleValue());
        assertEquals(2, system.get("polls"));
        assertEquals("yyyy/MM/dd HH:mm:ss", system.get("receiveTimeFormat"));
        assertEquals(Map.of("auth", 1, "general", 1, "ha", 1, "OTHER", 1), system.get("subtypeCounts"));
        assertEquals(Map.of("medium", 1, "informational", 1, "critical", 1, "OTHER", 1), system.get("severityCounts"));
        assertEquals(Map.of("auth-fail", 1, "general", 1, "ha-state-change", 1, "OTHER", 1), system.get("eventidTopCounts"));
        assertTrue(((List<?>) system.get("fieldNames")).containsAll(List.of("receive_time", "serial", "opaque", "eventid")));
        assertTrue(((Number) system.get("resultBytes")).longValue() > 0);
        var config = harness.summary("pan_config");
        assertEquals(3, config.get("entries"));
        assertEquals(Map.of("set", 2, "commit", 1), config.get("cmdCounts"));
        assertEquals(7200, ((Number) config.get("spanSeconds")).intValue());
        assertFalse(config.containsKey("eventidTopCounts"));

        for (String[] row : harness.recorded) {
            assertEquals("device-1", row[1]);
            assertEquals("palo_alto", row[2]);
            for (String raw : RAW) assertFalse(row[4].contains(raw), raw + " leaked into " + row[4]);
            assertAllValuesSafe(JSON.readValue(row[4], Map.class));
        }
        // The exact approved request shapes, the key in a header only, and a bounded poll pause before each get.
        var logCalls = harness.sent.stream().filter(s -> "log".equals(s.type())).toList();
        assertEquals(Map.of("log-type", "system", "nlogs", "100"), logCalls.get(0).formParams());
        assertEquals(Map.of("action", "get", "job-id", "41"), logCalls.get(1).formParams());
        assertEquals("synthetic-key", logCalls.get(1).headers().get("X-PAN-KEY"));
        assertEquals(Map.of("log-type", "config", "nlogs", "100"), logCalls.get(3).formParams());
        assertEquals(4, harness.sleeps.get());
    }

    @Test
    void anEmptyLogIsMeasuredAsZeroWithoutInventedSpan() throws Exception {
        var harness = new Harness("gateway", spec -> enqueue(spec)
                ? answer("<response status=\"success\"><result><job>41</job></result></response>")
                : answer(fin("", 0)));
        harness.run();
        assertEquals("COMPLETED", harness.finalState);
        var system = harness.summary("pan_system");
        assertEquals("MEASURED", system.get("status"));
        assertEquals(0, system.get("entries"));
        assertNull(system.get("spanSeconds"));
        assertNull(system.get("entriesPerDayEstimate"));
        assertNull(system.get("receiveTimeFormat"));
        assertEquals(List.of(), system.get("fieldNames"));
    }

    @Test
    void aJobThatNeverFinishesIsUnknownAfterTheBoundedWait() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        var harness = new Harness("gateway", spec -> {
            if (enqueue(spec)) return answer("<response status=\"success\"><result><job>41</job></result></response>");
            gets.incrementAndGet();
            return answer(ACTIVE);
        });
        harness.run();
        assertEquals("FAILED", harness.finalState);
        assertEquals("LOG_JOB_NOT_FINISHED", harness.finalReason);
        assertEquals(2 * PanSyslogMeasureJobExecutor.MAX_POLLS, gets.get(), "each log job is polled exactly the bound");
        for (String source : List.of("pan_system", "pan_config")) {
            assertEquals(Map.of("status", "UNKNOWN", "reason", "LOG_JOB_NOT_FINISHED"), harness.summary(source));
        }
    }

    @Test
    void anApiErrorIsUnknownAndNeverANumber() throws Exception {
        var harness = new Harness("gateway", spec -> enqueue(spec) && "system".equals(spec.formParams().get("log-type"))
                ? answer("<response status=\"error\" code=\"17\"><msg><line>Invalid query</line></msg></response>")
                : enqueue(spec) ? new XmlApiResult.Failed("synthetic connection reset")
                : answer(fin("", 0)));
        harness.run();
        assertEquals("FAILED", harness.finalState);
        assertEquals("API_ERROR", harness.finalReason);
        assertEquals(Map.of("status", "UNKNOWN", "reason", "API_ERROR"), harness.summary("pan_system"));
        assertEquals(Map.of("status", "UNKNOWN", "reason", "TRANSPORT_FAILED"), harness.summary("pan_config"));
    }

    @Test
    void anUnreadableAnswerOrAnHttpFailureIsUnknown() throws Exception {
        AtomicInteger gets = new AtomicInteger();
        var harness = new Harness("gateway", spec -> {
            if (enqueue(spec)) return answer("<response status=\"success\"><result><job>41</job></result></response>");
            return gets.incrementAndGet() == 1
                    ? answer("<response status=\"success\"><result><job><status>FIN</status></job></result></response>")
                    : new XmlApiResult.Completed(503, "");
        });
        harness.run();
        assertEquals(Map.of("status", "UNKNOWN", "reason", "LOG_RESULT_UNREADABLE"), harness.summary("pan_system"));
        assertEquals(Map.of("status", "UNKNOWN", "reason", "API_ERROR"), harness.summary("pan_config"));
    }

    @Test
    void panoramaIsNeverASourceAndNothingIsSent() {
        var harness = new Harness("management_server", spec -> { throw new AssertionError("no device contact"); });
        harness.run();
        assertEquals("REJECTED", harness.finalState);
        assertEquals("SYSLOG_MEASURE_UNAVAILABLE", harness.finalReason);
        assertTrue(harness.sent.isEmpty());
        assertTrue(harness.recorded.isEmpty());
    }

    @Test
    void theThreeRequestShapesResolveSignedOffReadGatesFromTheFixture() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        for (String command : PanSyslogMeasure.COMMANDS) assertTrue(PanSyslogMeasure.gate(command, gates).isPresent(), command);
        assertTrue(PanSyslogMeasure.gate("type=log&log-type=traffic&nlogs=100", gates).isEmpty());
        assertTrue(PanSyslogMeasure.capability(gates).executionEligible());
    }

    /** Every stored string is a field name, an enum-like token, a status/reason code or the time format. */
    @SuppressWarnings("unchecked")
    private static void assertAllValuesSafe(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                assertTrue(String.valueOf(k).matches("[A-Za-z0-9_-]{1,64}"), "key " + k);
                assertAllValuesSafe(v);
            });
        } else if (value instanceof List<?> list) {
            list.forEach(PanSyslogMeasureJobExecutorTest::assertAllValuesSafe);
        } else if (value instanceof String text) {
            assertTrue(text.matches("[A-Za-z0-9_-]{1,64}") || text.equals("yyyy/MM/dd HH:mm:ss"), "value " + text);
        } else {
            assertTrue(value == null || value instanceof Number || value instanceof Boolean, String.valueOf(value));
        }
    }
}
