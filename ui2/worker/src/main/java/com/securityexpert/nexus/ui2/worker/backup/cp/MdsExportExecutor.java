package com.securityexpert.nexus.ui2.worker.backup.cp;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;

/**
 * Check Point Multi-Domain Server export (V61; PO, 2026-09-23): one {@code mds_backup -b -l} of the whole server and
 * every domain, plus the Gaia configuration, licences, routes and uname, bundled in the run's own work directory,
 * digested on the device, fetched by SFTP, compared and removed by exact name.
 *
 * <p>mds_backup runs in the background with its exit code written to a file this run polls (14H: no SSH command is
 * held open for the backup's duration). A run that never sees the exit code before its deadline ends
 * OUTCOME_UNKNOWN and deletes nothing -- mds_backup may still be running and holds the MDS database lock.</p>
 */
public final class MdsExportExecutor {

    private static final System.Logger LOG = System.getLogger(MdsExportExecutor.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHORT = Duration.ofSeconds(60);
    private static final Duration CONFIG = Duration.ofSeconds(120);
    private static final Duration BUNDLE = Duration.ofSeconds(3600);
    private static final Duration DIGEST = Duration.ofSeconds(600);
    private static final Duration FETCH = Duration.ofSeconds(7200);
    /** A safety bound on the fetch, never a vendor-stated size. */
    private static final long MAX_BUNDLE_BYTES = 100L * 1024 * 1024 * 1024;

    private final DeviceTransport transport;
    private final ArtefactStore artefactStore;
    private final long minFreeBytes;
    private final Duration pollInterval;
    private final Duration runDeadline;

    public MdsExportExecutor(DeviceTransport transport, ArtefactStore artefactStore, long minFreeBytes, Duration pollInterval,
            Duration runDeadline) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.minFreeBytes = minFreeBytes;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.runDeadline = Objects.requireNonNull(runDeadline, "runDeadline");
    }

    public BackupResult collect(BackupRequest request, String deviceId, String jobId) {
        if (request.credentialRef().isEmpty() || request.credentialRef().get().isBlank()) {
            return new BackupResult.CredentialUnresolvable("no backup credential configured for check_point (BK-11) -- refused before any device contact");
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
            return run(session, deviceId, jobId);
        } finally {
            transport.disconnect(session);
        }
    }

