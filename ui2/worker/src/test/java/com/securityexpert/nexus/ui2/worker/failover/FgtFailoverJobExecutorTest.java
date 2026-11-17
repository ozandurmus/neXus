package com.securityexpert.nexus.ui2.worker.failover;

import static com.securityexpert.nexus.ui2.worker.failover.FgtFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.failover.FgtFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** Scripted SSH only: no socket, host or device contact. */
class FgtFailoverJobExecutorTest {
    private static final String DEVICE = "DEV-SYNTH-01", CLUSTER = "fgt-single|" + DEVICE;
    private static final Set<String> NEW_GATES = Set.of("fgt_failover_checksum_cluster", "fgt_failover_dump_by_group", "fgt_failover_ha_failover_status");

    private static final String PERFORMANCE = "CPU states: 0% user 0% system 0% nice 100% idle 0% iowait 0% irq 0% softirq\n"
        + "Memory: 4045364k total, 1700916k used (42.0%), 2000000k free (49.4%), 344448k freeable (8.5%)\n"
        + "Average network usage: 12 / 8 kbps in 1 minute, 11 / 7 kbps in 10 minutes, 10 / 6 kbps in 30 minutes\n"
        + "Maximal network usage: 99 / 99 kbps in 1 minute, 99 / 99 kbps in 10 minutes, 99 / 99 kbps in 30 minutes\n"
        + "Average sessions: 1234 sessions in 1 minute, 1200 sessions in 10 minutes, 1100 sessions in 30 minutes\n";
    private static final String ARP = "Address           Age(min)   Hardware Addr      Interface\n"
        + "192.0.2.1         0          00:00:5e:00:53:01  port1\n192.0.2.2         3          00:00:5e:00:53:02  port1\n"
        + "192.0.2.3         10         00:00:5e:00:53:03  port2\n";
    private static String arpWith(int entries) {
        StringBuilder b = new StringBuilder("Address           Age(min)   Hardware Addr      Interface\n");
        for (int i = 1; i <= entries; i++) b.append("192.0.2.").append(i).append("         0          00:00:5e:00:53:").append(String.format("%02x", i)).append("  port1\n");
        return b.toString();
    }
    private static final Set<String> TRAFFIC_GATES = Set.of("fgt_failover_performance_status", "fgt_failover_arp");
    private static Optional<JooqCpFailoverRepository.Check> previousTraffic = Optional.empty();
    @org.junit.jupiter.api.BeforeEach void resetPrevious() { previousTraffic = Optional.empty(); }
    /** The two information reads forced SIGNED_OFF (the committed rows are DRAFTED until the PO approves the literals). */
    private static GateRegistryPort signedOffWithTraffic() {
        var rows = fixtureRows().stream().map(r -> TRAFFIC_GATES.contains(r.gateId())
            ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                r.actionClass(), SignOffState.SIGNED_OFF, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    private static final class Script {
        final List<String> commands = new ArrayList<>();
        int connects;
        boolean multiVdom;
        boolean statusRefusedAtTopLevel;
        String recorded = SERIAL_A;
        boolean inGlobal;
        /** Synthetic answers of the two information reads (check 10): 12 + 8 kbps = 2500 bytes/s, 3 entries. */
        String performance = PERFORMANCE, arp = ARP;

        DeviceTransport transport() {
            return (DeviceTransport) Proxy.newProxyInstance(DeviceTransport.class.getClassLoader(),
                new Class<?>[] {DeviceTransport.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "connect" -> { connects++; return new ConnectResult.Authenticated(new TransportSession() {
                            @Override public String sessionId() { return "s"; } }); }
                        case "disconnect" -> { return null; }
                        case "execInteractive" -> {
                            String command = ((ExecSpec) args[1]).command();
                            commands.add(command);
                            return new ExecResult.Completed(answer(command), 0);
                        }
                        default -> throw new AssertionError(method.getName());
                    }
                });
        }
        /** Multi-VDOM ARP fixtures: VDOM name -> number of entries; a name in {@code unreadable} answers with a CLI error; one in {@code refusedEdit} refuses "edit". */
        java.util.Map<String, Integer> vdoms = new java.util.TreeMap<>(java.util.Map.of("Cust-A", 2, "Cust-B", 5, "root", 3));
        java.util.Set<String> unreadable = new java.util.HashSet<>(), refusedEdit = new java.util.HashSet<>();
        String currentVdom;
        private String answer(String command) {
            if (command.equals("config vdom")) return "FGT-SYNTH-1 (vdom) # ";
            if (command.startsWith("edit ")) {
                String name = command.substring(5);
                if (refusedEdit.contains(name)) return "Command fail. Return code -3\n";
                currentVdom = name;
                return "FGT-SYNTH-1 (" + name + ") # ";
            }
            if (command.equals("show system interface")) {
                StringBuilder b = new StringBuilder("config system interface\n");
                int i = 0;
                for (String name : vdoms.keySet()) b.append("    edit \"port").append(++i).append("\"\n        set vdom \"").append(name).append("\"\n        set type physical\n    next\n");
                return b.append("end\n").toString();
            }
            if (command.equals("get system arp") && currentVdom != null) {
                if (unreadable.contains(currentVdom)) return "Command fail. Return code -3\n";
                return arpWith(vdoms.get(currentVdom));
            }
            switch (command) {
                case "config global" -> { inGlobal = true; return "FGT-SYNTH-1 (global) # "; }
                case "end" -> { inGlobal = false; currentVdom = null; return "FGT-SYNTH-1 # "; }
                case "get system status" -> {
                    if (statusRefusedAtTopLevel && !inGlobal) return "Command fail. Return code -1\n";
                    return status(SERIAL_A, multiVdom);
                }
                case "get system ha status" -> { return Ha.ok().text(); }
                case "show full-configuration system ha" -> { return FgtFixtures.config("disable", "enable", "300"); }
                case "diagnose sys ha checksum cluster" -> { return checksums(true); }
                case "diagnose sys ha dump-by group" -> { return dump(1000); }
                case "execute ha failover status" -> { return flag(false); }
                case "get system performance status" -> { return performance; }
                case "get system arp" -> { return arp; }
                default -> throw new AssertionError("unexpected command: " + command);
            }
        }
        long count(String c) { return commands.stream().filter(c::equals).count(); }
    }

    private static DeviceRepository devices() {
        return (DeviceRepository) Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
            new Class<?>[] {DeviceRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                case "find" -> Optional.of(new DeviceRecord((String) args[0], "gateway", "fortinet", "manual",
                    Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "credential-ref"));
                case "findEndpointByDeviceId" -> Optional.of(new EndpointRecord((String) args[0], (String) args[0], "ssh_exec", "192.0.2.31", Instant.EPOCH));
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
        return GateRegistryFixtureLoader.loadFromStream(FgtFailoverJobExecutorTest.class.getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
    }
    private static GateRegistryPort signedOff() {
        var rows = fixtureRows();
        assertTrue(rows.stream().filter(r -> NEW_GATES.contains(r.gateId())).allMatch(r -> r.signOffState() == SignOffState.SIGNED_OFF));
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }
    /** The three new rows forced to DRAFTED: what the registry would hold before the PO's approval. */
    private static GateRegistryPort draftedNew() {
        var rows = fixtureRows().stream().map(r -> NEW_GATES.contains(r.gateId())
            ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                r.actionClass(), SignOffState.DRAFTED, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        return key -> rows.stream().filter(row -> row.key().equals(key)).toList();
    }

    private record Outcome(String state, String outcome, String failedCheck, String message, List<String> checks, List<String> derived,
            List<JobState> job, Script script, int ledgered) {}

    private static Outcome run(Script script, GateRegistryPort gates, String kind, FgtFailoverReadinessSwitch readinessSwitch, String clusterRef) {
        JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
        List<String> states = new ArrayList<>(), checks = new ArrayList<>(), derived = new ArrayList<>();
        List<String> last = new ArrayList<>(List.of("", "", ""));
        when(store.runByJob("job-1")).thenReturn(Optional.of(new JooqCpFailoverRepository.Run("run-1", clusterRef, null, null, "actor-1",
            Instant.now(), "job-1", "PLANNED", "PLANNED", null, null, null, "fortinet", kind)));
        doAnswer(inv -> {
            states.add(inv.getArgument(1));
            last.set(0, String.valueOf((Object) inv.getArgument(3))); last.set(1, String.valueOf((Object) inv.getArgument(4))); last.set(2, String.valueOf((Object) inv.getArgument(5)));
            return null;
        }).when(store).state(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class));
        doAnswer(inv -> { assertEquals(DEVICE, inv.getArgument(2)); checks.add(inv.getArgument(4) + ":" + inv.getArgument(5)); derived.add(inv.getArgument(6)); return null; })
            .when(store).check(anyString(), anyString(), anyString(), nullable(String.class), anyInt(), anyString(), anyString());
        when(store.previousReadinessTraffic(anyString(), anyInt())).thenAnswer(inv -> previousTraffic);
        AtomicInteger ledger = new AtomicInteger();
        doAnswer(inv -> { ledger.incrementAndGet(); return null; }).when(store).command(anyString(), anyString());
        DevicePlatformFactsRepository facts = mock(DevicePlatformFactsRepository.class);
        when(facts.find(DEVICE)).thenReturn(script.recorded == null ? Optional.empty()
            : Optional.of(new DevicePlatformFacts(DEVICE, Optional.of(script.recorded), Optional.empty(), Optional.empty(), java.util.Map.of(),
                Optional.empty(), "synthetic", Optional.empty())));
        var leases = leases();
        new FgtFailoverJobExecutor(store, devices(), leases.repository(), attempts(), script.transport(), gates, facts,
            readinessSwitch, d -> { }, Duration.ZERO).execute("job-1", 1);
        return new Outcome(states.isEmpty() ? null : states.get(states.size() - 1), last.get(0), last.get(1), last.get(2),
            checks, derived, leases.to(), script, ledger.get());
    }
    private static final FgtFailoverReadinessSwitch ON = new FgtFailoverReadinessSwitch(true);

    @Test void draftedNewGatesSendNothingNewAndLeaveTheirChecksUnknown() {
        Script script = new Script();
        Outcome out = run(script, draftedNew(), "READINESS", ON, CLUSTER);
        assertEquals(0, script.count("diagnose sys ha checksum cluster") + script.count("diagnose sys ha dump-by group") + script.count("execute ha failover status"));
        assertEquals(List.of("get system status", "get system ha status", "show full-configuration system ha"), script.commands);
        assertTrue(out.checks().contains("4:UNKNOWN"));
        assertTrue(out.checks().contains("8:UNKNOWN"));
        assertTrue(out.derived().stream().anyMatch(d -> d.contains("\"reason\":\"GATE_NOT_SIGNED_OFF\"")));
        assertEquals("UNKNOWN", out.outcome());
        assertEquals(3, out.ledgered());
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), out.job());
    }
    @Test void everyDraftedGateMeansNoContactAtAll() {
        var rows = fixtureRows().stream().map(r -> r.vendor().equals("fortinet")
            ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                r.actionClass(), SignOffState.DRAFTED, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        Script script = new Script();
        Outcome out = run(script, key -> rows.stream().filter(row -> row.key().equals(key)).toList(), "READINESS", ON, CLUSTER);
        assertEquals(0, script.connects);
        assertTrue(script.commands.isEmpty());
        assertEquals("UNKNOWN", out.outcome());
        assertEquals(10, out.checks().size());
    }
    @Test void signedOffGatesSendTheSixLiteralsInOrderOnOneSession() {
        Script script = new Script();
        Outcome out = run(script, signedOff(), "READINESS", ON, CLUSTER);
        assertEquals(List.of("get system status", "get system ha status", "show full-configuration system ha",
            "diagnose sys ha checksum cluster", "diagnose sys ha dump-by group", "execute ha failover status"), script.commands);
        assertEquals(1, script.connects);
        assertEquals(6, out.ledgered(), "every read is ledgered before it is sent");
        for (int no : new int[] {1, 2, 3, 4, 5, 7, 8, 9}) assertTrue(out.checks().contains(no + ":PASS"), "check " + no);
        assertTrue(out.checks().contains("6:UNKNOWN"));
        assertEquals("READY", out.outcome());
        for (String sent : script.commands) {
            assertFalse(sent.startsWith("execute ha failover set") || sent.contains("reset-uptime") || sent.contains("session stat")
                || sent.startsWith("config system") || sent.startsWith("set "), sent);
        }
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), out.job());
    }
    @Test void aMultiVdomUnitReadsInTheGlobalContextAndLeavesIt() {
        Script script = new Script();
        script.multiVdom = true;
        run(script, signedOff(), "READINESS", ON, CLUSTER);
        assertEquals(List.of("get system status", "config global", "get system ha status", "show full-configuration system ha",
            "diagnose sys ha checksum cluster", "diagnose sys ha dump-by group", "execute ha failover status", "end"), script.commands);
    }
    @Test void aStatusRefusedAtTheTopLevelIsAskedOnceInsideGlobal() {
        Script script = new Script();
        script.statusRefusedAtTopLevel = true;
        run(script, signedOff(), "READINESS", ON, CLUSTER);
        assertEquals(List.of("get system status", "config global", "get system status", "end", "config global", "get system ha status",
            "show full-configuration system ha", "diagnose sys ha checksum cluster", "diagnose sys ha dump-by group",
            "execute ha failover status", "end"), script.commands);
    }
    @Test void aFailoverRunKindIsRefusedWithNoContact() {
        Script script = new Script();
        Outcome out = run(script, signedOff(), "FAILOVER", ON, CLUSTER);
        assertEquals(0, script.connects);
        assertEquals("STOPPED", out.state());
        assertEquals("READINESS_ONLY", out.message());
        assertEquals(List.of(JobState.EXECUTING, JobState.FAILED), out.job());
    }
    @Test void theWorkerSwitchOffRefusesBeforeAnyContact() {
        Script script = new Script();
        Outcome out = run(script, signedOff(), "READINESS", FgtFailoverReadinessSwitch.OFF, CLUSTER);
        assertEquals(0, script.connects);
        assertEquals("FGT_READINESS_SWITCH_OFF", out.message());
        assertEquals("UNKNOWN", out.outcome());
        assertFalse(FgtFailoverReadinessSwitch.fromValue(null).enabled());
        assertFalse(FgtFailoverReadinessSwitch.fromValue("true").enabled());
        assertTrue(FgtFailoverReadinessSwitch.fromValue("on").enabled());
    }
    @Test void aClusterReferenceThatIsNotASingleAddressUnitIsNotEligible() {
        Script script = new Script();
        Outcome out = run(script, signedOff(), "READINESS", ON, "fgt-ha|0123456789abcdef");
        assertEquals(0, script.connects);
        assertEquals("CLUSTER_NOT_ELIGIBLE", out.message());
    }
    @Test void aMissingRecordedSerialLeavesIdentityUnknownAndAMismatchIsNotReady() {
        Script script = new Script();
        script.recorded = null;
        Outcome missing = run(script, signedOff(), "READINESS", ON, CLUSTER);
        assertTrue(missing.checks().contains("1:UNKNOWN"));
        assertTrue(missing.derived().stream().anyMatch(d -> d.contains("RECORDED_SERIAL_MISSING")));
        Script other = new Script();
        other.recorded = "FGT60F0000000009";
        Outcome mismatch = run(other, signedOff(), "READINESS", ON, CLUSTER);
        assertEquals("NOT_READY", mismatch.outcome());
        assertEquals("IDENTITY_MISMATCH", mismatch.message());
    }
    @Test void derivedRowsCarryNoDeviceValue() {
        Outcome out = run(new Script(), signedOff(), "READINESS", ON, CLUSTER);
        String all = String.join("\n", out.derived());
        for (String forbidden : List.of("FGT60F", "FGT-SYNTH", "192.0.2.", "wan1", "ha1", "7.4.5"))
            assertFalse(all.contains(forbidden), forbidden);
    }
    @Test void shapeKeepsVendorWordsAndMasksTheRest() {
        String shaped = String.join("\n", FgtShape.shape("Primary: FGT-SYNTH-1 , FGT60F0000000001, HA cluster index = 0\nGroup Name: secret-name\n    wan1: up 192.0.2.7 12:30:01\n", 10));
        assertTrue(shaped.contains("Primary"));
        assertTrue(shaped.contains("HA cluster index"));
        for (String forbidden : List.of("FGT60F", "FGT-SYNTH", "secret", "wan1", "192.0.2.7", "12:30"))
            assertFalse(shaped.contains(forbidden), forbidden);
    }

