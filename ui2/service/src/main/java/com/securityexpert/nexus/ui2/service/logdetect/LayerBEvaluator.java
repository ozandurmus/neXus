package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.LayerB;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/**
 * Layer-B findings of one device, source and device-local day (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 4.3,
 * 4.4, 7.1 and 7.4). A pure function of its {@link DayInput}: the same input always gives the same findings in the same
 * order (section 6.3). The repository glue lives in {@link LogDetectionP2Service}.
 *
 * <p><b>Kinds</b> (PROVISIONAL thresholds of 4.3): {@code NEW_ON_DEVICE} (the template was never counted on the device
 * since its learning began: not in its catalog baseline nor in an earlier count); {@code FLEET_RARE} (cohort of at
 * least {@value LogSeverity#MIN_COHORT}, the template on at most 5 % of it, and new or rate-changed here);
 * {@code RATE_SPIKE} ({@code z >= 6}, {@code x >= 5 max(m, 1)}, {@code x >= x_min} with x the peak hourly count);
 * {@code SILENCE} (a template present in at least 90 % of the device's usable baseline hours is absent over the whole
 * observed span of the day, at least 6 hours). {@code BOOT_BURST} is not evaluated in this step and is never raised.
 * One finding per (device, template, day): {@code kind} is the first that holds of FLEET_RARE, RATE_SPIKE, SILENCE,
 * NEW_ON_DEVICE; {@code kinds} lists all.</p>
 *
 * <p><b>History</b> (ratified 7-day rule): under 7 days nothing is raised (NOT_EVALUABLE); 7 to 13 days
 * ({@code BASELINE_LEARNING}) only NEW_ON_DEVICE and FLEET_RARE; rate and silence need 14 days, a usable baseline of at
 * least 7 days and a day whose every window was evaluated with no gap (section 7.4: a window with a gap gives no rate or
 * silence finding).</p>
 *
 * <p><b>Severity</b> (7.1): ATTENTION for FLEET_RARE or RATE_SPIKE with S at least 0.3; INFO otherwise (NEW_ON_DEVICE of a
 * fleet-common template, SILENCE of a template not classed as periodic health output, any S below 0.3). A statistical
 * finding is <b>never CRITICAL</b>: there is no rule that returns it, and {@link #floor} caps whatever reaches it.</p>
 */
public final class LayerBEvaluator {
    public static final String RULE_ID = "layer-b-statistics";
    public static final String RULE_VERSION = "p2-1";
    public static final String CONFIG_VERSION = "p2-config-1";
    /** The marker id of every layer-B finding of a source ({@code tpl.cp_messages}); the key token is the template id. */
    public static final String MARKER_PREFIX = "tpl.";
    static final double FLEET_RARE_SHARE = 0.05;
    static final double RATE_Z = 6.0;
    static final int RATE_MIN_LOW_WEIGHT = 20;
    static final double SILENCE_PERIODICITY = 0.9;
    static final Duration SILENCE_MIN_SPAN = Duration.ofHours(6);
    static final int LEARNING_DAYS = 14;
    static final double ATTENTION_MIN_WEIGHT = 0.3;
    static final int MAX_POINTERS = 40;
    static final Duration INCIDENT_GAP = Duration.ofMinutes(10);

    /** A template counted on the day: lines, peak hour, times, the strongest S and marker of its rows, pointers. */
    public record TemplateDay(String templateId, boolean provisional, String text, double weight, String markerId, long lines,
            int peakHour, LocalDateTime first, LocalDateTime last, List<Pointer> pointers) {
        public TemplateDay {
            pointers = List.copyOf(pointers);
        }
    }

    /** A device's baseline of one template: hourly median and MAD, periodicity, presence days, and what silence needs to name it. */
    public record Baseline(String templateId, double median, double mad, double periodicity, int presenceDays, String text,
            double weight, String markerId) {
    }

    /** Prevalence of one template: cohort N and other devices n of it. */
    public record Prevalence(int cohort, int otherDevices) {
    }

    /**
     * Everything one device-day evaluation reads. {@code baselineDays} is 0 when no baseline is usable; a template absent
     * from {@code baselines} then has median 0, MAD 0 and periodicity 0 over those days.
     */
    public record DayInput(String deviceId, String sourceKey, LocalDate day, String catalogVersion, int historyDays,
            List<TemplateDay> templates, Set<String> knownOnDevice, int baselineDays, boolean baselineFromCatalog,
            Map<String, Baseline> baselines, Map<String, Prevalence> prevalence, boolean prevalenceFromCatalog,
            Map<String, LocalDate> deviceFirstSeen, Map<String, LocalDate> fleetFirstSeen, boolean dayFullyEvaluated,
            String coverageState, LocalDateTime spanStart, LocalDateTime spanEnd, boolean markersOff) {
        public DayInput {
            templates = List.copyOf(templates);
            knownOnDevice = Set.copyOf(knownOnDevice);
            baselines = Map.copyOf(baselines);
            prevalence = Map.copyOf(prevalence);
            deviceFirstSeen = Map.copyOf(deviceFirstSeen);
            fleetFirstSeen = Map.copyOf(fleetFirstSeen);
        }
    }

