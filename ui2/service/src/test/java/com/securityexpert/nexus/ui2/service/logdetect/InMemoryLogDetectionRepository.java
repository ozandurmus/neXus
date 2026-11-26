package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;

/** A synthetic in-memory {@link LogDetectionRepository}: the same contract, no database. Counters and labels only. */
class InMemoryLogDetectionRepository implements LogDetectionRepository {
    Settings settings = new Settings(true, null, null, null);
    final List<PendingChunk> pending = new ArrayList<>();
    final List<EventRow> events = new ArrayList<>();
    final Map<String, IndexState> states = new LinkedHashMap<>();
    final Map<String, CoverageRow> coverage = new LinkedHashMap<>();
    final Map<String, FindingRow> findings = new LinkedHashMap<>();
    final Map<String, RunFacts> runs = new HashMap<>();
    /** Configurable device history for chronic tests: key {@code source|marker}. */
    final Map<String, Baseline> baselines = new HashMap<>();
    int cohort;
    int prevalence;
    /** When true, cohort and prevalence are computed from the stored coverage and evidence like the SQL does. */
    boolean computeFleet;
    Integer historyOverride;

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public Settings writeSettings(String actor, boolean enabled, Instant shadowUntil) {
        settings = new Settings(enabled, shadowUntil, Instant.now(), actor, settings.p2Enabled());
        return settings;
    }

    @Override
    public Settings writeSettings(String actor, boolean enabled, Instant shadowUntil, boolean p2Enabled) {
        settings = new Settings(enabled, shadowUntil, Instant.now(), actor, p2Enabled);
        return settings;
    }

    @Override
    public List<PendingChunk> pendingChunks(String rules, String markers, int limit) {
        return pending.stream().filter(c -> !states.containsKey(c.chunkId())).limit(limit).toList();
    }

    @Override
    public void replaceChunkIndex(IndexState state, List<EventRow> rows) {
        events.removeIf(e -> e.chunkId().equals(state.chunkId()));
        events.addAll(rows);
        states.put(state.chunkId(), state);
    }

    @Override
    public void recordIndexState(IndexState state) {
        events.removeIf(e -> e.chunkId().equals(state.chunkId()));
        states.put(state.chunkId(), state);
    }

    @Override
    public List<LocalDate> daysOfJob(String deviceId, String jobId) {
        List<LocalDate> days = new ArrayList<>();
        for (EventRow e : events) {
            IndexState s = states.get(e.chunkId());
            if (s != null && s.deviceId().equals(deviceId) && s.jobId().equals(jobId) && !days.contains(e.bucketStart().toLocalDate())) {
                days.add(e.bucketStart().toLocalDate());
            }
        }
        days.sort(Comparator.naturalOrder());
        return days;
    }

    @Override
    public List<String> devicesWithEvidence(LocalDate day) {
        return events.stream().filter(e -> e.bucketStart().toLocalDate().equals(day)).map(EventRow::deviceId).distinct()
                .sorted().toList();
    }

    @Override
    public List<DayEvidence> dayEvidence(String deviceId, LocalDate day) {
        Map<String, List<EventRow>> grouped = new TreeMap<>();
        for (EventRow e : events) {
            if (e.deviceId().equals(deviceId) && e.bucketStart().toLocalDate().equals(day)) {
                grouped.computeIfAbsent(e.sourceKey() + "|" + e.markerId() + "|" + e.keyToken(), k -> new ArrayList<>()).add(e);
            }
        }
        List<DayEvidence> out = new ArrayList<>();
        for (List<EventRow> rows : grouped.values()) {
            EventRow head = rows.get(0);
            long total = 0;
            int peak = 0;
            double w = 0;
            LocalDateTime first = null;
            LocalDateTime last = null;
            List<Pointer> pointers = new ArrayList<>();
            for (EventRow r : rows) {
                total += r.lineCount();
                peak = Math.max(peak, r.lineCount());
                w = Math.max(w, r.severityWeight());
                if (first == null || r.firstLineTime().isBefore(first)) first = r.firstLineTime();
                if (last == null || r.lastLineTime().isAfter(last)) last = r.lastLineTime();
                for (Pointer p : r.pointers()) if (pointers.size() < 40) pointers.add(p);
            }
            out.add(new DayEvidence(head.sourceKey(), head.markerId(), head.keyToken(), w, total, peak, first, last, pointers));
        }
        return out;
    }