    // ---- information check 10: traffic and ARP -----------------------------------------------------------------------

    @Test void draftedTrafficRowsSendNothingNewAndCheckTenIsUnknownNonBlocking() {
        Script script = new Script();
        Outcome out = run(script, signedOff(), "READINESS", ON, CLUSTER);
        assertEquals(0, script.count("get system performance status") + script.count("get system arp"));
        assertEquals("READY", out.outcome(), "an unapproved information check never blocks");
        assertTrue(out.checks().contains("10:UNKNOWN"));
        assertTrue(out.derived().get(out.derived().size() - 1).contains("GATE_NOT_SIGNED_OFF"));
    }
    @Test void aSingleVdomUnitReadsTrafficAndArpAtTheTopLevelAsBefore() {
        Script script = new Script();
        Outcome out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        assertEquals(List.of("get system status", "get system ha status", "show full-configuration system ha",
            "diagnose sys ha checksum cluster", "diagnose sys ha dump-by group", "execute ha failover status",
            "get system performance status", "get system arp"), script.commands);
        assertEquals(1, script.connects);
        assertEquals(8, out.ledgered());
        assertEquals("READY", out.outcome());
        assertTrue(out.checks().contains("10:PASS"));
        String evidence = out.derived().get(out.derived().size() - 1);
        assertTrue(evidence.contains("\"trafficBytesPerSecond\":2500"), evidence);
        assertTrue(evidence.contains("\"arpEntries\":3"), evidence);
        assertTrue(evidence.contains("\"averageSessions\":1234"), evidence);
        assertFalse(evidence.contains("previous") || evidence.contains("arpByVdom") || evidence.contains("trafficScope"), evidence);
        for (String forbidden : List.of("192.0.2.", "00:00:5e", "port1")) assertFalse(String.join("\n", out.derived()).contains(forbidden), forbidden);
    }
    @Test void aMultiVdomUnitReadsArpInsideEachVdomWithTheApprovedMovesAndLeavesEachOne() {
        Script script = new Script();
        script.multiVdom = true;
        Outcome out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        assertEquals(List.of("get system status", "config global", "get system ha status", "show full-configuration system ha",
            "diagnose sys ha checksum cluster", "diagnose sys ha dump-by group", "execute ha failover status",
            "get system performance status", "show system interface", "end",
            "config vdom", "edit Cust-A", "get system arp", "end",
            "config vdom", "edit Cust-B", "get system arp", "end",
            "config vdom", "edit root", "get system arp", "end"), script.commands);
        assertEquals(1, script.connects);
        assertEquals(11, out.ledgered(), "status, five HA reads, performance, interface listing, three ARP reads");
        assertEquals("READY", out.outcome());
        String evidence = out.derived().get(out.derived().size() - 1);
        assertTrue(out.checks().contains("10:PASS"));
        assertTrue(evidence.contains("\"arpEntries\":10"), "2 + 5 + 3: " + evidence);
        assertTrue(evidence.contains("\"arpByVdom\":{\"1\":2,\"2\":5,\"3\":3}"), evidence);
        assertTrue(evidence.contains("\"trafficScope\":\"BOX\"") && evidence.contains("\"trafficBytesPerSecond\":2500"), evidence);
        String all = String.join("\n", out.derived());
        for (String forbidden : List.of("Cust-A", "Cust-B", "root", "192.0.2.", "00:00:5e", "port1")) assertFalse(all.contains(forbidden), forbidden);
    }
    @Test void aVdomWhoseArpFailsMakesTheTotalUnknownButKeepsTheOtherCounts() {
        for (boolean refuseTheMove : new boolean[] {false, true}) {
            Script script = new Script();
            script.multiVdom = true;
            if (refuseTheMove) script.refusedEdit.add("Cust-B"); else script.unreadable.add("Cust-B");
            Outcome out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
            String evidence = out.derived().get(out.derived().size() - 1);
            assertEquals("READY", out.outcome(), "information only");
            assertTrue(out.checks().contains("10:UNKNOWN"));
            assertTrue(evidence.contains("ARP_UNAVAILABLE"), evidence);
            assertFalse(evidence.contains("\"arpEntries\""), evidence);
            assertTrue(evidence.contains("\"arpByVdom\":{\"1\":2,\"2\":null,\"3\":3}"), evidence);
            assertTrue(evidence.contains("\"arpVdomUnknown\":1"), evidence);
            // Every VDOM that was entered is left again, and a refused "edit" is not followed by a read in the wrong context.
            long moves = script.commands.stream().filter("config vdom"::equals).count();
            assertEquals(3, moves);
            assertEquals(3, script.commands.stream().filter("end"::equals).count() - 1, "one end per VDOM after the global one");
            assertEquals(refuseTheMove ? 2 : 3, script.count("get system arp"));
            assertEquals("end", script.commands.get(script.commands.size() - 1));
        }
    }
    @Test void anEarlierMultiVdomRunGivesPerVdomRatiosAndCountsTheDrops() {
        previousTraffic = Optional.of(new JooqCpFailoverRepository.Check("pre", DEVICE, null, 10, "PASS",
            "{\"trafficBytesPerSecond\":2500,\"arpEntries\":20,\"arpByVdom\":{\"1\":4,\"2\":5,\"3\":11}}", Instant.EPOCH));
        Script script = new Script();
        script.multiVdom = true;
        Outcome out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        String evidence = out.derived().get(out.derived().size() - 1);
        assertEquals("READY", out.outcome());
        assertTrue(evidence.contains("\"arpRatio\":0.5") && evidence.contains("\"arpDropped\":false"), evidence);
        assertTrue(evidence.contains("\"arpDroppedVdoms\":1"), "only 3 of 11 is below 0.50 (2 of 4 is exactly half): " + evidence);
        assertTrue(out.checks().contains("10:PASS"));
    }
    @Test void aDifferentNumberOfVdomsGivesNoPerVdomComparison() {
        previousTraffic = Optional.of(new JooqCpFailoverRepository.Check("pre", DEVICE, null, 10, "PASS",
            "{\"trafficBytesPerSecond\":2500,\"arpEntries\":20,\"arpByVdom\":{\"1\":4,\"2\":16}}", Instant.EPOCH));
        Script script = new Script();
        script.multiVdom = true;
        Outcome out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        String evidence = out.derived().get(out.derived().size() - 1);
        assertTrue(evidence.contains("\"previousArpEntries\":20"), evidence);
        assertFalse(evidence.contains("arpDroppedVdoms"), evidence);
    }
    @Test void aMultiVdomUnitWithADraftedArpGateOrAnUnavailableListingSendsNoVdomMoves() {
        Script script = new Script();
        script.multiVdom = true;
        run(script, signedOff(), "READINESS", ON, CLUSTER);
        assertTrue(script.commands.stream().noneMatch(c -> c.equals("config vdom") || c.startsWith("edit ") || c.equals("show system interface")
            || c.equals("get system arp") || c.equals("get system performance status")), script.commands.toString());
        // The ARP gate approved but the listing read refused (a registry without the inventory row): nothing is guessed.
        var rows = fixtureRows().stream().filter(r -> !r.gateId().equals("fgt_show_system_interface")).toList();
        GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).map(r -> TRAFFIC_GATES.contains(r.gateId())
            ? new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
                r.actionClass(), SignOffState.SIGNED_OFF, r.timeoutS(), r.retryRule(), r.maxFrequency(), r.sessionReuseRule(),
                r.unsupportedBehaviorRef(), r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer()) : r).toList();
        Script other = new Script();
        other.multiVdom = true;
        Outcome out = run(other, gates, "READINESS", ON, CLUSTER);
        assertTrue(other.commands.stream().noneMatch(c -> c.equals("config vdom") || c.equals("get system arp")), other.commands.toString());
        assertEquals("READY", out.outcome());
        assertTrue(out.checks().contains("10:UNKNOWN"));
        assertTrue(out.derived().get(out.derived().size() - 1).contains("GATE_UNAVAILABLE"));
    }
    @Test void anEarlierRunGivesTheBeforeAfterRatiosAndAFlagWithoutBlocking() {
        previousTraffic = Optional.of(new JooqCpFailoverRepository.Check("pre", DEVICE, null, 10, "PASS",
            "{\"trafficBytesPerSecond\":10000,\"arpEntries\":3}", Instant.EPOCH));
        Outcome out = run(new Script(), signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        String evidence = out.derived().get(out.derived().size() - 1);
        assertEquals("READY", out.outcome());
        assertTrue(evidence.contains("\"previousTrafficBytesPerSecond\":10000") && evidence.contains("\"previousArpEntries\":3"), evidence);
        assertTrue(evidence.contains("\"trafficRatio\":0.25") && evidence.contains("\"trafficDropped\":true"), evidence);
        assertTrue(evidence.contains("\"arpRatio\":1.0") && evidence.contains("\"arpDropped\":false"), evidence);
        assertTrue(out.checks().contains("10:PASS"));
    }
    @Test void unparsableAndZeroAnswersAreUnknownAndNeverBlock() {
        Script script = new Script();
        script.performance = "Command fail. Return code -3\n"; script.arp = "unknown action 0\n";
        Outcome out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        assertEquals("READY", out.outcome());
        assertTrue(out.checks().contains("10:UNKNOWN"));
        assertTrue(out.derived().get(out.derived().size() - 1).contains("TRAFFIC_AND_ARP_UNAVAILABLE"));
        script = new Script();
        script.performance = PERFORMANCE.replace("12 / 8 kbps in 1 minute", "0 / 0 kbps in 1 minute");
        script.arp = "Address           Age(min)   Hardware Addr      Interface\n";
        out = run(script, signedOffWithTraffic(), "READINESS", ON, CLUSTER);
        String evidence = out.derived().get(out.derived().size() - 1);
        assertEquals("READY", out.outcome());
        assertTrue(evidence.contains("TRAFFIC_AND_ARP_ZERO") && evidence.contains("\"trafficBytesPerSecond\":0")
            && evidence.contains("\"arpEntries\":0"), evidence);
    }
}
