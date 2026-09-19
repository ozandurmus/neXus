package com.securityexpert.nexus.ui2.worker.configuration.cp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigHighlight;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSection;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigSetting;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.core.VendorConfigParser;

/**
 * Byte-for-byte semantic port of Python Check Point configuration collector.
 * Transforms raw Check Point Gaia {@code show configuration} output into canonical,
 * redacted, and structured representations.
 */
public final class CheckPointGaiaConfigParser implements VendorConfigParser {

    public static final List<String> SECTION_ORDER = List.of(
            "system",
            "dns",
            "ntp",
            "management",
            "password_policy",
            "banner",
            "services",
            "logging",
            "high_availability",
            "interfaces",
            "routing",
            "snmp",
            "authentication",
            "other"
    );

    public static final Map<String, String> SECTION_LABELS = Map.ofEntries(
            Map.entry("system", "System"),
            Map.entry("dns", "DNS"),
            Map.entry("ntp", "NTP"),
            Map.entry("management", "Management"),
            Map.entry("password_policy", "Password Policy"),
            Map.entry("banner", "Login Banner"),
            Map.entry("services", "Management Services"),
            Map.entry("logging", "Logging"),
            Map.entry("high_availability", "High Availability"),
            Map.entry("interfaces", "Interfaces"),
            Map.entry("routing", "Routing"),
            Map.entry("snmp", "SNMP"),
            Map.entry("authentication", "Authentication"),
            Map.entry("other", "Other Gaia Configuration")
    );

    public static final Pattern SECRET_LINE_RE = Pattern.compile(
            "(?i)(?:password|passwd|secret|community|credential|token|psk|pre[-_ ]?shared|"
                    + "private[-_ ]?key|api[-_ ]?key|auth(?:entication)?[-_ ]?key|encrypted[-_ ]?secret|"
                    + "(?:^|[\\s_-])key(?:$|[\\s_-]))"
    );

    public static final Pattern PASSWORD_POLICY_SAFE_RE = Pattern.compile(
            "(?i)^set\\s+password-controls\\s+(?:"
                    + "min-password-length|password-min-length|complexity|palindrome-check|history-check|"
                    + "password-history|password-expiration|expiration-warning-days|expiration-lockout-days|"
                    + "deny-on-fail|deny-on-nonuse|force-change-when|password-format"
                    + ")\\b"
    );

    public static final Pattern MESSAGE_BODY_RE = Pattern.compile(
            "(?i)^(set\\s+message\\s+\\S+(?:\\s+(?:on|off))?)\\s+msgvalue\\s+.*$"
    );

    public static final Pattern VSX_SELECTOR_RE = Pattern.compile(
            "(?i)^set\\s+virtual-system\\s+\\d+$"
    );

    public static final String WITHHELD_LINE_MARKER = "# [SECURITYEXPERT SECRET-BEARING CONFIGURATION LINE WITHHELD]";
    public static final String WITHHELD_BANNER_MARKER = "[SECURITYEXPERT BANNER BODY WITHHELD]";

    public static final Set<String> TWO_TOKEN_SECTIONS = Set.of(
            "ssh server", "snmp traps", "bonding group", "aaa radius-servers",
            "installer policy", "ssl tls", "arp table", "ntp server"
    );

    public static String sectionOf(String setLine) {
        String[] tokens = setLine.split("\\s+");
        if (tokens.length < 2) {
            return "unknown";
        }
        if (tokens.length >= 3) {
            String twoToken = tokens[1] + " " + tokens[2];
            if (TWO_TOKEN_SECTIONS.contains(twoToken)) {
                return twoToken;
            }
        }
        return tokens[1];
    }

    @Override
    public ConfigVendor vendor() {
        return ConfigVendor.CHECK_POINT;
    }

    @Override
    public ConfigFormat format() {
        return ConfigFormat.GAIA_CLISH;
    }

    @Override
    public ConfigParseResult parse(ConfigParseContext context, InputStream content) {
        String rawConfig = readAll(content);
        return parseString(context, rawConfig);
    }

