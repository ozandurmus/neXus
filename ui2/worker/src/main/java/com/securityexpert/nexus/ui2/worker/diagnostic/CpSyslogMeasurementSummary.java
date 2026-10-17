package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces the raw output of one Check Point system-log measurement read ({@code cp_syslog_measure_*}, V149 and V157)
 * to a NUMERIC summary, in memory, before any masking (DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4, findings R1).
 * The aiview projection hides every integer of 6 or more digits, all mtimes and all file names, so sizes, ages and
 * rotation could not be measured from the masked text. This summary keeps exactly what that text loses:
 * <ul>
 * <li>sizes, mtimes, counts and medians as plain numbers;</li>
 * <li>a file or directory name only as a CLASS from a closed vocabulary ({@code messages.N}, {@code sarNN},
 *     {@code perf_cpu_NN.log}, {@code <proc>.N.core.gz}): known stem words stay, digit runs become {@code N}/{@code NN},
 *     every other word becomes {@code X}; a core-dump process name is an allowlisted daemon name or a keyed token;</li>
 * <li>an inode only as a keyed per-device token (equal inode, equal token; the number is never stored);</li>
 * <li>a line only as a timestamp-shape bucket, a comma-field count, a length and a count.</li>
 * </ul>
 * No raw line, path, file name, host name, address or user name ever reaches the returned map. A leading banner line
 * (25 of 53 gateways print one before every output) is skipped by shape, never by position. The method does not throw:
 * an output it cannot read is a typed {@code UNKNOWN} summary.
 */
final class CpSyslogMeasurementSummary {
    static final String SCHEMA = "cp_syslog_numeric_v1";
    static final String PREFIX = "cp_syslog_measure_";
    static final String OTHER = "OTHER";
    static final int MAX_CLASSES = 64;
    static final int MAX_BUCKETS = 32;

    /** Keyed (HMAC) tokens; the key never leaves the worker. */
    interface KeyedTokens {
        /** Opaque token of one inode on one device: equal inode on the same device gives an equal token. */
        String inode(long inode);

        /** Opaque token of a process-name word that is not on the allowlist. */
        String word(String lowerWord);
    }

    private enum Kind { STAT_ORDERED, STAT_NAMED, LS, TEXT, TAR }

    private record Spec(Kind kind, List<String> slots, String defaultSection, boolean commaFields,
            boolean firstTokenShape, boolean sar) {
        static Spec of(Kind kind) { return new Spec(kind, List.of(), null, false, false, false); }
    }

    private static final Map<String, Spec> SPECS = Map.ofEntries(
            Map.entry("cp_syslog_measure_stat", new Spec(Kind.STAT_ORDERED,
                    List.of("messages", "messages.1", "fwd.elg", "cpd.elg", "cpwd.elg"), null, false, false, false)),
            Map.entry("cp_syslog_measure_stat_ext", Spec.of(Kind.STAT_NAMED)),
            Map.entry("cp_syslog_measure_ls_sa", new Spec(Kind.LS, List.of(), "sa", false, false, false)),
            Map.entry("cp_syslog_measure_ls_spike", new Spec(Kind.LS, List.of(), "spike_detective", false, false, false)),
            Map.entry("cp_syslog_measure_ls_hcp", new Spec(Kind.LS, List.of(), "hcp_last", false, false, false)),
            Map.entry("cp_syslog_measure_ls_crash", new Spec(Kind.LS, List.of(), "unattributed", false, false, false)),
            Map.entry("cp_syslog_measure_tail_messages", Spec.of(Kind.TEXT)),
            Map.entry("cp_syslog_measure_tail_fwd", Spec.of(Kind.TEXT)),
            Map.entry("cp_syslog_measure_tail_cpd", Spec.of(Kind.TEXT)),
            Map.entry("cp_syslog_measure_tail_cpwd", Spec.of(Kind.TEXT)),
            Map.entry("cp_syslog_measure_tail_fwk", Spec.of(Kind.TEXT)),
            Map.entry("cp_syslog_measure_tail_spike", new Spec(Kind.TEXT, List.of(), null, true, true, false)),
            Map.entry("cp_syslog_measure_tail_spike_csv", new Spec(Kind.TEXT, List.of(), null, true, true, false)),
            Map.entry("cp_syslog_measure_head_sar", new Spec(Kind.TEXT, List.of(), null, false, false, true)),
            Map.entry("cp_syslog_measure_hcp_listing", Spec.of(Kind.TAR)));

