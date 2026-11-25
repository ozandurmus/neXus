package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.BudgetRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingQuery;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Settings;

/**
 * The read side of the log detection engine, phase P1 (design sections 7.2 to 7.4 and 9): findings, the log chip of the
 * devices list, the coverage strip, the alert-budget counters and the audited acknowledgement.
 *
 * <p>Every answer says what it counts. A device with no findings is answered with its coverage ("no findings, N of M
 * recorded windows evaluated"), and a device whose windows were not all evaluated is never green. While the shadow
 * period runs ({@code shadow: true}) the findings are stored and listed here, and the clients show them on the admin
 * screen only (design section 10). Evidence is pointers: the line itself is reached through the masked chunk read route.</p>
 */
public final class LogFindingReadService {
    public static final int CHIP_WINDOW_DAYS = 7;
    public static final int BUDGET_WINDOW_DAYS = 28;
    /** Chips read the open findings page by page; the bound is reported, never silent. */
    static final int CHIP_PAGE = 1000;
    static final int CHIP_MAX_PAGES = 50;
    /** The PROVISIONAL alert budget of design section 7.2. */
    public static final int BUDGET_MEDIAN_DEVICE_WEEK = 1;
    public static final int BUDGET_P95_DEVICE_WEEK = 5;
    public static final int BUDGET_FLEET_DAY = 20;
    private static final Pattern FINDING_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final int MAX_NOTE = 500;

    /** A refusal: HTTP status, stable code, sentence. */
    public record Refusal(int status, String code, String message) {
    }

    /** A JSON-shaped answer or a refusal. */
    public record Answer(Map<String, Object> body, Refusal refusal) {
        public boolean isOk() {
            return refusal == null;
        }

        static Answer ok(Map<String, Object> body) {
            return new Answer(body, null);
        }

        static Answer refused(int status, String code, String message) {
            return new Answer(null, new Refusal(status, code, message));
        }
    }

    private final LogDetectionRepository repository;
    private final Clock clock;

    public LogFindingReadService(LogDetectionRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Europe/Istanbul")));
    }

    private Map<String, Object> envelope() {
        Settings s = repository.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("shadow", s.enabled() && s.shadow(clock.instant()));
        m.put("shadow_until", iso(s.shadowUntil()));
        m.put("phase", "P1");
        m.put("layers", List.of("A"));
        m.put("time_basis", "DEVICE_LOCAL");
        return m;
    }

    public Answer findings(String deviceId, String severity, String state, int days, int limit) {
        if (deviceId != null && !DEVICE_ID.matcher(deviceId).matches()) {
            return Answer.refused(400, "DEVICE_ID_INVALID", "The device id is not a valid opaque identifier.");
        }
        if (severity != null && !List.of("CRITICAL", "ATTENTION", "INFO", "UNKNOWN").contains(severity)) {
            return Answer.refused(400, "SEVERITY_INVALID", "Severity is CRITICAL, ATTENTION, INFO or UNKNOWN.");
        }
        if (state != null && !List.of("OPEN", "ACKNOWLEDGED", "SUPPRESSED", "CLOSED").contains(state)) {
            return Answer.refused(400, "REVIEW_STATE_INVALID", "Review state is OPEN, ACKNOWLEDGED, SUPPRESSED or CLOSED.");
        }
        int boundedDays = Math.max(1, Math.min(days, 180));
        int bounded = Math.max(1, Math.min(limit, 500));
        List<FindingRow> rows = repository.findings(new FindingQuery(deviceId, severity, state, today().minusDays(boundedDays), bounded));
        Map<String, Object> body = envelope();
        body.put("device_id", deviceId);
        body.put("days", boundedDays);
        body.put("truncated", rows.size() >= bounded);
        List<Map<String, Object>> list = new ArrayList<>();
        for (FindingRow r : rows) list.add(finding(r));
        body.put("findings", list);
        return Answer.ok(body);
    }

    public Answer finding(String findingId) {
        if (findingId == null || !FINDING_ID.matcher(findingId).matches()) {
            return Answer.refused(400, "FINDING_ID_INVALID", "The finding id is not a valid opaque identifier.");
        }
        Optional<FindingRow> row = repository.finding(findingId);
        if (row.isEmpty()) return Answer.refused(404, "FINDING_NOT_FOUND", "No such finding.");
        Map<String, Object> body = envelope();
        body.put("finding", finding(row.get()));
        return Answer.ok(body);
    }

