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
}
