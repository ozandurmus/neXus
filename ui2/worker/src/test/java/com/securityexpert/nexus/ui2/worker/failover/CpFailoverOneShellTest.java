package com.securityexpert.nexus.ui2.worker.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.jobs.transport.*;

/**
 * The executor on the one-shell path (CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md): the scripted two-member transport of
 * {@link CpFailoverJobExecutorTest} answers inside a {@link ScriptedShell} per member. No socket, synthetic output only.
 */
class CpFailoverOneShellTest {

    /** The fixture's DRAFTED framing row, as a later sign-off migration would make it. */
    static GateRegistryPort signedOffFraming() {
        return signedOffFraming(CpFailoverJobExecutorTest.gates());
    }

    static GateRegistryPort signedOffFraming(GateRegistryPort fixture) {
        return key -> fixture.findByCanonicalKey(key).stream().map(row -> !CpFailoverJobExecutor.SHELL_FRAMING_GATE.equals(row.gateId())
                ? row : new GateRow(row.gateId(), row.vendor(), row.platformRoleScope(), row.shellContext(), row.transportKind(),
                        row.canonicalCommandKey(), row.actionClass(), SignOffState.SIGNED_OFF, row.timeoutS(), row.retryRule(),
                        row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(), row.secretOutputRisk(),
                        row.safeTelemetryFields(), row.sourceDocumentPointer())).toList();
    }

    private record Run(CpFailoverJobExecutorTest.Store store, CpFailoverJobExecutorTest.Script script, ShellTransport transport,
            List<Duration> pauses) {}

    private static Run run(String kind, boolean oneShell, GateRegistryPort gates, Duration commandPause,
            java.util.function.Consumer<ShellTransport> setup) {
        var store = new CpFailoverJobExecutorTest.Store();
        store.kind = kind;
        var script = new CpFailoverJobExecutorTest.Script();
        var clock = new AtomicLong();
        var transport = new ShellTransport(script.transport(), clock);
        setup.accept(transport);
        List<Duration> pauses = new ArrayList<>();
        new CpFailoverJobExecutor(store, CpFailoverJobExecutorTest.devices(), CpFailoverJobExecutorTest.leases(),
                CpFailoverJobExecutorTest.attempts(), transport, gates, d -> { pauses.add(d); clock.addAndGet(d.toNanos()); },
                commandPause, new FailoverMutationSwitch(!"READINESS".equals(kind)), clock::get,
                new CpFailoverShellMode(oneShell)).execute("job-1", 1);
        return new Run(store, script, transport, pauses);
    }

    private static Run readiness(boolean oneShell, java.util.function.Consumer<ShellTransport> setup) {
        return run("READINESS", oneShell, signedOffFraming(), Duration.ZERO, setup);
    }

    @Test
    void readinessUsesOneShellPerMemberAndNeverExec() {
        var run = readiness(true, t -> {});
        assertEquals("READY", run.store().outcome, run.store().checks.toString());
        assertEquals(26, run.store().checks.size());
        assertEquals(List.of(), run.transport().execLiterals, "No exec channel may be opened on the one-shell path");
        assertEquals(List.of("192.0.2.11", "192.0.2.12"), List.copyOf(run.transport().shells.keySet()));
        run.transport().shells.values().forEach(list -> assertEquals(1, list.size(), "One shell per member per pass"));
        for (var list : run.transport().shells.values()) {
            var shell = list.get(0);
            assertEquals(1, shell.closes, "Closed at the end of the pass");
            assertTrue(shell.inner.size() >= 12, "Every read of the member ran in its one shell: " + shell.inner);
            assertTrue(shell.inner.stream().noneMatch(c -> c.contains("bash -lc")), "No nested login shell: " + shell.inner);
            assertTrue(shell.inner.contains("cphaprob stat") && shell.inner.contains("cphaprob -a if"));
        }
    }

    @Test
    void shellAndExecPathsRecordTheSameChecks() {
        var shell = readiness(true, t -> {});
        var exec = readiness(false, t -> {});
        assertEquals(exec.store().checks, shell.store().checks);
        assertEquals(exec.store().derivedValues, shell.store().derivedValues);
        assertEquals(exec.store().outcome, shell.store().outcome);
    }

    @Test
    void vsRunsSendTheVsenvFormInsideTheShell() {
        var store = new CpFailoverJobExecutorTest.Store();
        store.kind = "READINESS";
        store.vsId = "12";
        var script = new CpFailoverJobExecutorTest.Script();
        var clock = new AtomicLong();
        var transport = new ShellTransport(script.transport(), clock);
        new CpFailoverJobExecutor(store, CpFailoverJobExecutorTest.devices(), CpFailoverJobExecutorTest.leases(),
                CpFailoverJobExecutorTest.attempts(), transport, signedOffFraming(), d -> clock.addAndGet(d.toNanos()),
                Duration.ZERO, new FailoverMutationSwitch(false), clock::get, new CpFailoverShellMode(true)).execute("job-1", 1);
        assertEquals("READY", store.outcome, store.checks.toString());
        assertEquals(List.of(), transport.execLiterals);
        for (var list : transport.shells.values())
            assertTrue(list.get(0).inner.stream().allMatch(c -> c.startsWith("vsenv 12 && ")), list.get(0).inner.toString());
    }

