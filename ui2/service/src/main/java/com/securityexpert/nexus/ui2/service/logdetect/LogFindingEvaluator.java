package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Baseline;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.DayEvidence;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/**
 * Layer A findings of one device-day (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 3.3, 3.4 and 7): evidence rows
 * become findings under the noise rules, with severity by rule and the coverage state of the window.
 *
 * <ul>
 *   <li><b>One finding</b> per (device, marker, key token, device-local day); its counts accumulate while the day's
 *       evidence grows (dedup, rule 1). {@code kern.boot} anchors are evidence only, never a finding.</li>
 *   <li><b>Chronic</b> (rule 3): present on at least 80 % of at least 7 usable baseline days and not a rate change.
 *       <b>Fleet background</b> (rule 4): on at least half of a cohort of at least {@value LogSeverity#MIN_COHORT}
 *       devices. The hardware exemption of rule 5 is {@link HardwareMarkers.Marker#exemptFromChronic()}.</li>
 *   <li><b>History</b> under 7 days is labelled {@code HISTORY_UNDER_7_DAYS} and the finding is still shown (PO decision
 *       2026-11-24, question 1); it is never a model question.</li>
 *   <li><b>Incident grouping</b> (rule 2): findings of one device whose windows are within 10 minutes share a group.</li>
 *   <li><b>Acknowledgement</b> (section 7.3): an acknowledged finding returns to OPEN when its severity rises; the
 *       return is recorded as a {@code REOPEN} row of {@code log_finding_action} (actor {@link #SYSTEM_ACTOR}). The
 *       "evidence more than 24 hours later" clause is carried by the finding key itself: a finding is one per device,
 *       marker, key token and device-local day, so evidence of another day is a new finding that starts OPEN, and
 *       evidence of the same day cannot lie more than 24 hours after earlier evidence of that day. There is no separate
 *       time comparison, because it could never be true. ("Score doubles" waits for the score of layer B.)</li>
 * </ul>
 */
public final class LogFindingEvaluator {
    public static final String RULE_ID = "layer-a-severity";
    public static final String RULE_VERSION = "p1-1";
    public static final String CONFIG_VERSION = "p1-config-1";
    static final Duration INCIDENT_GAP = Duration.ofMinutes(10);
    /** The actor of an engine-made review change (a {@code REOPEN} action); not an operator. */
    public static final String SYSTEM_ACTOR = "system:log-detection";

    private final LogDetectionRepository repository;

    public LogFindingEvaluator(LogDetectionRepository repository) {
        this.repository = repository;
    }

    /** Evaluates one device-day and stores its findings; returns how many findings the day has. */
    public int evaluate(String deviceId, LocalDate day) {
        List<DayEvidence> evidence = repository.dayEvidence(deviceId, day);
        List<CoverageRow> coverage = repository.coverage(deviceId, day).stream()
                .filter(c -> !c.windowDay().isBefore(day) && !c.windowDay().isAfter(day.plusDays(1))).toList();
        List<FindingRow> built = new ArrayList<>();
        for (DayEvidence e : evidence) {
            build(deviceId, day, e, coverage).ifPresent(built::add);
        }
        built.sort(Comparator.comparing(FindingRow::windowStart).thenComparing(FindingRow::findingId));
        List<FindingRow> grouped = groupIncidents(deviceId, day, built);
        for (FindingRow f : grouped) {
            Optional<FindingRow> existing = repository.finding(f.findingId());
            FindingRow carried = carryReview(f, existing);
            if (reopens(existing, carried)) {
                repository.reopenFinding(SYSTEM_ACTOR, carried, "severity rose from " + existing.get().severity() + " to "
                        + carried.severity());
            } else {
                repository.upsertFinding(carried);
            }
        }
        return grouped.size();
    }

    private Optional<FindingRow> build(String deviceId, LocalDate day, DayEvidence e, List<CoverageRow> coverage) {
        boolean pan = e.markerId().startsWith(LogEvidenceExtractor.PAN_MARKER_PREFIX);
        HardwareMarkers.Marker marker = HardwareMarkers.byId(e.markerId()).orElse(null);
        if (marker != null && marker.anchor()) return Optional.empty();
        if (marker == null && !pan) return Optional.empty(); // an id outside the closed lists is not a layer A finding
        double weight = e.severityWeight();
        LogProvenance provenance = marker != null ? marker.provenance() : LogProvenance.PROVISIONAL;
        boolean exemptChronic = marker != null ? marker.exemptFromChronic() : weight >= 1.0;
        boolean exemptFleet = marker != null ? marker.exemptFromFleetBackground() : weight >= 1.0;

        Baseline baseline = repository.baseline(deviceId, e.sourceKey(), e.markerId(), day);
        int history = repository.historyDays(deviceId, e.sourceKey(), day);
        int cohort = repository.cohortSize(e.sourceKey(), day);
        int prevalence = repository.prevalence(e.sourceKey(), e.markerId(), day);

        boolean chronic = false;
        if (!exemptChronic && baseline.usableDays() >= LogSeverity.MIN_BASELINE_DAYS) {
            double share = (double) baseline.presenceDays() / baseline.usableDays();
            double median = LogSeverity.median(baseline.dayCounts());
            chronic = share >= LogSeverity.CHRONIC_PRESENCE_SHARE && !LogSeverity.rateChange(e.lineCount(), median, marker != null);
        }
        boolean fleetBackground = !exemptFleet && cohort >= LogSeverity.MIN_COHORT
                && (double) prevalence / cohort >= LogSeverity.FLEET_BACKGROUND_SHARE;

        LogSeverity.Decision decision = LogSeverity.decide(new LogSeverity.Input(weight, provenance, chronic, fleetBackground));
        List<String> labels = new ArrayList<>(decision.labels());
        if (history < LogCoverage.NOT_EVALUABLE_BELOW_DAYS) labels.add("HISTORY_UNDER_7_DAYS");
        String state = coverageState(e.sourceKey(), coverage);
        return Optional.of(new FindingRow(findingId(deviceId, e.markerId(), e.keyToken(), day), deviceId, e.sourceKey(), RULE_ID,
                RULE_VERSION, pan ? "VENDOR_SEVERITY" : "MARKER", e.markerId(), e.keyToken(), day, e.firstLineTime(),
                e.lastLineTime(), decision.severity(), decision.rule(), weight, provenance.name(), e.lineCount(),
                e.peakBucketCount(), history, baseline.usableDays(), baseline.presenceDays(), cohort, prevalence, chronic,
                fleetBackground, labels, "0000000000000000", state, e.pointers(), LogMasking.RULE_SET,
                HardwareMarkers.LIST_VERSION, CONFIG_VERSION, "OPEN", null, null, null, null));
    }

