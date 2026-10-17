package com.securityexpert.nexus.ui2.worker.diagnostic;

import static com.securityexpert.nexus.ui2.worker.diagnostic.PanSystemLogCollectJobExecutorTest.ACTIVE;
import static com.securityexpert.nexus.ui2.worker.diagnostic.PanSystemLogCollectJobExecutorTest.ENQUEUED;
import static com.securityexpert.nexus.ui2.worker.diagnostic.PanSystemLogCollectJobExecutorTest.fin;
import static com.securityexpert.nexus.ui2.worker.diagnostic.PanSystemLogCollectJobExecutorTest.ts;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository.Cursor;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository.Evidence;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.policy.PolicyCompletenessSettings;

/**
 * P-2/P-3/P-4 against synthetic Panorama answers: off by default, trial-source only, window-gated, bounded, fail-closed,
 * and no secret, name or change detail reaches a stored row. Scripted XML API only: no socket, host or device contact.
 */
class PanoramaConfigLogReaderTest {
    private static final String SOURCE = "manager-1";
    private static final Instant OPEN = Instant.parse("2026-10-18T03:05:00Z");   // 06:05 Europe/Istanbul
    private static final Instant CLOSED = Instant.parse("2026-10-18T06:30:00Z"); // 09:30 Europe/Istanbul
    private static final String SECRET_TAG = "pass" + "word";
    private static final String PATH_PREFIX = "/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='Synthetic DG']";

    private final GateRegistryPort signed = key -> GateRegistryFixtureLoader.loadFromStream(getClass()
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(row -> row.key().equals(key)).toList();
    private final ApiTarget target = new ApiTarget(SOURCE, "192.0.2.10");
    private final ArrayDeque<String> answers = new ArrayDeque<>();
    private final List<Map<String, String>> sent = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private DeviceTransport transport;
    private PolicyChangeEvidenceRepository evidence;
    private PolicyCollectionRepository policies;

    @BeforeEach void setUp() {
        transport = mock(DeviceTransport.class);
        when(transport.xmlApiCall(any(), any(), any())).thenAnswer(call -> {
            XmlApiSpec spec = call.getArgument(1);
            sent.add(spec.formParams());
            assertEquals("X-PAN-KEY", spec.headers().keySet().iterator().next());
            assertEquals("no_target", spec.targetScope());
            return new XmlApiResult.Completed(200, answers.removeFirst());
        });
        evidence = mock(PolicyChangeEvidenceRepository.class);
        policies = mock(PolicyCollectionRepository.class);
        when(policies.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
    }

    private PanoramaConfigLogReader reader(PolicyCompletenessSettings settings, GateRegistryPort gates, Instant now, HostnameFingerprint fingerprint) {
        return new PanoramaConfigLogReader(transport, gates, evidence, policies, settings, new JobWindowPolicy(Clock.fixed(now, java.time.ZoneOffset.UTC), 60),
                fingerprint, pauses::add);
    }

    private static PolicyCompletenessSettings trial() {
        return new PolicyCompletenessSettings(Optional.of(SOURCE), Optional.empty(), Optional.empty());
    }

    private static String entry(String time, String seqno, String admin, String path, String cmd) {
        return "<entry logid=\"7000000000000000002\"><receive_time>" + time + "</receive_time><seqno>" + seqno + "</seqno>"
                + "<admin>" + admin + "</admin><cmd>" + cmd + "</cmd><result>Succeeded</result><path>" + path + "</path>"
                + "<before-change-detail>&lt;" + SECRET_TAG + "&gt;synthetic-value&lt;/" + SECRET_TAG + "&gt;</before-change-detail></entry>";
    }

    private PanoramaConfigLogReader.Result run(PanoramaConfigLogReader reader) {
        return reader.read(SOURCE, "job-1", true, OPEN, target, "synthetic-key".toCharArray(), () -> true);
    }

    @Test void aSourceThatTheSettingDoesNotNameIsOffWithoutAnyCall() {
        for (var settings : List.of(PolicyCompletenessSettings.OFF,
                new PolicyCompletenessSettings(Optional.of("another-manager"), Optional.empty(), Optional.empty()))) {
            var result = run(reader(settings, signed, OPEN, null));
            assertEquals("OFF", result.outcome());
        }
        verifyNoInteractions(transport, evidence, policies);
    }

    @Test void aJobThatStartedOutsideTheWindowReadsNothing() {
        var result = reader(trial(), signed, CLOSED, null).read(SOURCE, "job-1", true, CLOSED, target, new char[0], () -> true);
        assertEquals("OUTSIDE_JOB_WINDOW", result.reason());
        verifyNoInteractions(transport);
        verify(evidence, never()).save(any(), any(), any(), any());
    }

    @Test void aMissingOrUnsignedGateReadsNothing() {
        var result = run(reader(trial(), key -> List.of(), OPEN, null));
        assertEquals("UNKNOWN", result.outcome());
        assertEquals("POLICY_GATE_UNAVAILABLE", result.reason());
        verifyNoInteractions(transport);
    }

    @Test void theThrottleKeepsAnAutomaticRunOffTheDeviceInsideSixHours() {
        when(policies.beginDomain(anyString(), anyString(), eq(true))).thenReturn(false);
        assertEquals("THROTTLED", run(reader(trial(), signed, OPEN, null)).reason());
        verifyNoInteractions(transport);
    }

    @Test void theFirstRunSeedsThenStoresOnlyRedactedEvidenceAndTheFirstCursor() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.empty());
        when(evidence.save(any(), any(), any(), any())).thenReturn(2);
        answers.add(ENQUEUED);
        answers.add(fin(List.of(
                entry(ts(2), "11", "synthetic-admin", PATH_PREFIX + "/pre-rulebase/security/rules/entry[@name='rule-alpha']", "edit"),
                entry(ts(1), "10", "synthetic-admin", "/config/shared/address/entry[@name='obj-1']", "set"))));
        var result = run(reader(trial(), signed, OPEN, null));

        assertEquals("COLLECTED", result.outcome());
        assertEquals(2, result.entries());
        // P-3, then P-4: the seed has no cursor query; the poll carries the digits-only job id.
        assertEquals(Map.of("log-type", "config", "nlogs", "100"), sent.get(0));
        assertEquals(Map.of("action", "get", "job-id", "41"), sent.get(1));
        assertEquals(List.of(Duration.ofSeconds(2)), pauses);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Evidence>> rows = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Optional<Cursor>> cursor = ArgumentCaptor.forClass(Optional.class);
        verify(evidence).save(eq(SOURCE), eq("job-1"), rows.capture(), cursor.capture());
        // Oldest first; the cursor is the newest second plus the seqnos stored at it.
        assertEquals(List.of("10", "11"), rows.getValue().stream().map(Evidence::entryRef).toList());
        assertEquals(new Cursor(ts(2), List.of("11")), cursor.getValue().orElseThrow());
        Evidence named = rows.getValue().get(1), other = rows.getValue().get(0);
        assertEquals(PolicySnapshot.ref(SOURCE, "device-group", "Synthetic DG"), named.containerRef());
        assertNull(other.containerRef(), "a path that names no device group stays UNKNOWN, not guessed");
        assertEquals("masked:15", named.adminRef());
        assertEquals("edit", named.commandClass());
        assertEquals("Succeeded", named.resultClass());
        assertEquals("DEVICE_LOCAL", named.timeBasis());
        assertEquals(ts(2).replace('/', '-'), named.changeTime());
        // Nothing the log said about people, names or values reaches a stored row.
        String stored = rows.getValue().toString();
        for (String raw : List.of("synthetic-admin", "synthetic-value", "rule-alpha", "Synthetic DG", "obj-1", SECRET_TAG))
            assertFalse(stored.contains(raw), raw);
        assertTrue(java.util.Arrays.stream(Evidence.class.getRecordComponents()).noneMatch(c -> c.getName().toLowerCase().contains("detail")
                || c.getName().toLowerCase().contains("raw") || c.getName().toLowerCase().contains("name")));
    }

