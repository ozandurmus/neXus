package com.securityexpert.nexus.ui2.service.logdetect;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Template ids of layer B (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 4.1 step 7, Argon finding 6): a keyed
 * HMAC-SHA256 of (source, normaliser version, normalised token sequence), computed <b>before</b> the closed-vocabulary
 * filter and keyed with the product privacy key, so the id cannot be reversed by a dictionary attack on the text.
 * A catalog template id is {@code t} + 31 hex, a provisional id (a line that matched no frozen template, step 6) is
 * {@code p} + 31 hex. Domain-separated from {@link LogKeyToken}.
 */
public final class LogTemplateIds {
    private static final byte[] DOMAIN = "log-detection-template-id-v1\0".getBytes(StandardCharsets.UTF_8);
    private final byte[] key;

    public LogTemplateIds(byte[] privacyKey) {
        this.key = Objects.requireNonNull(privacyKey, "privacyKey").clone();
    }

    public String catalogId(String sourceKey, List<String> tokens) {
        return "t" + digest(sourceKey, tokens);
    }

    public String provisionalId(String sourceKey, List<String> tokens) {
        return "p" + digest(sourceKey, tokens);
    }

    private String digest(String sourceKey, List<String> tokens) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(DOMAIN);
            mac.update((sourceKey + "\0" + LogTemplateNormaliser.VERSION + "\0" + String.join(" ", tokens))
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal()).substring(0, 31);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
