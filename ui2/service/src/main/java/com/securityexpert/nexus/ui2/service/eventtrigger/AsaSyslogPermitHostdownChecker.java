package com.securityexpert.nexus.ui2.service.eventtrigger;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure, stateless, deterministic guard inspecting a stored Cisco ASA {@code running-config}
 * snapshot (from {@code asa_configuration_collect}) for {@code logging permit-hostdown} and
 * {@code logging host} syslog transport posture.
 *
 * <p>Implements the Cisco ASA hard prohibition and {@code FC-6} counts-only fact-check model from
 * {@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} (§1.1 Q2 item 4, §3.2 FC-6)
 * and enforces {@code AGENTS.md} raw-evidence and fail-closed laws:
 * <ul>
 *   <li>Direct Cisco ASA TCP or TLS syslog to Overwatch is permitted <strong>if and only if</strong>
 *       top-level {@code logging permit-hostdown} is verified {@link PermitHostdownStatus#PRESENT}
 *       in the stored {@code running-config}.</li>
 *   <li>Null, blank, truncated, or unreadable configs fail closed to
 *       {@link PermitHostdownStatus#UNKNOWN} with {@code directTcpOrTlsPermitted == false}.</li>
 *   <li>Zero raw IP addresses, interface names, hostnames, ports, or configuration lines are
 *       retained, returned, or logged; only closed enums, booleans, and counts are emitted.</li>
 * </ul>
 */
public final class AsaSyslogPermitHostdownChecker {

    /** Upper bound on an assessed config (64 MiB, as in the FC-6 measurement harness). */
    static final int MAX_CONFIG_CHARS = 64 * 1024 * 1024;

    private AsaSyslogPermitHostdownChecker() {
    }

    /** Closed verification state of {@code logging permit-hostdown} in a unit's stored config. */
    public enum PermitHostdownStatus {
        PRESENT,
        DISABLED,
        ABSENT,
        UNKNOWN
    }

    /** Closed classification of configured ASA {@code logging host} syslog transport. */
    public enum SyslogTransport {
        UDP,
        TCP,
        TLS_SECURE,
        UNKNOWN
    }

    /**
     * Counts-only, raw-evidence-free assessment for a single Cisco ASA {@code running-config}.
     *
     * <p>{@code directTcpOrTlsPermitted} is enforced {@code true} if and only if
     * {@code permitHostdown == PermitHostdownStatus.PRESENT}.</p>
     */
    public record Assessment(
            PermitHostdownStatus permitHostdown,
            Set<SyslogTransport> configuredTransports,
            int loggingHostLineCount,
            boolean directTcpOrTlsPermitted) {

        public Assessment {
            permitHostdown = permitHostdown == null ? PermitHostdownStatus.UNKNOWN : permitHostdown;
            configuredTransports = copyTransports(configuredTransports);
            if (loggingHostLineCount < 0) {
                throw new IllegalArgumentException("loggingHostLineCount must be >= 0");
            }
            directTcpOrTlsPermitted = (permitHostdown == PermitHostdownStatus.PRESENT);
        }

        private static Set<SyslogTransport> copyTransports(Set<SyslogTransport> source) {
            if (source == null || source.isEmpty()) {
                return Set.of();
            }
            EnumSet<SyslogTransport> copy = EnumSet.noneOf(SyslogTransport.class);
            for (SyslogTransport transport : source) {
                copy.add(transport == null ? SyslogTransport.UNKNOWN : transport);
            }
            return Collections.unmodifiableSet(copy);
        }
    }

    /**
     * Fleet-wide {@code FC-6} counts-only aggregation across assessed Cisco ASA units.
     *
     * <p>{@code asaDirectTcpTlsSafe} is {@code true} if and only if {@code asaTotalCount > 0} and
     * every assessed unit has {@link PermitHostdownStatus#PRESENT}.</p>
     */
    public record FleetSummary(
            int asaTotalCount,
            int asaPermitHostdownPresentCount,
            int asaPermitHostdownAbsentCount,
            int asaPermitHostdownDisabledCount,
            int asaPermitHostdownUnknownCount,
            boolean asaDirectTcpTlsSafe,
            Map<SyslogTransport, Integer> transportUnitCounts) {

        public FleetSummary {
            if (asaTotalCount < 0
                    || asaPermitHostdownPresentCount < 0
                    || asaPermitHostdownAbsentCount < 0
                    || asaPermitHostdownDisabledCount < 0
                    || asaPermitHostdownUnknownCount < 0) {
                throw new IllegalArgumentException("FleetSummary counts must be >= 0");
            }
            long classified = (long) asaPermitHostdownPresentCount
                    + asaPermitHostdownAbsentCount
                    + asaPermitHostdownDisabledCount
                    + asaPermitHostdownUnknownCount;
            if (classified != asaTotalCount) {
                throw new IllegalArgumentException(
                        "FleetSummary asaTotalCount must equal present + absent + disabled + unknown");
            }
            asaDirectTcpTlsSafe = asaTotalCount > 0 && asaPermitHostdownPresentCount == asaTotalCount;
            transportUnitCounts = normalizeTransportCounts(transportUnitCounts);
        }

        private static Map<SyslogTransport, Integer> normalizeTransportCounts(
                Map<SyslogTransport, Integer> source) {
            EnumMap<SyslogTransport, Integer> normalized = new EnumMap<>(SyslogTransport.class);
            for (SyslogTransport transport : SyslogTransport.values()) {
                int count = 0;
                if (source != null && source.containsKey(transport)) {
                    Integer value = source.get(transport);
                    if (value == null || value < 0) {
                        throw new IllegalArgumentException(
                                "Transport unit count must be non-null and >= 0 for " + transport);
                    }
                    count = value;
                }
                normalized.put(transport, count);
            }
            return Collections.unmodifiableMap(normalized);
        }
    }

    /**
     * Inspects a stored Cisco ASA {@code running-config} string and returns a raw-evidence-free
     * {@link Assessment}.
     */
    public static Assessment assess(String runningConfig) {
        if (runningConfig == null
                || runningConfig.length() > MAX_CONFIG_CHARS
                || runningConfig.isBlank()
                || isTruncatedOrUnreadable(runningConfig)) {
            return new Assessment(PermitHostdownStatus.UNKNOWN, Set.of(), 0, false);
        }

        if (!isVerifiedSingleContextAsaConfig(runningConfig)) {
            return new Assessment(PermitHostdownStatus.UNKNOWN, Set.of(), 0, false);
        }

        PermitHostdownStatus permitStatus = PermitHostdownStatus.ABSENT;
        EnumSet<SyslogTransport> transports = EnumSet.noneOf(SyslogTransport.class);
        int loggingHostLineCount = 0;

        String[] lines = runningConfig.split("\\r?\\n", -1);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            // Ignore indented sub-mode blocks and '!' comment lines.
            if (Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("!")) {
                continue;
            }

            String[] tokens = trimmed.split("\\s+");
            if (isPermitHostdownPresentDirective(tokens)) {
                permitStatus = PermitHostdownStatus.PRESENT;
            } else if (isPermitHostdownDisabledDirective(tokens)) {
                permitStatus = PermitHostdownStatus.DISABLED;
            } else if (isLoggingHostDirective(tokens)) {
                loggingHostLineCount++;
                transports.add(classifyLoggingHostTransport(tokens));
            }
        }

        return new Assessment(
                permitStatus,
                transports,
                loggingHostLineCount,
                permitStatus == PermitHostdownStatus.PRESENT);
    }

    /**
     * Aggregates per-unit {@link Assessment} results into an {@code FC-6} counts-only
     * {@link FleetSummary}.
     */
    public static FleetSummary summarize(Collection<Assessment> assessments) {
        EnumMap<SyslogTransport, Integer> transportUnitCounts = new EnumMap<>(SyslogTransport.class);
        for (SyslogTransport transport : SyslogTransport.values()) {
            transportUnitCounts.put(transport, 0);
        }

        if (assessments == null || assessments.isEmpty()) {
            return new FleetSummary(0, 0, 0, 0, 0, false, transportUnitCounts);
        }

        int total = 0;
        int present = 0;
        int absent = 0;
        int disabled = 0;
        int unknown = 0;

        for (Assessment assessment : assessments) {
            total++;
            if (assessment == null) {
                unknown++;
                continue;
            }
            switch (assessment.permitHostdown()) {
                case PRESENT -> present++;
                case ABSENT -> absent++;
                case DISABLED -> disabled++;
                case UNKNOWN -> unknown++;
            }
            for (SyslogTransport transport : assessment.configuredTransports()) {
                SyslogTransport key = transport == null ? SyslogTransport.UNKNOWN : transport;
                transportUnitCounts.merge(key, 1, Integer::sum);
            }
        }

        return new FleetSummary(
                total,
                present,
                absent,
                disabled,
                unknown,
                total > 0 && present == total,
                transportUnitCounts);
    }

    /**
     * Positive ASA anchors: a top-level {@code ASA Version} line and a final {@code : end} line,
     * with no multi-context markers. Anything else is not verified and must not yield ABSENT.
     */
    private static boolean isVerifiedSingleContextAsaConfig(String runningConfig) {
        boolean asaVersion = false;
        String lastNonBlank = "";
        for (String line : runningConfig.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            lastNonBlank = trimmed;
            if (Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            String first = tokens[0].toLowerCase(Locale.ROOT);
            if (first.equals("asa") && tokens.length >= 2 && "version".equalsIgnoreCase(tokens[1])) {
                asaVersion = true;
            } else if (first.equals("context") || first.equals("admin-context")
                    || (first.equals("mode") && tokens.length >= 2
                            && "multiple".equalsIgnoreCase(tokens[1]))) {
                return false;
            }
        }
        return asaVersion && lastNonBlank.equals(": end");
    }

    private static boolean isTruncatedOrUnreadable(String runningConfig) {
        for (int i = 0; i < runningConfig.length(); i++) {
            char ch = runningConfig.charAt(i);
            if (ch == '\uFFFD') {
                return true;
            }
            if (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') {
                return true;
            }
        }
        String[] lines = runningConfig.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("--more--")
                    || lower.equals("[truncated]")
                    || lower.equals("--- truncated ---")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPermitHostdownPresentDirective(String[] tokens) {
        return tokens.length == 2
                && "logging".equalsIgnoreCase(tokens[0])
                && "permit-hostdown".equalsIgnoreCase(tokens[1]);
    }

    private static boolean isPermitHostdownDisabledDirective(String[] tokens) {
        return tokens.length == 3
                && "no".equalsIgnoreCase(tokens[0])
                && "logging".equalsIgnoreCase(tokens[1])
                && "permit-hostdown".equalsIgnoreCase(tokens[2]);
    }

    private static boolean isLoggingHostDirective(String[] tokens) {
        return tokens.length >= 2
                && "logging".equalsIgnoreCase(tokens[0])
                && "host".equalsIgnoreCase(tokens[1]);
    }

    /**
     * Classifies a top-level {@code logging host <ifname> <ip> ...} line into a closed
     * {@link SyslogTransport} without retaining any interface name, IP, or port token.
     */
    private static SyslogTransport classifyLoggingHostTransport(String[] tokens) {
        if (tokens.length < 4) {
            return SyslogTransport.UNKNOWN;
        }

        ProtocolFamily protocol = null;
        boolean hasSecure = false;

        for (int i = 4; i < tokens.length; i++) {
            String token = tokens[i];
            if ("format".equalsIgnoreCase(token)) {
                if (i + 1 >= tokens.length) {
                    return SyslogTransport.UNKNOWN;
                }
                i++;
                continue;
            }
            if ("secure".equalsIgnoreCase(token)) {
                hasSecure = true;
                continue;
            }

            ProtocolFamily parsedProtocol = parseProtocolToken(token);
            if (parsedProtocol == null || protocol != null) {
                return SyslogTransport.UNKNOWN;
            }
            protocol = parsedProtocol;
        }

        if (hasSecure) {
            return protocol == ProtocolFamily.TCP ? SyslogTransport.TLS_SECURE : SyslogTransport.UNKNOWN;
        }
        if (protocol == ProtocolFamily.TCP) {
            return SyslogTransport.TCP;
        }
        return SyslogTransport.UDP;
    }

    private enum ProtocolFamily {
        UDP,
        TCP
    }

    private static ProtocolFamily parseProtocolToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.equals("tcp")) {
            return ProtocolFamily.TCP;
        }
        if (lower.equals("udp")) {
            return ProtocolFamily.UDP;
        }
        if (lower.startsWith("6/") || lower.startsWith("tcp/")) {
            String portPart = lower.substring(lower.indexOf('/') + 1);
            return isValidPortNumber(portPart) ? ProtocolFamily.TCP : null;
        }
        if (lower.startsWith("17/") || lower.startsWith("udp/")) {
            String portPart = lower.substring(lower.indexOf('/') + 1);
            return isValidPortNumber(portPart) ? ProtocolFamily.UDP : null;
        }
        return null;
    }

    private static boolean isValidPortNumber(String portPart) {
        if (portPart.isEmpty() || portPart.length() > 5) {
            return false;
        }
        int port = 0;
        for (int i = 0; i < portPart.length(); i++) {
            char c = portPart.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            port = port * 10 + (c - '0');
        }
        return port >= 1 && port <= 65535;
    }
}
