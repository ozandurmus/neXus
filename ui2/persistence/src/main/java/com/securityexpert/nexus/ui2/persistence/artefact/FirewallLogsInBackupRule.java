package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Check Point firewall log files inside a standard backup (Product Owner, 2026-11-04). A Gaia backup that sweeps in the
 * local firewall logs grows by hundreds of GiB; the measured estate had 47 of 56 latest Check Point backups carrying
 * them, 434 GiB of 605 GiB listed, the largest 270 GiB. The finding comes from the stored content listing
 * ({@code backup_artefact_entry}: path, type, size; never content), so it is raised when that listing is stored.
 *
 * <p>A backup raises the finding when the listed files under the firewall log directory total at least
 * {@link #MIN_BYTES}. Only sizes and a percentage are reported: no file name is ever printed.</p>
 */
public final class FirewallLogsInBackupRule {

    /**
     * The Check Point firewall log directory inside a backup archive: {@code fw1/log/} under {@code opt/CPsuite-<version>},
     * e.g. {@code opt/CPsuite-R81.20/fw1/log/}. One pattern for both PostgreSQL ({@code ~}) and Java; it uses only syntax
     * common to both, and is anchored on a path-segment boundary so a leading {@code ./} or {@code /} still matches.
     */
    public static final String LOG_PATH_REGEX = "(^|/)opt/CPsuite-[^/]+/fw1/log/";
    public static final Pattern LOG_PATH = Pattern.compile(LOG_PATH_REGEX);
    /** The combined size of the log files at which a backup raises the finding (100 MiB). */
    public static final long MIN_BYTES = 100L * 1024L * 1024L;

    private static final double GIB = 1024d * 1024d * 1024d;

    /** The finding: the bytes of log files, the bytes of all listed files, and the share. */
    public record Verdict(long logBytes, long totalBytes, double percent) {

        /** Sizes and a percentage only, e.g. "Backup contains 270.0 GiB of firewall logs (44.6% of the archive contents)". */
        public String summary() {
            return String.format(Locale.ROOT, "Backup contains %.1f GiB of firewall logs (%.1f%% of the archive contents)",
                    logBytes / GIB, percent);
        }
    }

    private FirewallLogsInBackupRule() {
    }

    public static boolean isLogPath(String entryPath) {
        return entryPath != null && LOG_PATH.matcher(entryPath).find();
    }

    /**
     * @param logBytes the combined size of the listed files under the firewall log directory
     * @param totalBytes the combined size of every listed file
     */
    public static Optional<Verdict> evaluate(long logBytes, long totalBytes) {
        if (logBytes < MIN_BYTES) {
            return Optional.empty();
        }
        double percent = totalBytes <= 0 ? 100d : Math.min(100d, 100d * logBytes / totalBytes);
        return Optional.of(new Verdict(logBytes, Math.max(totalBytes, logBytes), percent));
    }
}
