package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleEnvelope;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.ScheduleCryptographicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Runtime resolution is read-only. Provisioning is an explicit, separate offline operation. */
@Component
public class FailoverKeyManagementService {
    private final Path keyFile;
    private byte[] testSecret;

    public FailoverKeyManagementService() {
        this("");
    }

    @Autowired
    public FailoverKeyManagementService(@Value("${ui2.failover.master-key-file:}") String keyFilePath) {
        this.keyFile = keyFilePath == null || keyFilePath.isBlank() ? null : Path.of(keyFilePath);
    }

    public FailoverKeyManagementService(Path keyFile) {
        this.keyFile = Objects.requireNonNull(keyFile, "keyFile must not be null");
    }

    static FailoverKeyManagementService withFixedSecretForTesting(byte[] secret) {
        FailoverKeyManagementService service = new FailoverKeyManagementService();
        service.testSecret = Objects.requireNonNull(secret, "secret must not be null").clone();
        return service;
    }

    /**
     * Explicit offline bootstrap; never invoked by runtime lookup or application startup.
     * Requires an existing durable directory. CREATE_NEW refuses replacement, including symlinks.
     * A lost key must be restored from custody, never bootstrapped over existing authorizations.
     */
    public static void initializeKey(Path keyFile) {
        if (keyFile == null || !keyFile.isAbsolute() || keyFile.getParent() == null
            || !Files.isDirectory(keyFile.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("KEY_INITIALIZATION_UNAVAILABLE");
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (SeekableByteChannel channel = Files.newByteChannel(keyFile,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer bytes = StandardCharsets.US_ASCII.encode(HexFormat.of().formatHex(secret));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            if (channel instanceof java.nio.channels.FileChannel fileChannel) {
                fileChannel.force(true);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // File paths and key material never enter logs or shareable exception messages.
            throw new IllegalStateException("KEY_INITIALIZATION_UNAVAILABLE");
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    /** No cache: key loss, a missing mount or changed file permissions fail closed on the next use. */
    public Optional<byte[]> resolveMasterSecret() {
        if (testSecret != null) {
            return Optional.of(testSecret.clone());
        }
        if (keyFile == null || !keyFile.isAbsolute()) {
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(keyFile, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rw-------"))) {
                return Optional.empty();
            }
            try (SeekableByteChannel channel = Files.newByteChannel(keyFile,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() != 64) {
                    return Optional.empty();
                }
                ByteBuffer bytes = ByteBuffer.allocate(64);
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0) {
                        return Optional.empty();
                    }
                }
                bytes.flip();
                return Optional.of(HexFormat.of().parseHex(StandardCharsets.US_ASCII.decode(bytes).toString()));
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | SecurityException e) {
            return Optional.empty();
        }
    }

    /** Content-bound identity distinguishes a wrong key mount from altered schedule content. */
    public Optional<String> currentKeyId() {
        return resolveMasterSecret().map(FailoverKeyManagementService::keyIdentifier);
    }

    private static String keyIdentifier(byte[] secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("NEXUS_FAILOVER_KEY_ID_V1".getBytes(StandardCharsets.US_ASCII));
            return "sha256-" + HexFormat.of().formatHex(digest.digest(secret));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("KEY_ALGORITHM_UNAVAILABLE");
        }
    }

    public Optional<ScheduleCryptographicService> resolveCryptoService(String keyId) {
        return resolveCryptoService(keyId, FailoverScheduleEnvelope.DEFAULT_ALG_VERSION);
    }

    public Optional<ScheduleCryptographicService> resolveCryptoService(String keyId, String algorithmVersion) {
        if (keyId == null || !FailoverScheduleEnvelope.DEFAULT_ALG_VERSION.equals(algorithmVersion)) {
            return Optional.empty();
        }
        return resolveMasterSecret().filter(secret -> keyIdentifier(secret).equals(keyId))
            .map(ScheduleCryptographicService::new);
    }
}
