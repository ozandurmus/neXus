package com.securityexpert.nexus.ui2.worker.backup.cp;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.backup.BackupReadPlan;
import com.securityexpert.nexus.ui2.worker.backup.BackupRequest;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;

/**
 * Check Point Gaia Snapshot Executor for weekly OS-level image recovery.
 *
 * <p>Implements BackBox parity:
 * <ol>
 *   <li>Worker pull over outbound management SSH (no inbound cluster openings).</li>
 *   <li>Strict pre-flight disk space verification (at least 3x snapshot threshold).</li>
 *   <li>Asynchronous Clish {@code add snapshot <name>} execution.</li>
 *   <li>Poll until terminal status or timeout.</li>
 *   <li>Stream snapshot image straight to {@link ArtefactStore} with AES-256-GCM envelope encryption.</li>
 *   <li>Mandatory post-flight cleanup on gateway ({@code delete snapshot <name>}) to prevent disk exhaustion.</li>
 * </ol>
 */
public final class CheckPointSnapshotExecutor {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DISKSPACE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(1800); // 30 mins for snapshot image transfer
    private static final long MAX_SNAPSHOT_BYTES = 25L * 1024 * 1024 * 1024; // 25 GB bound

    private static final Pattern FIRST_INTEGER = Pattern.compile("(\\d+)");
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(\\d+)%");

    private final DeviceTransport transport;
    private final ArtefactStore artefactStore;
    private final long freeSpaceThresholdBytes;
    private final Duration pollInterval;
    private final Duration runDeadline;

    public CheckPointSnapshotExecutor(DeviceTransport transport, ArtefactStore artefactStore,
            long freeSpaceThresholdBytes, Duration pollInterval, Duration runDeadline) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.freeSpaceThresholdBytes = freeSpaceThresholdBytes;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.runDeadline = Objects.requireNonNull(runDeadline, "runDeadline");
    }

    public BackupResult collectSnapshot(BackupRequest request, String deviceId, String jobId) {
        if (request.credentialRef().isEmpty() || request.credentialRef().get().isBlank()) {
            return new BackupResult.CredentialUnresolvable("no distinct backup credential configured for check_point");
        }

        ConnectSpec spec = new ConnectSpec(request.credentialRef().get(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(request.connectionTarget(), spec, CONNECT_TIMEOUT);
        } catch (IllegalStateException e) {
            return new BackupResult.CredentialUnresolvable(e.getMessage());
        }

        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new BackupResult.ConnectFailed("SSH connection failed to " + request.connectionTarget());
        }

        TransportSession session = authenticated.session();
        try {
            return runSnapshotAgainstSession(session, deviceId, jobId);
        } finally {
            transport.disconnect(session);
        }
    }

    private BackupResult runSnapshotAgainstSession(TransportSession session, String deviceId, String jobId) {
        // 1. Pre-flight disk space check
        ExecOutcome diskspace = exec(session, BackupReadPlan.CP_SHOW_DISKSPACE, DISKSPACE_TIMEOUT);
        Optional<Long> freeBytes = parseFreeSpaceBytes(diskspace.output());
        if (freeBytes.isEmpty()) {
            return new BackupResult.InsufficientFreeSpace("show diskspace unparseable (fail-closed)");
        }
        if (freeBytes.get() < freeSpaceThresholdBytes) {
            return new BackupResult.InsufficientFreeSpace("free space " + freeBytes.get() + " bytes < required threshold "
                    + freeSpaceThresholdBytes + " bytes");
        }

        // 2. Submit snapshot
        String snapshotName = "nxs_snap_" + Math.abs(jobId.hashCode());
        ExecOutcome submit = exec(session, BackupReadPlan.addSnapshotCommand(snapshotName), SUBMIT_TIMEOUT);
        if (!submit.succeeded() || submit.output().toLowerCase().contains("failed") || submit.output().toLowerCase().contains("error")) {
            return new BackupResult.SubmitRefused("add snapshot refused: " + submit.output());
        }

        // 3. Poll until completion or deadline
        Instant deadline = Instant.now().plus(runDeadline);
        boolean completed = false;
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new BackupResult.OutcomeUnknown("snapshot polling interrupted");
            }

            ExecOutcome poll = exec(session, BackupReadPlan.CP_SHOW_SNAPSHOT_STATUS, POLL_TIMEOUT);
            String output = poll.output().toLowerCase();
            if (output.contains("completed") || output.contains("success") || output.contains("finished")) {
                completed = true;
                break;
            }
            if (output.contains("failed") || output.contains("error")) {
                return new BackupResult.SubmitRefused("snapshot operation reported failure: " + poll.output());
            }
        }

        if (!completed) {
            return new BackupResult.OutcomeUnknown("snapshot creation did not complete before deadline " + runDeadline);
        }

        // 4. Stream into ArtefactStore
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "check_point", false);
        } catch (IOException e) {
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }

        // Fetch snapshot export archive over SCP
        String remotePath = "/var/CPsnapshot/snapshots/" + snapshotName + ".tgz";
        FetchStreamResult fetchResult;
        try {
            fetchResult = transport.fetchStreaming(session, new FetchSpec(remotePath, MAX_SNAPSHOT_BYTES),
                    FETCH_TIMEOUT, handle.sink());
        } catch (RuntimeException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("snapshot fetch streaming failed: " + e.getMessage());
        }

        if (!(fetchResult instanceof FetchStreamResult.Fetched)) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("snapshot SCP transfer incomplete");
        }

        ArtefactStore.ArtefactMetadata metadata;
        try {
            metadata = handle.finish();
        } catch (IOException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("artefact store finish failed: " + e.getMessage());
        }

        // 5. Post-flight remote cleanup on gateway
        ExecOutcome delete = exec(session, BackupReadPlan.deleteSnapshotCommand(snapshotName), DELETE_TIMEOUT);
        if (!delete.succeeded()) {
            return new BackupResult.CleanupFailed(metadata, snapshotName,
                    "snapshot recorded in vault but remote cleanup failed: " + delete.output());
        }

        return new BackupResult.Completed(metadata, snapshotName, Optional.empty(), Optional.empty());
    }

    private ExecOutcome exec(TransportSession session, String command, Duration timeout) {
        ExecResult result = transport.exec(session, new ExecSpec(command), timeout);
        if (result instanceof ExecResult.Completed completed) {
            return new ExecOutcome(completed.exitStatus() == 0, completed.exitStatus(), completed.output());
        }
        return new ExecOutcome(false, -1, result.getClass().getSimpleName());
    }

    private static Optional<Long> parseFreeSpaceBytes(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        for (String line : output.split("\\R")) {
            String lower = line.toLowerCase();
            if (lower.contains("free") || lower.contains("available") || lower.contains("/var/log")) {
                Matcher m = FIRST_INTEGER.matcher(line);
                if (m.find()) {
                    try {
                        long value = Long.parseLong(m.group(1));
                        return Optional.of(value * 1024L * 1024L); // assume MB if in show diskspace, or scale
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static void closeQuietly(ArtefactStore.ArtefactHandle handle) {
        try {
            handle.close();
        } catch (Exception ignored) {
        }
    }

    private record ExecOutcome(boolean succeeded, int exitCode, String output) {
    }
}
