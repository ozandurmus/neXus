package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code cphaprob stat} (14C §3): HA role and cluster mode, <b>local row
 * only</b> -- this movement never reads or stores a peer's row (that is
 * {@code worker.confirm}'s own peer-follow concern, not inventory's).
 * {@code UNVERIFIED} against a real gateway (proven only in the
 * configuration path per 14C §3).
 */
public final class CheckPointHaStateParser {

    /** Fixed vocabulary (14C §3): VSLS / Load Sharing (multicast or unicast) / HA (or its "new mode") / VRRP. */
    public record HaState(String role, Optional<String> clusterMode) {
    }

    private static final Pattern NOT_A_MEMBER = Pattern.compile("(?i)not\\s+(enabled|installed|a cluster member)");
    private static final Pattern LOCAL_ROW = Pattern.compile("(?im)^.*\\(local\\).*?(ACTIVE|STANDBY|READY|DOWN)\\s*.*$");
    private static final Pattern CLUSTER_MODE_LINE = Pattern.compile("(?im)^\\s*Cluster mode:\\s*(.+?)\\s*$");

    private CheckPointHaStateParser() {
    }

    public static HaState parse(String output) {
        if (output == null || NOT_A_MEMBER.matcher(output).find()) {
            return new HaState("STANDALONE", Optional.empty());
        }
        Matcher local = LOCAL_ROW.matcher(output);
        String role = local.find() ? local.group(1) : "unknown";
        return new HaState(role, clusterModeOf(output));
    }

    private static Optional<String> clusterModeOf(String output) {
        Matcher matcher = CLUSTER_MODE_LINE.matcher(output);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String text = matcher.group(1);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
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
}
