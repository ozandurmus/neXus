package com.securityexpert.nexus.ui2.service.advisorystore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredAdvisory;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredKnownExploited;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.ProductRule;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Severity;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.Verdict;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VerdictKind;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.VersionRule;

/**
 * The verdicts of every device against every advisory, computed from one snapshot of the stored data (contract section 5:
 * verdicts are not stored; they are a pure function of the tables and the stored device facts, computed when read).
 * Pure: no database, no clock, no network. The rule JSON of each advisory is parsed once, not per device.
 *
 * <p>Device states (contract sections 6 and 7, rule 4), checked in this order: {@code NOT_COVERED} (no product or no
 * comparator), {@code NOT_EVALUATED} (the product's source never loaded or its last good load is older than 30 days),
 * {@code VERSION_UNKNOWN} (version missing or not fully parsed), otherwise {@code EVALUATED}. A device whose source is not
 * usable gets no verdicts at all: "nothing matched" is never produced from a source that was not compared.</p>
 */
public final class AdvisoryEstate {

    public static final String EVALUATED = "EVALUATED";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";
    public static final String NOT_COVERED = "NOT_COVERED";
    public static final String VERSION_UNKNOWN = "VERSION_UNKNOWN";

    /** Contract rule 4: amber after 7 days since the last good load, red (and not evaluated) after 30. */
    public static final long AMBER_AFTER_DAYS = 7;
    public static final long RED_AFTER_DAYS = 30;

    /** The stored facts of one device that the verdicts need. */
    public record DeviceFacts(String deviceId, String vendorHint, String role, String model, String version, String hostname) { }

    /** What one source holds and how old its last good load is ({@code lastGoodAt} null = never loaded). */
    public record SourceState(String source, Instant lastGoodAt, Long ageDays, String ageState, int advisories,
            int needsReview, LocalDate oldest, LocalDate newest) {
        public boolean usable() { return !"NEVER".equals(ageState) && !"RED".equals(ageState); }
    }

    public record Counts(int affected, int fixed, int notAffected, int notEvaluable) { }

    /** One device's verdict on one advisory; never {@code NOT_LISTED}. */
    public record DeviceVerdict(DeviceFacts device, VerdictKind kind, String basis, String fixedText) { }

    /** The Vulnerabilities part of one device's Lifecycle row (contract section 7 and 8). */
    public record DeviceAssessment(String state, String reason, String productKey, int affected, int affectedExploited,
            int affectedCriticalHigh, int notEvaluable, int fixed, String worstSeverity, SourceState compared) { }

    /** One advisory with its exploited flag and the verdicts of the estate's devices. */
    public record AdvisoryRow(StoredAdvisory advisory, boolean exploited, LocalDate exploitedSince, Counts counts,
            List<DeviceVerdict> verdicts, boolean listed) { }

    private final Map<String, DeviceAssessment> devices;
    private final List<AdvisoryRow> advisories;

    private AdvisoryEstate(Map<String, DeviceAssessment> devices, List<AdvisoryRow> advisories) {
        this.devices = devices;
        this.advisories = advisories;
    }

    public Map<String, DeviceAssessment> devices() { return devices; }

    /** Every stored advisory in id order; {@link AdvisoryRow#listed()} marks the ones the list endpoint shows. */
    public List<AdvisoryRow> advisories() { return advisories; }

    /** The source of a product key: the part before the colon is the source key (contract sections 2 and 6). */
    public static String sourceOf(String productKey) {
        int colon = productKey.indexOf(':');
        return colon < 0 ? productKey : productKey.substring(0, colon);
    }

    /** The age state of a source from the age of its last good load; {@code null} age = never loaded. */
    public static String ageState(Long ageDays) {
        return ageDays == null ? "NEVER" : ageDays > RED_AFTER_DAYS ? "RED" : ageDays > AMBER_AFTER_DAYS ? "AMBER" : "OK";
    }

    public static Long ageDays(Instant lastGoodAt, Instant now) {
        return lastGoodAt == null ? null : Math.max(0, Duration.between(lastGoodAt, now).toDays());
    }

