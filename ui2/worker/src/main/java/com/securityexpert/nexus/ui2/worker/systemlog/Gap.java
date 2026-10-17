package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.List;
import java.util.Objects;

/**
 * One gap row of contract section 4.5 (the {@code system_log_gap} table, minus the job id).
 *
 * <p>{@code knownLostBytes} is computed only from sizes this product observed. Where the contract says "unknown" it
 * is 0 and {@code extentUnknown} is true. {@code affectedPaths} are in-memory device paths for the executor to hash;
 * no gap field is ever written to a database in clear text.</p>
 *
 * @param kind the gap kind
 * @param knownLostBytes exact lost bytes the product can prove; for {@code TRUNCATED} the contract's upper bound
 * @param knownLostIsUpperBound true when {@code knownLostBytes} is only an upper bound ({@code TRUNCATED} with no
 *        continuing sibling)
 * @param pendingBytes for {@code BACKLOG}: the exact unread bytes still on the device; otherwise 0
 * @param extentUnknown true when files could have existed and vanished between two stats, or the extent cannot be
 *        computed at all
 * @param windowStartEpoch device clock of the last cursor read (equals the end for a first run)
 * @param windowEndEpoch device clock of this run's stat
 * @param affectedPaths device paths involved (masked in every aiview projection)
 * @param compressedBytesObserved sizes of compressed rotations observed and not readable (section 4.6); they are
 *        compressed sizes, never added to {@code knownLostBytes}
 * @param unreadableSiblings number of siblings that could not be read (compressed, or outside the name pattern)
 */
public record Gap(
        GapKind kind,
        long knownLostBytes,
        boolean knownLostIsUpperBound,
        long pendingBytes,
        boolean extentUnknown,
        long windowStartEpoch,
        long windowEndEpoch,
        List<String> affectedPaths,
        long compressedBytesObserved,
        int unreadableSiblings) {

    public Gap {
        Objects.requireNonNull(kind, "kind");
        if (knownLostBytes < 0 || pendingBytes < 0 || compressedBytesObserved < 0 || unreadableSiblings < 0) {
            throw new IllegalArgumentException("gap counters must be non-negative");
        }
        affectedPaths = List.copyOf(affectedPaths);
    }

    /** The exact skipped prefix of a first run. The executor adds the bytes of a trimmed leading partial line. */
    public static Gap initialBaseline(long skippedBytes, long epoch, String path) {
        return new Gap(GapKind.INITIAL_BASELINE, skippedBytes, false, 0, false, epoch, epoch, List.of(path), 0, 0);
    }

    public static Gap backlog(long pendingBytes, long windowStart, long windowEnd, List<String> paths) {
        return new Gap(GapKind.BACKLOG, 0, false, pendingBytes, false, windowStart, windowEnd, paths, 0, 0);
    }

    /** The executor records this when a transport or SFTP failure ends a source's reads (section 9: UNKNOWN). */
    public static Gap collectionFailed(long windowStart, long windowEnd) {
        return new Gap(GapKind.COLLECTION_FAILED, 0, false, 0, true, windowStart, windowEnd, List.of(), 0, 0);
    }

    /** The same gap with more exact lost bytes (for example the leading partial line of an initial baseline). */
    public Gap withAdditionalLostBytes(long extra) {
        if (extra < 0) {
            throw new IllegalArgumentException("extra must be non-negative");
        }
        return new Gap(kind, Math.addExact(knownLostBytes, extra), knownLostIsUpperBound, pendingBytes, extentUnknown,
                windowStartEpoch, windowEndEpoch, affectedPaths, compressedBytesObserved, unreadableSiblings);
    }

    /** True for kinds that are a loss (everything except BACKLOG; INITIAL_BASELINE is a recorded, intended skip). */
    public boolean isLoss() {
        return kind != GapKind.BACKLOG;
    }
}
