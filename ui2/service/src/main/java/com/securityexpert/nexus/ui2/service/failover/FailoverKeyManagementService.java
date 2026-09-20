package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.ScheduleCryptographicService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CF-P0.5: the Stage 1 engine minted a fresh random HMAC master secret in
 * {@code FailoverScheduleService}'s constructor on every process start. Every
 * schedule signed before a restart failed verification after it -- reported as
 * {@code ABORTED_TAMPERED}, a false tamper finding rather than the true cause
 * (key unavailability from a process restart).
 *
 * <p>Resolution order, first source that yields a non-blank value wins:
 * <ol>
 *   <li>{@code NEXUS_FAILOVER_MASTER_SECRET} environment variable.</li>
 *   <li>{@code nexus.failover.master-secret} system property.</li>
 *   <li>A local storage file (default {@code .nexus_failover_master.key}):
 *   read if present; if absent, a new secret is generated once with
 *   {@link SecureRandom} and persisted so every subsequent process start
 *   (in the same deployment, same file) reads the same key back.</li>
 * </ol>
 *
 * <p>This class never generates a secret that is not also durably readable on
 * the next resolution call -- an ephemeral, unpersisted, process-lifetime-only
 * key is exactly the CF-P0.5 defect. If every source fails (an unreadable or
 * unwritable file, for example), {@link #resolveMasterSecret()} returns
 * {@link Optional#empty()} rather than fabricating one; callers must fail
 * closed to {@code ABORTED_KEY_UNAVAILABLE}, never proceed with a signature
 * that cannot be durably re-verified.</p>
 */
@Component
public class FailoverKeyManagementService {

    private static final Logger LOG = LoggerFactory.getLogger(FailoverKeyManagementService.class);

    static final String ENV_MASTER_SECRET = "NEXUS_FAILOVER_MASTER_SECRET";
    static final String SYSPROP_MASTER_SECRET = "nexus.failover.master-secret";
    private static final String DEFAULT_KEY_FILE = ".nexus_failover_master.key";
    /** Records that this location has already been provisioned once; see resolveFromLocalStorageFile. */
    private static final String PROVISIONED_MARKER_SUFFIX = ".provisioned";
    private static final String PROVISIONED_MARKER_CONTENT =
        "This location was provisioned with a failover signing key. If the key file is missing while this\n"
        + "marker is present, the key was lost: the service fails closed to ABORTED_KEY_UNAVAILABLE rather\n"
        + "than minting a replacement, which would report every existing schedule as tampered.\n";

    private final Path keyFile;
    private final Path provisioningMarker;
    private final Map<String, ScheduleCryptographicService> cryptoServicesByKeyId = new ConcurrentHashMap<>();
    private volatile byte[] resolvedMasterSecret;

    public FailoverKeyManagementService() {
        this(Path.of(DEFAULT_KEY_FILE));
    }

    @Autowired
    public FailoverKeyManagementService(@Value("${ui2.failover.master-key-file:.nexus_failover_master.key}") String keyFilePath) {
        this(Path.of(Objects.requireNonNull(keyFilePath, "keyFilePath must not be null")));
    }

    /** Test/tooling entry point: pin the local storage file to an isolated location. */
    public FailoverKeyManagementService(Path keyFile) {
        this.keyFile = Objects.requireNonNull(keyFile, "keyFile must not be null");
        this.provisioningMarker = this.keyFile.resolveSibling(this.keyFile.getFileName() + PROVISIONED_MARKER_SUFFIX);
    }

    /**
     * Test-only entry point: bypasses env/system-property/local-file resolution entirely and
     * signs with a fixed secret supplied directly by the test. {@code keyFile} is never consulted
     * because {@link #resolveMasterSecret()} short-circuits once {@code resolvedMasterSecret} is
     * already populated.
     */
    static FailoverKeyManagementService withFixedSecretForTesting(byte[] fixedMasterSecret) {
        FailoverKeyManagementService service = new FailoverKeyManagementService(Path.of(DEFAULT_KEY_FILE));
        service.resolvedMasterSecret = Objects.requireNonNull(fixedMasterSecret, "fixedMasterSecret must not be null").clone();
        return service;
    }

    /**
     * Resolves the durable 32-byte HMAC master secret, memoizing the first
     * successful resolution for the lifetime of this instance. Never returns
     * a value that was not also durably persisted (env/system-property
     * sources are already durable by definition; the file source persists
     * before returning).
     */
    public Optional<byte[]> resolveMasterSecret() {
        byte[] cached = resolvedMasterSecret;
        if (cached != null) {
            return Optional.of(cached);
        }
        synchronized (this) {
            if (resolvedMasterSecret != null) {
                return Optional.of(resolvedMasterSecret);
            }
            Optional<byte[]> resolved = resolveFromEnv()
                .or(FailoverKeyManagementService::resolveFromSystemProperty)
                .or(this::resolveFromLocalStorageFile);
            resolved.ifPresent(secret -> resolvedMasterSecret = secret);
            return resolved;
        }
    }

    /**
     * CF-P1.2: resolves a signing/verification service bound to a specific
     * {@code keyId}. Only the {@code "k1"} identity is backed by the durable
     * master secret today -- any other {@code keyId} (a rotated or unknown
     * key) resolves to {@link Optional#empty()}, which is the caller's signal
     * to abort with {@code ABORTED_KEY_UNAVAILABLE} rather than guess a key.
     */
    public Optional<ScheduleCryptographicService> resolveCryptoService(String keyId) {
        Objects.requireNonNull(keyId, "keyId must not be null");
        if (!"k1".equals(keyId)) {
            LOG.warn("failover_key_unavailable: unknown keyId requested (keyId={})", keyId);
            return Optional.empty();
        }
        ScheduleCryptographicService cached = cryptoServicesByKeyId.get(keyId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<byte[]> secret = resolveMasterSecret();
        if (secret.isEmpty()) {
            LOG.warn("failover_key_unavailable: no master secret source resolved (keyId={})", keyId);
            return Optional.empty();
        }
        ScheduleCryptographicService created = new ScheduleCryptographicService(secret.get());
        ScheduleCryptographicService existing = cryptoServicesByKeyId.putIfAbsent(keyId, created);
        return Optional.of(existing != null ? existing : created);
    }

    private static Optional<byte[]> resolveFromEnv() {
        String value = System.getenv(ENV_MASTER_SECRET);
        return (value != null && !value.isBlank()) ? Optional.of(deriveKeyMaterial(value)) : Optional.empty();
    }

    private static Optional<byte[]> resolveFromSystemProperty() {
        String value = System.getProperty(SYSPROP_MASTER_SECRET);
        return (value != null && !value.isBlank()) ? Optional.of(deriveKeyMaterial(value)) : Optional.empty();
    }

    private Optional<byte[]> resolveFromLocalStorageFile() {
        try {
            if (Files.exists(keyFile)) {
                String hex = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
                if (hex.isEmpty()) {
                    LOG.warn("failover_key_unavailable: local storage file is empty (path={})", keyFile);
                    return Optional.empty();
                }
                return Optional.of(HexFormat.of().parseHex(hex));
            }

            // CF-P0.5: a missing key file is only safe to replace the very first time.
            // Generating a fresh key after the key was lost is worse than refusing to
            // start: every schedule sealed under the old key then fails verification and
            // is reported as ABORTED_TAMPERED, so an operational accident -- an
            // unmounted volume, a cleaned directory -- becomes indistinguishable from an
            // attacker having altered a schedule. The provisioning marker records that a
            // key once existed here, and its presence without the key means loss, which
            // fails closed to ABORTED_KEY_UNAVAILABLE.
            if (Files.exists(provisioningMarker)) {
                LOG.warn("failover_key_unavailable: key file is missing but this location was already provisioned; "
                    + "refusing to mint a replacement (path={})", keyFile);
                return Optional.empty();
            }

            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            String hex = HexFormat.of().formatHex(generated);
            Path parent = keyFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyFile, hex, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(provisioningMarker, PROVISIONED_MARKER_CONTENT, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            LOG.info("failover_master_key_generated: persisted a new durable signing key on first provisioning (path={})", keyFile);
            return Optional.of(generated);
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("failover_key_unavailable: local storage file could not be read or created (path={}, cause={})",
                keyFile, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Deterministically maps an arbitrary-length configured secret onto exactly 32 bytes of key material. */
    private static byte[] deriveKeyMaterial(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available: " + e.getMessage(), e);
        }
    }
}
