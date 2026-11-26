package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.Pointer;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.CatalogRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.CountRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.DeviceStatRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.ProvisionalTemplate;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateIndexState;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateRow;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanResult;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanStatus;

/**
 * The log-analysis job of phase P2, layer B (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 2, 4.1 step 5, 4.3, 4.4
 * and 8.2), beside the P1 job and behind its own switch: a pass does <b>nothing</b> unless both the P1 switch and the P2
 * switch are on ({@link LogDetectionRepository.Settings#p2Runs()}), and nothing without a FROZEN catalog version.
 *
 * <p>One pass: each pending chunk of an analysed source is read through {@link SystemLogReadService#scanMasked} (decrypted
 * in memory, masked by {@code LogMasking} before this class sees a line), normalised ({@link LogTemplateNormaliser}),
 * matched against the frozen catalog ({@link LogTemplateMatcher}; no match gives a provisional id) and counted per
 * template and device-local hour with up to three pointers. Then every device with counts on a touched (source, day) is
 * evaluated ({@link LayerBEvaluator}) and its layer-B findings stored, tagged {@code P2}. A masking refusal records
 * {@code MASKING_UNAVAILABLE} and processes nothing of the chunk; it is never read unmasked.</p>
 *
 * <p>Isolation like P1: a chunk that fails is recorded {@code UNREADABLE} and the pass goes on; a device-day whose
 * evaluation fails is logged by exception class only and left for the next pass that touches it. Single flight.</p>
 */
public final class LogDetectionP2Service {
    private static final System.Logger LOG = System.getLogger(LogDetectionP2Service.class.getName());
    private static final Pattern PAN_SEVERITY = Pattern.compile("\"severity\"\\s*:\\s*\"([A-Za-z]{3,16})\"");
    static final int MAX_COUNT_POINTERS = 3;
    static final int BASELINE_WINDOW_DAYS = 28;
    static final int HISTORY_LOOKBACK_DAYS = 365;

    /** What one pass did (counts only). */
    public record PassSummary(boolean ran, String reason, String catalogVersion, int chunksIndexed, int chunksRefused,
            int deviceDays, int findings) {
        static PassSummary skipped(String reason) {
            return new PassSummary(false, reason, null, 0, 0, 0, 0);
        }
    }

    private final LogDetectionRepository findings;
    private final LogTemplateRepository repository;
    private final SystemLogReadService reader;
    private final HardwareMarkers markers;
    private final LogTemplateNormaliser normaliser;
    private final LogTemplateIds ids;
    private final Function<String, String> keyedToken;
    private final Supplier<Long> nanoClock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<LogTemplateMatcher> matcher = new AtomicReference<>();

    public LogDetectionP2Service(LogDetectionRepository findings, LogTemplateRepository repository, SystemLogReadService reader,
            HardwareMarkers markers, LogTemplateNormaliser normaliser, LogTemplateIds ids, Function<String, String> keyedToken) {
        this(findings, repository, reader, markers, normaliser, ids, keyedToken, System::nanoTime);
    }

    LogDetectionP2Service(LogDetectionRepository findings, LogTemplateRepository repository, SystemLogReadService reader,
            HardwareMarkers markers, LogTemplateNormaliser normaliser, LogTemplateIds ids, Function<String, String> keyedToken,
            Supplier<Long> nanoClock) {
        this.findings = Objects.requireNonNull(findings, "findings");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.markers = Objects.requireNonNull(markers, "markers");
        this.normaliser = Objects.requireNonNull(normaliser, "normaliser");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.keyedToken = Objects.requireNonNull(keyedToken, "keyedToken");
        this.nanoClock = nanoClock;
    }

    public PassSummary runOnce(int maxChunks, Duration budget) {
        if (!findings.settings().p2Runs()) return PassSummary.skipped("DISABLED");
        if (!running.compareAndSet(false, true)) return PassSummary.skipped("ALREADY_RUNNING");
        try {
            Optional<CatalogRow> active = repository.activeCatalog();
            if (active.isEmpty()) return PassSummary.skipped("NO_FROZEN_CATALOG");
            return pass(matcherFor(active.get().catalogVersion()), maxChunks, budget);
        } finally {
            running.set(false);
        }
    }

