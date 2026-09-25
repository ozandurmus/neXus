package com.securityexpert.nexus.ui2.worker.backup.asa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver;

/**
 * Cisco ASA confirm, inventory and backup over one SSH interactive shell per run (docs/design/CISCO_ASA_CONTRACT.md).
 * Answers in the vendor-executor outcome types so the confirm, inventory and backup jobs the HTTPS vendors use run it
 * unchanged. Every command is a read; the backup is the configuration text (running with keys, startup, version),
 * never the device's own {@code backup} command, which writes an archive to its flash.
 */
public final class CiscoAsaExecutor {

    private static final System.Logger LOG = System.getLogger(CiscoAsaExecutor.class.getName());
    private static final Duration CONNECT = Duration.ofSeconds(30);
    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(180);

    private static final Duration ARCHIVE = Duration.ofSeconds(600);
    private static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;

    /** SCP pull from the device (production: SshExecTransport#scpFetch). */
    public interface ScpFetcher {
        long fetch(TransportSession session, String remotePath,
                com.securityexpert.nexus.ui2.worker.transport.ssh.SshExecTransport.ScpSink sink, long maxBytes,
                Duration timeout) throws IOException;
    }

    private final DeviceTransport ssh;
    private final ArtefactStore artefactStore;
    private final ScpFetcher scp;

    public CiscoAsaExecutor(DeviceTransport ssh, ArtefactStore artefactStore) {
        this(ssh, artefactStore, null);
    }

    public CiscoAsaExecutor(DeviceTransport ssh, ArtefactStore artefactStore, ScpFetcher scp) {
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.artefactStore = artefactStore;
        this.scp = scp;
    }

    /** One open shell, already set to no paging, at privilege 15 -- or why not. */
    private record Shell(TransportSession session, Optional<String> refusal, boolean authFailure) {
    }

    private Shell open(Target target, String credentialRef) {
        ConnectionTarget ct = new ConnectionTarget(UUID.randomUUID().toString(), target.host(), target.port());
        ConnectSpec spec = new ConnectSpec(credentialRef,
                PersistedManagementEndpointTrustResolver.scopeRef(target.host(), target.port()), Optional.empty());
        ConnectResult r = ssh.connect(ct, spec, CONNECT);
        return switch (r) {
            case ConnectResult.Authenticated a -> {
                TransportSession s = a.session();
                // A session setting: the pager is off for this login only.
                ssh.execInteractive(s, new ExecSpec(CiscoAsaPlan.TERMINAL_PAGER_0, true), SHORT);
                Optional<String> priv = read(s, CiscoAsaPlan.SHOW_CURPRIV, SHORT);
                Optional<Integer> level = priv.flatMap(CiscoAsaPlan::parsePrivilege);
                if (level.isPresent() && level.get() < 15) {
                    ssh.disconnect(s);
                    yield new Shell(null, Optional.of("the account is at privilege " + level.get()
                            + "; the ASA reads need a privilege-15 account"), true);
                }
                yield new Shell(s, Optional.empty(), false);
            }
            case ConnectResult.AuthenticationFailed f -> new Shell(null, Optional.of("authentication_failed"), true);
            case ConnectResult.HostKeyRejected h -> new Shell(null, Optional.of("host_key: " + h.reason()), false);
            case ConnectResult.TimedOut t -> new Shell(null, Optional.of("unreachable: " + t.reason()), false);
        };
    }

    private Optional<String> read(TransportSession s, String command, Duration timeout) {
        ExecResult r = ssh.execInteractive(s, new ExecSpec(command, true), timeout);
        if (r instanceof ExecResult.Completed c && !CiscoAsaPlan.isCliError(c.output())) {
            return Optional.of(stripEcho(c.output(), command));
        }
        LOG.log(System.Logger.Level.INFO, "[ASA] {0}: {1}", command, r instanceof ExecResult.Completed ? "cli_error" : r.getClass().getSimpleName());
        return Optional.empty();
    }

    /** The shell echoes the command on the first line and ends with the prompt: neither is device output. */
    static String stripEcho(String out, String command) {
        String text = out;
        int nl = text.indexOf('\n');
        if (nl >= 0 && text.substring(0, nl).contains(command)) {
            text = text.substring(nl + 1);
        }
        int last = text.lastIndexOf('\n');
        if (last >= 0) {
            String tail = text.substring(last + 1).strip();
            if (tail.endsWith("#") || tail.endsWith(">")) {
                text = text.substring(0, last + 1);
            }
        }
        return text;
    }