    /** The coverage of the source on the day: the first window that was not evaluated, else the evaluated state. */
    static String coverageState(String source, List<CoverageRow> rows) {
        String best = null;
        for (CoverageRow c : rows) {
            if (!c.sourceKey().equals(source)) continue;
            if (!LogCoverage.evaluated(c.state())) return c.state();
            if (best == null || c.historyDays() > 0) best = c.state();
        }
        return best == null ? LogCoverage.NOT_COLLECTED : best;
    }

    static String findingId(String deviceId, String markerId, String keyToken, LocalDate day) {
        return LogEvidenceExtractor.sha256Hex(deviceId + "|" + markerId + "|" + keyToken + "|" + day).substring(0, 32);
    }

    private static List<FindingRow> groupIncidents(String deviceId, LocalDate day, List<FindingRow> sorted) {
        List<FindingRow> out = new ArrayList<>();
        LocalDateTime clusterStart = null;
        LocalDateTime clusterEnd = null;
        String group = null;
        for (FindingRow f : sorted) {
            if (clusterEnd == null || f.windowStart().isAfter(clusterEnd.plus(INCIDENT_GAP))) {
                clusterStart = f.windowStart();
                clusterEnd = f.windowEnd();
                group = LogEvidenceExtractor.sha256Hex(deviceId + "|" + day + "|" + clusterStart).substring(0, 16);
            } else if (f.windowEnd().isAfter(clusterEnd)) {
                clusterEnd = f.windowEnd();
            }
            out.add(withGroup(f, group));
        }
        return out;
    }

    private static FindingRow withGroup(FindingRow f, String group) {
        return new FindingRow(f.findingId(), f.deviceId(), f.sourceKey(), f.ruleId(), f.ruleVersion(), f.kind(), f.markerId(),
                f.keyToken(), f.windowDay(), f.windowStart(), f.windowEnd(), f.severity(), f.severityRule(), f.severityWeight(),
                f.provenance(), f.eventCount(), f.peakBucketCount(), f.historyDays(), f.baselineDays(), f.presenceDays(),
                f.cohortDevices(), f.prevalenceDevices(), f.chronic(), f.fleetBackground(), f.labels(), group,
                f.coverageState(), f.pointers(), f.maskingRuleSet(), f.markerListVersion(), f.configVersion(), f.reviewState(),
                f.reviewBy(), f.reviewAt(), f.firstRaisedAt(), f.updatedAt());
    }

    /** Whether {@code carried} is an existing, reviewed finding that went back to OPEN (it needs a {@code REOPEN} record). */
    static boolean reopens(Optional<FindingRow> existing, FindingRow carried) {
        return existing.isPresent() && !"OPEN".equals(existing.get().reviewState()) && "OPEN".equals(carried.reviewState());
    }

    /** Keeps an existing finding's review, except where design section 7.3 raises it again (a severity rise). */
    static FindingRow carryReview(FindingRow fresh, Optional<FindingRow> existing) {
        if (existing.isEmpty()) return fresh;
        FindingRow old = existing.get();
        boolean reopen = LogSeverity.rank(fresh.severity()) > LogSeverity.rank(old.severity());
        if (!"OPEN".equals(old.reviewState()) && reopen) return fresh;
        return new FindingRow(fresh.findingId(), fresh.deviceId(), fresh.sourceKey(), fresh.ruleId(), fresh.ruleVersion(),
                fresh.kind(), fresh.markerId(), fresh.keyToken(), fresh.windowDay(), fresh.windowStart(), fresh.windowEnd(),
                fresh.severity(), fresh.severityRule(), fresh.severityWeight(), fresh.provenance(), fresh.eventCount(),
                fresh.peakBucketCount(), fresh.historyDays(), fresh.baselineDays(), fresh.presenceDays(), fresh.cohortDevices(),
                fresh.prevalenceDevices(), fresh.chronic(), fresh.fleetBackground(), fresh.labels(), fresh.incidentGroup(),
                fresh.coverageState(), fresh.pointers(), fresh.maskingRuleSet(), fresh.markerListVersion(),
                fresh.configVersion(), old.reviewState(), old.reviewBy(), old.reviewAt(), old.firstRaisedAt(), old.updatedAt());
    }
}
