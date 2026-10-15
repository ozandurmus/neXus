package com.securityexpert.nexus.ui2.service.sanitizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.sanitizer.SanitizerRepository;
import com.securityexpert.nexus.ui2.sanitizer.Sanitizer;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.format.UnsupportedInputException;
import com.securityexpert.nexus.ui2.sanitizer.pcap.PcapException;

/**
 * The Data Sanitizer inside neXus (docs/design/DATA_SANITIZER_CONTRACT.md §3): sanitize and revert jobs over the
 * engine in {@code ui2/sanitizer}, with the mapping vault behind them.
 *
 * <p><b>Temporary files, never retained.</b> An upload is streamed into the work area ({@code <id>.in}, owner-only
 * permissions), processed on a small executor, and deleted as soon as processing ends -- success or failure. The
 * result ({@code <id>.out}) is kept only until it is downloaded once or until {@code outputTtl} passes, whichever
 * comes first; a service start empties the work area and closes interrupted jobs.</p>
 *
 * <p><b>Hook points</b> for other features, both synchronous and off by default where they are wired
 * (DATA_SANITIZER_CONTRACT.md §3.2/§3.3, documented there):</p>
 * <ul>
 *   <li>{@link #sanitizeFile} -- Packet Analysis "anonymize on upload": sanitize a capture (or any supported file)
 *       before it is stored; the tokens go to the vault under a job of the uploading actor.</li>
 *   <li>{@link #sanitizeLogExport} -- system-log export: the text path on a stream.</li>
 * </ul>
 */
public final class SanitizerService {

    /** Why a request was refused before a job existed; the controller maps the code to an HTTP status. */
    public static final class Refusal extends RuntimeException {
        private final String code;

