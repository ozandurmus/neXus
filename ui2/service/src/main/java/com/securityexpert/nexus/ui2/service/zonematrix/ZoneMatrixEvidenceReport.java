package com.securityexpert.nexus.ui2.service.zonematrix;

import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Column;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.CoverageItem;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Finding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Status;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.PolicyStaleness;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixStaleness.StalenessThreshold;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Content of the zone matrix evidence report (docs/design/ZONE_MATRIX_CONTRACT.md section 7.3, the BDDK labelling rule
 * of 6.1; {@code ZM-11}, {@code ZM-30} title part, {@code ZM-32}).
 *
 * <p>Pure and deterministic: no Spring, no database, no file store, no clock ({@code now} is a parameter). It only
 * turns an {@link Input} into the sections of a {@link com.securityexpert.nexus.ui2.service.report.ReportDocument}.
 * Nothing here is registered in the Reports catalogue, and rights, the stored-file audit row ({@code ZM-28}), the
 * rights test of the screen view ({@code ZM-31}), the raw-only blocks appendix ({@code ZM-38}) and the zone-name
 * masking cell kind belong to a later step.</p>
 *
 * <h2>Screen view</h2>
 * Every zone name, rule name, policy and device display string in {@link Input} is taken as <em>already projected</em>
 * for the caller's screen view (masked or raw, 7.3 "content follows the caller's screen view"). The builder never sees
 * or emits zone blocks, witness addresses or the blocks appendix, and puts every identity text into
 * {@code TEXT} / {@code STATUS} / {@code TIMESTAMP} / {@code NUMBER} cells, never into {@code DEVICE}, {@code ADDRESS}
 * or {@code SERIAL} cells.
 *
 * <h2>Fail-closed rules</h2>
 * A missing value prints {@code UNKNOWN}; UNKNOWN is never shown as OK; every count states what it counts;
 * NOT_EVALUABLE and UNKNOWN findings are never dropped and nothing is capped. A finding whose period membership cannot
 * be decided from its dates is kept and labelled, never silently excluded. Operator-entered text (reference fields)
 * is passed through exactly as entered; the R1 CSV renderer owns the formula guard.
 */
public final class ZoneMatrixEvidenceReport {
    private ZoneMatrixEvidenceReport() {}

    /** The title when the BDDK labelling rule is not met (6.1). */
    public static final String GENERIC_TITLE = "Segmentation policy evidence report";

    /** Fixed sentence of 7.3, verbatim. */
    public static final String ACCEPTED_DEVIATIONS_SENTENCE =
            "Accepted deviations are not recorded in neXus; every rule that conflicts with the matrix is listed.";

    /** Fixed limitations text of 7.3: the five limitations the contract names, each stated once. */
    public static final String LIMITATIONS_TEXT = "Limitations: the evaluation reflects policy intent as defined in "
            + "management, not traffic observed on a device; implicit and default rules are listed in the coverage "
            + "items of the scope section; only IPv4 is evaluated in the first delivery; the evaluation is "
            + "rule-level, not flow-level; destination and static NAT are evaluated in a dual view (the original "
            + "and the translated destination).";

    private static final String UNKNOWN = "UNKNOWN";
    private static final String CONFIRMED = "CONFIRMED";

    /** Findings whose rule is decided by another ordered layer are listed but outside the headline (5.5, 7.2). */
    private static final List<String> OUTSIDE_HEADLINE_FLAGS =
            List.of("DENIED_BY_OTHER_ORDERED_LAYER", "OTHER_ORDERED_LAYERS_UNKNOWN");

    private static final List<String> OUTCOME_ORDER = List.of("VIOLATION", "NOT_EVALUABLE", "UNKNOWN");
    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    // ------------------------------------------------------------------ input

    /** The regulation the bank named (6.2 item 1). Both title and date must be non-blank for it to count as named. */
    public record Regulation(String title, String date) {
        public boolean named() {
            return title != null && !title.isBlank() && date != null && !date.isBlank();
        }
    }

    /**
     * One matrix cell as the report prints it: zone names already projected for the caller's view, reference fields
     * exactly as entered. {@code referenceStatus} is {@code EMPTY}, {@code PROPOSED} or {@code CONFIRMED}.
     */
    public record MatrixCell(String sourceZone, String destinationZone, String statement, List<String> services,
            String severity, String regulationReference, String referenceStatus, String confirmedBy,
            String confirmedAt) {
        public MatrixCell {
            services = services == null ? List.of() : List.copyOf(services);
        }

        /** The cell carries a regulation reference (counts toward M of "N of M cells confirmed by compliance"). */
        public boolean carriesReference() {
            return regulationReference != null && !regulationReference.isBlank();
        }

        /** Exactly {@code CONFIRMED}; no case folding (identity law). */
        public boolean confirmed() {
            return CONFIRMED.equals(referenceStatus);
        }
    }

