package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.Objects;

/**
 * One planned C2 read (contract section 4.3): a ranged read of one absolute path printed by this run's C1.
 *
 * <p>The transport asks for the byte range {@code [rangeStart(), rangeStart() + rangeLength())}: {@code leadIn}
 * bytes before {@code offset} (the anchor region, or for a first run the lead-in that tells whether the start is on
 * a line boundary), then up to {@code length} payload bytes. The payload counts against the budgets; the lead-in
 * does not.</p>
 *
 * <p>Offsets of later reads in the same file are <em>nominal</em>: the cursor stops at the last newline, so the real
 * start of a continuation is the cursor the executor holds after storing the previous read. {@link #rebase} turns a
 * nominal action into the real one.</p>
 *
 * @param path absolute path from this run's stat output
 * @param inode inode the stat printed for that path
 * @param offset first payload byte
 * @param length at most this many payload bytes (0 only for an anchor-verification read)
 * @param leadIn bytes read before {@code offset}; {@code min(anchorBytes, offset)}
 * @param expectedAnchor HMAC the first {@code leadIn} received bytes must have; null when nothing is verified
 * @param purpose why the read exists
 * @param continuation true for the second and later reads of one file in this plan; the executor verifies them
 *        against the anchor it computed after storing the previous read
 * @param finalFile true when this read reaches the end of a rotated sibling: its last line is complete even
 *        without a newline, so no partial line is carried
 */
public record ReadAction(
        String path,
        long inode,
        long offset,
        long length,
        int leadIn,
        String expectedAnchor,
        ReadPurpose purpose,
        boolean continuation,
        boolean finalFile) {

    public ReadAction {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(purpose, "purpose");
        if (offset < 0 || length < 0 || leadIn < 0 || leadIn > offset) {
            throw new IllegalArgumentException("offset, length and leadIn must be non-negative with leadIn <= offset");
        }
        if (expectedAnchor != null && leadIn == 0 && offset != 0) {
            throw new IllegalArgumentException("an anchor needs its lead-in bytes");
        }
    }

    /** 0-based start of the transport range ({@code <pos>} of C2). */
    public long rangeStart() {
        return offset - leadIn;
    }

    /** Length of the transport range ({@code <cap>} of C2): lead-in plus payload. */
    public long rangeLength() {
        return leadIn + length;
    }

    /** First byte after the planned payload. */
    public long endOffset() {
        return offset + length;
    }

    /** True when the executor must compare the lead-in bytes with {@link #expectedAnchor()}. */
    public boolean verifiesAnchor() {
        return expectedAnchor != null;
    }

    /**
     * The real action once the cursor is at {@code actualOffset} (after a previous read was trimmed to its last
     * newline): the same file and the same planned end, the lead-in re-derived for the new offset, the expected
     * anchor replaced by the one the executor computed. The length is re-clamped to {@code readCapBytes}.
     */
    public ReadAction rebase(long actualOffset, String anchor, PlannerConfig config) {
        long end = Math.max(endOffset(), actualOffset);
        long newLength = Math.min(config.readCapBytes(), end - actualOffset);
        int newLeadIn = (int) Math.min(config.anchorBytes(), actualOffset);
        return new ReadAction(path, inode, actualOffset, newLength, newLeadIn, anchor, purpose, continuation,
                finalFile && actualOffset + newLength == end);
    }
}