    /** Daemon names whose core dumps may be named in a summary; everything else becomes a keyed token. */
    private static final Set<String> PROCESSES = Set.of(
            "fwd", "fw", "fwk", "cpd", "cpwd", "cpm", "cprid", "cpviewd", "cpca", "cpmad", "cpsead", "cpstat",
            "vpnd", "mpdaemon", "rad", "pdpd", "pepd", "dlpda", "sfwd", "routed", "clish", "expert", "sshd", "httpd",
            "httpd2", "nginx", "java", "bash", "dbus", "ntpd", "snmpd", "syslogd", "rpcbind",
            "mq", "fwm", "fwssd", "scrub", "cphamcset", "cpshared", "dleserver", "log_indexer", "dynamic_objects",
            "cpdnsd", "dnsmasq", "cron", "crond", "lighttpd", "xinetd", "gated", "ospfd", "bgpd", "ripd", "zebra");

    /** Words that may appear in a name class as themselves. */
    private static final Set<String> STEMS;
    static {
        Set<String> stems = new java.util.HashSet<>(PROCESSES);
        stems.addAll(Set.of("messages", "mng", "elg", "hcp", "report", "reports", "last", "spike", "detective", "log",
                "logs", "csv", "sar", "sa", "perf", "cpu", "summary", "profiling", "core", "dump", "usermode", "crash",
                "tar", "gz", "tgz", "old", "bak", "json", "txt", "dat", "out", "err", "tmp", "kernel", "lock", "pid",
                "status", "debug", "trace", "info", "stat", "run", "dev", "all"));
        STEMS = Set.copyOf(stems);
    }

    private static final Pattern DATA_ORDERED = Pattern.compile("^(\\d{1,18}) (\\d{1,18}) (\\d{1,18})$");
    private static final Pattern DATA_NAMED = Pattern.compile("^(/\\S.*?) (\\d{1,18}) (\\d{1,18})$");
    private static final Pattern STAT_ERROR = Pattern.compile("^stat: cannot (?:stat|statx) [`'‘\"](.*)['’\"]: (.*)$");
    private static final Pattern CLOCK_LINE = Pattern.compile("^(\\d{9,11})$");
    private static final Pattern LS_ENTRY = Pattern.compile(
            "^([-dlbcpsD])[rwxsStT-]{9}[.+@]?\\s+(\\d{1,9})\\s+(\\d{1,18})\\s+(\\d{9,11})\\s+(.+)$");
    private static final Pattern LS_TOTAL = Pattern.compile("^total (\\d{1,18})$");
    private static final Pattern LS_HEADER = Pattern.compile("^(/[^:\\s][^:]*):$");
    private static final Pattern LS_ERROR = Pattern.compile("^ls: cannot (?:access|open directory) [`'‘\"](.*)['’\"]: (.*)$");
    private static final Pattern TAR_ENTRY = Pattern.compile(
            "^([-dlhcbpsD])[rwxsStT-]{9}\\s+\\S+\\s+(\\d{1,18})\\s+(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})(?::\\d{2})?\\s+(.+)$");
    private static final Pattern TEXT_ERROR = Pattern.compile("^(?:tail|head|tar|ls|bash|stat|cat): .*$");
    private static final Pattern NO_SUCH = Pattern.compile("(?i)no such file or directory");
    private static final Pattern DENIED = Pattern.compile("(?i)permission denied");

    private CpSyslogMeasurementSummary() {}

    static boolean handles(String gateId) {
        return gateId != null && SPECS.containsKey(gateId);
    }

