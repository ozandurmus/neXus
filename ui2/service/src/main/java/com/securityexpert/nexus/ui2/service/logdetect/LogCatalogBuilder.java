package com.securityexpert.nexus.ui2.service.logdetect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.CatalogRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.DeviceStatRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.DeviceWindowRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogTemplateRepository.TemplateRow;
import com.securityexpert.nexus.ui2.platform.logtemplate.TemplateMiner;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/**
 * The offline catalog build of layer B (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md sections 4.1 steps 3 and 4, 4.2 and
 * 6.5): masked lines of the training window, in a fixed order, go through {@link LogTemplateNormaliser} into one Drain
 * miner per source (the census's {@link TemplateMiner}, not a second miner); the result is a DRAFT catalog with, per
 * template, its id, vocabulary-form text, match form, class and S, fleet prevalence n of N, fleet first seen, and per
 * (device, template) the robust hourly baseline (median, MAD), presence share and periodicity over the device's usable
 * days.
 *
 * <p><b>Deterministic:</b> the same lines in the same order with the same parameters, key and vocabulary give the same
 * templates, the same ids, the same statistics and the same digest; nothing depends on the clock or on hash order.
 * <b>Bounded:</b> at most {@link Params#maxLines()} lines, {@link Params#maxClustersPerSource()} clusters per source
 * (further new shapes are counted as overflow) and {@link Params#maxDetailEntries()} (device, template, hour) cells;
 * a bound that is reached stops the read and is recorded in the manifest ({@code truncated}). <b>Masking first:</b>
 * the builder is only ever given masked lines; nothing it returns holds a word outside the vocabulary.</p>
 *
 * <p>Usable days of a device and source are the days with at least one line and no gap record; the cohort of a source
 * is the devices with at least {@value LogSeverity#MIN_BASELINE_DAYS} usable days (the ratified 7-day rule); fleet
 * prevalence counts cohort devices. Not thread safe.</p>
 */
public final class LogCatalogBuilder {
    /** Drain and bound parameters; the Drain values are the design's PROVISIONAL starting values (depth 4, similarity 0.4). */
    public record Params(int depth, double similarity, int maxChildren, int maxClustersPerSource, int maxTokens, long maxLines,
            long maxDetailEntries) {
        public static Params defaults() {
            return new Params(4, 0.4, 100, 20_000, LogTemplateNormaliser.MAX_TOKENS, 20_000_000L, 5_000_000L);
        }

        TemplateMiner.Params miner() {
            return new TemplateMiner.Params(depth, similarity, maxChildren, maxClustersPerSource, maxTokens);
        }
    }

    /** One masked line of the training window with its device, source and device-local time (null: no readable time). */
    public record Line(String deviceId, String sourceKey, LocalDateTime time, String maskedLine) {
    }

    /** The built DRAFT: the catalog row, its templates and its baselines. */
    public record Draft(CatalogRow catalog, List<TemplateRow> templates, List<DeviceWindowRow> windows, List<DeviceStatRow> stats) {
        public Draft {
            templates = List.copyOf(templates);
            windows = List.copyOf(windows);
            stats = List.copyOf(stats);
        }
    }

    private static final Pattern PAN_SEVERITY = Pattern.compile("\"severity\"\\s*:\\s*\"([A-Za-z]{3,16})\"");
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final class DeviceAcc {
        long lines;
        final Map<Long, Integer> hours = new HashMap<>();
        final Set<LocalDate> days = new TreeSet<>();
    }

    private static final class ClusterAcc {
        long lines;
        double weight;
        String markerId;
        boolean vendorSeverity;
        LocalDate firstDay;
        final Map<String, DeviceAcc> devices = new TreeMap<>();
    }

    private final LogTemplateNormaliser normaliser;
    private final HardwareMarkers markers;
    private final LogTemplateIds ids;
    private final Function<String, String> keyedToken;
    private final Params params;
    private final LocalDate from;
    private final LocalDate to;
    private final Map<String, Set<LocalDate>> gapDays;
    private final Map<String, TemplateMiner> miners = new TreeMap<>();
    private final Map<String, Map<Integer, ClusterAcc>> clusters = new TreeMap<>();
    private final Map<String, Set<LocalDate>> deviceSourceDays = new TreeMap<>();
    private final Map<String, Long> sourceLines = new TreeMap<>();
    private long lines;
    private long detailEntries;
    private long unparsed;
    private long overflow;
    private long outsideWindow;
    private long chunks;
    private String truncated;

