package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.CoverageRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.IndexState;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanStatus;

/**
 * The log-analysis job of the detection engine, phase P1 (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 8.1 option (a):
 * executed in ui2-service, bounded, started per closed collection window). A window is closed when the job's
 * {@code system_log_run} rows exist; {@link LogDetectionRepository#pendingChunks} offers only such chunks.
 *
 * <p>One pass: index each pending chunk (masking first, evidence rows only), record the coverage of every touched
 * (device, source, job), then evaluate the touched device-days into findings. The pass stops at its chunk and time
 * bounds; what is left stays pending and its windows stay without a coverage row (UNKNOWN), never healthy. A pass does
 * nothing while the P1 switch is off. Single flight: a second call while one runs returns at once.</p>
 *
 * <p>Isolation: one chunk that fails (a read or store error) is recorded {@code UNREADABLE} and the pass goes on, so a
 * bad chunk never stalls the chunks behind it. Evaluation runs after the whole batch is indexed, over every device with
 * evidence on a touched day (see {@code pass}), and is isolated per device-day: a device-day whose evaluation fails marks
 * the coverage of its windows {@link LogCoverage#EVALUATION_FAILED} (UNKNOWN, never "no findings") and the pass goes on.
 * Its chunks are INDEXED and no longer pending, so each pass first takes up the windows left in that state: it writes
 * their coverage again and evaluates their days again.</p>
 */
public final class LogDetectionService {
    private static final System.Logger LOG = System.getLogger(LogDetectionService.class.getName());

    /** What one pass did (counts only). */
    public record PassSummary(boolean ran, String reason, int chunksIndexed, int chunksRefused, int coverageRows, int findings) {
        static PassSummary skipped(String reason) {
            return new PassSummary(false, reason, 0, 0, 0, 0);
        }
    }

    private final LogDetectionRepository repository;
    private final SystemLogReadService reader;
    private final LogEvidenceExtractor extractor;
    private final LogFindingEvaluator evaluator;
    private final Supplier<Long> nanoClock;
    private final AtomicBoolean running = new AtomicBoolean();

    public LogDetectionService(LogDetectionRepository repository, SystemLogReadService reader, HardwareMarkers markers) {
        this(repository, reader, markers, System::nanoTime);
    }

    LogDetectionService(LogDetectionRepository repository, SystemLogReadService reader, HardwareMarkers markers,
            Supplier<Long> nanoClock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.extractor = new LogEvidenceExtractor(markers);
        this.evaluator = new LogFindingEvaluator(repository);
        this.nanoClock = nanoClock;
    }

    public PassSummary runOnce(int maxChunks, Duration budget) {
        if (!repository.settings().enabled()) return PassSummary.skipped("DISABLED");
        if (!running.compareAndSet(false, true)) return PassSummary.skipped("ALREADY_RUNNING");
        try {
            return pass(maxChunks, budget);
        } finally {
            running.set(false);
        }
    }