    @Test
    void timeoutMakesTheRunUnknownWithoutASecondShell() {
        var run = readiness(true, t -> t.hangOn = "cphaprob tablestat");
        assertEquals("UNKNOWN", run.store().outcome);
        assertEquals("COMMAND_UNAVAILABLE", run.store().stopCode);
        run.transport().shells.values().forEach(list -> assertEquals(1, list.size()));
        assertEquals(1, run.transport().shells.get("192.0.2.11").get(0).closes);
        assertEquals(List.of(), run.transport().execLiterals);
    }

    @Test
    void desyncIsUnknownAndTheBrokenShellIsNeverReusedOrReopened() {
        var run = readiness(true, t -> t.desyncOn = "cat /proc/net/dev");
        assertEquals("UNKNOWN", run.store().outcome, run.store().checks.toString());
        assertTrue(run.store().checks.contains("pre:8:UNKNOWN"), run.store().checks.toString());
        assertTrue(run.store().checks.stream().noneMatch(c -> c.startsWith("pre:8:PASS")));
        var broken = run.transport().shells.get("192.0.2.11");
        assertEquals(1, broken.size(), "No second shell after a desync");
        long traffic = broken.get(0).inner.stream().filter(c -> c.equals("cat /proc/net/dev")).count();
        assertEquals(1, traffic, "Nothing is sent on the member's shell after the desync: " + broken.get(0).inner);
        assertEquals("cat /proc/net/dev", broken.get(0).inner.get(broken.get(0).inner.size() - 1));
        assertEquals(List.of(), run.transport().execLiterals);
    }

    @Test
    void configuredPauseRunsBetweenEveryTwoCommands() {
        var run = run("READINESS", true, signedOffFraming(), Duration.ofMillis(1000), t -> {});
        assertEquals("READY", run.store().outcome);
        long commandPauses = run.pauses().stream().filter(Duration.ofMillis(1000)::equals).count();
        assertEquals(run.transport().sent() - 1, commandPauses, run.pauses().toString());
        assertEquals(1, run.pauses().stream().filter(Duration.ofSeconds(5)::equals).count(), "Traffic window unchanged");
    }

    @Test
    void draftedFramingGateStopsBeforeAnyConnection() {
        var run = run("READINESS", true, CpFailoverJobExecutorTest.gates(), Duration.ZERO, t -> {});
        assertEquals("UNKNOWN", run.store().outcome);
        assertEquals("COMMAND_GATE_UNAVAILABLE", run.store().stopCode);
        assertEquals(0, run.script().connectCount);
        assertTrue(run.transport().shells.isEmpty() && run.transport().execLiterals.isEmpty());
    }

    @Test
    void switchOffKeepsTheExactExecLiteralsAndNeverOpensAShell() {
        var off = run("READINESS", false, signedOffFraming(), Duration.ZERO, t -> {});
        var store = new CpFailoverJobExecutorTest.Store();
        store.kind = "READINESS";
        var script = new CpFailoverJobExecutorTest.Script();
        var nanos = new AtomicLong();
        new CpFailoverJobExecutor(store, CpFailoverJobExecutorTest.devices(), CpFailoverJobExecutorTest.leases(),
                CpFailoverJobExecutorTest.attempts(), script.transport(), CpFailoverJobExecutorTest.gates(),
                d -> nanos.addAndGet(d.toNanos()), Duration.ZERO, new FailoverMutationSwitch(false), nanos::get).execute("job-1", 1);
        assertTrue(off.transport().shells.isEmpty());
        assertEquals(script.commands, off.transport().execLiterals);
        assertTrue(off.transport().execLiterals.stream().allMatch(c -> c.startsWith("bash -lc '")));
        assertEquals(store.checks, off.store().checks);
    }

    @Test
    void failoverRunKeepsTheWritesOnExecAndReadsOnTheShells() {
        var run = run("FAILOVER", true, signedOffFraming(), Duration.ZERO, t -> {});
        assertEquals("DONE", run.store().state, run.store().checks.toString());
        assertEquals(List.of("bash -lc 'clusterXL_admin down'", "bash -lc 'clusterXL_admin up'"), run.transport().execLiterals);
        assertEquals(1, run.script().downCount);
        assertEquals(1, run.script().upCount);
        run.transport().shells.values().forEach(list -> assertEquals(1, list.size(), "One shell per member per run"));
        assertTrue(run.transport().shells.values().stream().flatMap(List::stream)
                .allMatch(s -> s.inner.stream().noneMatch(c -> c.contains("clusterXL_admin"))));
    }
}
