package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Every tunable of the planner in one place, so that closing a PROVISIONAL parameter of contract section 11 is a
 * change of {@link #defaults()} (or of the value the executor passes), not of planner logic.
 *
 * <p>Status of each default (contract sections 3.4, 4.3, 4.6, 4.7, 11):</p>
 * <ul>
 * <li>{@code readCapBytes} 8 MiB, {@code sourceBudgetBytes} 32 MiB, {@code deviceBudgetBytes} 64 MiB:
 *     PROVISIONAL. A too-small value only produces {@code BACKLOG}.</li>
 * <li>{@code backfillBytes} 8 MiB: DECIDED (PO 2026-10-17, section 13 Q3).</li>
 * <li>{@code anchorBytes} 64: design (section 4.1).</li>
 * <li>{@code siblingSuffixRegex}: PROVISIONAL. The read-eligible name is {@code ^<base><suffix>$}; the contract's
 *     default suffix is {@code (\.[0-9]{1,2})?}. Every name outside it, {@code .gz} included, is listed and never
 *     read.</li>
 * <li>{@code compressedSuffix} {@code .gz}: a sibling with this suffix is never read (section 4.6).</li>
 * <li>{@code fastRotatingHorizonHours} 9: the 6 h interval plus a 50 % margin (section 4.6).</li>
 * <li>{@code yearInferenceSlackDays} 1: the device time zone is not known, so a year-less log date up to one day
 *     after the device clock's UTC date is still taken as "not after the clock" (section 4.7).</li>
 * </ul>
 *
 * @param readCapBytes per-read payload cap; the transport range is {@code anchorBytes + readCapBytes} (8388672 B)
 * @param sourceBudgetBytes payload bytes one source may read per run
 * @param deviceBudgetBytes payload bytes one device may read per run across all incremental sources
 * @param backfillBytes first-run window: the last this-many bytes of each live file
 * @param anchorBytes length K of the fingerprint region before the cursor offset
 * @param siblingSuffixRegex regex for the part of a file name after the base name that makes a sibling readable
 * @param compressedSuffix suffix of a compressed rotation
 * @param fastRotatingHorizonHours retention horizon under which a source is flagged FAST_ROTATING
 * @param yearInferenceSlackDays slack for the year inference of year-less line timestamps
 */
public record PlannerConfig(
        long readCapBytes,
        long sourceBudgetBytes,
        long deviceBudgetBytes,
        long backfillBytes,
        int anchorBytes,
        String siblingSuffixRegex,
        String compressedSuffix,
        int fastRotatingHorizonHours,
        int yearInferenceSlackDays) {

    public static final long MIB = 1024L * 1024L;

    public PlannerConfig {
        if (readCapBytes <= 0 || sourceBudgetBytes <= 0 || deviceBudgetBytes <= 0 || backfillBytes <= 0) {
            throw new IllegalArgumentException("caps, budgets and backfill must be positive");
        }
        if (anchorBytes < 1 || anchorBytes > 4096) {
            throw new IllegalArgumentException("anchorBytes must be within 1..4096");
        }
        if (fastRotatingHorizonHours <= 0 || yearInferenceSlackDays < 0) {
            throw new IllegalArgumentException("horizon must be positive and slack non-negative");
        }
        if (siblingSuffixRegex == null || compressedSuffix == null || compressedSuffix.isEmpty()) {
            throw new IllegalArgumentException("sibling suffix regex and compressed suffix are required");
        }
        try {
            Pattern.compile(siblingSuffixRegex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("siblingSuffixRegex does not compile", e);
        }
    }

    /** The contract defaults (see the class comment for the status of each). */
    public static PlannerConfig defaults() {
        return new PlannerConfig(8 * MIB, 32 * MIB, 64 * MIB, 8 * MIB, 64, "(\\.[0-9]{1,2})?", ".gz", 9, 1);
    }

    /** The read-eligible name pattern of one family: {@code ^<base><siblingSuffixRegex>$}. */
    public Pattern eligibleNamePattern(String baseName) {
        return Pattern.compile("^" + Pattern.quote(baseName) + siblingSuffixRegex + "$");
    }

    /** The most bytes one transport read can ask for: the anchor region plus the payload cap (C2: 8388672). */
    public long maxRangeBytes() {
        return anchorBytes + readCapBytes;
    }
}
