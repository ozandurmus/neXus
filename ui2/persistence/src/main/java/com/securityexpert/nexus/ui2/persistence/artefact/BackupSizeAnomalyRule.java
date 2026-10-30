package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The backup size anomaly thresholds, in one place (PO decision 2026-10-30,
 * docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md): "if the previous one is 1 GB and this one 26 GB, call it an anomaly
 * and do not delete the last 1 GB backup, in case we need to go back; seven bad copies give us nothing."
 *
 * <p>A new standard backup is a size anomaly when, against the median plaintext size of the device's last
 * {@link #HISTORY} non-anomalous live standard backups, it is</p>
 * <ul>
 *   <li>growth: at least {@link #GROWTH_FACTOR} times the median and at least {@link #MIN_CHANGE_BYTES} larger, or</li>
 *   <li>shrink (possibly truncated): at most {@link #SHRINK_PERCENT} percent of the median and at least
 *       {@link #MIN_CHANGE_BYTES} smaller.</li>
 * </ul>
 * With fewer than {@link #HISTORY} earlier backups there is no normal to compare with, so nothing is an anomaly.
 * The floor keeps small devices (a 10 MB backup growing to 60 MB) out of it.
 */
public final class BackupSizeAnomalyRule {

    /** How many earlier non-anomalous backups make the device's normal size. */
    public static final int HISTORY = 3;
    /** Growth: the new backup is at least this many times the median. */
    public static final long GROWTH_FACTOR = 5;
    /** Shrink: the new backup is at most this percentage of the median. */
    public static final long SHRINK_PERCENT = 20;
    /** Either way the size must change by at least 1 GiB. */
    public static final long MIN_CHANGE_BYTES = 1024L * 1024L * 1024L;

    public enum Direction {
        GROWTH, SHRINK;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A detected anomaly: its direction, the median it was compared with, and new / median. */
    public record Verdict(Direction direction, long newBytes, long medianBytes, double ratio) {

        /** Sizes and the ratio only (no hostname or other identity), e.g. "grew 26.0x: 1.1 GB -> 28.6 GB". */
        public String summary() {
            return String.format(Locale.ROOT, "Backup size anomaly: %s %.1fx (%s -> %s, median of the last %d backups)",
                    direction == Direction.GROWTH ? "grew" : "shrank to", ratio, human(medianBytes), human(newBytes),
                    HISTORY);
        }
    }

    private BackupSizeAnomalyRule() {
    }

    /**
     * @param newBytes the new backup's plaintext size
     * @param history the plaintext sizes of the device's previous non-anomalous live standard backups (any order)
     */
    public static Optional<Verdict> evaluate(long newBytes, List<Long> history) {
        Objects.requireNonNull(history, "history");
        if (history.size() < HISTORY || newBytes < 0) {
            return Optional.empty();
        }
        long median = median(history.subList(0, HISTORY));
        if (median <= 0) {
            return Optional.empty();
        }
        double ratio = (double) newBytes / (double) median;
        if (newBytes >= GROWTH_FACTOR * median && newBytes - median >= MIN_CHANGE_BYTES) {
            return Optional.of(new Verdict(Direction.GROWTH, newBytes, median, ratio));
        }
        if (newBytes * 100 <= SHRINK_PERCENT * median && median - newBytes >= MIN_CHANGE_BYTES) {
            return Optional.of(new Verdict(Direction.SHRINK, newBytes, median, ratio));
        }
        return Optional.empty();
    }

    static long median(List<Long> sizes) {
        List<Long> sorted = new ArrayList<>(sizes);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    static String human(long bytes) {
        double gib = bytes / (double) MIN_CHANGE_BYTES;
        if (gib >= 1) {
            return String.format(Locale.ROOT, "%.1f GB", gib);
        }
        return String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0));
    }
}
