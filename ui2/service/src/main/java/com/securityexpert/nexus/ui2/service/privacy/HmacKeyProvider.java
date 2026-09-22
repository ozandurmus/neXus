package com.securityexpert.nexus.ui2.service.privacy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Provides a durable per-installation HMAC secret key for privacy-preserving
 * pseudonymization (C9 contract §5, PRIVATE_REPLAY_ARCHITECTURE §152).
 */
@Component
public class HmacKeyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(HmacKeyProvider.class);
    private static final int KEY_LENGTH_BYTES = 32;

    private final byte[] secretKey;

    @org.springframework.beans.factory.annotation.Autowired
    public HmacKeyProvider() {
        this(resolveKey());
    }

    public HmacKeyProvider(byte[] secretKey) {
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey").clone();
    }

    public byte[] getSecretKey() {
        return secretKey.clone();
    }

    private static byte[] resolveKey() {
        String keyFile = System.getenv("UI2_PRIVACY_HMAC_KEY_FILE");
        if (keyFile != null && !keyFile.isBlank()) {
            return readKeyFile(Path.of(keyFile.trim()));
        }
        String envKey = System.getenv("NEXUS_PRIVACY_HMAC_KEY");
        if (envKey != null && !envKey.isBlank()) {
            try {
                return Base64.getDecoder().decode(envKey.trim());
            } catch (IllegalArgumentException ignored) {
                return envKey.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        String homeDir = System.getProperty("user.home", ".");
        Path keyFilePath = Path.of(homeDir, ".nexus", "privacy_hmac.key");
        try {
            if (Files.exists(keyFilePath)) {
                byte[] keyBytes = Files.readAllBytes(keyFilePath);
                if (keyBytes.length >= KEY_LENGTH_BYTES) {
                    return keyBytes;
                }
            }
            Files.createDirectories(keyFilePath.getParent());
            byte[] generated = new byte[KEY_LENGTH_BYTES];
            new SecureRandom().nextBytes(generated);
            Files.write(keyFilePath, generated);
            LOG.info("Generated new durable privacy HMAC key at {}", keyFilePath);
            return generated;
        } catch (IOException e) {
            LOG.warn("Could not persist privacy HMAC key to {}, using ephemeral random key: {}", keyFilePath, e.getMessage());
            byte[] ephemeral = new byte[KEY_LENGTH_BYTES];
            new SecureRandom().nextBytes(ephemeral);
            return ephemeral;
        }
    }

    /**
     * The deployed key (25-secret-privacy-hmac-key.yaml, mounted read-only): base64 text or raw bytes, at least
     * 32 bytes. Named but missing, unreadable or short stops start-up (C1 §6 secret-file rule): a random fallback
     * would re-key every pseudonym on each restart, which is the defect this file exists to prevent.
     */
    static byte[] readKeyFile(Path path) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("privacy HMAC key file named by UI2_PRIVACY_HMAC_KEY_FILE is unreadable", e);
        }
        String text = new String(raw, java.nio.charset.StandardCharsets.US_ASCII).strip();
        byte[] key = raw;
        if (text.matches("[A-Za-z0-9+/=]+")) {
            try {
                key = Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException ignored) {
                key = raw;
            }
        }
        if (key.length < KEY_LENGTH_BYTES) {
            throw new IllegalStateException("privacy HMAC key file named by UI2_PRIVACY_HMAC_KEY_FILE holds fewer than "
                    + KEY_LENGTH_BYTES + " bytes");
        }
        return key;
    }
}
