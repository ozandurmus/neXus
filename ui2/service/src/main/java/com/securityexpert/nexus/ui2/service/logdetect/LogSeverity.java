package com.securityexpert.nexus.ui2.service.logdetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Layer A severity (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 3.1, 3.4 and 7.1): the weight S of a vendor severity
 * value, and the deterministic rule that turns evidence into a finding severity.
 *
 * <p>Statistics alone never produce CRITICAL. A CRITICAL finding needs an S = 1.0 value whose provenance is
 * {@code MEASURED} or {@code VENDOR_DOC}; a {@code PROVISIONAL} S = 1.0 value is capped to ATTENTION and carries the label
 * {@code PROVISIONAL_CAP} so the cap is visible. An unknown severity is not a low severity: a template with no severity
 * source weighs {@link #DEFAULT_WEIGHT}.</p>
 */
public final class LogSeverity {
    public static final String FINDING_CRITICAL = "CRITICAL";
    public static final String FINDING_ATTENTION = "ATTENTION";
    public static final String FINDING_INFO = "INFO";
    public static final String FINDING_UNKNOWN = "UNKNOWN";

    /** Weight of a line with no severity source (design section 3.1). */
    public static final double DEFAULT_WEIGHT = 0.3;
    /** Evidence rows are written for lines at or above this weight and for every marker line (design section 3.3). */
    public static final double EVIDENCE_FLOOR = 0.5;
    /** Share of usable baseline days at which a marker is chronic on a device (design section 3.4 rule 3). */
    public static final double CHRONIC_PRESENCE_SHARE = 0.8;
    /** Share of the cohort at which a marker is fleet background (design section 3.4 rule 4). */
    public static final double FLEET_BACKGROUND_SHARE = 0.5;
    /** A cohort smaller than this gives no prevalence judgement: the share is not meaningful (PROVISIONAL). */
    public static final int MIN_COHORT = 10;
    /** Usable baseline days needed before a device can be judged chronic (the ratified 7-day rule). */
    public static final int MIN_BASELINE_DAYS = 7;
    /** A rate change: the day count reaches this multiple of the baseline median (design section 4.3, PROVISIONAL). */
    public static final double RATE_MULTIPLE = 5.0;
    /** Minimum day count for a rate change of a marker (design section 4.3: 3 for markers, 5 otherwise). */
    public static final int RATE_MIN_MARKER = 3;
    public static final int RATE_MIN_OTHER = 5;

    private LogSeverity() {
    }

    /** PAN vendor severity values to S (design section 3.1). {@code low} is vendor-documented in general but never observed. */
    public static OptionalDouble panWeight(String level) {
        if (level == null) return OptionalDouble.empty();
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "critical" -> OptionalDouble.of(1.0);
            case "high" -> OptionalDouble.of(0.8);
            case "medium" -> OptionalDouble.of(0.5);
            case "low" -> OptionalDouble.of(0.3);
            case "informational" -> OptionalDouble.of(0.15);
            default -> OptionalDouble.empty();
        };
    }

    /** Syslog priority words to S (design section 3.1); used only where a stored line carries one. */
    public static OptionalDouble syslogWeight(String priority) {
        if (priority == null) return OptionalDouble.empty();
        return switch (priority.toLowerCase(Locale.ROOT)) {
            case "emerg", "alert", "crit" -> OptionalDouble.of(1.0);
            case "err", "error" -> OptionalDouble.of(0.5);
            case "warning", "warn" -> OptionalDouble.of(0.3);
            case "notice", "info" -> OptionalDouble.of(0.15);
            case "debug" -> OptionalDouble.of(0.05);
            default -> OptionalDouble.empty();
        };
    }

    public static int rank(String severity) {
        return switch (severity) {
            case FINDING_CRITICAL -> 3;
            case FINDING_ATTENTION -> 2;
            case FINDING_UNKNOWN -> 1;
            default -> 0;
        };
    }

    /** The inputs of one severity decision. */
    public record Input(double weight, LogProvenance provenance, boolean chronic, boolean fleetBackground) {
    }

    /** The severity of a finding, the rule that set it and the labels that explain it. */
    public record Decision(String severity, String rule, List<String> labels) {
        public Decision {
            labels = List.copyOf(labels);
        }
    }

    /**
     * The deterministic severity rule of a layer A finding. {@code chronic} and {@code fleetBackground} must already
     * respect the hardware exemption (the caller passes false for an exempt marker).
     */
    public static Decision decide(Input in) {
        List<String> labels = new ArrayList<>();
        if (in.chronic()) {
            labels.add("CHRONIC");
            return new Decision(FINDING_INFO, "CHRONIC_KNOWN_STATE", labels);
        }
        if (in.fleetBackground()) {
            labels.add("FLEET_BACKGROUND");
            return new Decision(FINDING_INFO, "FLEET_BACKGROUND", labels);
        }
        if (in.weight() >= 1.0) {
            if (in.provenance().supportsCritical()) return new Decision(FINDING_CRITICAL, "WEIGHT_1_CITED_PROVENANCE", labels);
            labels.add("PROVISIONAL_CAP");
            return new Decision(FINDING_ATTENTION, "WEIGHT_1_PROVISIONAL_CAPPED", labels);
        }
        if (in.weight() >= EVIDENCE_FLOOR) return new Decision(FINDING_ATTENTION, "WEIGHT_AT_LEAST_0_5", labels);
        return new Decision(FINDING_INFO, "WEIGHT_BELOW_ATTENTION_FLOOR", labels);
    }

    /** True when a day count is a rate change against the baseline median (the chronic state is then left). */
    public static boolean rateChange(long dayCount, double baselineMedian, boolean marker) {
        int floor = marker ? RATE_MIN_MARKER : RATE_MIN_OTHER;
        return dayCount >= Math.max(floor, RATE_MULTIPLE * Math.max(baselineMedian, 1.0));
    }

    /** Median of the per-day counts (zero days included); 0 for an empty list. */
    public static double median(List<Long> counts) {
        if (counts.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(counts);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
