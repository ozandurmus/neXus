package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The fleet-relative "large backup" rule, server side (Product Owner, 2026-11-04: a 28 GB Check Point backup among
 * about 0.2 GB ones must raise an alert directly). It is the same rule the Backups screen draws as the "Large" marker
 * ({@code ui2/frontend/src/screens/backupSize.ts}: {@code LARGE_BACKUP_MEDIAN_FACTOR}, {@code LARGE_BACKUP_MIN_BYTES},
 * {@code vendorMedianSizes}, {@code largeBackupFlag}), so the screen and the alert agree: a backup is large when it is at
 * least {@link #MEDIAN_FACTOR} times the median of the latest-backup sizes of the devices of its vendor and at least
 * {@link #MIN_BYTES} big.
 *
 * <p>This catches what {@link BackupSizeAnomalyRule} cannot: a device that has always been large is never five times
 * its own past, but it is still far above its fleet. A fleet of one is its own median (factor 1) and never flags, and a
 * zero median never flags rather than being divided by.</p>
 */
public final class FleetLargeBackupRule {

    /** Large: at least this many times the vendor's fleet median ... */
    public static final int MEDIAN_FACTOR = 10;
    /** ... and at least this big (1 GiB), so a small vendor with tiny archives does not flag a modest one. */
    public static final long MIN_BYTES = 1024L * 1024L * 1024L;

    /** A detected large backup: its size, the fleet median it was measured against, the ratio and the fleet size. */
    public record Verdict(String vendor, long newBytes, double medianBytes, double ratio, int fleetDevices) {

        /** Sizes, the ratio and the vendor only (no hostname or other identity). */
        public String summary() {
            return String.format(Locale.ROOT,
                    "Backup size above fleet norm: %.1fx the %s fleet median (%s -> %s, median of %d devices)", ratio,
                    vendor, BackupSizeAnomalyRule.human(Math.round(medianBytes)), BackupSizeAnomalyRule.human(newBytes),
                    fleetDevices);
        }
    }

    private FleetLargeBackupRule() {
    }

    /**
     * @param vendor the vendor whose fleet the backup is measured against
     * @param newBytes the new backup's plaintext size
     * @param fleetLatestSizes the plaintext size of each device's latest backup of that vendor, the new backup's device
     *     included with the new size (one entry per device, any order); a negative size is ignored, as the screen does
     */
    public static Optional<Verdict> evaluate(String vendor, long newBytes, List<Long> fleetLatestSizes) {
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(fleetLatestSizes, "fleetLatestSizes");
        List<Long> sizes = new ArrayList<>(fleetLatestSizes.stream().filter(s -> s != null && s >= 0).toList());
        if (sizes.isEmpty()) {
            return Optional.empty();
        }
        double median = median(sizes);
        if (median <= 0 || newBytes < MIN_BYTES) {
            return Optional.empty();
        }
        double ratio = newBytes / median;
        if (ratio < MEDIAN_FACTOR) {
            return Optional.empty();
        }
        return Optional.of(new Verdict(vendor, newBytes, median, ratio, sizes.size()));
    }

    /** The median as the screen computes it: the middle value, or the mean of the two middle values for an even count. */
    static double median(List<Long> sizes) {
        List<Long> sorted = new ArrayList<>(sizes);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
