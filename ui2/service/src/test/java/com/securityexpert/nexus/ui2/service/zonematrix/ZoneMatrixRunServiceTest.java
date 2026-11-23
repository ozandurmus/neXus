package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.FailureReason;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Zone matrix delivery step 4b-1: durable runs on the own bounded pool (docs/design/ZONE_MATRIX_CONTRACT.md sections
 * 5.1, 8.1, 8.2 and 9; acceptance criteria ZM-10 and the parts of ZM-39 this step owns: the separate pool, the time
 * limit ends a run FAILED, a pod kill or a failed write never leaves partial findings visible). The heap ceiling of
 * ZM-39 is not enforced in this step. Synthetic zone names and RFC 5737 documentation blocks only.
 */
class ZoneMatrixRunServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PolicyObject OBJ_ANY = new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");
    private static final PolicySnapshot.Cell ANY_CELL = new PolicySnapshot.Cell(List.of("obj-any"), false);
    private static final String POLICY = "pol-" + "0042-Ab";

    private final List<ZoneMatrixRunService> services = new ArrayList<>();

    @AfterEach void closeServices() { services.forEach(ZoneMatrixRunService::close); }

    // ------------------------------------------------------------------ fixture

    /** A published version: ZONE-ALPHA 192.0.2.0/25, ZONE-BRAVO 198.51.100.0/24; ALPHA -> BRAVO allows tcp/443 only. */
    private static final class Matrix {
        final ZoneMatrixServiceTest.Memory versions = new ZoneMatrixServiceTest.Memory();
        final Map<String, PolicySnapshot> snapshots = new ConcurrentHashMap<>();
        final String versionId;
        final int versionNumber;
        final String contentSha256;

        Matrix() {
            var service = new ZoneMatrixService(versions, new Principals() {
                @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
                @Override public boolean isRoot(String actor) { return false; }
            }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
            String alpha = (String) service.createZone("security-1", null,
                    new ZoneInput("ZONE-ALPHA", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
            long rev = versions.draft().orElseThrow().revision();
            String bravo = (String) service.createZone("security-1", rev,
                    new ZoneInput("ZONE-BRAVO", "", "APPLICATION", List.of("198.51.100.0/24"))).get("zone_id");
            rev = versions.draft().orElseThrow().revision();
            service.setCell("security-1", rev,
                    new CellInput(alpha, bravo, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "web tier", ""));
            var draft = versions.draft().orElseThrow();
            var published = service.publish("compliance-1", false,
                    new PublishRequest(draft.revision(), draft.contentSha256(), null));
            versionId = (String) published.get("version_id");
            versionNumber = ((Number) published.get("number")).intValue();
            contentSha256 = versions.active().orElseThrow().contentSha256();
        }

        PolicyCatalog catalog() {
            return new PolicyCatalog() {
                @Override public List<Map<String, Object>> catalogViews() { return List.of(); }
                @Override public Optional<PolicySnapshot> find(String policyId) {
                    return Optional.ofNullable(snapshots.get(policyId));
                }
            };
        }
    }

    private static PolicyObject addr(String id, String name, String cidr) {
        return new PolicyObject(id, name, "address", List.of(), List.of("ip-netmask: " + cidr), "RESOLVED");
    }

    private static PolicyObject svc(String id, String name, String proto, String port) {
        return new PolicyObject(id, name, "service", List.of(), List.of("protocol/" + proto + "/port: " + port), "RESOLVED");
    }

    private static Rule rule(String id, int number, String name, Boolean enabled, String src, String dst, String service) {
        return new Rule(id, "uid-" + id, number, name, enabled, new PolicySnapshot.Cell(List.of(src), false),
                new PolicySnapshot.Cell(List.of(dst), false), new PolicySnapshot.Cell(List.of(service), false), ANY_CELL,
                "allow", "Log", "", Map.of());
    }

    /**
     * Four rules: COMPLIANT (alpha -> bravo tcp/443), VIOLATION (alpha -> bravo tcp/22), NOT_EVALUABLE (source object
     * not collected), and a disabled rule (NOT_APPLICABLE). Rule and object names are planted markers for ZM-10.
     */
    private static PolicySnapshot snapshot(String policyId) {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("src", addr("src", "PLANTED-SRC-OBJECT", "192.0.2.16/28"));
        objects.put("dst", addr("dst", "PLANTED-DST-OBJECT", "198.51.100.77/32"));
        objects.put("https", svc("https", "HTTPS", "tcp", "443"));
        objects.put("ssh", svc("ssh", "SSH", "tcp", "22"));
        List<Rule> rules = List.of(
                rule("r1", 1, "PLANTED-RULE-COMPLIANT", true, "src", "dst", "https"),
                rule("r2", 2, "PLANTED-RULE-VIOLATION", true, "src", "dst", "ssh"),
                rule("r3", 3, "PLANTED-RULE-UNRESOLVED", true, "missing-object", "dst", "https"),
                rule("r4", 4, "PLANTED-RULE-DISABLED", false, "src", "dst", "ssh"));
        return new PolicySnapshot(
                new Metadata(policyId, "src-1", "FW-TANGO-04", "PAN", "dom-1", "DOM-BRAVO-02", "PLANTED-POLICY-NAME",
                        "2026-11-23T08:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC"))),
                List.of(new Section(PolicySnapshot.ref(policyId, "PAN device-group", "pre-rulebase"), "Pre rules",
                        "PAN device-group", null, rules)),
                objects);
    }

    private ZoneMatrixRunService service(Matrix m, FakeRuns runs, PolicyCatalog catalog, Duration limit,
            ExecutorService pool, ScheduledExecutorService watchdog) {
        var s = new ZoneMatrixRunService(runs, m.versions, catalog, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), limit,
                pool, watchdog);
        services.add(s);
        return s;
    }

    private ZoneMatrixRunService direct(Matrix m, FakeRuns runs) {
        return service(m, runs, m.catalog(), Duration.ofMinutes(5), new DirectExecutor(),
                ZoneMatrixRunService.newWatchdog());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void zm39_failureMidWriteLeavesNoVisibleFindings() {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        var service = direct(m, runs);

        var first = service.submit(POLICY, null);
        var before = runs.latestComplete(POLICY).orElseThrow();
        assertEquals(first.runId(), before.runId());
        List<ZoneMatrixRunRepository.Finding> visible = runs.findingsOf(first.runId());
        assertEquals(2, visible.size());

        runs.failNextComplete = true;
        var second = service.submit(POLICY, null);
        assertFalse(second.alreadyRunning());
        FakeRuns.Row failed = runs.rows.get(second.runId());
        assertEquals("FAILED", failed.state);
        assertEquals("STORE_ERROR", failed.failureReason);
        assertEquals(List.of(), runs.findingsOf(second.runId()), "a failed run never shows findings");
        assertEquals(0, runs.findings.getOrDefault(second.runId(), List.of()).size(), "nothing of the rolled-back write stays");
        assertEquals(before, runs.latestComplete(POLICY).orElseThrow(), "the last COMPLETE run is unchanged");
        assertEquals(visible, runs.findingsOf(first.runId()));
    }

    @Test
    void zm39_timeLimitEndsRunFailedWithoutFindings() throws Exception {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        PolicyCatalog slow = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return List.of(); }
            @Override public Optional<PolicySnapshot> find(String policyId) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // the watchdog interrupted the run
                }
                return Optional.ofNullable(m.snapshots.get(policyId));
            }
        };
        var service = service(m, runs, slow, Duration.ofMillis(100), ZoneMatrixRunService.newPool(),
                ZoneMatrixRunService.newWatchdog());

        long t0 = System.nanoTime();
        var submission = service.submit(POLICY, null);
        service.close(); // waits for the run to end; the watchdog keeps running meanwhile
        assertTrue(System.nanoTime() - t0 < 1_500_000_000L, "the watchdog interrupts the run at its limit");
        FakeRuns.Row row = runs.rows.get(submission.runId());
        assertEquals("FAILED", row.state);
        assertEquals("TIME_LIMIT", row.failureReason);
        assertEquals(0, runs.completeCalls.get(), "a run over its limit never reaches the findings write");
        assertTrue(runs.findings.isEmpty());
        assertTrue(runs.latestComplete(POLICY).isEmpty());
    }

    @Test
    void zm39_evaluationThatOutlivesItsWatchdogWritesNothing() {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        var service = direct(m, runs);
        String runId = service.submit(POLICY, null).runId();
        // A run failed by the time limit (or by startup recovery) refuses the late completion: nothing is written.
        assertFalse(runs.complete(runId, NOW, 1L, new ZoneMatrixRunRepository.Counts(0, 0, 0, 0, 0, 0),
                new ZoneMatrixRunRepository.Coverage("COMPLETE", List.of(), 0, true, "PAN", null, 0), List.of()));
    }

    @Test
    void secondSubmitForRunningPolicyReturnsSameRun() {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        var held = new HoldingExecutor();
        var service = service(m, runs, m.catalog(), Duration.ofMinutes(5), held, ZoneMatrixRunService.newWatchdog());

        var first = service.submit(POLICY, null);
        var second = service.submit(" " + POLICY + " ", null);
        assertFalse(first.alreadyRunning());
        assertTrue(second.alreadyRunning());
        assertEquals(first.runId(), second.runId());
        assertEquals(1, runs.rows.size(), "never a parallel run");
        assertEquals(1, held.tasks.size());

        // A run another pod holds (RUNNING in the database) is returned too.
        String other = "pol-" + "0042-ab"; // differs by case only: a different opaque id (identity law)
        runs.rows.put("00000000-0000-4000-8000-000000000099", FakeRuns.Row.running(
                "00000000-0000-4000-8000-000000000099", other, m.versionId));
        var third = service.submit(other, null);
        assertTrue(third.alreadyRunning());
        assertEquals("00000000-0000-4000-8000-000000000099", third.runId());

        held.runAll();
        assertEquals("COMPLETE", runs.rows.get(first.runId()).state);
        assertFalse(service.submit(POLICY, null).alreadyRunning(), "a finished run frees the policy");
    }

    @Test
    void atMostTwoRunsInFlight() throws Exception {
        assertEquals(2, ZoneMatrixRunService.THREADS);
        var m = new Matrix();
        var runs = new FakeRuns();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoEntered = new CountDownLatch(2);
        PolicyCatalog gated = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return List.of(); }
            @Override public Optional<PolicySnapshot> find(String policyId) {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                twoEntered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                return Optional.of(snapshot(policyId));
            }
        };
        var service = service(m, runs, gated, Duration.ofMinutes(5), ZoneMatrixRunService.newPool(),
                ZoneMatrixRunService.newWatchdog());
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) ids.add(service.submit("pol-" + i, null).runId());
        assertTrue(twoEntered.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(2, peak.get(), "the own pool runs at most two evaluations at once");
        release.countDown();
        service.close();
        assertEquals(2, peak.get());
        for (String id : ids) assertEquals("COMPLETE", runs.rows.get(id).state);
    }

    @Test
    void zm39_startupMarksRunningAsInterrupted() {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        var service = direct(m, runs);
        String complete = service.submit(POLICY, null).runId();
        runs.rows.put("00000000-0000-4000-8000-000000000001",
                FakeRuns.Row.running("00000000-0000-4000-8000-000000000001", "pol-a", m.versionId));
        runs.rows.put("00000000-0000-4000-8000-000000000002",
                FakeRuns.Row.running("00000000-0000-4000-8000-000000000002", "pol-b", m.versionId));

        assertEquals(2, service.recoverInterrupted());
        for (String id : List.of("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002")) {
            assertEquals("FAILED", runs.rows.get(id).state);
            assertEquals("INTERRUPTED_AT_STARTUP", runs.rows.get(id).failureReason);
            assertEquals(List.of(), runs.findingsOf(id));
        }
        assertEquals("COMPLETE", runs.rows.get(complete).state);
        assertTrue(runs.latestComplete("pol-a").isEmpty(), "an interrupted run is never read");
        assertEquals(0, service.recoverInterrupted());
    }

    @Test
    void onlyNonCompliantOutcomesStoredCountsForAll() {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        var service = direct(m, runs);

        String runId = service.submit(POLICY, null).runId();
        var run = runs.latestComplete(POLICY).orElseThrow();
        assertEquals(runId, run.runId());
        assertEquals(m.versionId, run.versionId());
        assertEquals(m.versionNumber, run.versionNumber());
        assertEquals(m.contentSha256, run.contentSha256());
        assertEquals(ZoneMatrixEvaluator.ENGINE_VERSION, run.engineVersion());
        assertEquals(new ZoneMatrixRunRepository.Counts(1, 1, 1, 0, 1, 2), run.counts());
        assertEquals(Instant.parse("2026-11-23T08:00:00Z"), run.snapshotCollectedAt());
        assertEquals(4, run.snapshotRuleCount());

        List<ZoneMatrixRunRepository.Finding> findings = runs.findingsOf(runId);
        assertEquals(List.of("VIOLATION", "NOT_EVALUABLE"), findings.stream().map(ZoneMatrixRunRepository.Finding::outcome).toList());
        var violation = findings.get(0);
        assertEquals(POLICY, violation.policySourceId(), "the opaque policy id is kept byte-exact");
        assertEquals("uid-r2", violation.ruleUid());
        assertEquals(1, violation.violatedCells().size());
        assertEquals(List.of("tcp/22"), violation.violatedCells().get(0).notAllowedServices());
        assertNotNull(violation.violatedCells().get(0).witness(), "the witness tuple is stored as the engine returns it");
        assertEquals(1, violation.violatedPairs().size());
        for (var f : findings) {
            assertEquals(NOW, f.firstObservedAt());
            assertEquals(NOW, f.lastObservedAt());
            assertNull(f.firstObservedAnyVersion());
            assertNull(f.resolvedAt());
            assertNull(f.resolutionReason());
        }
        assertNotNull(findings.get(1).reason());
    }

    @Test
    void refusesWithoutPublishedVersionOrPolicy() {
        var runs = new FakeRuns();
        var empty = new ZoneMatrixServiceTest.Memory();
        var service = new ZoneMatrixRunService(runs, empty, null, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30), new DirectExecutor(), ZoneMatrixRunService.newWatchdog());
        services.add(service);
        assertEquals("INVALID_POLICY", assertThrows(Refused.class, () -> service.submit(" ", null)).code);
        assertEquals("NO_ACTIVE_VERSION", assertThrows(Refused.class, () -> service.submit(POLICY, null)).code);
        assertEquals("NOT_FOUND", assertThrows(Refused.class, () -> service.submit(POLICY, "no-such-version")).code);
        assertTrue(runs.rows.isEmpty());

        var m = new Matrix();
        var missing = direct(m, runs);
        String runId = missing.submit("pol-not-stored", null).runId();
        assertEquals("FAILED", runs.rows.get(runId).state);
        assertEquals("SNAPSHOT_MISSING", runs.rows.get(runId).failureReason);
    }

    @Test
    void fullQueueEndsTheNewRunFailedPoolSaturated() {
        var m = new Matrix();
        var runs = new FakeRuns();
        ExecutorService refusing = new DirectExecutor() {
            @Override public void execute(Runnable command) { throw new RejectedExecutionException("full"); }
        };
        var service = service(m, runs, m.catalog(), Duration.ofSeconds(30), refusing, ZoneMatrixRunService.newWatchdog());
        var submission = service.submit(POLICY, null);
        assertEquals("FAILED", submission.state());
        assertEquals("POOL_SATURATED", runs.rows.get(submission.runId()).failureReason);
        assertFalse(service.submit(POLICY, null).alreadyRunning(), "a refused run does not hold the policy");
    }

    @Test
    void zm10_logsCarryIdsAndCountsOnly() {
        var m = new Matrix();
        m.snapshots.put(POLICY, snapshot(POLICY));
        var runs = new FakeRuns();
        Logger logger = (Logger) LoggerFactory.getLogger(ZoneMatrixRunService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var service = direct(m, runs);
            String ok = service.submit(POLICY, null).runId();
            runs.failNextComplete = true;
            service.submit(POLICY, null);
            service.submit("pol-not-stored", null);
            runs.rows.put("00000000-0000-4000-8000-000000000003",
                    FakeRuns.Row.running("00000000-0000-4000-8000-000000000003", "pol-c", m.versionId));
            service.recoverInterrupted();

            List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("zone_matrix_run_finished") && l.contains("run_id=" + ok)
                    && l.contains("version_number=" + m.versionNumber) && l.contains("content_sha256=" + m.contentSha256)
                    && l.contains("violation=1") && l.contains("findings=2")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("failure_reason=STORE_ERROR")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("failure_reason=SNAPSHOT_MISSING")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.equals("zone_matrix_runs_interrupted_at_startup count=1")), lines.toString());
            for (String line : lines) {
                for (String planted : List.of("ZONE-ALPHA", "ZONE-BRAVO", "192.0.2.", "198.51.100.", "PLANTED-",
                        "FW-TANGO-04", "FW-JULIET-06", "DOM-BRAVO-02", "tcp/22", "web tier", "injected")) {
                    assertFalse(line.contains(planted), "log line carries " + planted + ": " + line);
                }
            }
            for (ILoggingEvent event : appender.list) assertNull(event.getThrowableProxy(), "no stack trace or message");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------ fakes

    /** Runs every task on the calling thread. */
    static class DirectExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    /** Holds tasks until {@link #runAll()}. */
    static final class HoldingExecutor extends DirectExecutor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runAll() { tasks.forEach(Runnable::run); tasks.clear(); }
    }

    /** In-memory runs with the database's guarantees: one RUNNING per policy, all-or-nothing completion. */
    static final class FakeRuns implements ZoneMatrixRunRepository {
        static final class Row {
            String runId, policy, versionId, contentSha256, engineVersion, state, failureReason;
            int versionNumber;
            Instant startedAt, finishedAt;
            Counts counts;
            Coverage coverage;
            long duration;

            static Row running(String runId, String policy, String versionId) {
                Row r = new Row();
                r.runId = runId; r.policy = policy; r.versionId = versionId; r.versionNumber = 1;
                r.contentSha256 = "0".repeat(64); r.engineVersion = "test"; r.state = STATE_RUNNING; r.startedAt = NOW;
                return r;
            }

            Run toRun() {
                return new Run(runId, versionId, versionNumber, contentSha256, policy, engineVersion, state, failureReason,
                        startedAt, finishedAt, coverage == null ? null : coverage.snapshotCollectedAt(),
                        coverage == null ? null : coverage.snapshotRuleCount(), coverage == null ? null : coverage.vendor(),
                        coverage == null ? null : coverage.evaluated(), coverage == null ? null : coverage.coverage(),
                        coverage == null ? List.of() : coverage.itemCodes(),
                        coverage == null ? null : coverage.collectionFailures(), counts, duration);
            }
        }

        final Map<String, Row> rows = new LinkedHashMap<>();
        final Map<String, List<Finding>> findings = new LinkedHashMap<>();
        final AtomicInteger completeCalls = new AtomicInteger();
        volatile boolean failNextComplete;

        @Override public synchronized void start(NewRun run) {
            Optional<String> running = runningRunOf(run.policySourceId());
            if (running.isPresent()) throw new RunningRunExists(running.get());
            Row r = new Row();
            r.runId = run.runId(); r.policy = run.policySourceId(); r.versionId = run.versionId();
            r.versionNumber = run.versionNumber(); r.contentSha256 = run.contentSha256();
            r.engineVersion = run.engineVersion(); r.state = STATE_RUNNING; r.startedAt = run.startedAt();
            rows.put(r.runId, r);
        }

        @Override public synchronized Optional<String> runningRunOf(String policySourceId) {
            return rows.values().stream().filter(r -> r.policy.equals(policySourceId) && STATE_RUNNING.equals(r.state))
                    .map(r -> r.runId).findFirst();
        }

        @Override public synchronized boolean complete(String runId, Instant finishedAt, long durationMillis,
                Counts counts, Coverage coverage, List<Finding> list) {
            completeCalls.incrementAndGet();
            Row r = rows.get(runId);
            if (r == null || !STATE_RUNNING.equals(r.state)) return false;
            List<Finding> staged = new ArrayList<>();
            for (Finding f : list) {
                staged.add(f);
                if (failNextComplete && staged.size() == 1) {
                    failNextComplete = false;
                    // The transaction rolls back: the staged rows are dropped and the run stays RUNNING.
                    throw new IllegalStateException("injected write failure");
                }
            }
            findings.put(runId, List.copyOf(staged));
            r.state = STATE_COMPLETE; r.finishedAt = finishedAt; r.counts = counts; r.coverage = coverage;
            r.duration = durationMillis;
            return true;
        }

        @Override public synchronized boolean fail(String runId, FailureReason reason, Instant finishedAt) {
            Row r = rows.get(runId);
            if (r == null || !STATE_RUNNING.equals(r.state)) return false;
            r.state = STATE_FAILED; r.failureReason = reason.name(); r.finishedAt = finishedAt;
            return true;
        }

        @Override public synchronized int failInterrupted(Instant finishedAt) {
            int n = 0;
            for (Row r : rows.values()) {
                if (STATE_RUNNING.equals(r.state)) {
                    r.state = STATE_FAILED; r.failureReason = FailureReason.INTERRUPTED_AT_STARTUP.name();
                    r.finishedAt = finishedAt;
                    n++;
                }
            }
            return n;
        }

        @Override public synchronized Optional<Run> latestComplete(String policySourceId) {
            Row latest = null;
            for (Row r : rows.values()) {
                if (r.policy.equals(policySourceId) && STATE_COMPLETE.equals(r.state)) latest = r;
            }
            return Optional.ofNullable(latest).map(Row::toRun);
        }

        @Override public synchronized List<Finding> findingsOf(String completeRunId) {
            Row r = rows.get(completeRunId);
            if (r == null || !STATE_COMPLETE.equals(r.state)) return List.of();
            return findings.getOrDefault(completeRunId, List.of());
        }

        @Override public synchronized List<Run> runs(int limit) {
            return rows.values().stream().map(Row::toRun).limit(Math.max(0, limit)).toList();
        }
    }
}
