package com.securityexpert.nexus.ui2.service.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;

/**
 * The one projection pass of the Reports R1 contract ("One masking pass"): every report document passes through
 * {@link #project} exactly once, before any renderer (on-screen JSON, PDF, XLSX, CSV). MASKED uses the existing
 * {@code TopologyNamePseudonymizer} and {@code SubnetPreservingIpMasker} through {@link ReportProjectionMasker}; RAW
 * only drops the masker references. Renderers refuse an unprojected document.
 *
 * <p>Order inside the pass: typed names first (device, cluster, address, serial), which registers each raw name with
 * the pseudonymizer, then every free-text string (titles, notices, notes, KPI values, text cells) through the
 * address masker and the name dictionary, so a name repeated in prose is rewritten too.</p>
 */
public final class ReportDocumentMasker {

    private final ReportProjectionMasker masker;

    public ReportDocumentMasker(ReportProjectionMasker masker) {
        this.masker = Objects.requireNonNull(masker, "masker");
    }

    public ReportDocument project(ReportDocument doc, ProjectionMode mode) {
        Objects.requireNonNull(doc, "doc");
        if (doc.projected()) throw new IllegalStateException("a report document is projected exactly once");
        if (mode == ProjectionMode.RAW) return rewrite(doc, "RAW", c -> new Cell(c.text(), c.kind(), null), s -> s);
        // Pass 1: typed names, registering raw names for the text dictionary.
        Map<Cell, String> typed = new java.util.IdentityHashMap<>();
        for (Section section : doc.sections()) {
            for (Table table : section.tables()) {
                for (List<Cell> row : table.rows()) {
                    for (Cell cell : row) {
                        String masked = typedName(cell);
                        if (masked != null || isIdentity(cell)) typed.put(cell, masked);
                    }
                }
            }
        }
        // Pass 2: everything else is text.
        UnaryOperator<String> text = this::text;
        return rewrite(doc, "MASKED", c -> typed.containsKey(c)
                ? new Cell(nullToUnknown(typed.get(c)), c.kind(), null)
                : new Cell(c.kind() == ReportDocument.CellKind.TEXT || c.kind() == ReportDocument.CellKind.STATUS
                        ? text.apply(c.text()) : c.text(), c.kind(), null), text);
    }

    private static boolean isIdentity(Cell cell) {
        return switch (cell.kind()) {
            case DEVICE, CLUSTER, ADDRESS, SERIAL -> true;
            default -> false;
        };
    }

    private String typedName(Cell cell) {
        String raw = cell.text();
        if (raw == null || raw.isBlank()) return null;
        return switch (cell.kind()) {
            case DEVICE -> masker.device(raw, cell.ref() == null ? null : cell.ref().clusterRef(),
                    cell.ref() == null ? null : cell.ref().deviceId());
            case CLUSTER -> masker.cluster(raw);
            case ADDRESS -> masker.address(raw);
            case SERIAL -> masker.serial(raw);
            default -> null;
        };
    }

    private String text(String raw) {
        return raw == null ? null : masker.text(raw);
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static ReportDocument rewrite(ReportDocument doc, String projection, UnaryOperator<Cell> cells,
            UnaryOperator<String> text) {
        List<Section> sections = new ArrayList<>();
        for (Section s : doc.sections()) {
            List<Table> tables = new ArrayList<>();
            for (Table t : s.tables()) {
                List<List<Cell>> rows = new ArrayList<>();
                for (List<Cell> row : t.rows()) rows.add(row.stream().map(cells).toList());
                tables.add(new Table(t.id(), text.apply(t.title()), t.columns(), rows, text.apply(t.emptyText())));
            }
            List<Kpi> kpis = s.kpis().stream().map(k -> new Kpi(text.apply(k.label()), text.apply(k.value()), k.tone())).toList();
            sections.add(new Section(s.id(), text.apply(s.title()), s.dataAsOf(), kpis, tables,
                    s.notes().stream().map(text).toList()));
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        doc.parameters().forEach((k, v) -> parameters.put(k, text.apply(v)));
        return new ReportDocument(doc.templateId(), text.apply(doc.title()), doc.audience(), text.apply(doc.question()),
                doc.generatedAt(), projection, parameters, doc.dataAsOf(), doc.notices().stream().map(text).toList(),
                sections);
    }
}
