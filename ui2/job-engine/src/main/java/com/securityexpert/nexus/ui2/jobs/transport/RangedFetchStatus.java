package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * How one ranged {@code sftp_get} ended. Every value is a definite, typed outcome; no device text is carried.
 * Only {@link #CAP_REACHED} and {@link #END_OF_FILE} leave bytes in the sink that may be stored. For every other
 * value the caller discards whatever prefix reached the sink, leaves the cursor where it was, and records the
 * source as {@code UNKNOWN} for this run (phase B contract §9; C2 row of §10.1: re-planned next run).
 */
public enum RangedFetchStatus {

    /** Exactly the requested number of bytes was received; the file may hold more (the planner continues or records {@code BACKLOG}). */
    CAP_REACHED(true),
    /** The device signalled end of file inside the range and every byte up to it was received. */
    END_OF_FILE(true),
    /** End of file came before the size the server reported when the file was opened: the file shrank during the read. */
    SHORT_READ(false),
    /** The size the server reported when the file was opened is below {@code startOffset} (truncated or replaced since the stat). */
    SIZE_BELOW_OFFSET(false),
    /** SFTP status {@code SSH_FX_NO_SUCH_FILE}: the path is gone (rotation race). */
    FILE_MISSING(false),
    /** SFTP status {@code SSH_FX_PERMISSION_DENIED}. */
    PERMISSION_DENIED(false),
    /** The SFTP subsystem channel could not be opened on the session, or the server does not support the operation. */
    SUBSYSTEM_UNAVAILABLE(false),
    /** The read did not finish inside the gate's timeout; the channel was closed. */
    TIMEOUT(false),
    /** Any other SFTP status or channel failure. */
    TRANSPORT_FAILED(false),
    /** The local sink refused a write (artefact store failure); nothing about the device is implied. */
    SINK_FAILED(false),
    /** Refused before any I/O: gate not resolved {@code KNOWN}, platform out of scope, path or range not eligible. */
    REFUSED(false);

    private final boolean bytesUsable;

    RangedFetchStatus(boolean bytesUsable) {
        this.bytesUsable = bytesUsable;
    }

    /** Whether the bytes written to the sink are a complete, storable result of the requested range. */
    public boolean bytesUsable() {
        return bytesUsable;
    }

    /**
     * The phase B §9 per-source outcome this status forces when it ends the source's reads for the run, or
     * {@code null} when the planner decides from the byte counts ({@code COLLECTED} or {@code BACKLOG}).
     */
    public String forcedSourceOutcome() {
        return bytesUsable ? null : "UNKNOWN";
    }
}
