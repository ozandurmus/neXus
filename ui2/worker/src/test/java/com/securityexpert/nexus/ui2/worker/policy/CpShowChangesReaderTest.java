package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRunState;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository.Evidence;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;

/**
 * C-1/C-2 against synthetic Check Point answers: off by default, trial-domain only, changed-marker only, window-gated,
 * bounded, fail-closed, and no object or user name reaches a stored row. Scripted exec only: no socket or device contact.
 */
class CpShowChangesReaderTest {
    private static final String CONTAINER = "domain-ref-1", DOMAIN = "DOM-TANGO-01", TASK = "2f4f6a3e-0000-0000-0000-000000000001";
    private static final String PREVIOUS = "{\"uid\":\"s0\",\"publish_time\":\"2026-10-17T21:00:00+0300\",\"posix\":1}";
    private static final String CURRENT = "{\"uid\":\"s9\",\"publish_time\":\"2026-10-18T06:00:00+0300\",\"posix\":2}";
    private static final Instant OPEN = Instant.parse("2026-10-18T03:05:00Z");
    private static final Instant CLOSED = Instant.parse("2026-10-18T06:30:00Z");

    private final GateRegistryPort signed = key -> GateRegistryFixtureLoader.loadFromStream(getClass()
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml")).stream().filter(row -> row.key().equals(key)).toList();
    private final DiscoveryRun run = new DiscoveryRun("run-1", "check_point", "192.0.2.10", "synthetic-ref", "synthetic-actor",
        DiscoveryRunState.FINISHED, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    private final PolicyCollectionRepository.Request request = new PolicyCollectionRepository.Request("mds-1", "", true,
        PolicyCollectionRepository.Mode.CHANGED_ONLY, "job-1", 1);
    private final TransportSession session = () -> "synthetic-session";
    private final List<String> commands = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private DeviceTransport transport;
    private PolicyChangeEvidenceRepository evidence;
    private PolicyCollectionRepository policies;
    private Function<String, String> answers;

    @BeforeEach void setUp() {
        transport = mock(DeviceTransport.class);
        evidence = mock(PolicyChangeEvidenceRepository.class);
        policies = mock(PolicyCollectionRepository.class);
        when(policies.beginDomain(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(transport.connect(any(), any(), any())).thenReturn(new ConnectResult.Authenticated(session));
        when(transport.execInteractive(eq(session), any(), any())).thenAnswer(call -> {
            String command = ((ExecSpec) call.getArgument(1)).command();
            commands.add(command);
            return new ExecResult.Completed(answers.apply(command), 0);
        });
    }

    private CpShowChangesReader reader(PolicyCompletenessSettings settings, GateRegistryPort gates, Instant now) {
        var collector = new CheckPointPolicyCollector(transport, gates, policies, Duration.ofHours(1), 1);
        return new CpShowChangesReader(collector, gates, evidence, policies, settings,
            new JobWindowPolicy(Clock.fixed(now, java.time.ZoneOffset.UTC), 60), null, pauses::add);
    }

    private static PolicyCompletenessSettings trial() {
        return new PolicyCompletenessSettings(Optional.empty(), Optional.of(CONTAINER), Optional.empty());
    }

    private CpShowChangesReader.Result read(CpShowChangesReader reader, String previous, String current) {
        return reader.read(run, request, CONTAINER, DOMAIN, previous, current, OPEN, () -> true);
    }

    private static String task(String status, String details) {
        return "{\"tasks\":[{\"task-id\":\"" + TASK + "\",\"status\":\"" + status + "\",\"task-details\":[" + details + "]}]}";
    }

    private static String change(String session, String user, int added, int modified, int deleted) {
        StringBuilder ops = new StringBuilder("\"added-objects\":[");
        for (int i = 0; i < added; i++) ops.append(i == 0 ? "" : ",").append("{\"uid\":\"aaaaaaaa-0000-0000-0000-00000000000").append(i)
            .append("\",\"name\":\"obj-alpha\",\"type\":\"host\"}");
        ops.append("],\"modified-objects\":[");
        for (int i = 0; i < modified; i++) ops.append(i == 0 ? "" : ",").append("{\"old-object\":{\"uid\":\"bbbbbbbb-0000-0000-0000-00000000000").append(i)
            .append("\",\"name\":\"obj-old\",\"type\":\"network\"},\"new-object\":{\"uid\":\"bbbbbbbb-0000-0000-0000-00000000000").append(i)
            .append("\",\"name\":\"obj-new\",\"type\":\"network\"}}");
        ops.append("],\"deleted-objects\":[");
        for (int i = 0; i < deleted; i++) ops.append(i == 0 ? "" : ",").append("{\"uid\":\"cccccccc-0000-0000-0000-00000000000").append(i)
            .append("\",\"name\":\"obj-gone\",\"type\":\"access-rule\"}");
        ops.append("]");
        return "{\"session\":{\"session-uid\":\"" + session + "\",\"user-name\":\"" + user + "\",\"publish-time\":{\"posix\":1792292400000,"
            + "\"iso-8601\":\"2026-10-18T06:00+0300\"}},\"operations\":{" + ops + "}}";
    }

    @Test void aDomainTheSettingDoesNotNameIsOffWithoutAnyCall() {
        for (var settings : List.of(PolicyCompletenessSettings.OFF,
                new PolicyCompletenessSettings(Optional.empty(), Optional.of("another-domain"), Optional.empty()))) {
            assertEquals("OFF", read(reader(settings, signed, OPEN), PREVIOUS, CURRENT).outcome());
        }
        verifyNoInteractions(transport, evidence, policies);
    }

    @Test void aJobThatStartedOutsideTheWindowReadsNothing() {
        var result = reader(trial(), signed, CLOSED).read(run, request, CONTAINER, DOMAIN, PREVIOUS, CURRENT, CLOSED, () -> true);
        assertEquals("OUTSIDE_JOB_WINDOW", result.reason());
        verifyNoInteractions(transport);
    }

    @Test void aMissingGateReadsNothing() {
        var result = read(reader(trial(), key -> List.of(), OPEN), PREVIOUS, CURRENT);
        assertEquals("POLICY_GATE_UNAVAILABLE", result.reason());
        verifyNoInteractions(transport);
    }

    @Test void aWindowThatDoesNotMoveForwardOrUnreadableMarkersReadNothing() {
        assertEquals("WINDOW_NOT_FORWARD", read(reader(trial(), signed, OPEN), CURRENT, PREVIOUS).reason());
        assertEquals("WINDOW_NOT_FORWARD", read(reader(trial(), signed, OPEN), CURRENT, CURRENT).reason());
        assertEquals("MARKER_UNREADABLE", read(reader(trial(), signed, OPEN), "{}", CURRENT).reason());
        assertEquals("MARKER_UNREADABLE", read(reader(trial(), signed, OPEN), PREVIOUS, "not json").reason());
        verifyNoInteractions(transport);
    }

    @Test void theThrottleKeepsAnAutomaticRunOffTheMdsInsideSixHours() {
        when(policies.beginDomain(anyString(), anyString(), eq(true))).thenReturn(false);
        assertEquals("THROTTLED", read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT).reason());
        verifyNoInteractions(transport);
    }

    @Test void aChangedDomainIsReadWithTheApprovedCommandsAndOnlyCountsIdsAndTimesAreStored() {
        int[] polls = {0};
        answers = command -> command.contains("show-changes") ? "{\"task-id\":\"" + TASK + "\"}"
            : ++polls[0] == 1 ? task("in progress", "")
            : task("succeeded", "{\"changes\":[" + change("sess-1", "synthetic-admin", 2, 1, 1) + "]}");
        var result = read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT);

        assertEquals("COLLECTED", result.outcome());
        assertEquals(1, result.sessions());
        assertEquals(2, result.added()); assertEquals(1, result.modified()); assertEquals(1, result.deleted());
        assertEquals(2, result.polls());
        assertEquals(List.of(MgmtCliCommands.showChanges(DOMAIN, "2026-10-17T21:00:00+0300", "2026-10-18T06:00:00+0300", 0),
            MgmtCliCommands.showTask(DOMAIN, TASK), MgmtCliCommands.showTask(DOMAIN, TASK)), commands);
        assertEquals("mgmt_cli -r true -d 'DOM-TANGO-01' -f json show-changes from-date '2026-10-17T21:00:00+0300' to-date '2026-10-18T06:00:00+0300' limit 10 offset '0'",
            commands.get(0));
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5)), pauses);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Evidence>> rows = ArgumentCaptor.forClass(List.class);
        verify(evidence).save(eq("mds-1"), eq("job-1"), rows.capture(), eq(Optional.empty()));
        Evidence row = rows.getValue().get(0);
        assertEquals("sess-1", row.entryRef());
        assertEquals(CONTAINER, row.containerRef());
        assertEquals("2026-10-18 03:00:00", row.changeTime());
        assertEquals("UTC", row.timeBasis());
        assertEquals("masked:15", row.adminRef());
        assertEquals(2, row.addedCount()); assertEquals(1, row.modifiedCount()); assertEquals(1, row.deletedCount());
        assertEquals("[{\"uid\":\"aaaaaaaa-0000-0000-0000-000000000000\",\"type\":\"host\",\"op\":\"ADDED\"},"
            + "{\"uid\":\"aaaaaaaa-0000-0000-0000-000000000001\",\"type\":\"host\",\"op\":\"ADDED\"},"
            + "{\"uid\":\"bbbbbbbb-0000-0000-0000-000000000000\",\"type\":\"network\",\"op\":\"MODIFIED\"},"
            + "{\"uid\":\"cccccccc-0000-0000-0000-000000000000\",\"type\":\"access-rule\",\"op\":\"DELETED\"}]", row.objectChangesJson());
        for (String raw : List.of("synthetic-admin", "obj-alpha", "obj-old", "obj-new", "obj-gone"))
            assertFalse(row.toString().contains(raw), raw);
        verify(transport).disconnect(session);
    }

    @Test void aFullPageAsksForTheNextOffsetAndAShortPageStops() {
        answers = command -> {
            if (command.contains("show-changes")) return "{\"task-id\":\"" + TASK + "\"}";
            boolean second = commands.stream().filter(c -> c.contains("show-changes")).count() >= 2;
            StringBuilder changes = new StringBuilder();
            for (int i = 0; i < (second ? 3 : 10); i++) changes.append(i == 0 ? "" : ",").append(change("s-" + (second ? "b" : "a") + i, "u", 1, 0, 0));
            return task("succeeded", "{\"changes\":[" + changes + "]}");
        };
        var result = read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT);
        assertEquals(13, result.sessions());
        var changes = commands.stream().filter(c -> c.contains("show-changes")).toList();
        assertEquals(2, changes.size());
        assertTrue(changes.get(0).endsWith("offset '0'"));
        assertTrue(changes.get(1).endsWith("offset '10'"));
    }

    @Test void neverMoreThanTwentyOffsetsPerRun() {
        answers = command -> {
            if (command.contains("show-changes")) return "{\"task-id\":\"" + TASK + "\"}";
            int page = (int) commands.stream().filter(c -> c.contains("show-changes")).count();
            StringBuilder changes = new StringBuilder();
            for (int i = 0; i < 10; i++) changes.append(i == 0 ? "" : ",").append(change("s-" + page + "-" + i, "u", 1, 0, 0));
            return task("succeeded", "{\"changes\":[" + changes + "]}");
        };
        read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT);
        assertEquals(20, commands.stream().filter(c -> c.contains("show-changes")).count());
    }

    @Test void aTaskThatNeverFinishesIsUnknownAfterExactlyTwentyFourPollsAndStoresNothing() {
        answers = command -> command.contains("show-changes") ? "{\"task-id\":\"" + TASK + "\"}" : task("in progress", "");
        var result = read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT);
        assertEquals("TASK_NOT_FINISHED", result.reason());
        assertEquals(24, result.polls());
        assertEquals(24, pauses.size());
        verify(evidence, never()).save(any(), any(), any(), any());
        verify(transport).disconnect(session);
    }

    @Test void aFailedTaskAnApiErrorAndAMissingTaskIdAreUnknownAndStoreNothing() {
        answers = command -> command.contains("show-changes") ? "{\"task-id\":\"" + TASK + "\"}" : task("failed", "");
        assertEquals("TASK_FAILED", read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT).reason());
        answers = command -> "{\"code\":\"generic_err_invalid_parameter\",\"message\":\"Synthetic private message\"}";
        var api = read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT);
        assertEquals("API_ERROR_GENERIC_ERR_INVALID_PARAMETER", api.reason());
        assertFalse(api.note(0).contains("Synthetic private"));
        answers = command -> "{\"unexpected\":true}";
        assertEquals("TASK_ID_MISSING", read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT).reason());
        answers = command -> "no json at all";
        assertEquals("RESPONSE_NOT_JSON", read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT).reason());
        verify(evidence, never()).save(any(), any(), any(), any());
        verify(transport, times(4)).disconnect(session);
    }

    @Test void aChangeWithoutASessionUidIsCountedButNeverStored() {
        answers = command -> command.contains("show-changes") ? "{\"task-id\":\"" + TASK + "\"}"
            : task("succeeded", "{\"changes\":[{\"operations\":{\"added-objects\":[{\"uid\":\"aaaaaaaa-0000\",\"type\":\"host\"}]}}]}");
        var result = read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT);
        assertEquals("COLLECTED", result.outcome());
        assertEquals(1, result.unattributed());
        assertEquals(0, result.sessions());
        verify(evidence, never()).save(any(), any(), any(), any());
    }

    @Test void noChangesInTheWindowIsReportedAsSuch() {
        answers = command -> command.contains("show-changes") ? "{\"task-id\":\"" + TASK + "\"}" : task("succeeded", "");
        assertEquals("NO_CHANGES", read(reader(trial(), signed, OPEN), PREVIOUS, CURRENT).outcome());
    }

    @Test void commandBuildersRefuseAnythingOutsideTheApprovedShapes() {
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showChanges(DOMAIN, "yesterday", "2026-10-18T06:00:00+0300", 0));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showChanges(DOMAIN, "2026-10-17T21:00:00+0300", "2026-10-18T06:00:00+0300", 7));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showChanges(DOMAIN, "2026-10-17T21:00:00+0300", "2026-10-18T06:00:00+0300", 200));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showTask(DOMAIN, "x; rm"));
        assertThrows(IllegalArgumentException.class, () -> MgmtCliCommands.showTask(DOMAIN, "short"));
    }
}
