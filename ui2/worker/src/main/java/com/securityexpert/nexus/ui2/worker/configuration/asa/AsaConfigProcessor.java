package com.securityexpert.nexus.ui2.worker.configuration.asa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;

/** Sanitizes a single-context ASA running configuration before indexing or presentation. */
public final class AsaConfigProcessor {

    private static final System.Logger LOG = System.getLogger(AsaConfigProcessor.class.getName());
    // PO decision 2026-09-27: Configuration keeps these families; backup retains the full running-config.
    private static final Map<String, String> ALLOWED_PREFIXES = Map.ofEntries(
            Map.entry("asa version", "System"), Map.entry("hostname", "System"), Map.entry("domain-name", "System"),
            Map.entry("clock", "System"), Map.entry("boot", "System"), Map.entry("firewall", "System"),
            Map.entry("mode", "System"), Map.entry("console", "System"),
            Map.entry("dns", "DNS"), Map.entry("name-server", "DNS"), Map.entry("ntp", "NTP"),
            Map.entry("ssh", "Management access"), Map.entry("http", "Management access"),
            Map.entry("telnet", "Management access"), Map.entry("management-access", "Management access"),
            Map.entry("banner", "Management access"), Map.entry("password-policy", "Management access"),
            Map.entry("username", "Management access"), Map.entry("enable", "Management access"),
            Map.entry("aaa", "Management access"), Map.entry("aaa-server", "Management access"),
            Map.entry("ssl", "Management access"), Map.entry("snmp-server", "SNMP"),
            Map.entry("logging", "Logging"), Map.entry("failover", "Failover"),
            Map.entry("monitor-interface", "Failover"), Map.entry("prompt", "Failover"),
            Map.entry("interface", "Interfaces"), // mtu: not wanted (PO 2026-09-27)
            Map.entry("route", "Routing"), Map.entry("ipv6 route", "Routing"), Map.entry("router", "Routing"));

    public record Processed(String canonicalHash, int withheldLineCount, String sanitizedText,
            List<ConfigurationIndexEntry> index, int settingCount) {
    }

    private AsaConfigProcessor() {
    }

    public static Processed process(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sanitized = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        String section = null;
        boolean hasChildren = false;
        boolean keep = false;
        int withheld = 0;
        int settings = 0;
        int keptBlocks = 0;
        int droppedBlocks = 0;
        int droppedLines = 0;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.stripTrailing();
            String trimmed = line.strip();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || trimmed.equals("!") || volatileHeader(lower)) {
                continue;
            }
            boolean child = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            if (!child) {
                if (section != null && !hasChildren) {
                    counts.merge(section, 1, Integer::sum);
                    settings++;
                }
                String command = lower.startsWith("no ") ? lower.substring(3) : lower;
                section = ALLOWED_PREFIXES.entrySet().stream()
                        .filter(e -> command.equals(e.getKey()) || command.startsWith(e.getKey() + " "))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                keep = section != null;
                if (keep) keptBlocks++; else droppedBlocks++;
                hasChildren = false;
            } else if (keep) {
                hasChildren = true;
                counts.merge(section, 1, Integer::sum);
                settings++;
            }
            if (!keep) {
                droppedLines++;
                continue;
            }
            if (secret(lower) || trimmed.contains("[withheld]")) {
                withheld++;
                String keyword = trimmed.split("\\s+", 2)[0];
                sanitized.append(child ? " " : "").append(keyword).append(" [withheld]\n");
            } else {
                sanitized.append(line).append('\n');
            }
        }
        if (section != null && !hasChildren) {
            counts.merge(section, 1, Integer::sum);
            settings++;
        }
        List<ConfigurationIndexEntry> index = new ArrayList<>();
        counts.forEach((name, count) -> index.add(new ConfigurationIndexEntry("single", name, Optional.empty(), count)));
        LOG.log(System.Logger.Level.INFO, "[ASA] configuration projection kept blocks={0}, dropped blocks={1}, dropped lines={2}",
                keptBlocks, droppedBlocks, droppedLines);
        String out = sanitized.toString();
        return new Processed(sha256(out), withheld, out, index, settings);
    }

    private static boolean volatileHeader(String lower) {
        return lower.equals(":") || lower.startsWith(": saved") || lower.startsWith(": written by")
                || lower.startsWith(": serial number") || lower.startsWith(": hardware")
                || lower.startsWith("cryptochecksum:");
    }

    private static boolean secret(String lower) {
        return lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("key") || lower.contains("passphrase")
                // SNMP community strings: "snmp-server community <c>" and "snmp-server host <if> <ip> community <c>"
                || (lower.startsWith("snmp-server") && lower.contains("community"))
                // SNMPv3 users carry localized auth/priv keys ("... encrypted auth sha <k> priv aes 128 <k>"); seen
                // unmasked under aiview on 2026-09-27
                || (lower.startsWith("snmp-server user") && (lower.contains(" auth ") || lower.contains(" priv ")))
                || lower.contains(" engineid ");
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
