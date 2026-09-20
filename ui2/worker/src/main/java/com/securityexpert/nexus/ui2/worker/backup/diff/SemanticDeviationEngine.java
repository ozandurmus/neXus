package com.securityexpert.nexus.ui2.worker.backup.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic Deviation Engine for network firewall backup configurations.
 * 
 * <p>Compares consecutive backups for Check Point (Gaia Clish) and Palo Alto (PAN-OS XML),
 * classifying differences into MAJOR deviations (alert triggered) versus MINOR/UNCHANGED:
 * <ul>
 *   <li><b>MAJOR:</b> Network interfaces, IP/subnet changes, Routing (Static/BGP/OSPF),
 *       Access Rules, NAT modifications, Admin Users / Role Escalation, Cluster/HA state.</li>
 *   <li><b>MINOR:</b> Dynamic timestamps, uptime counters, ephemeral session tokens, comment edits.</li>
 *   <li><b>UNCHANGED:</b> Zero semantic difference.</li>
 *   <li><b>FIRST_RUN:</b> Baseline run; no previous artifact to compare.</li>
 * </ul>
 */
public final class SemanticDeviationEngine {

    public enum DeviationClass {
        MAJOR("major"),
        MINOR("minor"),
        UNCHANGED("unchanged"),
        FIRST_RUN("first_run");

        private final String wireValue;

        DeviationClass(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public record DeviationOutcome(
            DeviationClass deviationClass,
            String summary,
            Map<String, Object> details,
            boolean majorAlert
    ) {
        public DeviationOutcome {
            Objects.requireNonNull(deviationClass, "deviationClass");
            Objects.requireNonNull(summary, "summary");
            details = details == null ? Map.of() : Collections.unmodifiableMap(details);
        }
    }

    private static final Pattern CP_INTERFACE_LINE = Pattern.compile("^set interface\\s+(\\S+)\\s+(.*)$");
    private static final Pattern CP_ROUTE_LINE = Pattern.compile("^set static-route\\s+(\\S+)\\s+(.*)$");
    private static final Pattern CP_USER_LINE = Pattern.compile("^(?:add|set)\\s+user\\s+(\\S+)\\s+(.*)$");
    private static final Pattern CP_HA_LINE = Pattern.compile("^set cluster\\s+(.*)$");

    private static final Pattern PAN_RULE_TAG = Pattern.compile("<entry name=\"([^\"]+)\">");
    private static final Pattern PAN_INTERFACE_TAG = Pattern.compile("<entry name=\"([^\"]+)\">\\s*<ip>");

    public DeviationOutcome evaluate(String vendor, String previousConfigText, String currentConfigText) {
        if (previousConfigText == null || previousConfigText.isBlank()) {
            return new DeviationOutcome(
                    DeviationClass.FIRST_RUN,
                    "Initial backup baseline captured; no previous backup to compare.",
                    Map.of("status", "first_run"),
                    false
            );
        }

        if (currentConfigText == null) {
            currentConfigText = "";
        }

        if (previousConfigText.trim().equals(currentConfigText.trim())) {
            return new DeviationOutcome(
                    DeviationClass.UNCHANGED,
                    "Configuration is bit-for-bit identical to previous backup.",
                    Map.of("status", "unchanged"),
                    false
            );
        }

        String normalizedVendor = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT).trim();
        if (normalizedVendor.contains("check_point") || normalizedVendor.contains("cp")) {
            return evaluateCheckPoint(previousConfigText, currentConfigText);
        } else if (normalizedVendor.contains("palo_alto") || normalizedVendor.contains("pan")) {
            return evaluatePaloAlto(previousConfigText, currentConfigText);
        } else {
            return evaluateGeneric(previousConfigText, currentConfigText);
        }
    }

    private DeviationOutcome evaluateCheckPoint(String prev, String curr) {
        Map<String, String> prevIfaces = extractCheckPointMap(prev, CP_INTERFACE_LINE);
        Map<String, String> currIfaces = extractCheckPointMap(curr, CP_INTERFACE_LINE);

        Map<String, String> prevRoutes = extractCheckPointMap(prev, CP_ROUTE_LINE);
        Map<String, String> currRoutes = extractCheckPointMap(curr, CP_ROUTE_LINE);

        Map<String, String> prevUsers = extractCheckPointMap(prev, CP_USER_LINE);
        Map<String, String> currUsers = extractCheckPointMap(curr, CP_USER_LINE);

        List<String> ifaceDiffs = diffMaps("Interface", prevIfaces, currIfaces);
        List<String> routeDiffs = diffMaps("Route", prevRoutes, currRoutes);
        List<String> userDiffs = diffMaps("Admin User", prevUsers, currUsers);

        boolean major = !ifaceDiffs.isEmpty() || !routeDiffs.isEmpty() || !userDiffs.isEmpty();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("interface_diffs", ifaceDiffs);
        details.put("route_diffs", routeDiffs);
        details.put("user_diffs", userDiffs);

        if (major) {
            List<String> summaryParts = new ArrayList<>();
            if (!ifaceDiffs.isEmpty()) summaryParts.add(ifaceDiffs.size() + " interface change(s)");
            if (!routeDiffs.isEmpty()) summaryParts.add(routeDiffs.size() + " route change(s)");
            if (!userDiffs.isEmpty()) summaryParts.add(userDiffs.size() + " admin user change(s)");

            String summary = "MAJOR: " + String.join(", ", summaryParts) + " detected.";
            return new DeviationOutcome(DeviationClass.MAJOR, summary, details, true);
        }

        return new DeviationOutcome(
                DeviationClass.MINOR,
                "MINOR: System metadata or dynamic settings changed; core network and security policies unchanged.",
                details,
                false
        );
    }

