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

    private static final String CLUSTER = "CLS-TEST-01";

    private static final String EXEC_STAT = "bash -lc 'cphaprob stat'";
    private static final List<String> WRITES = List.of("bash -lc 'clusterXL_admin down'", "bash -lc 'clusterXL_admin up'");

    /** The fixture's framing row (V175 seeds it DRAFTED pending amendment A1) as the PO confirmation signs it off. */
    static GateRegistryPort signedOffFraming() {
        return signedOffFraming(CpFailoverJobExecutorTest.gates());
    }

    static GateRegistryPort signedOffFraming(GateRegistryPort fixture) {
        return framingAs(fixture, SignOffState.SIGNED_OFF);
    }

    /** The fixture with the framing row in another sign-off state. */
    static GateRegistryPort framingAs(SignOffState state) {
        return framingAs(CpFailoverJobExecutorTest.gates(), state);
    }

    static GateRegistryPort framingAs(GateRegistryPort fixture, SignOffState state) {
        return key -> fixture.findByCanonicalKey(key).stream().map(row -> !CpFailoverJobExecutor.SHELL_FRAMING_GATE.equals(row.gateId())
                ? row : new GateRow(row.gateId(), row.vendor(), row.platformRoleScope(), row.shellContext(), row.transportKind(),
                        row.canonicalCommandKey(), row.actionClass(), state, row.timeoutS(), row.retryRule(),
                        row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(), row.secretOutputRisk(),
                        row.safeTelemetryFields(), row.sourceDocumentPointer())).toList();
    }

    private record Run(CpFailoverJobExecutorTest.Store store, CpFailoverJobExecutorTest.Script script, ShellTransport transport,
            List<Duration> pauses) {}

    private static Run run(String kind, boolean oneShell, GateRegistryPort gates, Duration commandPause,
            java.util.function.Consumer<ShellTransport> setup) {
        // Same pause on both paths, so the two paths are comparable run for run.
        return run(kind, oneShell ? new CpFailoverShellMode(true, java.util.Set.of(CpFailoverShellMode.ALL), commandPause)
                : CpFailoverShellMode.OFF, gates, commandPause, setup);
    }

    private static Run run(String kind, CpFailoverShellMode mode, GateRegistryPort gates, Duration commandPause,
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
                mode).execute("job-1", 1);
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

    /** Review P0-1: the failed read stays UNKNOWN (never retried); the member's remaining reads fall back to exec. */
    @Test
    void desyncOnACaughtReadFallsBackToExecForTheRemainingReads() {
        var run = readiness(true, t -> t.desyncOn = "cat /proc/net/dev");
        assertEquals("UNKNOWN", run.store().outcome, run.store().checks.toString());
        assertEquals(26, run.store().checks.size(), "Every check is still recorded: " + run.store().checks);
        assertEquals(2, run.store().checks.stream().filter("pre:8:UNKNOWN"::equals).count(), run.store().checks.toString());
        assertTrue(run.store().checks.stream().filter(c -> !c.startsWith("pre:8:") && !c.startsWith("pre:6:"))
                .allMatch(c -> c.endsWith(":PASS")), run.store().checks.toString());
        var broken = run.transport().shells.get("192.0.2.11");
        assertEquals(1, broken.size(), "No second shell after a desync");
        assertEquals(1, run.transport().openAttempts.get("192.0.2.11"));
        assertEquals("cat /proc/net/dev", broken.get(0).inner.get(broken.get(0).inner.size() - 1),
                "Nothing is sent on the broken shell: " + broken.get(0).inner);
        assertEquals(List.of("bash -lc 'cat /proc/net/dev'"), run.transport().execLiterals,
                "Only the failed member's remaining read (the second traffic sample) used exec");
    }

    /** Review P0-1 / P1-4: a timeout on check 13 after DOWN must not stop the run or block the return. */
    @Test
    void timeoutOnTheLastFailoverReadAfterDownStillCompletesTheRun() {
        var script = new CpFailoverJobExecutorTest.Script();
        var store = new CpFailoverJobExecutorTest.Store();
        var clock = new AtomicLong();
        var transport = new ShellTransport(script.transport(), clock);
        transport.hangIf = (session, inner) -> script.down && !script.up && session.equals("192.0.2.11")
                && inner.equals("cphaprob show_failover");
        new CpFailoverJobExecutor(store, CpFailoverJobExecutorTest.devices(), CpFailoverJobExecutorTest.leases(),
                CpFailoverJobExecutorTest.attempts(), transport, signedOffFraming(), d -> clock.addAndGet(d.toNanos()),
                Duration.ZERO, new FailoverMutationSwitch(true), clock::get, new CpFailoverShellMode(true)).execute("job-1", 1);
        assertEquals("DONE", store.state, store.checks.toString());
        assertEquals("SUCCEEDED", store.outcome);
        assertTrue(store.checks.contains("post:13:UNKNOWN"), store.checks.toString());
        assertEquals(1, script.downCount); assertEquals(1, script.upCount);
        assertEquals(1, transport.shells.get("192.0.2.11").size(), "No second shell");
        assertEquals(1, transport.shells.get("192.0.2.11").get(0).interrupts, "Ctrl-C on the timed-out read");
        assertTrue(transport.execLiterals.contains("bash -lc 'cpstat os -f routing'"),
                "The member's later reads ran over exec: " + transport.execLiterals);
        int up = transport.execLiterals.indexOf(WRITES.get(1));
        assertTrue(transport.execLiterals.subList(0, up).stream().noneMatch("bash -lc 'cphaprob show_failover'"::equals),
                "The failed post read itself is never retried: " + transport.execLiterals);
        assertEquals(1, transport.execLiterals.stream().filter("bash -lc 'cphaprob show_failover'"::equals).count(),
                "Only the post_return phase's own read of check 13 runs (over exec)");
    }

    @Test
    void shellOpenFailureIsTriedOncePerMemberThenExec() {
        var run = readiness(true, t -> t.openFails = true);
        assertEquals("READY", run.store().outcome, run.store().checks.toString());
        assertEquals(Map.of("192.0.2.11", 1, "192.0.2.12", 1), run.transport().openAttempts);
        assertTrue(run.transport().shells.isEmpty());
        var exec = readiness(false, t -> {});
        assertEquals(exec.transport().execLiterals, run.transport().execLiterals);
        assertEquals(exec.store().checks, run.store().checks);
    }

    /** Review P1-6: the shell pause never changes an exec-path run (unlisted cluster). */
    @Test
    void shellPauseNeverChangesAnUnlistedClustersExecPause() {
        var mode = CpFailoverShellMode.fromValues("enabled", "CLS-OTHER-02", "100");
        var run = run("READINESS", mode, signedOffFraming(), Duration.ofSeconds(2), t -> {});
        assertTrue(run.transport().shells.isEmpty());
        assertTrue(run.pauses().stream().noneMatch(Duration.ofMillis(100)::equals), run.pauses().toString());
        assertEquals(run.transport().execLiterals.size() - 1,
                run.pauses().stream().filter(Duration.ofSeconds(2)::equals).count());
    }

    @Test
    void configuredPauseRunsBetweenEveryTwoCommands() {
        var mode = new CpFailoverShellMode(true, java.util.Set.of(CLUSTER), Duration.ofMillis(750));
        var run = run("READINESS", mode, signedOffFraming(), Duration.ofSeconds(2), t -> {});
        assertEquals("READY", run.store().outcome);
        assertTrue(run.pauses().stream().noneMatch(Duration.ofSeconds(2)::equals), "The exec pause is not used on the shell");
        long commandPauses = run.pauses().stream().filter(Duration.ofMillis(750)::equals).count();
        assertEquals(run.transport().sent() - 1, commandPauses, run.pauses().toString());
        assertEquals(1, run.pauses().stream().filter(Duration.ofSeconds(5)::equals).count(), "Traffic window unchanged");
    }

    @Test
    void fixtureFramingRowIsTheAmendedLiteralDraftedAsV175SeedsIt() {
        var rows = CpFailoverJobExecutorTest.gates().findByCanonicalKey(new com.securityexpert.nexus.ui2.capability.CanonicalCommandKey(
                "check_point", "cp_gaia_gateway", "expert", "SSH_INTERACTIVE", CpFailoverJobExecutor.SHELL_FRAMING));
        assertEquals(1, rows.size());
        assertEquals(SignOffState.DRAFTED, rows.get(0).signOffState());
        assertEquals(CpFailoverJobExecutor.SHELL_FRAMING_GATE, rows.get(0).gateId());
    }

    @Test
    void unsignedFramingGateStopsBeforeAnyConnection() {
        var run = run("READINESS", true, framingAs(SignOffState.DRAFTED), Duration.ZERO, t -> {});
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

    /** PO 2026-10-22 "one cluster first": switch AND allow-list; {@code *} = all clusters. */
    @Test
    void clusterAllowListDecidesThePathPerRun() {
        record Case(String name, CpFailoverShellMode mode, boolean shell) {}
        for (var c : List.of(
                new Case("off", CpFailoverShellMode.fromValues(null, CLUSTER, null), false),
                new Case("off-but-listed", CpFailoverShellMode.fromValues("disabled", CLUSTER, null), false),
                new Case("enabled-unset-list", CpFailoverShellMode.fromValues("enabled", null, null), false),
                new Case("enabled-not-listed", CpFailoverShellMode.fromValues("enabled", "CLS-OTHER-02", null), false),
                new Case("enabled-listed", CpFailoverShellMode.fromValues("enabled", "CLS-OTHER-02, " + CLUSTER, null), true),
                new Case("enabled-all", CpFailoverShellMode.fromValues("enabled", "*", null), true))) {
            for (String kind : List.of("READINESS", "FAILOVER")) {
                var run = run(kind, c.mode(), signedOffFraming(), Duration.ZERO, t -> {});
                String context = c.name() + " " + kind + " " + run.store().checks;
                assertEquals("READINESS".equals(kind) ? "READY" : "SUCCEEDED", run.store().outcome, context);
                assertEquals(c.shell() ? 2 : 0, run.transport().shells.size(), context);
                if (c.shell()) {
                    assertEquals("READINESS".equals(kind) ? List.of() : WRITES,
                            run.transport().execLiterals.stream().filter(l -> !EXEC_STAT.equals(l)).toList(), context);
                } else {
                    assertTrue(run.transport().execLiterals.size() > 20, context);
                    assertTrue(run.transport().execLiterals.stream().allMatch(l -> l.startsWith("bash -lc '")), context);
                }
            }
        }
    }

    @Test
    void failoverRunKeepsTheWritesOnExecAndReadsOnTheShells() {
        var run = run("FAILOVER", true, signedOffFraming(), Duration.ZERO, t -> {});
        assertEquals("DONE", run.store().state, run.store().checks.toString());
        // Review P1-4: the writes and the write-confirmation polls (cphaprob stat) keep their exec evidence.
        assertEquals(WRITES, run.transport().execLiterals.stream().filter(l -> !EXEC_STAT.equals(l)).toList());
        int down = run.transport().execLiterals.indexOf(WRITES.get(0)), up = run.transport().execLiterals.indexOf(WRITES.get(1));
        assertTrue(up - down >= 3, "Confirmation polls of the switch over exec: " + run.transport().execLiterals);
        assertTrue(run.transport().execLiterals.size() - up >= 3, "Confirmation polls of the return over exec");
        assertEquals(1, run.script().downCount);
        assertEquals(1, run.script().upCount);
        run.transport().shells.values().forEach(list -> assertEquals(1, list.size(), "One shell per member per run"));
        assertTrue(run.transport().shells.values().stream().flatMap(List::stream)
                .allMatch(s -> s.inner.stream().noneMatch(c -> c.contains("clusterXL_admin"))));
    }
}