    private BackupResult run(TransportSession session, String deviceId, String jobId) {
        String dir = MdsExportPlan.workDir(jobId);

        Optional<Long> free = com.securityexpert.nexus.ui2.worker.backup.BackupCapabilityExecutor
                .parseDfAvailableBytes(exec(session, MdsExportPlan.DF_VAR_LOG, SHORT).output());
        if (free.isEmpty()) {
            return new BackupResult.InsufficientFreeSpace("df -P /var/log could not be parsed for a free-space value; refusing (fail-closed)");
        }
        if (free.get() < minFreeBytes) {
            return new BackupResult.InsufficientFreeSpace("free space in /var/log is " + free.get() + " bytes, below the "
                    + minFreeBytes + "-byte heuristic for an MDS export (a local heuristic, not a vendor requirement)");
        }
        if (!exec(session, MdsExportPlan.with(MdsExportPlan.MKDIR, dir), SHORT).succeeded()) {
            return new BackupResult.SubmitRefused("the work directory could not be created");
        }

        // Context files: each best effort -- a missing one is a smaller bundle, never a failed export.
        for (String t : new String[] {MdsExportPlan.MDSSTAT, MdsExportPlan.LICENSES, MdsExportPlan.ROUTES, MdsExportPlan.UNAME}) {
            exec(session, MdsExportPlan.with(t, dir), SHORT);
        }
        exec(session, MdsExportPlan.with(MdsExportPlan.GAIA_CONFIGURATION, dir), CONFIG);

        // mds_backup: started once, never retried; its exit code is the truth.
        ExecOutcome start = exec(session, MdsExportPlan.with(MdsExportPlan.MDS_BACKUP_START, dir), SHORT);
        if (!start.succeeded() && !start.timedOut()) {
            // an explicit refusal (non-zero exit, channel failure): nothing started, the directory is ours to remove
            remove(session, dir);
            return new BackupResult.SubmitRefused("mds_backup could not be started");
        }
        // A timed-out start may well have started mds_backup (2026-09-24): never remove the directory under it --
        // poll for its exit code like any other run.
        Instant started = Instant.now();
        Optional<Integer> rc = Optional.empty();
        Instant deadline = started.plus(runDeadline);
        while (Instant.now().isBefore(deadline)) {
            sleep(pollInterval);
            ExecOutcome poll = exec(session, MdsExportPlan.with(MdsExportPlan.MDS_BACKUP_POLL, dir), SHORT);
            if (poll.succeeded()) {
                rc = parseExitCode(poll.output());
                if (rc.isPresent()) {
                    break;
                }
            }
        }
        long minutes = Duration.between(started, Instant.now()).toMinutes();
        LOG.log(System.Logger.Level.INFO, "[MDS_EXPORT] mds_backup finished={0} rc={1} after {2} min (measurement: run time of this estate)",
                rc.isPresent(), rc.map(String::valueOf).orElse("-"), minutes);
        if (rc.isEmpty()) {
            return new BackupResult.OutcomeUnknown("mds_backup did not report an exit code within " + runDeadline.toMinutes()
                    + " minutes; it may still be running and holding the MDS database lock -- the work directory is left in place");
        }
        if (rc.get() != 0) {
            remove(session, dir);
            return new BackupResult.SubmitRefused("mds_backup exited with code " + rc.get());
        }
        String listing = exec(session, MdsExportPlan.with(MdsExportPlan.LIST, dir), SHORT).output();
        if (listing == null || !listing.contains("mdsbk")) {
            remove(session, dir);
            return new BackupResult.SubmitOutputUnparseable("mds_backup exited 0 but left no *mdsbk* file in the work directory");
        }

        if (!exec(session, MdsExportPlan.with(MdsExportPlan.BUNDLE, dir), BUNDLE).succeeded()) {
            remove(session, dir);
            return new BackupResult.SubmitRefused("the bundle could not be written");
        }
        String bundle = dir + ".tgz";
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "check_point", false);
        } catch (IOException e) {
            remove(session, dir);
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }
        FetchStreamResult fetched;
        try {
            fetched = transport.fetchStreaming(session, new FetchSpec(bundle, MAX_BUNDLE_BYTES), FETCH, handle.sink());
        } catch (RuntimeException e) {
            closeQuietly(handle);
            remove(session, dir);
            return new BackupResult.ArtefactStoreFailed("sftp fetch failed: " + e.getClass().getSimpleName());
        }
        if (!(fetched instanceof FetchStreamResult.Fetched)) {
            closeQuietly(handle);
            remove(session, dir);
            return new BackupResult.ArtefactStoreFailed("sftp fetch failed: "
                    + (fetched instanceof FetchStreamResult.Failed f ? f.reason() : "unknown"));
        }
        ArtefactStore.ArtefactMetadata metadata;
        try {
            metadata = handle.finish();
        } catch (IOException e) {
            closeQuietly(handle);
            remove(session, dir);
            return new BackupResult.ArtefactStoreFailed("artefact store finish failed: " + e.getMessage());
        }
        Optional<String> deviceDigest = parseSha256(exec(session, MdsExportPlan.with(MdsExportPlan.DIGEST, dir), DIGEST).output());
        if (deviceDigest.isEmpty() || !deviceDigest.get().equalsIgnoreCase(metadata.plaintextSha256())) {
            // nothing is deleted until the digests match (BK-3)
            return new BackupResult.DigestMismatch(deviceDigest.orElse("UNPARSEABLE"), metadata.plaintextSha256());
        }
        if (!remove(session, dir)) {
            return new BackupResult.CleanupFailed(metadata, "provider1-" + jobId + ".tgz",
                    "removing the work directory and bundle failed after one retry");
        }
        return new BackupResult.Completed(metadata, "provider1-" + jobId + ".tgz", Optional.empty(), Optional.empty());
    }

    private boolean remove(TransportSession session, String dir) {
        String cmd = MdsExportPlan.with(MdsExportPlan.REMOVE, dir);
        return exec(session, cmd, SHORT).succeeded() || exec(session, cmd, SHORT).succeeded();
    }

    static Optional<Integer> parseExitCode(String output) {
        if (output == null) {
            return Optional.empty();
        }
        String s = output.strip();
        return s.matches("\\d{1,3}") ? Optional.of(Integer.parseInt(s)) : Optional.empty();
    }

    static Optional<String> parseSha256(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        String first = output.strip().split("\\s+", 2)[0];
        return first.matches("[0-9a-fA-F]{64}") ? Optional.of(first) : Optional.empty();
    }

    private ExecOutcome exec(TransportSession session, String command, Duration timeout) {
        ExecResult result = transport.exec(session, new ExecSpec(command), timeout);
        return switch (result) {
            case ExecResult.Completed c -> new ExecOutcome(c.output(), c.exitStatus() == 0, false);
            case ExecResult.TimedOut t -> new ExecOutcome("", false, true);
            case ExecResult.ChannelFailed f -> new ExecOutcome(String.valueOf(f.reason()), false, false);
        };
    }

    private record ExecOutcome(String output, boolean succeeded, boolean timedOut) {
    }

    private static void closeQuietly(ArtefactStore.ArtefactHandle handle) {
        try {
            handle.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