    /** The published matrix version the report cites. */
    public record MatrixVersion(Integer number, String contentSha256, Instant publishedAt, List<String> editors,
            String publisher, String templateOrigin, String defaultStatement, String defaultSeverity) {
        public MatrixVersion {
            editors = editors == null ? List.of() : List.copyOf(editors);
        }
    }

    /** One evaluated policy: staleness from {@link ZoneMatrixStaleness}, coverage items from the evaluator. */
    public record PolicyScope(String policy, String vendor, List<String> devices, Instant snapshotAt, Instant runAt,
            PolicyStaleness staleness, Integer matrixVersionNumber, String coverage, List<CoverageItem> coverageItems) {
        public PolicyScope {
            devices = devices == null ? List.of() : List.copyOf(devices);
            coverageItems = coverageItems == null ? List.of() : List.copyOf(coverageItems);
        }
    }

    /** A policy, vendor or device the evaluation did not cover, with the reason. */
    public record NotEvaluated(String subject, String kind, String reason) {}

    /** Text of 7.2, supplied by the caller and passed through unchanged; this class never recomputes a headline. */
    public record Summary(String headlineText, String outsideHeadlineText) {}

    /**
     * One row of the findings list. {@code lifecycle} carries the dates and the resolution; it is {@code null} for an
     * undecided rule (NOT_EVALUABLE, UNKNOWN) that has no stored finding (the lifecycle only stores violations), and
     * such a row is kept with {@code UNKNOWN} dates.
     */
    public record FindingRow(String policy, String rule, List<String> cells, String severity, String outcome,
            String reason, List<String> flags, String detail, Finding lifecycle) {
        public FindingRow {
            cells = cells == null ? List.of() : List.copyOf(cells);
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }

    /**
     * One rule touching unassigned private RFC 1918 / IPv6 ULA ranges (section 3.3). Policy, rule and section names
     * are already projected for the caller's view; {@code unassignedPrivateRanges} is {@code null} when the caller is
     * masked or lacks either {@code zone_matrix_zones_read} or {@code report_raw_view} (only {@code rangeCount} is shown).
     */
    public record ZoneDefinitionGapRow(String policy, String rule, String section, int rangeCount,
            List<String> unassignedPrivateRanges) {
        public ZoneDefinitionGapRow {
            unassignedPrivateRanges = unassignedPrivateRanges == null ? null : List.copyOf(unassignedPrivateRanges);
        }
    }

    /**
     * Everything the report prints. {@code periodFrom} is inclusive and {@code periodTo} exclusive; a {@code null}
     * bound means no bound on that side. Counts are {@code Integer} so that a count the caller cannot give prints
     * {@code UNKNOWN} instead of a false zero.
     */
    public record Input(String productVersion, String generatedBy, String viewLabel, Instant periodFrom,
            Instant periodTo, String filterDescription, Integer filteredFindingCount, Integer wholeScopeFindingCount,
            Regulation regulation, MatrixVersion version, List<MatrixCell> cells, StalenessThreshold threshold,
            List<PolicyScope> policies, List<NotEvaluated> notEvaluated, Summary summary, List<FindingRow> findings,
            List<ZoneDefinitionGapRow> zoneDefinitionGaps, boolean zoneDefinitionGapsComplete) {
        public Input {
            cells = cells == null ? List.of() : List.copyOf(cells);
            policies = policies == null ? List.of() : List.copyOf(policies);
            notEvaluated = notEvaluated == null ? List.of() : List.copyOf(notEvaluated);
            findings = findings == null ? List.of() : List.copyOf(findings);
            zoneDefinitionGaps = zoneDefinitionGaps == null ? List.of() : List.copyOf(zoneDefinitionGaps);
        }

        public Input(String productVersion, String generatedBy, String viewLabel, Instant periodFrom,
                Instant periodTo, String filterDescription, Integer filteredFindingCount, Integer wholeScopeFindingCount,
                Regulation regulation, MatrixVersion version, List<MatrixCell> cells, StalenessThreshold threshold,
                List<PolicyScope> policies, List<NotEvaluated> notEvaluated, Summary summary, List<FindingRow> findings) {
            this(productVersion, generatedBy, viewLabel, periodFrom, periodTo, filterDescription, filteredFindingCount,
                    wholeScopeFindingCount, regulation, version, cells, threshold, policies, notEvaluated, summary,
                    findings, List.of(), false);
        }
    }

    /** The content: title, notices and the sections in the order of 7.3. */
    public record Built(String title, List<String> notices, List<Section> sections) {
        public Built {
            notices = List.copyOf(notices);
            sections = List.copyOf(sections);
        }
    }

    // ------------------------------------------------------------------ title and confirmed line

    /**
     * Title per the BDDK labelling rule (6.1). The word BDDK appears only when the bank named the regulation (title and
     * date both non-blank), at least one cell carries a reference, and every cell that carries a reference is
     * {@code CONFIRMED}. A matrix with no referenced cell at all never earns the word: "every referenced cell is
     * confirmed" would hold vacuously and say nothing. Otherwise the title is {@link #GENERIC_TITLE}. The title never
     * states compliance, a pass or a figure.
     */
    public static String title(Regulation regulation, List<MatrixCell> cells) {
        Objects.requireNonNull(cells, "cells");
        if (!bddkEarned(regulation, cells)) {
            return GENERIC_TITLE;
        }
        return GENERIC_TITLE + " - BDDK regulation: " + regulation.title().strip() + ", " + regulation.date().strip();
    }

    private static boolean bddkEarned(Regulation regulation, List<MatrixCell> cells) {
        if (regulation == null || !regulation.named()) {
            return false;
        }
        long referenced = cells.stream().filter(MatrixCell::carriesReference).count();
        return referenced > 0 && cells.stream().filter(MatrixCell::carriesReference).allMatch(MatrixCell::confirmed);
    }

    /**
     * "N of M cells confirmed by compliance". M counts the cells that carry a regulation reference (non-blank);
     * N counts those of them whose status is {@code CONFIRMED}. Cells without a reference are in neither number.
     */
    public static String confirmedLine(List<MatrixCell> cells) {
        Objects.requireNonNull(cells, "cells");
        long m = cells.stream().filter(MatrixCell::carriesReference).count();
        long n = cells.stream().filter(MatrixCell::carriesReference).filter(MatrixCell::confirmed).count();
        return n + " of " + m + " cells confirmed by compliance";
    }

    // ------------------------------------------------------------------ period

    public enum Inclusion { IN_PERIOD, OUTSIDE_PERIOD, UNDECIDABLE }

    /**
     * Whether a finding belongs to the period {@code [from, to)} (7.3 "Period-based"): it was open at any time in the
     * period, or it was resolved within it. Open means from {@code firstObservedAt} until {@code resolvedAt}, or
     * without end while unresolved. A {@code null} bound is no bound. A {@code null} lifecycle, a missing date that
     * the decision needs, a resolved finding without a resolution time, or dates that contradict each other give
     * {@link Inclusion#UNDECIDABLE}: the finding is kept and labelled, never silently excluded.
     */
    public static Inclusion inclusion(Finding finding, Instant from, Instant to) {
        if (from == null && to == null) {
            return Inclusion.IN_PERIOD;
        }
        if (finding == null) {
            return Inclusion.UNDECIDABLE;
        }
        Instant first = finding.firstObservedAt();
        Instant resolvedAt = finding.resolvedAt();
        boolean resolved = finding.status() == Status.RESOLVED;
        if (resolved && resolvedAt != null && first != null && resolvedAt.isBefore(first)) {
            return Inclusion.UNDECIDABLE;
        }
        if (resolvedAt != null && inRange(resolvedAt, from, to)) {
            return Inclusion.IN_PERIOD;
        }
        if (resolved && resolvedAt != null && from != null && resolvedAt.isBefore(from)) {
            return Inclusion.OUTSIDE_PERIOD;
        }
        if (first == null) {
            return Inclusion.UNDECIDABLE;
        }
        if (to != null && !first.isBefore(to)) {
            return Inclusion.OUTSIDE_PERIOD;
        }
        if (resolved && resolvedAt == null) {
            return Inclusion.UNDECIDABLE;
        }
        return Inclusion.IN_PERIOD;
    }

    /** True when the finding is kept in the report for the period (in the period or undecidable). */
    public static boolean inPeriod(Finding finding, Instant from, Instant to) {
        return inclusion(finding, from, to) != Inclusion.OUTSIDE_PERIOD;
    }

    private static boolean inRange(Instant at, Instant from, Instant to) {
        return (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
    }

    // ------------------------------------------------------------------ build

    public static Built build(Input in, Instant now) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(now, "now");
        String title = title(in.regulation(), in.cells());

        List<Row> kept = new ArrayList<>();
        int outside = 0;
        int undecidable = 0;
        for (FindingRow row : in.findings()) {
            Inclusion inc = inclusion(row.lifecycle(), in.periodFrom(), in.periodTo());
            if (inc == Inclusion.OUTSIDE_PERIOD) {
                outside++;
            } else {
                if (inc == Inclusion.UNDECIDABLE) {
                    undecidable++;
                }
                kept.add(new Row(row, inc));
            }
        }
        kept.sort(ROW_ORDER);

        List<String> notices = notices(in, title, outside, undecidable, kept.size());
        List<Section> sections = List.of(
                header(in, title, now),
                matrixVersion(in),
                scope(in),
                summary(in, kept),
                findings(in, kept, outside),
                statements());
        return new Built(title, notices, sections);
    }

    private record Row(FindingRow row, Inclusion inclusion) {}

    private static final Comparator<Row> ROW_ORDER = Comparator
            .comparingInt((Row r) -> rank(OUTCOME_ORDER, r.row().outcome()))
            .thenComparingInt(r -> rank(SEVERITY_ORDER, r.row().severity()))
            .thenComparing(r -> nz(r.row().policy()))
            .thenComparing(r -> nz(r.row().rule()))
            .thenComparing(r -> String.join("|", r.row().cells()));

    private static int rank(List<String> order, String value) {
        int i = value == null ? -1 : order.indexOf(value);
        return i < 0 ? order.size() : i;
    }

    // ---- notices

    private static List<String> notices(Input in, String title, int outside, int undecidable, int kept) {
        List<String> out = new ArrayList<>();
        if (GENERIC_TITLE.equals(title)) {
            out.add("The title does not name the regulation: " + whyGeneric(in));
        }
        out.add("Zone, rule, policy and device names are printed as projected for the view stated in the header; zone "
                + "blocks, witness addresses and the blocks appendix are not part of this content.");
        if (in.zoneDefinitionGaps().stream().anyMatch(g -> g.unassignedPrivateRanges() != null
                && !g.unassignedPrivateRanges().isEmpty())) {
            out.add("This RAW report includes unassigned private ranges in the zone definition gaps table.");
        } else {
            out.add("Unassigned private ranges are not part of this content; only recorded range counts are shown.");
        }
        out.add(thresholdText(in.threshold()));
        if (in.periodFrom() != null || in.periodTo() != null) {
            out.add("Period filter: " + kept + " finding rows are listed (open at any time in the period or resolved "
                    + "within it); " + outside + " rows are outside the period and not listed; " + undecidable
                    + " of the listed rows are kept because their dates cannot decide membership.");
        }
        return out;
    }

    private static String whyGeneric(Input in) {
        List<String> reasons = new ArrayList<>();
        if (in.regulation() == null || !in.regulation().named()) {
            reasons.add("the bank has not named the regulation (title and date)");
        }
        long referenced = in.cells().stream().filter(MatrixCell::carriesReference).count();
        long unconfirmed = in.cells().stream().filter(MatrixCell::carriesReference).filter(c -> !c.confirmed()).count();
        if (referenced == 0) {
            reasons.add("no matrix cell carries a regulation reference");
        } else if (unconfirmed > 0) {
            reasons.add(unconfirmed + " of " + referenced + " cells that carry a reference are not confirmed");
        }
        return String.join("; ", reasons) + ".";
    }

    private static String thresholdText(StalenessThreshold t) {
        if (t == null) {
            return "Snapshot staleness threshold: UNKNOWN (not supplied).";
        }
        return "Snapshot staleness threshold: " + hours(t.value()) + " (" + t.source() + ").";
    }

    // ---- header

    private static Section header(Input in, String title, Instant now) {
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(kv("Report title", Cell.text(title)));
        rows.add(kv("Product version", Cell.text(orUnknown(in.productVersion()))));
        rows.add(kv("Generated at (UTC)", Cell.time(now)));
        rows.add(kv("Generated by", Cell.text(orUnknown(in.generatedBy()))));
        rows.add(kv("View (names as projected)", Cell.text(orUnknown(in.viewLabel()))));
        rows.add(kv("Period from (inclusive)", bound(in.periodFrom(), "no lower bound")));
        rows.add(kv("Period to (exclusive)", bound(in.periodTo(), "no upper bound")));
        rows.add(kv("Filter", Cell.text(in.filterDescription() == null || in.filterDescription().isBlank()
                ? "none (whole scope)" : in.filterDescription())));
        rows.add(kv("Findings in the filtered scope (count of findings matching the filter)", count(in.filteredFindingCount())));
        rows.add(kv("Findings in the whole scope (count of findings without the filter)", count(in.wholeScopeFindingCount())));
        rows.add(kv("Cells confirmed by compliance", Cell.text(confirmedLine(in.cells()))));
        Table table = new Table("header", "Report header", List.of(new Column("field", "Field"), new Column("value", "Value")),
                rows, "");
        List<String> notes = List.of("Cells confirmed by compliance: M counts matrix cells that carry a regulation "
                + "reference; N counts those of them with status CONFIRMED; cells without a reference are in neither "
                + "number.");
        return new Section("header", "Report header", List.of(), List.of(), List.of(table), notes);
    }

    private static List<Cell> kv(String key, Cell value) {
        return List.of(Cell.text(key), value);
    }

    private static Cell bound(Instant at, String noBound) {
        return at == null ? Cell.text(noBound) : Cell.time(at);
    }

    private static Cell count(Integer n) {
        return n == null ? Cell.text(UNKNOWN) : Cell.number(n);
    }

    // ---- matrix version

    private static Section matrixVersion(Input in) {
        MatrixVersion v = in.version();
        List<List<Cell>> meta = new ArrayList<>();
        meta.add(kv("Version number", v == null || v.number() == null ? Cell.text(UNKNOWN) : Cell.number(v.number())));
        meta.add(kv("Version content hash (sha256)", Cell.text(v == null ? UNKNOWN : orUnknown(v.contentSha256()))));
        meta.add(kv("Published at (UTC)", Cell.time(v == null ? null : v.publishedAt())));
        meta.add(kv("Editors", Cell.text(v == null || v.editors().isEmpty() ? UNKNOWN : String.join(", ", v.editors()))));
        meta.add(kv("Published by", Cell.text(v == null ? UNKNOWN : orUnknown(v.publisher()))));
        meta.add(kv("Template origin", Cell.text(v == null || v.templateOrigin() == null || v.templateOrigin().isBlank()
                ? "none (the matrix was not seeded from a template)" : v.templateOrigin())));
        Table metaTable = new Table("matrix-version", "Matrix version",
                List.of(new Column("field", "Field"), new Column("value", "Value")), meta, "");

        List<List<Cell>> cellRows = new ArrayList<>();
        for (MatrixCell c : in.cells()) {
            cellRows.add(List.of(
                    Cell.text(orUnknown(c.sourceZone())),
                    Cell.text(orUnknown(c.destinationZone())),
                    Cell.status(orUnknown(c.statement())),
                    Cell.text(servicesText(c)),
                    Cell.status(orUnknown(c.severity())),
                    Cell.text(asEntered(c.regulationReference())),
                    Cell.status(orUnknown(c.referenceStatus())),
                    Cell.text(asEntered(c.confirmedBy())),
                    Cell.text(asEntered(c.confirmedAt())),
                    Cell.status(confirmationMark(c))));
        }
        Table cellTable = new Table("matrix-cells", "Matrix cells (every explicit cell of the version)", List.of(
                new Column("source", "From zone"), new Column("destination", "To zone"),
                new Column("statement", "Statement"), new Column("services", "Services"),
                new Column("severity", "Severity"), new Column("reference", "Regulation reference (as entered)"),
                new Column("referenceStatus", "Reference status"), new Column("confirmedBy", "Confirmed by"),
                new Column("confirmedAt", "Confirmed at"), new Column("mark", "Compliance confirmation")),
                cellRows, "The version has no explicit cell.");

        List<String> notes = new ArrayList<>();
        if (v != null && v.defaultStatement() != null && !v.defaultStatement().isBlank()) {
            notes.add("A zone pair without an explicit cell takes the version default: " + v.defaultStatement()
                    + (v.defaultSeverity() == null || v.defaultSeverity().isBlank() ? "" : ", severity " + v.defaultSeverity())
                    + ".");
        }
        long unconfirmed = in.cells().stream().filter(c -> !c.confirmed()).count();
        notes.add(unconfirmed + " of " + in.cells().size() + " explicit cells are marked UNCONFIRMED (status is not "
                + "CONFIRMED).");
        return new Section("matrix-version", "Matrix version", List.of(), List.of(), List.of(metaTable, cellTable), notes);
    }

    private static String servicesText(MatrixCell c) {
        if (!c.services().isEmpty()) {
            return String.join(", ", c.services());
        }
        return "ALLOW_LISTED".equals(c.statement()) ? "(none listed: every allow rule in this cell is a violation)" : "";
    }

    private static String confirmationMark(MatrixCell c) {
        if (c.confirmed()) {
            return "CONFIRMED";
        }
        if (!c.carriesReference()) {
            return "UNCONFIRMED (no reference entered)";
        }
        return "UNCONFIRMED (reference status " + orUnknown(c.referenceStatus()) + ")";
    }

    // ---- scope

    private static Section scope(Input in) {
        List<List<Cell>> rows = new ArrayList<>();
        for (PolicyScope p : in.policies()) {
            PolicyStaleness s = p.staleness();
            String verdict = s == null ? UNKNOWN : s.verdict().name();
            String reasons = s == null || s.reasons().isEmpty() ? "" : " (" + s.reasons().stream().map(Enum::name)
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")";
            rows.add(List.of(
                    Cell.text(orUnknown(p.policy())),
                    Cell.text(orUnknown(p.vendor())),
                    Cell.text(p.devices().isEmpty() ? UNKNOWN : String.join(", ", p.devices())),
                    Cell.time(p.snapshotAt()),
                    Cell.text(s == null || s.snapshotAge() == null ? UNKNOWN : hours(s.snapshotAge())),
                    Cell.time(p.runAt()),
                    Cell.status(verdict + reasons),
                    Cell.status(coverage(p.coverage())),
                    Cell.text(p.matrixVersionNumber() == null ? UNKNOWN : Integer.toString(p.matrixVersionNumber())),
                    Cell.text(coverageItems(p))));
        }
        Table policies = new Table("scope-policies", "Evaluated policies", List.of(
                new Column("policy", "Policy"), new Column("vendor", "Vendor"), new Column("devices", "Devices"),
                new Column("snapshotAt", "Snapshot time"), new Column("snapshotAge", "Snapshot age at generation"),
                new Column("runAt", "Run time"), new Column("staleness", "CURRENT / STALE"),
                new Column("coverage", "COMPLETE / INCOMPLETE"), new Column("version", "Matrix version evaluated"),
                new Column("coverageItems", "Coverage items")),
                rows, "No policy was evaluated.");

        List<List<Cell>> nrows = new ArrayList<>();
        for (NotEvaluated n : in.notEvaluated()) {
            nrows.add(List.of(Cell.text(orUnknown(n.subject())), Cell.text(orUnknown(n.kind())),
                    Cell.text(orUnknown(n.reason()))));
        }
        Table notEvaluated = new Table("scope-not-evaluated", "Vendors, policies and devices not evaluated", List.of(
                new Column("subject", "Subject"), new Column("kind", "Kind"), new Column("reason", "Reason")),
                nrows, "None reported by the caller (this is not proof that every device was evaluated).");

        List<List<Cell>> gapRows = new ArrayList<>();
        for (ZoneDefinitionGapRow g : in.zoneDefinitionGaps()) {
            String ranges = g.unassignedPrivateRanges() == null
                    ? "WITHHELD"
                    : g.unassignedPrivateRanges().isEmpty() ? "" : String.join(", ", g.unassignedPrivateRanges());
            gapRows.add(List.of(
                    Cell.text(orUnknown(g.policy())),
                    Cell.text(orUnknown(g.rule())),
                    Cell.text(asEntered(g.section())),
                    Cell.number(g.rangeCount()),
                    Cell.text(ranges)));
        }
        Table gaps = new Table("scope-zone-definition-gaps", "Zone definition gaps (rules touching unassigned private ranges)",
                List.of(new Column("policy", "Policy"), new Column("rule", "Rule"), new Column("section", "Section"),
                        new Column("rangeCount", "Unassigned private range count"),
                        new Column("ranges", "Unassigned private ranges")),
                gapRows, in.zoneDefinitionGapsComplete()
                        ? "No zone definition gaps reported."
                        : "Zone definition gaps: UNKNOWN (evaluation evidence is missing or incomplete).");

        String gapCount = in.zoneDefinitionGapsComplete()
                ? in.zoneDefinitionGaps().size() + " zone definition gaps."
                : "zone definition gap total UNKNOWN (evaluation evidence is missing or incomplete)."
                        + (gapRows.isEmpty() ? "" : " Recorded gap rows shown: " + gapRows.size() + ".");
        List<String> notes = List.of(thresholdText(in.threshold()),
                "Policies listed: " + in.policies().size() + " evaluated policies; " + in.notEvaluated().size()
                        + " subjects not evaluated.", "Stored evidence on the active version in the selected policy scope: "
                        + gapCount);
        return new Section("scope", "Scope and coverage", List.of(), List.of(), List.of(policies, notEvaluated, gaps), notes);
    }

    private static String coverage(String coverage) {
        return "COMPLETE".equals(coverage) || "INCOMPLETE".equals(coverage) ? coverage : UNKNOWN;
    }

    private static String coverageItems(PolicyScope p) {
        if (p.coverageItems().isEmpty()) {
            return "COMPLETE".equals(p.coverage()) ? "none" : UNKNOWN;
        }
        List<String> parts = new ArrayList<>();
        for (CoverageItem i : p.coverageItems()) {
            parts.add(orUnknown(i.code()) + ": " + orUnknown(i.description()));
        }
        return String.join("; ", parts);
    }

    // ---- summary

    private static Section summary(Input in, List<Row> kept) {
        Summary s = in.summary();
        List<String> notes = new ArrayList<>();
        notes.add(s == null || s.headlineText() == null || s.headlineText().isBlank()
                ? "Headline: UNKNOWN (not supplied)." : s.headlineText());
        if (s != null && s.outsideHeadlineText() != null && !s.outsideHeadlineText().isBlank()) {
            notes.add(s.outsideHeadlineText());
        }
        notes.add("Counts in this section are counts of the finding rows listed in this report (after the period "
                + "filter), not counts of rules evaluated.");

        Map<String, Integer> byOutcome = new LinkedHashMap<>();
        for (String o : OUTCOME_ORDER) {
            byOutcome.put(o, 0);
        }
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        for (String sev : SEVERITY_ORDER) {
            bySeverity.put(sev, 0);
        }
        Map<String, Integer> byCell = new TreeMap<>();
        int violations = 0;
        int outsideHeadline = 0;
        int flagged = 0;
        for (Row r : kept) {
            FindingRow f = r.row();
            byOutcome.merge(orUnknown(f.outcome()), 1, Integer::sum);
            if (!f.flags().isEmpty()) {
                flagged++;
            }
            if ("VIOLATION".equals(f.outcome())) {
                violations++;
                bySeverity.merge(orUnknown(f.severity()), 1, Integer::sum);
                for (String cell : f.cells()) {
                    byCell.merge(orUnknown(cell), 1, Integer::sum);
                }
                if (outsideHeadline(f)) {
                    outsideHeadline++;
                }
            }
        }
        int notEvaluable = byOutcome.getOrDefault("NOT_EVALUABLE", 0);
        int unknown = byOutcome.getOrDefault("UNKNOWN", 0);
        List<Kpi> kpis = List.of(
                new Kpi("VIOLATION rows listed", Integer.toString(violations), violations > 0 ? "BAD" : "INFO"),
                new Kpi("of which outside the headline (other ordered layer)", Integer.toString(outsideHeadline),
                        outsideHeadline > 0 ? "WARN" : "INFO"),
                new Kpi("NOT_EVALUABLE rows listed", Integer.toString(notEvaluable), notEvaluable > 0 ? "WARN" : "INFO"),
                new Kpi("UNKNOWN rows listed", Integer.toString(unknown), unknown > 0 ? "UNKNOWN" : "INFO"),
                new Kpi("rows carrying a flag", Integer.toString(flagged), flagged > 0 ? "WARN" : "INFO"));

        Table outcome = countTable("summary-outcome", "Finding rows listed, per outcome", "Outcome", byOutcome);
        Table severity = countTable("summary-severity", "VIOLATION rows listed, per highest severity", "Severity", bySeverity);
        Table cell = countTable("summary-cell", "VIOLATION rows listed, per violated matrix cell (a row that violates "
                + "several cells counts once in each)", "Matrix cell (from zone -> to zone)", byCell);
        return new Section("summary", "Summary", List.of(), kpis, List.of(outcome, severity, cell), notes);
    }

    private static Table countTable(String id, String title, String keyLabel, Map<String, Integer> counts) {
        List<List<Cell>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            rows.add(List.of(Cell.text(e.getKey()), Cell.number(e.getValue())));
        }
        return new Table(id, title, List.of(new Column("key", keyLabel), new Column("count", "Finding rows")), rows,
                "None listed.");
    }

