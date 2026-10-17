package com.securityexpert.nexus.ui2.worker.systemlog;

/**
 * Keyed hash of an anchor region (contract section 4.1: HMAC-SHA256 under the product privacy key). The key lives
 * outside the planner; this seam keeps the planner and its tests free of key handling.
 */
@FunctionalInterface
public interface AnchorHasher {
    /** The keyed hash of exactly {@code data}, as the text stored in {@code anchor_hmac}. */
    String hash(byte[] data);
}