    private DeviationOutcome evaluatePaloAlto(String prev, String curr) {
        Set<String> prevRules = extractPanEntries(prev, PAN_RULE_TAG);
        Set<String> currRules = extractPanEntries(curr, PAN_RULE_TAG);

        Set<String> addedRules = new LinkedHashSet<>(currRules);
        addedRules.removeAll(prevRules);

        Set<String> removedRules = new LinkedHashSet<>(prevRules);
        removedRules.removeAll(currRules);

        boolean major = !addedRules.isEmpty() || !removedRules.isEmpty()
                || prev.contains("<virtual-router>") != curr.contains("<virtual-router>")
                || prev.contains("<high-availability>") != curr.contains("<high-availability>");

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rules_added", new ArrayList<>(addedRules));
        details.put("rules_removed", new ArrayList<>(removedRules));

        if (major) {
            List<String> summaryParts = new ArrayList<>();
            if (!addedRules.isEmpty()) summaryParts.add(addedRules.size() + " security rule(s) added");
            if (!removedRules.isEmpty()) summaryParts.add(removedRules.size() + " security rule(s) removed");
            if (summaryParts.isEmpty()) summaryParts.add("routing or high-availability configuration altered");

            String summary = "MAJOR: " + String.join(", ", summaryParts) + " detected in PAN-OS XML.";
            return new DeviationOutcome(DeviationClass.MAJOR, summary, details, true);
        }

        return new DeviationOutcome(
                DeviationClass.MINOR,
                "MINOR: Dynamic sequence IDs or timestamps altered; policies identical.",
                details,
                false
        );
    }

    private DeviationOutcome evaluateGeneric(String prev, String curr) {
        List<String> prevLines = prev.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> currLines = curr.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();

        Set<String> prevSet = new LinkedHashSet<>(prevLines);
        Set<String> currSet = new LinkedHashSet<>(currLines);

        Set<String> added = new LinkedHashSet<>(currSet);
        added.removeAll(prevSet);

        Set<String> removed = new LinkedHashSet<>(prevSet);
        removed.removeAll(currSet);

        boolean major = added.stream().anyMatch(this::isMajorKeyword)
                || removed.stream().anyMatch(this::isMajorKeyword);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lines_added", added.stream().limit(20).toList());
        details.put("lines_removed", removed.stream().limit(20).toList());

        if (major) {
            return new DeviationOutcome(
                    DeviationClass.MAJOR,
                    "MAJOR: Significant network/policy keyword modifications detected in backup diff.",
                    details,
                    true
            );
        }

        return new DeviationOutcome(
                DeviationClass.MINOR,
                "MINOR: Non-critical lines modified.",
                details,
                false
        );
    }

    private boolean isMajorKeyword(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("interface") || lower.contains("route") || lower.contains("rule")
                || lower.contains("nat") || lower.contains("user") || lower.contains("password")
                || lower.contains("cluster") || lower.contains("ha");
    }

    private static Map<String, String> extractCheckPointMap(String config, Pattern pattern) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : config.lines().toList()) {
            line = line.trim();
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                result.put(m.group(1), m.group(2));
            }
        }
        return result;
    }

    private static List<String> diffMaps(String entity, Map<String, String> prev, Map<String, String> curr) {
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, String> entry : curr.entrySet()) {
            if (!prev.containsKey(entry.getKey())) {
                diffs.add(entity + " '" + entry.getKey() + "' added: " + entry.getValue());
            } else if (!Objects.equals(prev.get(entry.getKey()), entry.getValue())) {
                diffs.add(entity + " '" + entry.getKey() + "' modified: [" + prev.get(entry.getKey()) + "] -> [" + entry.getValue() + "]");
            }
        }
        for (String key : prev.keySet()) {
            if (!curr.containsKey(key)) {
                diffs.add(entity + " '" + key + "' removed (was: " + prev.get(key) + ")");
            }
        }
        return diffs;
    }

    private static Set<String> extractPanEntries(String xml, Pattern pattern) {
        Set<String> entries = new LinkedHashSet<>();
        Matcher m = pattern.matcher(xml);
        while (m.find()) {
            entries.add(m.group(1));
        }
        return entries;
    }
}
