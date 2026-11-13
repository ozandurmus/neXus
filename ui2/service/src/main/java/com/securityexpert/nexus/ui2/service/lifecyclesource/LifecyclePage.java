package com.securityexpert.nexus.ui2.service.lifecyclesource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a lifecycle page parser returns (contract section 4): one entry per product key, the publication each entry belongs
 * to, and counts of what was not used. An entry is PARSED with its dates, or NEEDS_REVIEW with a code word and no dates.
 */
public final class LifecyclePage {

    public static final String PARSED = "PARSED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    /** The date fields of an entry, in the order of the V214 columns. */
    public static final List<String> DATE_FIELDS = List.of("end_of_sale", "end_of_engineering", "end_of_support",
            "end_of_security_support", "end_of_extended_support");

    /** One product as a publication lists it. {@code dates} maps a field of {@link #DATE_FIELDS} to its read. */
    public record Entry(String vendor, String kind, String productKey, String productLabel, String documentRef,
            Map<String, VendorDate.Read> dates, String parseState, String parseNote) {

        public Entry {
            Objects.requireNonNull(vendor);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(productKey);
            dates = dates == null ? Map.of() : Map.copyOf(dates);
        }

        public LocalDate date(String field) {
            VendorDate.Read read = dates.get(field);
            return PARSED.equals(parseState) && read != null ? read.date() : null;
        }

        public List<String> monthOnlyFields() {
            if (!PARSED.equals(parseState)) return List.of();
            List<String> out = new ArrayList<>();
            for (String field : DATE_FIELDS) {
                VendorDate.Read read = dates.get(field);
                if (read != null && read.kind() == VendorDate.Kind.MONTH) out.add(field);
            }
            return out;
        }

        /** Same product, same dates and same state: a repeat of this entry in the same document. */
        boolean sameAs(Entry other) {
            return kind.equals(other.kind) && productKey.equals(other.productKey) && parseState.equals(other.parseState)
                    && dates.equals(other.dates);
        }
    }

    /**
     * The result of one document. {@code recognised} is false when the document is not a lifecycle publication the parser
     * reads (a subscription bulletin, a page of another form); it is then counted as ignored and changes nothing.
     * {@code documentRefs} are the publications the document stands for: an upload or fetch replaces the stored entries of
     * exactly these, so a product that disappeared from the publication disappears from the store.
     */
    public record Result(boolean recognised, String pageForm, List<String> documentRefs, List<Entry> entries, int rowsSkipped) {
        public Result {
            documentRefs = documentRefs == null ? List.of() : List.copyOf(documentRefs);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public static Result notRecognised(String pageForm) {
            return new Result(false, pageForm, List.of(), List.of(), 0);
        }
    }

    private LifecyclePage() {
    }

    /** The state of an entry from its date reads: any unreadable date makes it NEEDS_REVIEW (DATE_UNREADABLE). */
    static Entry entry(String vendor, String kind, String key, String label, String ref, Map<String, VendorDate.Read> dates) {
        boolean unreadable = dates.values().stream().anyMatch(r -> r.kind() == VendorDate.Kind.UNREADABLE);
        return new Entry(vendor, kind, key, cut(label), ref, dates, unreadable ? NEEDS_REVIEW : PARSED,
                unreadable ? "DATE_UNREADABLE" : null);
    }

    static Entry review(String vendor, String kind, String key, String label, String ref, String note) {
        return new Entry(vendor, kind, key, cut(label), ref, Map.of(), NEEDS_REVIEW, note);
    }

    /**
     * One entry per kind and key: repeats with the same dates collapse; a key listed twice with different dates becomes one
     * NEEDS_REVIEW entry (CONFLICTING_ROWS), because choosing one of them would be a guess.
     */
    static List<Entry> merge(List<Entry> entries) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Entry e : entries) {
            String key = e.kind() + "|" + e.productKey();
            Entry kept = byKey.get(key);
            if (kept == null) byKey.put(key, e);
            else if (!kept.sameAs(e)) byKey.put(key, review(e.vendor(), e.kind(), e.productKey(), kept.productLabel(), kept.documentRef(),
                    "CONFLICTING_ROWS"));
        }
        return List.copyOf(byKey.values());
    }

    static String cut(String label) {
        String value = label == null ? "" : label.strip();
        return value.length() > 200 ? value.substring(0, 200) : value;
    }
}
