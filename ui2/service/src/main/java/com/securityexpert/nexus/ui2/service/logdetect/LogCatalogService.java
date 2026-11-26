package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.CatalogRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TrainingChunk;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanResult;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService.ScanStatus;

/**
 * Catalog versions of layer B (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 4.1 steps 3 and 4, 6.5 and 8.2): the
 * offline build of a DRAFT from the stored pool, the audited freeze with its diff, and the diff between two versions.
 *
 * <p>The build reads the training window's chunks through {@link SystemLogReadService#scanMasked} only (decrypted in
 * memory, masked by {@code LogMasking}; one masking session per chunk) and feeds {@link LogCatalogBuilder} in the
 * repository's deterministic order. If masking is refused for any chunk the build stops and stores nothing: a catalog
 * is never built from a partial or unmasked pool. Other unreadable chunks are skipped and counted in the result.
 * Building does not depend on either switch (it reads and writes catalog rows only, nothing is matched online); a
 * catalog is used online only after a {@code security_admin} freezes it and the P2 switch is on.</p>
 */
public final class LogCatalogService {
    /** Chunks one build may read (bounded; the manifest records the bound). */
    public static final int MAX_BUILD_CHUNKS = 50_000;
    /** A training window is at least the ratified 7 days and at most 92 days. */
    public static final int MIN_WINDOW_DAYS = 7;
    public static final int MAX_WINDOW_DAYS = 92;

    /** The outcome of a build: stored DRAFT version, or the reason nothing was stored. */
    public record BuildResult(boolean stored, String reason, String catalogVersion, int templates, int chunksRead,
            int chunksSkipped, String digest) {
    }

    private final LogTemplateRepository repository;
    private final SystemLogReadService reader;
    private final HardwareMarkers markers;
    private final LogTemplateNormaliser normaliser;
    private final LogTemplateIds ids;
    private final Function<String, String> keyedToken;

    public LogCatalogService(LogTemplateRepository repository, SystemLogReadService reader, HardwareMarkers markers,
            LogTemplateNormaliser normaliser, LogTemplateIds ids, Function<String, String> keyedToken) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.markers = Objects.requireNonNull(markers, "markers");
        this.normaliser = Objects.requireNonNull(normaliser, "normaliser");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.keyedToken = Objects.requireNonNull(keyedToken, "keyedToken");
    }

    /** Builds and stores a DRAFT over {@code [from, to]} (device-local days) of the analysed sources. */
    public BuildResult build(String actorFingerprint, LocalDate from, LocalDate to, LogCatalogBuilder.Params params) {
        Objects.requireNonNull(actorFingerprint, "actorFingerprint");
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (days < MIN_WINDOW_DAYS || days > MAX_WINDOW_DAYS) {
            return new BuildResult(false, "WINDOW_OUT_OF_RANGE", null, 0, 0, 0, null);
        }
        Set<String> sources = LogEvidenceExtractor.ANALYSED_SOURCES;
        List<TrainingChunk> chunks = repository.trainingChunks(sources, from, to, MAX_BUILD_CHUNKS);
        LogCatalogBuilder builder = new LogCatalogBuilder(normaliser, markers, ids, keyedToken, params, from, to,
                repository.gapDays(sources, from, to));
        int read = 0;
        int skipped = 0;
        for (TrainingChunk chunk : chunks) {
            if (builder.full()) break;
            boolean pan = LogEvidenceExtractor.SOURCE_PAN_SYSTEM.equals(chunk.sourceKey());
            ScanResult result = reader.scanMasked(chunk.chunkId(), (offset, length, masked) -> builder.accept(
                    new LogCatalogBuilder.Line(chunk.deviceId(), chunk.sourceKey(),
                            pan ? LogLineTimes.pan(masked) : LogLineTimes.gaia(masked), masked)));
            if (result.status() == ScanStatus.MASKING_UNAVAILABLE) {
                return new BuildResult(false, "MASKING_UNAVAILABLE", null, 0, read, skipped, null);
            }
            if (result.status() != ScanStatus.OK) {
                skipped++;
                continue;
            }
            builder.chunkRead();
            read++;
        }
        if (read == 0) return new BuildResult(false, "NO_CHUNKS", null, 0, 0, skipped, null);
        String version = repository.nextCatalogVersion();
        LogCatalogBuilder.Draft draft = builder.finish(version);
        repository.saveDraft(actorFingerprint, draft.catalog(), draft.templates(), draft.windows(), draft.stats());
        return new BuildResult(true, "OK", version, draft.templates().size(), read, skipped, draft.catalog().digest());
    }

    /** The refusal or the frozen row of a freeze (design 6.5: DRAFT to FROZEN, with the diff against the active version). */
    public record FreezeResult(boolean frozen, String reason, CatalogRow catalog) {
    }

    public FreezeResult freeze(String actorFingerprint, String version) {
        Optional<CatalogRow> row = repository.catalog(version);
        if (row.isEmpty()) return new FreezeResult(false, "CATALOG_NOT_FOUND", null);
        if (row.get().frozen()) return new FreezeResult(false, "ALREADY_FROZEN", row.get());
        Optional<CatalogRow> previous = repository.activeCatalog();
        String diff = LogCatalogDiff.json(previous.orElse(null),
                previous.map(p -> repository.templates(p.catalogVersion())).orElse(List.of()), row.get(),
                repository.templates(version), LogCatalogBuilder.Params.defaults().miner());
        Optional<CatalogRow> frozen = repository.freeze(actorFingerprint, version,
                previous.map(CatalogRow::catalogVersion).orElse(null), diff);
        return frozen.map(c -> new FreezeResult(true, "OK", c)).orElse(new FreezeResult(false, "NOT_A_DRAFT", null));
    }

    /** The diff between two stored versions (either state), computed now; ids and counts only. */
    public Optional<Map<String, Object>> diff(String fromVersion, String toVersion) {
        Optional<CatalogRow> from = repository.catalog(fromVersion);
        Optional<CatalogRow> to = repository.catalog(toVersion);
        if (from.isEmpty() || to.isEmpty()) return Optional.empty();
        return Optional.of(LogCatalogDiff.diff(from.get(), repository.templates(fromVersion), to.get(),
                repository.templates(toVersion), LogCatalogBuilder.Params.defaults().miner()));
    }

    /** Versions with state, digest and counts (no template text). */
    public List<Map<String, Object>> catalogs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CatalogRow c : repository.catalogs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("catalog_version", c.catalogVersion());
            m.put("state", c.state());
            m.put("previous_version", c.previousVersion());
            m.put("template_count", c.templateCount());
            m.put("digest", c.digest());
            m.put("created_at", c.createdAt() == null ? null : c.createdAt().toString());
            m.put("frozen_at", c.frozenAt() == null ? null : c.frozenAt().toString());
            out.add(m);
        }
        return out;
    }

    /** Template counts per source and class of a version, for a review summary (no template text). */
    public Map<String, Map<String, Integer>> classCounts(String version) {
        Map<String, Map<String, Integer>> out = new TreeMap<>();
        for (TemplateRow t : repository.templates(version)) {
            out.computeIfAbsent(t.sourceKey(), k -> new TreeMap<>()).merge(t.templateClass(), 1, Integer::sum);
        }
        return out;
    }
}
