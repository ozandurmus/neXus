package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Counts;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Coverage;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Finding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.NewRun;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ResolvedByVersionFinding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Witness;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Refused;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Step 4b-2b, part 1: the stored-summary projection behind {@code GET /api/v2/zone-matrix/summary}
 * (ZONE_MATRIX_CONTRACT.md 3.4, 7.1, 7.2, ZM-26, ZM-33, ZM-34, ZM-35, amendment 2026-11-23). Drives the real
 * {@link ZoneMatrixRunService#storedSummary()} over in-memory stores and the real {@link ZoneMatrixService} masking.
 */
class ZoneMatrixSummaryViewTest {
    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Planted synthetic names and addresses; none may reach a masked body, no address may reach any body.
    private static final String ZONE_A_NAME = "Z-PLANTED-CARDHOLDER";
    private static final String ZONE_B_NAME = "Z-PLANTED-BRANCH";
    private static final String RULE_NAME = "planted-rule-fw-tango-04-db";
    private static final String POLICY_NAME = "planted-policy-standard";
    private static final String SOURCE_NAME = "fw-tango-04.planted.example";
    private static final String CONTAINER_NAME = "planted-domain-juliet";
    private static final String WITNESS_SOURCE = "192.0.2.17/32";
    private static final String WITNESS_DESTINATION = "198.51.100.23/32";
    private static final String NAT_VIEW = "translated to 198.51.100.99";

    private final ZoneMatrixServiceTest.Memory versions = new ZoneMatrixServiceTest.Memory();
    private final ZoneMatrixRunServiceTest.FakeRuns runs = new ZoneMatrixRunServiceTest.FakeRuns();
    private final List<Map<String, Object>> catalog = new ArrayList<>();
    private final List<ZoneMatrixRunService> services = new ArrayList<>();
    private final ZoneMatrixService zoneMatrix = new ZoneMatrixService(versions, new Principals() {
        @Override public boolean holdsComplianceAdmin(String actor) { return false; }
        @Override public boolean isRoot(String actor) { return false; }
    }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
    private final String zoneA = UUID.randomUUID().toString();
    private final String zoneB = UUID.randomUUID().toString();
    private String activeId;

    @AfterEach void close() { services.forEach(ZoneMatrixRunService::close); }

    // ------------------------------------------------------------------ fixture

    private ZoneMatrixRunService runService() {
        PolicyCatalog policies = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return List.copyOf(catalog); }
            @Override public Optional<PolicySnapshot> find(String policyId) { return Optional.empty(); }
        };
        var service = new ZoneMatrixRunService(runs, versions, policies, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        services.add(service);
        return service;
    }

    private void activeVersion() {
        var content = new ZoneMatrixContent(List.of(
                new ZoneMatrixContent.Zone(zoneA, ZONE_A_NAME, "planted description", "DATABASE",
                        List.of(ZoneBlock.parse("192.0.2.0/25"))),
                new ZoneMatrixContent.Zone(zoneB, ZONE_B_NAME, "planted description", "USER",
                        List.of(ZoneBlock.parse("198.51.100.0/24")))));
        activeId = UUID.randomUUID().toString();
        versions.rows.put(activeId, new Version(activeId, 1, "ACTIVE", 2, content.canonicalJson(), content.sha256(), null,
                "author", NOW, "author", NOW, "publisher", NOW.minus(Duration.ofDays(1))));
    }

    private void policy(String id, String vendor, String collectedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("vendor", vendor);
        m.put("name", POLICY_NAME + "-" + id);
        m.put("sourceId", "src-" + id);
        m.put("sourceName", SOURCE_NAME);
        m.put("containerId", "ctr-" + id);
        m.put("containerName", CONTAINER_NAME);
        m.put("collectedAt", collectedAt);
        m.put("ruleCount", 12);
        catalog.add(m);
    }

    private static String hoursAgo(int h) { return NOW.minus(Duration.ofHours(h)).toString(); }

    private void completeRun(String policy, String coverage, int failures, List<Finding> findings) {
        String runId = UUID.randomUUID().toString();
        String sha = versions.rows.get(activeId).contentSha256();
        runs.start(new NewRun(runId, activeId, 1, sha, policy, "test", NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofHours(1))));
        int violation = (int) findings.stream().filter(f -> f.outcome().equals("VIOLATION")).count();
        int notEvaluable = (int) findings.stream().filter(f -> f.outcome().equals("NOT_EVALUABLE")).count();
        int unknown = (int) findings.stream().filter(f -> f.outcome().equals("UNKNOWN")).count();
        assertTrue(runs.complete(runId, NOW.minus(Duration.ofMinutes(5)), 100,
                new Counts(violation, 3, notEvaluable, unknown, 0, findings.size()),
                new Coverage(coverage, coverage.equals("COMPLETE") ? List.of() : List.of("RULEBASE_TRUNCATED"), failures, true,
                        "CP", NOW.minus(Duration.ofHours(1)), 12),
                findings));
    }

    private void failedRun(String policy) {
        String runId = UUID.randomUUID().toString();
        runs.start(new NewRun(runId, activeId, 1, versions.rows.get(activeId).contentSha256(), policy, "test",
                NOW.minus(Duration.ofMinutes(3)), null));
        assertTrue(runs.fail(runId, ZoneMatrixRunRepository.FailureReason.TIME_LIMIT, NOW.minus(Duration.ofMinutes(2))));
    }

    private Finding finding(String policy, String uid, Integer number, String outcome, String severity, List<String> flags) {
        List<Cell> cells = outcome.equals("VIOLATION")
                ? List.of(new Cell(zoneB, zoneA, "DENY_ALL", severity, List.of("tcp/22"), NAT_VIEW,
                        new Witness(WITNESS_SOURCE, WITNESS_DESTINATION, "tcp/22", 3221225489L, 3325256727L)))
                : List.of();
        List<String> pairs = List.of(zoneB + "->" + zoneA);
        return new Finding(policy, uid, List.of(), pairs, "rule-id-" + uid, number, RULE_NAME, "sec-1", "Pre rules",
                List.of("dev-" + policy), outcome, outcome.equals("VIOLATION") ? "statement DENY_ALL" : "IPv6 not evaluated",
                severity, flags, null, cells, outcome.equals("VIOLATION") ? List.of() : List.of("IPv6 not evaluated"),
                NAT_VIEW, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofMinutes(5)), null, null);
    }

    /** P-CUR current+complete with two violations, P-STALE stale with one NOT_EVALUABLE, P-INC incomplete with one UNKNOWN, P-NONE failed only. */
    private ZoneMatrixRunService fullFixture() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        policy("P-STALE", "PAN", hoursAgo(30));
        policy("P-INC", "CP", hoursAgo(2));
        policy("P-NONE", "CP", "not-a-time");
        policy("P-ASA", "ASA", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(
                finding("P-CUR", "u-low", 2, "VIOLATION", "LOW", List.of()),
                finding("P-CUR", "u-crit", 7, "VIOLATION", "CRITICAL", List.of("COVERED_BY_EARLIER_DENY"))));
        completeRun("P-STALE", "COMPLETE", 0, List.of(finding("P-STALE", "u-ne", 3, "NOT_EVALUABLE", null, List.of())));
        completeRun("P-INC", "INCOMPLETE", 1, List.of(finding("P-INC", "u-unk", 5, "UNKNOWN", null, List.of("BECAME_UNDECIDED"))));
        failedRun("P-NONE");
        return runService();
    }

    private ZoneMatrixSummaryView.Labels masked() { return zoneMatrix.summaryLabels(View.MASKED, false); }
    private ZoneMatrixSummaryView.Labels namesWithoutPolicyRead() { return zoneMatrix.summaryLabels(View.NAMES, false); }
    private ZoneMatrixSummaryView.Labels namesWithPolicyRead() { return zoneMatrix.summaryLabels(View.NAMES, true); }

    private static String json(Object o) throws Exception { return MAPPER.writeValueAsString(o); }

    @SuppressWarnings("unchecked")
    private <T> T get(Map<String, Object> m, String key) { return (T) m.get(key); }

    // ------------------------------------------------------------------ tests

    @Test
    void plantedNamesAndAddressesNeverAppearInTheMaskedProjectionAndNoViewCarriesWitnessData() throws Exception {
        StoredSummary summary = fullFixture().storedSummary();
        var stored = summary.findings().stream().flatMap(f -> f.finding().violatedCells().stream()).toList();
        assertTrue(stored.stream().anyMatch(c -> c.witness() != null), "fixture plants a witness in the stored cell");

        String maskedBody = json(ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.defaults()));
        for (String planted : List.of(ZONE_A_NAME, ZONE_B_NAME, RULE_NAME, POLICY_NAME, SOURCE_NAME, CONTAINER_NAME)) {
            assertFalse(maskedBody.contains(planted), "masked body carries a planted name");
        }
        String noPolicyRead = json(ZoneMatrixSummaryView.project(summary, namesWithoutPolicyRead(),
                ZoneMatrixSummaryView.Query.defaults()));
        for (String planted : List.of(RULE_NAME, POLICY_NAME, SOURCE_NAME, CONTAINER_NAME)) {
            assertFalse(noPolicyRead.contains(planted), "a caller without policy_read sees a planted policy-side name");
        }
        assertTrue(noPolicyRead.contains(ZONE_A_NAME), "zone_matrix_read shows zone names (section 3.4)");
        String full = json(ZoneMatrixSummaryView.project(summary, namesWithPolicyRead(), ZoneMatrixSummaryView.Query.defaults()));
        assertTrue(full.contains(RULE_NAME) && full.contains(ZONE_B_NAME), "positive control: policy_read shows names");

        for (String body : List.of(maskedBody, noPolicyRead, full)) {
            for (String address : List.of("192.0.2.", "198.51.100.", "3221225489", "3325256727")) {
                assertFalse(body.contains(address), "a body carries an address or witness sample");
            }
            for (String key : List.of("\"witness\"", "\"nat_view\"", "\"blocks\"", "\"source_range\"")) {
                assertFalse(body.contains(key), "a body carries a witness, NAT view or block field");
            }
            assertTrue(body.contains("\"witnesses_included\":false"));
        }
        assertTrue(maskedBody.contains("ZONE-1") || maskedBody.contains("ZONE-2"), "masked zone names are pseudonyms");
    }

    @Test
    void countsEqualTheStoredSummary() {
        StoredSummary summary = fullFixture().storedSummary();
        var body = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.defaults());
        Map<String, Object> h = get(body, "headline");
        var sh = summary.headline();
        assertEquals(sh.distinctViolatingRules(), h.get("distinct_violating_rules"));
        assertEquals(sh.violatedZonePairs(), h.get("violated_zone_pairs"));
        assertEquals(sh.criticalRules(), h.get("critical_rules"));
        assertEquals(sh.coveredByEarlierRule(), h.get("covered_by_earlier_rule"));
        assertEquals(sh.conditionalRules(), h.get("conditional_rules"));
        assertEquals(sh.outsideHeadlineOrderedLayer(), h.get("outside_headline_ordered_layer"));
        assertEquals(sh.notEvaluableFindings(), h.get("not_evaluable_findings"));
        assertEquals(sh.unknownFindings(), h.get("unknown_findings"));
        assertEquals(sh.becameUndecidedFindings(), h.get("became_undecided_findings"));
        assertEquals(sh.stalePolicies(), h.get("stale_policies"));
        assertEquals(sh.incompletePolicies(), h.get("incomplete_policies"));
        assertEquals(sh.olderVersionPolicies(), h.get("older_version_policies"));
        assertEquals(sh.notEvaluatedPolicies(), h.get("not_evaluated_policies"));
        assertEquals(sh.exact(), h.get("exact"));
        assertEquals(sh.countText(), h.get("count_text"));
        assertEquals(sh.text(), h.get("text"));
        assertEquals(2, sh.distinctViolatingRules());
        assertEquals("at least 2", h.get("count_text"));
        assertEquals(summary.policies().size(), ((List<?>) body.get("policies")).size());
        assertEquals(summary.notEvaluated().size(), ((List<?>) body.get("not_evaluated")).size());
        assertEquals(summary.findings().size(), body.get("total_findings"));
        assertEquals(summary.findings().size(), body.get("unfiltered_findings"));
        assertEquals(summary.findings().size(), body.get("returned"));
        assertEquals(false, body.get("capped"));
        assertEquals("READY", body.get("state"));
        Map<String, Object> active = get(body, "active_version");
        assertEquals(1, active.get("version_number"));
        assertEquals(versions.rows.get(activeId).contentSha256(), active.get("content_sha256"));
    }

    @Test
    void aStalePolicyAnIncompletePolicyAndOneNotEvaluableFindingNeverYieldAnExactZero() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        policy("P-STALE", "PAN", hoursAgo(30));
        policy("P-INC", "CP", hoursAgo(2));
        policy("P-NONE", "CP", "not-a-time");
        completeRun("P-CUR", "COMPLETE", 0, List.of());
        completeRun("P-STALE", "COMPLETE", 0, List.of());
        completeRun("P-INC", "INCOMPLETE", 1, List.of(finding("P-INC", "u-ne", 4, "NOT_EVALUABLE", null, List.of())));
        failedRun("P-NONE");
        var body = ZoneMatrixSummaryView.project(runService().storedSummary(), masked(), ZoneMatrixSummaryView.Query.defaults());

        Map<String, Object> h = get(body, "headline");
        assertEquals(0, h.get("distinct_violating_rules"));
        assertEquals(false, h.get("exact"));
        assertEquals("at least 0", h.get("count_text"));
        assertEquals("no proven violations in evaluated rules", h.get("text"));
        assertNotEquals("0", h.get("count_text"));
        Map<String, Object> staleness = get(h, "staleness_headline");
        assertEquals(false, staleness.get("exact"));

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> p : this.<List<Map<String, Object>>>get(body, "policies")) byId.put((String) p.get("policy_id"), p);
        assertEquals("STALE", this.<Map<String, Object>>get(byId.get("P-STALE"), "staleness").get("verdict"));
        assertEquals("CURRENT", this.<Map<String, Object>>get(byId.get("P-CUR"), "staleness").get("verdict"));
        assertEquals("INCOMPLETE", byId.get("P-INC").get("coverage"));
        assertEquals(List.of("RULEBASE_TRUNCATED"), byId.get("P-INC").get("coverage_items"));
        assertEquals(1, byId.get("P-INC").get("collection_failures"));
        // No COMPLETE run and an unreadable snapshot time: unknown, never 0.
        var none = byId.get("P-NONE");
        assertNull(none.get("collection_failures"));
        assertEquals(false, none.get("has_complete_run"));
        assertNull(none.get("latest_complete_run"));
        assertEquals("FAILED", this.<Map<String, Object>>get(none, "latest_run").get("state"));
        assertEquals("TIME_LIMIT", this.<Map<String, Object>>get(none, "latest_run").get("failure_reason"));
        Map<String, Object> noneStaleness = get(none, "staleness");
        assertNull(noneStaleness.get("snapshot_age_seconds"));
        assertEquals(false, noneStaleness.get("snapshot_age_known"));
        assertEquals("STALE", noneStaleness.get("verdict"));
        assertEquals(30L * 3600, this.<Map<String, Object>>get(byId.get("P-STALE"), "staleness").get("snapshot_age_seconds"));
    }

    @Test
    void findingsAreSortedFilteredAndCappedWithExplicitCounts() {
        StoredSummary summary = fullFixture().storedSummary();
        var all = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.defaults());
        List<Map<String, Object>> findings = get(all, "findings");
        assertEquals(List.of("u-crit", "u-low", "u-unk", "u-ne"), findings.stream().map(f -> f.get("rule_uid")).toList(),
                "severity first (CRITICAL, LOW, none), then outcome (UNKNOWN before NOT_EVALUABLE)");
        var crit = findings.get(0);
        assertEquals(7, crit.get("rule_number"));
        assertEquals("rule-id-u-crit", crit.get("rule_id"));
        assertEquals(List.of("COVERED_BY_EARLIER_DENY"), crit.get("flags"));
        assertEquals(1, crit.get("version_number"));
        assertEquals(true, crit.get("on_active_version"));
        assertEquals("P-CUR", crit.get("policy_source_id"));
        assertNotNull(crit.get("first_observed_at"));
        assertNotNull(crit.get("first_observed_any_version"));
        assertNotNull(crit.get("last_observed_at"));
        List<Map<String, Object>> pairs = get(crit, "violated_pairs");
        assertEquals(zoneB, pairs.get(0).get("source_zone_id"));
        assertEquals(List.of("tcp/22"), pairs.get(0).get("not_allowed_services"));

        var capped = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.parse(null, null, "1"));
        assertEquals(4, capped.get("total_findings"));
        assertEquals(1, capped.get("returned"));
        assertEquals(3, capped.get("more"));
        assertEquals(true, capped.get("capped"));

        var violations = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.parse(null, "VIOLATION", null));
        assertEquals(2, violations.get("total_findings"));
        assertEquals(4, violations.get("unfiltered_findings"));
        var stale = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.parse("P-STALE", null, null));
        assertEquals(1, stale.get("total_findings"));
        assertEquals("STALE", this.<List<Map<String, Object>>>get(stale, "findings").get(0).get("policy_staleness"));
        var none = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.parse("P-ASA", "UNKNOWN", null));
        assertEquals(0, none.get("total_findings"));
        assertEquals(false, none.get("capped"));

        var unknownPolicy = assertThrows(Refused.class, () -> ZoneMatrixSummaryView.project(summary, masked(),
                ZoneMatrixSummaryView.Query.parse("P-MISSING", null, null)));
        assertEquals(400, unknownPolicy.status);
        assertEquals("UNKNOWN_POLICY", unknownPolicy.code);
    }

    @Test
    void badParametersAreRefusedNeverClamped() {
        for (String limit : List.of("0", "1001", "-1", "abc", "", "2.5", "99999999")) {
            var refused = assertThrows(Refused.class, () -> ZoneMatrixSummaryView.Query.parse(null, null, limit), limit);
            assertEquals(400, refused.status);
            assertEquals("INVALID_LIMIT", refused.code);
        }
        for (String outcome : List.of("COMPLIANT", "violation", "NOT_APPLICABLE", "")) {
            assertEquals("INVALID_OUTCOME",
                    assertThrows(Refused.class, () -> ZoneMatrixSummaryView.Query.parse(null, outcome, null)).code);
        }
        assertEquals("INVALID_POLICY_ID", assertThrows(Refused.class, () -> ZoneMatrixSummaryView.Query.parse(" ", null, null)).code);
        assertEquals(1000, ZoneMatrixSummaryView.Query.parse(null, null, "1000").limit());
        assertEquals(200, ZoneMatrixSummaryView.Query.parse(null, null, null).limit());
        assertEquals("UNKNOWN_PARAMETER", assertThrows(Refused.class, () -> ZoneMatrixSummaryView.Query.checkParameterNames(
                Map.of("outcomes", new String[]{"VIOLATION"}))).code);
        assertEquals("REPEATED_PARAMETER", assertThrows(Refused.class, () -> ZoneMatrixSummaryView.Query.checkParameterNames(
                Map.of("limit", new String[]{"1", "2"}))).code);
    }

    @Test
    void theThresholdIsLabelledInterimAndNeverTheBanksValue() {
        var body = ZoneMatrixSummaryView.project(fullFixture().storedSummary(), masked(), ZoneMatrixSummaryView.Query.defaults());
        Map<String, Object> t = get(body, "staleness_threshold");
        assertEquals(true, t.get("interim"));
        assertEquals(24L, t.get("hours"));
        assertEquals(86400L, t.get("seconds"));
        assertTrue(((String) t.get("label")).contains("Product Owner"));
        assertTrue(((String) t.get("label")).contains("until the bank answers"));
        assertTrue(((String) t.get("label")).contains("not the bank's value"));

        var bank = new ZoneMatrixStaleness.StalenessThreshold(Duration.ofHours(12), "bank answer 2026-12-01");
        var bankBody = ZoneMatrixSummaryView.project(runService().storedSummary(bank), masked(), ZoneMatrixSummaryView.Query.defaults());
        Map<String, Object> bt = get(bankBody, "staleness_threshold");
        assertEquals(false, bt.get("interim"));
        assertEquals("source: bank answer 2026-12-01", bt.get("label"));
    }

    @Test
    void anEmptyStoreGivesAnExplicitEmptyStateNotAnError() throws Exception {
        var body = ZoneMatrixSummaryView.project(runService().storedSummary(), masked(), ZoneMatrixSummaryView.Query.defaults());
        assertEquals("NO_ACTIVE_VERSION", body.get("state"));
        assertNotNull(body.get("state_text"));
        assertNull(body.get("headline"));
        assertNotNull(body.get("headline_absent_reason"));
        assertNull(body.get("active_version"));
        assertEquals(List.of(), body.get("findings"));
        assertEquals(List.of(), body.get("policies"));
        assertEquals(0, body.get("total_findings"));
        assertEquals(false, body.get("capped"));
        Map<String, Object> resolved = get(body, "resolved_by_version_change");
        assertEquals(0, resolved.get("total_last_7_days"));
        assertFalse(json(body).contains("\"0 rules violate"));

        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        var noRun = ZoneMatrixSummaryView.project(runService().storedSummary(), masked(), ZoneMatrixSummaryView.Query.defaults());
        assertEquals("NO_COMPLETE_RUN", noRun.get("state"));
        assertEquals(false, this.<Map<String, Object>>get(noRun, "headline").get("exact"));
    }

    @Test
    void resolvedByVersionChangeRowsAreMaskedWithTheResolvingVersionsCited() throws Exception {
        activeVersion();
        var f = new Finding("P-CUR", "u-gone", List.of(), List.of(zoneB + "->" + zoneA), "rule-id-gone", 9, RULE_NAME, "sec-1",
                "Pre rules", List.of(), "VIOLATION", "statement DENY_ALL", "HIGH", List.of(), null,
                List.of(new Cell(zoneB, zoneA, "DENY_ALL", "HIGH", List.of("tcp/22"), NAT_VIEW,
                        new Witness(WITNESS_SOURCE, WITNESS_DESTINATION, "tcp/22", 1L, 2L))),
                List.of(), NAT_VIEW, NOW.minus(Duration.ofDays(4)), null, NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)),
                "MATRIX_VERSION_CHANGED");
        var resolved = new ResolvedByVersionFinding(activeId, 1, "v2-id", 2, NOW.minus(Duration.ofDays(1)), f);
        var summary = new StoredSummary(NOW, ZoneMatrixStaleness.INTERIM_PO_2026_11_23, versions.rows.get(activeId), List.of(),
                List.of(), List.of(), null, List.of(resolved), Map.of("v2-id", List.of(resolved)), null);
        var body = ZoneMatrixSummaryView.project(summary, masked(), ZoneMatrixSummaryView.Query.defaults());
        Map<String, Object> r = get(body, "resolved_by_version_change");
        assertEquals(1, r.get("total_last_7_days"));
        assertEquals(7L, r.get("window_days"));
        Map<String, Object> row = this.<List<Map<String, Object>>>get(r, "last_7_days").get(0);
        assertEquals(1, row.get("resolved_from_version_number"));
        assertEquals(2, row.get("resolved_in_version_number"));
        assertEquals(List.of(Map.of("version_id", "v2-id", "version_number", 2, "count", 1)), r.get("by_version"));
        String text = json(body);
        for (String planted : List.of(ZONE_A_NAME, ZONE_B_NAME, RULE_NAME, "192.0.2.", "198.51.100.")) {
            assertFalse(text.contains(planted), "resolved row carries a planted value");
        }
    }

    // ------------------------------------------------------------------ per-cell counts (step 4b-2)

    private static final String OUTSIDE = "DENIED_BY_OTHER_ORDERED_LAYER";

    /** A VIOLATION finding over the given {@code "src>dst"} cells (zone ids), with a per-cell severity. */
    private Finding cellFinding(String policy, String uid, String severity, List<String> flags, Instant resolvedAt,
            String... cellSpecs) {
        List<Cell> cells = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        for (String spec : cellSpecs) {
            String[] ends = spec.split(">");
            cells.add(new Cell(ends[0], ends[1], "DENY_ALL", severity, List.of("tcp/22"), NAT_VIEW,
                    new Witness(WITNESS_SOURCE, WITNESS_DESTINATION, "tcp/22", 3221225489L, 3325256727L)));
            pairs.add(ends[0] + "->" + ends[1]);
        }
        return new Finding(policy, uid, List.of(), pairs, "rule-id-" + uid, 1, RULE_NAME, "sec-1", "Pre rules",
                List.of("dev-" + policy), "VIOLATION", "statement DENY_ALL", severity, flags, null, cells, List.of(), NAT_VIEW,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofMinutes(5)), resolvedAt,
                resolvedAt == null ? null : "MATRIX_VERSION_CHANGED");
    }

    private List<Map<String, Object>> cellRows(Map<String, Object> body) {
        List<Map<String, Object>> rows = get(body, "cell_counts");
        return rows;
    }

    private Map<String, Object> cellRow(Map<String, Object> body, String source, String destination) {
        return cellRows(body).stream()
                .filter(r -> source.equals(r.get("source_zone_id")) && destination.equals(r.get("destination_zone_id")))
                .findFirst().orElseThrow(() -> new AssertionError("no cell row " + source + "->" + destination));
    }

    private Map<String, Object> projectNames(ZoneMatrixRunService service) {
        return ZoneMatrixSummaryView.project(service.storedSummary(), namesWithPolicyRead(), ZoneMatrixSummaryView.Query.defaults());
    }

    @Test
    void cellCountsCountDistinctRulesPerCellAndAgreeWithTheHeadline() {
        activeVersion();
        policy("P-ONE", "CP", hoursAgo(1));
        policy("P-TWO", "PAN", hoursAgo(1));
        String ab = zoneA + ">" + zoneB;
        String ba = zoneB + ">" + zoneA;
        completeRun("P-ONE", "COMPLETE", 0, List.of(
                cellFinding("P-ONE", "u-1", "LOW", List.of(), null, ab),                // cell AB
                cellFinding("P-ONE", "u-2", "HIGH", List.of(), null, ab, ba),           // cells AB and BA
                cellFinding("P-ONE", "u-1", "CRITICAL", List.of(), null, ab),           // same rule again: counted once
                cellFinding("P-ONE", "u-gone", "CRITICAL", List.of(), NOW.minusSeconds(60), ba))); // resolved: not counted
        completeRun("P-TWO", "COMPLETE", 0, List.of(cellFinding("P-TWO", "u-1", "MEDIUM", List.of(), null, ab))); // same uid, other policy
        var body = projectNames(runService());

        assertEquals(2, cellRows(body).size());
        var abRow = cellRow(body, zoneA, zoneB);
        assertEquals(3, abRow.get("headline_rules"), "u-1 and u-2 of P-ONE and u-1 of P-TWO");
        assertEquals(0, abRow.get("outside_headline_rules"));
        assertEquals("CRITICAL", abRow.get("highest_severity"));
        assertEquals(ZONE_A_NAME, abRow.get("source_name"));
        assertEquals(ZONE_B_NAME, abRow.get("destination_name"));
        var baRow = cellRow(body, zoneB, zoneA);
        assertEquals(1, baRow.get("headline_rules"), "the resolved rule is not counted");
        assertEquals("HIGH", baRow.get("highest_severity"));

        Map<String, Object> headline = get(body, "headline");
        assertEquals(3, headline.get("distinct_violating_rules"));
        assertEquals(2, headline.get("violated_zone_pairs"));
        assertEquals(headline.get("violated_zone_pairs"), body.get("cell_counts_total"));
        assertEquals(headline.get("exact"), body.get("cell_counts_exact"));
        assertEquals(true, body.get("cell_counts_exact"));
        assertEquals(2, body.get("cell_counts_returned"));
        assertEquals(0, body.get("cell_counts_more"));
        assertEquals(false, body.get("cell_counts_capped"));
    }

    @Test
    void anOutsideHeadlineOnlyRuleIsCountedApartAndNeverInTheHeadlineColumn() {
        activeVersion();
        policy("P-ONE", "CP", hoursAgo(1));
        String ab = zoneA + ">" + zoneB;
        String ba = zoneB + ">" + zoneA;
        completeRun("P-ONE", "COMPLETE", 0, List.of(
                cellFinding("P-ONE", "u-out", "HIGH", List.of(OUTSIDE), null, ab),
                cellFinding("P-ONE", "u-in", "LOW", List.of(), null, ba),
                // in both sets: counts only as a headline rule, as the headline does
                cellFinding("P-ONE", "u-both", "LOW", List.of(), null, ba),
                cellFinding("P-ONE", "u-both", "LOW", List.of("OTHER_ORDERED_LAYERS_UNKNOWN"), null, ab)));
        var body = projectNames(runService());
        Map<String, Object> headline = get(body, "headline");
        assertEquals(2, headline.get("distinct_violating_rules"));
        assertEquals(1, headline.get("outside_headline_ordered_layer"));

        var abRow = cellRow(body, zoneA, zoneB);
        assertEquals(0, abRow.get("headline_rules"));
        assertEquals(1, abRow.get("outside_headline_rules"), "u-out only; u-both is a headline rule");
        assertEquals("HIGH", abRow.get("highest_severity"));
        var baRow = cellRow(body, zoneB, zoneA);
        assertEquals(2, baRow.get("headline_rules"));
        assertEquals(0, baRow.get("outside_headline_rules"));
    }

    @Test
    void findingsOfAnOlderVersionRunAreNotCountedAndTheCountIsNoLongerExact() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        policy("P-OLD", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(cellFinding("P-CUR", "u-1", "LOW", List.of(), null, zoneA + ">" + zoneB)));
        String runId = UUID.randomUUID().toString();
        runs.start(new NewRun(runId, "older-version-id", 0, "0".repeat(64), "P-OLD", "test", NOW.minus(Duration.ofMinutes(10)),
                NOW.minus(Duration.ofHours(1))));
        assertTrue(runs.complete(runId, NOW.minus(Duration.ofMinutes(5)), 100, new Counts(1, 0, 0, 0, 0, 1),
                new Coverage("COMPLETE", List.of(), 0, true, "CP", NOW.minus(Duration.ofHours(1)), 12),
                List.of(cellFinding("P-OLD", "u-old", "CRITICAL", List.of(), null, zoneB + ">" + zoneA))));
        var body = projectNames(runService());

        assertEquals(1, cellRows(body).size(), "the older-version run adds no cell");
        assertEquals(1, cellRow(body, zoneA, zoneB).get("headline_rules"));
        assertEquals(false, body.get("cell_counts_exact"), "a policy not on the active version makes the count a lower bound");
        Map<String, Object> headline = get(body, "headline");
        assertEquals(headline.get("exact"), body.get("cell_counts_exact"));
        assertEquals(1, headline.get("distinct_violating_rules"));
    }

    @Test
    void aPolicyWithoutACompleteRunContributesNothingAndTheCountIsNotExact() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        policy("P-NONE", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(cellFinding("P-CUR", "u-1", "LOW", List.of(), null, zoneA + ">" + zoneB)));
        failedRun("P-NONE");
        var body = projectNames(runService());
        assertEquals(1, cellRows(body).size());
        assertEquals(false, body.get("cell_counts_exact"));
    }

    @Test
    void noActiveVersionGivesNullCellCountsNotAnEmptyList() {
        var body = ZoneMatrixSummaryView.project(runService().storedSummary(), masked(), ZoneMatrixSummaryView.Query.defaults());
        assertTrue(body.containsKey("cell_counts"));
        for (String key : List.of("cell_counts", "cell_counts_exact", "cell_counts_total", "cell_counts_returned",
                "cell_counts_more", "cell_counts_capped")) {
            assertNull(body.get(key), key);
        }
        assertNull(runService().storedSummary().cellCounts());
    }

    @Test
    void anActiveVersionWithNoOpenViolationGivesAnEmptyListNotNull() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of());
        var body = projectNames(runService());
        assertEquals(List.of(), body.get("cell_counts"));
        assertEquals(0, body.get("cell_counts_total"));
        assertEquals(true, body.get("cell_counts_exact"));
    }

    @Test
    void maskedCellCountsPseudonymiseZoneNamesAndLeakNoRawNameOrAddress() throws Exception {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(cellFinding("P-CUR", "u-1", "HIGH", List.of(), null, zoneA + ">" + zoneB)));
        var body = ZoneMatrixSummaryView.project(runService().storedSummary(), masked(), ZoneMatrixSummaryView.Query.defaults());
        var row = cellRows(body).get(0);
        assertTrue(String.valueOf(row.get("source_name")).startsWith("ZONE-"), "pseudonym, not the raw name");
        assertTrue(String.valueOf(row.get("destination_name")).startsWith("ZONE-"));
        String cellPart = json(body.get("cell_counts"));
        for (String planted : List.of(ZONE_A_NAME, ZONE_B_NAME, "192.0.2.", "198.51.100.", "\"witness\"", "\"nat_view\"", "\"blocks\"")) {
            assertFalse(cellPart.contains(planted), "cell counts carry " + planted);
        }
        assertFalse(json(body).contains(ZONE_A_NAME));
        assertFalse(json(body).contains(ZONE_B_NAME));
    }

    @Test
    void anUnreadableZoneNameStaysUnknownInACellCountRow() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        String ghost = UUID.randomUUID().toString();
        completeRun("P-CUR", "COMPLETE", 0, List.of(cellFinding("P-CUR", "u-1", "LOW", List.of(), null, ghost + ">" + zoneB)));
        var row = cellRows(projectNames(runService())).get(0);
        assertEquals("UNKNOWN", row.get("source_name"));
        assertEquals(ZONE_B_NAME, row.get("destination_name"));
    }

    @Test
    void theCellCountListIsSortedBySeverityThenIdsAndCappedByTheLimitWithTheCapSaid() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(
                cellFinding("P-CUR", "u-1", "LOW", List.of(), null, zoneA + ">" + zoneA),
                cellFinding("P-CUR", "u-2", "CRITICAL", List.of(), null, zoneB + ">" + zoneB),
                cellFinding("P-CUR", "u-3", "HIGH", List.of(), null, zoneA + ">" + zoneB),
                cellFinding("P-CUR", "u-4", "HIGH", List.of(), null, zoneB + ">" + zoneA)));
        var service = runService();
        var all = ZoneMatrixSummaryView.project(service.storedSummary(), namesWithPolicyRead(), ZoneMatrixSummaryView.Query.defaults());
        List<String> order = cellRows(all).stream().map(r -> r.get("highest_severity") + ":" + r.get("source_zone_id")).toList();
        String firstHigh = zoneA.compareTo(zoneB) < 0 ? zoneA : zoneB;
        assertEquals("CRITICAL:" + zoneB, order.get(0));
        assertEquals("HIGH:" + firstHigh, order.get(1));
        assertEquals("LOW:" + zoneA, order.get(3));

        var capped = ZoneMatrixSummaryView.project(service.storedSummary(), namesWithPolicyRead(),
                new ZoneMatrixSummaryView.Query(null, null, 3));
        assertEquals(3, cellRows(capped).size());
        assertEquals(4, capped.get("cell_counts_total"));
        assertEquals(3, capped.get("cell_counts_returned"));
        assertEquals(1, capped.get("cell_counts_more"));
        assertEquals(true, capped.get("cell_counts_capped"));
        assertEquals(order.subList(0, 3), cellRows(capped).stream()
                .map(r -> r.get("highest_severity") + ":" + r.get("source_zone_id")).toList(), "the cap keeps the head of the order");
    }

    @Test
    void aPairOnlyFindingFallsBackToItsViolatedPairsAndHasNoSeverity() {
        activeVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        var f = new Finding("P-CUR", "u-pair", List.of(), List.of(zoneA + "->" + zoneB), "rule-id-pair", 1, RULE_NAME, "sec-1",
                "Pre rules", List.of(), "VIOLATION", "statement DENY_ALL", null, List.of(), null, List.of(), List.of(), null,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofMinutes(5)), null, null);
        completeRun("P-CUR", "COMPLETE", 0, List.of(f));
        var row = cellRow(projectNames(runService()), zoneA, zoneB);
        assertEquals(1, row.get("headline_rules"));
        assertNull(row.get("highest_severity"), "an unknown severity is null, never a guessed value");
    }
}
