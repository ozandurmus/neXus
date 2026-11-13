package com.securityexpert.nexus.ui2.service.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.service.report.ComplianceFacts.Check;
import com.securityexpert.nexus.ui2.service.report.ComplianceFacts.Counts;
import com.securityexpert.nexus.ui2.service.report.ComplianceFacts.Outcome;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;

/**
 * RPT-02 Framework compliance and evidence coverage (contract "Scope"): per framework (CIS and NIST first, then
 * PCI-DSS and Financial Baseline) and per control family, pass / fail / no evidence; the top failing controls and the
 * firewalls affected. "No evidence" is its own column everywhere.
 */
final class ComplianceCoverageBuilder implements ReportBuilder {

    static final int TOP_CONTROLS = 10;
    static final int MAX_AFFECTED_ROWS = 500;

    @Override
    public ReportTemplate template() {
        return ReportTemplate.RPT_02;
    }

    @Override
    public ReportDocument build(Context ctx, ReportData data) {
        ComplianceFacts facts = new ComplianceFacts(data.devices(), data.complianceEvaluations());
        Map<String, java.time.Instant> configuration = data.newestConfiguration();
        PlaneAsOf compliance = compliancePlane(facts, configuration, ctx.now());
        String selected = ctx.parameters().framework();
        List<String> keys = selected == null || ReportParameters.ALL_FRAMEWORKS.equals(selected)
                ? List.copyOf(ComplianceFacts.FRAMEWORK_NAMES.keySet()) : List.of(selected);

        List<Section> sections = new ArrayList<>();
        List<List<Cell>> matrix = new ArrayList<>();
        for (String key : keys) {
            Counts c = facts.framework(key);
            matrix.add(List.of(Cell.text(ComplianceFacts.FRAMEWORK_NAMES.get(key)), Cell.number(c.total()),
                    Cell.number(c.pass()), Cell.number(c.fail()), Cell.number(c.noEvidence()),
                    Cell.percent(c.evidencedPassPct()), Cell.percent(c.noEvidencePct())));
        }
        sections.add(new Section("frameworks", "Framework summary", List.of(compliance), List.of(
                new Kpi("Firewalls evaluated", compliance.covered() + " of " + compliance.total(),
                        compliance.covered() == compliance.total() && compliance.total() > 0 ? "OK" : "UNKNOWN"),
                new Kpi("Critical controls failing", Integer.toString(facts.criticalFails()),
                        facts.checks.isEmpty() ? "UNKNOWN" : facts.criticalFails() > 0 ? "BAD" : "OK")),
                List.of(new Table("framework_summary", "Checks per framework (one control on one firewall)",
                        ReportBuilder.columns("framework", "Framework", "checks", "Checks", "pass", "Pass", "fail", "Fail",
                                "no_evidence", "No evidence", "evidenced_pass", "Pass share of evidenced",
                                "no_evidence_share", "No-evidence share"),
                        matrix, "No stored compliance evaluation.")),
                summaryNotes(keys)));

        for (String key : keys) {
            List<List<Cell>> rows = new ArrayList<>();
            facts.families(key).forEach((family, c) -> rows.add(List.of(Cell.text(family), Cell.number(c.pass()),
                    Cell.number(c.fail()), Cell.number(c.noEvidence()), Cell.percent(c.evidencedPassPct()))));
            sections.add(new Section("families_" + key.toLowerCase(java.util.Locale.ROOT),
                    ComplianceFacts.FRAMEWORK_NAMES.get(key) + " by control family", List.of(compliance), List.of(),
                    List.of(new Table("families_" + key.toLowerCase(java.util.Locale.ROOT), "Control families",
                            ReportBuilder.columns("family", "Family", "pass", "Pass", "fail", "Fail", "no_evidence",
                                    "No evidence", "evidenced_pass", "Pass share of evidenced"),
                            rows, "No check of this framework has been evaluated.")),
                    "FINANCIAL_BASELINE".equals(key) ? List.of(ComplianceFacts.FINANCIAL_BASELINE_NOTICE) : List.of()));
        }

        sections.add(topFailing(facts, keys, compliance));
        return ReportBuilder.document(ctx, List.of(compliance), facts.unreadable > 0
                ? List.of("UNKNOWN: " + facts.unreadable + " stored evaluation(s) could not be read; those firewalls count "
                        + "as without evidence.") : List.of(), sections);
    }

