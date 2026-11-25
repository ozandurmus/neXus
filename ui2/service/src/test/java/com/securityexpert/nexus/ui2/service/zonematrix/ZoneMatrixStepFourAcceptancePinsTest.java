package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyBulkhead;
import com.securityexpert.nexus.ui2.service.boot.ZoneMatrixConfiguration;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.DirectExecutor;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.FakeRuns;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step 4 acceptance pins for the clauses of {@code docs/design/ZONE_MATRIX_CONTRACT.md} (FROZEN, revision 2, section
 * 10.2) that the step-4 tests did not prove sentence by sentence: ZM-8 (trigger inputs), ZM-26 (distinct headline),
 * ZM-34 (became-undecided in the stored summary), ZM-36 (per-version listing and the 7-day window), ZM-39 (own pool,
 * an Error ends a run FAILED). Synthetic names and RFC 5737 documentation ranges only.
 */
class ZoneMatrixStepFourAcceptancePinsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final PolicyObject OBJ_ANY =
            new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");
    private static final PolicySnapshot.Cell ANY_CELL = new PolicySnapshot.Cell(List.of("obj-any"), false);
    private static final Map<String, List<String>> ZONES_ANY = Map.of("from", List.of("any"), "to", List.of("any"));

    private final List<ZoneMatrixRunService> services = new ArrayList<>();

    @AfterEach void closeAll() { services.forEach(ZoneMatrixRunService::close); }

    // ------------------------------------------------------------------------------------------ fixtures

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant initial) { this.instant = initial; }
        void set(Instant next) { this.instant = next; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class Catalog implements PolicyCatalog {
        final List<Map<String, Object>> views = new ArrayList<>();
        final Map<String, PolicySnapshot> snapshots = new ConcurrentHashMap<>();
        volatile RuntimeException failure;
        volatile Error error;

        void view(String id, String vendor, String collectedAt) {
            views.removeIf(v -> id.equals(v.get("id")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", "POLICY-" + id);
            m.put("vendor", vendor);
            m.put("sourceId", "src-" + id);
            m.put("sourceName", "FW-TANGO-04");
            m.put("containerId", "cont-" + id);
            m.put("containerName", "DOM-BRAVO-02");
            m.put("collectedAt", collectedAt);
            m.put("ruleCount", 0);
            views.add(m);
        }

        void putSnapshot(PolicySnapshot s) {
            snapshots.put(s.metadata().id(), s);
            view(s.metadata().id(), s.metadata().vendor(), s.metadata().collectedAt());
        }

        @Override public List<Map<String, Object>> catalogViews() { return List.copyOf(views); }

        @Override public Optional<PolicySnapshot> find(String policyId) {
            if (error != null) throw error;
            if (failure != null) throw failure;
            return Optional.ofNullable(snapshots.get(policyId));
        }
    }

    private static final class Env {
        final MutableClock clock = new MutableClock(NOW);
        final ZoneMatrixServiceTest.Memory versions = new ZoneMatrixServiceTest.Memory();
        final Catalog catalog = new Catalog();
        final FakeRuns runs = new FakeRuns();
        final ZoneMatrixRunService runService;
        final String versionId;
        String zoneAlphaId;
        String zoneBravoId;

        Env(List<ZoneMatrixRunService> registry, boolean publish) {
            runService = new ZoneMatrixRunService(runs, versions, catalog, MAPPER, clock, Duration.ofMinutes(5),
                    ZoneMatrixStaleness.INTERIM_PO_2026_11_23, new DirectExecutor(), ZoneMatrixRunService.newWatchdog());
            registry.add(runService);
            versionId = publish ? publishV1() : null;
        }

        /** Version 1: ZONE-ALPHA 192.0.2.0/25 to ZONE-BRAVO 198.51.100.0/24 allows tcp/443 only. */
        private String publishV1() {
            var service = new ZoneMatrixService(versions, new Principals() {
                @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
                @Override public boolean isRoot(String actor) { return false; }
            }, MAPPER, clock);
            zoneAlphaId = (String) service.createZone("security-1", null,
                    new ZoneInput("ZONE-ALPHA", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
            long rev = versions.draft().orElseThrow().revision();
            zoneBravoId = (String) service.createZone("security-1", rev,
                    new ZoneInput("ZONE-BRAVO", "", "APPLICATION", List.of("198.51.100.0/24"))).get("zone_id");
            rev = versions.draft().orElseThrow().revision();
            service.setCell("security-1", rev,
                    new CellInput(zoneAlphaId, zoneBravoId, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "web tier", ""));
            var draft = versions.draft().orElseThrow();
            return (String) service.publish("compliance-1", false,
                    new PublishRequest(draft.revision(), draft.contentSha256(), null)).get("version_id");
        }
    }

    private static PolicyObject addr(String id, String name, String cidr) {
        return new PolicyObject(id, name, "address", List.of(), List.of("ip-netmask: " + cidr), "RESOLVED");
    }

    private static PolicyObject svc(String id, String name, String proto, String port) {
        return new PolicyObject(id, name, "service", List.of(), List.of("protocol/" + proto + "/port: " + port), "RESOLVED");
    }

    private static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("src-alpha", addr("src-alpha", "OBJ-ALPHA", "192.0.2.16/28"));
        objects.put("dst-bravo", addr("dst-bravo", "OBJ-BRAVO", "198.51.100.77/32"));
        objects.put("https", svc("https", "HTTPS", "tcp", "443"));
        objects.put("ssh", svc("ssh", "SSH", "tcp", "22"));
        return objects;
    }

    private static Rule allowRule(String id, String uid, int number, String src, String service) {
        return new Rule(id, uid, number, "RULE-" + id, true, new PolicySnapshot.Cell(List.of(src), false),
                new PolicySnapshot.Cell(List.of("dst-bravo"), false), new PolicySnapshot.Cell(List.of(service), false),
                ANY_CELL, "allow", "Log", "", ZONES_ANY);
    }

    /** Complete-coverage local PAN firewall policy: a local rulebase, an explicit intrazone deny and zero failures. */
    private static PolicySnapshot completeLocalPan(String policyId, Instant collectedAt, List<Rule> rules) {
        Rule intraDeny = new Rule("def-intra", "uid-def-intra", 1, "intrazone-default", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "deny", "Log", "", Map.of("rule-type", List.of("intrazone")));
        return new PolicySnapshot(
                new Metadata(policyId, "src-" + policyId, "FW-TANGO-04", "PAN", "dom-1", "DOM-BRAVO-02",
                        "POLICY-" + policyId, collectedAt.toString(), "art-1",
                        List.of(new Target("gw-1", "FW-TANGO-04", "vsys1", "IN_SYNC"))),
                List.of(new Section("sec-local", "Local rules", "Local rules", null, rules),
                        new Section("sec-default", "Local default rules", "Local default rules", null, List.of(intraDeny))),
                objects(),
                List.of());
    }

    private static ZoneMatrixRunRepository.Finding stored(String policy, String uid, List<String> parentPath,
            String outcome, List<String> flags, Instant observed, Instant resolvedAt, String resolutionReason) {
        return new ZoneMatrixRunRepository.Finding(policy, uid, parentPath, List.of("ZONE-1->ZONE-2"), "r-" + uid, 1,
                "RULE-" + uid, "sec-1", "SECTION-1", List.of("FW-TANGO-04"), outcome, null, "HIGH", flags, null,
                List.of(), List.of(), "original", observed, observed, observed, resolvedAt, resolutionReason);
    }

    /** One COMPLETE run of {@code policy} against {@code version}, finished at {@code at}, holding {@code findings}. */
    private static String completeRun(Env env, String policy, String versionId, Instant at, String coverage,
            int failures, List<ZoneMatrixRunRepository.Finding> findings) {
        String runId = UUID.randomUUID().toString();
        env.runs.start(new ZoneMatrixRunRepository.NewRun(runId, versionId, 1, "0".repeat(64), policy,
                ZoneMatrixEvaluator.ENGINE_VERSION, at, at));
        assertTrue(env.runs.complete(runId, at, 1L, new ZoneMatrixRunRepository.Counts(findings.size(), 0, 0, 0, 0,
                        findings.size()),
                new ZoneMatrixRunRepository.Coverage(coverage, List.of(), failures, true, "PAN", at, 1), findings));
        return runId;
    }

    // ------------------------------------------------------------------------------------------ ZM-8

    @Test
    @DisplayName("ZM-8: only a stored CP or PAN snapshot with a parseable collection time starts a run, once per collection")
    void zm8_onlyCompletedStoredCollectionsStartARunAndTheTriggerReadsNothingElse() {
        // The trigger reads exactly two things from the policy store: the catalog of completed snapshots and one
        // snapshot by id. There is no checkpoint reader in the interface the run service is given.
        Set<String> catalogMethods = new TreeSet<>();
        for (Method m : PolicyCatalog.class.getDeclaredMethods()) catalogMethods.add(m.getName());
        assertEquals(Set.of("catalogViews", "find"), catalogMethods);

        var env = new Env(services, true);
        String collected = NOW.minus(Duration.ofMinutes(10)).toString();
        // Entries that carry no completed-collection time, or no run-capable vendor, or no id: never a run.
        env.catalog.view("pol-no-time", "PAN", null);
        env.catalog.view("pol-blank-time", "PAN", "  ");
        env.catalog.view("pol-bad-time", "CP", "not-a-timestamp");
        env.catalog.view("pol-asa", "ASA", collected);
        env.catalog.view("pol-fgt", "FGT", collected);
        env.catalog.view("  ", "PAN", collected);
        assertEquals(List.of(), env.runService.submitCompletedCollections());
        assertTrue(env.runs.rows.isEmpty(), "no run row was written for an entry that is not a completed collection");

        // A completed collection starts exactly one run; the same collection time starts none; a newer one starts one.
        env.catalog.putSnapshot(completeLocalPan("pol-done", NOW.minus(Duration.ofMinutes(10)), List.of()));
        assertEquals(1, env.runService.submitCompletedCollections().size());
        assertEquals(1, env.runs.rows.size());
        assertEquals(List.of(), env.runService.submitCompletedCollections());
        assertEquals(1, env.runs.rows.size());
        env.catalog.putSnapshot(completeLocalPan("pol-done", NOW.minus(Duration.ofMinutes(5)), List.of()));
        assertEquals(1, env.runService.submitCompletedCollections().size());
        assertEquals(2, env.runs.rows.size());
    }

    // ------------------------------------------------------------------------------------------ ZM-26

    @Test
    @DisplayName("ZM-26: the headline counts distinct (policy source, rule uid) on the active version only and is never exact when imperfect")
    void zm26_headlineCountsDistinctPolicySourceAndRuleUidOnTheActiveVersionOnly() {
        var env = new Env(services, true);
        String active = env.versionId;
        Instant collected = NOW.minus(Duration.ofHours(1));
        for (String id : List.of("pol-b1", "pol-b2")) env.catalog.view(id, "PAN", collected.toString());

        // pol-b1: one rule uid under two inline parents is one rule; a uid differing only by case is another rule.
        completeRun(env, "pol-b1", active, collected, "COMPLETE", 0, List.of(
                stored("pol-b1", "uid-x", List.of(), "VIOLATION", List.of(), collected, null, null),
                stored("pol-b1", "uid-x", List.of("parent-1"), "VIOLATION", List.of(), collected, null, null),
                stored("pol-b1", "UID-X", List.of(), "VIOLATION", List.of(), collected, null, null)));
        // pol-b2: the same uid in another policy source is a separate rule.
        completeRun(env, "pol-b2", active, collected, "COMPLETE", 0, List.of(
                stored("pol-b2", "uid-x", List.of(), "VIOLATION", List.of(), collected, null, null)));

        StoredSummary perfect = env.runService.storedSummary();
        assertEquals(3, perfect.headline().distinctViolatingRules(), "(b1,uid-x) once, (b1,UID-X), (b2,uid-x)");
        assertTrue(perfect.headline().exact());
        assertEquals("3", perfect.headline().countText());

        // An incompletely covered policy next to them: the count stays 3 but is "at least 3".
        env.catalog.view("pol-b4", "PAN", collected.toString());
        completeRun(env, "pol-b4", active, collected, "INCOMPLETE", 1, List.of());
        StoredSummary incomplete = env.runService.storedSummary();
        assertEquals(1, incomplete.headline().incompletePolicies());
        assertFalse(incomplete.headline().exact());
        assertEquals("at least 3", incomplete.headline().countText());

        // A policy whose last COMPLETE run is on an older version: its violation is not counted against the active
        // version, the policy is stale, and the count is "at least 3".
        env.catalog.views.removeIf(v -> "pol-b4".equals(v.get("id")));
        env.catalog.view("pol-b3", "PAN", collected.toString());
        completeRun(env, "pol-b3", "ver-older", collected.plusSeconds(1), "COMPLETE", 0, List.of(
                stored("pol-b3", "uid-old", List.of(), "VIOLATION", List.of(), collected, null, null)));
        StoredSummary older = env.runService.storedSummary();
        assertEquals(3, older.headline().distinctViolatingRules());
        assertEquals(1, older.headline().olderVersionPolicies());
        assertEquals(1, older.headline().stalePolicies());
        assertFalse(older.headline().exact());
        assertEquals("at least 3", older.headline().countText());
    }

    // ------------------------------------------------------------------------------------------ ZM-34

    @Test
    @DisplayName("ZM-34: a finding that became undecided is listed open and counted, and never lets the headline read exact")
    void zm34_becameUndecidedFindingIsListedCountedAndNeverMakesTheHeadlineExact() {
        var env = new Env(services, true);
        String policy = "pol-undecided";
        Instant t0 = NOW.minus(Duration.ofHours(3));
        env.catalog.putSnapshot(completeLocalPan(policy, t0, List.of(allowRule("r1", "uid-r1", 2, "src-alpha", "ssh"))));
        env.clock.set(t0);
        env.runService.submit(policy, null);
        env.clock.set(NOW);
        StoredSummary violating = env.runService.storedSummary();
        assertEquals(1, violating.headline().distinctViolatingRules());
        assertEquals(0, violating.headline().becameUndecidedFindings());

        // The same rule now references an object that was not collected: NOT_EVALUABLE.
        Instant t1 = NOW.minus(Duration.ofHours(1));
        env.catalog.putSnapshot(completeLocalPan(policy, t1,
                List.of(allowRule("r1", "uid-r1", 2, "missing-object", "ssh"))));
        env.clock.set(t1);
        env.runService.submit(policy, null);
        env.clock.set(NOW);

        StoredSummary undecided = env.runService.storedSummary();
        assertEquals("COMPLETE", undecided.policies().get(0).coverage(), "complete coverage, current snapshot");
        assertEquals(1, undecided.findings().size(), "the finding stays open and listed, it is not resolved");
        var finding = undecided.findings().get(0).finding();
        assertEquals("NOT_EVALUABLE", finding.outcome());
        assertTrue(finding.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED));
        assertNull(finding.resolvedAt());
        assertEquals(1, undecided.headline().becameUndecidedFindings());
        assertEquals(1, undecided.headline().notEvaluableFindings());
        assertFalse(undecided.headline().exact(), "only the undecided finding makes this headline inexact");
        assertTrue(undecided.headline().countText().startsWith("at least "));
    }

    // ------------------------------------------------------------------------------------------ ZM-36

    @Test
    @DisplayName("ZM-36: findings resolved by a version change are listed per resolving version, the Overview window is 7 days")
    void zm36_resolvedByVersionChangeIsListedPerVersionAndTheOverviewWindowIsSevenDays() {
        var env = new Env(services, true);
        Instant first = NOW.minus(Duration.ofDays(30));
        Instant resolvedV2 = NOW.minus(Duration.ofDays(7)).plusSeconds(60);   // inside the window
        Instant resolvedV3 = NOW.minus(Duration.ofDays(7)).minusSeconds(60);  // just outside the window

        // pol-a: resolved by v2; pol-b: resolved by v3 (two different resolving versions).
        completeRun(env, "pol-a", "ver-1", first, "COMPLETE", 0, List.of(
                stored("pol-a", "uid-1", List.of(), "VIOLATION", List.of(), first, resolvedV2, "MATRIX_VERSION_CHANGED")));
        completeRun(env, "pol-a", "ver-2", resolvedV2, "COMPLETE", 0, List.of());
        completeRun(env, "pol-b", "ver-1", first, "COMPLETE", 0, List.of(
                stored("pol-b", "uid-2", List.of(), "VIOLATION", List.of(), first, resolvedV3, "MATRIX_VERSION_CHANGED")));
        completeRun(env, "pol-b", "ver-3", resolvedV3, "COMPLETE", 0, List.of());
        // A rule that was removed is not a version change and is never listed.
        completeRun(env, "pol-c", "ver-1", first, "COMPLETE", 0, List.of(
                stored("pol-c", "uid-3", List.of(), "VIOLATION", List.of(), first, resolvedV2, "RULE_REMOVED_OR_DISABLED")));

        StoredSummary summary = env.runService.storedSummary();
        assertEquals(Set.of("ver-2", "ver-3"), summary.resolvedByVersionChangeByVersion().keySet());
        assertEquals(1, summary.resolvedByVersionChangeByVersion().get("ver-2").size());
        assertEquals(1, summary.resolvedByVersionChangeByVersion().get("ver-3").size());
        assertEquals(1, summary.resolvedByVersionChangeLast7Days().size(), "the one resolved less than 7 days ago");
        assertEquals("ver-2", summary.resolvedByVersionChangeLast7Days().get(0).resolvedInVersionId());
        assertEquals("ver-1", summary.resolvedByVersionChangeLast7Days().get(0).resolvedFromVersionId());
    }

    // ------------------------------------------------------------------------------------------ ZM-39

    @Test
    @DisplayName("ZM-39: the run pool is its own bounded pool (2 threads, queue 64) and depends on no PolicyBulkhead")
    void zm39_runPoolIsItsOwnBoundedPoolAndNothingOfItIsThePolicyBulkhead() throws Exception {
        ThreadPoolExecutor one = ZoneMatrixRunService.newPool();
        ThreadPoolExecutor two = ZoneMatrixRunService.newPool();
        try {
            assertNotSame(one, two, "every service builds its own pool");
            assertEquals(ZoneMatrixRunService.THREADS, one.getCorePoolSize());
            assertEquals(2, one.getMaximumPoolSize(), "at most two runs in total");
            assertEquals(ZoneMatrixRunService.QUEUE_CAPACITY, one.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, one.getRejectedExecutionHandler(),
                    "a full queue is refused, never run on the caller's (interactive) thread");
            Thread worker = one.getThreadFactory().newThread(() -> { });
            assertTrue(worker.isDaemon());
            assertTrue(worker.getName().startsWith("zone-matrix-run-"));
        } finally {
            one.shutdownNow();
            two.shutdownNow();
        }

        for (Field f : ZoneMatrixRunService.class.getDeclaredFields()) {
            assertNotEquals(PolicyBulkhead.class, f.getType(), "field " + f.getName());
        }
        for (Constructor<?> c : ZoneMatrixRunService.class.getDeclaredConstructors()) {
            assertFalse(Arrays.asList(c.getParameterTypes()).contains(PolicyBulkhead.class));
        }
        for (Method m : ZoneMatrixConfiguration.class.getDeclaredMethods()) {
            if (m.getReturnType() == ZoneMatrixRunService.class
                    || Arrays.asList(m.getParameterTypes()).contains(ZoneMatrixRunService.class)) {
                assertFalse(Arrays.asList(m.getParameterTypes()).contains(PolicyBulkhead.class),
                        m.getName() + " wires the run service without the bulkhead");
            }
        }
    }

    @Test
    @DisplayName("ZM-39: an Error inside a run (heap exhaustion included) ends the run FAILED and writes no finding")
    void zm39_anErrorInsideARunEndsItFailedAndNeverLeavesItRunningOrPartial() {
        var env = new Env(services, true);
        String policy = "pol-oom";
        env.catalog.putSnapshot(completeLocalPan(policy, NOW.minus(Duration.ofHours(1)),
                List.of(allowRule("r1", "uid-r1", 2, "src-alpha", "ssh"))));
        env.catalog.error = new OutOfMemoryError("synthetic");

        String runId = env.runService.submit(policy, null).runId();
        FakeRuns.Row row = env.runs.rows.get(runId);
        assertEquals("FAILED", row.state);
        assertEquals("ENGINE_ERROR", row.failureReason);
        assertEquals(0, env.runs.completeCalls.get(), "no findings write was attempted");
        assertTrue(env.runs.findings.isEmpty());
        assertTrue(env.runs.runningRunOf(policy).isEmpty(), "the policy is free for the next run");
        assertTrue(env.runs.latestComplete(policy).isEmpty(), "a failed run is never read");

        env.catalog.error = null;
        assertEquals("COMPLETE", env.runs.rows.get(env.runService.submit(policy, null).runId()).state);
    }
}