    @Override
    public Baseline baseline(String deviceId, String sourceKey, String markerId, LocalDate day) {
        return baselines.getOrDefault(sourceKey + "|" + markerId, new Baseline(0, 0, List.of()));
    }

    @Override
    public int historyDays(String deviceId, String sourceKey) {
        if (historyOverride != null) return historyOverride;
        return (int) states.values().stream().filter(s -> s.deviceId().equals(deviceId) && s.sourceKey().equals(sourceKey)
                && s.status().equals("INDEXED")).map(IndexState::logDay).distinct().count();
    }

    @Override
    public int cohortSize(String sourceKey, LocalDate day) {
        if (computeFleet) {
            return (int) coverage.values().stream().filter(c -> c.sourceKey().equals(sourceKey) && c.windowDay().equals(day)
                    && (c.state().equals("EVALUATED") || c.state().equals("BASELINE_LEARNING"))).map(CoverageRow::deviceId).distinct().count();
        }
        return cohort;
    }

    @Override
    public int prevalence(String sourceKey, String markerId, LocalDate day) {
        if (computeFleet) {
            return (int) events.stream().filter(e -> e.sourceKey().equals(sourceKey) && e.markerId().equals(markerId)
                    && e.bucketStart().toLocalDate().equals(day)).map(EventRow::deviceId).distinct().count();
        }
        return prevalence;
    }

    @Override
    public RunFacts runFacts(String deviceId, String sourceKey, String jobId) {
        RunFacts configured = runs.get(deviceId + "|" + sourceKey + "|" + jobId);
        int chunks = (int) pending.stream().filter(c -> c.deviceId().equals(deviceId) && c.sourceKey().equals(sourceKey)
                && c.jobId().equals(jobId)).count();
        return configured == null ? new RunFacts("COLLECTED", List.of(), chunks) : new RunFacts(configured.outcome(), configured.gapKinds(), chunks);
    }

    @Override
    public long[] lineCounts(String deviceId, String sourceKey, String jobId) {
        long lines = 0;
        long unparsed = 0;
        for (IndexState s : states.values()) {
            if (s.deviceId().equals(deviceId) && s.sourceKey().equals(sourceKey) && s.jobId().equals(jobId) && s.status().equals("INDEXED")) {
                lines += s.lines();
                unparsed += s.unparsed();
            }
        }
        return new long[] {lines, unparsed};
    }

    @Override
    public Map<String, Integer> indexStatuses(String deviceId, String sourceKey, String jobId) {
        Map<String, Integer> out = new TreeMap<>();
        for (IndexState s : states.values()) {
            if (s.deviceId().equals(deviceId) && s.sourceKey().equals(sourceKey) && s.jobId().equals(jobId)) out.merge(s.status(), 1, Integer::sum);
        }
        return out;
    }

    @Override
    public void upsertCoverage(CoverageRow row) {
        coverage.put(row.deviceId() + "|" + row.sourceKey() + "|" + row.jobId(), row);
    }

    @Override
    public int markEvaluationFailed(String deviceId, LocalDate day) {
        int marked = 0;
        for (var entry : coverage.entrySet()) {
            CoverageRow c = entry.getValue();
            boolean feedsDay = events.stream().anyMatch(e -> e.deviceId().equals(c.deviceId())
                    && (deviceId == null || e.deviceId().equals(deviceId)) && e.bucketStart().toLocalDate().equals(day)
                    && states.containsKey(e.chunkId()) && states.get(e.chunkId()).jobId().equals(c.jobId()));
            if (!feedsDay) continue;
            entry.setValue(new CoverageRow(c.deviceId(), c.sourceKey(), c.jobId(), c.windowDay(), LogCoverage.EVALUATION_FAILED,
                    LogCoverage.EVALUATION_FAILED, c.historyDays()));
            marked++;
        }
        return marked;
    }