    public HttpsVendorExecutor.ConfirmOutcome confirm(Target target, String credentialRef) {
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return shell.authFailure() ? new HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed(shell.refusal().orElse(""))
                    : new HttpsVendorExecutor.ConfirmOutcome.Failed(shell.refusal().orElse(""));
        }
        try {
            Optional<String> version = read(shell.session(), CiscoAsaPlan.SHOW_VERSION, SHORT);
            if (version.isEmpty()) {
                return new HttpsVendorExecutor.ConfirmOutcome.Failed("show version gave no answer");
            }
            CiscoAsaPlan.Version v = CiscoAsaPlan.parseVersion(version.get());
            if (v.softwareVersion().isEmpty()) {
                return new HttpsVendorExecutor.ConfirmOutcome.Failed("not an ASA: show version names no Adaptive Security Appliance software");
            }
            LOG.log(System.Logger.Level.INFO, "[ASA] confirm: hostname={0} model={1} version={2}",
                    v.hostname().isPresent(), v.model().isPresent(), v.softwareVersion().isPresent());
            return new HttpsVendorExecutor.ConfirmOutcome.Confirmed(
                    new HttpsVendorExecutor.Identity(v.hostname(), v.model(), v.softwareVersion()));
        } finally {
            ssh.disconnect(shell.session());
        }
    }

    public HttpsVendorExecutor.InventoryOutcome inventory(Target target, String credentialRef) {
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return shell.authFailure() ? new HttpsVendorExecutor.InventoryOutcome.AuthenticationFailed(shell.refusal().orElse(""))
                    : new HttpsVendorExecutor.InventoryOutcome.Failed(shell.refusal().orElse(""));
        }
        try {
            TransportSession s = shell.session();
            CiscoAsaPlan.Version v = CiscoAsaPlan.parseVersion(read(s, CiscoAsaPlan.SHOW_VERSION, SHORT).orElse(null));
            if (v.softwareVersion().isEmpty()) {
                return new HttpsVendorExecutor.InventoryOutcome.Failed("show version gave no ASA answer");
            }
            Optional<String> ip = read(s, CiscoAsaPlan.SHOW_IP_ADDRESS, SHORT);
            Optional<String> brief = read(s, CiscoAsaPlan.SHOW_INTERFACE_IP_BRIEF, SHORT);
            Optional<String> route = read(s, CiscoAsaPlan.SHOW_ROUTE, LONG);
            Optional<String> failover = read(s, CiscoAsaPlan.SHOW_FAILOVER_THIS_HOST, SHORT);
            boolean multiple = read(s, CiscoAsaPlan.SHOW_MODE, SHORT).map(CiscoAsaPlan::isMultipleContext).orElse(false);
            var interfaces = CiscoAsaPlan.interfaces(ip.orElse(null), brief.orElse(null));
            var routes = CiscoAsaPlan.parseRoutes(route.orElse(null));
            LOG.log(System.Logger.Level.INFO, "[ASA] inventory: interfaces={0} routes={1} ha={2} multiple_context={3}",
                    interfaces.size(), routes.size(), failover.flatMap(CiscoAsaPlan::parseHaRole).orElse("none"), multiple);
            String context = v.hostname().orElse(InventoryContext.PHYSICAL);
            return new HttpsVendorExecutor.InventoryOutcome.Completed(List.of(new InventoryContext(context, interfaces, routes)),
                    List.of(), Optional.empty(), new HttpsVendorExecutor.Identity(v.hostname(), v.model(), v.softwareVersion()));
        } finally {
            ssh.disconnect(shell.session());
        }
    }

    /**
     * Both backups, in order (PO 2026-09-25): the configuration text, then the ASA's own archive (backup to flash, SCP
     * pull, delete). The text is required; a failed archive stores the text and names the archive as missing.
     */
    public BackupResult backup(Target target, String credentialRef, String deviceId, String jobId) {
        if (artefactStore == null) {
            return new BackupResult.ArtefactStoreFailed("no artefact store in this worker");
        }
        Map<String, byte[]> members = new LinkedHashMap<>();
        List<String> manifest = new java.util.ArrayList<>();
        String archiveName = CiscoAsaPlan.archiveName(jobId);
        boolean archiveWritten = false;
        Optional<String> archiveProblem = Optional.empty();

        // 1. configuration text, and the archive written to flash, on one shell
        Shell shell = open(target, credentialRef);
        if (shell.session() == null) {
            return new BackupResult.ConnectFailed(shell.refusal().orElse("connect_failed"));
        }
        try {
            TransportSession s = shell.session();
            Optional<String> running = read(s, CiscoAsaPlan.MORE_SYSTEM_RUNNING_CONFIG, LONG);
            if (running.isEmpty() || !running.get().contains("ASA Version")) {
                return new BackupResult.SubmitOutputUnparseable("more system:running-config gave no ASA configuration");
            }
            members.put("running-config.txt", running.get().getBytes(StandardCharsets.UTF_8));
            read(s, CiscoAsaPlan.SHOW_STARTUP_CONFIG, LONG).ifPresent(t -> members.put("startup-config.txt", t.getBytes(StandardCharsets.UTF_8)));
            read(s, CiscoAsaPlan.SHOW_VERSION, SHORT).ifPresent(t -> members.put("show-version.txt", t.getBytes(StandardCharsets.UTF_8)));
            read(s, CiscoAsaPlan.SHOW_MODE, SHORT).ifPresent(t -> members.put("show-mode.txt", t.getBytes(StandardCharsets.UTF_8)));
            if (scp == null) {
                archiveProblem = Optional.of("ASA archive (no SCP client in this worker)");
            } else {
                ExecResult r = ssh.execInteractive(s, new ExecSpec(CiscoAsaPlan.backupArchive(archiveName), true), ARCHIVE);
                String out = r instanceof ExecResult.Completed c ? c.output() : "";
                archiveWritten = r instanceof ExecResult.Completed;
                if (!CiscoAsaPlan.archiveFinished(out)) {
                    archiveProblem = Optional.of("ASA archive (the backup command did not finish: "
                            + (r instanceof ExecResult.Completed ? (CiscoAsaPlan.isCliError(out) ? "refused" : "no 'Backup finished'")
                                    : r.getClass().getSimpleName()) + ")");
                } else {
                    List<String> failed = CiscoAsaPlan.archiveFailedItems(out);
                    manifest.add("archive items the ASA could not include: " + (failed.isEmpty() ? "none" : String.join(", ", failed)));
                }
            }
        } finally {
            ssh.disconnect(shell.session());
        }

        // 2. pull the archive over SCP into a temporary file (a torn transfer never reaches the bundle)
        java.nio.file.Path temp = null;
        long archiveBytes = -1;
        if (archiveProblem.isEmpty()) {
            try {
                temp = java.nio.file.Files.createTempFile("asa-archive-", ".part");
                java.nio.file.Path tempFile = temp;
                Shell pull = open(target, credentialRef);
                if (pull.session() == null) {
                    archiveProblem = Optional.of("ASA archive (SCP connection: " + pull.refusal().orElse("failed") + ")");
                } else {
                    try {
                        archiveBytes = scp.fetch(pull.session(), CiscoAsaPlan.scpPath(archiveName),
                                size -> java.nio.file.Files.newOutputStream(tempFile), MAX_ARCHIVE_BYTES, ARCHIVE);
                    } finally {
                        ssh.disconnect(pull.session());
                    }
                }
            } catch (IOException e) {
                archiveProblem = Optional.of("ASA archive (SCP: " + String.valueOf(e.getMessage()).split(":", 2)[0]
                        + "; is 'ssh scopy enable' set?)");
                archiveBytes = -1;
            }
        }

        // 3. delete the file this run created (only that name), whether or not the pull worked
        Optional<String> cleanup = Optional.empty();
        if (archiveWritten) {
            Shell del = open(target, credentialRef);
            if (del.session() == null) {
                cleanup = Optional.of("could not reconnect to delete disk0:/" + archiveName);
            } else {
                try {
                    ExecResult r = ssh.execInteractive(del.session(), new ExecSpec(CiscoAsaPlan.deleteArchive(archiveName), true), SHORT);
                    if (r instanceof ExecResult.Completed c && CiscoAsaPlan.isCliError(c.output())) {
                        cleanup = Optional.of("delete of disk0:/" + archiveName + " was refused");
                    } else if (r instanceof ExecResult.TimedOut) {
                        cleanup = Optional.of("delete of disk0:/" + archiveName + " timed out");
                    }
                } finally {
                    ssh.disconnect(del.session());
                }
            }
        }

        // 4. one bundle: text, archive (when pulled), manifest
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "cisco_asa", false);
        } catch (IOException e) {
            deleteQuietly(temp);
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }
        try {
            try (com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter tar =
                    new com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter(new java.util.zip.GZIPOutputStream(handle.sink()))) {
                for (var e : members.entrySet()) {
                    tar.file(e.getKey(), e.getValue());
                }
                if (archiveProblem.isEmpty() && temp != null && archiveBytes >= 0) {
                    try (java.io.OutputStream o = tar.begin("asa-backup.tar.gz", archiveBytes)) {
                        java.nio.file.Files.copy(temp, o);
                    }
                }
                StringBuilder text = new StringBuilder("# Cisco ASA backup (neXus): configuration text, then the ASA archive\n");
                members.forEach((name, bytes) -> text.append(name).append('\t').append(bytes.length).append(" bytes\n"));
                text.append("asa-backup.tar.gz\t").append(archiveProblem.isEmpty() ? archiveBytes + " bytes" : "MISSING: " + archiveProblem.get()).append('\n');
                manifest.forEach(line -> text.append("# ").append(line).append('\n'));
                tar.file("manifest.txt", text.toString().getBytes(StandardCharsets.UTF_8));
            }
            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            LOG.log(System.Logger.Level.INFO, "[ASA_BACKUP] stored {0} text files, archive={1}, {2} bytes", members.size(),
                    archiveProblem.isEmpty() ? archiveBytes : "missing", metadata.plaintextBytes());
            if (cleanup.isPresent()) {
                return new BackupResult.CleanupFailed(metadata, "asa-backup.tgz", cleanup.get());
            }
            return archiveProblem.isPresent()
                    ? new BackupResult.Partial(metadata, "asa-backup.tgz", archiveProblem.get())
                    : new BackupResult.Completed(metadata, "asa-backup.tgz", Optional.empty(), Optional.empty());
        } catch (IOException e) {
            try {
                handle.close();
            } catch (IOException ignored) {
                // best effort
            }
            return new BackupResult.ArtefactStoreFailed("bundle could not be stored: " + e.getClass().getSimpleName());
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void deleteQuietly(java.nio.file.Path p) {
        if (p != null) {
            try {
                java.nio.file.Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
