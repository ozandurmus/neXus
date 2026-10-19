package com.securityexpert.nexus.ui2.sanitizer.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.crypto.Mac;

/**
 * The one-way keyed identity tokens the inventory screens show: a serial becomes {@code SN-} + 10 upper-case hex and
 * any other identity value {@code KIND-} + 10 upper-case hex (a user {@code USR-}, an unclassified identity
 * {@code ID-}), each the first 5 bytes of HMAC-SHA256 under the product's privacy key. This is the single
 * implementation: {@code TopologyNamePseudonymizer#maskSerial} and {@code #maskPayloadToken} delegate here, so a
 * serial reads the same in the UI, in the masked system-log view and in a sanitized file. No vault: a token cannot be
 * turned back into its value. Thread-safe; {@link #toString()} prints nothing of the key.
 */
public final class IdentityTokens {

    /** The kind of a principal (user) token. */
    public static final String USER = "USR";
    /** The kind of an identity value that is neither a serial, an address nor a known name. */
    public static final String IDENTITY = "ID";

    private final byte[] key;
    private final ThreadLocal<Mac> mac;

    /** @param privacyKey the product's privacy HMAC key ({@code HmacKeyProvider#getSecretKey()}) */
    public IdentityTokens(byte[] privacyKey) {
        this.key = Objects.requireNonNull(privacyKey, "privacyKey").clone();
        this.mac = ThreadLocal.withInitial(this::newMac);
    }

    /** {@code SN-} + 10 hex of the stripped value; a null or blank value is returned as it is. */
    public String serial(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        return "SN-" + hex(mac.get().doFinal(("SERIAL:" + raw.strip()).getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code KIND-} + 10 hex of the exact value; a null or empty value is returned as it is. */
    public String payload(String kind, String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        return kind + "-" + hex(mac.get().doFinal(("PAYLOAD:" + kind + ":" + raw).getBytes(StandardCharsets.UTF_8)));
    }

    /** A user token ({@code USR-}). */
    public String user(String raw) {
        return payload(USER, raw);
    }

    /** True for an {@code SN-}, {@code USR-} or {@code ID-} token as this class writes them. */
    public static boolean isToken(CharSequence text) {
        int dash = -1;
        for (int i = 0; i < text.length() && i < 4; i++) {
            if (text.charAt(i) == '-') {
                dash = i;
                break;
            }
        }
        if (dash < 0 || text.length() != dash + 11) return false;
        String kind = text.subSequence(0, dash).toString();
        if (!kind.equals("SN") && !kind.equals(USER) && !kind.equals(IDENTITY)) return false;
        for (int i = dash + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    /** A fresh HMAC-SHA256 under the privacy key, for the estate dictionary's digests (same package only). */
    Mac newMac() {
        try {
            return SanitizerKey.mac(key);
        } catch (RuntimeException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder out = new StringBuilder(10);
        for (int i = 0; i < 5; i++) out.append(String.format("%02X", hash[i]));
        return out.toString();
    }

    @Override
    public String toString() {
        return "IdentityTokens[redacted]";
    }
}
