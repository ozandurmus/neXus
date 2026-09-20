package com.securityexpert.nexus.ui2.jobs.failover.schedule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable typed summary of cluster state captured at maintenance window scheduling time.
 * Serves as the authoritative baseline for T₀ JIT drift comparison.
 */
public record BaselineSnapshotSummary(
    String clusterRef,
    String vendor,
    String haMode,
    String activeMemberId,
    String standbyMemberId,
    String softwareVersion,
    String policyHash,
    long transitionCounter,
    String assessmentDigest,
    Instant recordedAt
) {
    public BaselineSnapshotSummary {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(activeMemberId, "activeMemberId must not be null");
        Objects.requireNonNull(standbyMemberId, "standbyMemberId must not be null");
        Objects.requireNonNull(assessmentDigest, "assessmentDigest must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }

    public static BaselineSnapshotSummary of(
        String clusterRef,
        String vendor,
        String haMode,
        String activeMemberId,
        String standbyMemberId,
        String softwareVersion,
        String policyHash,
        long transitionCounter,
        String assessmentDigest,
        Instant recordedAt
    ) {
        return new BaselineSnapshotSummary(
            clusterRef, vendor, haMode, activeMemberId, standbyMemberId,
            softwareVersion, policyHash, transitionCounter, assessmentDigest, recordedAt
        );
    }

    /**
     * Computes a deterministic SHA-256 digest over the canonical length-prefixed
     * representation of this baseline's fields. Two baselines with identical field
     * values always produce the identical digest; this is a content digest, not a
     * random identifier, so it can be safely re-derived and compared across restarts.
     */
    public String computeCanonicalDigest() {
        StringBuilder sb = new StringBuilder();
        appendField(sb, clusterRef);
        appendField(sb, vendor);
        appendField(sb, haMode);
        appendField(sb, activeMemberId);
        appendField(sb, standbyMemberId);
        appendField(sb, softwareVersion);
        appendField(sb, policyHash);
        appendField(sb, String.valueOf(transitionCounter));
        appendField(sb, recordedAt.toString());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available: " + e.getMessage(), e);
        }
    }

    /**
     * CF-P0.2: re-derives the digest from this baseline's own content and compares it
     * against the digest the record carries.
     *
     * <p>The envelope signature covers {@code assessmentDigest}, not the baseline fields
     * behind it, so a signature that verifies proves only that the digest is the one that
     * was signed. Without this check a party able to write the stored baseline could
     * change which member is recorded as active, leave the old digest in place, and still
     * pass envelope verification -- which would aim a mutating command at the wrong
     * member. Constant-time comparison, because the digest is a verification secret in
     * the only sense that matters here: an attacker must not learn how close a guess is.
     */
    public boolean digestMatchesContent() {
        byte[] carried = assessmentDigest.getBytes(StandardCharsets.UTF_8);
        byte[] recomputed = computeCanonicalDigest().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(carried, recomputed);
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:NULL|");
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            sb.append(bytes.length).append(':').append(value).append('|');
        }
    }
}
