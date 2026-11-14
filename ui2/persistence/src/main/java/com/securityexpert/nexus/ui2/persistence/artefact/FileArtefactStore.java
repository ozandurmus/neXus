package com.securityexpert.nexus.ui2.persistence.artefact;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.spec.SecretKeySpec;

import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;

/**
 * Filesystem-backed {@link ArtefactStore} (C7 section 4 minimum viable
 * form): {@code <root>/<vendor>/<device_id>/<artefact_ref>.enc}, content
 * addressed by the ciphertext's own SHA-256 (C7 section 3.2's pattern:
 * "{@code artefact_id}... sha256-of-ciphertext identity as value").
 *
 * <p>Write pipeline (14G CG-5: streamed end to end, never one in-memory
 * buffer): the caller's bytes are digested (plaintext hash), optionally
 * gzip-compressed, encrypted ({@link ArtefactStoreCipher#encryptingStream}),
 * digested again (ciphertext hash), and written straight to a temp file;
 * {@link ArtefactStore.ArtefactHandle#finish()} closes that chain and
 * atomically moves the temp file to its final, content-addressed path.</p>
 *
 * <p><b>Enforced byte quota (backlog {@code vault_has_no_enforced_quota_on_a_shared_host}):</b>
 * On a single-node deployment the artefact store is backed by a {@code local-path}
 * host directory whose filesystem capacity is the entire host disk. To prevent
 * backup artefacts from exhausting the shared host volume and starving PostgreSQL
 * or triggering kubelet {@code DiskPressure}, this store enforces a hard byte
 * quota before opening a new artefact and throughout streaming:
 * <ul>
 *   <li>Configurable via {@link #QUOTA_ENV_VAR} ({@code UI2_ARTEFACT_STORE_QUOTA_BYTES}) or
 *       explicitly via {@link #withQuota(Path, ArtefactStoreCipher, long)}. Every pod that
 *       mounts the store (service, worker, policy) resolves the same value from the same
 *       filesystem, so the status view and the enforcing writer agree.</li>
 *   <li>Defaults to {@link #DEFAULT_VOLUME_SHARE} (40%) of the underlying
 *       {@link FileStore#getTotalSpace() volume total space}, capped at
 *       {@link #DEFAULT_MAX_QUOTA_BYTES} (400 GiB, matching {@code 35-artefact-store-pvc.yaml}).
 *       Reserving 60% of a shared host disk leaves headroom for PostgreSQL,
 *       packet captures, system logs, container images, and kubelet's 15%
 *       {@code DiskPressure} eviction threshold.</li>
 *   <li>When usage reaches or exceeds the quota, writes are refused with
 *       {@link ArtefactStore.QuotaExceededException} (reason {@link #QUOTA_EXCEEDED_REASON}),
 *       any in-flight temporary file is immediately deleted (never leaving a
 *       partial file), and existing backups are never deleted automatically.</li>
 *   <li>Usage is the byte total of the files under the root, measured by a tree walk at most
 *       once per {@link #USAGE_MEASURE_INTERVAL_NANOS} per store instance; bytes this instance
 *       commits in between are added to the cached figure. A write by another pod can
 *       therefore go unseen for up to that interval -- the quota is a guard against filling
 *       the disk, not an exact byte ledger.</li>
 * </ul>
 */
public final class FileArtefactStore implements ArtefactStore {

    /** Environment variable overriding the enforced byte quota of the artefact store. */
    public static final String QUOTA_ENV_VAR = "UI2_ARTEFACT_STORE_QUOTA_BYTES";

    /** Stable warning code emitted on the system storage status endpoint when usage crosses {@link #NEAR_LIMIT_WARN_FRACTION}. */
    public static final String NEAR_QUOTA_WARNING_CODE = "artefact_store_near_quota";

    /**
     * Default safe share (40%) of the underlying filesystem's total capacity
     * when no explicit byte quota is configured.
     */
    public static final double DEFAULT_VOLUME_SHARE = 0.40;

    /**
     * Upper cap (400 GiB) on the default volume-share quota, matching the
     * 400 GiB recovery volume budget in {@code 35-artefact-store-pvc.yaml}.
     */
    public static final long DEFAULT_MAX_QUOTA_BYTES = 400L * 1024L * 1024L * 1024L;

    /** Fraction of {@link #quotaBytes()} (80%) at which the platform storage view raises a near-limit warning. */
    public static final double NEAR_LIMIT_WARN_FRACTION = 0.80;

    /** A tree walk of the store measures usage at most this often per instance (30 s). */
    static final long USAGE_MEASURE_INTERVAL_NANOS = 30_000_000_000L;

