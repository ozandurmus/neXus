package com.securityexpert.nexus.ui2.worker.backup.cp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.backup.BackupTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/** Device push into a dedicated chroot; only the uploaded ZIP proves success until the first appliance run. */
public final class QuantumSparkBackupExecutor {
    private static final System.Logger LOG = System.getLogger(QuantumSparkBackupExecutor.class.getName());
    private static final Duration CONNECT = Duration.ofSeconds(30);
    private static final Duration READ = Duration.ofSeconds(30);
    private static final Duration PUSH = Duration.ofSeconds(180);
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final DeviceTransport transport;
    private final ArtefactStore store;
    private final Path inbox;
    private final String receiverHost;
    private final Function<String, char[]> passwordOf;
    private final Duration uploadWait;
    private final SecureRandom random = new SecureRandom();

    public QuantumSparkBackupExecutor(DeviceTransport transport, ArtefactStore store, Path inbox, String receiverHost,
            Function<String, char[]> passwordOf) {
        this(transport, store, inbox, receiverHost, passwordOf, Duration.ofSeconds(180));
    }

    QuantumSparkBackupExecutor(DeviceTransport transport, ArtefactStore store, Path inbox, String receiverHost,
            Function<String, char[]> passwordOf, Duration uploadWait) {
        this.transport = Objects.requireNonNull(transport);
        this.store = Objects.requireNonNull(store);
        this.inbox = inbox;
        this.receiverHost = receiverHost;
        this.passwordOf = Objects.requireNonNull(passwordOf);
        this.uploadWait = Objects.requireNonNull(uploadWait);
    }

    public BackupResult collect(BackupRequest request, Optional<String> receiverCredentialRef, String deviceId, String jobId) {
        return BackupTranscript.record("Spark backup", () -> collectRecorded(request, receiverCredentialRef, deviceId, jobId));
    }

    private BackupResult collectRecorded(BackupRequest request, Optional<String> receiverCredentialRef, String deviceId, String jobId) {
        if (inbox == null || !Files.isDirectory(inbox) || receiverHost == null || receiverHost.isBlank())
            return new BackupResult.CredentialUnresolvable("Spark SFTP receiver is not configured");
        if (receiverCredentialRef.isEmpty() || request.credentialRef().isEmpty())
            return new BackupResult.CredentialUnresolvable("Spark backup or receiver credential is missing");
        char[] receiverPassword;
        try {
            receiverPassword = passwordOf.apply(receiverCredentialRef.get());
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("Spark receiver credential cannot be resolved");
        }
        if (receiverPassword == null) return new BackupResult.CredentialUnresolvable("Spark receiver credential is empty");
        try {
            ConnectResult connected = transport.connect(request.connectionTarget(),
                    new ConnectSpec(request.credentialRef().get(), request.trustRuleRef(), Optional.empty()), CONNECT);
            if (!(connected instanceof ConnectResult.Authenticated authenticated))
                return new BackupResult.ConnectFailed("Spark SSH connection failed: " + connected.getClass().getSimpleName());
            try {
                return run(authenticated.session(), receiverPassword, deviceId, jobId);
            } finally {
                transport.disconnect(authenticated.session());
            }
        } catch (IllegalStateException e) {
            return new BackupResult.CredentialUnresolvable("Spark backup credential cannot be resolved");
        } finally {
            Arrays.fill(receiverPassword, '\0');
        }
    }