    @Test void withTheMountedKeyTheAdminAndPathAreKeyedDigestsNotText() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.empty());
        answers.add(ENQUEUED);
        answers.add(fin(List.of(entry(ts(1), "10", "synthetic-admin", PATH_PREFIX, "edit"))));
        var fingerprint = HostnameFingerprint.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        run(reader(trial(), signed, OPEN, fingerprint));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Evidence>> rows = ArgumentCaptor.forClass(List.class);
        verify(evidence).save(any(), any(), rows.capture(), any());
        Evidence row = rows.getValue().get(0);
        assertTrue(row.adminRef().matches("[0-9a-f]{64}"));
        assertTrue(row.pathHmac().matches("[0-9a-f]{64}"));
        assertNotEquals(row.adminRef(), row.pathHmac());
        assertFalse(row.toString().contains("synthetic-admin"));
    }

    @Test void anIncrementalPageDropsTheBoundarySecondsKnownEntriesAndAdvancesTheCursor() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.of(new Cursor(ts(5), List.of("5"))));
        answers.add(ENQUEUED);
        answers.add(fin(List.of(entry(ts(5), "5", "a", PATH_PREFIX, "edit"), entry(ts(5), "6", "a", PATH_PREFIX, "edit"),
                entry(ts(7), "9", "a", PATH_PREFIX, "set"))));
        var result = run(reader(trial(), signed, OPEN, null));
        assertEquals(2, result.entries());
        assertEquals("forward", sent.get(0).get("dir"));
        assertEquals("(receive_time geq '" + ts(5) + "')", sent.get(0).get("query"));
        assertEquals("1000", sent.get(0).get("nlogs"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Evidence>> rows = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Optional<Cursor>> cursor = ArgumentCaptor.forClass(Optional.class);
        verify(evidence).save(any(), any(), rows.capture(), cursor.capture());
        assertEquals(List.of("6", "9"), rows.getValue().stream().map(Evidence::entryRef).toList());
        assertEquals(new Cursor(ts(7), List.of("9")), cursor.getValue().orElseThrow());
    }

    @Test void anEmptyPageIsNoNewDataAndLeavesTheCursor() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.of(new Cursor(ts(5), List.of("5"))));
        answers.add(ENQUEUED);
        answers.add(fin(List.of()));
        var result = run(reader(trial(), signed, OPEN, null));
        assertEquals("NO_NEW_DATA", result.outcome());
        @SuppressWarnings("unchecked") ArgumentCaptor<Optional<Cursor>> cursor = ArgumentCaptor.forClass(Optional.class);
        verify(evidence).save(any(), any(), eq(List.of()), cursor.capture());
        assertTrue(cursor.getValue().isEmpty());
    }

    @Test void aQueryTheDeviceDidNotHonourIsUnknownAndStoresNothing() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.of(new Cursor(ts(5), List.of("5"))));
        answers.add(ENQUEUED);
        answers.add(fin(List.of(entry(ts(1), "2", "a", PATH_PREFIX, "edit"))));
        var result = run(reader(trial(), signed, OPEN, null));
        assertEquals("QUERY_NOT_HONORED", result.reason());
        verify(evidence, never()).save(any(), any(), any(), any());
    }

    @Test void anUnfinishedLogJobIsUnknownAfterExactlyThirtyPollsAndStoresNothing() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.empty());
        answers.add(ENQUEUED);
        for (int i = 0; i < 40; i++) answers.add(ACTIVE);
        var result = run(reader(trial(), signed, OPEN, null));
        assertEquals("LOG_JOB_NOT_FINISHED", result.reason());
        assertEquals(30, pauses.size());
        assertEquals(31, sent.size(), "one enqueue and 30 polls, no more");
        verify(evidence, never()).save(any(), any(), any(), any());
    }

    @Test void anApiErrorAnUnreadableJobIdAndAnUnreadablePageAreUnknown() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.empty());
        answers.add("<response status=\"error\" code=\"7\"><msg>x</msg></response>");
        assertEquals("API_ERROR", run(reader(trial(), signed, OPEN, null)).reason());
        answers.clear(); sent.clear();
        answers.add("<response status=\"success\"><result><job>not-digits</job></result></response>");
        assertEquals("LOG_JOB_NOT_ENQUEUED", run(reader(trial(), signed, OPEN, null)).reason());
        answers.clear(); sent.clear();
        answers.add(ENQUEUED);
        answers.add(fin(List.of("<entry><receive_time>yesterday</receive_time><seqno>7</seqno></entry>")));
        assertEquals("LOG_ENTRY_TIME_UNREADABLE", run(reader(trial(), signed, OPEN, null)).reason());
        verify(evidence, never()).save(any(), any(), any(), any());
    }

    @Test void aFullPageRunStopsAtFourPagesAndReportsBacklog() {
        when(evidence.cursor(SOURCE)).thenReturn(Optional.of(new Cursor(ts(0), List.of())));
        AtomicInteger page = new AtomicInteger();
        doAnswer(call -> {
            XmlApiSpec spec = call.getArgument(1);
            sent.add(spec.formParams());
            if (!"get".equals(spec.formParams().get("action"))) return new XmlApiResult.Completed(200, ENQUEUED);
            int p = page.getAndIncrement();
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < PanoramaConfigLogReader.PAGE_SIZE; i++)
                entries.add(entry(ts(1 + p * PanoramaConfigLogReader.PAGE_SIZE + i), String.valueOf(1 + p * PanoramaConfigLogReader.PAGE_SIZE + i), "a", PATH_PREFIX, "edit"));
            return new XmlApiResult.Completed(200, fin(entries));
        }).when(transport).xmlApiCall(any(), any(), any());
        var result = run(reader(trial(), signed, OPEN, null));
        assertEquals("BACKLOG", result.outcome());
        assertEquals(4, result.pages());
        assertEquals(4, sent.stream().filter(m -> m.containsKey("query")).count(), "at most four pages per Panorama per run");
    }

    @Test void theContainerRefComesOnlyFromADeviceGroupPath() {
        assertEquals(PolicySnapshot.ref(SOURCE, "device-group", "Synthetic DG"), PanoramaConfigLogReader.containerRef(SOURCE, PATH_PREFIX + "/x"));
        assertEquals(PolicySnapshot.ref(SOURCE, "device-group", "Name 'quoted'"),
                PanoramaConfigLogReader.containerRef(SOURCE, "/device-group/entry[@name=\"Name 'quoted'\"]"));
        assertNull(PanoramaConfigLogReader.containerRef(SOURCE, "/config/shared/address"));
        assertNull(PanoramaConfigLogReader.containerRef(SOURCE, ""));
        assertNull(PanoramaConfigLogReader.containerRef(SOURCE, "/device-group/entry[@name='<redacted>']"));
    }
}
