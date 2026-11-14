package com.securityexpert.nexus.ui2.service.system;

/** How full a volume is against the disk headroom thresholds; {@code UNKNOWN} means it could not be measured. */
public enum DiskLevel {
    OK, WARNING, CRITICAL, UNKNOWN;

    /** The lower-case token the status payload and the front end use. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The more severe of two levels; UNKNOWN never outranks a measured level (a failed read is not a known-bad state). */
    public static DiskLevel worst(DiskLevel a, DiskLevel b) {
        if (a == UNKNOWN) return b;
        if (b == UNKNOWN) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