    @Override
    public List<CoverageRow> failedEvaluations(int limit) {
        return coverage.values().stream().filter(c -> c.state().equals(LogCoverage.EVALUATION_FAILED)).limit(limit).toList();
    }

    @Override
    public Optional<FindingRow> finding(String findingId) {
        return Optional.ofNullable(findings.get(findingId));
    }

    /** Like the SQL: an existing row keeps its review columns; only a new row takes the review state of {@code row}. */
    @Override
    public void upsertFinding(FindingRow row) {
        FindingRow old = findings.get(row.findingId());
        findings.put(row.findingId(), old == null ? row : withReview(row, old.reviewState(), old.reviewBy(), old.reviewAt()));
    }

    static FindingRow withReview(FindingRow f, String state, String by, Instant at) {
        return new FindingRow(f.findingId(), f.deviceId(), f.sourceKey(), f.ruleId(), f.ruleVersion(), f.kind(), f.markerId(),
                f.keyToken(), f.windowDay(), f.windowStart(), f.windowEnd(), f.severity(), f.severityRule(), f.severityWeight(),
                f.provenance(), f.eventCount(), f.peakBucketCount(), f.historyDays(), f.baselineDays(), f.presenceDays(),
                f.cohortDevices(), f.prevalenceDevices(), f.chronic(), f.fleetBackground(), f.labels(), f.incidentGroup(),
                f.coverageState(), f.pointers(), f.maskingRuleSet(), f.markerListVersion(), f.configVersion(), state, by, at,
                f.firstRaisedAt(), f.updatedAt());
    }

    /** The reopen calls: {@code actor|findingId|note}. */
    final List<String> reopens = new ArrayList<>();

    @Override
    public void reopenFinding(String actor, FindingRow row, String note) {
        reopens.add(actor + "|" + row.findingId() + "|" + note);
        findings.put(row.findingId(), withReview(row, "OPEN", null, null));
    }

    @Override
    public List<FindingRow> findings(FindingQuery q) {
        return findings.values().stream().filter(f -> q.deviceId() == null || f.deviceId().equals(q.deviceId()))
                .filter(f -> q.severity() == null || f.severity().equals(q.severity()))
                .filter(f -> q.reviewState() == null || f.reviewState().equals(q.reviewState()))
                .filter(f -> q.sinceDay() == null || !f.windowDay().isBefore(q.sinceDay()))
                .sorted(Comparator.comparingInt((FindingRow f) -> -LogSeverity.rank(f.severity())).thenComparing(FindingRow::windowStart)
                        .thenComparing(FindingRow::findingId))
                .skip(q.offset()).limit(Math.min(q.limit(), 1000)).toList();
    }

    @Override
    public List<CoverageRow> coverage(String deviceId, LocalDate sinceDay) {
        return coverage.values().stream().filter(c -> deviceId == null || c.deviceId().equals(deviceId))
                .filter(c -> !c.windowDay().isBefore(sinceDay)).toList();
    }

    @Override
    public List<BudgetRow> attentionCounts(LocalDate sinceDay) {
        Map<String, Integer> counts = new TreeMap<>();
        for (FindingRow f : findings.values()) {
            if (f.windowDay().isBefore(sinceDay) || LogSeverity.rank(f.severity()) < 2) continue;
            counts.merge(f.deviceId() + "|" + f.windowDay() + "|" + f.severity(), 1, Integer::sum);
        }
        List<BudgetRow> out = new ArrayList<>();
        counts.forEach((k, v) -> {
            String[] p = k.split("\\|");
            out.add(new BudgetRow(p[0], LocalDate.parse(p[1]), p[2], v));
        });
        return out;
    }

    @Override
    public Optional<FindingRow> acknowledge(String actor, String findingId, String note) {
        FindingRow f = findings.get(findingId);
        if (f == null) return Optional.empty();
        FindingRow acked = withReview(f, "ACKNOWLEDGED", actor, Instant.now());
        findings.put(findingId, acked);
        return Optional.of(acked);
    }

    @Override
    public int devicesWithCoverage(LocalDate day) {
        return (int) coverage.values().stream().filter(c -> c.windowDay().equals(day)).map(CoverageRow::deviceId).distinct().count();
    }
}