    /**
     * @param gapDays device-local days with a gap record per {@code device|source}; such days are not usable baseline days
     */
    public LogCatalogBuilder(LogTemplateNormaliser normaliser, HardwareMarkers markers, LogTemplateIds ids,
            Function<String, String> keyedToken, Params params, LocalDate from, LocalDate to, Map<String, Set<LocalDate>> gapDays) {
        this.normaliser = Objects.requireNonNull(normaliser, "normaliser");
        this.markers = Objects.requireNonNull(markers, "markers");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.keyedToken = Objects.requireNonNull(keyedToken, "keyedToken");
        this.params = Objects.requireNonNull(params, "params");
        if (to.isBefore(from)) throw new IllegalArgumentException("training window ends before it starts");
        this.from = from;
        this.to = to;
        this.gapDays = Map.copyOf(gapDays);
    }

    /** Counts one chunk read (manifest only). */
    public void chunkRead() {
        chunks++;
    }

    /** Whether a bound has stopped the read; the caller stops feeding lines. */
    public boolean full() {
        return truncated != null;
    }

    /** Adds one masked line; returns false when a bound is reached (the line is not counted). */
    public boolean accept(Line line) {
        if (truncated != null) return false;
        if (lines >= params.maxLines()) {
            truncated = "MAX_LINES";
            return false;
        }
        lines++;
        if (line.time() == null) {
            unparsed++;
            return true;
        }
        LocalDate day = line.time().toLocalDate();
        if (day.isBefore(from) || day.isAfter(to)) {
            outsideWindow++;
            return true;
        }
        List<String> tokens = normaliser.normalise(line.sourceKey(), line.maskedLine());
        if (tokens.isEmpty()) {
            unparsed++;
            return true;
        }
        TemplateMiner miner = miners.computeIfAbsent(line.sourceKey(), k -> new TemplateMiner(params.miner()));
        int cluster = miner.add(String.join(" ", tokens));
        if (cluster == TemplateMiner.OVERFLOW) {
            overflow++;
            return true;
        }
        if (cluster < 0) {
            unparsed++;
            return true;
        }
        ClusterAcc acc = clusters.computeIfAbsent(line.sourceKey(), k -> new TreeMap<>()).computeIfAbsent(cluster, k -> new ClusterAcc());
        DeviceAcc dev = acc.devices.get(line.deviceId());
        long hour = line.time().truncatedTo(ChronoUnit.HOURS).toEpochSecond(ZoneOffset.UTC) / 3600;
        boolean newCell = dev == null || !dev.hours.containsKey(hour);
        if (newCell && detailEntries >= params.maxDetailEntries()) {
            truncated = "MAX_DETAIL_ENTRIES";
            lines--;
            return false;
        }
        if (dev == null) {
            dev = new DeviceAcc();
            acc.devices.put(line.deviceId(), dev);
        }
        if (newCell) detailEntries++;
        dev.hours.merge(hour, 1, Integer::sum);
        dev.days.add(day);
        dev.lines++;
        acc.lines++;
        if (acc.firstDay == null || day.isBefore(acc.firstDay)) acc.firstDay = day;
        classify(acc, line.sourceKey(), line.maskedLine());
        deviceSourceDays.computeIfAbsent(line.deviceId() + "|" + line.sourceKey(), k -> new TreeSet<>()).add(day);
        sourceLines.merge(line.sourceKey(), 1L, Long::sum);
        return true;
    }

    /** Severity source of the template (design 3.1 and 3.2): the strongest marker or vendor severity of its lines. */
    private void classify(ClusterAcc acc, String sourceKey, String masked) {
        if (LogEvidenceExtractor.SOURCE_PAN_SYSTEM.equals(sourceKey)) {
            Matcher m = PAN_SEVERITY.matcher(masked);
            if (m.find()) {
                OptionalDouble w = LogSeverity.panWeight(m.group(1));
                if (w.isPresent()) {
                    acc.vendorSeverity = true;
                    acc.weight = Math.max(acc.weight, w.getAsDouble());
                }
            }
            return;
        }
        for (HardwareMarkers.Hit hit : markers.match(masked)) {
            double w = hit.marker().weight();
            if (acc.markerId == null || w > acc.weight || (w == acc.weight && hit.marker().id().compareTo(acc.markerId) < 0)) {
                acc.markerId = hit.marker().id();
                acc.weight = Math.max(acc.weight, w);
            }
        }
    }

