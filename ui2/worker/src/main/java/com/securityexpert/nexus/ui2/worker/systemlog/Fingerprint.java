package com.securityexpert.nexus.ui2.worker.systemlog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * The fingerprint (anchor) check of contract section 4.3 as pure functions. The anchor is the keyed hash of the
 * {@code K = min(anchorBytes, offset)} bytes just before the cursor offset. A ranged read starts {@code K} bytes
 * early, so the first {@code K} received bytes must hash to the stored value; a different hash means the content at
 * the offset is not what was read before (inode reuse, a replaced file, truncate-and-regrow).
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /** Result of comparing the received anchor region with the stored anchor. */
    public enum Result {
        /** The region hashes to the stored anchor, or no anchor is required (offset 0). */
        MATCH,
        /** The region differs: handle the source as {@code ANCHOR_MISMATCH}. */
        MISMATCH,
        /** Fewer than {@code K} bytes arrived: the source is {@code UNKNOWN} this run and the cursor stays. */
        SHORT_READ
    }

    /** {@code K} for a cursor offset: {@code min(anchorBytes, offset)}. */
    public static int anchorLength(long offset, PlannerConfig config) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        return (int) Math.min(config.anchorBytes(), offset);
    }

    /**
     * Checks the first {@code anchorLength} received bytes against the stored anchor.
     *
     * @param received the bytes received from the start of the range (anchor region first)
     * @param receivedLength how many bytes of {@code received} are valid
     * @param anchorLength {@code K} for this read ({@link ReadAction#leadIn()})
     * @param expectedAnchor the stored {@code anchor_hmac}; ignored when {@code anchorLength} is 0
     */
    public static Result verify(AnchorHasher hasher, byte[] received, int receivedLength, int anchorLength,
            String expectedAnchor) {
        Objects.requireNonNull(hasher, "hasher");
        if (anchorLength < 0 || receivedLength < 0 || receivedLength > received.length) {
            throw new IllegalArgumentException("invalid lengths");
        }
        if (anchorLength == 0) {
            return Result.MATCH;
        }
        if (receivedLength < anchorLength) {
            return Result.SHORT_READ;
        }
        if (expectedAnchor == null) {
            return Result.MISMATCH; // an offset without a stored anchor cannot be confirmed
        }
        String actual = hasher.hash(Arrays.copyOfRange(received, 0, anchorLength));
        boolean same = MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                expectedAnchor.getBytes(StandardCharsets.UTF_8));
        return same ? Result.MATCH : Result.MISMATCH;
    }

    /**
     * The anchor for a cursor that has just advanced to {@code newOffset}: the keyed hash of the last
     * {@code min(anchorBytes, newOffset)} bytes before it.
     *
     * @param window contiguous bytes of the file that end exactly at {@code newOffset}: everything the range
     *        returned up to there, lead-in first (a plan always reads {@code min(anchorBytes, offset)} lead-in
     *        bytes, so this is long enough even when the stored payload is short)
     * @param windowLength valid bytes in {@code window}
     * @param newOffset the new cursor offset
     * @return the anchor, or null when {@code newOffset} is 0 (nothing to fingerprint)
     * @throws IllegalArgumentException when the window is shorter than the bytes the anchor needs, which the lead-in
     *         of every planned read prevents
     */
    public static String nextAnchor(AnchorHasher hasher, byte[] window, int windowLength, long newOffset,
            PlannerConfig config) {
        Objects.requireNonNull(hasher, "hasher");
        int length = anchorLength(newOffset, config);
        if (length == 0) {
            return null;
        }
        if (windowLength < length || windowLength > window.length) {
            throw new IllegalArgumentException("the window is shorter than the anchor");
        }
        return hasher.hash(Arrays.copyOfRange(window, windowLength - length, windowLength));
    }
}
