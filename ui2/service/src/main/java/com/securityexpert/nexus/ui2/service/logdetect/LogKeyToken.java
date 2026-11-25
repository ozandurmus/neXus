package com.securityexpert.nexus.ui2.service.logdetect;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A keyed, one-way, domain-separated token of a word that is not in a closed vocabulary (design sections 3.2 and 8.3:
 * "kept as a keyed token"). HMAC-SHA256 under the product privacy key, 12 hex characters with the {@code k-} prefix;
 * equality only, never reversible, never shown as the word.
 */
public final class LogKeyToken implements Function<String, String> {
    private static final byte[] DOMAIN = "log-detection-key-token-v1\0".getBytes(StandardCharsets.UTF_8);
    private final byte[] key;

    public LogKeyToken(byte[] privacyKey) {
        this.key = Objects.requireNonNull(privacyKey, "privacyKey").clone();
    }

    @Override
    public String apply(String word) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DOMAIN);
            mac.update(word.getBytes(StandardCharsets.UTF_8));
            return "k-" + HexFormat.of().formatHex(mac.doFinal(), 0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
