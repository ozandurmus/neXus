package com.securityexpert.nexus.ui2.service.lifecycle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;

/** Bounded CSV validation; errors name the row and field, never reproduce input. */
public final class LifecycleCatalogImport {
    public static final Set<String> VENDORS = Set.of("CHECKPOINT", "PALOALTO", "FORTINET", "BLUECOAT",
            "INFOBLOX", "RADWARE", "CISCO_ASA", "PULSE_SECURE");
    public static final List<String> HEADER = List.of("vendor", "kind", "product", "end_of_sale",
            "end_of_support", "end_of_engineering", "note");
    public record RowError(int row, String reason) { }
    public record Result(List<LifecycleCatalogEntry> entries, List<RowError> errors) { }

    private LifecycleCatalogImport() { }

    public static Result parse(String csv, String actor, Instant now) {
        if (csv == null || csv.length() > 1_000_000) return failed(0, "CSV is missing or exceeds 1 MB");
        List<List<String>> rows;
        try { rows = csv(csv.startsWith("\uFEFF") ? csv.substring(1) : csv); }
        catch (IllegalArgumentException e) { return failed(0, e.getMessage()); }
        if (rows.isEmpty() || !rows.getFirst().equals(HEADER)) return failed(1, "Expected columns: " + String.join(",", HEADER));
        if (rows.size() > 5001) return failed(0, "CSV exceeds 5000 data rows");
        var entries = new ArrayList<LifecycleCatalogEntry>();
        var errors = new ArrayList<RowError>();
        Set<List<String>> keys = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            var row = rows.get(i);
            try {
                var entry = entry(row, "IMPORT", actor, now);
                if (!keys.add(List.of(entry.vendor(), entry.kind(), entry.product()))) {
                    errors.add(new RowError(i + 1, "Duplicate vendor/kind/product in file"));
                } else entries.add(entry);
            } catch (IllegalArgumentException e) { errors.add(new RowError(i + 1, e.getMessage())); }
        }
        return new Result(List.copyOf(entries), List.copyOf(errors));
    }

    public static LifecycleCatalogEntry entry(List<String> fields, String source, String actor, Instant now) {
        if (fields.size() != 7) throw new IllegalArgumentException("Expected seven columns");
        String vendor = fields.get(0).trim();
        String kind = fields.get(1).trim();
        String product = fields.get(2).trim();
        if (!VENDORS.contains(vendor)) throw new IllegalArgumentException("vendor is unsupported");
        if (!Set.of("HARDWARE", "SOFTWARE").contains(kind)) throw new IllegalArgumentException("kind must be HARDWARE or SOFTWARE");
        if (product.isEmpty() || product.length() > 200 || product.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("product must be 1–200 characters without control characters");
        }
        if (fields.get(6).length() > 2000) throw new IllegalArgumentException("note exceeds 2000 characters");
        return new LifecycleCatalogEntry(UUID.randomUUID().toString(), vendor, kind, product,
                date(fields.get(3), "end_of_sale"), date(fields.get(4), "end_of_support"),
                date(fields.get(5), "end_of_engineering"), source, fields.get(6), actor, now);
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            if (!value.trim().matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) { throw new IllegalArgumentException(field + " must be a valid YYYY-MM-DD date"); }
    }

    private static Result failed(int row, String reason) { return new Result(List.of(), List.of(new RowError(row, reason))); }

    private static List<List<String>> csv(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else field.append(c);
            } else if (c == ',' || c == '\r' || c == '\n') {
                row.add(field.toString()); field.setLength(0); closed = false;
                if (c != ',') {
                    rows.add(List.copyOf(row)); row.clear();
                    if (rows.size() > 5001) throw new IllegalArgumentException("CSV exceeds 5000 data rows");
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
            } else if (c == '"' && field.isEmpty() && !closed) quoted = true;
            else if (closed || c == '"') throw new IllegalArgumentException("Malformed CSV near row " + (rows.size() + 1));
            else field.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote near row " + (rows.size() + 1));
        if (!row.isEmpty() || !field.isEmpty() || closed) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }
}
