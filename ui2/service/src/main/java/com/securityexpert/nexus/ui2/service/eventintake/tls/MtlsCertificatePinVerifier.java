package com.securityexpert.nexus.ui2.service.eventintake.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-Java RFC 5425 {@code TLS_MUTUAL} dual-pin SHA-256 client-certificate verifier
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code §1 Q2} and
 * {@code §1.1 Q2}).
 *
 * <p>Supports zero-downtime certificate rotation using a required {@code cert_sha256_current}
 * pin and an optional staged {@code cert_sha256_next} pin bounded by an inclusive
 * {@code cert_next_valid_until} instant. Verification is fail-closed and constant-time via
 * {@link MessageDigest#isEqual(byte[], byte[])} over decoded 32-byte SHA-256 digests.</p>
 *
 * <p>Neither {@link PinConfig#toString()} nor {@link Outcome#reason()} ever exposes raw
 * certificate DER bytes, subject DNs, or full SHA-256 fingerprints
 * ({@code AGENTS.md} Sensitive identity reporting law and Raw-evidence law).</p>
 */
public final class MtlsCertificatePinVerifier {

    private static final String SHA256_ALGORITHM = "SHA-256";
    private static final int SHA256_HEX_LENGTH = 64;
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final Clock clock;

    /**
     * Creates a verifier backed by {@link Clock#systemUTC()}.
     */
    public MtlsCertificatePinVerifier() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a verifier backed by the supplied {@link Clock}.
     */
    public MtlsCertificatePinVerifier(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Immutable dual-pin certificate configuration.
     *
     * <p>Invalid configurations are evaluated fail-closed by {@link MtlsCertificatePinVerifier}
     * to {@link Outcome#INVALID_PIN_CONFIG}. {@link #toString()} always redacts pin digests.</p>
     *
     * @param certSha256Current  required 64-character hex SHA-256 digest of the active client cert
     * @param certSha256Next     optional (nullable/blank) 64-character hex SHA-256 digest of the staged next cert
     * @param certNextValidUntil inclusive upper validity bound required whenever {@code certSha256Next} is present
     */
    public record PinConfig(
            String certSha256Current,
            String certSha256Next,
            Instant certNextValidUntil) {

        /**
         * Creates a single-pin configuration without a staged rotation pin.
         */
        public static PinConfig of(String certSha256Current) {
            return new PinConfig(certSha256Current, null, null);
        }

        /**
         * Creates a dual-pin rotation configuration.
         */
        public static PinConfig of(
                String certSha256Current,
                String certSha256Next,
                Instant certNextValidUntil) {
            return new PinConfig(certSha256Current, certSha256Next, certNextValidUntil);
        }

        @Override
        public String toString() {
            final boolean hasCurrent = certSha256Current != null && !certSha256Current.isBlank();
            final boolean hasNext = certSha256Next != null && !certSha256Next.isBlank();
            return "PinConfig[certSha256Current=" + (hasCurrent ? "<redacted>" : "<absent>")
                    + ", certSha256Next=" + (hasNext ? "<redacted>" : "<none>")
                    + ", certNextValidUntil=" + certNextValidUntil
                    + "]";
        }
    }

    /**
     * Closed verification outcome vocabulary with fixed diagnostic reasons.
     */
    public enum Outcome {
        MATCH_CURRENT("Presented client certificate matched the current SHA-256 pin."),
        MATCH_NEXT("Presented client certificate matched the staged next SHA-256 pin within its validity window."),
        EXPIRED_NEXT_PIN("Presented client certificate matched the staged next SHA-256 pin after its validity window expired."),
        PIN_MISMATCH("Presented client certificate did not match any configured SHA-256 pin."),
        INVALID_PIN_CONFIG("Configured mTLS certificate pin specification is invalid."),
        MISSING_CERTIFICATE("Presented client certificate is missing or could not be DER-encoded.");

        private final String reason;

        Outcome(String reason) {
            this.reason = reason;
        }

        /**
         * Returns {@code true} only for {@link #MATCH_CURRENT} and {@link #MATCH_NEXT}.
         */
        public boolean isPermitted() {
            return this == MATCH_CURRENT || this == MATCH_NEXT;
        }

        /**
         * Returns a fixed closed-vocabulary diagnostic message that never echoes certificate
         * bytes, subject DNs, or SHA-256 fingerprints.
         */
        public String reason() {
            return reason;
        }
    }

    /**
     * Verifies DER-encoded certificate bytes against {@code config} using the verifier's clock.
     */
    public Outcome verify(PinConfig config, byte[] presentedCertDer) {
        return verify(config, presentedCertDer, clock.instant());
    }

    /**
     * Verifies an {@link X509Certificate} against {@code config} using the verifier's clock.
     */
    public Outcome verify(PinConfig config, X509Certificate presentedCert) {
        return verify(config, presentedCert, clock.instant());
    }

    /**
     * Verifies an {@link X509Certificate} against {@code config} at instant {@code now}.
     */
    public Outcome verify(PinConfig config, X509Certificate presentedCert, Instant now) {
        final DecodedPins pins = decodeAndValidateConfig(config);
        if (pins == null) {
            return Outcome.INVALID_PIN_CONFIG;
        }
        try {
            if (presentedCert == null) {
                return Outcome.MISSING_CERTIFICATE;
            }
            final byte[] encoded;
            try {
                encoded = presentedCert.getEncoded();
            } catch (CertificateEncodingException ex) {
                return Outcome.MISSING_CERTIFICATE;
            }
            return verifyDecoded(pins, encoded, now);
        } finally {
            pins.zeroize();
        }
    }

    /**
     * Verifies DER-encoded certificate bytes against {@code config} at instant {@code now}.
     */
    public Outcome verify(PinConfig config, byte[] presentedCertDer, Instant now) {
        final DecodedPins pins = decodeAndValidateConfig(config);
        if (pins == null) {
            return Outcome.INVALID_PIN_CONFIG;
        }
        try {
            return verifyDecoded(pins, presentedCertDer, now);
        } finally {
            pins.zeroize();
        }
    }

    private static Outcome verifyDecoded(DecodedPins pins, byte[] presentedCertDer, Instant now) {
        if (presentedCertDer == null || presentedCertDer.length == 0) {
            return Outcome.MISSING_CERTIFICATE;
        }

        final byte[] presentedDigest = sha256(presentedCertDer);
        try {
            final boolean matchesCurrent = MessageDigest.isEqual(presentedDigest, pins.currentDigest);
            final boolean matchesNext = pins.nextDigest != null
                    && MessageDigest.isEqual(presentedDigest, pins.nextDigest);

            if (matchesCurrent) {
                return Outcome.MATCH_CURRENT;
            }
            if (matchesNext) {
                if (now != null && !now.isAfter(pins.nextValidUntil)) {
                    return Outcome.MATCH_NEXT;
                }
                return Outcome.EXPIRED_NEXT_PIN;
            }
            return Outcome.PIN_MISMATCH;
        } finally {
            Arrays.fill(presentedDigest, (byte) 0);
        }
    }

    private static DecodedPins decodeAndValidateConfig(PinConfig config) {
        if (config == null) {
            return null;
        }
        final byte[] currentDigest = parseStrictSha256Hex(config.certSha256Current());
        if (currentDigest == null) {
            return null;
        }

        final String nextHex = config.certSha256Next();
        if (nextHex == null || nextHex.isBlank()) {
            return new DecodedPins(currentDigest, null, null);
        }

        final Instant nextValidUntil = config.certNextValidUntil();
        if (nextValidUntil == null) {
            Arrays.fill(currentDigest, (byte) 0);
            return null;
        }

        final byte[] nextDigest = parseStrictSha256Hex(nextHex);
        if (nextDigest == null) {
            Arrays.fill(currentDigest, (byte) 0);
            return null;
        }

        return new DecodedPins(currentDigest, nextDigest, nextValidUntil);
    }

    private static byte[] parseStrictSha256Hex(String hexDigest) {
        if (hexDigest == null
                || hexDigest.length() != SHA256_HEX_LENGTH
                || !SHA256_HEX_PATTERN.matcher(hexDigest).matches()) {
            return null;
        }
        return HexFormat.of().parseHex(hexDigest);
    }

    private static byte[] sha256(byte[] derBytes) {
        try {
            return MessageDigest.getInstance(SHA256_ALGORITHM).digest(derBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 MessageDigest provider unavailable", ex);
        }
    }

    private static final class DecodedPins {
        private final byte[] currentDigest;
        private final byte[] nextDigest;
        private final Instant nextValidUntil;

        private DecodedPins(byte[] currentDigest, byte[] nextDigest, Instant nextValidUntil) {
            this.currentDigest = currentDigest;
            this.nextDigest = nextDigest;
            this.nextValidUntil = nextValidUntil;
        }

        private void zeroize() {
            Arrays.fill(currentDigest, (byte) 0);
            if (nextDigest != null) {
                Arrays.fill(nextDigest, (byte) 0);
            }
        }
    }
}
