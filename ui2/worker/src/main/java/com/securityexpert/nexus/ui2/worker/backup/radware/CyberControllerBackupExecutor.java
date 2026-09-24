package com.securityexpert.nexus.ui2.worker.backup.radware;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.PromptAnswer;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;

/**
 * A Radware Cyber Controller's own configuration backup (RADWARE_CYBER_CONTROLLER_OWN_BACKUP_RECEIVER.md, V69; PO
 * 2026-09-24). On the Cyber Controller's restricted CLI: create a backup under this run's own name, export it by SFTP
 * to HOST-A's chrooted receiver (answering "Password:" from the credential store), then -- only once the file is in the
 * encrypted artefact store -- delete the upload and the Cyber Controller's copy by exact name ("(Y/N)?" answered "y").
 * The Cyber Controller's scheduler-generated backups are never touched. Plaintext exists only in the receiver's
 * upload directory, between the export and this run reading it.
 */
public final class CyberControllerBackupExecutor {

    private static final System.Logger LOG = System.getLogger(CyberControllerBackupExecutor.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(600);
    private static final Duration SHORT = Duration.ofSeconds(60);
    private static final Duration DELETE = Duration.ofSeconds(120);
    /** Measured config backups are ~35-37 MB; this bounds a runaway, it is not a vendor figure. */
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MIN_BYTES = 1024;
    public static final String RECEIVER_USER = "nexus-cc";

    private final DeviceTransport transport;
    private final ArtefactStore artefactStore;
    private final Path inbox;
    private final String receiverHost;
    private final Function<String, char[]> passwordOf;
    private final SecureRandom random = new SecureRandom();

    public CyberControllerBackupExecutor(DeviceTransport transport, ArtefactStore artefactStore, Path inbox, String receiverHost,
            Function<String, char[]> passwordOf) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.inbox = inbox;
        this.receiverHost = receiverHost;
        this.passwordOf = Objects.requireNonNull(passwordOf, "passwordOf");
    }

    public BackupResult collect(BackupRequest request, Optional<String> receiverCredentialRef, String deviceId, String jobId) {
        if (inbox == null || receiverHost == null || receiverHost.isBlank() || !Files.isDirectory(inbox)) {
            return new BackupResult.CredentialUnresolvable("the SFTP receiver is not configured in this worker (inbox or "
                    + "receiver address missing) -- refused before any device contact");
        }
        if (receiverCredentialRef.isEmpty()) {
            return new BackupResult.CredentialUnresolvable("no backup receiver credential is set on this Cyber Controller "
                    + "(Change credentials > Backup receiver credential) -- refused before any device contact");
        }
        if (request.credentialRef().isEmpty() || request.credentialRef().get().isBlank()) {
            return new BackupResult.CredentialUnresolvable("the Cyber Controller has no login credential");
        }
        char[] receiverPassword;
        try {
            receiverPassword = passwordOf.apply(receiverCredentialRef.get());
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("backup receiver credential not resolvable");
        }
        ConnectResult connect;
        try {
            connect = transport.connect(request.connectionTarget(),
                    new ConnectSpec(request.credentialRef().get(), request.trustRuleRef(), Optional.empty()), CONNECT_TIMEOUT);
        } catch (IllegalStateException unresolvable) {
            return new BackupResult.CredentialUnresolvable(String.valueOf(unresolvable.getMessage()));
        }
        if (!(connect instanceof ConnectResult.Authenticated authenticated)) {
            return new BackupResult.ConnectFailed("ssh connect: " + connect.getClass().getSimpleName());
        }
        TransportSession session = authenticated.session();
        try {
            return run(session, receiverPassword, deviceId, jobId);
        } finally {
            transport.disconnect(session);
            java.util.Arrays.fill(receiverPassword, '\0');
        }
    }