    /** The usable days of one device and source: days with lines and no gap record. */
    private Set<LocalDate> usableDays(String deviceId, String sourceKey) {
        Set<LocalDate> days = new TreeSet<>(deviceSourceDays.getOrDefault(deviceId + "|" + sourceKey, Set.of()));
        days.removeAll(gapDays.getOrDefault(deviceId + "|" + sourceKey, Set.of()));
        return days;
    }

    public Draft finish(String catalogVersion) {
        List<TemplateRow> templates = new ArrayList<>();
        List<DeviceWindowRow> windows = new ArrayList<>();
        List<DeviceStatRow> stats = new ArrayList<>();
        Map<String, Integer> cohorts = new TreeMap<>();
        for (var sourceEntry : clusters.entrySet()) {
            String source = sourceEntry.getKey();
            TemplateMiner miner = miners.get(source);
            // devices of the source and their usable days
            Map<String, Set<LocalDate>> usable = new TreeMap<>();
            for (String key : deviceSourceDays.keySet()) {
                int bar = key.lastIndexOf('|');
                if (!key.substring(bar + 1).equals(source)) continue;
                String device = key.substring(0, bar);
                Set<LocalDate> u = usableDays(device, source);
                if (!u.isEmpty()) usable.put(device, u);
            }
            for (var u : usable.entrySet()) {
                Set<LocalDate> d = u.getValue();
                windows.add(new DeviceWindowRow(catalogVersion, u.getKey(), source, d.size(), ((TreeSet<LocalDate>) d).first(),
                        ((TreeSet<LocalDate>) d).last()));
            }
            Set<String> cohort = new TreeSet<>();
            for (var u : usable.entrySet()) if (u.getValue().size() >= LogSeverity.MIN_BASELINE_DAYS) cohort.add(u.getKey());
            cohorts.put(source, cohort.size());
            // clusters to templates; two clusters with the same token sequence share one id (and their counts)
            Map<String, ClusterAcc> byTemplate = new TreeMap<>();
            Map<String, List<String>> tokensOf = new TreeMap<>();
            for (var c : sourceEntry.getValue().entrySet()) {
                List<String> tokens = miner.cluster(c.getKey()).tokens();
                String id = ids.catalogId(source, tokens);
                ClusterAcc existing = byTemplate.get(id);
                if (existing == null) {
                    byTemplate.put(id, c.getValue());
                    tokensOf.put(id, tokens);
                } else {
                    merge(existing, c.getValue());
                }
            }
            for (var t : byTemplate.entrySet()) {
                String id = t.getKey();
                ClusterAcc acc = t.getValue();
                List<String> tokens = tokensOf.get(id);
                int prevalence = 0;
                for (String device : acc.devices.keySet()) if (cohort.contains(device)) prevalence++;
                String templateClass = acc.markerId != null ? "MARKER" : acc.vendorSeverity ? "VENDOR_SEVERITY" : "UNCLASSED";
                double weight = templateClass.equals("UNCLASSED") ? LogSeverity.DEFAULT_WEIGHT : Math.max(0.05, acc.weight);
                templates.add(new TemplateRow(catalogVersion, id, source, normaliser.displayForm(tokens),
                        normaliser.matchForm(tokens, keyedToken), tokens.size(), acc.markerId, weight, templateClass,
                        LogProvenance.PROVISIONAL.name(), acc.lines, prevalence, cohort.size(), acc.firstDay));
                for (var d : acc.devices.entrySet()) {
                    Set<LocalDate> u = usable.get(d.getKey());
                    if (u == null || u.isEmpty()) continue;
                    stats.add(deviceStat(catalogVersion, d.getKey(), source, id, d.getValue(), u));
                }
            }
        }
        templates.sort(java.util.Comparator.comparing(TemplateRow::sourceKey).thenComparing(TemplateRow::templateId));
        windows.sort(java.util.Comparator.comparing(DeviceWindowRow::sourceKey).thenComparing(DeviceWindowRow::deviceId));
        stats.sort(java.util.Comparator.comparing(DeviceStatRow::sourceKey).thenComparing(DeviceStatRow::deviceId)
                .thenComparing(DeviceStatRow::templateId));
        String digest = digest(templates);
        String manifest = manifest(catalogVersion, cohorts, templates.size());
        CatalogRow row = new CatalogRow(catalogVersion, "DRAFT", null, manifest, digest, templates.size(), "{}", null, null);
        return new Draft(row, templates, windows, stats);
    }