    private static List<String> summaryNotes(List<String> keys) {
        List<String> notes = new ArrayList<>(List.of("Pass share of evidenced = pass / (pass + fail). No evidence is "
                + "counted apart and never as pass or fail. Management servers are not evaluated."));
        if (keys.contains("FINANCIAL_BASELINE")) notes.add(ComplianceFacts.FINANCIAL_BASELINE_NOTICE);
        return notes;
    }

    static PlaneAsOf compliancePlane(ComplianceFacts facts, Map<String, java.time.Instant> configuration,
            java.time.Instant now) {
        // Evidence time of an evaluation = the configuration read it evaluated (the evaluation is a pure function of it).
        List<java.time.Instant> times = new ArrayList<>();
        for (String deviceId : facts.evaluatedAt.keySet()) {
            times.add(configuration.getOrDefault(deviceId, facts.evaluatedAt.get(deviceId)));
        }
        return Freshness.plane("configuration (compliance)", times, facts.firewalls.size(), now, "firewalls");
    }

    private static Section topFailing(ComplianceFacts facts, List<String> keys, PlaneAsOf compliance) {
        Map<String, List<Check>> failingByControl = new LinkedHashMap<>();
        Map<String, Counts> countsByControl = new LinkedHashMap<>();
        for (Check c : facts.checks) {
            if (c.frameworks().keySet().stream().noneMatch(keys::contains)) continue;
            countsByControl.merge(c.controlId(), Counts.ZERO.plus(c.outcome()),
                    (a, b) -> new Counts(a.pass() + b.pass(), a.fail() + b.fail(), a.noEvidence() + b.noEvidence()));
            if (c.outcome() == Outcome.FAIL) failingByControl.computeIfAbsent(c.controlId(), k -> new ArrayList<>()).add(c);
        }
        List<String> top = failingByControl.keySet().stream()
                .sorted(Comparator.comparing((String id) -> -severityRank(failingByControl.get(id).get(0).severity()))
                        .thenComparing(id -> -failingByControl.get(id).size()).thenComparing(id -> id))
                .limit(TOP_CONTROLS).toList();
        List<List<Cell>> controlRows = new ArrayList<>();
        List<List<Cell>> affectedRows = new ArrayList<>();
        for (String id : top) {
            Check first = failingByControl.get(id).get(0);
            Counts counts = countsByControl.get(id);
            controlRows.add(List.of(Cell.text(id), Cell.text(first.title()), Cell.status(first.severity()),
                    Cell.number(counts.fail()), Cell.number(counts.pass()), Cell.number(counts.noEvidence())));
            for (Check c : failingByControl.get(id)) {
                if (affectedRows.size() >= MAX_AFFECTED_ROWS) break;
                var device = facts.firewallById.get(c.deviceId());
                affectedRows.add(List.of(Cell.text(id), ReportBuilder.device(device, c.deviceId()),
                        Cell.text(ReportBuilder.vendorLabel(device == null ? null : device.vendorHint())),
                        Cell.status(c.severity())));
            }
        }
        List<String> notes = new ArrayList<>();
        notes.add("Ordered by severity, then by the number of failing firewalls.");
        long affectedTotal = top.stream().mapToLong(id -> failingByControl.get(id).size()).sum();
        if (affectedTotal > affectedRows.size()) {
            notes.add("Showing " + affectedRows.size() + " of " + affectedTotal + " failing control/firewall pairs.");
        }
        return new Section("top_failing", "Top failing controls", List.of(compliance), List.of(),
                List.of(new Table("top_failing_controls", "Top failing controls",
                                ReportBuilder.columns("control", "Control", "title", "Title", "severity", "Severity", "fail",
                                        "Failing firewalls", "pass", "Passing firewalls", "no_evidence", "No evidence"),
                                controlRows, "No evaluated control fails."),
                        new Table("affected_firewalls", "Firewalls affected",
                                ReportBuilder.columns("control", "Control", "firewall", "Firewall", "vendor", "Vendor",
                                        "severity", "Severity"),
                                affectedRows, "No firewall fails a listed control.")),
                notes);
    }

    static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
