package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed.Chunk;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed.Masked;
import com.securityexpert.nexus.ui2.platform.logtemplate.TemplateMiner;
import com.securityexpert.nexus.ui2.worker.diagnostic.CensusLineShapes.Marker;
import com.securityexpert.nexus.ui2.worker.diagnostic.CensusLineShapes.Severity;

/**
 * System-log template census (P0 of the detection engine, PO 2026-11-24): a one-off, read-only measurement of the
 * stored log pool. It reads live (not pruned) {@code system_log_chunk} rows of a date window and some source keys,
 * through a {@link SystemLogCensusFeed} that decrypts each chunk and applies the aiview masking to every line <em>before</em> this
 * class sees it, mines templates per source with a Drain-style miner ({@link TemplateMiner}), marks severity and the
 * closed hardware/kernel marker set per line, and builds one JSON report: per source line, unparsed and template
 * counts; per template id, text (200 characters), count, fleet prevalence (device count), first and last day,
 * severity, marker and the largest count of one device on one day; and the "rare and severe" top list.
 *
 * <p><b>Privacy.</b> This class never holds raw text: a {@link SystemLogCensusFeed} returns masked lines only. Templates are built
 * from messages with every number, hex, identifier, address and masked token collapsed to {@code <*>}. Device names
 * leave only as the aiview pseudonym the feed supplies, and only in the rare-and-severe list. The finished report is
 * scanned by {@link CensusMaskGuard#assertClean(String)}; a hit fails the run and nothing is written.</p>
 *
 * <p><b>Bounds.</b> Chunk by chunk streaming, one chunk's lines in memory at a time; the template table of a source
 * holds at most {@link Options#maxTemplates()} clusters (further new shapes are counted as overflow); per-device
 * detail is kept only for templates seen on at most {@value #RARE_MAX_DEVICES} devices; {@link Options#maxLines()}
 * stops the run. The feed must hand chunks over grouped by (source, device, day) for the per-device-day maximum to be
 * exact (it only ever under-counts otherwise).</p>
 *
 * <p><b>Read only.</b> Nothing here writes to a table; the feed owns the (read-only) connection.</p>
 */
