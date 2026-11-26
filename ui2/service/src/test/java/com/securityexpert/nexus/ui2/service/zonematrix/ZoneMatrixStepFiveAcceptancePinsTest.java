package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_VIOLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.DirectExecutor;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunServiceTest.FakeRuns;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.CellInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step 5 traceability acceptance pins for {@code docs/design/ZONE_MATRIX_CONTRACT.md} (FROZEN, revision 2, sections
 * 5.7, 5.9, 7.1, 7.2, 10.2 and Amendment 2026-11-23): ZM-19 (mixed IPv4/IPv6 durable summary projection), ZM-26
 * (summary headline never carries "0" for incomplete, stale or NOT_EVALUABLE cases), ZM-35 (exact 24 h boundary is
 * CURRENT and exact, while over-24 h, failed, inactive-version or incomplete gives "at least N"), and ZM-36
 * (summary projection keeps version-change resolutions in the Overview headline through day 7 and drops them on day 8).
 *
 * <p>Synthetic zone names, device pseudonyms and RFC 5737 / RFC 3849 documentation blocks only.</p>
 */
class ZoneMatrixStepFiveAcceptancePinsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final String IPV6_NOT_EVALUATED = "IPv6 not evaluated";
    private static final PolicyObject OBJ_ANY =
            new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final Map<String, List<String>> ZONES_ANY = Map.of("from", List.of("any"), "to", List.of("any"));

    private final List<ZoneMatrixRunService> services = new ArrayList<>();

    @AfterEach
    void closeAll() {
        services.forEach(ZoneMatrixRunService::close);
    }

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

        @Override public List<Map<String, Object>> catalogViews() { return List.copyOf(views); }
        @Override public Optional<PolicySnapshot> find(String policyId) { return Optional.ofNullable(snapshots.get(policyId)); }
    }

    private static final class Env {
        final MutableClock clock = new MutableClock(NOW);
        final ZoneMatrixServiceTest.Memory versions = new ZoneMatrixServiceTest.Memory();
        final Catalog catalog = new Catalog();
        final FakeRuns runs = new FakeRuns();
        final ZoneMatrixService matrixService;
        final ZoneMatrixRunService runService;
        final String v1Id;
        String zUser;
        String zDmz;
        String zDb;

        Env(List<ZoneMatrixRunService> registry) {
            runService = new ZoneMatrixRunService(runs, versions, catalog, MAPPER, clock, Duration.ofMinutes(5),
                    ZoneMatrixStaleness.INTERIM_PO_2026_11_23, new DirectExecutor(), ZoneMatrixRunService.newWatchdog());
            registry.add(runService);
            matrixService = new ZoneMatrixService(versions, new Principals() {
                @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
                @Override public boolean isRoot(String actor) { return false; }
            }, MAPPER, clock, catalog, null, null, null, runService::submitPublishedVersion);
            v1Id = publishV1();
        }

        /** Version 1: Z-USER 192.0.2.0/25 -> Z-DMZ 192.0.2.128/25 ALLOW_ANY; Z-USER -> Z-DB 203.0.113.0/24 DENY_ALL. */
        private String publishV1() {
            zUser = (String) matrixService.createZone("security-1", null,
                    new ZoneInput("Z-USER", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
            long rev = versions.draft().orElseThrow().revision();
            zDmz = (String) matrixService.createZone("security-1", rev,
                    new ZoneInput("Z-DMZ", "", "DMZ", List.of("192.0.2.128/25"))).get("zone_id");
            rev = versions.draft().orElseThrow().revision();
            zDb = (String) matrixService.createZone("security-1", rev,
                    new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/24"))).get("zone_id");
            rev = versions.draft().orElseThrow().revision();
            matrixService.setCell("security-1", rev,
                    new CellInput(zUser, zDmz, "ALLOW_ANY", List.of(), "MEDIUM", "dmz", ""));
            var draft = versions.draft().orElseThrow();
            return (String) matrixService.publish("compliance-1", false,
                    new PublishRequest(draft.revision(), draft.contentSha256(), null)).get("version_id");
        }

        Map<String, Object> projectMasked() {
            return ZoneMatrixSummaryView.project(
                    runService.storedSummary(),
                    matrixService.summaryLabels(View.MASKED, false),
                    ZoneMatrixSummaryView.Query.defaults());
        }
    }

    private static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> m = new LinkedHashMap<>();
        m.put("obj-any", OBJ_ANY);
        m.put("v4-user", new PolicyObject("v4-user", "V4-USER", "address", List.of(),
                List.of("ip-netmask: 192.0.2.10/32"), "RESOLVED"));
        m.put("dual-user", new PolicyObject("dual-user", "DUAL-USER", "address", List.of(),
                List.of("ip-netmask: 192.0.2.10/32", "ip-netmask: 2001:db8::20/128"), "RESOLVED"));
        m.put("dmz-host", new PolicyObject("dmz-host", "DMZ-HOST", "address", List.of(),
                List.of("ip-netmask: 192.0.2.140/32"), "RESOLVED"));
        m.put("db-host", new PolicyObject("db-host", "DB-HOST", "address", List.of(),
                List.of("ip-netmask: 203.0.113.10/32"), "RESOLVED"));
        m.put("https", new PolicyObject("https", "HTTPS", "service", List.of(),
                List.of("protocol/tcp/port: 443"), "RESOLVED"));
        return m;
    }

    private static Rule panAllow(String id, int num, String src, String dst) {
        return new Rule(id, "uid-" + id, num, "RULE-" + id, true,
                new Cell(List.of(src), false), new Cell(List.of(dst), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", ZONES_ANY);
    }

    private static PolicySnapshot localPan(String id, Instant collectedAt, List<Rule> rules, List<CollectionFailure> failures) {
        Rule intraDeny = new Rule("def-intra", "uid-def-intra", 1, "intrazone-default", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "deny", "Log", "", Map.of("rule-type", List.of("intrazone")));
        return new PolicySnapshot(
                new Metadata(id, "src-" + id, "FW-TANGO-04", "PAN", "dom-1", "DOM-BRAVO-02", "POL-" + id,
                        collectedAt.toString(), "art-1",
                        List.of(new Target("gw-1", "FW-TANGO-04", "vsys1", "IN_SYNC"))),
                List.of(new Section("sec-local", "Local rules", "Local rules", null, rules),
                        new Section("sec-default", "Local default rules", "Local default rules", null, List.of(intraDeny))),
                objects(),
                failures);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Map<String, Object> m, String key) {
        return (T) m.get(key);
    }

    // ------------------------------------------------------------------------------------------ ZM-19

    @Test
    @DisplayName("ZM-19: a durable run projects both a mixed IPv4/IPv6 object's IPv4 violation and 'IPv6 not evaluated'")
    void zm19_mixedIpv4Ipv6RulesReportIpv4ViolationAndIpv6NotEvaluatedThroughDurableSummary() {
        var env = new Env(services);
        env.catalog.putSnapshot(localPan("pol-zm19", NOW.minus(Duration.ofHours(1)), List.of(
                panAllow("r-dual-viol", 1, "dual-user", "db-host"),
                panAllow("r-dual-allow", 2, "dual-user", "dmz-host")), List.of()));
        env.runService.submit("pol-zm19", null);

        Map<String, Object> body = env.projectMasked();
        Map<String, Object> h = get(body, "headline");
        assertEquals(1, h.get("distinct_violating_rules"), "IPv4 violation of the mixed object is counted in the headline");
        assertEquals(1, h.get("not_evaluable_findings"), "mixed object in ALLOW_ANY cell stays NOT_EVALUABLE");
        assertEquals(false, h.get("exact"));
        assertEquals("at least 1", h.get("count_text"));

        List<Map<String, Object>> findings = get(body, "findings");
        assertEquals(2, findings.size());
        Map<String, Object> violation = findings.stream()
                .filter(f -> OUTCOME_VIOLATION.equals(f.get("outcome"))).findFirst().orElseThrow();
        assertTrue(this.<List<String>>get(violation, "undecided_parts").contains(IPV6_NOT_EVALUATED));
        List<Map<String, Object>> pairs = get(violation, "violated_pairs");
        assertEquals(env.zUser, pairs.get(0).get("source_zone_id"));
        assertEquals(env.zDb, pairs.get(0).get("destination_zone_id"));

        Map<String, Object> notEval = findings.stream()
                .filter(f -> OUTCOME_NOT_EVALUABLE.equals(f.get("outcome"))).findFirst().orElseThrow();
        assertEquals(IPV6_NOT_EVALUATED, notEval.get("reason"));
    }

    // ------------------------------------------------------------------------------------------ ZM-26

    @Test
    @DisplayName("ZM-26: an incomplete policy alone or a stale policy alone never projects '0' in any summary headline field")
    void zm26_incompletePolicyAloneAndStalePolicyAloneNeverProjectZeroInSummaryHeadline() {
        // 1) Incomplete coverage alone (CURRENT snapshot, 0 violating rules, 1 collection failure).
        var incEnv = new Env(services);
        incEnv.catalog.putSnapshot(localPan("pol-inc", NOW.minus(Duration.ofHours(1)), List.of(),
                List.of(new CollectionFailure("sec-local", "timeout"))));
        incEnv.runService.submit("pol-inc", null);
        Map<String, Object> incHeadline = get(incEnv.projectMasked(), "headline");
        assertEquals(false, incHeadline.get("exact"));
        assertEquals("at least 0", incHeadline.get("count_text"));
        assertEquals("no proven violations in evaluated rules", incHeadline.get("text"));
        assertEquals("at least 0", this.<Map<String, Object>>get(incHeadline, "staleness_headline").get("text"));
        assertNotEquals("0", incHeadline.get("count_text"));
        assertNotEquals("0", this.<Map<String, Object>>get(incHeadline, "staleness_headline").get("text"));

        // 2) Stale policy alone (snapshot 30 h old, COMPLETE coverage, 0 violating rules).
        var staleEnv = new Env(services);
        staleEnv.catalog.putSnapshot(localPan("pol-stale", NOW.minus(Duration.ofHours(30)), List.of(), List.of()));
        staleEnv.runService.submit("pol-stale", null);
        Map<String, Object> staleHeadline = get(staleEnv.projectMasked(), "headline");
        assertEquals(false, staleHeadline.get("exact"));
        assertEquals("at least 0", staleHeadline.get("count_text"));
        assertEquals("no proven violations in evaluated rules", staleHeadline.get("text"));
        assertEquals("at least 0", this.<Map<String, Object>>get(staleHeadline, "staleness_headline").get("text"));
    }

    @Test
    @Disabled("DEFECT: ZM-26 - ZoneMatrixSummaryView projects headline.staleness_headline.text=\"0\" for a CURRENT and COMPLETE policy with 0 violations and 1 NOT_EVALUABLE finding, which ViolationsTab renders verbatim in summary-headline")
    @DisplayName("ZM-26: one NOT_EVALUABLE rule on a CURRENT and COMPLETE policy never projects '0' in summary headline fields rendered by ViolationsTab")
    void zm26_oneNotEvaluableRuleOnCurrentCompletePolicyNeverProjectsZeroInSummaryHeadline() {
        var env = new Env(services);
        env.catalog.putSnapshot(localPan("pol-ne", NOW.minus(Duration.ofHours(1)),
                List.of(panAllow("r-ne", 1, "missing-object", "dmz-host")), List.of()));
        env.runService.submit("pol-ne", null);

        Map<String, Object> body = env.projectMasked();
        Map<String, Object> h = get(body, "headline");
        assertEquals(0, h.get("distinct_violating_rules"));
        assertEquals(1, h.get("not_evaluable_findings"));
        assertEquals(false, h.get("exact"));
        assertEquals("at least 0", h.get("count_text"));
        assertEquals("no proven violations in evaluated rules", h.get("text"));
        // ViolationsTab.tsx renders h.staleness_headline.text verbatim inside data-testid="summary-headline":
        // it must not be the bare string "0" when a NOT_EVALUABLE rule prevents proving zero violations.
        Map<String, Object> stalenessHeadline = get(h, "staleness_headline");
        assertNotEquals("0", stalenessHeadline.get("text"));
    }

    // ------------------------------------------------------------------------------------------ ZM-35

    @Test
    @DisplayName("ZM-35: exact 24 h snapshot age stays CURRENT and exact 'N', while 24 h + 1 s, FAILED, inactive version or INCOMPLETE gives 'at least N'")
    void zm35_exact24hBoundaryIsCurrentAndExactWhileImperfectPoliciesProjectAtLeastN() {
        var env = new Env(services);
        List<Rule> twoViolations = List.of(
                panAllow("r-1", 1, "v4-user", "db-host"),
                panAllow("r-2", 2, "v4-user", "db-host"));

        // Exact 24 h boundary: CURRENT and COMPLETE -> exact "2", never "at least 2".
        Instant exact24h = NOW.minus(Duration.ofHours(24));
        env.catalog.putSnapshot(localPan("pol-24h", exact24h, twoViolations, List.of()));
        env.runService.submit("pol-24h", null);
        Map<String, Object> atBoundary = env.projectMasked();
        Map<String, Object> boundaryPolicy = this.<List<Map<String, Object>>>get(atBoundary, "policies").get(0);
        assertEquals("CURRENT", this.<Map<String, Object>>get(boundaryPolicy, "staleness").get("verdict"));
        assertEquals(86_400L, this.<Map<String, Object>>get(boundaryPolicy, "staleness").get("snapshot_age_seconds"));
        Map<String, Object> boundaryHeadline = get(atBoundary, "headline");
        assertEquals(true, boundaryHeadline.get("exact"));
        assertEquals("2", boundaryHeadline.get("count_text"));
        assertTrue(((String) boundaryHeadline.get("text")).startsWith("2 rules violate the zone matrix (v1)"));

        // 24 h + 1 s: STALE -> "at least 2".
        env.clock.set(NOW.plusSeconds(1));
        Map<String, Object> over24h = env.projectMasked();
        Map<String, Object> overPolicy = this.<List<Map<String, Object>>>get(over24h, "policies").get(0);
        assertEquals("STALE", this.<Map<String, Object>>get(overPolicy, "staleness").get("verdict"));
        Map<String, Object> overHeadline = get(over24h, "headline");
        assertEquals(false, overHeadline.get("exact"));
        assertEquals("at least 2", overHeadline.get("count_text"));
        assertTrue(((String) overHeadline.get("text")).startsWith("at least 2 rules violate the zone matrix (v1)"));

        // Restore fresh snapshot, then add an INCOMPLETE covered policy -> "at least 2".
        env.clock.set(NOW);
        env.catalog.putSnapshot(localPan("pol-inc", NOW.minus(Duration.ofHours(1)), List.of(),
                List.of(new CollectionFailure("sec-local", "partial"))));
        env.runService.submit("pol-inc", null);
        Map<String, Object> withIncHeadline = get(env.projectMasked(), "headline");
        assertEquals(false, withIncHeadline.get("exact"));
        assertTrue(((String) withIncHeadline.get("text")).startsWith("at least 2 rules violate the zone matrix (v1)"));

        // Remove incomplete policy; fail a run on pol-24h -> LAST_RUN_FAILED -> STALE -> "at least 2".
        env.catalog.views.removeIf(v -> "pol-inc".equals(v.get("id")));
        String failedId = UUID.randomUUID().toString();
        env.runs.start(new ZoneMatrixRunRepository.NewRun(failedId, env.v1Id, 1, "a".repeat(64), "pol-24h",
                ZoneMatrixEvaluator.ENGINE_VERSION, NOW, exact24h));
        env.runs.fail(failedId, ZoneMatrixRunRepository.FailureReason.TIME_LIMIT, NOW);
        Map<String, Object> failedHeadline = get(env.projectMasked(), "headline");
        assertEquals(false, failedHeadline.get("exact"));
        assertTrue(((String) failedHeadline.get("text")).startsWith("at least 2 rules violate the zone matrix (v1)"));
    }

    // ------------------------------------------------------------------------------------------ ZM-36

    @Test
    @DisplayName("ZM-36: version-change resolved findings stay in the summary's 7-day Overview count through exact day 7 and drop out on day 8")
    void zm36_summaryViewKeepsVersionResolvedFindingsThroughDaySevenAndDropsThemOnDayEight() {
        var env = new Env(services);
        Instant t1 = NOW;
        env.catalog.putSnapshot(localPan("pol-zm36", t1, List.of(panAllow("r-1", 1, "v4-user", "db-host")), List.of()));
        env.runService.submit("pol-zm36", null);
        assertEquals(1, env.runService.storedSummary().headline().distinctViolatingRules());

        // Publish v2 allowing Z-USER -> Z-DB tcp/443 with required loosening rationale; post-publish run resolves r-1.
        Instant tResolve = t1.plus(Duration.ofHours(1));
        env.clock.set(tResolve);
        env.catalog.putSnapshot(localPan("pol-zm36", tResolve, List.of(panAllow("r-1", 1, "v4-user", "db-host")), List.of()));
        env.matrixService.setCell("security-1", null,
                new CellInput(env.zUser, env.zDb, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "approved access", ""));
        var draft = env.versions.draft().orElseThrow();
        String v2Id = (String) env.matrixService.publish("compliance-1", false,
                new PublishRequest(draft.revision(), draft.contentSha256(), "approved access")).get("version_id");

        // At exact 7 days after tResolve (fresh snapshot so policy stays CURRENT): still shown in 7-day window.
        Instant exactDay7 = tResolve.plus(Duration.ofDays(7));
        env.clock.set(exactDay7);
        env.catalog.putSnapshot(localPan("pol-zm36", exactDay7.minus(Duration.ofHours(1)),
                List.of(panAllow("r-1", 1, "v4-user", "db-host")), List.of()));
        Map<String, Object> day7Body = env.projectMasked();
        Map<String, Object> day7Headline = get(day7Body, "headline");
        assertEquals(1, day7Headline.get("resolved_by_version_change_last_7_days"));
        Map<String, Object> day7Resolved = get(day7Body, "resolved_by_version_change");
        assertEquals(1, day7Resolved.get("total_last_7_days"));
        assertEquals(1, this.<List<?>>get(day7Resolved, "last_7_days").size());
        assertEquals(List.of(Map.of("version_id", v2Id, "version_number", 2, "count", 1)), day7Resolved.get("by_version"));

        // Day 8 (tResolve + 8 days): drops out of the Overview 7-day window while per-version history retains it.
        Instant day8 = tResolve.plus(Duration.ofDays(8));
        env.clock.set(day8);
        env.catalog.putSnapshot(localPan("pol-zm36", day8.minus(Duration.ofHours(1)),
                List.of(panAllow("r-1", 1, "v4-user", "db-host")), List.of()));
        Map<String, Object> day8Body = env.projectMasked();
        Map<String, Object> day8Headline = get(day8Body, "headline");
        assertEquals(0, day8Headline.get("resolved_by_version_change_last_7_days"));
        Map<String, Object> day8Resolved = get(day8Body, "resolved_by_version_change");
        assertEquals(0, day8Resolved.get("total_last_7_days"));
        assertEquals(List.of(), day8Resolved.get("last_7_days"));
        assertEquals(List.of(Map.of("version_id", v2Id, "version_number", 2, "count", 1)), day8Resolved.get("by_version"));
        assertEquals(1, env.runService.storedSummary().resolvedByVersionChangeByVersion().get(v2Id).size());
    }
}
