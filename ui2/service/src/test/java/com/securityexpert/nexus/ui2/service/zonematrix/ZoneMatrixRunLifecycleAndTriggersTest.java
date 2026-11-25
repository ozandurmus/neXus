package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedByVersionFinding;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.boot.ZoneMatrixConfiguration;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.PolicyCoverage;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.DirectExecutor;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.FakeRuns;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.Reason;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.StalenessThreshold;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.Verdict;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Zone matrix delivery step 4b-2a unit tests: durable finding lifecycle, publish and completed-collection triggers,
 * and 24 h interim staleness / coverage / headline projections (ZONE_MATRIX_CONTRACT.md sections 5.1, 5.9, 7.1, 7.2,
 * 8.2, Amendment 2026-11-23; acceptance criteria ZM-8, ZM-10, ZM-25, ZM-26, ZM-34, ZM-35, ZM-36 second half, ZM-42).
 * Synthetic zone names, device pseudonyms and RFC 5737 documentation blocks only.
 */
class ZoneMatrixRunLifecycleAndTriggersTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");
    private static final PolicyObject OBJ_ANY =
            new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");
    private static final PolicySnapshot.Cell ANY_CELL = new PolicySnapshot.Cell(List.of("obj-any"), false);
    private static final Map<String, List<String>> ZONES_ANY = Map.of("from", List.of("any"), "to", List.of("any"));

    private final List<ZoneMatrixRunService> services = new ArrayList<>();

    @AfterEach void closeAll() { services.forEach(ZoneMatrixRunService::close); }

    // ------------------------------------------------------------------ fixture helpers

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant initial) { this.instant = initial; }
        void set(Instant next) { this.instant = next; }
        void advance(Duration d) { this.instant = this.instant.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class MutableCatalog implements PolicyCatalog {
        final List<Map<String, Object>> views = new ArrayList<>();
        final Map<String, PolicySnapshot> snapshots = new ConcurrentHashMap<>();
        volatile String failFindPolicyId;

        void putSnapshot(PolicySnapshot s) {
            snapshots.put(s.metadata().id(), s);
            views.removeIf(v -> s.metadata().id().equals(v.get("id")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.metadata().id());
            m.put("name", s.metadata().name());
            m.put("vendor", s.metadata().vendor());
            m.put("sourceId", s.metadata().sourceId());
            m.put("sourceName", s.metadata().sourceName());
            m.put("containerId", s.metadata().containerId());
            m.put("containerName", s.metadata().containerName());
            m.put("collectedAt", s.metadata().collectedAt());
            m.put("ruleCount", s.ruleCount());
            m.put("targets", List.of());
            views.add(m);
        }

        void putCatalogOnly(String id, String vendor, String name, String sourceId, String sourceName, String collectedAt) {
            views.removeIf(v -> id.equals(v.get("id")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("vendor", vendor);
            m.put("sourceId", sourceId);
            m.put("sourceName", sourceName);
            m.put("containerId", "cont-" + id);
            m.put("containerName", "DOM-BRAVO-02");
            m.put("collectedAt", collectedAt);
            m.put("ruleCount", 0);
            m.put("targets", List.of());
            views.add(m);
        }

        @Override public List<Map<String, Object>> catalogViews() { return List.copyOf(views); }

        @Override public Optional<PolicySnapshot> find(String policyId) {
            if (policyId.equals(failFindPolicyId)) {
                throw new IllegalStateException("PLANTED-SECRET-FAILURE-MESSAGE-192.0.2.99");
            }
            return Optional.ofNullable(snapshots.get(policyId));
        }
    }

    private static final class Env {
        final MutableClock clock = new MutableClock(T0);
        final ZoneMatrixServiceTest.Memory versions = new ZoneMatrixServiceTest.Memory();
        final MutableCatalog catalog = new MutableCatalog();
        final FakeRuns runs = new FakeRuns();
        final ZoneMatrixRunService runService;
        final ZoneMatrixService matrixService;
        String zoneAlphaId;
        String zoneBravoId;

        Env(List<ZoneMatrixRunService> registry) {
            this(registry, ZoneMatrixStaleness.INTERIM_PO_2026_11_23, new ZoneMatrixRunServiceTest.DirectExecutor());
        }

        Env(List<ZoneMatrixRunService> registry, StalenessThreshold threshold, ExecutorService executor) {
            this.runService = new ZoneMatrixRunService(runs, versions, catalog, MAPPER, clock,
                    Duration.ofMinutes(5), threshold, executor, ZoneMatrixRunService.newWatchdog());
            registry.add(this.runService);
            this.matrixService = new ZoneMatrixService(versions, new Principals() {
                @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
                @Override public boolean isRoot(String actor) { return false; }
            }, MAPPER, clock, catalog, null, null, null, runService::submitPublishedVersion);
        }

        /** Publishes v1: ZONE-ALPHA 192.0.2.0/25 -> ZONE-BRAVO 198.51.100.0/24 ALLOW_LISTED [tcp/443]. */
        String publishV1AllowHttpsOnly() {
            zoneAlphaId = (String) matrixService.createZone("security-1", null,
                    new ZoneInput("ZONE-ALPHA", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
            long rev = versions.draft().orElseThrow().revision();
            zoneBravoId = (String) matrixService.createZone("security-1", rev,
                    new ZoneInput("ZONE-BRAVO", "", "APPLICATION", List.of("198.51.100.0/24"))).get("zone_id");
            rev = versions.draft().orElseThrow().revision();
            matrixService.setCell("security-1", rev,
                    new CellInput(zoneAlphaId, zoneBravoId, "ALLOW_LISTED", List.of("tcp/443"), "CRITICAL", "https", ""));
            var draft = versions.draft().orElseThrow();
            return (String) matrixService.publish("compliance-1", false,
                    new PublishRequest(draft.revision(), draft.contentSha256(), null)).get("version_id");
        }

        String publishUpdatedCell(List<String> allowedServices, String rationale) {
            Long rev = versions.draft().map(com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version::revision).orElse(null);
            matrixService.setCell("security-1", rev,
                    new CellInput(zoneAlphaId, zoneBravoId, "ALLOW_LISTED", allowedServices, "CRITICAL", rationale, ""));
            var draft = versions.draft().orElseThrow();
            return (String) matrixService.publish("compliance-1", false,
                    new PublishRequest(draft.revision(), draft.contentSha256(), rationale)).get("version_id");
        }
    }

    private static PolicyObject addr(String id, String name, String cidr) {
        return new PolicyObject(id, name, "address", List.of(), List.of("ip-netmask: " + cidr), "RESOLVED");
    }

    private static PolicyObject svc(String id, String name, String proto, String port) {
        return new PolicyObject(id, name, "service", List.of(), List.of("protocol/" + proto + "/port: " + port), "RESOLVED");
    }

    private static Map<String, PolicyObject> baseObjects() {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("src-alpha", addr("src-alpha", "PLANTED-ALPHA-HOST", "192.0.2.16/28"));
        objects.put("dst-bravo", addr("dst-bravo", "PLANTED-BRAVO-HOST", "198.51.100.77/32"));
        objects.put("https", svc("https", "HTTPS", "tcp", "443"));
        objects.put("ssh", svc("ssh", "SSH", "tcp", "22"));
        return objects;
    }

    private static Rule panRule(String id, String uid, int number, String name, Boolean enabled,
            String src, String dst, String service, String action, Map<String, List<String>> extras) {
        return new Rule(id, uid, number, name, enabled,
                new PolicySnapshot.Cell(List.of(src), false),
                new PolicySnapshot.Cell(List.of(dst), false),
                new PolicySnapshot.Cell(List.of(service), false),
                ANY_CELL, action, "Log", "", extras);
    }

    /** Complete-coverage local PAN firewall policy (explicit intrazone-default deny + zero failures). */
    private static PolicySnapshot completeLocalPanSnapshot(String policyId, Instant collectedAt, List<Rule> rules) {
        return completeLocalPanSnapshot(policyId, collectedAt, rules, List.of());
    }

    private static PolicySnapshot completeLocalPanSnapshot(String policyId, Instant collectedAt, List<Rule> rules,
            List<CollectionFailure> failures) {
        Rule intraDeny = panRule("def-intra", "uid-def-intra", 1, "intrazone-default", true,
                "obj-any", "obj-any", "obj-any", "deny", Map.of("rule-type", List.of("intrazone")));
        return new PolicySnapshot(
                new Metadata(policyId, "src-" + policyId, "FW-TANGO-04", "PAN", "dom-1", "DOM-BRAVO-02",
                        "PLANTED-POLICY-" + policyId, collectedAt == null ? null : collectedAt.toString(), "art-1",
                        List.of(new Target("gw-1", "FW-TANGO-04", "vsys1", "IN_SYNC"))),
                List.of(
                        new Section("sec-local", "Local rules", "Local rules", null, rules),
                        new Section("sec-default", "Local default rules", "Local default rules", null, List.of(intraDeny))),
                baseObjects(),
                failures);
    }

    /** Panorama device-group snapshot: coverage is always INCOMPLETE (PAN_LOCAL_RULES_NOT_COLLECTED). */
    private static PolicySnapshot panoramaIncompleteSnapshot(String policyId, Instant collectedAt, List<Rule> rules) {
        return new PolicySnapshot(
                new Metadata(policyId, "src-pan", "FW-JULIET-06", "PAN", "dg-1", "DOM-BRAVO-02",
                        "PLANTED-DG-" + policyId, collectedAt.toString(), "art-pan",
                        List.of(new Target("gw-1", "FW-TANGO-04", "vsys1", "IN_SYNC"))),
                List.of(new Section(PolicySnapshot.ref(policyId, "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, rules)),
                baseObjects());
    }

    // ------------------------------------------------------------------ ZM-25

    @Test
    void zm25_togglingCoveredByEarlierDenyAcrossRunsPreservesFirstObservedAt() {
        var env = new Env(services);
        env.publishV1AllowHttpsOnly();

        String pol = "pol-zm25";
        Rule violating = panRule("r-viol", "uid-viol", 2, "PLANTED-SSH-ALLOW", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        Rule earlierDeny = panRule("r-deny", "uid-deny", 1, "PLANTED-SSH-DENY", true,
                "src-alpha", "dst-bravo", "ssh", "deny", ZONES_ANY);

        // Run 1 at T0: uncovered violation.
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, T0, List.of(violating)));
        String run1 = env.runService.submit(pol, null).runId();
        var f1 = env.runs.findingsOf(run1).get(0);
        assertEquals("VIOLATION", f1.outcome());
        assertFalse(f1.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals(T0, f1.firstObservedAt());
        assertEquals(T0, f1.firstObservedAnyVersion());
        assertEquals(T0, f1.lastObservedAt());

        // Run 2 at T0+1h: earlier deny covers the violating rule -> flag ON, firstObservedAt stays T0.
        Instant t1 = T0.plus(Duration.ofHours(1));
        env.clock.set(t1);
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, t1, List.of(earlierDeny, violating)));
        String run2 = env.runService.submit(pol, null).runId();
        var f2 = env.runs.findingsOf(run2).get(0);
        assertEquals("VIOLATION", f2.outcome());
        assertTrue(f2.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals("r-deny", f2.coveringRuleId());
        assertEquals(T0, f2.firstObservedAt(), "toggling COVERED_BY_EARLIER_DENY on keeps firstObservedAt");
        assertEquals(T0, f2.firstObservedAnyVersion());
        assertEquals(t1, f2.lastObservedAt());

        // Run 3 at T0+2h: earlier deny removed -> flag OFF again, firstObservedAt still T0.
        Instant t2 = T0.plus(Duration.ofHours(2));
        env.clock.set(t2);
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, t2, List.of(violating)));
        String run3 = env.runService.submit(pol, null).runId();
        var f3 = env.runs.findingsOf(run3).get(0);
        assertFalse(f3.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertNull(f3.coveringRuleId());
        assertEquals(T0, f3.firstObservedAt(), "toggling COVERED_BY_EARLIER_DENY off keeps firstObservedAt");
        assertEquals(T0, f3.firstObservedAnyVersion());
        assertEquals(t2, f3.lastObservedAt());
    }

    // ------------------------------------------------------------------ ZM-34

    @Test
    void zm34_violationTurningUndecidedStaysOpenWithBecameUndecidedAndPriorLastObservedAt() {
        var env = new Env(services);
        env.publishV1AllowHttpsOnly();

        String pol = "pol-zm34-undecided";
        Rule violating = panRule("r1", "uid-r1", 1, "PLANTED-RULE", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, T0, List.of(violating)));
        String run1 = env.runService.submit(pol, null).runId();
        var f1 = env.runs.findingsOf(run1).get(0);
        assertEquals("VIOLATION", f1.outcome());
        assertEquals(1, f1.violatedPairs().size());
        assertEquals(1, f1.violatedCells().size());

        // Run 2 at T1: rule now references an unresolved object -> NOT_EVALUABLE, stays OPEN with BECAME_UNDECIDED.
        Instant t1 = T0.plus(Duration.ofHours(2));
        env.clock.set(t1);
        Rule unresolved = panRule("r1", "uid-r1", 1, "PLANTED-RULE", true,
                "missing-obj", "dst-bravo", "ssh", "allow", ZONES_ANY);
        Rule standaloneUnresolved = panRule("r2", "uid-r2", 2, "PLANTED-OTHER", true,
                "missing-obj", "dst-bravo", "https", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, t1, List.of(unresolved, standaloneUnresolved)));
        String run2 = env.runService.submit(pol, null).runId();
        List<ZoneMatrixRunRepository.Finding> run2Findings = env.runs.findingsOf(run2);
        assertEquals(2, run2Findings.size(), "open BECAME_UNDECIDED + standalone NOT_EVALUABLE without duplication");

        var became = run2Findings.stream().filter(f -> "uid-r1".equals(f.ruleUid())).findFirst().orElseThrow();
        assertEquals("NOT_EVALUABLE", became.outcome());
        assertEquals("unresolved object", became.reason());
        assertTrue(became.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED));
        assertEquals(f1.violatedPairs(), became.violatedPairs(), "carries prior violatedPairs");
        assertEquals(f1.violatedCells(), became.violatedCells(), "carries prior violatedCells");
        assertEquals(T0, became.firstObservedAt());
        assertEquals(T0, became.firstObservedAnyVersion());
        assertEquals(T0, became.lastObservedAt(), "undecided is not a reproduction; lastObservedAt stays T0");
        assertNull(became.resolvedAt());
        assertNull(env.runs.findingsOf(run1).get(0).resolvedAt(), "prior open row was not resolved");

        var standalone = run2Findings.stream().filter(f -> "uid-r2".equals(f.ruleUid())).findFirst().orElseThrow();
        assertEquals(List.of(), standalone.violatedPairs());
        assertFalse(standalone.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED));
        assertEquals(t1, standalone.firstObservedAt());

        // Run 3 at T2: rule turns UNKNOWN via planCompletion directly (verifying UNKNOWN branch as well).
        Instant t2 = T0.plus(Duration.ofHours(3));
        var unknownRule = new ZoneMatrixEvaluator.RuleEvaluation(
                new ZoneMatrixEvaluator.FindingIdentity(pol, "uid-r1", List.of(), List.of()),
                "r1", "uid-r1", 1, "PLANTED-RULE", "sec-local", "Local rules",
                List.of(), List.of("FW-TANGO-04"), List.of(), List.of(),
                "UNKNOWN", "analysis budget", null, Set.of(), null, List.of(), List.of(), List.of(), "original");
        var synthEval = new ZoneMatrixEvaluator.EvaluationResult(
                ZoneMatrixEvaluator.ENGINE_VERSION,
                new ZoneMatrixEvaluator.VersionCitation(env.versions.active().orElseThrow().versionId(), 1, "a".repeat(64)),
                pol, "P", "src", "FW-TANGO-04", "dom", "DOM", "PAN", t2.toString(), List.of(),
                true, null, "COMPLETE", List.of(), 0, Map.of("UNKNOWN", 1),
                new ZoneMatrixEvaluator.NotApplicableSummary(0, 0, 0, 0, 0, List.of()), null,
                List.of(unknownRule), List.of(), false, 1L, 0L);
        var planUnknown = ZoneMatrixRunService.planCompletion(pol, synthEval,
                new ZoneMatrixRunRepository.PriorHistory(run2, List.of(
                        new ZoneMatrixRunRepository.PriorFinding(1L, run1, env.versions.active().orElseThrow().versionId(),
                                1, T0, true, env.runs.findingsOf(run1).get(0)),
                        new ZoneMatrixRunRepository.PriorFinding(2L, run2, env.versions.active().orElseThrow().versionId(),
                                1, t1, true, became))),
                t2);
        assertTrue(planUnknown.resolutions().isEmpty());
        assertEquals("UNKNOWN", planUnknown.findingsToInsert().get(0).outcome());
        assertEquals("analysis budget", planUnknown.findingsToInsert().get(0).reason());
        assertTrue(planUnknown.findingsToInsert().get(0).flags().contains("BECAME_UNDECIDED"));
        assertEquals(T0, planUnknown.findingsToInsert().get(0).lastObservedAt());

        // Run 4 at T3: object resolved again and rule still violates -> BECAME_UNDECIDED cleared, lastObservedAt = t3.
        Instant t3 = T0.plus(Duration.ofHours(4));
        env.clock.set(t3);
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, t3, List.of(violating)));
        String run4 = env.runService.submit(pol, null).runId();
        var reproduced = env.runs.findingsOf(run4).get(0);
        assertEquals("VIOLATION", reproduced.outcome());
        assertFalse(reproduced.flags().contains(ZoneMatrixFindingLifecycle.FLAG_BECAME_UNDECIDED));
        assertEquals(T0, reproduced.firstObservedAt());
        assertEquals(t3, reproduced.lastObservedAt());
    }

    @Test
    void zm34_incompleteSnapshotsCarryOpenFindingsForwardAndNeverResolveMissingOrCompliantRules() {
        var env = new Env(services);
        env.publishV1AllowHttpsOnly();

        String polFailures = "pol-zm34-failures";
        Rule violatingA = panRule("r-a", "uid-a", 1, "PLANTED-A", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        Rule violatingB = panRule("r-b", "uid-b", 2, "PLANTED-B", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot(polFailures, T0, List.of(violatingA, violatingB)));
        String run1 = env.runService.submit(polFailures, null).runId();
        assertEquals(2, env.runs.findingsOf(run1).size());

        // Run 2: snapshot has collection failure (failureCount = 1), rule A missing, rule B changed to https (compliant).
        Instant t1 = T0.plus(Duration.ofHours(1));
        env.clock.set(t1);
        Rule compliantB = panRule("r-b", "uid-b", 2, "PLANTED-B", true,
                "src-alpha", "dst-bravo", "https", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot(polFailures, t1, List.of(compliantB),
                List.of(new CollectionFailure("sec-2", "timeout"))));
        String run2 = env.runService.submit(polFailures, null).runId();
        List<ZoneMatrixRunRepository.Finding> carried = env.runs.findingsOf(run2);
        assertEquals(2, carried.size(), "both open findings carried forward when failureCount > 0");
        for (var f : carried) {
            assertEquals("VIOLATION", f.outcome());
            assertEquals(T0, f.firstObservedAt());
            assertEquals(T0, f.lastObservedAt(), "incomplete carry-forward does not advance lastObservedAt");
            assertNull(f.resolvedAt());
        }
        assertTrue(env.runs.findingsOf(run1).stream().allMatch(f -> f.resolvedAt() == null));

        // Panorama-sourced policy (always INCOMPLETE coverage): missing rule is also never resolved.
        String polPan = "pol-zm34-panorama";
        env.catalog.putSnapshot(panoramaIncompleteSnapshot(polPan, T0, List.of(violatingA)));
        env.clock.set(T0);
        env.runService.submit(polPan, null);
        env.clock.set(t1);
        env.catalog.putSnapshot(panoramaIncompleteSnapshot(polPan, t1, List.of()));
        String panRun2 = env.runService.submit(polPan, null).runId();
        assertEquals(1, env.runs.findingsOf(panRun2).size(), "Panorama INCOMPLETE coverage never resolves missing rules");
        assertEquals("uid-a", env.runs.findingsOf(panRun2).get(0).ruleUid());
    }

    // ------------------------------------------------------------------ ZM-36 second half & ZM-42

    @Test
    void zm36_and_zm42_resolutionReasonsVersionChangeWindowReappearanceAndByteExactIdentity() {
        var env = new Env(services);
        String v1 = env.publishV1AllowHttpsOnly();

        String pol = "pol-zm36-42";
        Rule rVersionResolved = panRule("r1", "uid-001-a", 1, "PLANTED-R1", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        Rule rRuleChanged = panRule("r2", "007", 2, "PLANTED-R2", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        Rule rRemoved = panRule("r3", "uid-003", 3, "PLANTED-R3", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);

        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, T0, List.of(rVersionResolved, rRuleChanged, rRemoved)));
        String run1 = env.runService.submit(pol, null).runId();
        assertEquals(3, env.runs.findingsOf(run1).size());

        // Under v1 at T0+1h: r2 changed to https (RULE_CHANGED), r3 removed (RULE_REMOVED_OR_DISABLED),
        // plus byte-different uids "UID-001-A" and "07" added (must not match "uid-001-a" or "007").
        Instant t1 = T0.plus(Duration.ofHours(1));
        env.clock.set(t1);
        Rule r2Fixed = panRule("r2", "007", 2, "PLANTED-R2", true,
                "src-alpha", "dst-bravo", "https", "allow", ZONES_ANY);
        Rule rCaseVariant = panRule("r4", "UID-001-A", 4, "PLANTED-R4", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        Rule rZeroVariant = panRule("r5", "07", 5, "PLANTED-R5", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot(pol, t1,
                List.of(rVersionResolved, r2Fixed, rCaseVariant, rZeroVariant)));
        String run2 = env.runService.submit(pol, null).runId();

        Map<String, ZoneMatrixRunRepository.Finding> run1AfterRun2 = new LinkedHashMap<>();
        env.runs.findingsOf(run1).forEach(f -> run1AfterRun2.put(f.ruleUid(), f));
        assertNull(run1AfterRun2.get("uid-001-a").resolvedAt(), "still violating");
        assertEquals("RULE_CHANGED", run1AfterRun2.get("007").resolutionReason());
        assertEquals(t1, run1AfterRun2.get("007").resolvedAt());
        assertEquals("RULE_REMOVED_OR_DISABLED", run1AfterRun2.get("uid-003").resolutionReason());
        assertEquals(t1, run1AfterRun2.get("uid-003").resolvedAt());

        Map<String, ZoneMatrixRunRepository.Finding> run2ByUid = new LinkedHashMap<>();
        env.runs.findingsOf(run2).forEach(f -> run2ByUid.put(f.ruleUid(), f));
        assertEquals(T0, run2ByUid.get("uid-001-a").firstObservedAnyVersion());
        assertEquals(t1, run2ByUid.get("UID-001-A").firstObservedAnyVersion(), "case-different uid never borrows T0");
        assertEquals(t1, run2ByUid.get("07").firstObservedAnyVersion(), "leading-zero-different uid never borrows T0");
        assertTrue(env.runService.resolvedByVersionChange(null).isEmpty());

        // Publish v2 at T0+2h loosening ALPHA->BRAVO to allow tcp/443 + tcp/22.
        // Note: publish automatically triggers evaluation against v2!
        Instant t2 = T0.plus(Duration.ofHours(2));
        env.clock.set(t2);
        String v2 = env.publishUpdatedCell(List.of("tcp/443", "tcp/22"), "temporary migration window");

        var latestAfterV2 = env.runs.latestComplete(pol).orElseThrow();
        assertEquals(v2, latestAfterV2.versionId());
        assertEquals(List.of(), env.runs.findingsOf(latestAfterV2.runId()), "all ssh rules now compliant under v2");

        List<ResolvedByVersionFinding> byVersion = env.runService.resolvedByVersionChange(null);
        assertEquals(3, byVersion.size(), "uid-001-a (counted once despite 2 runs), UID-001-A and 07 resolved by v2");
        assertTrue(byVersion.stream().allMatch(r -> v1.equals(r.resolvedFromVersionId())
                && v2.equals(r.resolvedInVersionId())
                && t2.equals(r.resolvedAt())));
        assertEquals(List.of(v2), List.copyOf(env.runService.resolvedByVersionChangeByVersion().keySet()));
        assertEquals(3, env.runService.storedSummary().resolvedByVersionChangeLast7Days().size());

        // Advance 8 days -> outside the 7-day Overview window, but still in per-version history.
        Instant t3 = t2.plus(Duration.ofDays(8));
        env.clock.set(t3);
        assertTrue(env.runService.storedSummary().resolvedByVersionChangeLast7Days().isEmpty());
        assertEquals(3, env.runService.storedSummary().resolvedByVersionChangeByVersion().get(v2).size());

        // Publish v3 tightening ALPHA->BRAVO back to tcp/443 only -> violation reappears with new firstObservedAt = t3
        // and preserved firstObservedAnyVersion = T0 (ZM-42)!
        String v3 = env.publishUpdatedCell(List.of("tcp/443"), null);
        var latestAfterV3 = env.runs.latestComplete(pol).orElseThrow();
        assertEquals(v3, latestAfterV3.versionId());
        Map<String, ZoneMatrixRunRepository.Finding> runV3ByUid = new LinkedHashMap<>();
        env.runs.findingsOf(latestAfterV3.runId()).forEach(f -> runV3ByUid.put(f.ruleUid(), f));
        var reappeared = runV3ByUid.get("uid-001-a");
        assertNotNull(reappeared);
        assertEquals(t3, reappeared.firstObservedAt(), "new finding instance gets t3 as firstObservedAt");
        assertEquals(T0, reappeared.firstObservedAnyVersion(), "ZM-42: preserves earliest v1 firstObservedAnyVersion");
        assertEquals(t3, reappeared.lastObservedAt());
    }

    // ------------------------------------------------------------------ ZM-8 triggers

    @Test
    void zm8_publishAndCompletedCollectionTriggersSubmitOnlyEligibleSnapshotsAndSkipFailedDuplicates() {
        var env = new Env(services);
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-pan-1", T0, List.of()));
        env.catalog.putCatalogOnly("pol-asa-1", "ASA", "ASA-FW-01", "dev-asa-1", "FW-BRAVO-02", T0.toString());
        env.catalog.putCatalogOnly("pol-fgt-1", "FGT", "FGT-FW-01", "dev-fgt-1", "FW-TANGO-04", T0.toString());
        env.catalog.putCatalogOnly("pol-unparsed", "CP", "CP-NO-TS", "dev-cp-1", "FW-JULIET-06", "not-a-timestamp");

        // Before any active version exists, submitCompletedCollections does nothing.
        assertEquals(List.of(), env.runService.submitCompletedCollections());
        assertTrue(env.runs.rows.isEmpty());

        // Publishing v1 triggers evaluation for CP/PAN policies only (skips ASA/FGT; pol-unparsed fails SNAPSHOT_MISSING).
        String v1 = env.publishV1AllowHttpsOnly();
        assertTrue(env.runs.latestComplete("pol-pan-1").isPresent());
        assertTrue(env.runs.latestRun("pol-asa-1").isEmpty(), "ASA is never submitted");
        assertTrue(env.runs.latestRun("pol-fgt-1").isEmpty(), "FGT is never submitted");

        int runsAfterPublish = env.runs.rows.size();
        // Polling again with unchanged collectedAt submits nothing.
        assertEquals(List.of(), env.runService.submitCompletedCollections());
        assertEquals(runsAfterPublish, env.runs.rows.size());

        // A new completed collection (new collectedAt) triggers exactly one new run.
        Instant t1 = T0.plus(Duration.ofMinutes(30));
        env.clock.set(t1);
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-pan-1", t1, List.of()));
        var subs = env.runService.submitCompletedCollections();
        assertEquals(1, subs.size());
        assertEquals(t1, env.runs.latestComplete("pol-pan-1").orElseThrow().snapshotCollectedAt());
        assertEquals(List.of(), env.runService.submitCompletedCollections(), "second poll on t1 is a no-op");

        // A snapshot whose evaluation FAILED on (v1, t2) is NOT retried every minute until collectedAt or version changes.
        Instant t2 = T0.plus(Duration.ofHours(1));
        env.clock.set(t2);
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-broken", t2, List.of()));
        env.catalog.failFindPolicyId = "pol-broken";
        var failedSub = env.runService.submitCompletedCollections();
        assertEquals(1, failedSub.size());
        var failedRun = env.runs.latestFinishedForVersion("pol-broken", v1).orElseThrow();
        assertEquals("FAILED", failedRun.state());
        assertEquals(t2, failedRun.snapshotCollectedAt());

        int countAfterFailure = env.runs.rows.size();
        assertEquals(List.of(), env.runService.submitCompletedCollections(), "failed (v1, t2) is not retried on next tick");
        assertEquals(countAfterFailure, env.runs.rows.size());

        // Once a newer completed collection t3 lands and the transient error clears, it runs once.
        Instant t3 = T0.plus(Duration.ofHours(2));
        env.clock.set(t3);
        env.catalog.failFindPolicyId = null;
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-broken", t3, List.of()));
        assertEquals(1, env.runService.submitCompletedCollections().size());
        assertEquals("COMPLETE", env.runs.latestFinishedForVersion("pol-broken", v1).orElseThrow().state());
    }

    @Test
    void publishSucceedsEvenWhenPoolIsSaturatedAndConfigurationTriggerRespectsSwitches() {
        ExecutorService refusing = new DirectExecutor() {
            @Override public void execute(Runnable command) { throw new RejectedExecutionException("full"); }
        };
        var env = new Env(services, ZoneMatrixStaleness.INTERIM_PO_2026_11_23, refusing);
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-sat-1", T0, List.of()));

        String v1 = env.publishV1AllowHttpsOnly();
        assertEquals(v1, env.versions.active().orElseThrow().versionId(), "POOL_SATURATED never rolls back publish");
        assertEquals("POOL_SATURATED", env.runs.latestRun("pol-sat-1").orElseThrow().failureReason());

        // ZoneMatrixConfiguration.CollectionPollTrigger runs only when both switches are true.
        AtomicInteger polls = new AtomicInteger();
        var countedCatalog = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() {
                polls.incrementAndGet();
                return List.of();
            }
            @Override public Optional<PolicySnapshot> find(String policyId) { return Optional.empty(); }
        };
        var probeService = new ZoneMatrixRunService(env.runs, env.versions, countedCatalog, MAPPER, env.clock,
                Duration.ofSeconds(30), new DirectExecutor(), ZoneMatrixRunService.newWatchdog());
        services.add(probeService);

        var config = new ZoneMatrixConfiguration();
        var offByDefaultScheduling = config.zoneMatrixCollectionPollTrigger(probeService, false, true);
        assertFalse(offByDefaultScheduling.isEnabled());
        offByDefaultScheduling.pollCompletedCollections();
        assertEquals(0, polls.get());

        var offByCollectionSwitch = config.zoneMatrixCollectionPollTrigger(probeService, true, false);
        assertFalse(offByCollectionSwitch.isEnabled());
        offByCollectionSwitch.pollCompletedCollections();
        assertEquals(0, polls.get());

        var enabledTrigger = config.zoneMatrixCollectionPollTrigger(probeService, true, true);
        assertTrue(enabledTrigger.isEnabled());
        enabledTrigger.pollCompletedCollections();
        assertEquals(1, polls.get());
    }

    // ------------------------------------------------------------------ ZM-26 & ZM-35

    @Test
    void zm26_and_zm35_stalenessProjectionUses24hInterimThresholdAndHeadlineNeverRendersExactZeroWhenImperfect() {
        var env = new Env(services);
        assertEquals(Duration.ofHours(24), env.runService.stalenessThreshold().value());
        assertEquals("interim (PO 2026-11-23) until the bank states it", env.runService.stalenessThreshold().source());

        // No active version -> headline is null, covered policies report VERSION_NOT_ACTIVE.
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-a", T0, List.of()));
        env.catalog.putCatalogOnly("pol-asa", "ASA", "ASA-01", "dev-asa", "FW-BRAVO-02", T0.toString());
        StoredSummary beforePublish = env.runService.storedSummary();
        assertNull(beforePublish.headline());
        assertEquals(1, beforePublish.notEvaluated().size());
        assertEquals("not evaluated: policy not collected", beforePublish.notEvaluated().get(0).reason());
        assertEquals(Verdict.STALE, beforePublish.policies().get(0).staleness().verdict());
        assertTrue(beforePublish.policies().get(0).staleness().reasons().contains(Reason.VERSION_NOT_ACTIVE));

        // Publish v1 -> pol-a evaluated, CURRENT and COMPLETE, 0 violations, 0 undecided -> exact "0"!
        env.publishV1AllowHttpsOnly();
        StoredSummary cleanZero = env.runService.storedSummary();
        assertEquals("interim (PO 2026-11-23) until the bank states it", cleanZero.stalenessThreshold().source());
        assertEquals(Verdict.CURRENT, cleanZero.policies().get(0).staleness().verdict());
        assertEquals("COMPLETE", cleanZero.policies().get(0).coverage());
        assertTrue(cleanZero.headline().exact());
        assertEquals("0", cleanZero.headline().countText());
        assertEquals("0 rules violate the zone matrix (v1)", cleanZero.headline().text());

        // Bank-supplied threshold parameter overrides the 24h interim threshold cleanly.
        env.clock.advance(Duration.ofHours(10));
        StalenessThreshold bankThreshold = new StalenessThreshold(Duration.ofHours(6), "bank policy 2026-12-01");
        StoredSummary bankAssessed = env.runService.storedSummary(bankThreshold);
        assertEquals(bankThreshold, bankAssessed.stalenessThreshold());
        assertEquals(Verdict.STALE, bankAssessed.policies().get(0).staleness().verdict());
        assertEquals(List.of(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD), bankAssessed.policies().get(0).staleness().reasons());
        assertFalse(bankAssessed.headline().exact());
        assertEquals("at least 0", bankAssessed.headline().countText());
        assertEquals("no proven violations in evaluated rules", bankAssessed.headline().text());

        // Under default 24h interim threshold: 10h is still CURRENT; advancing past 24h becomes STALE.
        assertEquals(Verdict.CURRENT, env.runService.storedSummary().policies().get(0).staleness().verdict());
        env.clock.set(T0.plus(Duration.ofHours(25)));
        StoredSummary over24h = env.runService.storedSummary();
        PolicyCoverage staleAge = over24h.policies().get(0);
        assertEquals(Verdict.STALE, staleAge.staleness().verdict());
        assertEquals(List.of(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD), staleAge.staleness().reasons());
        assertEquals(Duration.ofHours(25), staleAge.staleness().snapshotAge());
        assertFalse(over24h.headline().exact());
        assertEquals("at least 0", over24h.headline().countText());
        assertEquals("no proven violations in evaluated rules", over24h.headline().text());

        // Fresh snapshot with 1 NOT_EVALUABLE rule (CURRENT + COMPLETE, 0 violations) -> NEVER "0" (ZM-26)!
        Instant tFresh = env.clock.instant();
        Rule unresolved = panRule("r-unres", "uid-unres", 1, "PLANTED-UNRES", true,
                "missing-obj", "dst-bravo", "https", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-a", tFresh, List.of(unresolved)));
        env.runService.submit("pol-a", null);
        StoredSummary withNotEvaluable = env.runService.storedSummary();
        assertEquals(Verdict.CURRENT, withNotEvaluable.policies().get(0).staleness().verdict());
        assertEquals("COMPLETE", withNotEvaluable.policies().get(0).coverage());
        assertEquals(1, withNotEvaluable.headline().notEvaluableFindings());
        assertFalse(withNotEvaluable.headline().exact());
        assertEquals("at least 0", withNotEvaluable.headline().countText());
        assertEquals("no proven violations in evaluated rules", withNotEvaluable.headline().text());

        // Fresh snapshot with 1 headline violation + 1 outside-headline ordered-layer violation, then a FAILED run ->
        // LAST_RUN_FAILED marks policy STALE while keeping prior COMPLETE findings counted as "at least 1".
        Rule viol = panRule("r-v", "uid-v", 1, "PLANTED-VIOL", true,
                "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-a", tFresh, List.of(viol)));
        env.runService.submit("pol-a", null);
        // Inject an outside-headline finding on a second CURRENT + COMPLETE policy to verify exclusion.
        env.catalog.putSnapshot(completeLocalPanSnapshot("pol-b", tFresh, List.of()));
        String runB = UUID.randomUUID().toString();
        var active = env.versions.active().orElseThrow();
        env.runs.start(new ZoneMatrixRunRepository.NewRun(runB, active.versionId(), active.number(),
                active.contentSha256(), "pol-b", ZoneMatrixEvaluator.ENGINE_VERSION, tFresh, tFresh));
        var outsideFinding = new ZoneMatrixRunRepository.Finding("pol-b", "uid-outside", List.of(),
                List.of(env.zoneAlphaId + "->" + env.zoneBravoId), "r-out", 1, "PLANTED-OUT", "sec", "Network",
                List.of("FW-TANGO-04"), "VIOLATION", null, "CRITICAL",
                List.of("DENIED_BY_OTHER_ORDERED_LAYER"), null, List.of(), List.of(), "original",
                tFresh, tFresh, tFresh, null, null);
        env.runs.complete(runB, tFresh, 1L, new ZoneMatrixRunRepository.Counts(1, 0, 0, 0, 0, 1),
                new ZoneMatrixRunRepository.Coverage("COMPLETE", List.of(), 0, true, "CP", tFresh, 1),
                List.of(outsideFinding));

        StoredSummary exactOne = env.runService.storedSummary();
        assertTrue(exactOne.headline().exact());
        assertEquals(1, exactOne.headline().distinctViolatingRules(), "DENIED_BY_OTHER_ORDERED_LAYER excluded");
        assertEquals(1, exactOne.headline().outsideHeadlineOrderedLayer());
        assertEquals("1", exactOne.headline().countText());
        assertEquals("+1 outside the headline (other ordered layer denies or unknown)",
                exactOne.headline().outsideHeadlineText());

        // Now fail the next run of pol-a -> LAST_RUN_FAILED -> STALE -> "at least 1".
        env.runs.failNextComplete = true;
        env.runService.submit("pol-a", null);
        StoredSummary afterFailedRun = env.runService.storedSummary();
        PolicyCoverage polAAfterFail = afterFailedRun.policies().stream()
                .filter(p -> "pol-a".equals(p.policyId())).findFirst().orElseThrow();
        assertEquals(Verdict.STALE, polAAfterFail.staleness().verdict());
        assertEquals(List.of(Reason.LAST_RUN_FAILED), polAAfterFail.staleness().reasons());
        assertFalse(afterFailedRun.headline().exact());
        assertEquals("at least 1", afterFailedRun.headline().countText());
        assertTrue(afterFailedRun.headline().text().startsWith("at least 1 rule violates"));
    }

    // ------------------------------------------------------------------ ZM-10

    @Test
    void zm10_triggersAndLifecycleLogsNeverLeakZoneNamesBlocksAddressesDevicesOrRuleNames() {
        Logger runLogger = (Logger) LoggerFactory.getLogger(ZoneMatrixRunService.class);
        Logger matrixLogger = (Logger) LoggerFactory.getLogger(ZoneMatrixService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runLogger.addAppender(appender);
        matrixLogger.addAppender(appender);
        try {
            var env = new Env(services);
            Rule violating = panRule("r1", "uid-r1", 1, "PLANTED-SECRET-RULE", true,
                    "src-alpha", "dst-bravo", "ssh", "allow", ZONES_ANY);
            env.catalog.putSnapshot(completeLocalPanSnapshot("pol-log-1", T0, List.of(violating)));
            env.catalog.putSnapshot(completeLocalPanSnapshot("pol-log-fail", T0, List.of(violating)));
            env.catalog.failFindPolicyId = "pol-log-fail";

            env.publishV1AllowHttpsOnly();
            env.clock.advance(Duration.ofMinutes(10));
            env.catalog.putSnapshot(completeLocalPanSnapshot("pol-log-1", env.clock.instant(), List.of()));
            env.runService.submitCompletedCollections();

            List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertFalse(lines.isEmpty());
            for (String line : lines) {
                for (String forbidden : List.of(
                        "ZONE-ALPHA", "ZONE-BRAVO", "192.0.2.", "198.51.100.", "PLANTED-",
                        "FW-TANGO-04", "FW-JULIET-06", "DOM-BRAVO-02", "tcp/22", "tcp/443")) {
                    assertFalse(line.contains(forbidden), "ZM-10 violation (" + forbidden + "): " + line);
                }
            }
            for (ILoggingEvent ev : appender.list) {
                assertNull(ev.getThrowableProxy(), "logs must never attach throwable stack traces or messages");
            }
        } finally {
            runLogger.detachAppender(appender);
            matrixLogger.detachAppender(appender);
        }
    }
}