public final class SystemLogTemplateCensus {
    /** A template seen on more devices than this is not "rare". */
    public static final int RARE_MAX_DEVICES = 3;
    public static final int RARE_LIST_SIZE = 100;
    public static final int TEMPLATE_TEXT_LIMIT = 200;
    /** A line is cut here before it is parsed (the miner and the patterns work on a bounded length). */
    static final int MAX_LINE_CHARS = 2000;
    static final int MAX_DAYS_PER_RARE_DEVICE = 400;
    public static final String TOOL = "system_log_template_census";
    public static final int REPORT_VERSION = 1;
    public static final Set<String> SOURCES = Set.of("cp_messages", "cp_fwd_elg", "cp_cpd_elg", "cp_cpwd_elg",
            "cp_fwk_elg", "cp_mq_mng_elg", "pan_system");
    private static final Pattern SOURCE_KEY = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern PSEUDONYM = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,47}");

    /** The run arguments. */
    public record Options(LocalDate from, LocalDate to, List<String> sources, long maxLines, int depth, double similarity,
            int maxChildren, int maxTemplates, int maxChunkBytes, String out) {

        public static final long DEFAULT_MAX_LINES = 200_000_000L;
        public static final int DEFAULT_MAX_CHUNK_BYTES = 16 * 1024 * 1024;

        public Options {
            if (from == null || to == null) throw new IllegalArgumentException("--from and --to are required");
            if (to.isBefore(from)) throw new IllegalArgumentException("--to is before --from");
            if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("--sources is required");
            sources = List.copyOf(sources);
            for (String s : sources) {
                if (!SOURCE_KEY.matcher(s).matches()) throw new IllegalArgumentException("bad source key");
            }
            if (maxLines < 1) throw new IllegalArgumentException("--max-lines must be positive");
            if (maxChunkBytes < 1024) throw new IllegalArgumentException("--max-chunk-bytes is too small");
        }

        /** Parses {@code --name value} pairs; unknown names are refused. */
        public static Options parse(String[] args) {
            Map<String, String> a = new HashMap<>();
            Set<String> known = Set.of("--from", "--to", "--sources", "--max-lines", "--depth", "--similarity",
                    "--max-children", "--max-templates", "--max-chunk-bytes", "--out");
            for (int i = 0; i < args.length; i += 2) {
                if (!known.contains(args[i]) || i + 1 >= args.length) throw new IllegalArgumentException("bad argument");
                a.put(args[i], args[i + 1]);
            }
            try {
                String sources = a.get("--sources");
                if (sources == null) throw new IllegalArgumentException("--sources is required");
                return new Options(requiredDay(a, "--from"), requiredDay(a, "--to"), List.of(sources.split(",")),
                        Long.parseLong(a.getOrDefault("--max-lines", String.valueOf(DEFAULT_MAX_LINES))),
                        Integer.parseInt(a.getOrDefault("--depth", "4")),
                        Double.parseDouble(a.getOrDefault("--similarity", "0.5")),
                        Integer.parseInt(a.getOrDefault("--max-children", "100")),
                        Integer.parseInt(a.getOrDefault("--max-templates", "20000")),
                        Integer.parseInt(a.getOrDefault("--max-chunk-bytes", String.valueOf(DEFAULT_MAX_CHUNK_BYTES))),
                        a.get("--out"));
            } catch (NumberFormatException | DateTimeParseException e) {
                throw new IllegalArgumentException("bad argument value");
            }
        }

        private static LocalDate requiredDay(Map<String, String> a, String name) {
            String v = a.get(name);
            if (v == null) throw new IllegalArgumentException(name + " is required");
            return LocalDate.parse(v);
        }

        TemplateMiner.Params minerParams() {
            return new TemplateMiner.Params(depth, similarity, maxChildren, maxTemplates, 64);
        }
    }

    private static final class Detail {
        long count;
        final TreeMap<String, Integer> days = new TreeMap<>();
    }

    private static final class Stat {
        long count;
        final BitSet devices = new BitSet();
        String firstDay;
        String lastDay;
        Severity severity = Severity.UNKNOWN;
        Marker marker;
        int maxDeviceDay;
        Map<Integer, Detail> detail = new HashMap<>(); // dropped once more than RARE_MAX_DEVICES devices were seen
    }

    private static final class Family {
        final String key;
        final TemplateMiner miner;
        final Map<Integer, Stat> stats = new HashMap<>();
        long lines;
        long unparsed;
        long guardRejected;
        long overflow;
        long chunks;
        long chunksFailed;
        long chunksTruncated;
        final BitSet devices = new BitSet();

        Family(String key, TemplateMiner.Params params) {
            this.key = key;
            this.miner = new TemplateMiner(params);
        }
    }

    private final Options options;
    private final SystemLogCensusFeed feed;
    private final PrintStream progress;
    private final Map<String, Family> families = new TreeMap<>();
    private final Map<String, Integer> deviceIndex = new HashMap<>();
    private final List<String> deviceIds = new ArrayList<>();
    private final Map<Integer, int[]> scratch = new HashMap<>(); // cluster id -> count for the current (source, device, day)
    private String scratchKey;
    private Family scratchFamily;
    private long totalLines;
    private long totalChunks;
    private long failedChunks;
    private boolean stoppedByMaxLines;
    private final Map<String, Long> errorKinds = new TreeMap<>();

    private SystemLogTemplateCensus(Options options, SystemLogCensusFeed feed, PrintStream progress) {
        this.options = options;
        this.feed = feed;
        this.progress = progress;
    }

    /** Runs the census and returns the finished, guard-checked report as JSON text. */
    public static String run(SystemLogCensusFeed feed, Options options, PrintStream progress) throws Exception {
        SystemLogTemplateCensus census = new SystemLogTemplateCensus(options, feed, progress);
        census.scan();
        String json = new ObjectMapper().writeValueAsString(census.report());
        CensusMaskGuard.assertClean(json);
        return json;
    }

    private void scan() throws Exception {
        feed.chunks(options.from(), options.to(), options.sources(), chunk -> {
            if (totalLines >= options.maxLines()) {
                stoppedByMaxLines = true;
                return false;
            }
            Family family = families.computeIfAbsent(chunk.sourceKey(), k -> new Family(k, options.minerParams()));
            int device = deviceIndex.computeIfAbsent(chunk.deviceId(), k -> {
                deviceIds.add(k);
                return deviceIds.size() - 1;
            });
            String key = chunk.sourceKey() + '\u0000' + chunk.deviceId() + '\u0000' + chunk.logDay();
            if (!key.equals(scratchKey)) {
                flushScratch();
                scratchKey = key;
                scratchFamily = family;
            }
            totalChunks++;
            family.chunks++;
            family.devices.set(device);
            Masked masked;
            try {
                masked = feed.read(chunk, options.maxChunkBytes());
            } catch (Exception e) {
                family.chunksFailed++;
                failedChunks++;
                errorKinds.merge(e.getClass().getSimpleName().toLowerCase(Locale.ROOT), 1L, Long::sum);
                return true;
            }
            if (masked.truncated()) family.chunksTruncated++;
            family.guardRejected += masked.guardRejected();
            family.lines += masked.guardRejected();
            totalLines += masked.guardRejected();
            for (String line : masked.lines()) {
                if (totalLines >= options.maxLines()) {
                    stoppedByMaxLines = true;
                    break;
                }
                addLine(family, device, chunk.logDay(), line);
            }
            if (progress != null && totalChunks % 100 == 0) {
                progress.println("progress chunks=" + totalChunks + " lines=" + totalLines + " failed_chunks=" + failedChunks);
            }
            return !stoppedByMaxLines;
        });
        flushScratch();
        if (progress != null) {
            progress.println("done chunks=" + totalChunks + " lines=" + totalLines + " failed_chunks=" + failedChunks);
        }
    }

    private void addLine(Family family, int device, String day, String rawMasked) {
        if (rawMasked == null || rawMasked.isBlank()) return;
        String line = rawMasked.length() > MAX_LINE_CHARS ? rawMasked.substring(0, MAX_LINE_CHARS) : rawMasked;
        totalLines++;
        family.lines++;
        CensusLineShapes.Parsed parsed = CensusLineShapes.parse(family.key, line);
        if (parsed == null) {
            family.unparsed++;
            return;
        }
        int id = family.miner.add(CensusLineShapes.normalize(parsed.message()));
        if (id == TemplateMiner.OVERFLOW) {
            family.overflow++;
            return;
        }
        if (id == TemplateMiner.EMPTY) {
            family.unparsed++;
            return;
        }
        Stat stat = family.stats.computeIfAbsent(id, k -> new Stat());
        stat.count++;
        stat.devices.set(device);
        if (stat.firstDay == null || day.compareTo(stat.firstDay) < 0) stat.firstDay = day;
        if (stat.lastDay == null || day.compareTo(stat.lastDay) > 0) stat.lastDay = day;
        stat.severity = Severity.max(stat.severity, parsed.severity());
        if (stat.marker == null) stat.marker = CensusLineShapes.marker(line);
        if (stat.devices.cardinality() <= RARE_MAX_DEVICES) {
            Detail d = stat.detail.computeIfAbsent(device, k -> new Detail());
            d.count++;
            if (d.days.size() < MAX_DAYS_PER_RARE_DEVICE || d.days.containsKey(day)) d.days.merge(day, 1, Integer::sum);
        } else if (!stat.detail.isEmpty()) {
            stat.detail = new HashMap<>();
        }
        scratch.computeIfAbsent(id, k -> new int[1])[0]++;
    }

    private void flushScratch() {
        if (scratchFamily != null) {
            for (Map.Entry<Integer, int[]> e : scratch.entrySet()) {
                Stat stat = scratchFamily.stats.get(e.getKey());
                if (stat != null && e.getValue()[0] > stat.maxDeviceDay) stat.maxDeviceDay = e.getValue()[0];
            }
        }
        scratch.clear();
    }

    // ------------------------------------------------------------------ report

    private record RareEntry(String family, int clusterId, String templateId, String template, Stat stat) {
    }

    private Map<String, Object> report() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", TOOL);
        root.put("report_version", REPORT_VERSION);
        root.put("mask_rule_set", feed.maskRuleSet());
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", options.from().toString());
        window.put("to", options.to().toString());
        window.put("sources", options.sources());
        root.put("window", window);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("depth", options.depth());
        params.put("similarity", options.similarity());
        params.put("max_children", options.maxChildren());
        params.put("max_templates_per_source", options.maxTemplates());
        params.put("max_lines", options.maxLines());
        params.put("max_chunk_bytes", options.maxChunkBytes());
        root.put("params", params);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("chunks_read", totalChunks);
        totals.put("chunks_failed", failedChunks);
        totals.put("chunk_error_kinds", errorKinds);
        totals.put("lines", totalLines);
        totals.put("devices", deviceIds.size());
        totals.put("stopped_by_max_lines", stoppedByMaxLines);
        root.put("totals", totals);

        Map<String, Object> out = new LinkedHashMap<>();
        List<RareEntry> rare = new ArrayList<>();
        for (Family f : families.values()) {
            Map<String, Object> fam = new LinkedHashMap<>();
            fam.put("chunks", f.chunks);
            fam.put("chunks_failed", f.chunksFailed);
            fam.put("chunks_truncated", f.chunksTruncated);
            fam.put("devices", f.devices.cardinality());
            fam.put("lines", f.lines);
            fam.put("unparsed_lines", f.unparsed);
            fam.put("guard_rejected_lines", f.guardRejected);
            fam.put("overflow_lines", f.overflow);
            fam.put("template_count", f.miner.clusterCount());
            List<Map<String, Object>> templates = new ArrayList<>();
            List<Integer> ids = new ArrayList<>(f.stats.keySet());
            ids.sort(Comparator.<Integer>comparingLong(i -> -f.stats.get(i).count).thenComparing(Comparator.naturalOrder()));
            for (int id : ids) {
                Stat s = f.stats.get(id);
                String text = f.miner.cluster(id).text();
                String templateId = templateId(text);
                String shown = text.length() > TEMPLATE_TEXT_LIMIT ? text.substring(0, TEMPLATE_TEXT_LIMIT) : text;
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("template_id", templateId);
                t.put("template", shown);
                t.put("count", s.count);
                t.put("device_count", s.devices.cardinality());
                t.put("first_day", s.firstDay);
                t.put("last_day", s.lastDay);
                t.put("severity", s.severity.json());
                t.put("marker", s.marker == null ? null : s.marker.json());
                t.put("max_device_day_count", s.maxDeviceDay);
                templates.add(t);
                if (s.devices.cardinality() <= RARE_MAX_DEVICES && (s.severity.atLeastError() || s.marker != null)) {
                    rare.add(new RareEntry(f.key, id, templateId, shown, s));
                }
            }
            fam.put("templates", templates);
            out.put(f.key, fam);
        }
        root.put("families", out);
        root.put("rare_and_severe", rareList(rare));
        return root;
    }

    private List<Map<String, Object>> rareList(List<RareEntry> rare) {
        rare.sort(Comparator.<RareEntry>comparingInt(r -> -r.stat().severity.ordinal())
                .thenComparingInt(r -> r.stat().marker == null ? 1 : 0)
                .thenComparingLong(r -> -r.stat().count)
                .thenComparing(RareEntry::templateId).thenComparing(RareEntry::family));
        List<Map<String, Object>> list = new ArrayList<>();
        for (RareEntry r : rare) {
            if (list.size() >= RARE_LIST_SIZE) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("family", r.family());
            m.put("template_id", r.templateId());
            m.put("template", r.template());
            m.put("count", r.stat().count);
            m.put("device_count", r.stat().devices.cardinality());
            m.put("severity", r.stat().severity.json());
            m.put("marker", r.stat().marker == null ? null : r.stat().marker.json());
            List<Map<String, Object>> devices = new ArrayList<>();
            for (Map.Entry<Integer, Detail> e : new TreeMap<>(r.stat().detail).entrySet()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("device", pseudonym(e.getKey()));
                d.put("count", e.getValue().count);
                List<Map<String, Object>> days = new ArrayList<>();
                for (Map.Entry<String, Integer> day : e.getValue().days.entrySet()) {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("day", day.getKey());
                    dm.put("count", day.getValue());
                    days.add(dm);
                }
                d.put("days", days);
                devices.add(d);
            }
            m.put("devices", devices);
            list.add(m);
        }
        return list;
    }

    private String pseudonym(int device) {
        String name = null;
        try {
            name = feed.devicePseudonym(deviceIds.get(device));
        } catch (RuntimeException ignored) {
            // fall through to the opaque label
        }
        // A pseudonym must look like one; anything else (a raw name, an address) is replaced by an opaque ordinal.
        if (name == null || !PSEUDONYM.matcher(name).matches() || !CensusMaskGuard.findings(name).isEmpty()) {
            return "DEVICE-" + (device + 1);
        }
        return name;
    }

    /** Stable id of a template: the first 12 hex digits of the SHA-256 of its full text. */
    public static String templateId(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