    private LayerBEvaluator() {
    }

    /** The findings of the day, ordered by score (highest first), then template id. */
    public static List<FindingRow> evaluate(DayInput in) {
        if (in.historyDays() < LogCoverage.NOT_EVALUABLE_BELOW_DAYS) return List.of();
        boolean learning = in.historyDays() < LEARNING_DAYS;
        boolean rateEvaluable = !learning && in.baselineDays() >= LogSeverity.MIN_BASELINE_DAYS && in.dayFullyEvaluated();
        List<FindingRow> out = new ArrayList<>();
        Map<String, TemplateDay> present = new TreeMap<>();
        for (TemplateDay t : in.templates()) present.put(t.templateId(), t);
        for (TemplateDay t : present.values()) {
            double s = weight(t.weight(), t.markerId(), in.markersOff());
            boolean isNew = !in.knownOnDevice().contains(t.templateId());
            Baseline b = in.baselines().get(t.templateId());
            double m = b == null ? 0 : b.median();
            double mad = b == null ? 0 : b.mad();
            double z = LayerBScore.z(t.peakHour(), m, mad);
            double cRate = rateEvaluable ? LayerBScore.rateChange(z) : 0.0;
            int xMin = t.markerId() != null ? LogSeverity.RATE_MIN_MARKER : s < 0.5 ? RATE_MIN_LOW_WEIGHT : LogSeverity.RATE_MIN_OTHER;
            boolean spike = rateEvaluable && z >= RATE_Z && t.peakHour() >= LogSeverity.RATE_MULTIPLE * Math.max(m, 1.0)
                    && t.peakHour() >= xMin;
            Prevalence p = in.prevalence().getOrDefault(t.templateId(), new Prevalence(0, 0));
            boolean rare = p.cohort() >= LogSeverity.MIN_COHORT && (double) p.otherDevices() / p.cohort() <= FLEET_RARE_SHARE
                    && (isNew || spike);
            List<String> kinds = new ArrayList<>();
            if (rare) kinds.add("FLEET_RARE");
            if (spike) kinds.add("RATE_SPIKE");
            if (isNew) kinds.add("NEW_ON_DEVICE");
            if (kinds.isEmpty()) continue; // present and unchanged: no finding (its C would be the 0.1 floor)
            double r = LayerBScore.rarity(p.cohort(), p.otherDevices());
            double c = LayerBScore.change(isNew, cRate, false, false);
            out.add(row(in, t.templateId(), t.provisional(), t.text(), s, kinds, r, c,
                    rateEvaluable ? z : null, rateEvaluable ? m : null, rateEvaluable ? mad : null, t.lines(), t.peakHour(),
                    t.first(), t.last(), t.pointers(), p, b == null ? 0 : b.presenceDays(), learning));
        }
        if (rateEvaluable && in.spanStart() != null && in.spanEnd() != null
                && !Duration.between(in.spanStart(), in.spanEnd()).minus(SILENCE_MIN_SPAN).isNegative()) {
            for (Baseline b : new TreeMap<>(in.baselines()).values()) {
                if (present.containsKey(b.templateId()) || b.periodicity() < SILENCE_PERIODICITY) continue;
                double s = weight(b.weight(), b.markerId(), in.markersOff());
                Prevalence p = in.prevalence().getOrDefault(b.templateId(), new Prevalence(0, 0));
                double r = LayerBScore.rarity(p.cohort(), p.otherDevices());
                double c = LayerBScore.change(false, 0.0, false, true);
                out.add(row(in, b.templateId(), b.templateId().startsWith("p"), b.text(), s, List.of("SILENCE"), r, c,
                        LayerBScore.z(0, b.median(), b.mad()), b.median(), b.mad(), 0, 0, in.spanStart(), in.spanEnd(),
                        List.of(), p, b.presenceDays(), learning));
            }
        }
        List<FindingRow> grouped = groupIncidents(in.deviceId(), in.day(), out);
        grouped.sort(Comparator.comparingDouble((FindingRow f) -> -f.layerB().score()).thenComparing(f -> f.layerB().templateId()));
        return grouped;
    }

    /** S of a template; with the marker list switched off (the 4.5 information run) a marker template weighs 0.3. */
    static double weight(double weight, String markerId, boolean markersOff) {
        double s = markersOff && markerId != null ? LogSeverity.DEFAULT_WEIGHT : weight;
        return Math.max(0.05, Math.min(1.0, s));
    }