    // ---- findings

    private static Section findings(Input in, List<Row> kept, int outside) {
        List<List<Cell>> rows = new ArrayList<>();
        for (Row r : kept) {
            FindingRow f = r.row();
            Finding l = f.lifecycle();
            rows.add(List.of(
                    Cell.text(orUnknown(f.policy())),
                    Cell.text(orUnknown(f.rule())),
                    Cell.text(f.cells().isEmpty() ? UNKNOWN : String.join("; ", f.cells())),
                    Cell.status(orUnknown(f.severity())),
                    Cell.status(orUnknown(f.outcome())),
                    Cell.status(membership(f)),
                    Cell.text(f.flags().isEmpty() ? "" : String.join(", ", f.flags())),
                    Cell.text(orUnknown(f.reason())),
                    Cell.text(asEntered(f.detail())),
                    Cell.status(l == null ? "UNKNOWN (no stored finding)" : stateOf(l)),
                    Cell.time(l == null ? null : l.firstObservedAt()),
                    Cell.time(l == null ? null : l.firstObservedAnyVersion()),
                    Cell.time(l == null ? null : l.lastObservedAt()),
                    Cell.time(l == null ? null : l.resolvedAt()),
                    Cell.text(l == null ? "" : l.resolutionReason() != null ? l.resolutionReason().name()
                            : l.status() == Status.RESOLVED ? UNKNOWN : ""),
                    Cell.status(r.inclusion() == Inclusion.UNDECIDABLE
                            ? "KEPT (dates cannot decide period membership)" : "IN_PERIOD")));
        }
        Table table = new Table("findings", "Findings", List.of(
                new Column("policy", "Policy"), new Column("rule", "Rule"), new Column("cells", "Violated matrix cells"),
                new Column("severity", "Highest severity"), new Column("outcome", "Outcome"),
                new Column("headline", "Headline membership"), new Column("flags", "Flags"),
                new Column("reason", "Reason"), new Column("detail", "Detail"), new Column("state", "State"),
                new Column("firstObservedAt", "First observed"),
                new Column("firstObservedAnyVersion", "First observed (any matrix version)"),
                new Column("lastObservedAt", "Last observed"), new Column("resolvedAt", "Resolved"),
                new Column("resolution", "Resolution reason"), new Column("period", "Period membership")),
                rows, "No finding rows in the period (this is not proof of compliance: see scope and coverage).");

        List<String> notes = List.of(
                kept.size() + " finding rows are listed; none is capped. VIOLATION rows include flagged rows and rows "
                        + "outside the headline; NOT_EVALUABLE and UNKNOWN rows are listed with their reason.",
                outside + " rows supplied by the caller are outside the period and not listed.");
        return new Section("findings", "Findings", List.of(), List.of(), List.of(table), notes);
    }