    public ConfigParseResult parseString(ConfigParseContext context, String rawConfigurationText) {
        List<String> setLines = extractCanonicalSetLines(rawConfigurationText);
        Optional<String> canonicalHash = setLines.isEmpty()
                ? Optional.empty()
                : Optional.of(sha256Hex(String.join("\n", setLines)));

        List<String> safeLines = new ArrayList<>();
        List<String> safeSetLines = new ArrayList<>();
        int withheld = 0;

        for (String line : setLines) {
            if (isWithheld(line)) {
                withheld++;
                safeLines.add(WITHHELD_LINE_MARKER);
                continue;
            }
            String display = formatDisplayLine(line);
            safeLines.add(display);
            safeSetLines.add(display);
        }

        StringBuilder sanitizedText = new StringBuilder();
        sanitizedText.append("# SecurityExpert Check Point Gaia configuration evidence (redacted)\n");
        sanitizedText.append("# schema=checkpoint-gaia-redacted-v1\n");
        sanitizedText.append("# raw-canonical-sha256=").append(canonicalHash.orElse("unavailable")).append("\n");
        sanitizedText.append("# secret-bearing-lines-withheld=").append(withheld).append("\n");
        for (String safeLine : safeLines) {
            sanitizedText.append(safeLine).append('\n');
        }

        Map<String, List<ConfigSetting>> sectionsMap = new LinkedHashMap<>();
        for (String sectionId : SECTION_ORDER) {
            sectionsMap.put(sectionId, new ArrayList<>());
        }

        String contextLabel = context.contextRef().orElse(null);

        for (String line : safeSetLines) {
            List<String> tokens = safeTokens(line);
            if (tokens.isEmpty()) {
                continue;
            }
            String sectionId = sectionFor(tokens);
            String[] settingValue = parseSettingValue(tokens);
            sectionsMap.get(sectionId).add(ConfigSetting.of(settingValue[0], settingValue[1], "local", contextLabel));
        }

        List<ConfigSection> resultSections = new ArrayList<>();
        int totalSettingsCount = 0;

        for (String sectionId : SECTION_ORDER) {
            List<ConfigSetting> rows = sectionsMap.get(sectionId);
            if (rows.isEmpty()) {
                continue;
            }
            rows.sort(Comparator.comparing((ConfigSetting s) -> s.setting().toLowerCase(Locale.ROOT))
                    .thenComparing(ConfigSetting::value));

            resultSections.add(new ConfigSection(sectionId, SECTION_LABELS.get(sectionId), rows.size(), rows));
            totalSettingsCount += rows.size();
        }

        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        for (String line : setLines) {
            sectionCounts.merge(sectionOf(line), 1, Integer::sum);
        }
        List<ConfigurationIndexEntry> index = sectionCounts.entrySet().stream()
                .map(e -> new ConfigurationIndexEntry(context.entityType(), e.getKey(), Optional.empty(), e.getValue()))
                .toList();

        List<ConfigHighlight> highlights = extractHighlights(sectionsMap);

        return new ConfigParseResult(
                ConfigVendor.CHECK_POINT,
                canonicalHash,
                withheld,
                sanitizedText.toString(),
                index,
                resultSections,
                highlights,
                totalSettingsCount
        );
    }