    /** One chip per device that has a finding in the window: the worst open finding, or only its coverage when none. */
    public Answer chips() {
        LocalDate since = today().minusDays(CHIP_WINDOW_DAYS);
        List<FindingRow> rows = new ArrayList<>();
        boolean truncated = false;
        for (int page = 0; ; page++) {
            if (page == CHIP_MAX_PAGES) {
                truncated = true; // more open findings than the bound reads: say so instead of showing partial counts as whole
                break;
            }
            List<FindingRow> batch = repository.findings(new FindingQuery(null, null, "OPEN", since, CHIP_PAGE, page * CHIP_PAGE));
            rows.addAll(batch);
            if (batch.size() < CHIP_PAGE) break;
        }
        Map<String, List<FindingRow>> byDevice = new TreeMap<>();
        for (FindingRow r : rows) byDevice.computeIfAbsent(r.deviceId(), k -> new ArrayList<>()).add(r);
        Map<String, List<CoverageRow>> coverageByDevice = new TreeMap<>();
        for (CoverageRow c : repository.coverage(null, since)) coverageByDevice.computeIfAbsent(c.deviceId(), k -> new ArrayList<>()).add(c);
        List<Map<String, Object>> chips = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (var e : byDevice.entrySet()) {
            seen.put(e.getKey(), true);
            chips.add(chip(e.getKey(), e.getValue(), coverageByDevice.getOrDefault(e.getKey(), List.of())));
        }
        for (var e : coverageByDevice.entrySet()) {
            if (seen.containsKey(e.getKey())) continue;
            chips.add(chip(e.getKey(), List.of(), e.getValue()));
        }
        Map<String, Object> body = envelope();
        body.put("window_days", CHIP_WINDOW_DAYS);
        body.put("counts", "open findings of ATTENTION or higher in the last " + CHIP_WINDOW_DAYS + " days; INFO is listed, not counted");
        body.put("chips", chips);
        body.put("truncated", truncated);
        if (truncated) {
            body.put("truncated_note", "more than " + CHIP_MAX_PAGES * CHIP_PAGE + " open findings in the window; per-device counts are partial");
        }
        return Answer.ok(body);
    }

    static Map<String, Object> chip(String deviceId, List<FindingRow> open, List<CoverageRow> coverage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device_id", deviceId);
        List<FindingRow> counted = open.stream().filter(f -> LogSeverity.rank(f.severity()) >= LogSeverity.rank("ATTENTION")).toList();
        FindingRow worst = counted.stream().max((a, b) -> Integer.compare(LogSeverity.rank(a.severity()), LogSeverity.rank(b.severity())))
                .orElse(null);
        Map<String, Object> cov = coverageSummary(coverage);
        m.put("coverage", cov);
        if (worst == null) {
            // Nothing to count: say what the device's coverage allows to be said, never plain "healthy".
            boolean allEvaluated = (int) cov.get("windows_total") > 0 && (int) cov.get("windows_evaluated") == (int) cov.get("windows_total");
            m.put("state", allEvaluated ? "NO_FINDINGS" : "UNKNOWN");
            m.put("severity", null);
            m.put("count", 0);
            m.put("text", allEvaluated
                    ? "No findings; " + cov.get("windows_evaluated") + " of " + cov.get("windows_total") + " recorded windows evaluated"
                    : (int) cov.get("windows_total") == 0 ? "UNKNOWN: no windows evaluated"
                            : "No findings in the evaluated windows; " + (int) ((int) cov.get("windows_total") - (int) cov.get("windows_evaluated"))
                                    + " of " + cov.get("windows_total") + " windows UNKNOWN");
            return m;
        }
        m.put("state", "FINDINGS");
        m.put("severity", worst.severity());
        m.put("count", counted.size());
        m.put("text", worst.markerId() + (worst.keyToken().isEmpty() ? "" : " " + worst.keyToken()) + ": " + worst.eventCount()
                + " lines on " + worst.windowDay());
        return m;
    }

