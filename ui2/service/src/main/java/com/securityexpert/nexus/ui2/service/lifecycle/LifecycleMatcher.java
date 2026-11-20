package com.securityexpert.nexus.ui2.service.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleVendorRepository.VendorEntry;

/**
 * Matches one device part (hardware model or software version) against the lifecycle catalog
 * (docs/design/LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 5). Two kinds of catalog row exist: operator rows (V144,
 * typed or imported as CSV by a security_admin) and vendor entries (V219, read from a vendor publication online or from an
 * uploaded page). The order, and every state:
 * <ol>
 *   <li>No key for the device's model or version: {@code NO_LIFECYCLE_DATA}, with the reason.</li>
 *   <li>An operator row with the same key wins (an explicit entry, for example a contracted extension). More than one:
 *       {@code NO_LIFECYCLE_DATA} (ambiguous). Older than {@value #HAND_LOADED_FRESH_DAYS} days: {@code NOT_EVALUATED}.</li>
 *   <li>Vendor entries with the same key: those still fresh ({@value #FEED_FRESH_DAYS} days for an online source,
 *       {@value #HAND_LOADED_FRESH_DAYS} for an uploaded page) count; if none is fresh, {@code NOT_EVALUATED}. A fresh
 *       entry that needs review, or fresh entries that disagree, give {@code NEEDS_REVIEW} and no dates. Otherwise
 *       {@code MATCHED}.</li>
 *   <li>No row with the key: {@code NO_LIFECYCLE_DATA} when a fresh source covers this vendor and kind (the vendor lists
 *       no such product), else {@code NOT_EVALUATED} (nothing was loaded to compare with).</li>
 * </ol>
 */
public final class LifecycleMatcher {

    public static final long FEED_FRESH_DAYS = 30;
    public static final long HAND_LOADED_FRESH_DAYS = 180;

    public static final String MATCHED = "MATCHED";
    public static final String NO_LIFECYCLE_DATA = "NO_LIFECYCLE_DATA";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    public static final String NOT_ANNOUNCED = "Not announced";
    public static final String NOT_APPLICABLE = "Not applicable";

    /** The whole catalog, loaded once per request. {@code now} null switches the age rule off (old callers and tests). */
    public record Catalog(List<LifecycleCatalogEntry> operator, List<VendorEntry> vendor, Instant now) {
        public Catalog {
            operator = operator == null ? List.of() : operator;
            vendor = vendor == null ? List.of() : vendor;
        }

        public static Catalog operatorOnly(List<LifecycleCatalogEntry> operator) {
            return new Catalog(operator, List.of(), null);
        }
    }

    private LifecycleMatcher() {
    }

