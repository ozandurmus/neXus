package com.securityexpert.nexus.ui2.service.logdetect;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository;

/**
 * A synthetic in-memory {@link LogTemplateRepository}: the same contract, no database. Layer-B findings go into the
 * {@code findings} map of the given P1 double, so the read side sees both layers. It refuses what V273 refuses that a
 * test can reach: a CRITICAL layer-B finding, a stored form outside the closed character set, and a change to a FROZEN
 * catalog.
 */
class InMemoryLogTemplateRepository implements LogTemplateRepository {
    private static final Pattern STORED_FORM = Pattern.compile("[\\[\\]A-Za-z0-9 <>_.:/#()=,*+%&|!?@~$^{}-]{1,4000}");
    final InMemoryLogDetectionRepository p1;
    final Map<String, CatalogRow> catalogs = new TreeMap<>();
    final Map<String, List<TemplateRow>> templates = new HashMap<>();
    final Map<String, List<DeviceWindowRow>> windows = new HashMap<>();
    final Map<String, List<DeviceStatRow>> stats = new HashMap<>();
    final List<TrainingChunk> training = new ArrayList<>();
    final Map<String, Set<LocalDate>> gaps = new HashMap<>();
    final List<PendingChunk> pending = new ArrayList<>();
    final Map<String, TemplateIndexState> states = new LinkedHashMap<>();
    final List<CountRow> counts = new ArrayList<>();
    final Map<String, ProvisionalTemplate> provisional = new TreeMap<>();
    int reopenCount;
    /** Number of repository calls of the online path (switch-off proof). */
    int onlineCalls;

    InMemoryLogTemplateRepository(InMemoryLogDetectionRepository p1) {
        this.p1 = p1;
    }

    private static int number(String version) {
        return Integer.parseInt(version.substring(4));
    }

    @Override
    public List<TrainingChunk> trainingChunks(Collection<String> sources, LocalDate from, LocalDate to, int limit) {
        return training.stream().filter(c -> sources.contains(c.sourceKey()) && !c.logDay().isBefore(from) && !c.logDay().isAfter(to))
                .sorted(Comparator.comparing(TrainingChunk::sourceKey).thenComparing(TrainingChunk::logDay)
                        .thenComparing(TrainingChunk::deviceId).thenComparing(TrainingChunk::chunkId))
                .limit(limit).toList();
    }

    @Override
    public Map<String, Set<LocalDate>> gapDays(Collection<String> sources, LocalDate from, LocalDate to) {
        Map<String, Set<LocalDate>> out = new TreeMap<>();
        gaps.forEach((k, v) -> {
            if (!sources.contains(k.substring(k.indexOf('|') + 1))) return;
            for (LocalDate d : v) if (!d.isBefore(from) && !d.isAfter(to)) out.computeIfAbsent(k, x -> new TreeSet<>()).add(d);
        });
        return out;
    }

    @Override
    public String nextCatalogVersion() {
        return "cat-" + (catalogs.keySet().stream().mapToInt(InMemoryLogTemplateRepository::number).max().orElse(0) + 1);
    }

    @Override
    public void saveDraft(String actor, CatalogRow c, List<TemplateRow> t, List<DeviceWindowRow> w, List<DeviceStatRow> s) {
        if (catalogs.containsKey(c.catalogVersion())) throw new IllegalStateException("duplicate key log_template_catalog");
        for (TemplateRow r : t) {
            if (!STORED_FORM.matcher(r.templateText()).matches() || !STORED_FORM.matcher(r.matchForm()).matches()) {
                throw new IllegalStateException("violates check constraint log_template_template_text_check");
            }
        }
        catalogs.put(c.catalogVersion(), new CatalogRow(c.catalogVersion(), "DRAFT", null, c.manifestJson(), c.digest(),
                c.templateCount(), "{}", Instant.now(), null));
        templates.put(c.catalogVersion(), List.copyOf(t));
        windows.put(c.catalogVersion(), List.copyOf(w));
        stats.put(c.catalogVersion(), List.copyOf(s));
    }