    /** Severity by rule (design 7.1); never CRITICAL. */
    static String[] severity(List<String> kinds, double s) {
        if (s < ATTENTION_MIN_WEIGHT) return new String[] {LogSeverity.FINDING_INFO, "B_WEIGHT_BELOW_0_3"};
        if (kinds.contains("FLEET_RARE")) return new String[] {LogSeverity.FINDING_ATTENTION, "B_FLEET_RARE_S_AT_LEAST_0_3"};
        if (kinds.contains("RATE_SPIKE")) return new String[] {LogSeverity.FINDING_ATTENTION, "B_RATE_SPIKE_S_AT_LEAST_0_3"};
        if (kinds.contains("SILENCE")) return new String[] {LogSeverity.FINDING_INFO, "B_SILENCE_NOT_HEALTH_CLASSED"};
        return new String[] {LogSeverity.FINDING_INFO, "B_NEW_ON_DEVICE_FLEET_COMMON"};
    }

    /** The severity floor of design 7.1: a statistical finding alone is never CRITICAL. */
    static String[] floor(String[] decided) {
        if (LogSeverity.FINDING_CRITICAL.equals(decided[0])) return new String[] {LogSeverity.FINDING_ATTENTION, "B_STATISTICAL_FLOOR"};
        return decided;
    }

    private static FindingRow row(DayInput in, String templateId, boolean provisional, String text, double s, List<String> kinds,
            double r, double c, Double z, Double m, Double mad, long lines, int peak, LocalDateTime start, LocalDateTime end,
            List<Pointer> pointers, Prevalence p, int presenceDays, boolean learning) {
        String[] sev = floor(severity(kinds, s));
        List<String> labels = new ArrayList<>();
        if (learning) labels.add("BASELINE_LEARNING");
        if (provisional) labels.add("PROVISIONAL_TEMPLATE");
        if (z != null && in.baselineFromCatalog()) labels.add("CATALOG_BASELINE");
        if (in.prevalenceFromCatalog()) labels.add("CATALOG_PREVALENCE");
        if (kinds.contains("RATE_SPIKE") || kinds.contains("SILENCE")) labels.add("HA_ROLE_UNKNOWN"); // pooled baseline (6.4)
        if ("B_STATISTICAL_FLOOR".equals(sev[1])) labels.add("STATISTICAL_FLOOR");
        String marker = MARKER_PREFIX + in.sourceKey();
        List<Pointer> bounded = pointers.size() > MAX_POINTERS ? pointers.subList(0, MAX_POINTERS) : pointers;
        LayerB layerB = new LayerB(templateId, text, in.catalogVersion(), LogTemplateNormaliser.VERSION, kinds, r, c,
                LayerBScore.score(s, r, c), z, m, mad, in.deviceFirstSeen().get(templateId), in.fleetFirstSeen().get(templateId));
        return new FindingRow(findingId(in.deviceId(), marker, templateId, in.day()), in.deviceId(), in.sourceKey(), RULE_ID,
                RULE_VERSION, kinds.get(0), marker, templateId, in.day(), start, end, sev[0], sev[1], s,
                LogProvenance.PROVISIONAL.name(), lines, peak, in.historyDays(), in.baselineDays(), presenceDays, p.cohort(),
                p.otherDevices(), false, false, labels, "0000000000000000", in.coverageState(), bounded, LogMasking.RULE_SET,
                HardwareMarkers.LIST_VERSION, CONFIG_VERSION, "OPEN", null, null, null, null, layerB);
    }

    static String findingId(String deviceId, String marker, String templateId, LocalDate day) {
        return LogEvidenceExtractor.sha256Hex(deviceId + "|" + marker + "|" + templateId + "|" + day).substring(0, 32);
    }

    /** Findings of the day whose windows lie within 10 minutes of each other share one incident group (design 3.4 rule 2). */
    static List<FindingRow> groupIncidents(String deviceId, LocalDate day, List<FindingRow> rows) {
        List<FindingRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(FindingRow::windowStart).thenComparing(FindingRow::findingId));
        List<FindingRow> out = new ArrayList<>();
        LocalDateTime clusterEnd = null;
        String group = null;
        for (FindingRow f : sorted) {
            if (clusterEnd == null || f.windowStart().isAfter(clusterEnd.plus(INCIDENT_GAP))) {
                clusterEnd = f.windowEnd();
                group = LogEvidenceExtractor.sha256Hex(deviceId + "|B|" + day + "|" + f.windowStart()).substring(0, 16);
            } else if (f.windowEnd().isAfter(clusterEnd)) {
                clusterEnd = f.windowEnd();
            }
            out.add(f.withIncidentGroup(group));
        }
        return out;
    }
}
