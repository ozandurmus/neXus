package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ip -o link show} (gate {@code cp_inventory_ip_link_show}, V148; PO 2026-10-15): interface name to the
 * link-layer address the kernel reports for it. Only {@code link/ether} values are read; a loopback, tunnel or
 * {@code link/none} interface has no address and is simply absent from the result, never guessed. The all-zero
 * address is treated the same way. Both the one-line ({@code -o}, records joined by a backslash) and the
 * multi-line form are accepted, since an interactive shell may present either. A VLAN or bond member printed as
 * {@code eth1.100@eth1} is keyed by its own name, {@code eth1.100}.
 */
public final class CheckPointIpLinkParser {

    private static final Pattern HEADER = Pattern.compile("^\\s*\\d+:\\s+([^:\\s@]+)(?:@[^:\\s]+)?:\\s");
    private static final Pattern ETHER = Pattern.compile("link/ether\\s+([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\b");
    private static final String ALL_ZERO = "00:00:00:00:00:00";

    private CheckPointIpLinkParser() {
    }

    public static Map<String, String> parse(String output) {
        Map<String, String> macByName = new LinkedHashMap<>();
        if (output == null || output.isBlank()) {
            return macByName;
        }
        String current = null;
        for (String rawLine : output.split("\\R")) {
            for (String segment : rawLine.split("\\\\")) {
                Matcher header = HEADER.matcher(segment);
                if (header.find()) {
                    current = header.group(1);
                    // A one-line record carries its link/ether on the same segment after the flags.
                    segment = segment.substring(header.end());
                }
                Matcher ether = ETHER.matcher(segment);
                if (current != null && ether.find()) {
                    String mac = ether.group(1).toLowerCase(Locale.ROOT);
                    if (!ALL_ZERO.equals(mac)) {
                        macByName.putIfAbsent(current, mac);
                    }
                }
            }
        }
        return macByName;
    }
}
