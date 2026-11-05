package com.securityexpert.nexus.ui2.service.report;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV (Reports R1 contract "Renderers": RFC 4180 with formula-injection escaping). One file holds the whole report:
 * a header block (report, projection, generated, parameters, data as of, notices), then each table as a title record,
 * a column record and its rows. Every record has the same number of fields (the widest table's), CRLF line breaks,
 * every field quoted, quotes doubled. A field a spreadsheet would read as a formula (leading {@code = + - @}, or a
 * leading tab, CR or LF, after any leading whitespace or control characters) is prefixed with a single quote.
 */
public final class CsvReportRenderer implements ReportRenderer {

    @Override
    public ReportFormat format() {
        return ReportFormat.CSV;
    }

    @Override
    public byte[] render(ReportDocument d) {
        ReportRenderer.requireProjected(d);
        List<List<String>> records = new ArrayList<>();
        records.add(List.of("Report", d.templateId() + " " + d.title()));
        records.add(List.of("Projection", ReportRenderer.projectionLabel(d)));
        records.add(List.of("Generated", d.generatedAt().toString()));
        d.parameters().forEach((k, v) -> records.add(List.of("Parameter " + k, v)));
        for (ReportDocument.PlaneAsOf p : d.dataAsOf()) {
            records.add(List.of("Data as of: " + p.plane(), "newest " + ReportRenderer.show(ReportJson.iso(p.newest()))
                    + "; oldest " + ReportRenderer.show(ReportJson.iso(p.oldest())) + "; coverage " + p.covered() + " of "
                    + p.total() + " " + p.unit() + "; stale " + p.stale()));
        }
        for (String notice : d.notices()) records.add(List.of("Notice", notice));
        for (ReportDocument.Section s : d.sections()) {
            records.add(List.of());
            records.add(List.of("Section", s.title()));
            for (ReportDocument.Kpi k : s.kpis()) records.add(List.of("Figure", k.label(), k.value(), k.tone()));
            for (ReportDocument.Table t : s.tables()) {
                records.add(List.of());
                records.add(List.of("Table", s.title() + " - " + t.title()));
                records.add(t.columns().stream().map(ReportDocument.Column::label).toList());
                if (t.rows().isEmpty()) records.add(List.of(ReportRenderer.show(t.emptyText())));
                for (List<ReportDocument.Cell> row : t.rows()) {
                    records.add(row.stream().map(c -> ReportRenderer.show(c.text())).toList());
                }
            }
            for (String note : s.notes()) records.add(List.of("Note", note));
        }
        int width = records.stream().mapToInt(List::size).max().orElse(1);
        StringBuilder out = new StringBuilder();
        for (List<String> record : records) {
            for (int i = 0; i < width; i++) {
                if (i > 0) out.append(',');
                out.append(cell(i < record.size() ? record.get(i) : ""));
            }
            out.append("\r\n");
            if (out.length() > MAX_BYTES) throw new TooLarge(MAX_BYTES);
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new TooLarge(MAX_BYTES);
        return bytes;
    }

    /** One RFC 4180 field, quoted, with the formula guard. */
    static String cell(String value) {
        String text = value == null ? "" : value;
        if (text.matches("(?s)^[\\s\\p{Cc}]*[=+@-].*") || text.startsWith("\t") || text.startsWith("\r")
                || text.startsWith("\n")) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
