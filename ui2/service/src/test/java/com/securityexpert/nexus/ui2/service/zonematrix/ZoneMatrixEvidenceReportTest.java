package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.CellKind;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.CoverageItem;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.FindingIdentity;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Built;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.FindingRow;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Inclusion;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Input;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.MatrixCell;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.MatrixVersion;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.NotEvaluated;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.PolicyScope;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Regulation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceReport.Summary;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Finding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.ResolutionReason;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Transition;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.RunState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Evidence report content (docs/design/ZONE_MATRIX_CONTRACT.md 6.1, 7.3; ZM-11, ZM-30 title part, ZM-32). Synthetic
 * names only: zones ZONE-ALPHA / ZONE-BRAVO, policies FW-TANGO-04 / FW-JULIET-06.
 */
class ZoneMatrixEvidenceReportTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Regulation NAMED = new Regulation("Synthetic Regulation", "2026-01-01");

    private static MatrixCell cell(String ref, String status) {
        return new MatrixCell("ZONE-ALPHA", "ZONE-BRAVO", "DENY_ALL", List.of(), "HIGH", ref, status,
                "CONFIRMED".equals(status) ? "compliance-user-1" : "", "CONFIRMED".equals(status) ? "2026-11-20T10:00:00Z" : "");
    }

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    // ---- title (ZM-30, 6.1)

    @Test
    void zm30_titleIsGenericWhenTheRegulationIsNotNamed() {
        List<MatrixCell> confirmed = List.of(cell("Doc A, art. 1", "CONFIRMED"));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, ZoneMatrixEvidenceReport.title(null, confirmed));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(new Regulation("", "2026-01-01"), confirmed));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(new Regulation("Synthetic Regulation", " "), confirmed));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(new Regulation(null, null), confirmed));
        assertEquals("Segmentation policy evidence report", ZoneMatrixEvidenceReport.GENERIC_TITLE);
    }

    @Test
    void zm30_titleIsGenericWhileAnyReferencedCellIsNotConfirmed() {
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, ZoneMatrixEvidenceReport.title(NAMED,
                List.of(cell("Doc A", "CONFIRMED"), cell("Doc B", "PROPOSED"))));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(NAMED, List.of(cell("Doc B", "EMPTY"))));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(NAMED, List.of(cell("Doc B", null))));
        // identity law: no case folding of the status
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(NAMED, List.of(cell("Doc B", "confirmed"))));
    }

    @Test
    void zm30_bddkAppearsOnlyWhenNamedAndEveryReferencedCellIsConfirmed() {
        String title = ZoneMatrixEvidenceReport.title(NAMED,
                List.of(cell("Doc A", "CONFIRMED"), cell("", "PROPOSED"), cell("  ", "EMPTY")));
        assertTrue(title.contains("BDDK"), title);
        assertTrue(title.startsWith(ZoneMatrixEvidenceReport.GENERIC_TITLE), title);
        assertTrue(title.contains("Synthetic Regulation") && title.contains("2026-01-01"), title);
        // an unreferenced cell does not block the word
        assertFalse(Pattern.compile("(?i)bddk\\s*(compliant|pass|score)").matcher(title).find());
        assertFalse(title.contains("%"));
    }

    @Test
    void zm30_aMatrixWithNoReferencedCellNeverEarnsTheWord() {
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, ZoneMatrixEvidenceReport.title(NAMED, List.of()));
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE,
                ZoneMatrixEvidenceReport.title(NAMED, List.of(cell("", "EMPTY"))));
    }

    @Test
    void zm30_noStringOfABuiltDocumentClaimsBddkCompliancePassScoreOrPercentage() {
        Pattern bad = Pattern.compile("(?i)bddk\\s*(compliant|pass|score)|score|%");
        for (Input in : List.of(input(List.of(cell("Doc A", "CONFIRMED"))), input(List.of(cell("Doc A", "PROPOSED"))),
                input(List.of()))) {
            Built built = ZoneMatrixEvidenceReport.build(in, NOW);
            for (String s : allStrings(built)) {
                assertFalse(bad.matcher(s).find(), "forbidden claim in: " + s);
            }
        }
        Built named = ZoneMatrixEvidenceReport.build(input(List.of(cell("Doc A", "CONFIRMED"))), NOW);
        assertTrue(named.title().contains("BDDK"));
        Built plain = ZoneMatrixEvidenceReport.build(input(List.of(cell("Doc A", "PROPOSED"))), NOW);
        assertEquals(ZoneMatrixEvidenceReport.GENERIC_TITLE, plain.title());
        assertTrue(plain.notices().stream().anyMatch(n -> n.contains("not confirmed")), plain.notices().toString());
        assertFalse(allStrings(plain).stream().anyMatch(s -> s.contains("BDDK")),
                "BDDK must not appear anywhere while a referenced cell is unconfirmed");
    }

    // ---- confirmed line

    @Test
    void confirmedLineCountsOnlyCellsThatCarryAReference() {
        assertEquals("0 of 0 cells confirmed by compliance", ZoneMatrixEvidenceReport.confirmedLine(List.of()));
        assertEquals("2 of 3 cells confirmed by compliance", ZoneMatrixEvidenceReport.confirmedLine(List.of(
                cell("A", "CONFIRMED"), cell("B", "CONFIRMED"), cell("C", "PROPOSED"), cell("", "PROPOSED"),
                cell(null, "EMPTY"), cell("   ", "CONFIRMED"))));
        Built built = ZoneMatrixEvidenceReport.build(input(List.of(cell("A", "CONFIRMED"), cell("C", "PROPOSED"))), NOW);
        assertTrue(allStrings(built).contains("1 of 2 cells confirmed by compliance"));
    }

    // ---- period (ZM-32)

    private static Finding finding(boolean resolved, Instant first, Instant anyVersion, Instant last, Instant resolvedAt) {
        return new Finding(new FindingIdentity("POL-1", "uid-1", List.of(), List.of("ZONE-ALPHA->ZONE-BRAVO")),
                resolved ? Transition.RESOLVED : Transition.REPRODUCED, "VIOLATION", "reason", Set.of(), first, anyVersion,
                Map.of(), last, "ver-1", resolved ? ResolutionReason.RULE_CHANGED : null, resolvedAt, null, null, null);
    }

    @Test
    void zm32_periodInclusionFollowsOpenOrResolvedWithinTheHalfOpenPeriod() {
        Instant from = t("2026-11-10T00:00:00Z");
        Instant to = t("2026-11-20T00:00:00Z");
        // open before and during
        assertEquals(Inclusion.IN_PERIOD, ZoneMatrixEvidenceReport.inclusion(
                finding(false, t("2026-11-01T00:00:00Z"), t("2026-10-01T00:00:00Z"), t("2026-11-19T00:00:00Z"), null), from, to));
        // opened inside, still open
        assertEquals(Inclusion.IN_PERIOD, ZoneMatrixEvidenceReport.inclusion(
                finding(false, t("2026-11-15T00:00:00Z"), t("2026-11-15T00:00:00Z"), t("2026-11-19T00:00:00Z"), null), from, to));
        // resolved inside
        assertEquals(Inclusion.IN_PERIOD, ZoneMatrixEvidenceReport.inclusion(finding(true, t("2026-11-01T00:00:00Z"),
                t("2026-11-01T00:00:00Z"), t("2026-11-12T00:00:00Z"), t("2026-11-13T00:00:00Z")), from, to));
        // resolved exactly at the inclusive start
        assertEquals(Inclusion.IN_PERIOD, ZoneMatrixEvidenceReport.inclusion(finding(true, t("2026-11-01T00:00:00Z"),
                t("2026-11-01T00:00:00Z"), t("2026-11-09T00:00:00Z"), from), from, to));
        // resolved before the period: excluded
        assertEquals(Inclusion.OUTSIDE_PERIOD, ZoneMatrixEvidenceReport.inclusion(finding(true, t("2026-11-01T00:00:00Z"),
                t("2026-11-01T00:00:00Z"), t("2026-11-05T00:00:00Z"), t("2026-11-06T00:00:00Z")), from, to));
        // opened after the period, and exactly at the exclusive end: excluded
        assertEquals(Inclusion.OUTSIDE_PERIOD, ZoneMatrixEvidenceReport.inclusion(
                finding(false, t("2026-11-22T00:00:00Z"), t("2026-11-22T00:00:00Z"), t("2026-11-23T00:00:00Z"), null), from, to));
        assertEquals(Inclusion.OUTSIDE_PERIOD, ZoneMatrixEvidenceReport.inclusion(
                finding(false, to, to, to, null), from, to));
        // resolved after the period but open during it
        assertEquals(Inclusion.IN_PERIOD, ZoneMatrixEvidenceReport.inclusion(finding(true, t("2026-11-01T00:00:00Z"),
                t("2026-11-01T00:00:00Z"), t("2026-11-25T00:00:00Z"), t("2026-11-25T00:00:00Z")), from, to));
        assertTrue(ZoneMatrixEvidenceReport.inPeriod(
                finding(false, t("2026-11-01T00:00:00Z"), t("2026-10-01T00:00:00Z"), t("2026-11-19T00:00:00Z"), null), from, to));
        assertFalse(ZoneMatrixEvidenceReport.inPeriod(
                finding(false, t("2026-11-22T00:00:00Z"), t("2026-11-22T00:00:00Z"), t("2026-11-23T00:00:00Z"), null), from, to));
    }

    @Test
    void zm32_undecidableDatesKeepTheFindingAndLabelIt() {
        Instant from = t("2026-11-10T00:00:00Z");
        Instant to = t("2026-11-20T00:00:00Z");
        // no lifecycle record at all
        assertEquals(Inclusion.UNDECIDABLE, ZoneMatrixEvidenceReport.inclusion(null, from, to));
        // missing first-observed time
        assertEquals(Inclusion.UNDECIDABLE, ZoneMatrixEvidenceReport.inclusion(
                finding(false, null, null, t("2026-11-19T00:00:00Z"), null), from, to));
        // resolved without a resolution time that is not before the period
        assertEquals(Inclusion.UNDECIDABLE, ZoneMatrixEvidenceReport.inclusion(finding(true, t("2026-11-01T00:00:00Z"),
                t("2026-11-01T00:00:00Z"), t("2026-11-12T00:00:00Z"), null), from, to));
        // contradicting dates (resolved before first observed)
        assertEquals(Inclusion.UNDECIDABLE, ZoneMatrixEvidenceReport.inclusion(finding(true, t("2026-11-15T00:00:00Z"),
                t("2026-11-15T00:00:00Z"), t("2026-11-15T00:00:00Z"), t("2026-11-01T00:00:00Z")), from, to));
        assertTrue(ZoneMatrixEvidenceReport.inPeriod(null, from, to));
        // no period at all: everything is in
        assertEquals(Inclusion.IN_PERIOD, ZoneMatrixEvidenceReport.inclusion(null, null, null));
    }

    @Test
    void zm32_reportListsPeriodMembersWithAllDatesAndLabelsKeptRowsAndCountsExcludedOnes() {
        Instant from = t("2026-11-10T00:00:00Z");
        Instant to = t("2026-11-20T00:00:00Z");
        Finding open = finding(false, t("2026-11-01T01:02:03Z"), t("2026-10-05T04:05:06Z"), t("2026-11-19T07:08:09Z"), null);
        Finding resolvedInside = finding(true, t("2026-11-02T00:00:00Z"), t("2026-11-02T00:00:00Z"),
                t("2026-11-12T00:00:00Z"), t("2026-11-13T10:11:12Z"));
        Finding resolvedBefore = finding(true, t("2026-11-01T00:00:00Z"), t("2026-11-01T00:00:00Z"),
                t("2026-11-05T00:00:00Z"), t("2026-11-06T00:00:00Z"));
        Finding openedAfter = finding(false, t("2026-11-22T00:00:00Z"), t("2026-11-22T00:00:00Z"), t("2026-11-23T00:00:00Z"), null);
        Input in = withFindings(List.of(
                row("rule-open", "VIOLATION", "HIGH", List.of(), open),
                row("rule-resolved", "VIOLATION", "LOW", List.of(), resolvedInside),
                row("rule-before", "VIOLATION", "LOW", List.of(), resolvedBefore),
                row("rule-after", "VIOLATION", "LOW", List.of(), openedAfter),
                row("rule-undecided", "NOT_EVALUABLE", "MEDIUM", List.of(), null)), from, to);
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        Table findings = table(built, "findings");
        List<String> rules = column(findings, "rule");
        assertEquals(3, rules.size(), rules.toString());
        assertTrue(rules.contains("rule-open") && rules.contains("rule-resolved") && rules.contains("rule-undecided"));
        assertFalse(rules.contains("rule-before") || rules.contains("rule-after"));
        List<String> all = allStrings(built);
        // first, first-any-version, last and resolved dates are printed
        for (String printed : List.of("2026-11-01T01:02:03Z", "2026-10-05T04:05:06Z", "2026-11-19T07:08:09Z",
                "2026-11-13T10:11:12Z")) {
            assertTrue(all.contains(printed), "missing date " + printed);
        }
        assertTrue(column(findings, "resolution").contains("RULE_CHANGED"));
        // the row without dates prints UNKNOWN dates and is labelled as kept
        int undecided = rules.indexOf("rule-undecided");
        assertEquals("UNKNOWN", column(findings, "firstObservedAt").get(undecided));
        assertTrue(column(findings, "period").get(undecided).startsWith("KEPT"));
        assertTrue(allStrings(built).stream().anyMatch(s -> s.contains("2 rows supplied by the caller are outside the period")));
        assertTrue(built.notices().stream().anyMatch(n -> n.contains("Period filter")), built.notices().toString());
    }

    // ---- ZM-11 and the findings section

    @Test
    void zm11_versionHashFixedSentencesReasonsAndReferenceFieldsExactlyAsEntered() {
        MatrixCell formulaLike = new MatrixCell("ZONE-ALPHA", "ZONE-BRAVO", "ALLOW_LISTED", List.of("tcp/443"), "HIGH",
                "=Doc A, art. 1", "PROPOSED", "", "");
        MatrixCell padded = new MatrixCell("ZONE-BRAVO", "ZONE-ALPHA", "DENY_ALL", List.of(), "CRITICAL",
                "  Doc B v2  ", "CONFIRMED", "compliance-user-1", "2026-11-20T10:00:00Z");
        Input in = withFindings(List.of(
                row("rule-1", "VIOLATION", "HIGH", List.of(), null),
                row("rule-2", "NOT_EVALUABLE", "MEDIUM", List.of(), null),
                row("rule-3", "UNKNOWN", "LOW", List.of(), null)), null, null);
        in = new Input(in.productVersion(), in.generatedBy(), in.viewLabel(), null, null, in.filterDescription(),
                in.filteredFindingCount(), in.wholeScopeFindingCount(), NAMED, in.version(), List.of(formulaLike, padded),
                in.threshold(), in.policies(), in.notEvaluated(), in.summary(), in.findings());
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        List<String> all = allStrings(built);

        assertTrue(all.contains("0123456789abcdef-synthetic-hash"), "version hash");
        assertTrue(all.contains(ZoneMatrixEvidenceReport.ACCEPTED_DEVIATIONS_SENTENCE));
        assertEquals("Accepted deviations are not recorded in neXus; every rule that conflicts with the matrix is listed.",
                ZoneMatrixEvidenceReport.ACCEPTED_DEVIATIONS_SENTENCE);
        assertTrue(all.contains(ZoneMatrixEvidenceReport.LIMITATIONS_TEXT));
        for (String part : List.of("policy intent as defined in management", "implicit and default rules",
                "coverage", "IPv4", "rule-level", "NAT", "dual view")) {
            assertTrue(ZoneMatrixEvidenceReport.LIMITATIONS_TEXT.contains(part), part);
        }
        // reasons of undecided findings are present
        Table findings = table(built, "findings");
        assertEquals(List.of("VIOLATION", "NOT_EVALUABLE", "UNKNOWN"), column(findings, "outcome"));
        assertEquals(3, column(findings, "reason").stream().filter(r -> r.equals("reason of rule-1")
                || r.equals("reason of rule-2") || r.equals("reason of rule-3")).count());
        // reference fields exactly as entered, including a leading '=' and surrounding blanks
        Table cells = table(built, "matrix-cells");
        assertTrue(column(cells, "reference").contains("=Doc A, art. 1"));
        assertTrue(column(cells, "reference").contains("  Doc B v2  "));
        assertTrue(column(cells, "confirmedBy").contains("compliance-user-1"));
        // unconfirmed cells are marked, the confirmed one is not
        List<String> marks = column(cells, "mark");
        assertEquals(1, marks.stream().filter(m -> m.equals("CONFIRMED")).count());
        assertEquals(1, marks.stream().filter(m -> m.startsWith("UNCONFIRMED (reference status PROPOSED)")).count());
        // statement and services are printed
        assertTrue(column(cells, "services").contains("tcp/443"));
        assertTrue(column(cells, "statement").containsAll(List.of("ALLOW_LISTED", "DENY_ALL")));
    }

    @Test
    void zm11_everyUndecidedFindingIsListedAndNothingIsCapped() {
        List<FindingRow> rows = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            rows.add(row("rule-" + i, i % 3 == 0 ? "UNKNOWN" : i % 3 == 1 ? "NOT_EVALUABLE" : "VIOLATION", "LOW",
                    List.of(), null));
        }
        Built built = ZoneMatrixEvidenceReport.build(withFindings(rows, null, null), NOW);
        Table findings = table(built, "findings");
        assertEquals(600, findings.rows().size());
        assertEquals(200, column(findings, "outcome").stream().filter("UNKNOWN"::equals).count());
        assertEquals(200, column(findings, "outcome").stream().filter("NOT_EVALUABLE"::equals).count());
        Table byOutcome = table(built, "summary-outcome");
        assertEquals(List.of("VIOLATION", "NOT_EVALUABLE", "UNKNOWN"), column(byOutcome, "key"));
        assertEquals(List.of("200", "200", "200"), column(byOutcome, "count"));
    }

    @Test
    void outsideHeadlineAndFlaggedViolationsAreListedAndLabelled() {
        Input in = withFindings(List.of(
                row("rule-in", "VIOLATION", "HIGH", List.of(), null),
                row("rule-outside", "VIOLATION", "CRITICAL", List.of("DENIED_BY_OTHER_ORDERED_LAYER"), null),
                row("rule-unknown-layer", "VIOLATION", "LOW", List.of("OTHER_ORDERED_LAYERS_UNKNOWN"), null),
                row("rule-covered", "VIOLATION", "MEDIUM", List.of("COVERED_BY_EARLIER_DENY"), null),
                row("rule-undecided", "NOT_EVALUABLE", "MEDIUM", List.of("BECAME_UNDECIDED"), null)), null, null);
        Built built = ZoneMatrixEvidenceReport.build(in, NOW);
        Table findings = table(built, "findings");
        List<String> rules = column(findings, "rule");
        List<String> membership = column(findings, "headline");
        List<String> flags = column(findings, "flags");
        assertEquals(5, rules.size());
        assertTrue(membership.get(rules.indexOf("rule-in")).startsWith("COUNTS_TOWARD_HEADLINE"));
        assertTrue(membership.get(rules.indexOf("rule-outside")).startsWith("OUTSIDE_HEADLINE"));
        assertTrue(membership.get(rules.indexOf("rule-unknown-layer")).startsWith("OUTSIDE_HEADLINE"));
        assertTrue(membership.get(rules.indexOf("rule-covered")).startsWith("COUNTS_TOWARD_HEADLINE"));
        assertEquals("COVERED_BY_EARLIER_DENY", flags.get(rules.indexOf("rule-covered")));
        assertTrue(membership.get(rules.indexOf("rule-undecided")).startsWith("NOT_COUNTED"));
        assertEquals("BECAME_UNDECIDED", flags.get(rules.indexOf("rule-undecided")));
        // ordering: violations first, most severe first
        assertEquals(List.of("rule-outside", "rule-in", "rule-covered", "rule-unknown-layer", "rule-undecided"), rules);
        Section summary = section(built, "summary");
        assertEquals("4", summary.kpis().get(0).value());
        assertEquals("2", summary.kpis().get(1).value());
    }

    @Test
    void summaryHeadlineTextPassesThroughUnchangedAndCountsStateWhatTheyCount() {
        Built built = ZoneMatrixEvidenceReport.build(input(List.of(cell("A", "CONFIRMED"))), NOW);
        Section summary = section(built, "summary");
        assertTrue(summary.notes().contains("at least 3 rules violate the zone matrix (v7) across 2 zone pairs"));
        assertTrue(summary.notes().contains("+1 outside the headline (other ordered layer denies or unknown)"));
        assertTrue(summary.notes().stream().anyMatch(n -> n.contains("counts of the finding rows listed")));
        for (Table t : summary.tables()) {
            assertTrue(t.title().toLowerCase().contains("listed"), t.title());
        }
        Table byCell = table(built, "summary-cell");
        assertEquals(List.of("ZONE-ALPHA -> ZONE-BRAVO"), column(byCell, "key"));
        Table bySeverity = table(built, "summary-severity");
        assertEquals(List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"), column(bySeverity, "key"));

        Input noSummary = new Input("1.0", "user", "MASKED", null, null, null, null, null, NAMED, null, List.of(), null,
                List.of(), List.of(), null, List.of());
        Section s2 = section(ZoneMatrixEvidenceReport.build(noSummary, NOW), "summary");
        assertTrue(s2.notes().contains("Headline: UNKNOWN (not supplied)."));
    }

    // ---- header and scope

    @Test
    void headerStatesGenerationViewPeriodFilterAndMissingValuesAsUnknown() {
        Built built = ZoneMatrixEvidenceReport.build(input(List.of(cell("A", "CONFIRMED"))), NOW);
        Table header = table(built, "header");
        List<String> fields = column(header, "field");
        List<String> values = column(header, "value");
        assertEquals("2026-11-25T12:00:00Z", values.get(fields.indexOf("Generated at (UTC)")));
        assertEquals("MASKED", values.get(fields.indexOf("View (names as projected)")));
        assertEquals("report-user-1", values.get(fields.indexOf("Generated by")));
        assertEquals("1.2.3-synthetic", values.get(fields.indexOf("Product version")));
        assertEquals("1 of 1 cells confirmed by compliance", values.get(fields.indexOf("Cells confirmed by compliance")));
        assertEquals("12", values.get(fields.indexOf("Findings in the filtered scope (count of findings matching the filter)")));
        assertEquals("40", values.get(fields.indexOf("Findings in the whole scope (count of findings without the filter)")));
        assertEquals("policy FW-TANGO-04", values.get(fields.indexOf("Filter")));

        Input empty = new Input(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        Table h2 = table(ZoneMatrixEvidenceReport.build(empty, NOW), "header");
        List<String> v2 = column(h2, "value");
        List<String> f2 = column(h2, "field");
        assertEquals("UNKNOWN", v2.get(f2.indexOf("Product version")));
        assertEquals("UNKNOWN", v2.get(f2.indexOf("Generated by")));
        assertEquals("UNKNOWN", v2.get(f2.indexOf("Findings in the whole scope (count of findings without the filter)")));
        assertEquals("none (whole scope)", v2.get(f2.indexOf("Filter")));
    }

    @Test
    void scopeListsPoliciesStalenessCoverageAndWhatWasNotEvaluated() {
        Built built = ZoneMatrixEvidenceReport.build(input(List.of(cell("A", "CONFIRMED"))), NOW);
        Table policies = table(built, "scope-policies");
        assertEquals(2, policies.rows().size());
        int tango = column(policies, "policy").indexOf("FW-TANGO-04");
        assertEquals("CURRENT", column(policies, "staleness").get(tango));
        assertEquals("COMPLETE", column(policies, "coverage").get(tango));
        assertEquals("2026-11-25T11:00:00Z", column(policies, "snapshotAt").get(tango));
        assertEquals("1 h 0 min", column(policies, "snapshotAge").get(tango));
        int juliet = column(policies, "policy").indexOf("FW-JULIET-06");
        assertTrue(column(policies, "staleness").get(juliet).startsWith("STALE (SNAPSHOT_OLDER_THAN_THRESHOLD"));
        assertEquals("INCOMPLETE", column(policies, "coverage").get(juliet));
        assertTrue(column(policies, "coverageItems").get(juliet).contains("DEFAULT_RULES_NOT_COLLECTED: default rules"));
        assertEquals("UNKNOWN", column(policies, "runAt").get(juliet));
        Table ne = table(built, "scope-not-evaluated");
        assertEquals(List.of("Vendor-X"), column(ne, "subject"));
        assertEquals(List.of("policy not collected"), column(ne, "reason"));
        assertTrue(section(built, "scope").notes().stream().anyMatch(n -> n.contains("interim")));
        assertTrue(built.notices().stream().anyMatch(n -> n.contains("24 h 0 min") && n.contains("interim")));
    }

    @Test
    void unknownCoverageOrStalenessIsNeverShownAsCompleteOrCurrent() {
        PolicyScope p = new PolicyScope("FW-TANGO-04", "VendorY", List.of(), null, null, null, null, "weird", null);
        Input in = new Input("1.0", "u", "MASKED", null, null, null, null, null, null, null, List.of(), null, List.of(p),
                List.of(), null, List.of());
        Table policies = table(ZoneMatrixEvidenceReport.build(in, NOW), "scope-policies");
        assertEquals(List.of("UNKNOWN"), column(policies, "staleness"));
        assertEquals(List.of("UNKNOWN"), column(policies, "coverage"));
        assertEquals(List.of("UNKNOWN"), column(policies, "snapshotAt"));
        assertEquals(List.of("UNKNOWN"), column(policies, "devices"));
        assertEquals(List.of("UNKNOWN"), column(policies, "coverageItems"));
    }

    // ---- identity cell kinds, zone blocks, determinism

    @Test
    void noIdentityCellKindAndNoZoneBlockOrWitnessAddressInTheOutput() {
        Built built = ZoneMatrixEvidenceReport.build(input(List.of(cell("A", "CONFIRMED"), cell("B", "PROPOSED"))), NOW);
        for (Section s : built.sections()) {
            for (Table t : s.tables()) {
                for (List<Cell> row : t.rows()) {
                    for (Cell c : row) {
                        assertNotEquals(CellKind.DEVICE, c.kind());
                        assertNotEquals(CellKind.ADDRESS, c.kind());
                        assertNotEquals(CellKind.SERIAL, c.kind());
                        assertNotEquals(CellKind.CLUSTER, c.kind());
                        assertNull(c.ref());
                    }
                }
            }
        }
        for (String s : allStrings(built)) {
            assertFalse(s.matches("(?s).*192\\.0\\.2\\.\\d+.*"), "no zone block or witness address: " + s);
            assertFalse(s.matches("(?s).*198\\.51\\.100\\.\\d+.*"), "no address: " + s);
        }
        assertEquals(List.of("header", "matrix-version", "scope", "summary", "findings", "statements"),
                built.sections().stream().map(Section::id).toList());
    }

    @Test
    void sameInputTwiceGivesEqualBuilt() {
        Input in = input(List.of(cell("A", "CONFIRMED"), cell("B", "PROPOSED")));
        assertEquals(ZoneMatrixEvidenceReport.build(in, NOW), ZoneMatrixEvidenceReport.build(in, NOW));
        assertEquals(ZoneMatrixEvidenceReport.build(in, NOW), ZoneMatrixEvidenceReport.build(input(in.cells()), NOW));
    }

    // ---- helpers

    private static FindingRow row(String rule, String outcome, String severity, List<String> flags, Finding lifecycle) {
        return new FindingRow("FW-TANGO-04", rule, List.of("ZONE-ALPHA -> ZONE-BRAVO"), severity, outcome,
                "reason of " + rule, flags, "services outside the cell: tcp/22", lifecycle);
    }

    private static Input input(List<MatrixCell> cells) {
        Input base = withFindings(List.of(
                row("rule-a", "VIOLATION", "CRITICAL", List.of(), null),
                row("rule-b", "VIOLATION", "HIGH", List.of("DENIED_BY_OTHER_ORDERED_LAYER"), null)), null, null);
        return new Input(base.productVersion(), base.generatedBy(), base.viewLabel(), null, null, base.filterDescription(),
                base.filteredFindingCount(), base.wholeScopeFindingCount(), NAMED, base.version(), cells, base.threshold(),
                base.policies(), base.notEvaluated(), base.summary(), base.findings());
    }

    private static Input withFindings(List<FindingRow> findings, Instant from, Instant to) {
        MatrixVersion version = new MatrixVersion(7, "0123456789abcdef-synthetic-hash", t("2026-11-18T09:00:00Z"),
                List.of("editor-1", "editor-2"), "publisher-1", "neXus segmentation template (proposal) rev 1",
                "DENY_ALL", "MEDIUM");
        PolicyScope tango = new PolicyScope("FW-TANGO-04", "VendorX", List.of("FW-TANGO-04-A", "FW-TANGO-04-B"),
                t("2026-11-25T11:00:00Z"), t("2026-11-25T11:05:00Z"),
                ZoneMatrixStaleness.assess(new PolicyInput("POL-1", "2026-11-25T11:00:00Z", RunState.COMPLETE, "ver-1", "ver-1"),
                        NOW, ZoneMatrixStaleness.INTERIM_PO_2026_11_23),
                7, "COMPLETE", List.of());
        PolicyScope juliet = new PolicyScope("FW-JULIET-06", "VendorX", List.of("FW-JULIET-06-A"),
                t("2026-11-20T11:00:00Z"), null,
                ZoneMatrixStaleness.assess(new PolicyInput("POL-2", "2026-11-20T11:00:00Z", RunState.COMPLETE, "ver-1", "ver-1"),
                        NOW, ZoneMatrixStaleness.INTERIM_PO_2026_11_23),
                null, "INCOMPLETE", List.of(new CoverageItem("DEFAULT_RULES_NOT_COLLECTED", "default rules were not collected")));
        return new Input("1.2.3-synthetic", "report-user-1", "MASKED", from, to, "policy FW-TANGO-04", 12, 40, NAMED,
                version, List.of(), ZoneMatrixStaleness.INTERIM_PO_2026_11_23, List.of(tango, juliet),
                List.of(new NotEvaluated("Vendor-X", "VENDOR", "policy not collected")),
                new Summary("at least 3 rules violate the zone matrix (v7) across 2 zone pairs",
                        "+1 outside the headline (other ordered layer denies or unknown)"),
                findings);
    }

    private static Section section(Built built, String id) {
        return built.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static Table table(Built built, String id) {
        return built.sections().stream().flatMap(s -> s.tables().stream()).filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no table " + id));
    }

    private static List<String> column(Table table, String key) {
        int idx = -1;
        for (int i = 0; i < table.columns().size(); i++) {
            if (table.columns().get(i).key().equals(key)) {
                idx = i;
            }
        }
        assertTrue(idx >= 0, "no column " + key);
        List<String> out = new ArrayList<>();
        for (List<Cell> row : table.rows()) {
            out.add(row.get(idx).text());
        }
        return out;
    }

    private static List<String> allStrings(Built built) {
        List<String> out = new ArrayList<>();
        out.add(built.title());
        out.addAll(built.notices());
        for (Section s : built.sections()) {
            out.add(s.title());
            out.addAll(s.notes());
            s.kpis().forEach(k -> {
                out.add(k.label());
                out.add(k.value());
            });
            for (Table t : s.tables()) {
                out.add(t.title());
                out.add(t.emptyText());
                t.columns().forEach(c -> out.add(c.label()));
                t.rows().forEach(r -> r.forEach(c -> out.add(c.text())));
            }
        }
        return out;
    }
}
