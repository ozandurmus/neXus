package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code cphaprob stat} (14D CF-3, PR-4): local member's HA role and
 * cluster mode -- this movement never reads or stores a peer's row (that
 * is {@code worker.confirm}'s own peer-follow concern, not inventory's).
 * The two measured mode strings are {@code High Availability (Active Up)
 * with IGMP Membership} and {@code Virtual System Load Sharing (Active
 * Up)}; on VSLS, the physical-context read also carries a {@code Virtual
 * Devices Status on each Cluster Member} table giving every virtual
 * system's role on every member without ever running {@code vsenv} --
 * {@link #perVsidLocalRole()} exposes that table's {@code [local]}-marked
 * column, parsed once here rather than re-derived per VS (no per-VSID
 * {@code cphaprob stat} re-read is ever parsed). {@code
 * InventoryCapabilityExecutor} persists the physical role and every
 * per-VSID role from {@link #perVsidLocalRole()} into {@code
 * device_inventory_ha} (migration V17), one row per context.
 */
public final class CheckPointHaStateParser {

    public record HaState(String role, Optional<String> clusterMode, Map<String, String> perVsidLocalRole) {
    }

    private static final Pattern NOT_A_MEMBER = Pattern.compile("(?i)not\\s+(enabled|installed|a cluster member)");
    private static final Pattern LOCAL_ROW = Pattern.compile("(?im)^.*\\(local\\).*?(ACTIVE|STANDBY|READY|DOWN)\\s*.*$");
    private static final Pattern CLUSTER_MODE_LINE = Pattern.compile("(?im)^\\s*Cluster mode:\\s*(.+?)\\s*$");
    private static final Pattern VSLS_SECTION_HEADER =
            Pattern.compile("(?i)virtual devices status on each cluster member");
    private static final Pattern LOCAL_COLUMN_MARKER = Pattern.compile("(?i)\\[local]");
    private static final Pattern STATE_TOKEN = Pattern.compile("(?i)(ACTIVE|STANDBY|READY|DOWN)");

    private CheckPointHaStateParser() {
    }

    public static HaState parse(String output) {
        if (output == null || NOT_A_MEMBER.matcher(output).find()) {
            return new HaState("STANDALONE", Optional.empty(), Map.of());
        }
        Matcher local = LOCAL_ROW.matcher(output);
        String role = local.find() ? local.group(1) : "unknown";
        return new HaState(role, clusterModeOf(output), perVsidLocalRoleOf(output));
    }

    private static Optional<String> clusterModeOf(String output) {
        Matcher matcher = CLUSTER_MODE_LINE.matcher(output);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String text = matcher.group(1);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("virtual system load sharing")) {
            return Optional.of("Virtual System Load Sharing");
        }
        if (lower.contains("load sharing") && lower.contains("unicast")) {
            return Optional.of("Load Sharing unicast");
        }
        if (lower.contains("load sharing") && lower.contains("multicast")) {
            return Optional.of("Load Sharing multicast");
        }
        if (lower.contains("vrrp")) {
            return Optional.of("VRRP");
        }
        if (lower.contains("high availability")) {
            return Optional.of("High Availability");
        }
        return Optional.of(text);
    }

    /**
     * PR-4's VSLS table: a header row whose columns include one marked
     * {@code [local]} (case-insensitive) fixes which member-column is the
     * local one; each following {@code <VSID> | ... | <state>} row (pipe-
     * delimited, tolerant of the exact column count) contributes that
     * VSID's role at the local column. Stops at the first row after the
     * header that carries no {@code |} (the totals/legend block).
     */
    private static Map<String, String> perVsidLocalRoleOf(String output) {
        Map<String, String> roles = new LinkedHashMap<>();
        String[] lines = output.split("\\R");
        int i = 0;
        for (; i < lines.length; i++) {
            if (VSLS_SECTION_HEADER.matcher(lines[i]).find()) {
                i++;
                break;
            }
        }
        int localColumn = -1;
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.contains("|")) {
                return roles;
            }
            String[] columns = line.split("\\|");
            for (int c = 0; c < columns.length; c++) {
                if (LOCAL_COLUMN_MARKER.matcher(columns[c]).find()) {
                    localColumn = c;
                }
            }
            i++;
            break;
        }
        if (localColumn < 0) {
            return roles;
        }
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.contains("|")) {
                break;
            }
            String[] columns = line.split("\\|");
            if (columns.length <= localColumn) {
                continue;
            }
            String vsid = columns[0].trim();
            if (!vsid.matches("\\d+")) {
                continue;
            }
            Matcher state = STATE_TOKEN.matcher(columns[localColumn]);
            if (state.find()) {
                roles.put(vsid, state.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return roles;
    }
}