    public Answer coverage(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            return Answer.refused(400, "DEVICE_ID_INVALID", "The device id is not a valid opaque identifier.");
        }
        LocalDate since = today().minusDays(CHIP_WINDOW_DAYS);
        List<CoverageRow> rows = repository.coverage(deviceId, since);
        Map<String, Object> body = envelope();
        body.put("device_id", deviceId);
        body.put("window_days", CHIP_WINDOW_DAYS);
        Map<String, Object> summary = coverageSummary(rows);
        body.put("summary", summary);
        Map<String, List<CoverageRow>> bySource = new TreeMap<>();
        for (CoverageRow c : rows) bySource.computeIfAbsent(c.sourceKey(), k -> new ArrayList<>()).add(c);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (var e : bySource.entrySet()) {
            Map<String, Object> s = coverageSummary(e.getValue());
            s.put("source_key", e.getKey());
            sources.add(s);
        }
        body.put("sources", sources);
        List<Map<String, Object>> windows = new ArrayList<>();
        for (CoverageRow c : rows) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("source_key", c.sourceKey());
            w.put("job_id", c.jobId());
            w.put("window_day", c.windowDay().toString());
            w.put("state", c.state());
            w.put("reason", c.reason());
            w.put("history_days", c.historyDays());
            windows.add(w);
        }
        body.put("windows", windows);
        return Answer.ok(body);
    }

    static Map<String, Object> coverageSummary(List<CoverageRow> rows) {
        Map<String, Integer> byState = new TreeMap<>();
        int evaluated = 0;
        for (CoverageRow c : rows) {
            byState.merge(c.state(), 1, Integer::sum);
            if (LogCoverage.evaluated(c.state())) evaluated++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windows_total", rows.size());
        m.put("windows_evaluated", evaluated);
        m.put("by_state", byState);
        m.put("counts", "recorded collection windows of the last " + CHIP_WINDOW_DAYS + " days; a window without a row is not counted");
        return m;
    }

    /**
     * The alert-budget counters of design section 7.2 over the last four weeks: ATTENTION-or-higher findings per
     * device-week (median, p95; devices with coverage and no finding count as 0) and per fleet-day, against the
     * PROVISIONAL budget. The budget is a tuning target, not a filter: nothing here drops a finding.
     */
    public Answer budget() {
        // Exactly four 7-day weeks that end today: today is the last day of week 3, so no day of the window is left out.
        LocalDate since = today().minusDays(BUDGET_WINDOW_DAYS - 1L);
        List<BudgetRow> rows = repository.attentionCounts(since);
        Map<LocalDate, Integer> fleetDay = new TreeMap<>();
        Map<String, Map<Long, Integer>> deviceWeek = new TreeMap<>();
        int critical = 0;
        for (BudgetRow r : rows) {
            fleetDay.merge(r.day(), r.findings(), Integer::sum);
            long week = java.time.temporal.ChronoUnit.DAYS.between(since, r.day()) / 7;
            deviceWeek.computeIfAbsent(r.deviceId(), k -> new TreeMap<>()).merge(week, r.findings(), Integer::sum);
            if (r.severity().equals("CRITICAL")) critical += r.findings();
        }
        List<CoverageRow> coverage = repository.coverage(null, since);
        java.util.Set<String> devices = new java.util.TreeSet<>();
        for (CoverageRow c : coverage) devices.add(c.deviceId());
        List<Integer> perDeviceWeek = new ArrayList<>();
        for (String d : devices) {
            Map<Long, Integer> weeks = deviceWeek.getOrDefault(d, Map.of());
            for (long w = 0; w < BUDGET_WINDOW_DAYS / 7; w++) perDeviceWeek.add(weeks.getOrDefault(w, 0));
        }
        Collections.sort(perDeviceWeek);
        int median = percentile(perDeviceWeek, 50);
        int p95 = percentile(perDeviceWeek, 95);
        int maxFleetDay = fleetDay.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<String, Object> body = envelope();
        body.put("window_days", BUDGET_WINDOW_DAYS);
        body.put("counts", "findings of ATTENTION or higher (not suppressed) per device-week and fleet-day, over devices with coverage");
        body.put("devices_with_coverage", devices.size());
        body.put("median_device_week", median);
        body.put("p95_device_week", p95);
        body.put("max_fleet_day", maxFleetDay);
        body.put("critical_total", critical);
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("median_device_week", BUDGET_MEDIAN_DEVICE_WEEK);
        budget.put("p95_device_week", BUDGET_P95_DEVICE_WEEK);
        budget.put("fleet_day", BUDGET_FLEET_DAY);
        budget.put("status", "PROVISIONAL");
        body.put("budget", budget);
        body.put("within_budget", !devices.isEmpty() && median <= BUDGET_MEDIAN_DEVICE_WEEK && p95 <= BUDGET_P95_DEVICE_WEEK
                && maxFleetDay <= BUDGET_FLEET_DAY);
        body.put("evaluable", !devices.isEmpty());
        return Answer.ok(body);
    }

    static int percentile(List<Integer> sorted, int percent) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public Answer acknowledge(String actorFingerprint, String findingId, String note) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return Answer.refused(403, "LOG_FINDING_ACK_NOT_PERMITTED", "No authenticated session.");
        }
        if (findingId == null || !FINDING_ID.matcher(findingId).matches()) {
            return Answer.refused(400, "FINDING_ID_INVALID", "The finding id is not a valid opaque identifier.");
        }
        if (note != null && note.length() > MAX_NOTE) {
            return Answer.refused(400, "NOTE_TOO_LONG", "The note is at most " + MAX_NOTE + " characters.");
        }
        Optional<FindingRow> row = repository.acknowledge(actorFingerprint, findingId, note == null || note.isBlank() ? null : note.strip());
        if (row.isEmpty()) return Answer.refused(404, "FINDING_NOT_FOUND", "No such finding.");
        Map<String, Object> body = envelope();
        body.put("finding", finding(row.get()));
        return Answer.ok(body);
    }

    public Map<String, Object> settings() {
        Settings s = repository.settings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("shadow_until", iso(s.shadowUntil()));
        m.put("shadow", s.enabled() && s.shadow(clock.instant()));
        m.put("updated_at", iso(s.updatedAt()));
        m.put("updated_by", s.updatedBy() == null ? null : "recorded");
        return m;
    }

    /** {@code enabled} and an optional {@code shadow_days} (0 to 90): the shadow period starts now. */
    public Answer updateSettings(String actorFingerprint, Map<String, Object> body) {
        if (actorFingerprint == null || actorFingerprint.isBlank()) {
            return Answer.refused(403, "LOG_DETECTION_SETTINGS_NOT_PERMITTED", "No authenticated session.");
        }
        if (body == null || body.isEmpty() || !body.containsKey("enabled") || !(body.get("enabled") instanceof Boolean enabled)) {
            return Answer.refused(400, "LOG_DETECTION_SETTINGS_INVALID", "The body must carry enabled (true or false).");
        }
        for (String key : body.keySet()) {
            if (!key.equals("enabled") && !key.equals("shadow_days")) {
                return Answer.refused(400, "LOG_DETECTION_SETTINGS_INVALID", key + ": not a log detection setting");
            }
        }
        Instant shadowUntil = repository.settings().shadowUntil();
        if (body.containsKey("shadow_days")) {
            Object v = body.get("shadow_days");
            if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0 || n.intValue() > 90) {
                return Answer.refused(400, "LOG_DETECTION_SETTINGS_INVALID", "shadow_days: must be a whole number from 0 to 90");
            }
            shadowUntil = n.intValue() == 0 ? null : clock.instant().plus(java.time.Duration.ofDays(n.intValue()));
        }
        repository.writeSettings(actorFingerprint, enabled, shadowUntil);
        return Answer.ok(settings());
    }

    // ------------------------------------------------------------ shape

    static Map<String, Object> finding(FindingRow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("finding_id", f.findingId());
        m.put("device_id", f.deviceId());
        m.put("source_key", f.sourceKey());
        m.put("layer", "A");
        m.put("rule_id", f.ruleId());
        m.put("rule_version", f.ruleVersion());
        m.put("kind", f.kind());
        m.put("marker_id", f.markerId());
        m.put("key_token", f.keyToken());
        m.put("window_day", f.windowDay().toString());
        m.put("window_start", f.windowStart().toString());
        m.put("window_end", f.windowEnd().toString());
        m.put("time_basis", "DEVICE_LOCAL");
        m.put("severity", f.severity());
        m.put("severity_rule", f.severityRule());
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("s", f.severityWeight());
        score.put("r", null);
        score.put("c", null);
        score.put("score", null);
        score.put("note", "P1 has layer A only: rarity (R) and change (C) arrive with layer B");
        m.put("score", score);
        m.put("provenance", f.provenance());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("lines", f.eventCount());
        counts.put("peak_bucket_lines", f.peakBucketCount());
        counts.put("history_days", f.historyDays());
        counts.put("baseline_days", f.baselineDays());
        counts.put("presence_days", f.presenceDays());
        counts.put("cohort_devices", f.cohortDevices());
        counts.put("prevalence_devices", f.prevalenceDevices());
        counts.put("counts", "lines of the marker on the device-local day; baseline and presence count usable days of the last 28");
        m.put("counts", counts);
        m.put("chronic", f.chronic());
        m.put("fleet_background", f.fleetBackground());
        m.put("labels", f.labels());
        m.put("incident_group", f.incidentGroup());
        m.put("coverage_state", f.coverageState());
        List<Map<String, Object>> pointers = new ArrayList<>();
        for (Pointer p : f.pointers()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("chunk_id", p.chunkId());
            pm.put("offset", p.offset());
            pm.put("length", p.length());
            pointers.add(pm);
        }
        m.put("evidence_pointers", pointers);
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("masking_rule_set", f.maskingRuleSet());
        versions.put("marker_list", f.markerListVersion());
        versions.put("config", f.configVersion());
        versions.put("catalog", null);
        m.put("versions", versions);
        m.put("review_state", f.reviewState());
        m.put("review_at", iso(f.reviewAt()));
        m.put("first_raised_at", iso(f.firstRaisedAt()));
        m.put("updated_at", iso(f.updatedAt()));
        return m;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