    private static void merge(ClusterAcc into, ClusterAcc from) {
        into.lines += from.lines;
        if (from.weight > into.weight || (from.weight == into.weight && from.markerId != null
                && (into.markerId == null || from.markerId.compareTo(into.markerId) < 0))) {
            into.weight = from.weight;
            into.markerId = from.markerId;
        }
        into.vendorSeverity |= from.vendorSeverity;
        if (from.firstDay != null && (into.firstDay == null || from.firstDay.isBefore(into.firstDay))) into.firstDay = from.firstDay;
        for (var d : from.devices.entrySet()) {
            DeviceAcc target = into.devices.computeIfAbsent(d.getKey(), k -> new DeviceAcc());
            target.lines += d.getValue().lines;
            target.days.addAll(d.getValue().days);
            d.getValue().hours.forEach((h, n) -> target.hours.merge(h, n, Integer::sum));
        }
    }

    /** Hourly baseline over the device's usable days (design 4.2: hour grain for cp_messages and pan_system). */
    static DeviceStatRow deviceStat(String version, String deviceId, String source, String templateId, DeviceAcc acc,
            Set<LocalDate> usable) {
        List<Long> nonZero = new ArrayList<>();
        int presence = 0;
        long lineCount = 0;
        for (var h : new TreeMap<>(acc.hours).entrySet()) {
            LocalDate day = LocalDateTime.ofEpochSecond(h.getKey() * 3600, 0, ZoneOffset.UTC).toLocalDate();
            if (!usable.contains(day)) continue;
            nonZero.add((long) h.getValue());
        }
        for (LocalDate d : acc.days) if (usable.contains(d)) presence++;
        lineCount = acc.lines;
        long buckets = (long) usable.size() * 24;
        long[] values = nonZero.stream().mapToLong(Long::longValue).toArray();
        LogRobustStats.MedianMad mm = LogRobustStats.of(buckets, values);
        TreeSet<LocalDate> days = new TreeSet<>(acc.days);
        return new DeviceStatRow(version, deviceId, source, templateId, "ALL", days.first(), days.last(), Math.max(1, lineCount),
                presence, usable.size(), usable.isEmpty() ? 0 : (double) presence / usable.size(),
                buckets == 0 ? 0 : Math.min(1.0, (double) values.length / buckets), mm.median(), mm.mad(), (int) buckets);
    }

    /** SHA-256 over the sorted (template id, class, S, marker) list (design section 6.5). */
    static String digest(List<TemplateRow> templates) {
        StringBuilder sb = new StringBuilder();
        templates.stream().sorted(java.util.Comparator.comparing(TemplateRow::templateId)).forEach(t -> sb.append(t.templateId())
                .append('|').append(t.templateClass()).append('|').append(String.format(Locale.ROOT, "%.2f", t.severityWeight()))
                .append('|').append(t.markerId() == null ? "" : t.markerId()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String manifest(String version, Map<String, Integer> cohorts, int templateCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("catalog_version", version);
        m.put("normaliser_version", LogTemplateNormaliser.VERSION);
        m.put("vocabulary_version", LogTemplateNormaliser.VOCABULARY_VERSION);
        m.put("masking_rule_set", LogMasking.RULE_SET);
        m.put("marker_list_version", HardwareMarkers.LIST_VERSION);
        Map<String, Object> drain = new LinkedHashMap<>();
        drain.put("depth", params.depth());
        drain.put("similarity", params.similarity());
        drain.put("max_children", params.maxChildren());
        drain.put("max_clusters_per_source", params.maxClustersPerSource());
        drain.put("max_tokens", params.maxTokens());
        m.put("drain", drain);
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("max_lines", params.maxLines());
        bounds.put("max_detail_entries", params.maxDetailEntries());
        m.put("bounds", bounds);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", from.toString());
        window.put("to", to.toString());
        window.put("time_basis", "DEVICE_LOCAL");
        m.put("training_window", window);
        m.put("sources", new ArrayList<>(new TreeSet<>(clusters.keySet())));
        m.put("source_lines", sourceLines);
        m.put("cohort_devices", cohorts);
        m.put("chunks", chunks);
        m.put("lines", lines);
        m.put("unparsed_lines", unparsed);
        m.put("overflow_lines", overflow);
        m.put("outside_window_lines", outsideWindow);
        m.put("truncated", truncated != null);
        m.put("truncated_reason", truncated);
        m.put("template_count", templateCount);
        m.put("baseline_grain", "HOUR");
        m.put("segments", "ALL (version and HA-role segments of design 6.4 not yet built)");
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest not serialisable", e);
        }
    }
}
