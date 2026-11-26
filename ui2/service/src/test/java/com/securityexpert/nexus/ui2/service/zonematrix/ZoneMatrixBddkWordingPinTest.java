package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.Version;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Counts;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Coverage;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Finding;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.NewRun;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.report.CsvReportRenderer;
import com.securityexpert.nexus.ui2.service.report.ReportDocument;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Built;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Input;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Regulation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReportInputs.Request;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixRunService.StoredSummary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PolicyCatalog;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ZM-30}, the surface-wide half (ZONE_MATRIX_CONTRACT.md 10.2, 6.1, 7.2, 7.3): no surface shows "BDDK compliant",
 * "BDDK pass" or a BDDK score; the report title omits BDDK while any referenced cell is unconfirmed, and the header prints
 * "N of M cells confirmed by compliance".
 *
 * <p>Two halves. (a) A source scan: every string literal of the zone matrix main sources (service, controller, policy
 * bridge, persistence, the CLI) is checked against the forbidden set; adjacent literals joined by {@code +} are checked as
 * one string. (b) Behavioural: the stored summary view, the evidence report content and its CSV for fixtures with
 * unconfirmed and confirmed cells, an incomplete run, a stale policy, a failed run, a NOT_EVALUABLE and an UNKNOWN rule.
 *
 * <p>Already pinned elsewhere and cited, not repeated: the title and the header line in isolation
 * ({@code ZoneMatrixEvidenceReportTest#zm30_titleIsGenericWhenTheRegulationIsNotNamed},
 * {@code #zm30_titleIsGenericWhileAnyReferencedCellIsNotConfirmed}, {@code #zm30_bddkAppearsOnlyWhenNamedAndEveryReferencedCellIsConfirmed},
 * {@code #confirmedLineCountsOnlyCellsThatCarryAReference}), the headline text
 * ({@code ZoneMatrixEvaluatorTest#zm19_zm21_zm27_zm30_...}) and the Overview/screen side (frontend
 * {@code ZoneMatrixBddkWordingPin.test.tsx}). Synthetic names and documentation ranges only.
 */
class ZoneMatrixBddkWordingPinTest {
    private static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The forbidden set (one definition per test file, the frontend file carries the same one): BDDK followed within the
     * same sentence by up to three words and then compliant, compliance, pass, passed, score, uyumlu, uygun, gecti
     * (with the Turkish letters), puan or a percent figure; a bare "BDDK compliant" is the zero-word case.
     */
    static final Pattern FORBIDDEN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])bddk(?![\\p{L}\\p{N}])(?:[^\\p{L}\\p{N}.!?;\\n]*[\\p{L}\\p{N}_]+){0,3}?[^\\p{L}\\p{N}.!?;\\n]*"
                    + "(?:(?<![\\p{L}\\p{N}])(?:complian(?:t|ce)|pass(?:ed)?(?![\\p{L}])|score(?![\\p{L}])|uyumlu|uygun|ge\\u00e7t[i\\u0130\\u0131]|puan)"
                    + "|\\d[\\d.,]*\\s*%|%\\s*\\d)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // Planted synthetic identities (no BDDK word in any reference: the pin below needs the word to be absent).
    private static final String ZONE_A_NAME = "Z-PLANTED-CARDHOLDER";
    private static final String ZONE_B_NAME = "Z-PLANTED-BRANCH";
    private static final String ZONE_C_NAME = "Z-PLANTED-DMZ";
    private static final String RULE_NAME = "planted-rule-fw-tango-04-db";
    private static final String POLICY_NAME = "planted-policy-standard";
    private static final String SOURCE_NAME = "fw-tango-04.planted.example";
    private static final String CONTAINER_NAME = "planted-domain-juliet";
    private static final String CONFIRMER = "planted-compliance-officer";
    private static final String PUBLISHER = "planted-publisher";
    private static final String EDITOR = "planted-editor";
    private static final String CALLER = "planted-caller";
    private static final String REFERENCE = "PLANTED-ARTICLE-12";

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

    // ------------------------------------------------------------------ the scanner and its own controls

    /** The string literals of a Java source (comments, char literals skipped; text blocks supported; {@code "a" + "b"} joined). */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        int lastEnd = -1;
        StringBuilder current = null;
        while (i < n) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int e = src.indexOf('\n', i);
                i = e < 0 ? n : e;
                continue;
            }
            if (src.startsWith("/*", i)) {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') i += src.charAt(i) == '\\' ? 2 : 1;
                i++;
                continue;
            }
            if (c == '"') {
                int start = i;
                String value;
                if (src.startsWith("\"\"\"", i)) {
                    int e = i + 3;
                    while (e < n && !(src.startsWith("\"\"\"", e) && src.charAt(e - 1) != '\\')) e++;
                    value = src.substring(i + 3, Math.min(e, n));
                    i = Math.min(e + 3, n);
                } else {
                    int e = i + 1;
                    while (e < n && src.charAt(e) != '"') e += src.charAt(e) == '\\' ? 2 : 1;
                    value = src.substring(i + 1, Math.min(e, n));
                    i = Math.min(e + 1, n);
                }
                boolean joined = current != null && lastEnd >= 0 && src.substring(lastEnd, start).strip().equals("+");
                if (joined) {
                    current.append(value);
                } else {
                    if (current != null) out.add(current.toString());
                    current = new StringBuilder(value);
                }
                lastEnd = i;
                continue;
            }
            i++;
        }
        if (current != null) out.add(current.toString());
        return out;
    }

    private static boolean forbidden(String text) { return FORBIDDEN.matcher(text).find(); }

    @Test
    void zm30_theForbiddenSetCatchesPlantedPhrasesAndAllowsTheContractsOwnWording() {
        for (String bad : List.of("BDDK compliant", "bddk COMPLIANT", "This is BDDK-compliant.", "BDDK pass", "BDDK passed",
                "BDDK score: 3", "the BDDK score", "BDDK 95%", "BDDK %95", "BDDK uyumlu", "BDDK'ya uygun", "BDDK geçti",
                "BDDK GEÇTİ", "BDDK puanı", "BDDK compliance status", "Overall BDDK result is a pass",
                "BDDK regulation shows 80 %")) {
            assertTrue(forbidden(bad), "must be forbidden: " + bad);
        }
        for (String ok : List.of("Segmentation policy evidence report - BDDK regulation: Planted regulation, 2020-03-15",
                "Overwatch proposal based on general segmentation practice, not on a BDDK clause",
                "1 of 2 cells confirmed by compliance", "BDDK-planted-article-12", "BDDK regulation. The cell is compliant.",
                "BDDK reference withheld; password rules", "bddkcompliant", "BDDK 95")) {
            assertFalse(forbidden(ok), "must stay allowed: " + ok);
        }
        List<String> found = literals("class A { String a = \"BDDK \" + \"pass\"; String b = \"ok\"; // \"BDDK score\"\n"
                + "String c = \"\"\"\n  BDDK compliant\n  \"\"\"; char q = '\"'; String d = \"x\\\"y\"; }");
        assertEquals(List.of("BDDK pass", "ok", "\n  BDDK compliant\n  ", "x\\\"y"), found,
                "the scanner joins concatenated literals, skips comments and char literals and reads text blocks");
        assertEquals(2, found.stream().filter(ZoneMatrixBddkWordingPinTest::forbidden).count());
    }

    // ------------------------------------------------------------------ (a) source scan

    private static Path module(String relative) { return Path.of(System.getProperty("user.dir")).resolve(relative).normalize(); }

    private static List<Path> javaFiles(Path dir, String namePattern) throws IOException {
        assertTrue(Files.isDirectory(dir), "a scanned source directory moved: " + dir);
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.getFileName().toString().matches(namePattern)).sorted().toList();
        }
    }

    /** Every zone matrix surface of the main sources: where each package or file lives is named here once. */
    private static List<Path> surfaces() throws IOException {
        String svc = "src/main/java/com/securityexpert/nexus/ui2/service/";
        List<Path> files = new ArrayList<>();
        files.addAll(javaFiles(module(svc + "zonematrix"), ".*\\.java"));
        files.addAll(javaFiles(module(svc + "api"), "ZoneMatrix.*\\.java"));
        files.addAll(javaFiles(module(svc + "policy"), "ZoneMatrix.*\\.java"));
        files.addAll(javaFiles(module(svc + "boot"), "ZoneMatrix.*\\.java"));
        files.addAll(javaFiles(module("../persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/zonematrix"), ".*\\.java"));
        files.addAll(javaFiles(module("../cli/src/main/java/com/securityexpert/nexus/ui2/cli"), "ZonesMatrixCli\\.java"));
        return files;
    }

    @Test
    void zm30_noStringLiteralOfAnyZoneMatrixSurfaceSaysBddkCompliantPassOrAScore() throws IOException {
        List<Path> files = surfaces();
        for (String must : List.of("ZoneMatrixSummaryView.java", "ZoneMatrixEvidenceReport.java", "ZoneMatrixEvidenceReportInputs.java",
                "ZoneMatrixService.java", "ZoneMatrixController.java", "ZoneMatrixTemplate.java", "ZonesMatrixCli.java")) {
            assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals(must)), "scan misses " + must);
        }
        int checked = 0;
        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            for (String literal : literals(Files.readString(file, StandardCharsets.UTF_8))) {
                checked++;
                if (forbidden(literal)) hits.add(file.getFileName() + ": a string literal matches the forbidden set");
            }
        }
        assertTrue(checked > 500, "the scan read the literals of the zone matrix sources: " + checked);
        assertEquals(List.of(), hits);
    }

    // ------------------------------------------------------------------ (b) behavioural fixture

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

    /** Active version with three zones and four cells whose references and statuses are given. */
    private void activeVersion(String refAB, String statusAB, String refBA, String statusBA) {
        var zones = List.of(
                new ZoneMatrixContent.Zone(zoneA, ZONE_A_NAME, "planted description", "DATABASE",
                        List.of(ZoneBlock.parse("192.0.2.0/25"))),
                new ZoneMatrixContent.Zone(zoneB, ZONE_B_NAME, "planted description", "USER",
                        List.of(ZoneBlock.parse("198.51.100.0/24"))),
                new ZoneMatrixContent.Zone(zoneC, ZONE_C_NAME, "planted description", "DMZ",
                        List.of(ZoneBlock.parse("192.0.2.128/25"))));
        var cells = List.of(
                cell(zoneA, zoneB, "DENY_ALL", List.of(), "CRITICAL", refAB, statusAB),
                cell(zoneB, zoneA, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", refBA, statusBA),
                cell(zoneB, zoneC, "DENY_ALL", List.of(), "MEDIUM", "", "EMPTY"),
                cell(zoneC, zoneA, "DENY_ALL", List.of(), "LOW", "", "EMPTY"));
        var matrix = new ZoneMatrixContent.Matrix("DENY_ALL", "MEDIUM", null, List.of(), cells);
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

    private Finding finding(String policy, String uid, Integer number, String outcome, String severity, List<String> flags,
            String from, String to) {
        boolean violation = outcome.equals("VIOLATION");
        List<Cell> cells = violation ? List.of(new Cell(from, to, "DENY_ALL", severity, List.of("tcp/22"), null, null)) : List.of();
        return new Finding(policy, uid, List.of(), List.of(from + "->" + to), "rule-id-" + uid, number, RULE_NAME, "sec-1",
                "Pre rules", List.of("dev-" + policy), outcome, violation ? "statement DENY_ALL" : outcome + " planted reason " + uid,
                severity, flags, null, cells, violation ? List.of() : List.of("IPv6 not evaluated"), null,
                NOW.minus(Duration.ofDays(2)), violation ? NOW.minus(Duration.ofDays(3)) : null,
                NOW.minus(Duration.ofMinutes(5)), null, null);
    }

    /**
     * Current policy with two violations, stale policy with a NOT_EVALUABLE rule, incomplete policy with an UNKNOWN rule that
     * became undecided, a failed-only policy; cells: one PROPOSED, one CONFIRMED, two without a reference.
     */
    private StoredSummary unconfirmedFixture() {
        activeVersion(REFERENCE, "PROPOSED", REFERENCE, "CONFIRMED");
        policy("P-CUR", "CP", hoursAgo(1));
        policy("P-STALE", "PAN", hoursAgo(30));
        policy("P-INC", "CP", hoursAgo(2));
        policy("P-NONE", "CP", hoursAgo(1));
        policy("P-ASA", "ASA", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of(
                finding("P-CUR", "u-low", 2, "VIOLATION", "LOW", List.of(), zoneB, zoneA),
                finding("P-CUR", "u-crit", 7, "VIOLATION", "CRITICAL", List.of("COVERED_BY_EARLIER_DENY"), zoneA, zoneB)));
        completeRun("P-STALE", "COMPLETE", 0, List.of(finding("P-STALE", "u-ne", 3, "NOT_EVALUABLE", null, List.of(), zoneB, zoneA)));
        completeRun("P-INC", "INCOMPLETE", 1,
                List.of(finding("P-INC", "u-unk", 5, "UNKNOWN", null, List.of("BECAME_UNDECIDED"), zoneB, zoneA)));
        failedRun("P-NONE");
        return runService().storedSummary();
    }

    /** Every cell with a reference confirmed, every policy current and complete, no finding: the cleanest stored state. */
    private StoredSummary confirmedCleanFixture() {
        activeVersion(REFERENCE, "CONFIRMED", REFERENCE, "CONFIRMED");
        policy("P-CUR", "CP", hoursAgo(1));
        completeRun("P-CUR", "COMPLETE", 0, List.of());
        return runService().storedSummary();
    }

    private ZoneMatrixSummaryView.Labels masked() { return zoneMatrix.summaryLabels(View.MASKED, false); }
    private ZoneMatrixSummaryView.Labels raw() { return zoneMatrix.summaryLabels(View.NAMES, true); }

    private static Request request(Regulation regulation) {
        return new Request("9.9.9-test", CALLER, null, null, null, null, regulation, null);
    }

    private static List<String> texts(JsonNode node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node.isValueNode()) {
            out.add(node.asText());
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> { out.add(e.getKey()); collect(e.getValue(), out); });
        } else {
            node.forEach(child -> collect(child, out));
        }
    }

    private static byte[] csv(Built built, String projection) {
        var doc = new ReportDocument("ZM-EVIDENCE-TEST", built.title(), "Compliance", "q", NOW, projection,
                Map.of(), List.of(), built.notices(), built.sections());
        return new CsvReportRenderer().render(doc);
    }

    private static String header(Built built, String field) {
        return built.sections().stream().flatMap(s -> s.tables().stream()).filter(t -> t.id().equals("header")).findFirst()
                .orElseThrow().rows().stream().filter(r -> r.get(0).text().equals(field)).findFirst().orElseThrow().get(1).text();
    }

    private static void assertClean(String what, List<String> produced) {
        assertFalse(produced.isEmpty(), what + ": nothing was produced");
        for (String s : produced) assertFalse(forbidden(s), what + ": a produced text matches the forbidden set: " + s);
    }

    // ------------------------------------------------------------------ (b) tests

    @Test
    void zm30_theStoredSummaryViewSaysNothingAboutBddkComplianceInAnyView() {
        StoredSummary summary = unconfirmedFixture();
        for (var labels : List.of(masked(), raw())) {
            var body = MAPPER.valueToTree(ZoneMatrixSummaryView.project(summary, labels, ZoneMatrixSummaryView.Query.defaults()));
            assertClean("stored summary", texts(body));
            assertFalse(body.toString().toLowerCase(java.util.Locale.ROOT).contains("bddk"), "the stored summary carries no BDDK word at all");
            JsonNode headline = body.get("headline");
            assertFalse(headline.get("exact").asBoolean(), "the fixture is stale, incomplete and has undecided rules");
            assertTrue(headline.get("stale_policies").asInt() >= 1 && headline.get("incomplete_policies").asInt() >= 1
                    && headline.get("not_evaluable_findings").asInt() >= 1 && headline.get("unknown_findings").asInt() >= 1,
                    "the fixture really holds a stale policy, an incomplete run, a NOT_EVALUABLE and an UNKNOWN rule");
        }
    }

    @Test
    void zm30_theStoredSummaryOfTheCleanestStateSaysNothingAboutBddkCompliance() {
        var body = MAPPER.valueToTree(ZoneMatrixSummaryView.project(confirmedCleanFixture(), masked(), ZoneMatrixSummaryView.Query.defaults()));
        assertClean("stored summary of the cleanest state", texts(body));
        assertFalse(body.toString().toLowerCase(java.util.Locale.ROOT).contains("bddk"));
    }

    @Test
    void zm30_theReportTextAndItsCsvNeverClaimBddkComplianceAndTheTitleOmitsBddkWhileACellIsUnconfirmed() {
        Regulation regulation = new Regulation("Planted regulation on information systems", "2020-03-15");
        StoredSummary summary = unconfirmedFixture();
        for (var labels : List.of(masked(), raw())) {
            Input input = ZoneMatrixEvidenceReportInputs.assemble(summary, labels, MAPPER, request(regulation), NOW);
            Built built = ZoneMatrixEvidenceReport.build(input, NOW);
            assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, built.title(), "one referenced cell is only PROPOSED");
            assertEquals("1 of 2 cells confirmed by compliance", header(built, "Cells confirmed by compliance"),
                    "M = the two cells that carry a reference, N = the one CONFIRMED (the same in the masked and the raw view)");
            List<String> produced = ZoneMatrixEvidenceReportInputsTest.strings(built);
            assertClean("report text", produced);
            for (String s : produced) assertFalse(s.toLowerCase(java.util.Locale.ROOT).contains("bddk"), "BDDK in: " + s);
            for (String projection : List.of("MASKED", "NAMES")) {
                String text = new String(csv(built, projection), StandardCharsets.UTF_8);
                assertFalse(forbidden(text), "the CSV matches the forbidden set");
                assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("bddk"), "BDDK in the CSV of an unconfirmed report");
                assertTrue(text.contains("1 of 2 cells confirmed by compliance"), "the CSV carries the header line");
            }
        }
    }

    @Test
    void zm30_aConfirmedReportMayNameTheRegulationInTheTitleAndStillClaimsNothing() {
        Regulation regulation = new Regulation("Planted regulation on information systems", "2020-03-15");
        StoredSummary summary = confirmedCleanFixture();
        for (var labels : List.of(masked(), raw())) {
            Input input = ZoneMatrixEvidenceReportInputs.assemble(summary, labels, MAPPER, request(regulation), NOW);
            Built confirmed = ZoneMatrixEvidenceReport.build(input, NOW);
            assertTrue(confirmed.title().contains("BDDK"), "positive control: every referenced cell confirmed earns the word");
            assertEquals("2 of 2 cells confirmed by compliance", header(confirmed, "Cells confirmed by compliance"));
            assertClean("report text of a confirmed report", ZoneMatrixEvidenceReportInputsTest.strings(confirmed));
            assertFalse(forbidden(new String(csv(confirmed, "NAMES"), StandardCharsets.UTF_8)), "the CSV of a confirmed report");
        }
    }
}