    private BackupResult run(TransportSession session, char[] receiverPassword, String deviceId, String jobId) {
        byte[] t = new byte[8];
        random.nextBytes(t);
        String token = HexFormat.of().formatHex(t);
        String name = CyberControllerBackupPlan.name(token);
        Path upload = inbox.resolve(token + ".tgz" + CyberControllerBackupPlan.EXPORT_SUFFIX);

        ExecResult created = transport.execInteractive(session, new ExecSpec(CyberControllerBackupPlan.with(CyberControllerBackupPlan.CREATE, name)), LONG);
        String listing = text(transport.execInteractive(session, new ExecSpec(CyberControllerBackupPlan.LIST), SHORT));
        Optional<Long> listedKb = CyberControllerBackupPlan.listedSizeKb(listing, name);
        LOG.log(System.Logger.Level.INFO, "[CC_BACKUP] create ended {0}; listed={1} sizeK={2}", created.getClass().getSimpleName(),
                listedKb.isPresent(), listedKb.map(String::valueOf).orElse("-"));
        if (listedKb.isEmpty()) {
            // nothing of ours is listed: nothing to delete on the Cyber Controller
            return new BackupResult.SubmitRefused("the Cyber Controller did not create the backup (" + created.getClass().getSimpleName() + ")");
        }

        String target = CyberControllerBackupPlan.exportTarget(RECEIVER_USER, receiverHost, token);
        ExecResult exported = transport.execInteractiveAnswering(session,
                new ExecSpec(CyberControllerBackupPlan.with(CyberControllerBackupPlan.EXPORT, name, target)),
                List.of(new PromptAnswer(CyberControllerBackupPlan.PASSWORD_PROMPT, receiverPassword)), LONG);
        boolean exportDone = text(exported).contains(CyberControllerBackupPlan.EXPORT_DONE);
        LOG.log(System.Logger.Level.INFO, "[CC_BACKUP] export ended {0}; completed={1}; upload present={2}",
                exported.getClass().getSimpleName(), exportDone, Files.exists(upload));
        if (!exportDone || !Files.isRegularFile(upload)) {
            deleteQuietly(upload);
            String cleanup = deleteOnController(session, name) ? "" : "; removing nexus backup from the Cyber Controller also failed";
            return new BackupResult.SubmitRefused("the export did not complete (" + firstLine(text(exported)) + ")" + cleanup);
        }

        ArtefactStore.ArtefactMetadata metadata;
        long bytes;
        try {
            bytes = Files.size(upload);
            if (bytes < MIN_BYTES || bytes > MAX_BYTES) {
                deleteQuietly(upload);
                deleteOnController(session, name);
                return new BackupResult.SubmitOutputUnparseable("the export was " + bytes + " bytes; outside the accepted bounds");
            }
            ArtefactStore.ArtefactHandle handle = artefactStore.open(deviceId, jobId, "radware", false);
            try (InputStream in = Files.newInputStream(upload)) {
                in.transferTo(handle.sink());
                metadata = handle.finish();
            } catch (IOException e) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                    // best effort
                }
                throw e;
            }
        } catch (IOException e) {
            deleteQuietly(upload);
            deleteOnController(session, name);
            return new BackupResult.ArtefactStoreFailed("storing the upload failed: " + e.getClass().getSimpleName());
        }
        LOG.log(System.Logger.Level.INFO, "[CC_BACKUP] stored {0} bytes (Cyber Controller listed {1} K)", bytes,
                listedKb.get());

        boolean uploadRemoved = deleteQuietly(upload);
        boolean controllerRemoved = deleteOnController(session, name);
        String archive = "cyber-controller-config-" + token + ".tgz" + CyberControllerBackupPlan.EXPORT_SUFFIX;
        if (!uploadRemoved || !controllerRemoved) {
            return new BackupResult.CleanupFailed(metadata, archive, (!uploadRemoved ? "the upload on HOST-A was not removed; " : "")
                    + (!controllerRemoved ? "the backup " + name + " was not removed from the Cyber Controller" : ""));
        }
        return new BackupResult.Completed(metadata, archive, Optional.empty(), Optional.empty());
    }

    /** Deletes this run's backup, by exact name, answering the measured "(Y/N)?" with "y". */
    private boolean deleteOnController(TransportSession session, String name) {
        ExecResult r = transport.execInteractiveAnswering(session,
                new ExecSpec(CyberControllerBackupPlan.with(CyberControllerBackupPlan.DELETE, name)),
                List.of(new PromptAnswer(CyberControllerBackupPlan.DELETE_CONFIRM_PROMPT, new char[] {'y'})), DELETE);
        boolean done = text(r).contains(CyberControllerBackupPlan.DELETE_DONE);
        LOG.log(System.Logger.Level.INFO, "[CC_BACKUP] delete {0} ended {1}; completed={2}", name, r.getClass().getSimpleName(), done);
        return done;
    }

    private static boolean deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "[CC_BACKUP] could not remove the upload: {0}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String text(ExecResult r) {
        return r instanceof ExecResult.Completed c && c.output() != null ? c.output() : "";
    }

    private static String firstLine(String s) {
        String t = s == null ? "" : s.strip();
        int nl = t.indexOf('\n');
        // the export echoes its target URL, which carries HOST-A's address: never into a job reason
        String line = (nl < 0 ? t : t.substring(0, nl)).replaceAll("sftp://\\S+", "sftp://<receiver>");
        return line.isEmpty() ? "no output" : line.length() > 160 ? line.substring(0, 160) : line;
    }
}
