package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;

/**
 * Worker switch for the Check Point one-shell path (docs/design/CP_FAILOVER_ONE_SHELL_SESSION_2026_10_22.md §3.6,
 * §3.7). Off unless {@value #SWITCH} is exactly {@code enabled}; read once at worker start.
 */
public record CpFailoverShellMode(boolean oneShell) {
    public static final String SWITCH = "NEXUS_CP_FAILOVER_ONE_SHELL";
    public static final String PAUSE = "NEXUS_CP_FAILOVER_COMMAND_PAUSE_MS";
    public static final CpFailoverShellMode OFF = new CpFailoverShellMode(false);
    /** Design default pause between checks on the one shell. */
    public static final Duration SHELL_DEFAULT_PAUSE = Duration.ofMillis(1000);
    static final long MAX_PAUSE_MS = 60_000;

    public static CpFailoverShellMode fromEnvironment() {
        return fromValue(System.getenv(SWITCH));
    }

    public static CpFailoverShellMode fromValue(String value) {
        return new CpFailoverShellMode(value != null && "enabled".equals(value.strip()));
    }

    /**
     * The pause between consecutive Check Point failover commands: {@value #PAUSE} when it is a whole number of
     * milliseconds in 0..60000; otherwise 1000 ms on the one shell, and the existing {@code legacyPause}
     * ({@code UI2_FAILOVER_COMMAND_PAUSE_SECONDS}) on the exec path, so switch-off behaviour is unchanged.
     */
    public Duration commandPause(String pauseMs, Duration legacyPause) {
        Duration fallback = oneShell ? SHELL_DEFAULT_PAUSE : legacyPause;
        if (pauseMs == null || !pauseMs.strip().matches("[0-9]{1,5}")) return fallback;
        long millis = Long.parseLong(pauseMs.strip());
        return millis > MAX_PAUSE_MS ? fallback : Duration.ofMillis(millis);
    }
}