    @Override
    public Optional<CatalogRow> catalog(String version) {
        return Optional.ofNullable(catalogs.get(version));
    }

    @Override
    public List<CatalogRow> catalogs() {
        return catalogs.values().stream().sorted(Comparator.comparingInt((CatalogRow c) -> number(c.catalogVersion())).reversed()).toList();
    }

    @Override
    public Optional<CatalogRow> activeCatalog() {
        return catalogs.values().stream().filter(CatalogRow::frozen).max(Comparator.comparingInt(c -> number(c.catalogVersion())));
    }

    @Override
    public List<TemplateRow> templates(String version) {
        return templates.getOrDefault(version, List.of());
    }

    @Override
    public Optional<CatalogRow> freeze(String actor, String version, String previous, String diff) {
        CatalogRow c = catalogs.get(version);
        if (c == null || c.frozen()) return Optional.empty();
        CatalogRow frozen = new CatalogRow(version, "FROZEN", previous, c.manifestJson(), c.digest(), c.templateCount(), diff,
                c.createdAt(), Instant.now());
        catalogs.put(version, frozen);
        return Optional.of(frozen);
    }

    @Override
    public List<DeviceStatRow> deviceStats(String version, String deviceId, String sourceKey) {
        return stats.getOrDefault(version, List.of()).stream()
                .filter(s -> s.deviceId().equals(deviceId) && s.sourceKey().equals(sourceKey)).toList();
    }

    @Override
    public Optional<DeviceWindowRow> deviceWindow(String version, String deviceId, String sourceKey) {
        return windows.getOrDefault(version, List.of()).stream()
                .filter(w -> w.deviceId().equals(deviceId) && w.sourceKey().equals(sourceKey)).findFirst();
    }

    @Override
    public Map<String, Integer> catalogPrevalence(String version, String sourceKey, Collection<String> ids) {
        Map<String, Integer> out = new TreeMap<>();
        for (TemplateRow t : templates(version)) {
            if (t.sourceKey().equals(sourceKey) && ids.contains(t.templateId())) out.put(t.templateId(), t.prevalenceDevices());
        }
        return out;
    }

    @Override
    public List<PendingChunk> pendingChunks(String version, String rules, String normaliser, Collection<String> sources, int limit) {
        onlineCalls++;
        return pending.stream().filter(c -> sources.contains(c.sourceKey()) && !states.containsKey(c.chunkId() + "|" + version))
                .limit(limit).toList();
    }

    @Override
    public void replaceChunkCounts(TemplateIndexState state, List<CountRow> rows, List<ProvisionalTemplate> prov) {
        onlineCalls++;
        counts.removeIf(c -> c.chunkId().equals(state.chunkId()) && c.catalogVersion().equals(state.catalogVersion()));
        counts.addAll(rows);
        for (ProvisionalTemplate p : prov) {
            if (!STORED_FORM.matcher(p.templateText()).matches()) throw new IllegalStateException("violates check constraint");
            provisional.merge(p.templateId(), p, (a, b) -> a.firstSeenDay().isBefore(b.firstSeenDay()) ? a : b);
        }
        states.put(state.chunkId() + "|" + state.catalogVersion(), state);
    }

    @Override
    public void recordIndexState(TemplateIndexState state) {
        onlineCalls++;
        counts.removeIf(c -> c.chunkId().equals(state.chunkId()) && c.catalogVersion().equals(state.catalogVersion()));
        states.put(state.chunkId() + "|" + state.catalogVersion(), state);
    }

    @Override
    public List<CountRow> counts(String version, String deviceId, String sourceKey, LocalDate from, LocalDate toExclusive) {
        return counts.stream().filter(c -> c.catalogVersion().equals(version) && c.deviceId().equals(deviceId)
                && c.sourceKey().equals(sourceKey) && !c.bucketStart().toLocalDate().isBefore(from)
                && c.bucketStart().toLocalDate().isBefore(toExclusive))
                .sorted(Comparator.comparing(CountRow::bucketStart).thenComparing(CountRow::templateId).thenComparing(CountRow::countId))
                .toList();
    }