    public static Map<String, Object> match(String vendor, String kind, boolean manager, String model, String observed, Catalog catalog) {
        boolean hardware = "HARDWARE".equals(kind);
        boolean ruled = LifecycleKeys.ruled(vendor);
        String key = hardware ? LifecycleKeys.model(vendor, observed) : LifecycleKeys.deviceTrain(vendor, manager, model, observed);
        String basisWord = hardware ? ruled ? "model key" : "model exact" : ruled ? "version train" : "version exact";
        if (key == null) {
            if (observed == null || observed.isBlank()) {
                return body(NO_LIFECYCLE_DATA, hardware ? "MODEL_MISSING" : "VERSION_MISSING", "Model/version missing", null, null, null);
            }
            if (hardware && LifecycleKeys.virtualPlatform(observed)) {
                return body(NO_LIFECYCLE_DATA, "VIRTUAL_PLATFORM", "Virtual machine or open server (" + observed.strip()
                        + "): no appliance model to look up", null, null, null);
            }
            return body(NO_LIFECYCLE_DATA, hardware ? "MODEL_FORM_UNRECOGNISED" : "VERSION_FORM_UNRECOGNISED",
                    "Model/version form not recognised for lifecycle matching", null, null, null);
        }

        var operatorRows = catalog.operator().stream().filter(e -> e.vendor().equals(vendor) && e.kind().equals(kind))
                .filter(e -> key.equals(catalogKey(vendor, kind, e.product()))).toList();
        if (operatorRows.size() > 1) return body(NO_LIFECYCLE_DATA, "AMBIGUOUS_CATALOG_MATCH", "Ambiguous catalog matches", null, null, key);
        if (operatorRows.size() == 1) {
            var row = operatorRows.getFirst();
            Long age = ageDays(row.importedAt(), catalog.now());
            if (age != null && age > HAND_LOADED_FRESH_DAYS) {
                return body(NOT_EVALUATED, "OPERATOR_ENTRY_STALE", "Operator catalog entry is " + age + " days old (limit " + HAND_LOADED_FRESH_DAYS
                        + "); confirm it against the vendor page", null, null, key);
            }
            var dates = LifecycleProjection.catalog(row, false);
            dates.put("origin", "OPERATOR_ENTRY");
            dates.put("age_days", age);
            return body(MATCHED, null, basisWord + " · operator entry", dates, key);
        }

        if (hardware && LifecycleKeys.virtualPanorama(vendor, key)) {
            String basis = basisWord + " · virtual Panorama (hardware not applicable)";
            return body(MATCHED, basis, basis, notApplicableView(vendor, kind, key), key);
        }

        var vendorRows = catalog.vendor().stream().filter(e -> e.vendor().equals(vendor) && e.kind().equals(kind)
                && key.equals(e.productKey())).toList();
        var fresh = vendorRows.stream().filter(e -> fresh(e, catalog.now())).toList();
        if (!vendorRows.isEmpty() && fresh.isEmpty()) {
            var newest = vendorRows.stream().max(Comparator.comparing(VendorEntry::confirmedAt)).orElseThrow();
            return body(NOT_EVALUATED, "VENDOR_ENTRY_STALE", "Vendor source " + newest.source() + " last confirmed this entry "
                    + ageDays(newest.confirmedAt(), catalog.now()) + " days ago (limit " + limit(newest) + ")", null, null, key);
        }
        if (!fresh.isEmpty()) {
            var review = fresh.stream().filter(e -> !"PARSED".equals(e.parseState())).findFirst();
            if (review.isPresent()) {
                return body(NEEDS_REVIEW, "VENDOR_ENTRY_NEEDS_REVIEW", "Vendor entry needs review (" + review.get().parseNote() + ", " + review.get().source() + ")",
                        null, vendorView(review.get(), catalog.now()), key);
            }
            Set<List<LocalDate>> distinct = new LinkedHashSet<>();
            for (var e : fresh) distinct.add(java.util.Arrays.asList(e.endOfSale(), e.endOfEngineering(), e.endOfSupport()));
            if (distinct.size() > 1) return body(NEEDS_REVIEW, "VENDOR_SOURCES_DISAGREE", "Vendor sources disagree (CONFLICTING_SOURCES)", null, null, key);
            var chosen = fresh.stream().min(Comparator.comparing((VendorEntry e) -> "FEED".equals(e.origin()) ? 0 : 1)
                    .thenComparing(VendorEntry::confirmedAt, Comparator.reverseOrder())).orElseThrow();
            return body(MATCHED, null, basisWord + " · " + describe(chosen), vendorView(chosen, catalog.now()), key);
        }

        var freshVendorSource = catalog.vendor().stream()
                .filter(e -> e.vendor().equals(vendor) && e.kind().equals(kind) && fresh(e, catalog.now()))
                .min(Comparator.comparing((VendorEntry e) -> "FEED".equals(e.origin()) ? 0 : 1)
                        .thenComparing(VendorEntry::confirmedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (hardware && "PALOALTO".equals(vendor) && freshVendorSource.isPresent()) {
            VendorEntry sourceEntry = freshVendorSource.get();
            LocalDate checked = checkedDate(sourceEntry.confirmedAt(), catalog.now());
            String note = "No end-of-life announced for this model on the vendor page"
                    + (checked == null ? "" : " (checked " + checked + ")");
            return body(MATCHED, note, note, notAnnouncedView(sourceEntry, key, catalog.now()), key);
        }

        boolean covered = catalog.operator().stream().anyMatch(e -> e.vendor().equals(vendor) && e.kind().equals(kind)
                && !operatorStale(e, catalog.now()))
                || freshVendorSource.isPresent();
        if (covered) {
            return body(NO_LIFECYCLE_DATA, "NO_CATALOG_MATCH", "No catalog match"
                    + ("CISCO_ASA".equals(vendor) ? " (no end-of-life bulletin names this " + (hardware ? "model" : "train") + ")" : ""),
                    null, null, key);
        }
        return body(NOT_EVALUATED, "NO_SOURCE_LOADED", "No lifecycle source loaded for " + vendor + " " + kind.toLowerCase(java.util.Locale.ROOT)
                + "; load the vendor page or the catalog CSV", null, null, key);
    }

    /** The key of an operator catalog product; for vendors without key rules the trimmed text. */
    public static String catalogKey(String vendor, String kind, String product) {
        return "HARDWARE".equals(kind) ? LifecycleKeys.model(vendor, product) : LifecycleKeys.catalogTrain(vendor, product);
    }

    static boolean fresh(VendorEntry e, Instant now) {
        Long age = ageDays(e.confirmedAt(), now);
        return age == null || age <= limit(e);
    }

    private static boolean operatorStale(LifecycleCatalogEntry e, Instant now) {
        Long age = ageDays(e.importedAt(), now);
        return age != null && age > HAND_LOADED_FRESH_DAYS;
    }

    static long limit(VendorEntry e) {
        return "FEED".equals(e.origin()) ? FEED_FRESH_DAYS : HAND_LOADED_FRESH_DAYS;
    }

    static Long ageDays(Instant at, Instant now) {
        if (at == null || now == null) return null;
        return Math.max(0, Duration.between(at, now).toDays());
    }

    private static String describe(VendorEntry e) {
        String where = switch (e.source()) {
            case "cisco_eol" -> "Cisco bulletin " + e.documentRef();
            case "paloalto_eol" -> "Palo Alto end-of-life page";
            case "checkpoint_lifecycle" -> "Check Point life cycle page";
            default -> e.source();
        };
        return where + ("FEED".equals(e.origin()) ? " (online)" : " (uploaded page)");
    }

    /** The vendor entry in the shape of {@link LifecycleProjection#catalog} plus its provenance. */
    static Map<String, Object> vendorView(VendorEntry e, Instant now) {
        var body = new LinkedHashMap<String, Object>();
        body.put("catalog_id", e.entryId());
        body.put("vendor", e.vendor());
        body.put("kind", e.kind());
        body.put("product", e.productLabel());
        body.put("end_of_sale", text(e.endOfSale()));
        body.put("end_of_support", text(e.endOfSupport()));
        body.put("end_of_engineering", text(e.endOfEngineering()));
        body.put("end_of_security_support", text(e.endOfSecuritySupport()));
        body.put("end_of_extended_support", text(e.endOfExtendedSupport()));
        body.put("month_only", e.monthOnlyFields());
        body.put("source", e.source());
        body.put("origin", e.origin());
        body.put("document_ref", e.documentRef());
        body.put("source_url", e.sourceUrl());
        body.put("parse_state", e.parseState());
        body.put("parse_note", e.parseNote());
        body.put("confirmed_at", e.confirmedAt() == null ? null : e.confirmedAt().toString());
        body.put("age_days", ageDays(e.confirmedAt(), now));
        return body;
    }

    private static Map<String, Object> notAnnouncedView(VendorEntry e, String productKey, Instant now) {
        var body = new LinkedHashMap<String, Object>();
        body.put("vendor", e.vendor());
        body.put("kind", e.kind());
        body.put("product", productKey);
        body.put("end_of_sale", NOT_ANNOUNCED);
        body.put("end_of_support", NOT_ANNOUNCED);
        body.put("end_of_engineering", NOT_ANNOUNCED);
        body.put("month_only", List.of());
        body.put("source", e.source());
        body.put("origin", e.origin());
        body.put("document_ref", e.documentRef());
        body.put("source_url", e.sourceUrl());
        body.put("confirmed_at", e.confirmedAt() == null ? null : e.confirmedAt().toString());
        body.put("age_days", ageDays(e.confirmedAt(), now));
        return body;
    }

    private static Map<String, Object> notApplicableView(String vendor, String kind, String product) {
        var body = new LinkedHashMap<String, Object>();
        body.put("vendor", vendor);
        body.put("kind", kind);
        body.put("product", product);
        body.put("end_of_sale", NOT_APPLICABLE);
        body.put("end_of_support", NOT_APPLICABLE);
        body.put("end_of_engineering", NOT_APPLICABLE);
        body.put("month_only", List.of());
        body.put("origin", "NOT_APPLICABLE");
        return body;
    }

    private static LocalDate checkedDate(Instant confirmedAt, Instant now) {
        Instant at = confirmedAt != null ? confirmedAt : now;
        return at == null ? null : at.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static Map<String, Object> body(String status, String reason, String basis, Map<String, Object> catalog, String key) {
        return body(status, null, reason, basis, catalog, key);
    }

    /** {@code code} is the stable reason word of a part that is not MATCHED (read-only; the UI shows the text beside it). */
    private static Map<String, Object> body(String status, String code, String reason, String basis, Map<String, Object> catalog, String key) {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", status);
        body.put("reason_code", MATCHED.equals(status) ? null : code);
        body.put("reason", reason);
        body.put("basis", basis);
        body.put("catalog", MATCHED.equals(status) ? catalog : null);
        body.put("key", key);
        if (NEEDS_REVIEW.equals(status) && catalog != null) body.put("review", catalog);
        return body;
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
