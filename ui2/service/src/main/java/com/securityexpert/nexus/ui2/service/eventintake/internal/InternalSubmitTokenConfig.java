package com.securityexpert.nexus.ui2.service.eventintake.internal;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable SHA-256 bearer-token digest configuration for internal {@code ui2-event-intake}
 * to {@code ui2-service} submit authentication
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code Q8};
 * {@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.2} Option A).
 *
 * <p>Supports zero-downtime Kubernetes Secret rotation via a required {@code currentSha256Hex}
 * digest and an optional staged {@code nextSha256Hex} digest bounded by {@code nextValidUntil}.
 * Validation is fail-closed: blank, wrong-length, uppercase, or non-hex digests are rejected
 * with {@link IllegalArgumentException} without echoing the rejected value. {@link #toString()}
 * never exposes digest material ({@code AGENTS.md} Raw-evidence law).</p>
 */
public final class InternalSubmitTokenConfig {

    private static final int SHA256_HEX_LENGTH = 64;
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final byte[] currentSha256Bytes;
    private final byte[] nextSha256Bytes;
    private final Instant nextValidUntil;

    /**
     * Creates a single-digest configuration without a staged rotation token.
     *
     * @param currentSha256Hex required 64-character lowercase hexadecimal SHA-256 digest
     */
    public static InternalSubmitTokenConfig of(String currentSha256Hex) {
        return new InternalSubmitTokenConfig(currentSha256Hex, null, null);
    }

    /**
     * Creates a rotation-capable digest configuration.
     *
     * @param currentSha256Hex required 64-character lowercase hexadecimal SHA-256 digest
     * @param nextSha256Hex    optional 64-character lowercase hexadecimal SHA-256 digest
     * @param nextValidUntil   optional inclusive upper validity bound for {@code nextSha256Hex}
     */
    public static InternalSubmitTokenConfig of(
            String currentSha256Hex,
            String nextSha256Hex,
            Instant nextValidUntil) {
        return new InternalSubmitTokenConfig(currentSha256Hex, nextSha256Hex, nextValidUntil);
    }

    /**
     * Constructs a single-digest configuration without a staged rotation token.
     */
    public InternalSubmitTokenConfig(String currentSha256Hex) {
        this(currentSha256Hex, null, null);
    }

    /**
     * Constructs a rotation-capable digest configuration.
     */
    public InternalSubmitTokenConfig(
            String currentSha256Hex,
            String nextSha256Hex,
            Instant nextValidUntil) {
        this.currentSha256Bytes = parseRequiredSha256Hex(currentSha256Hex, "currentSha256Hex");
        if (nextSha256Hex == null) {
            if (nextValidUntil != null) {
                throw new IllegalArgumentException(
                        "nextValidUntil must not be configured without nextSha256Hex");
            }
            this.nextSha256Bytes = null;
            this.nextValidUntil = null;
        } else {
            this.nextSha256Bytes = parseRequiredSha256Hex(nextSha256Hex, "nextSha256Hex");
            this.nextValidUntil = nextValidUntil;
        }
    }

    /**
     * Returns a defensive copy of the decoded 32-byte {@code current} SHA-256 digest.
     */
    public byte[] currentSha256Bytes() {
        return currentSha256Bytes.clone();
    }

    /**
     * Returns a defensive copy of the decoded 32-byte {@code next} SHA-256 digest when configured.
     */
    public Optional<byte[]> nextSha256Bytes() {
        return nextSha256Bytes != null
                ? Optional.of(nextSha256Bytes.clone())
                : Optional.empty();
    }

    /**
     * Returns {@code true} when a staged {@code next} rotation digest is configured.
     */
    public boolean hasNextToken() {
        return nextSha256Bytes != null;
    }

    /**
     * Returns the inclusive upper validity bound for the staged {@code next} digest, or {@code null}
     * if not set.
     */
    public Instant nextValidUntil() {
        return nextValidUntil;
    }

    private static byte[] parseRequiredSha256Hex(String hexDigest, String fieldName) {
        if (hexDigest == null
                || hexDigest.length() != SHA256_HEX_LENGTH
                || !SHA256_HEX_PATTERN.matcher(hexDigest).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a 64-character lowercase hexadecimal SHA-256 digest");
        }
        return HexFormat.of().parseHex(hexDigest);
    }

    @Override
    public String toString() {
        return "InternalSubmitTokenConfig[currentSha256=<redacted>, nextSha256="
                + (nextSha256Bytes != null ? "<redacted>" : "<none>")
                + ", nextValidUntil=" + nextValidUntil
                + "]";
    }
}
