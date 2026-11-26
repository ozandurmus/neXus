package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Revision;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Counts;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Coverage;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Finding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.NewRun;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Witness;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.report.CsvReportRenderer;
import com.securityexpert.nexus.ui2.service.report.ReportDocument;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Built;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.FindingRow;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Input;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.PolicyScope;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Regulation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReportInputs.Request;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.Reason;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.Verdict;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Step 6b-1: the evidence report input assembler over the stored read models (ZONE_MATRIX_CONTRACT.md 3.4, 7.3, 8.2,
 * {@code ZM-11}, {@code ZM-28} formula-guard half, {@code ZM-30} title pin, {@code ZM-31} projection half,
 * {@code ZM-32}; amendment 2026-11-23). Drives the real {@link ZoneMatrixRunService#storedSummary()} over in-memory
 * stores and the real {@link ZoneMatrixService#summaryLabels} masking. Synthetic names and documentation ranges only.
 */
class ZoneMatrixEvidenceReportInputsTest {
    private static final Instant NOW = Instant.parse("2026-11-25T08:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Planted synthetic identities; none may reach a masked input or report, no address may reach any.
    private static final String ZONE_A_NAME = "Z-PLANTED-CARDHOLDER";
    private static final String ZONE_B_NAME = "Z-PLANTED-BRANCH";
    private static final String ZONE_C_NAME = "Z-PLANTED-DMZ";
    private static final String RULE_NAME = "planted-rule-fw-tango-04-db";
    private static final String POLICY_NAME = "planted-policy-standard";
    private static final String SOURCE_NAME = "fw-tango-04.planted.example";
    private static final String CONTAINER_NAME = "planted-domain-juliet";
    private static final String SET_NAME = "planted-set-web";
    private static final String CONFIRMER = "planted-compliance-officer";
    private static final String PUBLISHER = "planted-publisher";
    private static final String EDITOR = "planted-editor";
    private static final String CALLER = "planted-caller";
    private static final String REFERENCE = "BDDK-planted-article-12";
    private static final String WITNESS_SOURCE = "192.0.2.17/32";
    private static final String WITNESS_DESTINATION = "198.51.100.23/32";
    private static final String NAT_VIEW = "translated to 198.51.100.99";
    private static final List<String> ADDRESS_MARKERS = List.of("192.0.2.", "198.51.100.", "3221225489", "3325256727",
            "translated to");

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
    private final String zoneC = UUID.randomUUID().toString();
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

    /** Active version with three zones, one service set and four cells whose references are given per cell. */
    private void activeVersion(String refAB, String statusAB, String refBA, String statusBA, String refBC,
            String statusBC, String refCA, String statusCA) {
        var zones = List.of(
                new ZoneMatrixContent.Zone(zoneA, ZONE_A_NAME, "planted description", "DATABASE",
                        List.of(ZoneBlock.parse("192.0.2.0/25"))),
                new ZoneMatrixContent.Zone(zoneB, ZONE_B_NAME, "planted description", "USER",
                        List.of(ZoneBlock.parse("198.51.100.0/24"))),
                new ZoneMatrixContent.Zone(zoneC, ZONE_C_NAME, "planted description", "DMZ",
                        List.of(ZoneBlock.parse("192.0.2.128/25"))));
        var cells = List.of(
                cell(zoneA, zoneB, "DENY_ALL", List.of(), "CRITICAL", refAB, statusAB),
                cell(zoneB, zoneA, "ALLOW_LISTED", List.of("tcp/443", "set:" + SET_NAME), "HIGH", refBA, statusBA),
                cell(zoneB, zoneC, "DENY_ALL", List.of(), "MEDIUM", refBC, statusBC),
                cell(zoneC, zoneA, "DENY_ALL", List.of(), "LOW", refCA, statusCA));
        var matrix = new ZoneMatrixContent.Matrix("DENY_ALL", "MEDIUM", null,
                List.of(new ZoneMatrixContent.ServiceSet(SET_NAME, List.of("tcp/8443"))), cells);
        var content = new ZoneMatrixContent(zones, matrix);
        activeId = UUID.randomUUID().toString();
        versions.rows.put(activeId, new Version(activeId, 1, "ACTIVE", 2, content.canonicalJson(), content.sha256(), null,
                EDITOR, NOW, EDITOR, NOW, PUBLISHER, NOW.minus(Duration.ofDays(1))));
    }

    private static ZoneMatrixContent.Cell cell(String source, String destination, String statement, List<String> services,
            String severity, String reference, String status) {
        boolean confirmed = "CONFIRMED".equals(status);
        return new ZoneMatrixContent.Cell(source, destination, statement, services, severity, null, reference, status,
                confirmed ? CONFIRMER : null, confirmed ? "2026-11-20T10:00:00Z" : null);
    }

    private void defaultVersion() {
        activeVersion(REFERENCE, "PROPOSED", REFERENCE, "CONFIRMED", "", "EMPTY", "", "EMPTY");
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

    private Finding finding(String policy, String uid, Integer number, String ruleName, String outcome, String severity,
            List<String> flags, String from, String to) {
        boolean violation = outcome.equals("VIOLATION");
        List<Cell> cells = violation
                ? List.of(new Cell(from, to, "DENY_ALL", severity, List.of("tcp/22"), NAT_VIEW,
                        new Witness(WITNESS_SOURCE, WITNESS_DESTINATION, "tcp/22", 3221225489L, 3325256727L)))
                : List.of();
        List<String> pairs = List.of(from + "->" + to);
        return new Finding(policy, uid, List.of(), pairs, "rule-id-" + uid, number, ruleName, "sec-1", "Pre rules",
                List.of("dev-" + policy), outcome, violation ? "statement DENY_ALL" : outcome + " planted reason " + uid,
                severity, flags, null, cells, violation ? List.of() : List.of("IPv6 not evaluated"),
                NAT_VIEW, NOW.minus(Duration.ofDays(2)), violation ? NOW.minus(Duration.ofDays(3)) : null,
                NOW.minus(Duration.ofMinutes(5)), null, null);
    }

    /** P-CUR current with two violations, P-STALE stale with a NOT_EVALUABLE, P-INC incomplete with an UNKNOWN, P-NONE failed only. */
    private StoredSummary fullFixture() {
        defaultVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        policy("P-STALE", "PAN", hoursAgo(30));
        policy("P-INC", "CP", hoursAgo(2));
        policy("P-NONE", "CP", hoursAgo(1));
        policy("P-ASA", "ASA", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(
                finding("P-CUR", "u-low", 2, RULE_NAME, "VIOLATION", "LOW", List.of(), zoneB, zoneA),
                finding("P-CUR", "u-crit", 7, RULE_NAME, "VIOLATION", "CRITICAL", List.of("COVERED_BY_EARLIER_DENY"),
                        zoneA, zoneB)));
        completeRun("P-STALE", "COMPLETE", 0, List.of(
                finding("P-STALE", "u-ne", 3, RULE_NAME, "NOT_EVALUABLE", null, List.of(), zoneB, zoneA)));
        completeRun("P-INC", "INCOMPLETE", 1, List.of(
                finding("P-INC", "u-unk", 5, RULE_NAME, "UNKNOWN", null, List.of(), zoneB, zoneA)));
        failedRun("P-NONE");
        return runService().storedSummary();
    }

    private static Request request() {
        return new Request("9.9.9-test", CALLER, null, null, null, null, null,
                List.of(new Revision(1, EDITOR, NOW, "ZONE_ADDED", null, "sha", false)));
    }

    private Input assemble(StoredSummary summary, ZoneMatrixSummaryView.Labels labels, Request request) {
        return ZoneMatrixEvidenceReportInputs.assemble(summary, labels, MAPPER, request, NOW);
    }

    private ZoneMatrixSummaryView.Labels masked() { return zoneMatrix.summaryLabels(View.MASKED, false); }
    private ZoneMatrixSummaryView.Labels raw() { return zoneMatrix.summaryLabels(View.NAMES, true); }

    /** Every string reachable from a value: record components, lists, maps, enums and instants. */
    static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        walk(value, out);
        return out;
    }

    private static void walk(Object v, List<String> out) {
        if (v == null) return;
        if (v instanceof String s) { out.add(s); return; }
        if (v instanceof Enum<?> e) { out.add(e.name()); return; }
        if (v instanceof Number || v instanceof Boolean || v instanceof Instant || v instanceof Duration) {
            out.add(String.valueOf(v));
            return;
        }
        if (v instanceof Iterable<?> it) { for (Object o : it) walk(o, out); return; }
        if (v instanceof Map<?, ?> m) { m.forEach((k, x) -> { walk(k, out); walk(x, out); }); return; }
        if (v.getClass().isRecord()) {
            for (RecordComponent c : v.getClass().getRecordComponents()) {
                try {
                    var accessor = c.getAccessor();
                    accessor.setAccessible(true);
                    walk(accessor.invoke(v), out);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
            return;
        }
        throw new AssertionError("unexpected type in the walk: " + v.getClass());
    }

    private static List<List<String>> table(Built built, String id) {
        return built.sections().stream().flatMap(s -> s.tables().stream()).filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow().rows().stream().map(r -> r.stream().map(ReportDocument.Cell::text).toList()).toList();
    }

    private static String headerValue(Built built, String keyPrefix) {
        return table(built, "header").stream().filter(r -> r.get(0).startsWith(keyPrefix)).findFirst().orElseThrow().get(1);
    }

    private static byte[] csv(Built built, String projection) {
        var doc = new ReportDocument("ZM-EVIDENCE-TEST", built.title(), "Compliance", "q", NOW, projection,
                Map.of(), List.of(), built.notices(), built.sections());
        return new CsvReportRenderer().render(doc);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // ------------------------------------------------------------------ ZM-31 projection half

    @Test
    void zm26_notEvaluableOnlyOnCurrentCompletePolicyNeverCarriesABareZeroHeadlineInTheReportSummary() {
        defaultVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(
                finding("P-CUR", "u-ne", 3, RULE_NAME, "NOT_EVALUABLE", null, List.of(), zoneB, zoneA)));
        StoredSummary summary = runService().storedSummary();
        assertEquals(0, summary.headline().distinctViolatingRules());
        assertEquals(1, summary.headline().notEvaluableFindings());

        String text = assemble(summary, masked(), request()).summary().headlineText();
        assertTrue(text.contains("no proven violations in evaluated rules. at least 0. Splits:"), text);
        assertFalse(text.contains(". 0. Splits:"), text);
        assertFalse(text.contains("0 rules violate"), text);
        assertTrue(text.endsWith("The count is a lower bound."), text);
    }

    @Test
    void zm26_currentCompletePolicyWithNothingUndecidedKeepsTheExactZeroHeadlineInTheReportSummary() {
        defaultVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of());
        String text = assemble(runService().storedSummary(), masked(), request()).summary().headlineText();
        assertTrue(text.contains("0 rules violate the zone matrix (v1). 0. Splits:"), text);
        assertTrue(text.endsWith("The count is exact."), text);
    }

    @Test
    void zm31_maskedAndRawInputsDifferAndTheMaskedOneCarriesNoPlantedNameAndNoViewCarriesWitnessData() {
        StoredSummary summary = fullFixture();
        assertTrue(summary.findings().stream().flatMap(f -> f.finding().violatedCells().stream())
                .anyMatch(c -> c.witness() != null && c.natView() != null), "fixture plants witness and NAT view");

        Input maskedInput = assemble(summary, masked(), request());
        Input rawInput = assemble(summary, raw(), request());
        assertNotEquals(maskedInput.cells(), rawInput.cells(), "zone names differ between the views");
        assertNotEquals(maskedInput.findings(), rawInput.findings(), "rule and policy names differ between the views");
        assertNotEquals(maskedInput.policies(), rawInput.policies());
        assertNotEquals(maskedInput.generatedBy(), rawInput.generatedBy());

        Built maskedBuilt = ZoneMatrixEvidenceReport.build(maskedInput, NOW);
        Built rawBuilt = ZoneMatrixEvidenceReport.build(rawInput, NOW);
        List<String> maskedStrings = new ArrayList<>(strings(maskedInput));
        maskedStrings.addAll(strings(maskedBuilt));
        for (String planted : List.of(ZONE_A_NAME, ZONE_B_NAME, ZONE_C_NAME, RULE_NAME, POLICY_NAME, SOURCE_NAME,
                CONTAINER_NAME, SET_NAME, CONFIRMER, PUBLISHER, EDITOR, CALLER, REFERENCE)) {
            for (String s : maskedStrings) {
                assertFalse(s.contains(planted), "the masked input or report carries a planted identity");
            }
        }
        List<String> rawStrings = new ArrayList<>(strings(rawInput));
        rawStrings.addAll(strings(rawBuilt));
        for (String planted : List.of(ZONE_A_NAME, RULE_NAME, POLICY_NAME, SET_NAME, CONFIRMER, REFERENCE)) {
            assertTrue(rawStrings.stream().anyMatch(s -> s.contains(planted)), "positive control: raw view shows names");
        }
        for (List<String> all : List.of(maskedStrings, rawStrings)) {
            for (String s : all) {
                for (String marker : ADDRESS_MARKERS) {
                    assertFalse(s.contains(marker), "an input or report carries a block, witness or NAT view");
                }
            }
        }
        for (var c : maskedInput.cells()) {
            assertTrue(c.sourceZone().matches("ZONE-\\d+") && c.destinationZone().matches("ZONE-\\d+"),
                    "masked zone names are ZONE-<n> pseudonyms");
        }
        for (FindingRow f : maskedInput.findings()) {
            for (String pair : f.cells()) assertTrue(pair.matches("ZONE-\\d+ -> ZONE-\\d+"), "masked pair names");
        }
        var setCell = maskedInput.cells().stream().filter(c -> c.services().size() == 2).findFirst().orElseThrow();
        assertEquals(List.of("set:SET-1", "tcp/443"), setCell.services(), "service set names are pseudonymised");
        assertTrue(maskedInput.cells().stream().anyMatch(c -> c.regulationReference()
                .equals(ZoneMatrixEvidenceReportInputs.WITHHELD_REFERENCE)), "the masked view withholds reference text");
        assertEquals("USER-1", maskedInput.generatedBy());
        assertEquals(ZoneMatrixEvidenceReport.confirmedLine(rawInput.cells()),
                ZoneMatrixEvidenceReport.confirmedLine(maskedInput.cells()), "the confirmed count is the same in both views");
        assertEquals(maskedInput, assemble(summary, masked(), request()), "deterministic");
    }

    // ------------------------------------------------------------------ undecided, counts, unreadable names

    @Test
    void undecidedRulesStayAndMissingCountsAndUnreadableZonesPrintUnknown() {
        StoredSummary summary = fullFixture();
        Input in = assemble(summary, raw(), request());
        assertEquals(summary.findings().size(), in.findings().size(), "nothing dropped, nothing capped");
        FindingRow ne = in.findings().stream().filter(f -> "NOT_EVALUABLE".equals(f.outcome())).findFirst().orElseThrow();
        FindingRow unk = in.findings().stream().filter(f -> "UNKNOWN".equals(f.outcome())).findFirst().orElseThrow();
        assertEquals("NOT_EVALUABLE planted reason u-ne", ne.reason());
        assertEquals("UNKNOWN planted reason u-unk", unk.reason());
        assertNull(ne.lifecycle(), "a standalone undecided row has no stored lifecycle");
        assertNotNull(in.findings().stream().filter(f -> "VIOLATION".equals(f.outcome())).findFirst().orElseThrow()
                .lifecycle(), "a violation carries its stored lifecycle dates");

        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        var findings = table(built, "findings");
        assertTrue(findings.stream().anyMatch(r -> r.contains("NOT_EVALUABLE") && r.contains("NOT_EVALUABLE planted reason u-ne")
                && r.contains("UNKNOWN (no stored finding)")));
        assertTrue(findings.stream().anyMatch(r -> r.contains("UNKNOWN") && r.contains("UNKNOWN planted reason u-unk")));

        // P-NONE has no COMPLETE run: the whole-scope count cannot be given.
        assertNull(in.wholeScopeFindingCount());
        assertNull(in.filteredFindingCount());
        assertEquals("UNKNOWN", headerValue(built, "Findings in the whole scope"));
        assertEquals("UNKNOWN", headerValue(built, "Findings in the filtered scope"));
        assertTrue(in.notEvaluated().stream().anyMatch(n -> n.reason().startsWith("no COMPLETE evaluation run")));
        assertTrue(in.notEvaluated().stream().anyMatch(n -> n.reason().equals(ZoneMatrixRunService.NOT_EVALUATED_REASON)),
                "the ASA policy is listed with the stored reason");

        // A filter on a fully evaluated policy gives exact counts.
        Input cur = assemble(summary, raw(), new Request("9.9.9-test", CALLER, null, null, "P-CUR", null, null, null));
        assertEquals(2, cur.filteredFindingCount());
        assertEquals(2, cur.findings().size());
        assertTrue(cur.filterDescription().contains(POLICY_NAME + "-P-CUR"));
        // Editors not read: UNKNOWN, never an empty list presented as "nobody".
        assertEquals("UNKNOWN", table(ZoneMatrixEvidenceReport.build(cur, NOW), "matrix-version").stream()
                .filter(r -> r.get(0).equals("Editors")).findFirst().orElseThrow().get(1));

        // A finding naming a zone the version does not contain prints UNKNOWN.
        catalog.clear();
        policy("P-GAP", "CP", hoursAgo(1));
        completeRun("P-GAP", "COMPLETE", 0, List.of(finding("P-GAP", "u-gap", 9, RULE_NAME, "VIOLATION", "HIGH",
                List.of(), "zone-not-in-version", zoneA)));
        Input gap = assemble(runService().storedSummary(), masked(), request());
        assertEquals(1, gap.wholeScopeFindingCount());
        assertTrue(gap.findings().get(0).cells().get(0).startsWith("UNKNOWN -> ZONE-"), "an unreadable zone name is UNKNOWN");
        var gapRow = table(ZoneMatrixEvidenceReport.build(gap, NOW), "findings").get(0);
        assertTrue(gapRow.get(2).startsWith("UNKNOWN -> "));
    }

    @Test
    void zm26_aCompleteRunOverIncompleteCoverageNeverPrintsAnExactCountAndNeitherDoesAnEmptyScope() {
        // COMPLETE run, INCOMPLETE coverage, no findings: an exact 0 would read as "nothing found".
        defaultVersion();
        policy("P-TRUNC", "CP", hoursAgo(1));
        completeRun("P-TRUNC", "INCOMPLETE", 0, List.of());
        Input whole = assemble(runService().storedSummary(), raw(), request());
        assertNull(whole.wholeScopeFindingCount());
        assertNull(whole.filteredFindingCount());
        Input filtered = assemble(runService().storedSummary(), raw(),
                new Request("9.9.9-test", CALLER, null, null, "P-TRUNC", null, null, null));
        assertNull(filtered.filteredFindingCount());
        assertNull(filtered.wholeScopeFindingCount());
        Built built = ZoneMatrixEvidenceReport.build(whole, NOW);
        assertEquals("UNKNOWN", headerValue(built, "Findings in the whole scope"));
        assertEquals("UNKNOWN", headerValue(built, "Findings in the filtered scope"));

        // One complete-coverage policy beside the truncated one: the whole scope is still unknown, the complete one exact.
        policy("P-OK", "CP", hoursAgo(1));
        completeRun("P-OK", "COMPLETE", 0, List.of());
        StoredSummary mixed = runService().storedSummary();
        assertNull(assemble(mixed, raw(), request()).wholeScopeFindingCount());
        assertEquals(0, assemble(mixed, raw(),
                new Request("9.9.9-test", CALLER, null, null, "P-OK", null, null, null)).filteredFindingCount());

        // Collection failures downgrade coverage the same way.
        catalog.clear();
        policy("P-FAIL", "CP", hoursAgo(1));
        completeRun("P-FAIL", "COMPLETE", 2, List.of());
        assertNull(assemble(runService().storedSummary(), raw(), request()).wholeScopeFindingCount());

        // No evaluable policy at all: nothing was evaluated, so 0 is not a count.
        catalog.clear();
        policy("P-ASA", "ASA", hoursAgo(1));
        StoredSummary none = runService().storedSummary();
        assertTrue(none.policies().isEmpty());
        Input empty = assemble(none, raw(), request());
        assertNull(empty.wholeScopeFindingCount());
        assertNull(empty.filteredFindingCount());

        // The all-COMPLETE case stays exact.
        catalog.clear();
        policy("P-GOOD", "CP", hoursAgo(1));
        completeRun("P-GOOD", "COMPLETE", 0, List.of());
        Input good = assemble(runService().storedSummary(), raw(), request());
        assertEquals(0, good.wholeScopeFindingCount());
        assertEquals(0, good.filteredFindingCount());
    }

    @Test
    void aPeriodReportListsVersionChangeResolutionsAndStatesItsCountsAsUnknown() {
        StoredSummary summary = fullFixture();
        Input in = assemble(summary, raw(), new Request("9.9.9-test", CALLER, NOW.minus(Duration.ofDays(7)), NOW,
                null, null, null, null));
        assertNull(in.wholeScopeFindingCount());
        assertNull(in.filteredFindingCount());
        assertTrue(in.filterDescription().contains("only those resolved by a matrix version change"));
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        assertEquals(summary.findings().size(), table(built, "findings").size(), "every open finding is in the period");
    }

    // ------------------------------------------------------------------ ZM-32 resolved findings of every reason

    private static final Instant PERIOD_FROM = NOW.minus(Duration.ofDays(7));

    /** A stored VIOLATION that was resolved at {@code resolvedAt} for {@code reason} (a raw string, so unknown ones work). */
    private Finding resolved(String policy, String uid, int number, String reason, Instant resolvedAt) {
        Finding f = finding(policy, uid, number, RULE_NAME, "VIOLATION", "HIGH", List.of(), zoneB, zoneA);
        return new Finding(f.policySourceId(), f.ruleUid(), f.inlineParentPath(), f.violatedPairs(), f.ruleId(),
                f.ruleNumber(), f.ruleName(), f.sectionId(), f.sectionName(), f.targetDeviceIds(), f.outcome(),
                f.reason(), f.highestSeverity(), f.flags(), f.coveringRuleId(), f.violatedCells(), f.undecidedParts(),
                f.natView(), resolvedAt.minus(Duration.ofDays(2)), resolvedAt.minus(Duration.ofDays(3)),
                resolvedAt.minus(Duration.ofMinutes(5)), resolvedAt, reason);
    }

    private ZoneMatrixRunService periodFixture(Finding... resolvedFindings) {
        defaultVersion();
        policy("P-CUR", "CP", hoursAgo(1));
        List<Finding> all = new ArrayList<>();
        all.add(finding("P-CUR", "u-open", 1, RULE_NAME, "VIOLATION", "LOW", List.of(), zoneB, zoneA));
        all.addAll(List.of(resolvedFindings));
        completeRun("P-CUR", "COMPLETE", 0, all);
        return runService();
    }

    private Input periodInput(ZoneMatrixRunService service, List<ZoneMatrixRunRepository.ResolvedFinding> read,
            ZoneMatrixSummaryView.Labels labels) {
        return ZoneMatrixEvidenceReportInputs.assemble(service.storedSummary(), read, labels, MAPPER,
                new Request("9.9.9-test", CALLER, PERIOD_FROM, NOW, null, null, null, null), NOW);
    }

    @Test
    void zm32_aPeriodReportListsFindingsResolvedForEveryReasonInsideThePeriodExactlyOnce() {
        Instant changed = NOW.minus(Duration.ofDays(1));
        Instant removed = NOW.minus(Duration.ofDays(2));
        Instant versionChange = NOW.minus(Duration.ofDays(3));
        ZoneMatrixRunService service = periodFixture(
                resolved("P-CUR", "u-changed", 11, "RULE_CHANGED", changed),
                resolved("P-CUR", "u-removed", 12, "RULE_REMOVED_OR_DISABLED", removed),
                resolved("P-CUR", "u-version", 13, "MATRIX_VERSION_CHANGED", versionChange),
                resolved("P-CUR", "u-before", 14, "RULE_CHANGED", PERIOD_FROM.minus(Duration.ofSeconds(1))),
                resolved("P-CUR", "u-after", 15, "RULE_CHANGED", NOW));
        var read = service.resolvedInPeriod(PERIOD_FROM, NOW);
        assertEquals(3, read.size(), "the half-open period holds the first three only");

        // The stores list one finding twice (an older chain row, and the version change also from the summary read).
        List<ZoneMatrixRunRepository.ResolvedFinding> twice = new ArrayList<>(read);
        twice.add(read.get(0));
        twice.addAll(read);
        Input in = periodInput(service, twice, raw());
        assertTrue(in.filterDescription().contains("for any reason"));
        assertNull(in.wholeScopeFindingCount());
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        var rows = table(built, "findings");
        assertEquals(4, rows.size(), "one open finding and three resolved ones, each once");
        for (String number : List.of("#11", "#12", "#13")) {
            assertEquals(1, rows.stream().filter(r -> r.get(1).contains("(rule " + number + ")")).count(), number);
        }
        assertTrue(rows.stream().noneMatch(r -> r.get(1).contains("(rule #14)") || r.get(1).contains("(rule #15)")),
                "resolved before the period or at its exclusive end: absent");
        for (var row : rows) {
            if (row.get(1).contains("(rule #1)")) continue;
            assertEquals("RESOLVED", row.get(9));
            assertFalse(row.get(10).isBlank(), "first observed date");
            assertFalse(row.get(11).isBlank(), "first observed in any version date");
            assertFalse(row.get(12).isBlank(), "last observed date");
            assertFalse(row.get(13).isBlank(), "resolved date");
        }
        assertEquals("RULE_CHANGED", reasonOf(rows, "#11"));
        assertEquals("RULE_REMOVED_OR_DISABLED", reasonOf(rows, "#12"));
        assertEquals("MATRIX_VERSION_CHANGED", reasonOf(rows, "#13"));
        assertEquals("", reasonOf(rows, "#1"), "an open finding has no resolution reason");
    }

    private static String reasonOf(List<List<String>> rows, String number) {
        return rows.stream().filter(r -> r.get(1).contains("(rule " + number + ")")).findFirst().orElseThrow().get(14);
    }

    @Test
    void zm32_aVersionChangeResolutionTheSummaryAlreadyListsIsNotListedAgain() {
        ZoneMatrixRunService service = periodFixture(
                resolved("P-CUR", "u-version", 13, "MATRIX_VERSION_CHANGED", NOW.minus(Duration.ofDays(3))));
        StoredSummary summary = service.storedSummary();
        assertEquals(1, summary.resolvedByVersionChangeByVersion().values().stream().mapToInt(List::size).sum());
        Input in = ZoneMatrixEvidenceReportInputs.assemble(summary, service.resolvedInPeriod(PERIOD_FROM, NOW), raw(),
                MAPPER, new Request("9.9.9-test", CALLER, PERIOD_FROM, NOW, null, null, null, null), NOW);
        var rows = table(ZoneMatrixEvidenceReport.build(in, NOW), "findings");
        assertEquals(1, rows.stream().filter(r -> r.get(1).contains("(rule #13)")).count());
    }

    @Test
    void zm32_aResolutionReasonTheEnumDoesNotKnowIsKeptAndPrintedAsUnknown() {
        ZoneMatrixRunService service = periodFixture(
                resolved("P-CUR", "u-future", 16, "RULE_SPLIT_BY_A_FUTURE_ENGINE", NOW.minus(Duration.ofDays(1))));
        Input in = periodInput(service, service.resolvedInPeriod(PERIOD_FROM, NOW), raw());
        var rows = table(ZoneMatrixEvidenceReport.build(in, NOW), "findings");
        assertEquals("UNKNOWN", reasonOf(rows, "#16"));
        assertEquals("RESOLVED", rows.stream().filter(r -> r.get(1).contains("(rule #16)")).findFirst().orElseThrow().get(9));
    }

    @Test
    void zm32_theMaskedViewMasksTheNamesOfEveryResolvedRowToo() {
        ZoneMatrixRunService service = periodFixture(
                resolved("P-CUR", "u-changed", 11, "RULE_CHANGED", NOW.minus(Duration.ofDays(1))),
                resolved("P-CUR", "u-removed", 12, "RULE_REMOVED_OR_DISABLED", NOW.minus(Duration.ofDays(2))));
        Input in = periodInput(service, service.resolvedInPeriod(PERIOD_FROM, NOW), masked());
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        assertEquals(3, table(built, "findings").size());
        List<String> all = new ArrayList<>(strings(in));
        built.sections().forEach(sec -> sec.tables().forEach(t -> t.rows().forEach(r ->
                r.forEach(c -> all.add(c.text())))));
        for (String planted : List.of(RULE_NAME, ZONE_A_NAME, ZONE_B_NAME, ZONE_C_NAME, POLICY_NAME, SOURCE_NAME,
                CONTAINER_NAME)) {
            assertTrue(all.stream().noneMatch(v -> v != null && v.contains(planted)), planted);
        }
        for (String marker : ADDRESS_MARKERS) {
            assertTrue(all.stream().noneMatch(v -> v != null && v.contains(marker)), marker);
        }
    }

    @Test
    void zm32_withoutThePeriodReadOnlyVersionChangeResolutionsAreListedAndTheDescriptionSaysSo() {
        ZoneMatrixRunService service = periodFixture(
                resolved("P-CUR", "u-changed", 11, "RULE_CHANGED", NOW.minus(Duration.ofDays(1))));
        Input in = periodInput(service, null, raw());
        assertTrue(in.filterDescription().contains("only those resolved by a matrix version change"));
        assertEquals(1, table(ZoneMatrixEvidenceReport.build(in, NOW), "findings").size());
    }

    @Test
    void anUnknownPolicyOrOutcomeFilterIsRefused() {
        StoredSummary summary = fullFixture();
        var policy = assertThrows(ZoneMatrixService.Refused.class, () -> assemble(summary, masked(),
                new Request(null, null, null, null, "P-MISSING", null, null, null)));
        assertEquals("UNKNOWN_POLICY", policy.code);
        var outcome = assertThrows(ZoneMatrixService.Refused.class, () -> assemble(summary, masked(),
                new Request(null, null, null, null, null, "COMPLIANT", null, null)));
        assertEquals("INVALID_OUTCOME", outcome.code);
    }

    // ------------------------------------------------------------------ staleness

    @Test
    void anOldSnapshotAndAFailedLastRunAreStaleAndTheInterimThresholdIsLabelled() {
        defaultVersion();
        policy("P-OLD", "PAN", hoursAgo(30));
        policy("P-FAIL", "CP", hoursAgo(1));
        policy("P-OK", "CP", hoursAgo(1));
        completeRun("P-OLD", "COMPLETE", 0, List.of());
        completeRun("P-FAIL", "COMPLETE", 0, List.of());
        failedRun("P-FAIL");
        completeRun("P-OK", "COMPLETE", 0, List.of());
        StoredSummary summary = runService().storedSummary();
        assertEquals(ZoneMatrixStaleness.INTERIM_PO_2026_11_23, summary.stalenessThreshold());

        Input in = assemble(summary, raw(), request());
        Map<String, PolicyScope> byName = new LinkedHashMap<>();
        for (PolicyScope p : in.policies()) byName.put(p.policy().substring(0, p.policy().indexOf(" (container")), p);
        PolicyScope old = byName.get(POLICY_NAME + "-P-OLD");
        assertEquals(Verdict.STALE, old.staleness().verdict());
        assertTrue(old.staleness().reasons().contains(Reason.SNAPSHOT_OLDER_THAN_THRESHOLD));
        assertEquals(Duration.ofHours(30), old.staleness().snapshotAge());
        PolicyScope failed = byName.get(POLICY_NAME + "-P-FAIL");
        assertEquals(Verdict.STALE, failed.staleness().verdict());
        assertEquals(List.of(Reason.LAST_RUN_FAILED), failed.staleness().reasons());
        assertEquals(Verdict.CURRENT, byName.get(POLICY_NAME + "-P-OK").staleness().verdict());

        assertEquals(Duration.ofHours(24), in.threshold().value());
        assertEquals(ZoneMatrixSummaryView.INTERIM_THRESHOLD_LABEL, in.threshold().source());
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        String expected = "Snapshot staleness threshold: 24 h 0 min (" + ZoneMatrixSummaryView.INTERIM_THRESHOLD_LABEL + ").";
        assertTrue(built.notices().contains(expected), "the threshold notice carries the interim label");
        var scope = table(built, "scope-policies");
        assertTrue(scope.stream().anyMatch(r -> r.get(0).startsWith(POLICY_NAME + "-P-FAIL")
                && r.get(6).equals("STALE (LAST_RUN_FAILED)")));
        assertTrue(scope.stream().anyMatch(r -> r.get(0).startsWith(POLICY_NAME + "-P-OLD")
                && r.get(6).equals("STALE (SNAPSHOT_OLDER_THAN_THRESHOLD)") && r.get(4).equals("30 h 0 min")));
        assertEquals(0, in.wholeScopeFindingCount(), "every policy has a COMPLETE run: an exact zero, not UNKNOWN");
    }

    // ------------------------------------------------------------------ ZM-28 formula-guard half

    @Test
    void zm28_plantedFormulaReferencesAndRuleNamesAreEscapedByTheR1CsvGuardAndTheBytesHashIsReproducible()
            throws Exception {
        String cmd = "=cmd|' /c calc'!A0";
        String plus = "+1";
        String minus = "-1";
        String at = "@SUM(1)";
        activeVersion(cmd, "PROPOSED", plus, "PROPOSED", minus, "PROPOSED", at, "PROPOSED");
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(
                finding("P-CUR", "u-1", null, cmd, "VIOLATION", "HIGH", List.of(), zoneA, zoneB),
                finding("P-CUR", "u-2", null, plus, "VIOLATION", "HIGH", List.of(), zoneB, zoneA),
                finding("P-CUR", "u-3", null, minus, "VIOLATION", "LOW", List.of(), zoneB, zoneC),
                finding("P-CUR", "u-4", null, at, "VIOLATION", "LOW", List.of(), zoneC, zoneA)));
        Input in = assemble(runService().storedSummary(), raw(), request());
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        for (String planted : List.of(cmd, plus, minus, at)) {
            assertTrue(in.cells().stream().anyMatch(c -> planted.equals(c.regulationReference())),
                    "the reference passes exactly as entered");
            assertTrue(in.findings().stream().anyMatch(f -> planted.equals(f.rule())), "the rule name passes unchanged");
        }

        byte[] bytes = csv(built, "RAW");
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String planted : List.of(cmd, plus, minus, at)) {
            String quoted = planted.replace("\"", "\"\"");
            assertTrue(text.contains("\"'" + quoted + "\""), "a planted formula is escaped by the R1 guard");
            assertFalse(text.contains("\"" + quoted + "\""), "a planted formula reaches the CSV unescaped");
        }
        for (String line : text.split("\r\n")) {
            for (String field : line.split("\",\"")) {
                String f = field.startsWith("\"") ? field.substring(1) : field;
                assertFalse(f.matches("(?s)^[\\s\\p{Cc}]*[=+@-].*"), "an unescaped formula-leading field");
            }
        }
        String hash = sha256(bytes);
        assertEquals(64, hash.length());
        assertEquals(hash, sha256(csv(ZoneMatrixEvidenceReport.build(
                assemble(runService().storedSummary(), raw(), request()), NOW), "RAW")),
                "the CSV bytes and their sha256 are reproducible from the same stored data");
    }

    // ------------------------------------------------------------------ ZM-30 pin

    @Test
    void zm30_titleIsGenericWhileAReferenceIsUnconfirmedAndNoTextClaimsCompliance() {
        Regulation regulation = new Regulation("Planted regulation on information systems", "2020-03-15");
        StoredSummary summary = fullFixture();
        Input in = assemble(summary, masked(), new Request("9.9.9-test", CALLER, null, null, null, null, regulation, null));
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, built.title(), "one referenced cell is only PROPOSED");
        assertNoComplianceClaim(built);

        versions.rows.clear();
        catalog.clear();
        activeVersion("", "EMPTY", "", "EMPTY", "", "EMPTY", "", "EMPTY");
        Built empty = ZoneMatrixEvidenceReport.build(assemble(runService().storedSummary(), raw(),
                new Request("9.9.9-test", CALLER, null, null, null, null, regulation, null)), NOW);
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, empty.title(), "no referenced cell never earns the word");
        assertNoComplianceClaim(empty);

        versions.rows.clear();
        activeVersion(REFERENCE, "CONFIRMED", REFERENCE, "CONFIRMED", "", "EMPTY", "", "EMPTY");
        Built confirmed = ZoneMatrixEvidenceReport.build(assemble(runService().storedSummary(), masked(),
                new Request("9.9.9-test", CALLER, null, null, null, null, regulation, null)), NOW);
        assertNotEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, confirmed.title(), "positive control: every reference confirmed");
        assertNoComplianceClaim(confirmed);
    }

    private static void assertNoComplianceClaim(Built built) {
        for (String s : strings(built)) {
            String lower = s.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("bddk compliant") || lower.contains("bddk pass") || lower.contains("score")
                    || lower.contains("%") || lower.contains("percent"), "a report text claims compliance or a figure");
        }
    }
}