    private PassSummary pass(int maxChunks, Duration budget) {
        long deadline = nanoClock.get() + budget.toNanos();
        List<PendingChunk> pending = repository.pendingChunks(LogMasking.RULE_SET, HardwareMarkers.LIST_VERSION, maxChunks);
        Set<String[]> touchedWindows = new LinkedHashSet<>();
        Set<String> seenWindows = new LinkedHashSet<>();
        Set<LocalDate> touchedDays = new java.util.TreeSet<>();
        Map<String, LocalDate> earliestIndexed = new LinkedHashMap<>();
        int indexed = 0;
        int refused = 0;
        for (PendingChunk chunk : pending) {
            if (nanoClock.get() > deadline) break;
            IndexState state = indexChunkIsolated(chunk);
            if ("INDEXED".equals(state.status())) indexed++; else refused++;
            if (seenWindows.add(chunk.deviceId() + "\u0000" + chunk.sourceKey() + "\u0000" + chunk.jobId())) {
                touchedWindows.add(new String[] {chunk.deviceId(), chunk.sourceKey(), chunk.jobId(), chunk.logDay().toString()});
            }
            if ("INDEXED".equals(state.status())) {
                touchedDays.addAll(repository.daysOfJob(chunk.deviceId(), chunk.jobId()));
                earliestIndexed.merge(chunk.deviceId() + "\u0000" + chunk.sourceKey(), chunk.logDay(),
                        (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        // Windows whose evaluation failed in an earlier pass: their chunks are no longer pending, so they are taken up here.
        for (CoverageRow failed : repository.failedEvaluations(Math.max(1, maxChunks))) {
            if (seenWindows.add(failed.deviceId() + "\u0000" + failed.sourceKey() + "\u0000" + failed.jobId())) {
                touchedWindows.add(new String[] {failed.deviceId(), failed.sourceKey(), failed.jobId(), failed.windowDay().toString()});
            }
            touchedDays.addAll(repository.daysOfJob(failed.deviceId(), failed.jobId()));
        }
        int coverageRows = 0;
        for (String[] w : touchedWindows) {
            if (writeCoverage(w[0], w[1], w[2], LocalDate.parse(w[3]))) coverageRows++;
        }
        // Newest-first backfill: when an older day of (device, source) finishes indexing and completes the 28-day history
        // window of an already-indexed newer day, refresh that newer window's coverage and re-evaluate the device-day.
        Set<String> deviceDaysToReevaluate = new LinkedHashSet<>();
        for (var entry : earliestIndexed.entrySet()) {
            int nul = entry.getKey().indexOf('\u0000');
            String dev = entry.getKey().substring(0, nul);
            String src = entry.getKey().substring(nul + 1);
            LocalDate earliest = entry.getValue();
            for (CoverageRow c : repository.readyCoverageAfter(dev, src, earliest)) {
                int history = repository.historyDays(dev, src, c.windowDay());
                LogCoverage.State next = LogCoverage.historyState(history);
                if (!next.state().equals(c.state()) || !next.reason().equals(c.reason()) || history != c.historyDays()) {
                    repository.upsertCoverage(new CoverageRow(dev, src, c.jobId(), c.windowDay(), next.state(), next.reason(), history));
                    coverageRows++;
                    if (LogCoverage.NOT_EVALUABLE.equals(c.state()) && !LogCoverage.NOT_EVALUABLE.equals(next.state())) {
                        touchedDays.add(c.windowDay());
                        touchedDays.add(c.windowDay().minusDays(1));
                    }
                }
            }
            for (LocalDate d : repository.readyEvidenceDaysAfter(dev, src, earliest)) {
                deviceDaysToReevaluate.add(dev + "\u0000" + d);
            }
        }
        // Findings are evaluated only after the whole batch is indexed and its coverage written, and every device that has
        // evidence on a touched day is evaluated again, not only the devices of this batch: the fleet-background input
        // (cohort and prevalence, design 3.4 rule 4) of a day changes when a later pass indexes another device, so a device
        // evaluated by an earlier pass of a backlog must not keep a verdict made on a partial cohort.
        int findings = 0;
        for (LocalDate day : touchedDays) {
            List<String> devices;
            try {
                devices = repository.devicesWithEvidence(day);
            } catch (RuntimeException e) {
                recordEvaluationFailure(null, day, e);
                continue;
            }
            for (String deviceId : devices) {
                try {
                    findings += evaluator.evaluate(deviceId, day);
                } catch (RuntimeException e) {
                    recordEvaluationFailure(deviceId, day, e);
                }
            }
        }
        for (String key : deviceDaysToReevaluate) {
            int nul = key.indexOf('\u0000');
            String dev = key.substring(0, nul);
            LocalDate day = LocalDate.parse(key.substring(nul + 1));
            if (touchedDays.contains(day)) continue;
            try {
                findings += evaluator.evaluate(dev, day);
            } catch (RuntimeException e) {
                recordEvaluationFailure(dev, day, e);
            }
        }
        return new PassSummary(true, "OK", indexed, refused, coverageRows, findings);
    }

    /**
     * A device-day whose evaluation failed must never read as evaluated: the coverage rows of the windows that feed it are
     * marked {@link LogCoverage#EVALUATION_FAILED} ({@code deviceId} null: every device with evidence that day), which the
     * reader counts as UNKNOWN and the next pass takes up again. Only the exception class is logged, never its message.
     */
    private void recordEvaluationFailure(String deviceId, LocalDate day, RuntimeException failure) {
        LOG.log(System.Logger.Level.WARNING, "log detection: device-day evaluation failed, coverage marked EVALUATION_FAILED cause={0}",
                failure.getClass().getSimpleName());
        try {
            repository.markEvaluationFailed(deviceId, day);
        } catch (RuntimeException second) {
            LOG.log(System.Logger.Level.WARNING, "log detection: EVALUATION_FAILED not stored cause={0}",
                    second.getClass().getSimpleName());
        }
    }

    /**
     * One chunk can never stall the queue: an unexpected failure while reading, extracting or storing the chunk is recorded
     * as {@code UNREADABLE} in {@code log_index_state} (which removes the chunk's evidence rows and is retried only after
     * the retry interval of {@code pendingChunks}), and the pass continues with the next chunk. Only the exception class
     * is logged, never its message (it could carry a line).
     */
    private IndexState recordFailure(PendingChunk chunk, RuntimeException failure) {
        LOG.log(System.Logger.Level.WARNING, "log detection: chunk failed, recorded UNREADABLE cause={0}",
                failure.getClass().getSimpleName());
        IndexState state = state(chunk, "UNREADABLE", 0, 0, 0);
        try {
            repository.recordIndexState(state);
        } catch (RuntimeException second) {
            LOG.log(System.Logger.Level.WARNING, "log detection: UNREADABLE state not stored cause={0}",
                    second.getClass().getSimpleName());
        }
        return state;
    }

    private IndexState indexChunkIsolated(PendingChunk chunk) {
        try {
            return indexChunk(chunk);
        } catch (RuntimeException e) {
            return recordFailure(chunk, e);
        }
    }

    private IndexState indexChunk(PendingChunk chunk) {
        String rules = LogMasking.RULE_SET;
        String markers = HardwareMarkers.LIST_VERSION;
        if (!LogEvidenceExtractor.ANALYSED_SOURCES.contains(chunk.sourceKey())) {
            IndexState state = state(chunk, "UNSUPPORTED", 0, 0, 0);
            repository.recordIndexState(state);
            return state;
        }
        LogEvidenceExtractor.Extraction x = extractor.extract(reader, chunk);
        if (x.status() == ScanStatus.OK) {
            IndexState state = state(chunk, "INDEXED", x.lines(), x.matched(), x.unparsed());
            repository.replaceChunkIndex(state, x.events());
            return state;
        }
        IndexState state = state(chunk, indexStatus(x.status()), x.lines(), 0, 0);
        repository.recordIndexState(state);
        LOG.log(System.Logger.Level.INFO, "log detection: chunk not indexed status={0} rules={1} markers={2}", state.status(), rules,
                markers);
        return state;
    }

    private static IndexState state(PendingChunk c, String status, long lines, long matched, long unparsed) {
        return new IndexState(c.chunkId(), LogMasking.RULE_SET, HardwareMarkers.LIST_VERSION, c.deviceId(), c.sourceKey(),
                c.jobId(), c.logDay(), status, lines, matched, unparsed);
    }

    static String indexStatus(ScanStatus status) {
        return switch (status) {
            case MASKING_UNAVAILABLE -> "MASKING_UNAVAILABLE";
            case PRUNED -> "PRUNED";
            case NOT_APPLICABLE -> "NOT_APPLICABLE";
            case LINE_TOO_LONG -> "LINE_TOO_LONG";
            case LINE_COUNT_MISMATCH -> "LINE_COUNT_MISMATCH";
            case STORE_UNAVAILABLE -> "STORE_UNAVAILABLE";
            default -> "UNREADABLE"; // NOT_FOUND, ARTEFACT_MISSING and a read failure are all "the bytes could not be read"
        };
    }

    /** Writes the coverage row once every chunk of the window has an index state; returns whether a row was written. */
    private boolean writeCoverage(String deviceId, String sourceKey, String jobId, LocalDate day) {
        var run = repository.runFacts(deviceId, sourceKey, jobId);
        var statuses = repository.indexStatuses(deviceId, sourceKey, jobId);
        int indexedChunks = statuses.values().stream().mapToInt(Integer::intValue).sum();
        if (run.chunks() > indexedChunks) return false; // part of the window is still pending: no verdict yet
        long[] counts = repository.lineCounts(deviceId, sourceKey, jobId);
        int history = repository.historyDays(deviceId, sourceKey, day);
        LogCoverage.State state = LogCoverage.of(LogEvidenceExtractor.ANALYSED_SOURCES.contains(sourceKey), run, statuses,
                counts[0], counts[1], history);
        repository.upsertCoverage(new CoverageRow(deviceId, sourceKey, jobId, day, state.state(), state.reason(), history));
        return true;
    }
}