    private LogTemplateMatcher matcherFor(String version) {
        LogTemplateMatcher current = matcher.get();
        if (current != null && current.catalogVersion().equals(version)) return current;
        LogTemplateMatcher built = new LogTemplateMatcher(version, repository.templates(version), LogCatalogBuilder.Params.defaults().miner());
        matcher.set(built);
        return built;
    }

    private PassSummary pass(LogTemplateMatcher m, int maxChunks, Duration budget) {
        long deadline = nanoClock.get() + budget.toNanos();
        List<PendingChunk> pending = repository.pendingChunks(m.catalogVersion(), LogMasking.RULE_SET, LogTemplateNormaliser.VERSION,
                LogEvidenceExtractor.ANALYSED_SOURCES, maxChunks);
        Set<String> touched = new TreeSet<>(); // source|day
        int indexed = 0;
        int refused = 0;
        for (PendingChunk chunk : pending) {
            if (nanoClock.get() > deadline) break;
            Set<LocalDate> days = new TreeSet<>();
            String status = indexIsolated(chunk, m, days);
            if ("INDEXED".equals(status)) indexed++; else refused++;
            for (LocalDate d : days) touched.add(chunk.sourceKey() + "|" + d);
        }
        int deviceDays = 0;
        int raised = 0;
        for (String key : touched) {
            int bar = key.indexOf('|');
            String source = key.substring(0, bar);
            LocalDate day = LocalDate.parse(key.substring(bar + 1));
            List<String> devices;
            try {
                devices = repository.devicesWithCounts(m.catalogVersion(), source, day);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "log detection P2: device list failed cause={0}", e.getClass().getSimpleName());
                continue;
            }
            for (String device : devices) {
                try {
                    raised += evaluate(m, device, source, day, false).size();
                    deviceDays++;
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "log detection P2: device-day evaluation failed cause={0}",
                            e.getClass().getSimpleName());
                }
            }
        }
        return new PassSummary(true, "OK", m.catalogVersion(), indexed, refused, deviceDays, raised);
    }

    // ------------------------------------------------------------ indexing

    private static final class Acc {
        int count;
        LocalDateTime first;
        LocalDateTime last;
        double weight;
        String markerId;
        boolean provisional;
        final List<Pointer> pointers = new ArrayList<>(MAX_COUNT_POINTERS);
    }

    private String indexIsolated(PendingChunk chunk, LogTemplateMatcher m, Set<LocalDate> days) {
        try {
            return indexChunk(chunk, m, days);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "log detection P2: chunk failed, recorded UNREADABLE cause={0}",
                    e.getClass().getSimpleName());
            days.clear();
            try {
                repository.recordIndexState(state(chunk, m, "UNREADABLE", 0, 0, 0, 0));
            } catch (RuntimeException second) {
                LOG.log(System.Logger.Level.WARNING, "log detection P2: UNREADABLE state not stored cause={0}",
                        second.getClass().getSimpleName());
            }
            return "UNREADABLE";
        }
    }

    private String indexChunk(PendingChunk chunk, LogTemplateMatcher m, Set<LocalDate> days) {
        String source = chunk.sourceKey();
        if (!LogEvidenceExtractor.ANALYSED_SOURCES.contains(source)) {
            repository.recordIndexState(state(chunk, m, "UNSUPPORTED", 0, 0, 0, 0));
            return "UNSUPPORTED";
        }
        boolean pan = LogEvidenceExtractor.SOURCE_PAN_SYSTEM.equals(source);
        Map<String, Acc> accs = new LinkedHashMap<>();
        Map<String, ProvisionalTemplate> provisional = new TreeMap<>();
        long[] counters = new long[3]; // matched, provisional, unparsed
        ScanResult result = reader.scanMasked(chunk.chunkId(), (offset, length, masked) -> {
            LocalDateTime time = pan ? LogLineTimes.pan(masked) : LogLineTimes.gaia(masked);
            List<String> tokens = time == null ? List.of() : normaliser.normalise(source, masked);
            if (tokens.isEmpty()) {
                counters[2]++;
                return;
            }
            double lineWeight = 0;
            String lineMarker = null;
            if (pan) {
                Matcher sev = PAN_SEVERITY.matcher(masked);
                OptionalDouble w = sev.find() ? LogSeverity.panWeight(sev.group(1)) : OptionalDouble.empty();
                if (w.isPresent()) lineWeight = w.getAsDouble();
            } else {
                for (HardwareMarkers.Hit hit : markers.match(masked)) {
                    if (lineMarker == null || hit.marker().weight() > lineWeight) {
                        lineMarker = hit.marker().id();
                        lineWeight = hit.marker().weight();
                    }
                }
            }
            String matchForm = normaliser.matchForm(tokens, keyedToken);
            Optional<TemplateRow> t = m.match(source, matchForm);
            String id;
            double weight;
            String marker;
            boolean prov;
            if (t.isPresent()) {
                counters[0]++;
                id = t.get().templateId();
                weight = Math.max(t.get().severityWeight(), lineWeight);
                marker = t.get().markerId() != null ? t.get().markerId() : lineMarker;
                prov = false;
            } else {
                counters[1]++;
                id = ids.provisionalId(source, tokens);
                weight = lineWeight > 0 ? lineWeight : LogSeverity.DEFAULT_WEIGHT;
                marker = lineMarker;
                prov = true;
                provisional.putIfAbsent(id, new ProvisionalTemplate(id, source, normaliser.displayForm(tokens), time.toLocalDate()));
            }
            LocalDateTime bucket = time.truncatedTo(ChronoUnit.HOURS);
            Acc a = accs.computeIfAbsent(id + "|" + bucket, k -> new Acc());
            a.count++;
            a.provisional = prov;
            a.weight = Math.max(a.weight, Math.max(0.05, weight));
            if (a.markerId == null) a.markerId = marker;
            if (a.first == null || time.isBefore(a.first)) a.first = time;
            if (a.last == null || time.isAfter(a.last)) a.last = time;
            if (a.pointers.size() < MAX_COUNT_POINTERS) a.pointers.add(new Pointer(chunk.chunkId(), offset, length));
        });
        if (result.status() != ScanStatus.OK) {
            repository.recordIndexState(state(chunk, m, LogDetectionService.indexStatus(result.status()), result.lines(), 0, 0, 0));
            return LogDetectionService.indexStatus(result.status());
        }
        List<CountRow> rows = new ArrayList<>();
        for (var e : accs.entrySet()) {
            int bar = e.getKey().indexOf('|');
            String templateId = e.getKey().substring(0, bar);
            LocalDateTime bucket = LocalDateTime.parse(e.getKey().substring(bar + 1));
            Acc a = e.getValue();
            rows.add(new CountRow(countId(chunk.chunkId(), m.catalogVersion(), templateId, bucket), chunk.chunkId(),
                    m.catalogVersion(), chunk.deviceId(), source, templateId, a.provisional, bucket, a.weight, a.markerId,
                    a.count, a.first, a.last, a.pointers));
            days.add(bucket.toLocalDate());
        }
        repository.replaceChunkCounts(state(chunk, m, "INDEXED", result.lines(), counters[0], counters[1], counters[2]), rows,
                List.copyOf(provisional.values()));
        return "INDEXED";
    }

    private static TemplateIndexState state(PendingChunk c, LogTemplateMatcher m, String status, long lines, long matched,
            long provisional, long unparsed) {
        return new TemplateIndexState(c.chunkId(), m.catalogVersion(), LogMasking.RULE_SET, LogTemplateNormaliser.VERSION,
                c.deviceId(), c.sourceKey(), c.jobId(), c.logDay(), status, lines, matched, provisional, unparsed);
    }

    static String countId(String chunkId, String version, String templateId, LocalDateTime bucket) {
        return LogEvidenceExtractor.sha256Hex(chunkId + "|" + version + "|" + templateId + "|" + bucket).substring(0, 32);
    }

    // ------------------------------------------------------------ evaluation

    /**
     * Evaluates one device-day and stores its layer-B findings (unless {@code dryRun}); returns the findings in score
     * order. {@code dryRun} with the marker list switched off is the information run of design section 4.5.
     */
    List<FindingRow> evaluate(LogTemplateMatcher m, String deviceId, String source, LocalDate day, boolean markersOff) {
        LayerBEvaluator.DayInput input = input(m, deviceId, source, day, markersOff);
        List<FindingRow> rows = LayerBEvaluator.evaluate(input);
        if (markersOff) return rows;
        for (FindingRow f : rows) {
            Optional<FindingRow> existing = findings.finding(f.findingId());
            if (existing.isPresent() && !"OPEN".equals(existing.get().reviewState()) && reopens(existing.get(), f)) {
                repository.reopenLayerBFinding(LogFindingEvaluator.SYSTEM_ACTOR, f, reopenNote(existing.get(), f));
            } else {
                repository.upsertLayerBFinding(existing.map(f::withReviewOf).orElse(f));
            }
        }
        repository.deleteStaleLayerBFindings(deviceId, source, day, rows.stream().map(FindingRow::findingId).toList());
        return rows;
    }

    /**
     * The information run of design section 4.5 ("marker list off"): the device-day's layer-B findings under the active
     * catalog with every marker template weighed at the default S, computed and returned, never stored. Empty when no
     * catalog is frozen.
     */
    public List<FindingRow> markersOffRun(String deviceId, String source, LocalDate day) {
        Optional<CatalogRow> active = repository.activeCatalog();
        if (active.isEmpty()) return List.of();
        return evaluate(matcherFor(active.get().catalogVersion()), deviceId, source, day, true);
    }

    /** Design 7.3: an acknowledged finding is raised again when its severity rises or its score doubles. */
    static boolean reopens(FindingRow old, FindingRow fresh) {
        if (LogSeverity.rank(fresh.severity()) > LogSeverity.rank(old.severity())) return true;
        double before = old.layerB() == null ? 0 : old.layerB().score();
        return before > 0 && fresh.layerB() != null && fresh.layerB().score() >= 2 * before;
    }

    private static String reopenNote(FindingRow old, FindingRow fresh) {
        if (LogSeverity.rank(fresh.severity()) > LogSeverity.rank(old.severity())) {
            return "severity rose from " + old.severity() + " to " + fresh.severity();
        }
        return "score doubled";
    }

    LayerBEvaluator.DayInput input(LogTemplateMatcher m, String deviceId, String source, LocalDate day, boolean markersOff) {
        String version = m.catalogVersion();
        LocalDate from = day.minusDays(BASELINE_WINDOW_DAYS);
        List<CountRow> counts = repository.counts(version, deviceId, source, from, day.plusDays(1));
        // the day
        Map<String, Map<LocalDateTime, Long>> hourly = new TreeMap<>();
        Map<String, CountRow> head = new TreeMap<>();
        Map<String, double[]> weight = new HashMap<>();
        Map<String, LocalDateTime[]> times = new HashMap<>();
        Map<String, List<Pointer>> pointers = new HashMap<>();
        Map<String, LocalDate> earliest = new HashMap<>();
        LocalDateTime spanStart = null;
        LocalDateTime spanEnd = null;
        for (CountRow c : counts) {
            earliest.merge(c.templateId(), c.bucketStart().toLocalDate(), (a, b) -> a.isBefore(b) ? a : b);
            if (!c.bucketStart().toLocalDate().equals(day)) continue;
            hourly.computeIfAbsent(c.templateId(), k -> new TreeMap<>()).merge(c.bucketStart(), (long) c.lineCount(), Long::sum);
            head.putIfAbsent(c.templateId(), c);
            weight.merge(c.templateId(), new double[] {c.severityWeight()}, (a, b) -> new double[] {Math.max(a[0], b[0])});
            LocalDateTime[] t = times.computeIfAbsent(c.templateId(), k -> new LocalDateTime[] {c.firstLineTime(), c.lastLineTime()});
            if (c.firstLineTime().isBefore(t[0])) t[0] = c.firstLineTime();
            if (c.lastLineTime().isAfter(t[1])) t[1] = c.lastLineTime();
            List<Pointer> ps = pointers.computeIfAbsent(c.templateId(), k -> new ArrayList<>());
            for (Pointer p : c.pointers()) if (ps.size() < LayerBEvaluator.MAX_POINTERS) ps.add(p);
            if (spanStart == null || c.firstLineTime().isBefore(spanStart)) spanStart = c.firstLineTime();
            if (spanEnd == null || c.lastLineTime().isAfter(spanEnd)) spanEnd = c.lastLineTime();
        }
        Set<String> provisionalIds = new TreeSet<>();
        for (CountRow c : head.values()) if (c.provisional()) provisionalIds.add(c.templateId());
        Map<String, String> provisionalTexts = new HashMap<>(repository.provisionalTexts(provisionalIds));
        List<LayerBEvaluator.TemplateDay> templates = new ArrayList<>();
        for (var e : hourly.entrySet()) {
            String id = e.getKey();
            CountRow c = head.get(id);
            long lines = e.getValue().values().stream().mapToLong(Long::longValue).sum();
            int peak = (int) Math.min(Integer.MAX_VALUE, e.getValue().values().stream().mapToLong(Long::longValue).max().orElse(0));
            LocalDateTime[] t = times.get(id);
            templates.add(new LayerBEvaluator.TemplateDay(id, c.provisional(), text(m, id, provisionalTexts), weight.get(id)[0],
                    c.markerId(), lines, peak, t[0], t[1], pointers.get(id)));
        }
        // the device's history and the templates it already knew
        Optional<LogTemplateRepository.DeviceWindowRow> window = repository.deviceWindow(version, deviceId, source);
        List<DeviceStatRow> stats = repository.deviceStats(version, deviceId, source);
        Set<String> known = new HashSet<>(repository.templatesBefore(version, deviceId, source, day));
        Map<String, LocalDate> deviceFirstSeen = new HashMap<>();
        for (DeviceStatRow s : stats) {
            known.add(s.templateId());
            deviceFirstSeen.put(s.templateId(), s.firstSeen());
        }
        for (var e : earliest.entrySet()) deviceFirstSeen.putIfAbsent(e.getKey(), e.getValue());
        int history = Math.max(window.map(LogTemplateRepository.DeviceWindowRow::usableDays).orElse(0),
                repository.indexedDays(version, deviceId, source, day.minusDays(HISTORY_LOOKBACK_DAYS), day).size());
        // baseline: rolling (28 usable days before the day) when it has 7 days, else the catalog's training window
        Set<LocalDate> gaps = repository.gapDays(List.of(source), from, day).getOrDefault(deviceId + "|" + source, Set.of());
        Set<LocalDate> usable = new TreeSet<>(repository.indexedDays(version, deviceId, source, from, day));
        usable.removeAll(gaps);
        Map<String, LayerBEvaluator.Baseline> baselines = new TreeMap<>();
        int baselineDays;
        boolean fromCatalog;
        if (usable.size() >= LogSeverity.MIN_BASELINE_DAYS) {
            baselineDays = usable.size();
            fromCatalog = false;
            Map<String, Map<LocalDateTime, Long>> base = new TreeMap<>();
            Map<String, Set<LocalDate>> presence = new TreeMap<>();
            Map<String, CountRow> baseHead = new TreeMap<>();
            for (CountRow c : counts) {
                LocalDate d = c.bucketStart().toLocalDate();
                if (!usable.contains(d)) continue;
                base.computeIfAbsent(c.templateId(), k -> new TreeMap<>()).merge(c.bucketStart(), (long) c.lineCount(), Long::sum);
                presence.computeIfAbsent(c.templateId(), k -> new TreeSet<>()).add(d);
                baseHead.merge(c.templateId(), c, (a, b) -> a.severityWeight() >= b.severityWeight() ? a : b);
            }
            Set<String> baseProvisional = new TreeSet<>();
            for (CountRow c : baseHead.values()) if (c.provisional() && !provisionalTexts.containsKey(c.templateId())) baseProvisional.add(c.templateId());
            provisionalTexts.putAll(repository.provisionalTexts(baseProvisional));
            long buckets = (long) usable.size() * 24;
            for (var e : base.entrySet()) {
                long[] values = e.getValue().values().stream().mapToLong(Long::longValue).toArray();
                LogRobustStats.MedianMad mm = LogRobustStats.of(buckets, values);
                CountRow c = baseHead.get(e.getKey());
                baselines.put(e.getKey(), new LayerBEvaluator.Baseline(e.getKey(), mm.median(), mm.mad(),
                        Math.min(1.0, (double) values.length / buckets), presence.get(e.getKey()).size(),
                        text(m, e.getKey(), provisionalTexts), c.severityWeight(), c.markerId()));
            }
        } else if (window.isPresent() && window.get().usableDays() >= LogSeverity.MIN_BASELINE_DAYS) {
            baselineDays = window.get().usableDays();
            fromCatalog = true;
            for (DeviceStatRow s : stats) {
                TemplateRow t = m.template(s.templateId()).orElse(null);
                baselines.put(s.templateId(), new LayerBEvaluator.Baseline(s.templateId(), s.baselineMedian(), s.baselineMad(),
                        s.periodicity(), s.presenceDays(), t == null ? null : t.templateText(),
                        t == null ? LogSeverity.DEFAULT_WEIGHT : t.severityWeight(), t == null ? null : t.markerId()));
            }
        } else {
            baselineDays = 0;
            fromCatalog = false;
        }
        // prevalence: the rolling cohort when it is large enough, else the catalog's training window
        Set<String> relevant = new TreeSet<>(hourly.keySet());
        for (LayerBEvaluator.Baseline b : baselines.values()) if (b.periodicity() >= LayerBEvaluator.SILENCE_PERIODICITY) relevant.add(b.templateId());
        Map<String, LayerBEvaluator.Prevalence> prevalence = new TreeMap<>();
        Map<String, Integer> rollingDays = repository.indexedDaysPerDevice(version, source, day.minusDays(BASELINE_WINDOW_DAYS - 1L),
                day.plusDays(1));
        Set<String> cohort = new TreeSet<>();
        for (var e : rollingDays.entrySet()) if (e.getValue() >= LogSeverity.MIN_BASELINE_DAYS) cohort.add(e.getKey());
        boolean prevalenceFromCatalog = cohort.size() < LogSeverity.MIN_COHORT;
        if (!prevalenceFromCatalog) {
            Map<String, Set<String>> devices = repository.templateDevices(version, source, relevant,
                    day.minusDays(BASELINE_WINDOW_DAYS - 1L), day);
            for (String id : relevant) {
                Set<String> on = new TreeSet<>(devices.getOrDefault(id, Set.of()));
                on.retainAll(cohort);
                on.remove(deviceId);
                prevalence.put(id, new LayerBEvaluator.Prevalence(cohort.size(), on.size()));
            }
        } else {
            int n = m.cohort(source);
            Map<String, Integer> catalog = repository.catalogPrevalence(version, source, relevant);
            Set<String> ownCatalog = new HashSet<>();
            for (DeviceStatRow s : stats) ownCatalog.add(s.templateId());
            boolean ownInCohort = window.isPresent() && window.get().usableDays() >= LogSeverity.MIN_BASELINE_DAYS;
            for (String id : relevant) {
                int on = catalog.getOrDefault(id, 0);
                if (ownInCohort && ownCatalog.contains(id)) on = Math.max(0, on - 1);
                prevalence.put(id, new LayerBEvaluator.Prevalence(n, on));
            }
        }
        Map<String, LocalDate> fleetFirstSeen = new HashMap<>();
        for (String id : relevant) m.template(id).map(TemplateRow::fleetFirstSeen).ifPresent(d -> fleetFirstSeen.put(id, d));
        // coverage of the day (P1 coverage rows of the source)
        List<CoverageRow> coverage = findings.coverage(deviceId, day).stream()
                .filter(c -> c.sourceKey().equals(source) && c.windowDay().equals(day)).toList();
        boolean fullyEvaluated = !coverage.isEmpty() && coverage.stream()
                .allMatch(c -> LogCoverage.evaluated(c.state()) && !LogCoverage.GAP.equals(c.state()));
        String coverageState = LogFindingEvaluator.coverageState(source, coverage);
        return new LayerBEvaluator.DayInput(deviceId, source, day, version, history, templates, known, baselineDays, fromCatalog,
                baselines, prevalence, prevalenceFromCatalog, deviceFirstSeen, fleetFirstSeen, fullyEvaluated, coverageState,
                spanStart, spanEnd, markersOff);
    }

    private static String text(LogTemplateMatcher m, String templateId, Map<String, String> provisionalTexts) {
        Optional<TemplateRow> t = m.template(templateId);
        if (t.isPresent()) return t.get().templateText();
        return provisionalTexts.get(templateId);
    }
}
