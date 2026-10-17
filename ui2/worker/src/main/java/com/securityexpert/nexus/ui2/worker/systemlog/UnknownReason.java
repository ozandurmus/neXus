package com.securityexpert.nexus.ui2.worker.systemlog;

/** Why a plan is {@link SourceOutcome#UNKNOWN}. Fail closed: never "no new data", never "absent". */
public enum UnknownReason {
    /** No device-clock line, or a clock and nothing else: the C1 output is unreadable. */
    STAT_UNREADABLE,
    /** A malformed stat line could belong to this family, so its records cannot be trusted. */
    STAT_MALFORMED_FOR_SOURCE,
    /** An error line other than "No such file or directory" names this family and its base file is not listed. */
    STAT_ERROR_FOR_SOURCE,
    /** The stored cursor breaks its own invariants (negative value, or an offset without an anchor). */
    CURSOR_INVALID
}
