package com.securityexpert.nexus.ui2.sanitizer.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The sanitizer's keyed PRF (DATA_SANITIZER_CONTRACT.md §2 "Key"): the product's privacy HMAC key
 * ({@code ui2-privacy-hmac-key}), from which one sub-key per mapping family is derived as
 * {@code HMAC-SHA256(key, "nexus-sanitizer/v1/" + family)}, so an IP flip bit can never equal a label digest.
 * The raw key never leaves this object; {@link #toString()} prints nothing of it.
 */
public final class SanitizerKey {

    private static final String DOMAIN = "nexus-sanitizer/v1/";
    private final byte[] key;

    private SanitizerKey(byte[] key) {
        this.key = key;
    }

    /** @param key at least 32 bytes (the privacy key, or a test key) */
    public static SanitizerKey of(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException("the sanitizer key must hold at least 32 bytes");
        }
        return new SanitizerKey(key.clone());
    }

    /** The sub-key for one mapping family ({@code ip}, {@code mac}, {@code name}, {@code dns}). */
    public byte[] subKey(String family) {
        return hmac(key, (DOMAIN + family).getBytes(StandardCharsets.UTF_8));
    }

    /** The inventory's one-way identity tokens ({@code SN-}, {@code USR-}) under this same key. */
    public IdentityTokens identityTokens() {
        return new IdentityTokens(key);
    }

    /** A fresh HMAC-SHA256 instance keyed with {@code key}; callers keep one per thread. */
    public static Mac mac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        return mac(key).doFinal(data);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SanitizerKey that && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "SanitizerKey[redacted]";
    }
}
