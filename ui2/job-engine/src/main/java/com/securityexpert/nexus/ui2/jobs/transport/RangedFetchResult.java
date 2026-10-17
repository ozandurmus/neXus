package com.securityexpert.nexus.ui2.jobs.transport;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * The exact accounting of one ranged {@code sftp_get} (phase B contract §4.3). Byte counts are what neXus itself
 * requested and wrote to the sink; bytes a server served past {@code bytesRequested} by request-ahead are never
 * written and never counted.
 *
 * @param status          the typed outcome
 * @param startOffset     the 0-based position the read started at
 * @param bytesRequested  the number of bytes asked for (after the caller's per-read cap)
 * @param bytesReceived   the number of bytes written to the sink (for a failure: the prefix to discard)
 * @param sizeAtOpen      the file size the SFTP server reported for the path immediately before the open (JSch
 *                        stats the path before it sends {@code SSH_FXP_OPEN}; SFTP v3 handles expose no inode and
 *                        the library offers no {@code fstat}); empty when the open was never reached
 * @param eofReached      whether the server signalled end of file inside the range
 * @param reasonCode      a fixed-vocabulary code for a non-usable status (refusal reason, SFTP status id,
 *                        exception class name); never device text, never a path; empty string when not applicable
 */
public record RangedFetchResult(RangedFetchStatus status, long startOffset, long bytesRequested, long bytesReceived,
        OptionalLong sizeAtOpen, boolean eofReached, String reasonCode) {

    public RangedFetchResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sizeAtOpen, "sizeAtOpen");
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    /** A refusal before any I/O: nothing requested from the device, nothing received. */
    public static RangedFetchResult refused(long startOffset, String reasonCode) {
        return new RangedFetchResult(RangedFetchStatus.REFUSED, startOffset, 0, 0, OptionalLong.empty(), false,
                reasonCode);
    }

    public boolean bytesUsable() {
        return status.bytesUsable();
    }
}