    private record Prepared(StoredAdvisory advisory, AdvisoryModel.ParseState state, Severity severity, boolean exploited,
            LocalDate exploitedSince, Map<String, List<ProductRule>> rulesByProduct) { }

    public static AdvisoryEstate evaluate(AdvisoryCore core, List<StoredAdvisory> stored, List<StoredKnownExploited> exploited,
            Map<String, SourceState> sources, List<DeviceFacts> deviceFacts) {
        Map<String, LocalDate> kev = new HashMap<>();
        for (StoredKnownExploited k : exploited) kev.merge(k.cveId(), k.dateAdded(), (a, b) -> a.isBefore(b) ? a : b);

        // Parse every advisory's rules once per request and index the advisories by the products they name.
        List<Prepared> prepared = new ArrayList<>(stored.size());
        Map<String, List<Prepared>> byProduct = new HashMap<>();
        for (StoredAdvisory a : stored) {
            Map<String, List<ProductRule>> rulesByProduct = new LinkedHashMap<>();
            // Contract rule 5: a stored rule the codec cannot read back is never taken for "no rule" (that would read as
            // "not listed", i.e. green); the advisory is treated as NEEDS_REVIEW for this request.
            boolean unreadable = false;
            for (var rule : a.rules()) {
                List<VersionRule> rules = core.rulesFromJson(rule.ruleJson());
                if ((rules == null || rules.isEmpty()) && rule.ruleJson() != null && rule.ruleJson().strip().length() > 2) {
                    unreadable = true;
                }
                rulesByProduct.computeIfAbsent(rule.productKey(), k -> new ArrayList<>())
                        .add(new ProductRule(rule.productKey(), rule.train(), rules == null ? List.of() : rules, rule.fixedText()));
            }
            LocalDate since = null;
            for (String cve : a.cveIds()) {
                LocalDate added = kev.get(cve);
                if (added != null && (since == null || added.isBefore(since))) since = added;
            }
            Prepared p = new Prepared(a, unreadable ? AdvisoryModel.ParseState.NEEDS_REVIEW : parseState(a.parseState()), severity(a.severity()), since != null, since, rulesByProduct);
            prepared.add(p);
            for (String product : rulesByProduct.keySet()) {
                if (sourceOf(product).equals(a.source())) byProduct.computeIfAbsent(product, k -> new ArrayList<>()).add(p);
            }
        }

        Map<String, List<DeviceVerdict>> verdictsByAdvisory = new HashMap<>();
        Map<String, DeviceAssessment> assessments = new LinkedHashMap<>();
        Set<String> estateProducts = new HashSet<>();
        var evaluator = core.evaluator();
        for (DeviceFacts device : deviceFacts) {
            Optional<String> productKey = core.productKey(device.vendorHint(), device.role(), device.model());
            Optional<com.securityexpert.nexus.ui2.service.advisory.ProductComparator> comparator =
                    productKey.flatMap(core::comparator);
            if (productKey.isEmpty() || comparator.isEmpty()) {
                assessments.put(device.deviceId(), empty(NOT_COVERED, "NO_COMPARATOR", productKey.orElse(null), null));
                continue;
            }
            String product = productKey.get();
            estateProducts.add(product);
            SourceState source = sources.get(sourceOf(product));
            if (source == null || !source.usable()) {
                String reason = source == null || "NEVER".equals(source.ageState()) ? "SOURCE_NEVER_LOADED" : "SOURCE_STALE";
                assessments.put(device.deviceId(), empty(NOT_EVALUATED, reason, product, compared(source)));
                continue;
            }
            String version = device.version();
            boolean missing = version == null || version.isBlank();
            boolean unparsed = !missing && comparator.get().canonical(version).isEmpty();
            List<Prepared> candidates = byProduct.getOrDefault(product, List.of());
            if (missing || unparsed) {
                String reason = missing ? "VERSION_MISSING" : "VERSION_UNPARSED";
                for (Prepared p : candidates) {
                    verdictsByAdvisory.computeIfAbsent(p.advisory().advisoryId(), k -> new ArrayList<>())
                            .add(new DeviceVerdict(device, VerdictKind.NOT_EVALUABLE, reason, null));
                }
                assessments.put(device.deviceId(), empty(VERSION_UNKNOWN, reason, product, compared(source)));
                continue;
            }
            int affected = 0, affectedExploited = 0, criticalHigh = 0, notEvaluable = 0, fixed = 0;
            Severity worst = null;
            for (Prepared p : candidates) {
                Verdict verdict;
                try {
                    verdict = evaluator.evaluate(product, version, p.state(), p.rulesByProduct().get(product));
                } catch (RuntimeException e) {
                    verdict = new Verdict(VerdictKind.NOT_EVALUABLE, "EVALUATOR_ERROR", null);
                }
                if (verdict == null || verdict.kind() == VerdictKind.NOT_LISTED) continue;
                verdictsByAdvisory.computeIfAbsent(p.advisory().advisoryId(), k -> new ArrayList<>())
                        .add(new DeviceVerdict(device, verdict.kind(), verdict.basis(), verdict.fixedText()));
                switch (verdict.kind()) {
                    case AFFECTED -> {
                        affected++;
                        if (p.exploited()) affectedExploited++;
                        if (p.severity() == Severity.CRITICAL || p.severity() == Severity.HIGH) criticalHigh++;
                        if (worst == null || p.severity().ordinal() < worst.ordinal()) worst = p.severity();
                    }
                    case NOT_EVALUABLE -> notEvaluable++;
                    case FIXED -> fixed++;
                    default -> { }
                }
            }
            assessments.put(device.deviceId(), new DeviceAssessment(EVALUATED, null, product, affected, affectedExploited,
                    criticalHigh, notEvaluable, fixed, worst == null ? null : worst.name(), compared(source)));
        }

        List<AdvisoryRow> rows = new ArrayList<>(prepared.size());
        for (Prepared p : prepared) {
            List<DeviceVerdict> verdicts = verdictsByAdvisory.getOrDefault(p.advisory().advisoryId(), List.of());
            int affected = 0, fixed = 0, notAffected = 0, notEvaluable = 0;
            for (DeviceVerdict v : verdicts) {
                switch (v.kind()) {
                    case AFFECTED -> affected++;
                    case FIXED -> fixed++;
                    case NOT_AFFECTED -> notAffected++;
                    case NOT_EVALUABLE -> notEvaluable++;
                    default -> { }
                }
            }
            boolean namesEstateProduct = p.rulesByProduct().keySet().stream().anyMatch(estateProducts::contains);
            boolean listed = !verdicts.isEmpty() || (p.state() == AdvisoryModel.ParseState.NEEDS_REVIEW && namesEstateProduct);
            rows.add(new AdvisoryRow(p.advisory(), p.exploited(), p.exploitedSince(),
                    new Counts(affected, fixed, notAffected, notEvaluable), verdicts, listed));
        }
        return new AdvisoryEstate(assessments, rows);
    }

    /** Exploited and affected first, then severity, then newest published, then id. */
    public static final Comparator<AdvisoryRow> LIST_ORDER = Comparator
            .comparing((AdvisoryRow r) -> !(r.exploited() && r.counts().affected() > 0))
            .thenComparingInt(r -> severity(r.advisory().severity()).ordinal())
            .thenComparing((AdvisoryRow r) -> r.advisory().publishedOn(), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(r -> r.advisory().advisoryId());

    private static DeviceAssessment empty(String state, String reason, String product, SourceState compared) {
        return new DeviceAssessment(state, reason, product, 0, 0, 0, 0, 0, null, compared);
    }

    /** The basis of the comparison, null when the source never loaded (there is nothing it was compared against). */
    private static SourceState compared(SourceState source) {
        return source == null || source.lastGoodAt() == null ? null : source;
    }

    static Severity severity(String value) {
        try { return Severity.valueOf(value); } catch (RuntimeException e) { return Severity.UNKNOWN; }
    }

    private static AdvisoryModel.ParseState parseState(String value) {
        return "PARSED".equals(value) ? AdvisoryModel.ParseState.PARSED : AdvisoryModel.ParseState.NEEDS_REVIEW;
    }
}
