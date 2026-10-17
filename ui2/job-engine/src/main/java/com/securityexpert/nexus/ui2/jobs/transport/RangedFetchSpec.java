package com.securityexpert.nexus.ui2.jobs.transport;

import java.util.Objects;

/**
 * The byte range of a ranged {@code sftp_get} (C4 §2.3 as amended by A-2; phase B contract §4.3): open
 * {@code remotePath} read-only over the SFTP subsystem of the run's existing session, read at most
 * {@code maxBytes} bytes starting at the 0-based byte position {@code startOffset}, close. Both numbers are computed
 * by neXus, never taken from operator input or from a device response; the caller's gate check
 * ({@code CpSyslogRangedRead}) bounds {@code maxBytes} before this value reaches a transport.
 */
public record RangedFetchSpec(String remotePath, long startOffset, long maxBytes) {

    public RangedFetchSpec {
        Objects.requireNonNull(remotePath, "remotePath");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be non-negative");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be at least 1");
        }
    }
}