    @Override
    public Set<String> templatesBefore(String version, String deviceId, String sourceKey, LocalDate day) {
        Set<String> out = new TreeSet<>();
        for (CountRow c : counts) {
            if (c.catalogVersion().equals(version) && c.deviceId().equals(deviceId) && c.sourceKey().equals(sourceKey)
                    && c.bucketStart().toLocalDate().isBefore(day)) out.add(c.templateId());
        }
        return out;
    }

    @Override
    public List<LocalDate> indexedDays(String version, String deviceId, String sourceKey, LocalDate from, LocalDate toExclusive) {
        Set<LocalDate> out = new TreeSet<>();
        for (TemplateIndexState s : states.values()) {
            if (s.catalogVersion().equals(version) && s.deviceId().equals(deviceId) && s.sourceKey().equals(sourceKey)
                    && "INDEXED".equals(s.status()) && !s.logDay().isBefore(from) && s.logDay().isBefore(toExclusive)) out.add(s.logDay());
        }
        return new ArrayList<>(out);
    }

    @Override
    public Map<String, Integer> indexedDaysPerDevice(String version, String sourceKey, LocalDate from, LocalDate toExclusive) {
        Map<String, Set<LocalDate>> days = new TreeMap<>();
        for (TemplateIndexState s : states.values()) {
            if (s.catalogVersion().equals(version) && s.sourceKey().equals(sourceKey) && "INDEXED".equals(s.status())
                    && !s.logDay().isBefore(from) && s.logDay().isBefore(toExclusive)) {
                days.computeIfAbsent(s.deviceId(), k -> new TreeSet<>()).add(s.logDay());
            }
        }
        Map<String, Integer> out = new TreeMap<>();
        days.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    @Override
    public Map<String, Set<String>> templateDevices(String version, String sourceKey, Collection<String> ids, LocalDate from,
            LocalDate toInclusive) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (CountRow c : counts) {
            LocalDate d = c.bucketStart().toLocalDate();
            if (c.catalogVersion().equals(version) && c.sourceKey().equals(sourceKey) && ids.contains(c.templateId())
                    && !d.isBefore(from) && !d.isAfter(toInclusive)) {
                out.computeIfAbsent(c.templateId(), k -> new TreeSet<>()).add(c.deviceId());
            }
        }
        return out;
    }

    @Override
    public List<String> devicesWithCounts(String version, String sourceKey, LocalDate day) {
        return counts.stream().filter(c -> c.catalogVersion().equals(version) && c.sourceKey().equals(sourceKey)
                && c.bucketStart().toLocalDate().equals(day)).map(CountRow::deviceId).distinct().sorted().toList();
    }

    @Override
    public Map<String, String> provisionalTexts(Collection<String> ids) {
        Map<String, String> out = new TreeMap<>();
        for (String id : ids) if (provisional.containsKey(id)) out.put(id, provisional.get(id).templateText());
        return out;
    }

    @Override
    public void upsertLayerBFinding(FindingRow row) {
        if ("CRITICAL".equals(row.severity())) throw new IllegalStateException("violates check constraint log_finding_layer_b_never_critical");
        FindingRow old = p1.findings.get(row.findingId());
        p1.findings.put(row.findingId(), old == null ? row : row.withReviewOf(old));
    }

    @Override
    public int deleteStaleLayerBFindings(String deviceId, String sourceKey, LocalDate day, Collection<String> keep) {
        List<String> stale = p1.findings.values().stream().filter(f -> f.layerB() != null && "OPEN".equals(f.reviewState())
                && f.deviceId().equals(deviceId) && f.sourceKey().equals(sourceKey) && f.windowDay().equals(day)
                && !keep.contains(f.findingId())).map(FindingRow::findingId).toList();
        stale.forEach(p1.findings::remove);
        return stale.size();
    }

    @Override
    public void reopenLayerBFinding(String actor, FindingRow row, String note) {
        reopenCount++;
        p1.findings.put(row.findingId(), row);
    }
}
