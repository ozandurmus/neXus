package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.List;
import java.util.Objects;

/**
 * The stored cursor of one (device, source key), contract section 4.1, as the planner needs it. No raw log bytes are
 * part of it: the anchor is only an HMAC.
 *
 * @param inode inode of the file the cursor points into
 * @param offsetBytes first unread byte in that file (always just after a newline)
 * @param anchorHmac HMAC of the {@code min(64, offsetBytes)} bytes before the offset; null or blank only at offset 0
 * @param familySnapshot the previous run's stat of the whole family (every member, readable or not)
 * @param deviceEpochAtRead the device clock at the last stat; the start of any gap window
 */
public record SourceCursor(
        long inode,
        long offsetBytes,
        String anchorHmac,
        List<FileStat> familySnapshot,
        long deviceEpochAtRead) {

    public SourceCursor {
        Objects.requireNonNull(familySnapshot, "familySnapshot");
        familySnapshot = List.copyOf(familySnapshot);
    }

    /** True when the cursor satisfies its own invariants (offset 0 needs no anchor, any other offset needs one). */
    boolean isConsistent() {
        if (inode < 0 || offsetBytes < 0 || deviceEpochAtRead < 0) {
            return false;
        }
        return offsetBytes == 0 || (anchorHmac != null && !anchorHmac.isBlank());
    }
}