    /** Largest v1 file the store will still decrypt in memory (a 1 GiB pod keeps ~768 MiB of heap). */
    static final long LEGACY_MAX_BYTES = 200L * 1024 * 1024;

    private final Path root;
    private final ArtefactStoreCipher cipher;
    private final long legacyMaxBytes;
    private final long quotaBytes;
    private final Object usageLock = new Object();
    private long measuredUsedBytes;
    private long measuredAtNanos;
    private boolean measured;

    public FileArtefactStore(Path root, ArtefactStoreCipher cipher) {
        this(root, cipher, LEGACY_MAX_BYTES, resolveQuotaBytes(root, System.getenv()));
    }

    FileArtefactStore(Path root, ArtefactStoreCipher cipher, long legacyMaxBytes) {
        this(root, cipher, legacyMaxBytes, resolveQuotaBytes(root, System.getenv()));
    }

    public FileArtefactStore(Path root, ArtefactStoreCipher cipher, long legacyMaxBytes, long quotaBytes) {
        this.legacyMaxBytes = legacyMaxBytes;
        this.root = Objects.requireNonNull(root, "root");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        if (quotaBytes <= 0) {
            throw new IllegalArgumentException("quotaBytes must be > 0, got: " + quotaBytes);
        }
        this.quotaBytes = quotaBytes;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("could not create artefact store root: " + root, e);
        }
    }

    /** Creates a {@link FileArtefactStore} with an explicit enforced byte quota. */
    public static FileArtefactStore withQuota(Path root, ArtefactStoreCipher cipher, long quotaBytes) {
        return new FileArtefactStore(root, cipher, LEGACY_MAX_BYTES, quotaBytes);
    }

    /** Returns the enforced maximum bytes this artefact store allows on disk. */
    public long quotaBytes() {
        return quotaBytes;
    }

    /** Returns the current bytes stored under {@link #root} (a fresh tree walk). */
    public long usedBytes() {
        return measure(null);
    }

    private long measure(Path excludedTempFile) {
        long used = usedBytesExcluding(root, excludedTempFile);
        synchronized (usageLock) {
            measuredUsedBytes = used;
            measuredAtNanos = System.nanoTime();
            measured = true;
        }
        return used;
    }

    /**
     * Usage for the quota decision, without the caller's own in-flight temp file: the last measurement if younger
     * than the interval, else a fresh walk.
     */
    private long currentUsedBytes(Path ownTempFile) {
        synchronized (usageLock) {
            if (measured && System.nanoTime() - measuredAtNanos < USAGE_MEASURE_INTERVAL_NANOS) {
                return measuredUsedBytes;
            }
        }
        return measure(ownTempFile);
    }

    private void recordCommitted(long bytes) {
        synchronized (usageLock) {
            if (measured) {
                measuredUsedBytes += bytes;
            }
        }
    }

    /** Returns {@code true} when current store usage has reached or exceeded {@link #NEAR_LIMIT_WARN_FRACTION} of {@link #quotaBytes()}. */
    public boolean isNearLimit() {
        return isNearLimit(usedBytes(), quotaBytes);
    }

    /** Returns {@code true} when {@code usedBytes} is at or above 80% ({@link #NEAR_LIMIT_WARN_FRACTION}) of {@code quotaBytes}. */
    public static boolean isNearLimit(long usedBytes, long quotaBytes) {
        if (quotaBytes <= 0 || usedBytes <= 0) {
            return false;
        }
        long threshold = (long) Math.ceil(quotaBytes * NEAR_LIMIT_WARN_FRACTION);
        return usedBytes >= threshold;
    }

    /**
     * Computes the default quota (40% of {@code volumeTotalBytes}, capped at
     * {@link #DEFAULT_MAX_QUOTA_BYTES} = 400 GiB).
     */
    public static long defaultQuotaBytes(long volumeTotalBytes) {
        if (volumeTotalBytes <= 0) {
            return DEFAULT_MAX_QUOTA_BYTES;
        }
        long share = Math.max(1L, Math.round(volumeTotalBytes * DEFAULT_VOLUME_SHARE));
        return Math.min(DEFAULT_MAX_QUOTA_BYTES, share);
    }

    /** Computes the default quota for the filesystem backing {@code root}. */
    public static long defaultQuotaBytes(Path root) {
        Objects.requireNonNull(root, "root");
        try {
            Files.createDirectories(root);
            FileStore fileStore = Files.getFileStore(root);
            return defaultQuotaBytes(fileStore.getTotalSpace());
        } catch (IOException e) {
            return DEFAULT_MAX_QUOTA_BYTES;
        }
    }

    /**
     * Parses an explicit byte quota from {@link #QUOTA_ENV_VAR}, if set. A malformed or non-positive value stops
     * the pod at start-up (fail closed) rather than silently falling back to the default.
     */
    public static Optional<Long> resolveConfiguredQuotaBytes(Map<String, String> env) {
        String raw = env == null ? null : env.get(QUOTA_ENV_VAR);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(QUOTA_ENV_VAR + " is not a valid byte count", e);
        }
        if (parsed <= 0) {
            throw new IllegalStateException(QUOTA_ENV_VAR + " must be a positive byte count");
        }
        return Optional.of(parsed);
    }

    /** Resolves the enforced byte quota from {@code env} if configured, otherwise {@link #defaultQuotaBytes(Path)}. */
    public static long resolveQuotaBytes(Path root, Map<String, String> env) {
        return resolveConfiguredQuotaBytes(env).orElseGet(() -> defaultQuotaBytes(root));
    }

    /** Sums the size in bytes of all regular files currently stored under {@code root}. */
    public static long usedBytes(Path root) {
        return usedBytesExcluding(root, null);
    }

    private static long usedBytesExcluding(Path root, Path excluded) {
        if (root == null || !Files.isDirectory(root)) {
            return 0L;
        }
        Path skip = excluded == null ? null : excluded.toAbsolutePath().normalize();
        long[] total = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && (skip == null || !file.toAbsolutePath().normalize().equals(skip))) {
                        total[0] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A temp file or directory removed by a concurrent writer while the walk runs is expected.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not measure artefact store usage", e);
        }
        return total[0];
    }

    @Override
    public void checkQuota() throws QuotaExceededException {
        long used = currentUsedBytes(null);
        if (used >= quotaBytes) {
            throw new QuotaExceededException(used, quotaBytes, 0L);
        }
    }

    @Override
    public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) throws IOException {
        long usedBeforeOpen = currentUsedBytes(null);
        if (usedBeforeOpen >= quotaBytes) {
            throw new QuotaExceededException(usedBeforeOpen, quotaBytes, 0L);
        }
        Path vendorDir = root.resolve(safeSegment(vendor)).resolve(safeSegment(deviceId));
        Files.createDirectories(vendorDir);
        Path tempFile = Files.createTempFile(vendorDir, "artefact-", ".tmp");
        try {
            return new FileArtefactHandle(tempFile, vendorDir, gzip, usedBeforeOpen);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
            throw t;
        }
    }

    @Override
    public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) throws IOException {
        Path path = pathFor(ref);
        // A v1 (pre-streaming) file is decrypted whole in memory by the JDK's GCM; above this size
        // that is an OutOfMemoryError in a 1 GiB pod (measured live 2026-09-22), so it is refused with
        // a reason instead. A v2 file streams at any size.
        long size = Files.size(path);
        if (size > legacyMaxBytes) {
            byte[] head;
            try (InputStream probe = Files.newInputStream(path)) {
                head = probe.readNBytes(4);
            }
            if (!ArtefactStoreCipher.isStreamable(head)) {
                throw new IOException("artefact was stored in the pre-streaming format and is larger than can be "
                        + "decrypted in memory (" + size + " bytes); take a new backup of this device");
            }
        }
        SecretKeySpec dataKey = cipher.unwrapKey(wrappedDataKey);
        InputStream decrypted = cipher.decryptingStream(Files.newInputStream(path), dataKey);
        return gzip ? new GZIPInputStream(decrypted) : decrypted;
    }

    private Path pathFor(ArtefactRef ref) {
        // ref.value() is "<vendor>/<device_id>/<sha256hex>" -- see FileArtefactHandle.finish().
        return root.resolve(ref.value());
    }

    private static String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private final class FileArtefactHandle implements ArtefactHandle {

        private final Path tempFile;
        private final Path vendorDeviceDir;
        private final boolean gzip;
        private final SecretKeySpec dataKey;
        private final OutputStream fileOut;
        private final CountingDigestOutputStream plaintextCounter;
        private final CountingDigestOutputStream ciphertextCounter;
        private final OutputStream sink;
        private boolean finished;
        private boolean committed;

        FileArtefactHandle(Path tempFile, Path vendorDeviceDir, boolean gzip, long usedBeforeOpen) throws IOException {
            this.tempFile = tempFile;
            this.vendorDeviceDir = vendorDeviceDir;
            this.gzip = gzip;
            // BK-15: a fresh, random data key per artefact -- the master key
            // never encrypts artefact bytes directly.
            this.dataKey = cipher.generateDataKey();
            this.fileOut = Files.newOutputStream(tempFile);
            this.ciphertextCounter = new CountingDigestOutputStream(fileOut, usedBeforeOpen, quotaBytes, tempFile);
            OutputStream cipherOut = cipher.encryptingStream(ciphertextCounter, dataKey);
            OutputStream compressOut = gzip ? new GZIPOutputStream(cipherOut) : cipherOut;
            this.plaintextCounter = new CountingDigestOutputStream(compressOut);
            this.sink = plaintextCounter;
        }

        @Override
        public OutputStream sink() {
            return sink;
        }

        @Override
        public ArtefactMetadata finish() throws IOException {
            if (finished) {
                throw new IllegalStateException("finish() already called on this artefact handle");
            }
            finished = true;
            try {
                sink.close(); // closes the whole chain: gzip finish -> cipher doFinal (GCM tag) -> file close
                long ciphertextBytes = ciphertextCounter.count();
                // Re-checked against the (possibly refreshed) usage: other writers may have committed since open().
                long otherBytes = currentUsedBytes(tempFile);
                if (otherBytes + ciphertextBytes > quotaBytes) {
                    throw new QuotaExceededException(otherBytes, quotaBytes, ciphertextBytes);
                }
                String ciphertextSha256 = ciphertextCounter.digestHex();
                Path finalPath = vendorDeviceDir.resolve(ciphertextSha256 + ".enc");
                Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
                committed = true;
                recordCommitted(ciphertextBytes);
                ArtefactRef ref = new ArtefactRef(root.relativize(finalPath).toString());
                return new ArtefactMetadata(ref, plaintextCounter.digestHex(), plaintextCounter.count(), ciphertextSha256,
                        ciphertextBytes, gzip ? "gzip" : "none", cipher.keyId(), cipher.wrapKey(dataKey));
            } finally {
                if (!committed) {
                    closeAndDeleteTempFile();
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (!committed) {
                if (!finished && !ciphertextCounter.isAborted()) {
                    try {
                        sink.close();
                    } catch (IOException ignored) {
                        // Best-effort teardown when closing an unfinished handle; tempFile is deleted below.
                    }
                }
                closeAndDeleteTempFile();
            }
        }

        private void closeAndDeleteTempFile() {
            try {
                fileOut.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    /** Counts bytes written and digests them (SHA-256) in one pass -- no second read of the data. */
    private static final class CountingDigestOutputStream extends FilterOutputStream {

        private final MessageDigest digest;
        private final boolean enforceQuota;
        private final long usedBeforeOpen;
        private final long quotaBytes;
        private final Path tempFile;
        private final OutputStream rawOut;
        private long count;
        private boolean aborted;
        private QuotaExceededException abortCause;

        CountingDigestOutputStream(OutputStream out) {
            this(out, false, 0L, Long.MAX_VALUE, null);
        }

        CountingDigestOutputStream(OutputStream out, long usedBeforeOpen, long quotaBytes, Path tempFile) {
            this(out, true, usedBeforeOpen, quotaBytes, tempFile);
        }

        private CountingDigestOutputStream(OutputStream out, boolean enforceQuota, long usedBeforeOpen,
                long quotaBytes, Path tempFile) {
            super(wrapWithDigest(out, newSha256()));
            this.rawOut = out;
            this.digest = ((DigestOutputStream) this.out).getMessageDigest();
            this.enforceQuota = enforceQuota;
            this.usedBeforeOpen = usedBeforeOpen;
            this.quotaBytes = quotaBytes;
            this.tempFile = tempFile;
        }

        private static OutputStream wrapWithDigest(OutputStream out, MessageDigest digest) {
            return new DigestOutputStream(out, digest);
        }

        private static MessageDigest newSha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            ensureWithinQuota(1L);
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return;
            }
            ensureWithinQuota(len);
            out.write(b, off, len);
            count += len;
        }

        private void ensureWithinQuota(long additionalBytes) throws QuotaExceededException {
            if (!enforceQuota) {
                return;
            }
            if (aborted) {
                throw abortCause;
            }
            long projectedTotal = usedBeforeOpen + count + additionalBytes;
            if (projectedTotal > quotaBytes) {
                aborted = true;
                abortCause = new QuotaExceededException(usedBeforeOpen, quotaBytes, count + additionalBytes);
                try {
                    rawOut.close();
                } catch (IOException ignored) {
                }
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                    }
                }
                throw abortCause;
            }
        }

        boolean isAborted() {
            return aborted;
        }

        long count() {
            return count;
        }

        String digestHex() {
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}
