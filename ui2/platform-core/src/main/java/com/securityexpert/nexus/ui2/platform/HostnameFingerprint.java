package com.securityexpert.nexus.ui2.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 fingerprint for a backup artefact manifest row's {@code
 * hostname_fingerprint} column (BK-16 / C7 section 3.2: "never a raw
 * hostname"). Unlike {@link PrincipalFingerprint} (unkeyed SHA-256, matching
 * an existing Python reference algorithm for a different, lower-sensitivity
 * purpose), a hostname is low-entropy enough that an unkeyed hash is
 * dictionary-attackable against a persisted column -- so this fingerprint is
 * keyed, under its own, separately named secret (C1 section 6.1: never the
 * credential-store, role-binding or artefact-store-encryption key).
 *
 * <p>Deterministic and one-way: the same hostname under the same key always
 * yields the same fingerprint (so two artefacts from the same host still
 * correlate), and the key material makes the output unrecoverable to the raw
 * hostname without it.</p>
 */
public final class HostnameFingerprint {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    private HostnameFingerprint(SecretKeySpec key) {
        this.key = key;
    }

    /** @param base64Key a base64-encoded key, as read from {@code UI2_HOSTNAME_FINGERPRINT_KEY_FILE} (never a default/literal). */
    public static HostnameFingerprint fromBase64Key(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.strip());
        if (raw.length == 0) {
            throw new IllegalArgumentException("hostname fingerprint key must not decode to zero bytes");
        }
        return new HostnameFingerprint(new SecretKeySpec(raw, ALGORITHM));
    }

    /** Lowercase hex HMAC-SHA256 of {@code hostname} under this instance's key. Never returns the input. */
    public String of(String hostname) {
        String input = hostname == null ? "" : hostname;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hostname fingerprint could not be computed", e);
        }
    }

    /**
     * An incremental keyed content digest (system-log phase B contract section 12: content-derived identifiers are
     * keyed, never bare hashes): HMAC-SHA256 under this instance's key of {@code UTF-8(domain) || 0x00 || content}, as
     * lowercase hex. The domain separates the uses of the one key (anchors, chunk and file content identities).
     */
    public final class Keyed {
        private final Mac mac;

        private Keyed(String domain) {
            try {
                mac = Mac.getInstance(ALGORITHM);
                mac.init(key);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("content fingerprint could not be computed", e);
            }
            mac.update(domain.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
        }

        public void update(byte[] data, int offset, int length) {
            mac.update(data, offset, length);
        }

        public String finish() {
            byte[] digest = mac.doFinal();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        }
    }

    /** Starts an incremental keyed digest of content under {@code domain}. */
    public Keyed content(String domain) {
        if (domain == null || domain.isEmpty() || domain.indexOf('\0') >= 0)
            throw new IllegalArgumentException("content fingerprint domain");
        return new Keyed(domain);
    }

    /** The keyed digest of {@code data[offset, offset + length)} under {@code domain}; see {@link Keyed}. */
    public String ofContent(String domain, byte[] data, int offset, int length) {
        Keyed keyed = content(domain);
        keyed.update(data, offset, length);
        return keyed.finish();
    }
}
