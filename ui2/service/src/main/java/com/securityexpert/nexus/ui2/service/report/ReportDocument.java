package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The one structured document a report builder produces (Reports R1 contract "Masking and RBAC": "each report builds
 * one structured document model"). {@link ReportDocumentMasker} projects it once (MASKED or RAW) and every renderer --
 * the on-screen JSON, PDF, XLSX and CSV -- reads only the projected document.
 *
 * <p>Identity-bearing values are typed cells ({@link CellKind#DEVICE}, {@link CellKind#CLUSTER},
 * {@link CellKind#ADDRESS}, {@link CellKind#SERIAL}) with an optional {@link Ref} the masker needs (cluster reference,
 * device id). The ref is never rendered or serialized, and the projection drops it.</p>
 */
public record ReportDocument(String templateId, String title, String audience, String question, Instant generatedAt,
        String projection, Map<String, String> parameters, List<PlaneAsOf> dataAsOf, List<String> notices,
        List<Section> sections) {

    public ReportDocument {
        Objects.requireNonNull(templateId, "templateId");
        parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
        dataAsOf = List.copyOf(dataAsOf);
        notices = List.copyOf(notices);
        sections = List.copyOf(sections);
    }

    /** Not projected yet: the builders' output. */
    public static final String UNPROJECTED = "UNPROJECTED";

    /**
     * "Data as of" for one evidence plane (contract "Data age and coverage"): newest and oldest evidence time among the
     * covered units, how many of {@code total} units have evidence, and how many of the covered ones are stale.
     */
    public record PlaneAsOf(String plane, Instant newest, Instant oldest, int covered, int total, int stale, String unit) {
        public boolean anyStale() {
            return stale > 0;
        }
    }

    /** A headline figure: label, value and a tone word (OK, WARN, BAD, UNKNOWN, STALE, INFO). */
    public record Kpi(String label, String value, String tone) {
    }

    public record Column(String key, String label) {
    }

    public enum CellKind { TEXT, STATUS, NUMBER, PERCENT, TIMESTAMP, DEVICE, CLUSTER, ADDRESS, SERIAL }

    /** What the masker needs beside a name: the device's cluster reference and its id. Never rendered. */
    public record Ref(String deviceId, String clusterRef) {
    }

    public record Cell(String text, CellKind kind, @JsonIgnore Ref ref) {
        public Cell {
            Objects.requireNonNull(kind, "kind");
        }

        public static Cell text(String text) {
            return new Cell(text, CellKind.TEXT, null);
        }

        public static Cell status(String status) {
            return new Cell(status, CellKind.STATUS, null);
        }

        public static Cell number(long value) {
            return new Cell(Long.toString(value), CellKind.NUMBER, null);
        }

        public static Cell percent(String value) {
            return new Cell(value, CellKind.PERCENT, null);
        }

        public static Cell time(Instant at) {
            return new Cell(at == null ? "UNKNOWN" : at.toString(), CellKind.TIMESTAMP, null);
        }

        public static Cell device(String hostname, String deviceId, String clusterRef) {
            return new Cell(hostname, CellKind.DEVICE, new Ref(deviceId, clusterRef));
        }

        public static Cell cluster(String clusterRef) {
            return new Cell(clusterRef, CellKind.CLUSTER, null);
        }
    }

    public record Table(String id, String title, List<Column> columns, List<List<Cell>> rows, String emptyText) {
        public Table {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            for (List<Cell> row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException("row width " + row.size() + " != " + columns.size() + " in " + id);
                }
            }
        }
    }

    public record Section(String id, String title, List<PlaneAsOf> dataAsOf, List<Kpi> kpis, List<Table> tables,
            List<String> notes) {
        public Section {
            dataAsOf = List.copyOf(dataAsOf);
            kpis = List.copyOf(kpis);
            tables = List.copyOf(tables);
            notes = List.copyOf(notes);
        }
    }

    @JsonIgnore
    public boolean projected() {
        return !UNPROJECTED.equals(projection);
    }
}
