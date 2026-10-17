package com.securityexpert.nexus.ui2.worker.systemlog;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 anchor hasher producing 64 lower-case hex characters. */
public final class HmacSha256AnchorHasher implements AnchorHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public HmacSha256AnchorHasher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length < 16) {
            throw new IllegalArgumentException("the anchor key must be at least 16 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), ALGORITHM);
    }

    @Override
    public String hash(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
