package com.securityexpert.nexus.ui2.platform;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Overwatch rename R1b: environment dual-read. A variable the code knows as {@code NEXUS_<X>} (or
 * {@code UI2_<X>}) is read as {@code OVERWATCH_<X>} first and falls back to the name the caller passed. Blank
 * values count as unset. Names with any other prefix are read verbatim. Deploy manifests are not renamed by
 * this class; it only widens what is accepted.
 *
 * <p>At most one INFO line per process is logged, and only when a legacy name actually supplied a value. It names
 * no variable and no value.</p>
 */
public final class OverwatchEnv {

    public static final String OVERWATCH_PREFIX = "OVERWATCH_";
    private static final String[] LEGACY_PREFIXES = {"NEXUS_", "UI2_"};
    private static final AtomicBoolean LEGACY_LOGGED = new AtomicBoolean(false);

    private OverwatchEnv() {
    }

    public static String get(String name) {
        return get(System::getenv, name);
    }

    public static String getOrDefault(String name, String defaultValue) {
        String value = get(name);
        return value != null ? value : defaultValue;
    }

    public static String get(Function<String, String> source, String name) {
        Objects.requireNonNull(source, "source");
        if (name == null || name.isEmpty()) {
            return null;
        }
        String overwatchName = overwatchName(name);
        if (overwatchName != null) {
            String preferred = nonBlank(source.apply(overwatchName));
            if (preferred != null) {
                return preferred;
            }
        }
        String value = nonBlank(source.apply(name));
        if (value != null && overwatchName != null && !name.startsWith(OVERWATCH_PREFIX)) {
            logLegacyOnce();
        }
        return value;
    }

    public static String getOrDefault(Function<String, String> source, String name, String defaultValue) {
        String value = get(source, name);
        return value != null ? value : defaultValue;
    }

    /** Wraps {@code source} so every lookup applies the dual-read. */
    public static Function<String, String> lookup(Function<String, String> source) {
        Objects.requireNonNull(source, "source");
        return name -> get(source, name);
    }

    /** The {@code OVERWATCH_<X>} name for a {@code NEXUS_<X>} or {@code UI2_<X>} name, else {@code null}. */
    static String overwatchName(String name) {
        for (String prefix : LEGACY_PREFIXES) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return OVERWATCH_PREFIX + name.substring(prefix.length());
            }
        }
        return null;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void logLegacyOnce() {
        if (LEGACY_LOGGED.compareAndSet(false, true)) {
            System.getLogger(OverwatchEnv.class.getName()).log(System.Logger.Level.INFO,
                    "Legacy NEXUS_/UI2_ environment names are in use; OVERWATCH_ names are preferred.");
        }
    }
}