    static Map<String, Object> unknown(String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "UNKNOWN");
        summary.put("reason", reason);
        return summary;
    }

    /** The summary of one finished read; never throws. */
    static Map<String, Object> summarize(String gateId, String output, int exitStatus, long measuredAtEpoch,
            KeyedTokens tokens) {
        Spec spec = SPECS.get(gateId);
        if (spec == null) return unknown("GATE_NOT_SUMMARISED");
        try {
            List<String> lines = lines(output == null ? "" : output);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "MEASURED");
            summary.put("schema", SCHEMA);
            summary.put("kind", spec.kind().name().startsWith("STAT") ? "stat" : spec.kind().name().toLowerCase(Locale.ROOT));
            summary.put("exitStatus", exitStatus);
            summary.put("measuredAtEpoch", measuredAtEpoch);
            summary.put("outputLines", lines.size());
            Map<String, Object> body = switch (spec.kind()) {
                case STAT_ORDERED, STAT_NAMED -> stat(spec, lines, measuredAtEpoch, tokens);
                case LS -> ls(spec, lines, tokens);
                case TEXT -> text(spec, lines);
                case TAR -> tar(lines, tokens);
            };
            if ("UNKNOWN".equals(body.get("status"))) return body;
            summary.putAll(body);
            return summary;
        } catch (RuntimeException unreadable) {
            return unknown("OUTPUT_UNREADABLE");
        }
    }

    // ---- stat ---------------------------------------------------------------------------------------------------

    private static Map<String, Object> stat(Spec spec, List<String> lines, long measuredAt, KeyedTokens tokens) {
        boolean ordered = spec.kind() == Kind.STAT_ORDERED;
        Long deviceClock = null;
        int ignored = 0;
        List<Map<String, Object>> slots = new ArrayList<>();
        List<long[]> values = new ArrayList<>();   // inode,size,mtime per data slot (ordered) in slot order
        for (String line : lines) {
            Matcher error = STAT_ERROR.matcher(line);
            if (error.matches()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class", ordered ? "" : classify(baseName(error.group(1)), tokens).clazz());
                entry.put("present", false);
                entry.put("reason", NO_SUCH.matcher(error.group(2)).find() ? "NO_SUCH_FILE"
                        : DENIED.matcher(error.group(2)).find() ? "PERMISSION_DENIED" : "STAT_ERROR");
                slots.add(entry);
                continue;
            }
            Matcher data = (ordered ? DATA_ORDERED : DATA_NAMED).matcher(line);
            if (data.matches()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                long size, mtime, inode = 0;
                if (ordered) {
                    inode = Long.parseLong(data.group(1));
                    size = Long.parseLong(data.group(2));
                    mtime = Long.parseLong(data.group(3));
                    entry.put("class", "");
                    entry.put("present", true);
                    entry.put("inode", tokens.inode(inode));
                } else {
                    size = Long.parseLong(data.group(2));
                    mtime = Long.parseLong(data.group(3));
                    entry.put("class", classify(baseName(data.group(1)), tokens).clazz());
                    entry.put("present", true);
                }
                entry.put("sizeBytes", size);
                entry.put("mtimeEpoch", mtime);
                slots.add(entry);
                values.add(new long[] {size, mtime});
                continue;
            }
            Matcher clock = CLOCK_LINE.matcher(line.strip());
            if (ordered && deviceClock == null && clock.matches()) {
                deviceClock = Long.parseLong(clock.group(1));
                continue;
            }
            ignored++;
        }
        if (ordered) {
            if (slots.size() != spec.slots().size()) {
                Map<String, Object> unknown = unknown("SLOT_COUNT_MISMATCH");
                unknown.put("expectedSlots", spec.slots().size());
                unknown.put("foundSlots", slots.size());
                return unknown;
            }
            for (int i = 0; i < slots.size(); i++) slots.get(i).put("class", spec.slots().get(i));
        } else if (slots.isEmpty()) {
            return unknown("NO_STAT_LINES");
        }
        long clockEpoch = deviceClock != null ? deviceClock : measuredAt;
        for (Map<String, Object> entry : slots) {
            if (Boolean.TRUE.equals(entry.get("present"))) {
                entry.put("ageSeconds", clockEpoch - (Long) entry.get("mtimeEpoch"));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceClockEpoch", deviceClock);
        body.put("ageBasis", deviceClock != null ? "DEVICE_CLOCK" : "NEXUS_CLOCK");
        body.put("ignoredLines", ignored);
        body.put("files", slots);
        return body;
    }

    // ---- ls -lgo --time-style=+%s ------------------------------------------------------------------------------------

    private static final class Section {
        boolean present = true;
        Long totalKiB;
        int files, dirs, links, others;
        final Stats fileSizes = new Stats();
        long oldest = Long.MAX_VALUE, newest = Long.MIN_VALUE;
        final Map<String, Stats> classes = new TreeMap<>();
        final Map<String, int[]> families = new TreeMap<>();  // files, rotated, gz
    }

    private static Map<String, Object> ls(Spec spec, List<String> lines, KeyedTokens tokens) {
        Map<String, Section> sections = new LinkedHashMap<>();
        String current = spec.defaultSection();
        int ignored = 0, errors = 0;
        for (String line : lines) {
            Matcher header = LS_HEADER.matcher(line);
            if (header.matches()) {
                current = dirClass(header.group(1));
                sections.computeIfAbsent(current, k -> new Section());
                continue;
            }
            Matcher error = LS_ERROR.matcher(line);
            if (error.matches()) {
                errors++;
                Section missing = sections.computeIfAbsent(dirClass(error.group(1)), k -> new Section());
                if (NO_SUCH.matcher(error.group(2)).find() || DENIED.matcher(error.group(2)).find()) missing.present = false;
                continue;
            }
            Matcher total = LS_TOTAL.matcher(line);
            if (total.matches()) {
                sections.computeIfAbsent(current, k -> new Section()).totalKiB = Long.parseLong(total.group(1));
                continue;
            }
            Matcher entry = LS_ENTRY.matcher(line);
            if (!entry.matches()) {
                ignored++;
                continue;
            }
            Section section = sections.computeIfAbsent(current, k -> new Section());
            char type = entry.group(1).charAt(0);
            long size = Long.parseLong(entry.group(3));
            long mtime = Long.parseLong(entry.group(4));
            String name = entry.group(5);
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) name = name.substring(0, arrow);
            NameInfo info = classify(baseName(name), tokens);
            section.oldest = Math.min(section.oldest, mtime);
            section.newest = Math.max(section.newest, mtime);
            String key = (type == 'd' ? "dir:" : type == 'l' ? "link:" : "") + info.clazz();
            if (!section.classes.containsKey(key) && section.classes.size() >= MAX_CLASSES) key = OTHER;
            section.classes.computeIfAbsent(key, k -> new Stats()).add(size, mtime);
            if (type == '-') {
                section.files++;
                section.fileSizes.add(size, mtime);
                int[] family = section.families.get(info.family());
                if (family == null && section.families.size() >= MAX_CLASSES) family = section.families.computeIfAbsent(OTHER, k -> new int[3]);
                else if (family == null) family = section.families.computeIfAbsent(info.family(), k -> new int[3]);
                family[0]++;
                if (info.rotated()) family[1]++;
                if (info.compressed()) family[2]++;
            } else if (type == 'd') section.dirs++;
            else if (type == 'l') section.links++;
            else section.others++;
        }
        if (sections.isEmpty()) return unknown("NO_LISTING_LINES");
        Map<String, Object> out = new LinkedHashMap<>();
        long entries = 0, fileBytes = 0;
        Map<String, Object> bySection = new LinkedHashMap<>();
        for (Map.Entry<String, Section> e : sections.entrySet()) {
            Section s = e.getValue();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("present", s.present);
            int count = s.files + s.dirs + s.links + s.others;
            entries += count;
            fileBytes += s.fileSizes.sum;
            view.put("entryCount", count);
            view.put("files", s.files);
            view.put("dirs", s.dirs);
            view.put("links", s.links);
            view.put("totalBlocksKiB", s.totalKiB);
            view.put("fileBytes", s.fileSizes.sum);
            view.put("minFileBytes", s.fileSizes.count == 0 ? null : s.fileSizes.min);
            view.put("maxFileBytes", s.fileSizes.count == 0 ? null : s.fileSizes.max);
            view.put("medianFileBytes", s.fileSizes.median());
            view.put("oldestMtimeEpoch", count == 0 ? null : s.oldest);
            view.put("newestMtimeEpoch", count == 0 ? null : s.newest);
            Map<String, Object> classes = new LinkedHashMap<>();
            s.classes.forEach((name, stats) -> classes.put(name, stats.view()));
            view.put("nameClasses", classes);
            Map<String, Object> families = new LinkedHashMap<>();
            int maxSiblings = 0, rotated = 0;
            boolean anyGz = false;
            for (Map.Entry<String, int[]> f : s.families.entrySet()) {
                Map<String, Object> family = new LinkedHashMap<>();
                family.put("files", f.getValue()[0]);
                family.put("rotated", f.getValue()[1]);
                family.put("gz", f.getValue()[2]);
                families.put(f.getKey(), family);
                maxSiblings = Math.max(maxSiblings, f.getValue()[0]);
                rotated += f.getValue()[1];
                anyGz |= f.getValue()[2] > 0;
            }
            view.put("families", families);
            view.put("maxSiblings", maxSiblings);
            view.put("rotatedFiles", rotated);
            view.put("anyGz", anyGz);
            bySection.put(e.getKey(), view);
        }
        out.put("entryCount", entries);
        out.put("fileBytes", fileBytes);
        out.put("errorLines", errors);
        out.put("ignoredLines", ignored);
        out.put("sections", bySection);
        return out;
    }

    // ---- tail / head --------------------------------------------------------------------------------------------------

    private static final Pattern SHAPE_SYSLOG_YEAR = Pattern.compile("^[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\d{4}\\b.*");
    private static final Pattern SHAPE_SYSLOG = Pattern.compile("^[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\b.*");
    private static final Pattern SHAPE_ELG_TS = Pattern.compile("^\\[[^\\[\\]]{1,200}\\]@[^\\[\\]]{0,200}\\[\\d{1,2} [A-Z][a-z]{2} \\d{2}:\\d{2}:\\d{2}\\].*");
    private static final Pattern SHAPE_ELG = Pattern.compile("^\\[[^\\[\\]]{1,200}\\]@.*");
    private static final Pattern SHAPE_BRACKET_TS = Pattern.compile("^\\[\\d{1,2} [A-Z][a-z]{2} \\d{2}:\\d{2}:\\d{2}\\].*");
    private static final Pattern SHAPE_ISO_TIME = Pattern.compile("^\\W{0,2}\\d{4}-\\d{2}-\\d{2}[T _]\\d{2}:\\d{2}:\\d{2}.*");
    private static final Pattern SHAPE_ISO_DATE = Pattern.compile("^\\W{0,2}\\d{4}-\\d{2}-\\d{2}\\b.*");
    private static final Pattern SHAPE_EPOCH = Pattern.compile("^\\W{0,2}\\d{10}(?:\\.\\d+)?\\b.*");
    private static final Pattern SHAPE_TIME_FIRST = Pattern.compile("^\\W{0,2}\\d{2}:\\d{2}:\\d{2}\\b.*");
    private static final Pattern SHAPE_TIME_INSIDE = Pattern.compile(".*\\b\\d{2}:\\d{2}:\\d{2}\\b.*");
    private static final Pattern SAR_CPU = Pattern.compile("\\((\\d{1,5}) CPU\\)");
    private static final Pattern SAR_HEADER = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}(?: [AP]M)?\\s+CPU(?:\\s+\\S+)+\\s*$");
    private static final Pattern SAR_ROW = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}(?: [AP]M)?\\s+(all|\\d{1,5})(?:\\s+\\S+)+\\s*$");

    private static Map<String, Object> text(Spec spec, List<String> lines) {
        int blank = 0, errors = 0, maxLength = 0;
        boolean absent = false;
        Map<String, Integer> shapes = new TreeMap<>(), commas = new TreeMap<>(), firstTokens = new TreeMap<>();
        int sarCpus = -1, sarColumns = -1, sarRows = 0, sarPerCpuRows = 0;
        boolean sarMeridiem = false;
        for (String line : lines) {
            maxLength = Math.max(maxLength, line.length());
            if (line.isBlank()) { blank++; continue; }
            if (TEXT_ERROR.matcher(line).matches() && (NO_SUCH.matcher(line).find() || DENIED.matcher(line).find()
                    || line.startsWith("tail: ") || line.startsWith("head: "))) {
                errors++;
                absent |= NO_SUCH.matcher(line).find();
                continue;
            }
            bump(shapes, timestampShape(line));
            if (spec.commaFields()) bump(commas, String.valueOf(Math.min(64, countOf(line, ',') + 1)));
            if (spec.firstTokenShape()) bump(firstTokens, firstTokenShape(line));
            if (spec.sar()) {
                Matcher cpu = SAR_CPU.matcher(line);
                if (sarCpus < 0 && line.startsWith("Linux ") && cpu.find()) sarCpus = Integer.parseInt(cpu.group(1));
                if (sarColumns < 0 && SAR_HEADER.matcher(line).matches()) {
                    sarColumns = line.trim().split("\\s+").length;
                    sarMeridiem = line.matches("^\\d{2}:\\d{2}:\\d{2} [AP]M\\s.*");
                    if (sarMeridiem) sarColumns--;   // "AM"/"PM" is a part of the time, not a column
                }
                Matcher row = SAR_ROW.matcher(line);
                if (row.matches()) {
                    sarRows++;
                    if (!"all".equals(row.group(1))) sarPerCpuRows++;
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lineCount", lines.size());
        body.put("blankLines", blank);
        body.put("errorLines", errors);
        body.put("absent", absent);
        body.put("maxLineLength", maxLength);
        body.put("timestampShapes", capped(shapes));
        if (spec.commaFields()) body.put("commaFieldCounts", capped(commas));
        if (spec.firstTokenShape()) body.put("firstTokenShapes", capped(firstTokens));
        if (spec.sar()) {
            Map<String, Object> sar = new LinkedHashMap<>();
            sar.put("cpuCount", sarCpus < 0 ? null : sarCpus);
            sar.put("headerColumns", sarColumns < 0 ? null : sarColumns);
            sar.put("meridiemClock", sarMeridiem);
            sar.put("sampleRows", sarRows);
            sar.put("perCpuRows", sarPerCpuRows);
            body.put("sar", sar);
        }
        return body;
    }

    static String timestampShape(String line) {
        if (SHAPE_ELG_TS.matcher(line).matches()) return "ELG_HEADER_WITH_TIME";
        if (SHAPE_ELG.matcher(line).matches()) return "ELG_HEADER_NO_TIME";
        if (SHAPE_BRACKET_TS.matcher(line).matches()) return "BRACKET_DD_MON_TIME";
        if (SHAPE_SYSLOG_YEAR.matcher(line).matches()) return "SYSLOG_MON_DD_TIME_YEAR";
        if (SHAPE_SYSLOG.matcher(line).matches()) return "SYSLOG_MON_DD_TIME";
        if (SHAPE_ISO_TIME.matcher(line).matches()) return "ISO_DATE_TIME";
        if (SHAPE_ISO_DATE.matcher(line).matches()) return "ISO_DATE";
        if (SHAPE_EPOCH.matcher(line).matches()) return "EPOCH_SECONDS";
        if (SHAPE_TIME_FIRST.matcher(line).matches()) return "TIME_FIRST";
        if (SHAPE_TIME_INSIDE.matcher(line).matches()) return "TIME_INSIDE";
        return "NONE";
    }

    /** The first token (up to a space or comma) as character-class runs only: upper A, lower a, digit 9. */
    static String firstTokenShape(String line) {
        int end = 0;
        while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != ',' && line.charAt(end) != '\t') end++;
        String token = line.substring(0, end);
        if (token.isEmpty()) return "EMPTY";
        if (token.length() > 48) return "LONG";
        if (token.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return "ADDRESS_LIKE";   // counted, never described
        StringBuilder shape = new StringBuilder();
        char last = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            char mapped;
            if (c >= '0' && c <= '9') mapped = '9';
            else if (c >= 'A' && c <= 'Z') mapped = 'A';
            else if (c >= 'a' && c <= 'z') mapped = 'a';
            else if (c == '_' || c == ':' || c == '.' || c == '-' || c == '+' || c == '[' || c == ']') mapped = c;
            else return OTHER;
            if (mapped == last && (mapped == '9' || mapped == 'A' || mapped == 'a')) continue;
            shape.append(mapped);
            last = mapped;
        }
        return shape.toString();
    }

    // ---- tar -tzvf ----------------------------------------------------------------------------------------------------

    private static Map<String, Object> tar(List<String> lines, KeyedTokens tokens) {
        Stats sizes = new Stats();
        Map<String, Stats> classes = new TreeMap<>();
        int errors = 0, ignored = 0, dirs = 0;
        boolean absent = false;
        long oldest = Long.MAX_VALUE, newest = Long.MIN_VALUE;
        for (String line : lines) {
            Matcher entry = TAR_ENTRY.matcher(line);
            if (entry.matches()) {
                char type = entry.group(1).charAt(0);
                long size = Long.parseLong(entry.group(2));
                long local = java.time.LocalDateTime.of(Integer.parseInt(entry.group(3)), Integer.parseInt(entry.group(4)),
                        Integer.parseInt(entry.group(5)), Integer.parseInt(entry.group(6)), Integer.parseInt(entry.group(7)))
                        .toEpochSecond(java.time.ZoneOffset.UTC);
                oldest = Math.min(oldest, local);
                newest = Math.max(newest, local);
                String name = entry.group(8);
                int arrow = name.indexOf(" -> ");
                if (arrow > 0) name = name.substring(0, arrow);
                String key = (type == 'd' ? "dir:" : "") + classify(baseName(name), tokens).clazz();
                if (!classes.containsKey(key) && classes.size() >= MAX_CLASSES) key = OTHER;
                classes.computeIfAbsent(key, k -> new Stats()).add(size, local);
                if (type == 'd') dirs++; else sizes.add(size, local);
                continue;
            }
            if (TEXT_ERROR.matcher(line).matches() || line.contains("Error is not recoverable") || line.contains("returned status")) {
                errors++;
                absent |= NO_SUCH.matcher(line).find();
                continue;
            }
            ignored++;
        }
        int members = (int) (sizes.count + dirs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberCount", members);
        body.put("directoryMembers", dirs);
        body.put("capped", members >= 50);
        body.put("memberBytes", sizes.sum);
        body.put("minMemberBytes", sizes.count == 0 ? null : sizes.min);
        body.put("maxMemberBytes", sizes.count == 0 ? null : sizes.max);
        body.put("medianMemberBytes", sizes.median());
        // the archive's own clock: date and time as printed by tar, read as if UTC (device-local, no zone is printed)
        body.put("oldestMemberLocalEpoch", members == 0 ? null : oldest);
        body.put("newestMemberLocalEpoch", members == 0 ? null : newest);
        body.put("nameClasses", viewAll(classes));
        body.put("errorLines", errors);
        body.put("absent", absent);
        body.put("ignoredLines", ignored);
        return body;
    }

    // ---- name classes -------------------------------------------------------------------------------------------------

    record NameInfo(String clazz, String family, boolean rotated, boolean compressed) {}

    private static final Pattern COMPRESSED = Pattern.compile("^(.+?)(\\.gz|\\.bz2|\\.xz|\\.zip|\\.tgz|\\.Z)$");
    private static final Pattern ROT_DATE = Pattern.compile("^(.+?)([-.])(\\d{8})$");
    private static final Pattern ROT_INDEX = Pattern.compile("^(.+?)\\.(\\d{1,3})$");
    private static final Pattern ROT_OLD = Pattern.compile("^(.+?)\\.(old|bak|orig|prev)$");
    private static final Pattern CORE_PROC_PID = Pattern.compile("^([A-Za-z0-9_+-]{1,64})\\.(\\d{1,10})\\.core$");
    private static final Pattern CORE_PREFIX = Pattern.compile("^core\\.([A-Za-z0-9_+-]{1,64})\\.(\\d{1,10})(?:\\.\\d{1,12})?$");
    private static final Pattern CORE_PROC = Pattern.compile("^([A-Za-z0-9_+-]{1,64})\\.core(?:\\.(\\d{1,10}))?$");
    private static final Pattern CORE_BARE = Pattern.compile("^core(?:\\.\\d{1,10})?$");
    private static final Pattern WORD_OR_DIGITS = Pattern.compile("[A-Za-z]+|[0-9]+|.");

    /** The class of one file or directory name (already a base name). The input is never returned. */
    static NameInfo classify(String name, KeyedTokens tokens) {
        if (name == null || name.isEmpty()) return new NameInfo(OTHER, OTHER, false, false);
        if (name.length() > 128) return new NameInfo("LONG_NAME", "LONG_NAME", false, false);
        String core = name;
        String compression = "";
        Matcher compressed = COMPRESSED.matcher(core);
        if (compressed.matches()) {
            core = compressed.group(1);
            compression = compressed.group(2).toLowerCase(Locale.ROOT);
        }
        // core dumps: <proc>.<pid>.core[.gz], core.<proc>.<pid>[...], <proc>.core[.<pid>], core[.<pid>]
        Matcher m;
        String dump = null;
        if ((m = CORE_PROC_PID.matcher(core)).matches()) dump = procClass(m.group(1), tokens) + ".N.core";
        else if ((m = CORE_PREFIX.matcher(core)).matches()) dump = "core." + procClass(m.group(1), tokens) + ".N";
        else if (CORE_BARE.matcher(core).matches()) dump = core.contains(".") ? "core.N" : "core";
        else if ((m = CORE_PROC.matcher(core)).matches() && !"core".equals(m.group(1)))
            dump = procClass(m.group(1), tokens) + ".core" + (m.group(2) != null ? ".N" : "");
        if (dump != null) return new NameInfo(dump + compression, dump, false, !compression.isEmpty());

        boolean rotated = false;
        String rotation = "";
        if ((m = ROT_DATE.matcher(core)).matches() && plausibleDate(m.group(3))) {
            core = m.group(1); rotated = true; rotation = m.group(2) + "YYYYMMDD";
        } else if ((m = ROT_OLD.matcher(core)).matches()) {
            core = m.group(1); rotated = true; rotation = ".old";
        } else if ((m = ROT_INDEX.matcher(core)).matches()) {
            core = m.group(1); rotated = true; rotation = ".N";
        } else if (core.length() > 1 && core.endsWith("~")) {
            core = core.substring(0, core.length() - 1); rotated = true; rotation = "~";
        }
        String family = shape(core);
        return new NameInfo(family + rotation + compression, family, rotated, !compression.isEmpty());
    }

    private static boolean plausibleDate(String digits) {
        return digits.length() == 8 && (digits.startsWith("19") || digits.startsWith("20"));
    }

    private static String procClass(String word, KeyedTokens tokens) {
        String lower = word.toLowerCase(Locale.ROOT);
        return PROCESSES.contains(lower) ? lower : "p_" + tokens.word(lower);
    }

    /** Words on the stem list stay; other words become X; digit runs become N, NN, NNN, N+, YYYYMMDD or EPOCH. */
    static String shape(String value) {
        StringBuilder shape = new StringBuilder();
        Matcher matcher = WORD_OR_DIGITS.matcher(value);
        while (matcher.find() && shape.length() < 96) {
            String part = matcher.group();
            char first = part.charAt(0);
            if (Character.isDigit(first)) {
                shape.append(digitShape(part));
            } else if (first < 128 && Character.isLetter(first)) {
                String lower = part.toLowerCase(Locale.ROOT);
                shape.append(STEMS.contains(lower) ? lower : "X");
            } else {
                shape.append(first == '.' || first == '_' || first == '-' || first == '+' ? String.valueOf(first) : "?");
            }
        }
        return shape.toString();
    }

    private static String digitShape(String digits) {
        int n = digits.length();
        if (n == 8 && plausibleDate(digits)) return "YYYYMMDD";
        if (n == 10 && digits.startsWith("1")) return "EPOCH";
        return n == 1 ? "N" : n == 2 ? "NN" : n <= 7 ? "NNN" : "N+";
    }

    static String baseName(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    /** A listed directory's class: only the directories this programme reads are named; anything else is "other". */
    static String dirClass(String path) {
        String base = baseName(path).toLowerCase(Locale.ROOT);
        return switch (base) {
            case "sa" -> "sa";
            case "spike_detective" -> "spike_detective";
            case "crash" -> "crash";
            case "usermode" -> "usermode";
            case "last" -> path.contains("/hcp/") ? "hcp_last" : "other";
            default -> "other";
        };
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static List<String> lines(String output) {
        String[] parts = output.split("\\R", -1);
        int n = parts.length;
        while (n > 0 && parts[n - 1].isEmpty()) n--;
        List<String> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(parts[i].replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", ""));
        }
        return result;
    }

    private static int countOf(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) n++;
        return n;
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static Map<String, Integer> capped(Map<String, Integer> counts) {
        if (counts.size() <= MAX_BUCKETS) return counts;
        Map<String, Integer> out = new TreeMap<>();
        int other = 0;
        int kept = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (kept++ < MAX_BUCKETS - 1) out.put(e.getKey(), e.getValue()); else other += e.getValue();
        }
        out.put(OTHER, other);
        return out;
    }

    private static Map<String, Object> viewAll(Map<String, Stats> classes) {
        Map<String, Object> view = new LinkedHashMap<>();
        classes.forEach((name, stats) -> view.put(name, stats.view()));
        return view;
    }

    /** Count, sum, min, max, median and oldest/newest time of one group of entries. */
    private static final class Stats {
        long count, sum, min = Long.MAX_VALUE, max = Long.MIN_VALUE, oldest = Long.MAX_VALUE, newest = Long.MIN_VALUE;
        final List<Long> sizes = new ArrayList<>();

        void add(long size, long time) {
            count++;
            sum += size;
            min = Math.min(min, size);
            max = Math.max(max, size);
            oldest = Math.min(oldest, time);
            newest = Math.max(newest, time);
            sizes.add(size);
        }

        Long median() {
            if (sizes.isEmpty()) return null;
            List<Long> sorted = new ArrayList<>(sizes);
            Collections.sort(sorted);
            return sorted.get((sorted.size() - 1) / 2);
        }

        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("count", count);
            view.put("bytes", sum);
            view.put("minBytes", count == 0 ? null : min);
            view.put("maxBytes", count == 0 ? null : max);
            view.put("medianBytes", median());
            view.put("oldestMtimeEpoch", count == 0 ? null : oldest);
            view.put("newestMtimeEpoch", count == 0 ? null : newest);
            return view;
        }
    }
}
