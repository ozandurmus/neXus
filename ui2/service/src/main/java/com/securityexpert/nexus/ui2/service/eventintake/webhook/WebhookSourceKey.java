package com.securityexpert.nexus.ui2.service.eventintake.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-source HMAC-SHA256 verification key configuration supporting zero-downtime dual-{@code kid}
 * secret rotation ({@code Q1} Option {@code 1B}, {@code §4.5}).
 *
 * <p>Secret key byte arrays are defensively copied on construction and on key resolution.
 * {@link #toString()} never exposes secret material.</p>
 */
public final class WebhookSourceKey {

    private final String sourceId;
    private final String currentKid;
    private final byte[] currentSecret;
    private final String nextKid;
    private final byte[] nextSecret;
    private final Instant nextValidUntil;

    /**
     * Constructs a single-key configuration without a staged rotation key.
     */
    public WebhookSourceKey(String sourceId, String currentKid, byte[] currentSecret) {
        this(sourceId, currentKid, currentSecret, null, null, null);
    }

    /**
     * Constructs a dual-{@code kid} key configuration with an optional rotation key and expiry bound.
     *
     * @param sourceId       enrolled opaque event-source identifier (non-blank)
     * @param currentKid     active key identifier (non-blank)
     * @param currentSecret  active HMAC secret bytes (non-empty)
     * @param nextKid        optional staged rotation key identifier (nullable; non-blank if present)
     * @param nextSecret     optional staged rotation HMAC secret bytes (nullable; non-empty if present)
     * @param nextValidUntil optional inclusive expiry instant for {@code nextKid} (nullable = no upper bound)
     */
    public WebhookSourceKey(
            String sourceId,
            String currentKid,
            byte[] currentSecret,
            String nextKid,
            byte[] nextSecret,
            Instant nextValidUntil) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be null or blank");
        }
        if (currentKid == null || currentKid.isBlank()) {
            throw new IllegalArgumentException("currentKid must not be null or blank");
        }
        if (currentSecret == null || currentSecret.length == 0) {
            throw new IllegalArgumentException("currentSecret must not be null or empty");
        }
        if (nextKid != null && nextKid.isBlank()) {
            throw new IllegalArgumentException("nextKid must not be blank when provided");
        }
        if (nextSecret != null && nextSecret.length == 0) {
            throw new IllegalArgumentException("nextSecret must not be empty when provided");
        }
        this.sourceId = sourceId;
        this.currentKid = currentKid;
        this.currentSecret = currentSecret.clone();
        this.nextKid = nextKid;
        this.nextSecret = nextSecret != null ? nextSecret.clone() : null;
        this.nextValidUntil = nextValidUntil;
    }

    public String sourceId() {
        return sourceId;
    }

    public String currentKid() {
        return currentKid;
    }

    public String nextKid() {
        return nextKid;
    }

    public Instant nextValidUntil() {
        return nextValidUntil;
    }

    public boolean hasNextSecret() {
        return nextSecret != null;
    }

    /**
     * Resolves the HMAC secret bytes for {@code kid} at instant {@code now}.
     *
     * <p>Matches {@code currentKid} unconditionally, or {@code nextKid} only when {@code nextSecret != null}
     * and ({@code nextValidUntil == null} or {@code !now.isAfter(nextValidUntil)}). Callers should zero the
     * returned array once HMAC verification completes.</p>
     */
    public Optional<byte[]> resolveSecret(String kid, Instant now) {
        Objects.requireNonNull(now, "now");
        if (kid == null || kid.isBlank()) {
            return Optional.empty();
        }
        if (constantTimeEqualsUtf8(kid, currentKid)) {
            return Optional.of(currentSecret.clone());
        }
        if (nextKid != null
                && nextSecret != null
                && constantTimeEqualsUtf8(kid, nextKid)
                && (nextValidUntil == null || !now.isAfter(nextValidUntil))) {
            return Optional.of(nextSecret.clone());
        }
        return Optional.empty();
    }

    private static boolean constantTimeEqualsUtf8(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "WebhookSourceKey[sourceId=" + sourceId
                + ", currentKid=" + currentKid
                + ", currentSecret=<redacted>"
                + ", nextKid=" + (nextKid != null ? nextKid : "<none>")
                + ", nextSecret=" + (nextSecret != null ? "<redacted>" : "<none>")
                + ", nextValidUntil=" + nextValidUntil
                + "]";
    }
}