    public static List<String> extractCanonicalSetLines(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (String line : raw.split("\\R", -1)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("set ")) {
                continue;
            }
            if (VSX_SELECTOR_RE.matcher(trimmed).matches()) {
                continue;
            }
            rows.add(trimmed);
        }
        return rows;
    }

    public static boolean isWithheld(String line) {
        if (PASSWORD_POLICY_SAFE_RE.matcher(line).find()) {
            return false;
        }
        return SECRET_LINE_RE.matcher(line).find();
    }

    public static String formatDisplayLine(String line) {
        Matcher matcher = MESSAGE_BODY_RE.matcher(line);
        if (matcher.matches()) {
            return matcher.replaceFirst("$1 msgvalue " + WITHHELD_BANNER_MARKER);
        }
        return line;
    }

    public static List<String> safeTokens(String line) {
        List<String> rawTokens = splitShell(line);
        if (!rawTokens.isEmpty() && rawTokens.get(0).equalsIgnoreCase("set")) {
            return rawTokens.subList(1, rawTokens.size());
        }
        return List.of();
    }

    public static String sectionFor(List<String> tokens) {
        if (tokens.isEmpty()) {
            return "other";
        }
        String head = tokens.get(0).toLowerCase(Locale.ROOT);
        if (Set.of("hostname", "domainname", "timezone", "time", "clock").contains(head)) {
            return "system";
        }
        if ("password-controls".equals(head)) {
            return "password_policy";
        }
        if (Set.of("message", "banner").contains(head)) {
            return "banner";
        }
        if ("dns".equals(head)) {
            return "dns";
        }
        if ("ntp".equals(head)) {
            return "ntp";
        }
        if (Set.of("interface", "bonding", "bridge", "vlan").contains(head)) {
            return "interfaces";
        }
        if (Set.of("static-route", "route", "routing", "ospf", "bgp", "rip").contains(head)) {
            return "routing";
        }
        if ("snmp".equals(head)) {
            return "snmp";
        }
        if (Set.of("syslog", "log", "logging").contains(head)) {
            return "logging";
        }
        if (Set.of("cluster", "clusterxl", "ha", "high-availability").contains(head)) {
            return "high_availability";
        }
        if (Set.of("user", "aaa", "radius", "tacacs", "ldap", "authentication").contains(head)) {
            return "authentication";
        }
        if (Set.of("web", "ssh", "allowed-client", "management", "inactivity-timeout", "expert-password").contains(head)) {
            return "management";
        }
        return "other";
    }

    public static String[] parseSettingValue(List<String> tokens) {
        if (tokens.isEmpty()) {
            return new String[]{"Setting", "—"};
        }
        String head = tokens.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = tokens.subList(1, tokens.size());

        if ("password-controls".equals(head) && !rest.isEmpty()) {
            String label = "Password · " + pretty(rest.get(0));
            String val = rest.size() > 1 ? String.join(" ", rest.subList(1, rest.size())) : "enabled";
            return new String[]{label, val};
        }
        if ("message".equals(head) && !rest.isEmpty()) {
            String onOff = rest.stream()
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .filter(s -> "on".equals(s) || "off".equals(s))
                    .findFirst()
                    .orElse(null);
            return new String[]{pretty(rest.get(0)), "off".equals(onOff) ? "absent" : "present"};
        }
        if ("banner".equals(head) && !rest.isEmpty()) {
            return new String[]{"Banner", "present"};
        }
        if ("hostname".equals(head)) {
            return new String[]{"Hostname", rest.isEmpty() ? "—" : String.join(" ", rest)};
        }
        if ("domainname".equals(head)) {
            return new String[]{"Domain", rest.isEmpty() ? "—" : String.join(" ", rest)};
        }
        if ("timezone".equals(head)) {
            return new String[]{"Timezone", rest.isEmpty() ? "—" : String.join(" ", rest)};
        }
        if ("dns".equals(head) && !rest.isEmpty()) {
            String qualifier = rest.get(0).toLowerCase(Locale.ROOT);
            String label;
            if ("primary".equals(qualifier)) {
                label = "Primary DNS";
            } else if ("secondary".equals(qualifier)) {
                label = "Secondary DNS";
            } else {
                label = "DNS · " + pretty(qualifier);
            }
            String val = rest.size() > 1 ? String.join(" ", rest.subList(1, rest.size())) : "—";
            return new String[]{label, val};
        }
        if ("ntp".equals(head) && !rest.isEmpty()) {
            List<String> lower = rest.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
            String label;
            List<String> valParts;
            int primIdx = lower.indexOf("primary");
            int secIdx = lower.indexOf("secondary");
            if (primIdx != -1) {
                label = "Primary NTP Server";
                valParts = rest.subList(primIdx + 1, rest.size());
            } else if (secIdx != -1) {
                label = "Secondary NTP Server";
                valParts = rest.subList(secIdx + 1, rest.size());
            } else {
                label = "NTP · " + pretty(rest.get(0));
                valParts = rest.subList(1, rest.size());
            }
            String val = !valParts.isEmpty() ? String.join(" ", valParts) : (rest.isEmpty() ? "—" : String.join(" ", rest));
            return new String[]{label, val};
        }
        if ("interface".equals(head) && !rest.isEmpty()) {
            String iface = rest.get(0);
            String setting = "Interface " + iface;
            if (rest.size() >= 2) {
                setting += " · " + pretty(rest.get(1));
            }
            String val = rest.size() > 2 ? String.join(" ", rest.subList(2, rest.size())) : "—";
            return new String[]{setting, val};
        }
        if (("static-route".equals(head) || "route".equals(head)) && !rest.isEmpty()) {
            String val = rest.size() > 1 ? String.join(" ", rest.subList(1, rest.size())) : "—";
            return new String[]{"Static Route · " + rest.get(0), val};
        }
        if ("snmp".equals(head) && !rest.isEmpty()) {
            String val = rest.size() > 1 ? String.join(" ", rest.subList(1, rest.size())) : "—";
            return new String[]{"SNMP · " + pretty(rest.get(0)), val};
        }
        if (Set.of("syslog", "log", "logging").contains(head) && !rest.isEmpty()) {
            String val = rest.size() > 1 ? String.join(" ", rest.subList(1, rest.size())) : "—";
            return new String[]{pretty(head) + " · " + pretty(rest.get(0)), val};
        }

        List<String> idParts = new ArrayList<>();
        int idCount = Math.min(2, tokens.size());
        for (int i = 0; i < idCount; i++) {
            idParts.add(pretty(tokens.get(i)));
        }
        String identity = String.join(" · ", idParts);
        String value;
        if (tokens.size() > 2) {
            value = String.join(" ", tokens.subList(2, tokens.size()));
        } else if (tokens.size() > 1) {
            value = String.join(" ", tokens.subList(1, tokens.size()));
        } else {
            value = "enabled";
        }
        return new String[]{identity.isEmpty() ? "Setting" : identity, value.isEmpty() ? "—" : value};
    }

    public static List<ConfigHighlight> extractHighlights(Map<String, List<ConfigSetting>> sectionsMap) {
        String[][] wanted = {
                {"system", "Hostname"},
                {"system", "Domain"},
                {"system", "Timezone"},
                {"dns", "Primary DNS"},
                {"dns", "Secondary DNS"},
                {"ntp", "Primary NTP Server"},
                {"ntp", "Secondary NTP Server"}
        };

        List<ConfigHighlight> list = new ArrayList<>();
        for (String[] pair : wanted) {
            String sec = pair[0];
            String label = pair[1];
            List<ConfigSetting> rows = sectionsMap.getOrDefault(sec, List.of());
            for (ConfigSetting s : rows) {
                if (s.setting().equals(label)) {
                    list.add(new ConfigHighlight(label, s.value(), sec, SECTION_LABELS.get(sec)));
                    break;
                }
            }
        }
        return list;
    }

    public static String pretty(String val) {
        if (val == null || val.isEmpty()) {
            return "";
        }
        String[] parts = val.replace('_', '-').split("-");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static List<String> splitShell(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readAll(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read configuration stream", e);
        }
    }
}