    private BackupResult run(TransportSession session, char[] receiverPassword, String deviceId, String jobId) {
        byte[] tokenBytes = new byte[8];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        String encryptionPassword = randomPassword();
        Path upload = inbox.resolve(token + ".zip");
        try {
            String beforeShape = logShape("before", transport.execInteractive(session, new ExecSpec(QuantumSparkBackupPlan.LOG), READ));
            String command;
            try {
                command = QuantumSparkBackupPlan.command(receiverHost, token, encryptionPassword, receiverPassword);
            } catch (IllegalArgumentException e) {
                return new BackupResult.CredentialUnresolvable("Spark receiver parameters are not valid for clish");
            }
            ExecResult pushed = transport.execInteractive(session, new ExecSpec(command), PUSH);
            String firstOutput = JobTranscript.lineShape(output(pushed));
            LOG.log(System.Logger.Level.INFO, "[SPARK_BACKUP] push outcome={0} first_line_shape={1}",
                    pushed.getClass().getSimpleName(), firstOutput);
            JobTranscriptScope.add("ssh", "note", "Spark push first_line_shape=" + firstOutput);
            JobTranscriptScope.add("sftp", "transfer", "waiting for upload file=" + token + ".zip");
            boolean present = waitForUpload(upload);
            BackupTranscript.note("Spark upload present=" + present);
            String afterShape = logShape("after", transport.execInteractive(session, new ExecSpec(QuantumSparkBackupPlan.LOG), READ));
            LOG.log(System.Logger.Level.INFO, "[SPARK_BACKUP] log leading_line_shape_changed={0}",
                    !beforeShape.equals(afterShape));
            if (!present) return new BackupResult.SubmitRefused("the appliance did not upload the backup (" + firstOutput + ")");
            long bytes = Files.size(upload);
            if (bytes <= 1024 || bytes > MAX_BYTES || !zipHeader(upload))
                return new BackupResult.SubmitRefused("the appliance upload did not have the expected ZIP shape");
            ArtefactStore.ArtefactHandle handle = store.open(deviceId, jobId, "check_point", false);
            try {
                try (TarWriter tar = new TarWriter(new GZIPOutputStream(handle.sink()))) {
                    try (OutputStream member = tar.begin("spark-settings.zip", bytes); InputStream source = Files.newInputStream(upload)) {
                        var digest = java.security.MessageDigest.getInstance("SHA-256");
                        source.transferTo(new java.security.DigestOutputStream(member, digest));
                        JobTranscriptScope.add("sftp", "transfer", "received file=" + token + ".zip size=" + bytes
                                + " sha256=" + HexFormat.of().formatHex(digest.digest()));
                    } catch (java.security.NoSuchAlgorithmException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                    tar.file("manifest.txt", ("Quantum Spark settings backup\nspark-settings.zip\nrestore-password.txt\n")
                            .getBytes(StandardCharsets.UTF_8));
                    tar.file("restore-password.txt", (encryptionPassword + "\n").getBytes(StandardCharsets.US_ASCII));
                }
                ArtefactStore.ArtefactMetadata metadata = handle.finish();
                return Files.deleteIfExists(upload)
                        ? new BackupResult.Completed(metadata, "spark-settings.tgz", Optional.empty(), Optional.empty())
                        : new BackupResult.CleanupFailed(metadata, "spark-settings.tgz", "Spark upload could not be removed");
            } catch (IOException e) {
                try { handle.close(); } catch (IOException ignored) { }
                throw e;
            }
        } catch (IOException e) {
            return new BackupResult.ArtefactStoreFailed("Spark upload or store failed: " + e.getClass().getSimpleName());
        } finally {
            try { Files.deleteIfExists(upload); } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[SPARK_BACKUP] upload cleanup failed: {0}", e.getClass().getSimpleName());
            }
        }
    }

    private boolean waitForUpload(Path upload) {
        long deadline = System.nanoTime() + uploadWait.toNanos();
        do {
            if (Files.isRegularFile(upload, LinkOption.NOFOLLOW_LINKS)) return true;
            if (System.nanoTime() >= deadline) return false;
            try { Thread.sleep(250); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (true);
    }

    private static boolean zipHeader(Path upload) throws IOException {
        try (InputStream in = Files.newInputStream(upload)) {
            return in.read() == 'P' && in.read() == 'K' && in.read() == 3 && in.read() == 4;
        }
    }

    private String randomPassword() {
        char[] value = new char[24];
        for (int i = 0; i < value.length; i++) value[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(value);
    }

    private static String output(ExecResult result) {
        return result instanceof ExecResult.Completed completed ? completed.output() : "";
    }

    private static String logShape(String label, ExecResult result) {
        String shape = JobTranscript.lineShape(output(result));
        LOG.log(System.Logger.Level.INFO, "[SPARK_BACKUP] {0} log outcome={1} first_line_shape={2}",
                label, result.getClass().getSimpleName(), shape);
        JobTranscriptScope.add("ssh", "note", "Spark backup log " + label + " first_line_shape=" + shape);
        return shape;
    }
}
