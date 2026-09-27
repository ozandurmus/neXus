package com.securityexpert.nexus.ui2.configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PO decision 2026-09-27: Configuration shows curated sections; backup retains the full show. Baseline: what the
 * Check Point / Palo Alto planes show plus what their compliance controls read (ssh-config: SSH ciphers; ddns;
 * log filters: system/config log forwarding; log disk/memory: log quota).
 */
public final class FortiGateConfigurationAllowlist {
    public static final Map<String, String> SECTIONS = sections();

    private static Map<String, String> sections() {
        Map<String, String> sections = new LinkedHashMap<>();
        for (String name : List.of("system global", "system settings", "system console", "system central-management", "system fortiguard", "system ddns")) sections.put(name, "System");
        sections.put("system dns", "DNS");
        sections.put("system ntp", "NTP");
        for (String name : List.of("system admin", "system accprofile", "system password-policy", "system snmp sysinfo", "system snmp community", "system snmp user", "system ssh-config")) sections.put(name, "Management");
        for (String name : List.of("user tacacs+", "user radius", "user ldap")) sections.put(name, "Authentication");
        sections.put("system ha", "High Availability");
        for (String name : List.of("log setting", "log syslogd setting", "log syslogd2 setting", "log syslogd3 setting", "log syslogd4 setting", "log fortianalyzer setting", "log fortianalyzer2 setting", "log fortianalyzer3 setting",
                "log syslogd filter", "log syslogd2 filter", "log syslogd3 filter", "log syslogd4 filter",
                "log fortianalyzer filter", "log fortianalyzer2 filter", "log fortianalyzer3 filter",
                "log disk setting", "log memory setting")) sections.put(name, "Logging");
        for (String name : List.of("system interface", "system zone")) sections.put(name, "Interfaces");
        for (String name : List.of("router static", "router static6", "router bgp", "router ospf")) sections.put(name, "Routing");
        return Map.copyOf(sections);
    }

    public record Filtered(String text, int keptBlocks, int droppedBlocks, int droppedLines) {}

    private static final class Frame {
        final String name;
        final String opening;
        final boolean wrapper;
        boolean emitted;

        Frame(String name, String opening, boolean wrapper, boolean emitted) {
            this.name = name;
            this.opening = opening;
            this.wrapper = wrapper;
            this.emitted = emitted;
        }
    }

    private FortiGateConfigurationAllowlist() {}

    public static Filtered filter(String input) {
        String normalized = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        List<Frame> stack = new ArrayList<>();
        String marker = null;
        boolean quoted = false;
        int kept = 0;
        int dropped = 0;
        for (String raw : normalized.split("\n", -1)) {
            String line = raw.stripTrailing();
            String t = line.strip();
            boolean visible = !stack.isEmpty() && stack.getLast().emitted && !stack.getLast().wrapper;
            if (quoted) {
                if (visible) out.append(line).append('\n');
                if ((quotes(line) & 1) != 0) quoted = false;
                continue;
            }
            if (t.startsWith("#nexus-full-configuration ")) { marker = t; continue; }
            if (t.startsWith("#")) continue;
            if (t.startsWith("config ")) {
                String name = t.substring(7).strip();
                boolean wrapper = stack.isEmpty() && (name.equals("global") || name.equals("vdom"));
                boolean sectionScope = stack.isEmpty() || stack.size() == 1 && stack.getFirst().name.equals("global")
                        || stack.size() == 2 && stack.getFirst().name.equals("vdom") && stack.get(1).name.startsWith("edit ");
                boolean allowed = sectionScope && SECTIONS.containsKey(name);
                if (allowed) {
                    for (Frame frame : stack) {
                        if (!frame.emitted) { out.append(frame.opening).append('\n'); frame.emitted = true; }
                    }
                    if (marker != null) out.append(marker).append('\n');
                    kept++;
                } else if (sectionScope && !wrapper) dropped++;
                Frame frame = new Frame(name, line, wrapper, allowed || (!sectionScope && visible));
                stack.add(frame);
                if (frame.emitted) out.append(line).append('\n');
                marker = null;
            } else if (t.startsWith("edit ")) {
                boolean wrapper = stack.size() == 1 && stack.getFirst().name.equals("vdom");
                Frame frame = new Frame(t, line, wrapper, visible);
                stack.add(frame);
                if (visible) out.append(line).append('\n');
            } else if (t.equals("end") || t.equals("next")) {
                if (!stack.isEmpty()) {
                    boolean closesVdom = t.equals("end") && stack.size() == 2
                            && stack.getFirst().name.equals("vdom") && stack.getLast().name.startsWith("edit ");
                    Frame frame = stack.removeLast();
                    if (frame.emitted) out.append(line).append('\n');
                    if (closesVdom) stack.removeLast();
                }
            } else {
                if (visible) out.append(line).append('\n');
                if (t.startsWith("set ") && (quotes(line) & 1) != 0) quoted = true;
            }
        }
        int outputLines = (int) out.toString().lines().count();
        return new Filtered(out.toString(), kept, dropped, Math.max(0, (int) normalized.lines().count() - outputLines));
    }

    private static int quotes(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\\') i++;
            else if (line.charAt(i) == '"') count++;
        }
        return count;
    }
}