        public Refusal(String code, String reason) {
            super(reason);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final String TOO_LARGE = "FILE_TOO_LARGE";
    public static final String BUSY = "SANITIZER_BUSY";
    public static final String VAULT_UNAVAILABLE = "VAULT_UNAVAILABLE";
    public static final String NOT_FOUND = "JOB_NOT_FOUND";
    public static final String NOT_OWNER = "NOT_JOB_OWNER";
    public static final String OUTPUT_GONE = "OUTPUT_GONE";

    public enum Scope { OWN_JOBS, ALL_JOBS, ONE_JOB }

    private final SanitizerRepository repository;
    private final VaultCrypto crypto;
    private final byte[] privacyKey;
    private final Supplier<KnownNames> knownNames;
    private final Path workDir;
    private final long maxBytes;
    private final int retentionDays;
    private final Duration outputTtl;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();

    public SanitizerService(SanitizerRepository repository, VaultCrypto crypto, byte[] privacyKey,
            Supplier<KnownNames> knownNames, Path workDir, long maxBytes, int retentionDays, Duration outputTtl,
            ExecutorService executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.crypto = crypto;
        this.privacyKey = privacyKey.clone();
        this.knownNames = knownNames == null ? () -> KnownNames.NONE : knownNames;
        this.workDir = workDir;
        this.maxBytes = maxBytes;
        this.retentionDays = retentionDays;
        this.outputTtl = outputTtl;
        this.executor = executor;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public Duration outputTtl() {
        return outputTtl;
    }

    public boolean vaultAvailable() {
        return crypto != null;
    }

    /** Empties the work area and closes jobs a previous process left running (single-replica service). */
    public void start() throws IOException {
        Files.createDirectories(workDir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(workDir)) {
            for (Path file : files) Files.deleteIfExists(file);
        }
        repository.closeInterrupted();
    }

    // ---- sanitize ------------------------------------------------------------------------------------------

    /** Streams the upload to the work area and queues the job; returns its id. */
    public String startSanitize(String actor, InputStream upload) throws IOException {
        if (crypto == null) throw new Refusal(VAULT_UNAVAILABLE, "the mapping vault needs the artefact-store key");
        String jobId = UUID.randomUUID().toString();
        Path in = receive(jobId, upload);
        VaultCrypto.JobKey key = crypto.newJobKey();
        try {
            repository.createJob(jobId, actor, key.wrapped(), key.keyId(), retentionDays);
            executor.execute(() -> runSanitize(jobId, actor, key, in));
        } catch (RejectedExecutionException full) {
            Files.deleteIfExists(in);
            repository.finishJob(jobId, actor, false, null, 0, 0, "{}", BUSY, 0);
            throw new Refusal(BUSY, "two jobs are running and the queue is full; retry later");
        } catch (RuntimeException failed) {
            Files.deleteIfExists(in);
            throw failed;
        }
        return jobId;
    }

    private void runSanitize(String jobId, String actor, VaultCrypto.JobKey key, Path in) {
        Path out = output(jobId);
        long inputBytes = size(in);
        JobVault vault = new JobVault(repository, crypto, key, jobId, actor);
        try {
            Sanitizer.Result result = engine().sanitize(in, out, vault);
            vault.flush();
            repository.finishJob(jobId, actor, true, fileType(result), inputBytes, size(out), counts(result.counts()), null,
                    vault.written());
        } catch (Exception failed) {
            deleteQuietly(out);
            repository.finishJob(jobId, actor, false, null, inputBytes, 0, "{}", reason(failed), vault.written());
        } finally {
            deleteQuietly(in);
        }
    }

    public Optional<SanitizerRepository.JobRow> job(String jobId) {
        return repository.findJob(jobId);
    }

    /** A job the actor may see: its creator's, or any for a security administrator. */
    public SanitizerRepository.JobRow visibleJob(String jobId, String actor, boolean securityAdmin) {
        SanitizerRepository.JobRow row = repository.findJob(jobId).orElseThrow(() -> new Refusal(NOT_FOUND, "no such job"));
        if (!securityAdmin && !row.createdBy().equals(actor)) throw new Refusal(NOT_OWNER, "only the job's creator or a security administrator");
        return row;
    }

    /** Opens the sanitized file for its one download; the file is deleted when the returned stream closes. */
    public InputStream openOutput(String jobId, String actor, boolean securityAdmin) throws IOException {
        visibleJob(jobId, actor, securityAdmin);
        Path out = output(jobId);
        if (!Files.isRegularFile(out) || !repository.markOutput(jobId, actor, "DOWNLOADED")) {
            throw new Refusal(OUTPUT_GONE, "the sanitized file was already downloaded or has expired");
        }
        return deletingStream(out);
    }

    // ---- revert --------------------------------------------------------------------------------------------

    /**
     * Streams the returned file to the work area and queues its revert. {@link Scope#ALL_JOBS} is for security
     * administrators only; {@link Scope#ONE_JOB} needs the actor to own that job unless they are one; everyone else
     * reverts against the vaults of their own sanitize jobs ({@link Scope#OWN_JOBS}).
     */
    public String startRevert(String actor, InputStream upload, Scope scope, String sourceJobId, boolean securityAdmin)
            throws IOException {
        if (crypto == null) throw new Refusal(VAULT_UNAVAILABLE, "the mapping vault needs the artefact-store key");
        if (scope == Scope.ALL_JOBS && !securityAdmin) throw new Refusal(NOT_OWNER, "reverting against every vault needs security_admin");
        if (scope == Scope.ONE_JOB) visibleJob(sourceJobId, actor, securityAdmin);
        String createdBy = scope == Scope.OWN_JOBS ? actor : null;
        String restrictTo = scope == Scope.ONE_JOB ? sourceJobId : null;
        String revertId = UUID.randomUUID().toString();
        Path in = receive(revertId, upload);
        try {
            repository.createRevert(revertId, actor, scope.name(), restrictTo);
            executor.execute(() -> runRevert(revertId, actor, createdBy, restrictTo, in));
        } catch (RejectedExecutionException full) {
            Files.deleteIfExists(in);
            repository.finishRevert(revertId, actor, false, null, 0, 0, "{}", BUSY, 0);
            throw new Refusal(BUSY, "two jobs are running and the queue is full; retry later");
        } catch (RuntimeException failed) {
            Files.deleteIfExists(in);
            throw failed;
        }
        return revertId;
    }

    private void runRevert(String revertId, String actor, String createdBy, String jobId, Path in) {
        Path out = output(revertId);
        long inputBytes = size(in);
        try {
            Sanitizer.Result result = engine().revert(in, out, new ScopedResolver(repository, crypto, createdBy, jobId));
            repository.finishRevert(revertId, actor, true, fileType(result), inputBytes, size(out), counts(result.counts()),
                    null, result.counts().getOrDefault("reverted", 0L));
        } catch (Exception failed) {
            deleteQuietly(out);
            repository.finishRevert(revertId, actor, false, null, inputBytes, 0, "{}", reason(failed), 0);
        } finally {
            deleteQuietly(in);
        }
    }

    public SanitizerRepository.RevertRow visibleRevert(String revertId, String actor, boolean securityAdmin) {
        SanitizerRepository.RevertRow row = repository.findRevert(revertId).orElseThrow(() -> new Refusal(NOT_FOUND, "no such revert"));
        if (!securityAdmin && !row.createdBy().equals(actor)) throw new Refusal(NOT_OWNER, "only the revert's creator or a security administrator");
        return row;
    }

    public InputStream openRevertOutput(String revertId, String actor, boolean securityAdmin) throws IOException {
        visibleRevert(revertId, actor, securityAdmin);
        Path out = output(revertId);
        if (!Files.isRegularFile(out) || !repository.markRevertOutput(revertId, actor, "DOWNLOADED")) {
            throw new Refusal(OUTPUT_GONE, "the reverted file was already downloaded or has expired");
        }
        return deletingStream(out);
    }

    // ---- hook points ---------------------------------------------------------------------------------------

    /**
     * Synchronous sanitize of a file another feature holds (Packet Analysis "anonymize on upload"): records a job of
     * {@code actor} with its vault, writes {@code output}, returns the engine result. The caller owns both paths.
     */
    public Sanitizer.Result sanitizeFile(Path input, Path output, String actor) throws IOException {
        if (crypto == null) throw new Refusal(VAULT_UNAVAILABLE, "the mapping vault needs the artefact-store key");
        String jobId = UUID.randomUUID().toString();
        VaultCrypto.JobKey key = crypto.newJobKey();
        repository.createJob(jobId, actor, key.wrapped(), key.keyId(), retentionDays);
        JobVault vault = new JobVault(repository, crypto, key, jobId, actor);
        try {
            Sanitizer.Result result = engine().sanitize(input, output, vault);
            vault.flush();
            repository.finishJob(jobId, actor, true, fileType(result), size(input), size(output), counts(result.counts()),
                    null, vault.written());
            repository.markOutput(jobId, actor, "DOWNLOADED"); // handed to the caller, never served from here
            return result;
        } catch (IOException | RuntimeException failed) {
            repository.finishJob(jobId, actor, false, null, size(input), 0, "{}", reason(failed), vault.written());
            throw failed;
        }
    }

    /** Synchronous text path for the system-log export: lines in, sanitized lines out, tokens to the vault. */
    public Sanitizer.Result sanitizeLogExport(InputStream in, OutputStream out, String actor) throws IOException {
        if (crypto == null) throw new Refusal(VAULT_UNAVAILABLE, "the mapping vault needs the artefact-store key");
        String jobId = UUID.randomUUID().toString();
        VaultCrypto.JobKey key = crypto.newJobKey();
        repository.createJob(jobId, actor, key.wrapped(), key.keyId(), retentionDays);
        JobVault vault = new JobVault(repository, crypto, key, jobId, actor);
        Sanitizer.Result result = engine().sanitizeText(in, out, vault);
        vault.flush();
        repository.finishJob(jobId, actor, true, "text", 0, 0, counts(result.counts()), null, vault.written());
        repository.markOutput(jobId, actor, "DOWNLOADED");
        return result;
    }

    // ---- housekeeping --------------------------------------------------------------------------------------

    /** Deletes results older than the output TTL and marks them expired; returns how many went. */
    public int expireOutputs() throws IOException {
        int expired = 0;
        Instant cutoff = Instant.now().minus(outputTtl);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(workDir, "*.out")) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) continue;
                String id = file.getFileName().toString().replace(".out", "");
                Files.deleteIfExists(file);
                if (!repository.markOutput(id, SanitizerRepository.SYSTEM_ACTOR, "EXPIRED")) {
                    repository.markRevertOutput(id, SanitizerRepository.SYSTEM_ACTOR, "EXPIRED");
                }
                expired++;
            }
        }
        return expired;
    }

    public int pruneVaults() {
        return repository.pruneExpiredVaults(1000);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private Sanitizer engine() {
        return new Sanitizer(new Mappers(SanitizerKey.of(privacyKey), knownNames.get()));
    }

    private Path receive(String id, InputStream upload) throws IOException {
        Files.createDirectories(workDir);
        Path in = workDir.resolve(id + ".in");
        try {
            Files.createFile(in, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException noPosix) {
            Files.createFile(in);
        }
        long total = 0;
        try (OutputStream out = Files.newOutputStream(in, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = upload.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) throw new Refusal(TOO_LARGE, "a file may be at most " + maxBytes + " bytes");
                out.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(in);
            throw failed;
        }
        if (total == 0) {
            Files.deleteIfExists(in);
            throw new Refusal("EMPTY_FILE", "the uploaded file is empty");
        }
        return in;
    }

    private Path output(String id) {
        return workDir.resolve(id + ".out");
    }

    private static InputStream deletingStream(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        return new java.io.FilterInputStream(in) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        };
    }

    private String counts(Map<String, Long> counts) {
        try {
            return json.writeValueAsString(counts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("counts are not serialisable", e);
        }
    }

    private static String fileType(Sanitizer.Result result) {
        return result.type().id();
    }

    /** A closed-vocabulary failure code; never the exception's message, which may quote input. */
    static String reason(Throwable failed) {
        if (failed instanceof UnsupportedInputException unsupported) return unsupported.code();
        if (failed instanceof PcapException) return "PCAP_REFUSED";
        if (failed instanceof Refusal refusal) return refusal.code();
        if (failed instanceof UncheckedIOException || failed instanceof IOException) return "PROCESSING_IO_FAILED";
        if (failed instanceof OutOfMemoryError) return "OUT_OF_MEMORY";
        return "PROCESSING_FAILED";
    }

    private static long size(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the housekeeping sweep retries
        }
    }
}