    private static boolean outsideHeadline(FindingRow f) {
        return f.flags().stream().anyMatch(OUTSIDE_HEADLINE_FLAGS::contains);
    }

    private static String membership(FindingRow f) {
        if (!"VIOLATION".equals(f.outcome())) {
            return "NOT_COUNTED (undecided, never counted as compliant)";
        }
        return outsideHeadline(f) ? "OUTSIDE_HEADLINE (another ordered layer decides or is unknown)"
                : "COUNTS_TOWARD_HEADLINE";
    }

    private static String stateOf(Finding l) {
        return l.status() == Status.RESOLVED ? "RESOLVED" : "OPEN";
    }

    // ---- fixed sentences

    private static Section statements() {
        return new Section("statements", "Statements", List.of(), List.of(), List.of(),
                List.of(ACCEPTED_DEVIATIONS_SENTENCE, LIMITATIONS_TEXT));
    }

    // ------------------------------------------------------------------ small helpers

    private static String orUnknown(String v) {
        return v == null || v.isBlank() ? UNKNOWN : v;
    }

    /** Operator-entered text: exactly as entered, no trimming, no escaping; a missing value is an empty cell. */
    private static String asEntered(String v) {
        return v == null ? "" : v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String hours(Duration d) {
        long minutes = d.toMinutes();
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
