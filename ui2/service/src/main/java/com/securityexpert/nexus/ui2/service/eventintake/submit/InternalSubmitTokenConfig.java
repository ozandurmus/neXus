package com.securityexpert.nexus.ui2.service.eventintake.submit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Immutable SHA-256 bearer-token digest configuration for internal trigger submission
 * ({@code POST /internal/event-triggers/submit} from {@code ui2-event-intake} to {@code ui2-service},
 * per {@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code Q8} and {@code §1.1}).
 *
 * <p>Stores only 32-byte SHA-256 digests (never raw bearer token strings) and supports zero-downtime
 * dual-slot rotation via an optional {@code nextSha256Hex} bounded by an inclusive {@code nextValidUntil}
 * instant. {@link #toString()} redacts all digest material.</p>
 */
public final class InternalSubmitTokenConfig {

    public static final int MIN_RAW_TOKEN_LENGTH = 32;

    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final Pattern LOWERCASE_SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final byte[] currentDigest;
    private final byte[] nextDigest;
    private final Instant nextValidUntil;

    /**
     * Constructs a single-slot configuration with no staged rotation digest.
     *
     * @param currentSha256Hex 64 lowercase hexadecimal characters representing SHA-256 of the active token
     */
    public InternalSubmitTokenConfig(String currentSha256Hex) {
        this(currentSha256Hex, null, null);
    }

    /**
     * Constructs a bearer-token digest configuration with an optional staged rotation digest and
     * mandatory inclusive expiry bound.
     *
     * @param currentSha256Hex required 64 lowercase hex chars for the active token digest
     * @param nextSha256Hex    optional 64 lowercase hex chars for the staged rotation token digest
     * @param nextValidUntil   required inclusive expiry instant whenever {@code nextSha256Hex} is present;
     *                         must be {@code null} when {@code nextSha256Hex} is {@code null}
     */
    public InternalSubmitTokenConfig(
            String currentSha256Hex,
            String nextSha256Hex,
            Instant nextValidUntil) {
        this(
                parseDigestHex(currentSha256Hex, "currentSha256Hex"),
                parseOptionalNextDigestHex(nextSha256Hex, nextValidUntil),
                nextValidUntil);
    }

    private InternalSubmitTokenConfig(byte[] currentDigest, byte[] nextDigest, Instant nextValidUntil) {
        this.currentDigest = currentDigest;
        this.nextDigest = nextDigest;
        this.nextValidUntil = nextValidUntil;
    }

    /**
     * Derives a single-slot configuration from a raw bearer token, immediately discarding the raw token
     * reference and zeroing temporary token byte buffers.
     */
    public static InternalSubmitTokenConfig fromRawToken(String currentRawToken) {
        return fromRawTokens(currentRawToken, null, null);
    }

    /**
     * Derives a configuration from raw bearer token(s), computing SHA-256 digests and immediately
     * discarding references to raw token strings and zeroing temporary byte buffers.
     *
     * @param currentRawToken required active raw token (non-blank, &ge; 32 chars, no whitespace/control chars)
     * @param nextRawToken    optional staged rotation raw token (non-blank, &ge; 32 chars, no whitespace/control chars)
     * @param nextValidUntil  required inclusive expiry instant whenever {@code nextRawToken} is present;
     *                        must be {@code null} when {@code nextRawToken} is {@code null}
     */
    public static InternalSubmitTokenConfig fromRawTokens(
            String currentRawToken,
            String nextRawToken,
            Instant nextValidUntil) {
        validateRawToken(currentRawToken, "currentRawToken");
        if (nextRawToken == null) {
            if (nextValidUntil != null) {
                throw new IllegalArgumentException("nextValidUntil must be null when nextRawToken is null");
            }
            return new InternalSubmitTokenConfig(digestRawToken(currentRawToken), null, null);
        }
        validateRawToken(nextRawToken, "nextRawToken");
        if (nextValidUntil == null) {
            throw new IllegalArgumentException("nextValidUntil must not be null when nextRawToken is present");
        }
        return new InternalSubmitTokenConfig(
                digestRawToken(currentRawToken),
                digestRawToken(nextRawToken),
                nextValidUntil);
    }

    public String currentSha256Hex() {
        return HexFormat.of().formatHex(currentDigest);
    }

    public String nextSha256Hex() {
        return nextDigest != null ? HexFormat.of().formatHex(nextDigest) : null;
    }

    public Instant nextValidUntil() {
        return nextValidUntil;
    }

    public boolean hasNextToken() {
        return nextDigest != null;
    }

    byte[] currentDigestBytes() {
        return currentDigest.clone();
    }

    byte[] nextDigestBytes() {
        return nextDigest != null ? nextDigest.clone() : null;
    }

    private static byte[] parseDigestHex(String sha256Hex, String fieldName) {
        if (sha256Hex == null || !LOWERCASE_SHA256_HEX_PATTERN.matcher(sha256Hex).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be exactly 64 lowercase hexadecimal characters");
        }
        return HexFormat.of().parseHex(sha256Hex);
    }

    private static byte[] parseOptionalNextDigestHex(String nextSha256Hex, Instant nextValidUntil) {
        if (nextSha256Hex == null) {
            if (nextValidUntil != null) {
                throw new IllegalArgumentException("nextValidUntil must be null when nextSha256Hex is null");
            }
            return null;
        }
        if (nextValidUntil == null) {
            throw new IllegalArgumentException("nextValidUntil must not be null when nextSha256Hex is present");
        }
        return parseDigestHex(nextSha256Hex, "nextSha256Hex");
    }

    private static void validateRawToken(String rawToken, String fieldName) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() < MIN_RAW_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName + " must be non-blank and at least " + MIN_RAW_TOKEN_LENGTH + " characters");
        }
        for (int i = 0; i < rawToken.length(); i++) {
            char c = rawToken.charAt(i);
            if (c <= 0x20 || c == 0x7F || Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        fieldName + " must not contain whitespace or control characters");
            }
        }
    }

    static byte[] digestRawToken(String rawToken) {
        byte[] utf8 = rawToken.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            return digest.digest(utf8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 provider unavailable", ex);
        } finally {
            Arrays.fill(utf8, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "InternalSubmitTokenConfig[currentSha256=<redacted>"
                + ", nextSha256=" + (nextDigest != null ? "<redacted>" : "<none>")
                + ", nextValidUntil=" + nextValidUntil
                + "]";
    }
}
