package com.securityexpert.nexus.ui2.platform;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shape measurement only; this does not establish vendor metric semantics. No raw values survive. */
public final class CpviewProjection {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> COUNTERS = Set.of("throughput in", "throughput out", "packets/s", "drops",
            "cpu %", "concurrent connections", "new connections/s");
    private static final Set<String> SECTIONS = Set.of("traffic", "cpu", "connections", "performance", "summary");
    private static final Pattern NUMBER = Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*(%|bps|Kbps|Mbps|Gbps|B/s|KB/s|MB/s|GB/s|pps|packets/s|connections/s)?");
    private static final Pattern SCOPE = Pattern.compile("(?i)\\b(?:vs|vsid|vsys|vsx|virtual system|context)\\b");

    public static String project(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("OUTPUT_LIMIT_EXCEEDED");
        StringBuilder out = new StringBuilder("projection: cpview_measurement\n");
        out.append("scope_marker_present: ").append(SCOPE.matcher(raw).find()).append('\n');
        int sections = 0, fields = 0;
        for (String line : raw.split("\\R")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0 || colon == line.length() - 1) {
                String name = (colon < 0 ? line : line.substring(0, colon)).replaceAll("^\\[|\\]$", "").strip().toLowerCase(Locale.ROOT);
                out.append("section: ").append(SECTIONS.contains(name) ? name : "MASKED_SECTION_" + ++sections).append('\n');
                continue;
            }
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            var number = NUMBER.matcher(value);
            boolean numeric = number.matches();
            boolean allowed = COUNTERS.contains(name);
            out.append("field: ").append(allowed ? name : "MASKED_FIELD_" + ++fields)
                .append("; type: ").append(numeric ? "NUMBER" : value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") ? "BOOLEAN" : "TEXT")
                .append("; unit: ").append(numeric && number.group(2) != null ? number.group(2) : "UNKNOWN");
            if (allowed && numeric) out.append("; value: ").append(number.group(1));
            out.append('\n');
        }
        return out.toString();
    }
    private CpviewProjection() {}
}
