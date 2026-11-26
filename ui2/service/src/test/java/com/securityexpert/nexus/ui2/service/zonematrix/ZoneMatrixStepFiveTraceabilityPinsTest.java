package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_VIOLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
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
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step 5 traceability reconciliation pins for {@code docs/design/ZONE_MATRIX_CONTRACT.md} (FROZEN, revision 2, section
 * 10.2; ZM-19, ZM-35, ZM-36). Synthetic principals, masked pseudonyms and RFC 5737 / RFC 3849 documentation blocks
 * only.
 */
class ZoneMatrixStepFiveTraceabilityPinsTest {
    private static final Instant NOW = Instant.parse("2026-11-26T06:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final PolicyObject OBJ_ANY =
            new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");
    private static final String IPV6_NOT_EVALUATED = "IPv6 not evaluated";

    private final List<ZoneMatrixRunService> services = new ArrayList<>();

    @AfterEach
    void closeServices() {
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

    private static Principals principals() {
        return new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        };
    }

    // ------------------------------------------------------------------------------------------ ZM-19

    @Test
    @DisplayName("ZM-19: mixed IPv4/IPv6 source and destination objects keep their IPv4 verdict and report IPv6 not evaluated through the stored summary")
    @SuppressWarnings("unchecked")
    void zm19_mixedIpv4Ipv6SourceAndDestinationKeepIpv4VerdictAndReportIpv6NotEvaluatedThroughStoredSummary() {
        var versions = new ZoneMatrixServiceTest.Memory();
        var runs = new FakeRuns();
        var clock = new MutableClock(NOW);
        Map<String, PolicySnapshot> snapshots = new LinkedHashMap<>();
        PolicyCatalog catalog = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() {
                List<Map<String, Object>> out = new ArrayList<>();
                for (PolicySnapshot s : snapshots.values()) {
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
                    out.add(m);
                }
                return out;
            }
            @Override public Optional<PolicySnapshot> find(String id) { return Optional.ofNullable(snapshots.get(id)); }
        };

        var matrixService = new ZoneMatrixService(versions, principals(), MAPPER, clock);
        String zUser = (String) matrixService.createZone("security-1", null,
                new ZoneInput("Z-USER", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
        long rev = versions.draft().orElseThrow().revision();
        String zDmz = (String) matrixService.createZone("security-1", rev,
                new ZoneInput("Z-DMZ", "", "DMZ", List.of("192.0.2.128/25"))).get("zone_id");
        rev = versions.draft().orElseThrow().revision();
        String zDb = (String) matrixService.createZone("security-1", rev,
                new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/24"))).get("zone_id");
        rev = versions.draft().orElseThrow().revision();
        matrixService.setCell("security-1", rev,
                new CellInput(zUser, zDmz, "ALLOW_ANY", List.of(), "LOW", "dmz allow", ""));
        var draft = versions.draft().orElseThrow();
        matrixService.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));

        var runService = new ZoneMatrixRunService(runs, versions, catalog, MAPPER, clock, Duration.ofMinutes(2),
                ZoneMatrixStaleness.INTERIM_PO_2026_11_23, new DirectExecutor(), ZoneMatrixRunService.newWatchdog());
        services.add(runService);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("v4-user", new PolicyObject("v4-user", "USER-V4", "address", List.of(),
                List.of("ip-netmask: 192.0.2.10/32"), "RESOLVED"));
        objects.put("dual-db", new PolicyObject("dual-db", "DB-DUAL", "address", List.of(),
                List.of("ip-netmask: 203.0.113.10/32", "ip-netmask: 2001:db8::99/128"), "RESOLVED"));
        objects.put("dual-dmz", new PolicyObject("dual-dmz", "DMZ-DUAL", "address", List.of(),
                List.of("ip-netmask: 192.0.2.140/32", "ip-netmask: 2001:db8::40/128"), "RESOLVED"));
        objects.put("https", new PolicyObject("https", "HTTPS", "service", List.of(),
                List.of("protocol/tcp/port: 443"), "RESOLVED"));

        Rule rViolatingDualDest = new Rule("r-dual-db", "uid-dual-db", 1, "RULE-DUAL-DB", true,
                new Cell(List.of("v4-user"), false), new Cell(List.of("dual-db"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        Rule rCompliantIpv4DualDest = new Rule("r-dual-dmz", "uid-dual-dmz", 2, "RULE-DUAL-DMZ", true,
                new Cell(List.of("v4-user"), false), new Cell(List.of("dual-dmz"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        Rule intraDeny = new Rule("def-intra", "uid-def-intra", 1, "intrazone-default", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "deny", "Log", "", Map.of("rule-type", List.of("intrazone")));

        PolicySnapshot snap = new PolicySnapshot(
                new Metadata("pol-zm19", "src-1", "FW-TANGO-04", "PAN", "dom-1", "DOM-BRAVO-02", "POL-ZM19",
                        NOW.minus(Duration.ofHours(1)).toString(), "art-1",
                        List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC"))),
                List.of(
                        new Section("s-loc", PolicySnapshot.PAN_LOCAL_RULES, PolicySnapshot.PAN_LOCAL_RULES, null,
                                List.of(rViolatingDualDest, rCompliantIpv4DualDest)),
                        new Section("s-def", PolicySnapshot.PAN_LOCAL_DEFAULT, PolicySnapshot.PAN_LOCAL_DEFAULT, null,
                                List.of(intraDeny))),
                objects);
        snapshots.put("pol-zm19", snap);
        runService.submit("pol-zm19", null);

        Map<String, Object> body = ZoneMatrixSummaryView.project(
                runService.storedSummary(), matrixService.summaryLabels(View.NAMES, true), ZoneMatrixSummaryView.Query.defaults());
        Map<String, Object> headline = (Map<String, Object>) body.get("headline");
        assertEquals(1, headline.get("distinct_violating_rules"));
        assertEquals(1, headline.get("not_evaluable_findings"));
        assertEquals(false, headline.get("exact"));

        List<Map<String, Object>> findings = (List<Map<String, Object>>) body.get("findings");
        assertEquals(2, findings.size());
        Map<String, Object> violation = findings.stream()
                .filter(f -> "uid-dual-db".equals(f.get("rule_uid"))).findFirst().orElseThrow();
        assertEquals(OUTCOME_VIOLATION, violation.get("outcome"));
        List<String> undecidedParts = (List<String>) violation.get("undecided_parts");
        assertTrue(undecidedParts.contains(IPV6_NOT_EVALUATED), "undecided_parts: " + undecidedParts);
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) violation.get("violated_pairs");
        assertEquals(zUser, pairs.get(0).get("source_zone_id"));
        assertEquals(zDb, pairs.get(0).get("destination_zone_id"));

        Map<String, Object> notEvaluable = findings.stream()
                .filter(f -> "uid-dual-dmz".equals(f.get("rule_uid"))).findFirst().orElseThrow();
        assertEquals(OUTCOME_NOT_EVALUABLE, notEvaluable.get("outcome"));
        assertEquals(IPV6_NOT_EVALUATED, notEvaluable.get("reason"));
    }

    // ------------------------------------------------------------------------------------------ ZM-35

    @Test
    @DisplayName("ZM-35: the stored summary headline text prints exact N only when all covered policies are CURRENT and COMPLETE, else 'at least N'")
    @SuppressWarnings("unchecked")
    void zm35_storedSummaryHeadlineUsesAtLeastNForEachStalenessCauseAndIncompleteCoverageAndExactNOnlyWhenAllCurrentAndComplete() {
        var versions = new ZoneMatrixServiceTest.Memory();
        var runs = new FakeRuns();
        var clock = new MutableClock(NOW);
        List<Map<String, Object>> catalogViews = new ArrayList<>();
        PolicyCatalog catalog = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return List.copyOf(catalogViews); }
            @Override public Optional<PolicySnapshot> find(String id) { return Optional.empty(); }
        };
        var matrixService = new ZoneMatrixService(versions, principals(), MAPPER, clock);
        String zUser = (String) matrixService.createZone("security-1", null,
                new ZoneInput("Z-USER", "", "USER", List.of("192.0.2.0/25"))).get("zone_id");
        long rev = versions.draft().orElseThrow().revision();
        String zDb = (String) matrixService.createZone("security-1", rev,
                new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/24"))).get("zone_id");
        var draft = versions.draft().orElseThrow();
        String activeId = (String) matrixService.publish("compliance-1", false,
                new PublishRequest(draft.revision(), draft.contentSha256(), null)).get("version_id");
        String sha = versions.active().orElseThrow().contentSha256();

        var runService = new ZoneMatrixRunService(runs, versions, catalog, MAPPER, clock, Duration.ofMinutes(2),
                ZoneMatrixStaleness.INTERIM_PO_2026_11_23, new DirectExecutor(), ZoneMatrixRunService.newWatchdog());
        services.add(runService);

        Instant fresh = NOW.minus(Duration.ofHours(1));
        catalogViews.add(Map.of("id", "pol-1", "name", "POLICY-1", "vendor", "CP", "sourceId", "s1",
                "sourceName", "FW-TANGO-04", "collectedAt", fresh.toString(), "ruleCount", 4));
        catalogViews.add(Map.of("id", "pol-2", "name", "POLICY-2", "vendor", "PAN", "sourceId", "s2",
                "sourceName", "FW-JULIET-06", "collectedAt", fresh.toString(), "ruleCount", 4));

        var vFinding = new ZoneMatrixRunRepository.Finding("pol-1", "uid-1", List.of(), List.of(zUser + "->" + zDb),
                "r-1", 1, "RULE-1", "sec-1", "Network", List.of("FW-TANGO-04"), OUTCOME_VIOLATION, "statement DENY_ALL",
                "CRITICAL", List.of(), null, List.of(), List.of(), "original", fresh, fresh, fresh, null, null);
        complete(runs, "pol-1", activeId, 1, sha, fresh, "COMPLETE", List.of(vFinding));
        complete(runs, "pol-2", activeId, 1, sha, fresh, "COMPLETE", List.of());

        // Both CURRENT and COMPLETE -> exact "1 rule violates..."
        Map<String, Object> exact = (Map<String, Object>) ZoneMatrixSummaryView.project(
                runService.storedSummary(), matrixService.summaryLabels(View.MASKED, false), ZoneMatrixSummaryView.Query.defaults()).get("headline");
        assertEquals(true, exact.get("exact"));
        assertEquals("1", exact.get("count_text"));
        assertTrue(String.valueOf(exact.get("text")).startsWith("1 rule violates the zone matrix (v1)"));

        // 1) Snapshot older than threshold -> STALE -> "at least 1 rule violates..."
        clock.set(fresh.plus(Duration.ofHours(25)));
        Map<String, Object> oldSnap = (Map<String, Object>) ZoneMatrixSummaryView.project(
                runService.storedSummary(), matrixService.summaryLabels(View.MASKED, false), ZoneMatrixSummaryView.Query.defaults()).get("headline");
        assertEquals(false, oldSnap.get("exact"));
        assertTrue(String.valueOf(oldSnap.get("text")).startsWith("at least 1 rule violates the zone matrix (v1)"));

        // 2) Fresh again, then pol-2 INCOMPLETE coverage -> "at least 1 rule violates..."
        clock.set(NOW);
        complete(runs, "pol-2", activeId, 1, sha, NOW.minus(Duration.ofMinutes(30)), "INCOMPLETE", List.of());
        Map<String, Object> incomplete = (Map<String, Object>) ZoneMatrixSummaryView.project(
                runService.storedSummary(), matrixService.summaryLabels(View.MASKED, false), ZoneMatrixSummaryView.Query.defaults()).get("headline");
        assertEquals(false, incomplete.get("exact"));
        assertTrue(String.valueOf(incomplete.get("text")).startsWith("at least 1 rule violates the zone matrix (v1)"));

        // 3) Restore pol-2 COMPLETE on an older version -> STALE (VERSION_NOT_ACTIVE) -> "at least 1 rule violates..."
        complete(runs, "pol-2", "v-older", 0, "b".repeat(64), NOW.minus(Duration.ofMinutes(20)), "COMPLETE", List.of());
        Map<String, Object> oldVer = (Map<String, Object>) ZoneMatrixSummaryView.project(
                runService.storedSummary(), matrixService.summaryLabels(View.MASKED, false), ZoneMatrixSummaryView.Query.defaults()).get("headline");
        assertEquals(false, oldVer.get("exact"));
        assertTrue(String.valueOf(oldVer.get("text")).startsWith("at least 1 rule violates the zone matrix (v1)"));

        // 4) Restore pol-2 COMPLETE on active version, then append a FAILED run -> STALE (LAST_RUN_FAILED) -> "at least 1..."
        complete(runs, "pol-2", activeId, 1, sha, NOW.minus(Duration.ofMinutes(10)), "COMPLETE", List.of());
        String failId = UUID.randomUUID().toString();
        runs.start(new ZoneMatrixRunRepository.NewRun(failId, activeId, 1, sha, "pol-2", "test", NOW.minusSeconds(30), null));
        runs.fail(failId, ZoneMatrixRunRepository.FailureReason.TIME_LIMIT, NOW.minusSeconds(20));
        Map<String, Object> failedLast = (Map<String, Object>) ZoneMatrixSummaryView.project(
                runService.storedSummary(), matrixService.summaryLabels(View.MASKED, false), ZoneMatrixSummaryView.Query.defaults()).get("headline");
        assertEquals(false, failedLast.get("exact"));
        assertTrue(String.valueOf(failedLast.get("text")).startsWith("at least 1 rule violates the zone matrix (v1)"));
    }

    private static void complete(FakeRuns runs, String policy, String versionId, int number, String sha, Instant at,
            String coverage, List<ZoneMatrixRunRepository.Finding> findings) {
        String runId = UUID.randomUUID().toString();
        runs.start(new ZoneMatrixRunRepository.NewRun(runId, versionId, number, sha, policy, "test", at, at));
        assertTrue(runs.complete(runId, at, 10L,
                new ZoneMatrixRunRepository.Counts(findings.size(), 0, 0, 0, 0, findings.size()),
                new ZoneMatrixRunRepository.Coverage(coverage, List.of(), 0, true, "CP", at, 4),
                findings));
    }

    // ------------------------------------------------------------------------------------------ ZM-36

    @Test
    @DisplayName("ZM-36: each of the six loosening change kinds individually sets rationale_required and refuses publish without rationale")
    void zm36_eachOfTheSixLooseningKindsIndividuallyRequiresARationaleOnPublish() {
        assertSingleLooseningRequiresRationale("STATEMENT_RELAXED", ctx ->
                ctx.service.setCell("security-2", ctx.rev(),
                        new CellInput(ctx.zDb, ctx.zApp, "ALLOW_LISTED", List.of("tcp/443"), "CRITICAL", null, "")));

        assertSingleLooseningRequiresRationale("SERVICES_ADDED", ctx ->
                ctx.service.setCell("security-2", ctx.rev(),
                        new CellInput(ctx.zApp, ctx.zDb, "ALLOW_LISTED", List.of("tcp/5432", "tcp/1521"), "HIGH", null, "")));

        assertSingleLooseningRequiresRationale("DEFAULT_CHANGED", ctx ->
                ctx.service.setDefault("security-2", ctx.rev(), "ALLOW_LISTED", "MEDIUM"));

        assertSingleLooseningRequiresRationale("SEVERITY_DOWNGRADED", ctx ->
                ctx.service.setCell("security-2", ctx.rev(),
                        new CellInput(ctx.zDb, ctx.zApp, "DENY_ALL", List.of(), "LOW", null, "")));

        assertSingleLooseningRequiresRationale("ADDRESSES_MOVED", ctx -> {
            ctx.service.updateZone("security-2", ctx.rev(), ctx.zDb,
                    new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/26")));
            ctx.service.updateZone("security-2", ctx.rev(), ctx.zApp,
                    new ZoneInput("Z-APP", "", "APPLICATION", List.of("198.51.100.0/24", "203.0.113.64/26")));
        });

        assertSingleLooseningRequiresRationale("UNASSIGNED_SHRUNK", ctx ->
                ctx.service.updateZone("security-2", ctx.rev(), ctx.zDmz,
                        new ZoneInput("Z-DMZ", "", "DMZ", List.of("192.0.2.0/25", "192.0.2.128/25"))));
    }

    private record LooseningCtx(ZoneMatrixService service, ZoneMatrixServiceTest.Memory store,
            String zApp, String zDb, String zDmz) {
        Long rev() { return store.draft().map(d -> d.revision()).orElse(null); }
    }

    @SuppressWarnings("unchecked")
    private static void assertSingleLooseningRequiresRationale(String expectedKind, Consumer<LooseningCtx> edit) {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = new ZoneMatrixService(store, principals(), MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        String zApp = (String) service.createZone("security-1", null,
                new ZoneInput("Z-APP", "", "APPLICATION", List.of("198.51.100.0/24"))).get("zone_id");
        long rev = store.draft().orElseThrow().revision();
        String zDb = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-DB", "", "DATABASE", List.of("203.0.113.0/25"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        String zDmz = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-DMZ", "", "DMZ", List.of("192.0.2.0/25"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev, new CellInput(zApp, zDb, "ALLOW_LISTED", List.of("tcp/5432"), "HIGH", null, ""));
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev, new CellInput(zDb, zApp, "DENY_ALL", List.of(), "CRITICAL", null, ""));
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev, new CellInput(zDmz, zApp, "ALLOW_ANY", List.of(), "LOW", null, ""));
        var v1 = store.draft().orElseThrow();
        service.publish("compliance-1", false, new PublishRequest(v1.revision(), v1.contentSha256(), null));

        edit.accept(new LooseningCtx(service, store, zApp, zDb, zDmz));

        Map<String, Object> diff = (Map<String, Object>) service.draft(View.NAMES, "compliance-1", false, true).get("diff");
        List<Map<String, Object>> loosening = (List<Map<String, Object>>) diff.get("loosening");
        assertTrue(loosening.stream().anyMatch(r -> expectedKind.equals(r.get("kind"))),
                expectedKind + " missing from " + loosening);
        assertEquals(true, diff.get("rationale_required"), expectedKind + " must set rationale_required");

        var draft = store.draft().orElseThrow();
        assertEquals("RATIONALE_REQUIRED", assertThrows(Refused.class, () ->
                service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null))).code,
                expectedKind);
        assertEquals("RATIONALE_REQUIRED", assertThrows(Refused.class, () ->
                service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), "   "))).code,
                expectedKind);
        assertEquals(2, service.publish("compliance-1", false,
                new PublishRequest(draft.revision(), draft.contentSha256(), "approved loosening rationale")).get("number"),
                expectedKind);
    }
}
