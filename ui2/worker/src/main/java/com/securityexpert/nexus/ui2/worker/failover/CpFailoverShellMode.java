package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Worker switch for the Check Point one-shell path (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3.6,
 * §3.7; PO_DECISION_RECORD_2026_10_22_CP_ONE_SHELL_SESSION.md). A run uses the shell only when {@value #SWITCH} is
 * exactly {@code enabled} AND the run's cluster ref is listed in {@value #CLUSTERS} (comma-separated cluster refs;
 * {@code *} = every cluster; empty or unset = none). Read once at worker start.
 *
 * @param shellPause the pause between commands of a run on the shell path ({@value #PAUSE}, 0..10000 ms, default
 *     1000); it never changes the exec path's pause ({@code UI2_FAILOVER_COMMAND_PAUSE_SECONDS}, review P1-6)
 */
public record CpFailoverShellMode(boolean oneShell, Set<String> clusters, Duration shellPause) {
    public static final String SWITCH = "NEXUS_CP_FAILOVER_ONE_SHELL";
    public static final String CLUSTERS = "NEXUS_CP_FAILOVER_ONE_SHELL_CLUSTERS";
    public static final String PAUSE = "NEXUS_CP_FAILOVER_COMMAND_PAUSE_MS";
    public static final String ALL = "*";
    /** Design default pause between checks on the one shell. */
    public static final Duration SHELL_DEFAULT_PAUSE = Duration.ofMillis(1000);
    public static final CpFailoverShellMode OFF = new CpFailoverShellMode(false, Set.of(), SHELL_DEFAULT_PAUSE);
    /** Review P2-3: a pass with the longest pause stays far inside the gateway's idle and session windows. */
    static final long MAX_PAUSE_MS = 10_000;

    public CpFailoverShellMode {
        clusters = clusters == null ? Set.of() : Set.copyOf(clusters);
        shellPause = shellPause == null ? SHELL_DEFAULT_PAUSE : shellPause;
    }

    /** Test convenience: switch on for every cluster ({@code *}) with the default pause, or {@link #OFF}. */
    public CpFailoverShellMode(boolean oneShell) {
        this(oneShell, oneShell ? Set.of(ALL) : Set.of(), SHELL_DEFAULT_PAUSE);
    }

    public static CpFailoverShellMode fromEnvironment() {
        return fromValues(System.getenv(SWITCH), System.getenv(CLUSTERS), System.getenv(PAUSE));
    }

    public static CpFailoverShellMode fromValues(String switchValue, String clusterList, String pauseMs) {
        boolean on = switchValue != null && "enabled".equals(switchValue.strip());
        Set<String> clusters = clusterList == null ? Set.of() : Arrays.stream(clusterList.split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new CpFailoverShellMode(on, clusters, pause(pauseMs, SHELL_DEFAULT_PAUSE));
    }

    /** Whether a run of {@code clusterRef} uses the shell: switch on and the cluster listed (or {@code *}). */
    public boolean usesShell(String clusterRef) {
        return oneShell && (clusters.contains(ALL) || clusterRef != null && clusters.contains(clusterRef));
    }

    static Duration pause(String pauseMs, Duration fallback) {
        if (pauseMs == null || !pauseMs.strip().matches("[0-9]{1,6}")) return fallback;
        long millis = Long.parseLong(pauseMs.strip());
        return millis > MAX_PAUSE_MS ? fallback : Duration.ofMillis(millis);
    }

    /** Safe for logs: counts only, never the cluster refs (they may name customer clusters). */
    @Override
    public String toString() {
        return "oneShell=" + oneShell + " allClusters=" + clusters.contains(ALL) + " listedClusters="
                + clusters.stream().filter(value -> !ALL.equals(value)).count() + " shellPauseMs=" + shellPause.toMillis();
    }
}
