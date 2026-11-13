package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.failover.AsaFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** Scripted SSH only: no socket, host or device contact. */
class AsaFailoverJobExecutorTest {
    private static final String CLUSTER = "CLS-TANGO-01", ACTIVE = "FW-TANGO-04", STANDBY = "FW-JULIET-06";
    private static final List<String> FAILOVER_GATES = List.of("asa_failover_show_failover", "asa_failover_show_failover_state",
        "asa_failover_show_failover_history", "asa_failover_show_conn_count");

    private static final class Script {
        final List<String> commands = new ArrayList<>();
        final List<String> sessions = new ArrayList<>();
        int connects;
        String mode = "Security context mode: single\n";
        /** Per member and command, how many answers were given (sample 1 vs sample 2 of show failover). */
        final java.util.Map<String, Integer> asked = new java.util.HashMap<>();

        DeviceTransport transport() {
            return (DeviceTransport) Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[] {DeviceTransport.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "connect" -> {
                            connects++;
                            String endpoint = ((ConnectionTarget) args[0]).endpointId();
                            sessions.add(endpoint);
                            return new ConnectResult.Authenticated(new TransportSession() {
                                @Override public String sessionId() { return endpoint; }
                            });
                        }
                        case "disconnect" -> { return null; }
                        case "execInteractive" -> {
                            String member = ((TransportSession) args[0]).sessionId();
                            String command = ((ExecSpec) args[1]).command();
                            commands.add(member + ":" + command);
                            return new ExecResult.Completed(answer(member, command), 0);
                        }
                        default -> throw new AssertionError(method.getName());
                    }
                });
        }
        private String answer(String member, String command) {
            boolean active = ACTIVE.equals(member);
            int n = asked.merge(member + "|" + command, 1, Integer::sum);
            return switch (command) {
                case "terminal pager 0" -> "";
                case "show curpriv" -> "Username        : synthetic\nCurrent privilege level : 15\n";
                case "show mode" -> mode;
                case "show failover" -> (active ? AsaFixtures.active() : AsaFixtures.standby()).text();
                case "show failover state" -> AsaFixtures.stateOk(!active);
                case "show failover history" -> AsaFixtures.history(3, "10:11:12 +03 Nov 10 2026");
                case "show conn count" -> AsaFixtures.conn(active ? 4778 : 3801, 1_085_767);
                default -> throw new AssertionError("unexpected command: " + command);
            };
        }
        long count(String command) { return commands.stream().filter(c -> c.endsWith(":" + command)).count(); }
    }

    private static DeviceSummaryRecord summary(String id, String vendor) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(CLUSTER));
    }
    private static DeviceRepository devices() {
        return (DeviceRepository) Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[] {DeviceRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                case "findMembersByClusterRef" -> List.of(summary(ACTIVE, "cisco_asa"), summary(STANDBY, "cisco_asa"));
                case "find" -> Optional.of(new DeviceRecord((String) args[0], "gateway", "cisco_asa", "manual",
                    Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord((String) args[0], (String) args[0],
                    "ssh_exec", ACTIVE.equals(args[0]) ? "192.0.2.11" : "192.0.2.12", Instant.EPOCH));
                default -> throw new AssertionError(method.getName());
            });
    }
    private record Leases(JobLeaseRepository repository, List<JobState> to) {}
    private static Leases leases() {
        List<JobState> to = new ArrayList<>();
        var repository = (JobLeaseRepository) Proxy.newProxyInstance(JobLeaseRepository.class.getClassLoader(),
            new Class<?>[] {JobLeaseRepository.class}, (proxy, method, args) -> {
                if ("transitionState".equals(method.getName())) { to.add((JobState) args[3]); return true; }
                throw new AssertionError(method.getName());
            });
        return new Leases(repository, to);
    }
    private static JobStepAttemptRepository attempts() {
        AtomicInteger counter = new AtomicInteger();
        return (JobStepAttemptRepository) Proxy.newProxyInstance(JobStepAttemptRepository.class.getClassLoader(),
            new Class<?>[] {JobStepAttemptRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                case "findByJobAndStep" -> List.of();
                case "insertPreContact" -> "attempt-" + counter.incrementAndGet();
                case "markBoundaryCrossed", "writeOutcome" -> true;
                default -> throw new AssertionError(method.getName());
            });
    }
    private static List<GateRow> fixtureRows() {
        return GateRegistryFixtureLoader.loadFromStream(AsaFailoverJobExecutorTest.class
            .getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
    }
    /** The fixture exactly as committed: the four readiness rows are DRAFTED. */
    private static GateRegistryPort draftedGates() {
        var rows = fixtureRows();
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    /** A test registry with the four readiness rows SIGNED_OFF (the committed rows stay DRAFTED). */
    private static GateRegistryPort signedOffGates() {
        var rows = fixtureRows().stream().map(r -> FAILOVER_GATES.contains(r.gateId())
            ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                r.actionClass(), SignOffState.SIGNED_OFF, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    private record Outcome(String state, String outcome, String failedCheck, String message, List<String> checks, List<String> derived,
            List<JobState> job, Script script, List<Duration> pauses, int ledgered) {}

    private static Outcome run(Script script, GateRegistryPort gates, String kind, AsaFailoverReadinessSwitch readinessSwitch,
            Optional<HaPairIdentity> recorded) {
        JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
        List<String> states = new ArrayList<>(), checks = new ArrayList<>(), derived = new ArrayList<>();
        List<String> last = new ArrayList<>(List.of("", "", ""));
        when(store.runByJob("job-1")).thenReturn(Optional.of(new JooqCpFailoverRepository.Run("run-1", CLUSTER, null, null, "actor-1",
            Instant.now(), "job-1", "PLANNED", "PLANNED", null, null, null, "cisco_asa", kind)));
        doAnswer(inv -> {
            states.add(inv.getArgument(1));
            last.set(0, String.valueOf((Object) inv.getArgument(3))); last.set(1, String.valueOf((Object) inv.getArgument(4))); last.set(2, String.valueOf((Object) inv.getArgument(5)));
            return null;
        }).when(store).state(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class));
        doAnswer(inv -> { checks.add(inv.getArgument(4) + ":" + inv.getArgument(5)); derived.add(inv.getArgument(6)); return null; })
            .when(store).check(anyString(), anyString(), anyString(), nullable(String.class), anyInt(), anyString(), anyString());
        AtomicInteger ledger = new AtomicInteger();
        doAnswer(inv -> { ledger.incrementAndGet(); return null; }).when(store).command(anyString(), anyString());
        HaPairIdentityRepository identities = mock(HaPairIdentityRepository.class);
        when(identities.find(CLUSTER)).thenReturn(recorded);
        var leases = leases();
        List<Duration> pauses = new ArrayList<>();
        new AsaFailoverJobExecutor(store, devices(), leases.repository(), attempts(), script.transport(), gates, identities,
            readinessSwitch, pauses::add, Duration.ZERO, Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC))
            .execute("job-1", 1);
        return new Outcome(states.isEmpty() ? null : states.get(states.size() - 1), last.get(0), last.get(1), last.get(2),
            checks, derived, leases.to(), script, pauses, ledger.get());
    }
    private static final Optional<HaPairIdentity> PAIR = Optional.of(new HaPairIdentity(CLUSTER, "cisco_asa",
        AsaFixtures.SERIAL_A, AsaFixtures.SERIAL_B, HaPairIdentity.State.MATCH));
    private static final AsaFailoverReadinessSwitch ON = new AsaFailoverReadinessSwitch(true);

    @Test void committedGateRowsAreDraftedSoNothingIsSentAndEveryCheckIsUnknown() {
        Script script = new Script();
        Outcome out = run(script, draftedGates(), "READINESS", ON, PAIR);
        assertEquals(0, script.connects, "no session at all");
        assertTrue(script.commands.isEmpty());
        assertEquals("DONE", out.state());
        assertEquals("UNKNOWN", out.outcome());
        assertEquals(18, out.checks().size());
        for (int no = 1; no <= 9; no++) {
            assertTrue(out.checks().contains(no + ":UNKNOWN"), "check " + no);
        }
        assertEquals(18, out.derived().stream().filter(d -> d.contains("\"reason\":\"GATE_NOT_SIGNED_OFF\"")).count());
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), out.job());
        assertEquals(0, out.ledgered());
    }

    @Test void signedOffGatesSendTheFourLiteralsInTheExactOrderAndTheResultIsReady() {
        Script script = new Script();
        Outcome out = run(script, signedOffGates(), "READINESS", ON, PAIR);
        List<String> perMember = List.of("terminal pager 0", "show curpriv", "show mode", "show failover", "show failover",
            "show failover state", "show failover history", "show conn count");
        List<String> expected = new ArrayList<>();
        for (String member : List.of(STANDBY, ACTIVE)) for (String c : perMember) expected.add(member + ":" + c);
        assertEquals(expected, script.commands);
        assertEquals(2, script.connects);
        assertEquals("READY", out.outcome());
        assertEquals("DONE", out.state());
        for (int no = 1; no <= 9; no++) { String pass = no + ":PASS"; assertEquals(2, out.checks().stream().filter(pass::equals).count(), "check " + no); }
        // Two samples at least 5 s apart on each member, by the pause hook.
        assertEquals(2, out.pauses().stream().filter(p -> p.compareTo(Duration.ofSeconds(5)) >= 0).count());
        assertEquals(10, out.ledgered(), "every read is ledgered before it is sent");
        // Nothing but reads: no failover-changing literal exists in what was sent.
        for (String sent : script.commands) {
            String command = sent.substring(sent.indexOf(':') + 1);
            assertFalse(command.contains("failover active") || command.startsWith("no ") || command.startsWith("configure"), command);
        }
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), out.job());
    }

    @Test void theOneTimeMeasurementRowsAreNeverUsedForReadiness() {
        // The V210 rows carry the same four literals (SSH_EXEC, SIGNED_OFF); readiness resolves only its own
        // SSH_INTERACTIVE rows, so signing the measurement rows off or not changes nothing here.
        Outcome out = run(new Script(), draftedGates(), "READINESS", ON, PAIR);
        assertFalse(out.derived().stream().anyMatch(d -> d.contains("GATE_UNAVAILABLE")), "no ambiguity with the measurement rows");
        assertEquals(0, out.ledgered());
    }

    @Test void aFailoverRunKindIsRefusedWithNoContact() {
        Script script = new Script();
        Outcome out = run(script, signedOffGates(), "FAILOVER", ON, PAIR);
        assertEquals(0, script.connects);
        assertTrue(script.commands.isEmpty());
        assertEquals("STOPPED", out.state());
        assertEquals("READINESS_ONLY", out.message());
        assertEquals(List.of(JobState.EXECUTING, JobState.FAILED), out.job());
        assertTrue(out.checks().isEmpty());
    }

    @Test void theWorkerSwitchOffRefusesBeforeAnyContact() {
        Script script = new Script();
        Outcome out = run(script, signedOffGates(), "READINESS", AsaFailoverReadinessSwitch.OFF, PAIR);
        assertEquals(0, script.connects);
        assertEquals("UNKNOWN", out.outcome());
        assertEquals("ASA_READINESS_SWITCH_OFF", out.message());
        assertTrue(out.checks().isEmpty());
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), out.job());
        assertFalse(AsaFailoverReadinessSwitch.fromValue(null).enabled());
        assertFalse(AsaFailoverReadinessSwitch.fromValue("true").enabled());
        assertFalse(AsaFailoverReadinessSwitch.fromValue("off").enabled());
        assertTrue(AsaFailoverReadinessSwitch.fromValue("on").enabled());
    }

    @Test void aMultipleContextAnswerStopsBeforeTheFailoverReads() {
        Script script = new Script();
        script.mode = "Security context mode: multiple\n";
        Outcome out = run(script, signedOffGates(), "READINESS", ON, PAIR);
        assertEquals("UNKNOWN", out.outcome());
        assertEquals("UNSUPPORTED_MODE", out.message());
        assertEquals(0, script.count("show failover"));
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), out.job());
    }

    @Test void noRecordedPairLeavesTheRunUnknownEvenWhenEverythingElsePasses() {
        Outcome out = run(new Script(), signedOffGates(), "READINESS", ON, Optional.empty());
        assertEquals("UNKNOWN", out.outcome());
        assertEquals("Pair identity", out.failedCheck());
        assertEquals("NO_RECORDED_PAIR", out.message());
    }

    @Test void derivedRowsCarryNoDeviceValue() {
        Outcome out = run(new Script(), signedOffGates(), "READINESS", ON, PAIR);
        String all = String.join("\n", out.derived());
        for (String forbidden : List.of("SYNTH000", "9.18", "192.0.2.", "outside", "GigabitEthernet", "FW-TANGO", "FW-JULIET"))
            assertFalse(all.contains(forbidden), forbidden);
    }
}
