package com.securityexpert.nexus.ui2.worker.backup;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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

/**
 * The Check Point gateway backup's own device contact (14H BK-1..BK-8): the
 * submit-then-poll-then-fetch-then-verify-then-delete state machine section
 * 2 of the frozen record requires in place of the superseded blocking-exec
 * profile. Mirrors {@code worker.configuration.ConfigurationCapabilityExecutor}'s
 * connect/exec/disconnect shape, but this movement's flow has more terminal
 * states than a completed/failed split (a digest mismatch and a failed
 * cleanup are each their own outcome, never folded into a generic failure
 * string -- see {@link BackupResult}).
 *
 * <p>One SSH session is opened and held for the entire run (free-space read
 * through delete) -- {@code connect} once, {@code disconnect} once, exactly
 * as the gate doc's own "session reuse" column states for every literal.</p>
 */
public final class BackupCapabilityExecutor {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DISKSPACE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DIGEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(900);
    /** A generous safety bound on the SFTP fetch (entry 5's own timeout column) -- never a vendor-stated archive size limit. */
    private static final long MAX_ARCHIVE_BYTES = 5L * 1024 * 1024 * 1024;

    private static final Pattern ARCHIVE_NAME = Pattern.compile("([\\w][\\w.\\-]*\\.tgz)");
    private static final Pattern FIRST_INTEGER = Pattern.compile("(\\d+)");

    private enum BackupStatus {
        IN_PROGRESS, SUCCEEDED, FAILED
    }

    private final DeviceTransport transport;
    private final ArtefactStore artefactStore;
    /** BK-6: a configurable local heuristic, never presented as a vendor requirement. */
    private final long freeSpaceThresholdBytes;
    private final Duration pollInterval;
    private final Duration runDeadline;

    public BackupCapabilityExecutor(DeviceTransport transport, ArtefactStore artefactStore,
            long freeSpaceThresholdBytes, Duration pollInterval, Duration runDeadline) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.freeSpaceThresholdBytes = freeSpaceThresholdBytes;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.runDeadline = Objects.requireNonNull(runDeadline, "runDeadline");
    }

    public BackupResult collect(BackupRequest request, String deviceId, String jobId) {
        if (request.credentialRef().isEmpty() || request.credentialRef().get().isBlank()) {
            // BK-11: fails closed, before any device contact.
            return new BackupResult.CredentialUnresolvable(
                    "no distinct backup credential configured for check_point (BK-11: never falls back to the "
                            + "collection credential) -- refused before any device contact");
        }

        ConnectSpec spec = new ConnectSpec(request.credentialRef().get(), request.trustRuleRef(), Optional.empty());
        ConnectResult connectResult;
        try {
            connectResult = transport.connect(request.connectionTarget(), spec, CONNECT_TIMEOUT);
        } catch (IllegalStateException credentialUnresolvable) {
            return new BackupResult.CredentialUnresolvable(String.valueOf(credentialUnresolvable.getMessage()));
        }
        if (!(connectResult instanceof ConnectResult.Authenticated authenticated)) {
            return new BackupResult.ConnectFailed(describeConnect(connectResult));
        }
        TransportSession session = authenticated.session();
        try {
            return runAgainstSession(session, deviceId, jobId);
        } finally {
            transport.disconnect(session);
        }
    }

    private BackupResult runAgainstSession(TransportSession session, String deviceId, String jobId) {
        // Entry 1 (BK-6): free-space precondition.
        ExecOutcome diskspace = exec(session, BackupReadPlan.CP_SHOW_DISKSPACE, DISKSPACE_TIMEOUT);
        Optional<Long> freeBytes = parseFreeSpaceBytes(diskspace.output());
        if (freeBytes.isEmpty()) {
            return new BackupResult.InsufficientFreeSpace(
                    "show diskspace output could not be parsed for a free-space value; refusing (fail-closed)");
        }
        if (freeBytes.get() < freeSpaceThresholdBytes) {
            return new BackupResult.InsufficientFreeSpace("free space " + freeBytes.get() + " bytes is below the "
                    + "configured heuristic threshold " + freeSpaceThresholdBytes + " bytes (BK-6: a local "
                    + "heuristic, never presented as a vendor requirement)");
        }

        // Entry 2 (BK-5): submit -- never retried.
        ExecOutcome submit = exec(session, BackupReadPlan.CP_ADD_BACKUP_LOCAL, SUBMIT_TIMEOUT);
        if (!submit.succeeded()) {
            // BK-8: a vendor refusal (snapshot in progress, an open management client) is the failure reason.
            return new BackupResult.SubmitRefused("add backup local was refused: " + submit.output());
        }
        Optional<String> archiveName = parseArchiveName(submit.output());
        if (archiveName.isEmpty()) {
            return new BackupResult.SubmitOutputUnparseable(
                    "add backup local's own output did not name an archive; refusing rather than guessing");
        }
        String name = archiveName.get();

        // Entry 3 (BK-5): poll until terminal or the run's own deadline.
        BackupStatus status = pollUntilTerminalOrDeadline(session);
        if (status == BackupStatus.FAILED) {
            return new BackupResult.SubmitRefused("show backup status reported a failed backup for " + name);
        }
        if (status != BackupStatus.SUCCEEDED) {
            return new BackupResult.OutcomeUnknown("show backup status never reported a terminal state for " + name
                    + " before the run deadline; the device-side archive is not deleted");
        }

        // Entry 5 (BK-3): SFTP fetch, streamed straight into the artefact store.
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "check_point", false);
        } catch (IOException e) {
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }
        FetchStreamResult fetchResult;
        try {
            fetchResult = transport.fetchStreaming(session, new FetchSpec(name, MAX_ARCHIVE_BYTES), FETCH_TIMEOUT,
                    handle.sink());
        } catch (RuntimeException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("sftp fetch failed: " + e.getMessage());
        }
        if (!(fetchResult instanceof FetchStreamResult.Fetched)) {
            closeQuietly(handle);
            String reason = fetchResult instanceof FetchStreamResult.Failed failed ? failed.reason() : "unknown";
            return new BackupResult.ArtefactStoreFailed("sftp fetch failed: " + reason);
        }
        ArtefactStore.ArtefactMetadata metadata;
        try {
            metadata = handle.finish();
        } catch (IOException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("artefact store finish failed: " + e.getMessage());
        }

        // Entry 6 (BK-3): the digest computed on the device is compared with
        // the digest of the bytes actually received -- nothing is deleted
        // until they match.
        ExecOutcome digest = exec(session, BackupReadPlan.archiveDigestCommand(name), DIGEST_TIMEOUT);
        Optional<String> deviceDigest = parseSha256sumOutput(digest.output());
        if (deviceDigest.isEmpty() || !deviceDigest.get().equalsIgnoreCase(metadata.plaintextSha256())) {
            return new BackupResult.DigestMismatch(deviceDigest.orElse("UNPARSEABLE"), metadata.plaintextSha256());
        }

        // Entry 7 (BK-7): delete the exact archive name this run created --
        // may be retried once, never a pattern, never a listing.
        ExecOutcome delete = exec(session, BackupReadPlan.deleteBackupCommand(name), DELETE_TIMEOUT);
        if (!delete.succeeded()) {
            delete = exec(session, BackupReadPlan.deleteBackupCommand(name), DELETE_TIMEOUT);
        }
        if (!delete.succeeded()) {
            return new BackupResult.CleanupFailed(metadata, name,
                    "delete backup " + name + " failed after its one retry (BK-7): " + delete.output());
        }

        return new BackupResult.Completed(metadata, name, Optional.empty(), Optional.empty());
    }

    private BackupStatus pollUntilTerminalOrDeadline(TransportSession session) {
        Instant deadline = Instant.now().plus(runDeadline);
        while (true) {
            ExecOutcome statusOutcome = exec(session, BackupReadPlan.CP_SHOW_BACKUP_STATUS, POLL_TIMEOUT);
            BackupStatus status = classifyStatus(statusOutcome.output());
            if (status == BackupStatus.SUCCEEDED || status == BackupStatus.FAILED) {
                return status;
            }
            if (!Instant.now().plus(pollInterval).isBefore(deadline)) {
                return BackupStatus.IN_PROGRESS; // never reached terminal before the deadline -> OUTCOME_UNKNOWN
            }
            sleep(pollInterval);
        }
    }

    private ExecOutcome exec(TransportSession session, String command, Duration timeout) {
        ExecResult result = transport.exec(session, new ExecSpec(command), timeout);
        return switch (result) {
            case ExecResult.Completed completed -> new ExecOutcome(completed.output(), completed.exitStatus() == 0);
            case ExecResult.TimedOut ignored -> new ExecOutcome("", false);
            case ExecResult.ChannelFailed failed -> new ExecOutcome(String.valueOf(failed.reason()), false);
        };
    }

    private record ExecOutcome(String output, boolean succeeded) {
    }

    static Optional<Long> parseFreeSpaceBytes(String diskspaceOutput) {
        if (diskspaceOutput == null || diskspaceOutput.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = FIRST_INTEGER.matcher(diskspaceOutput);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            // Gaia's show diskspace reports free space in KB; a KB->byte
            // conversion is a representation detail, never a vendor
            // requirement re-derivation (BK-6).
            return Optional.of(Long.parseLong(matcher.group(1)) * 1024L);
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    static Optional<String> parseArchiveName(String submitOutput) {
        if (submitOutput == null) {
            return Optional.empty();
        }
        Matcher matcher = ARCHIVE_NAME.matcher(submitOutput);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    static Optional<String> parseSha256sumOutput(String digestOutput) {
        if (digestOutput == null || digestOutput.isBlank()) {
            return Optional.empty();
        }
        String firstToken = digestOutput.strip().split("\\s+", 2)[0];
        return firstToken.matches("[0-9a-fA-F]{64}") ? Optional.of(firstToken) : Optional.empty();
    }

    private static BackupStatus classifyStatus(String statusOutput) {
        if (statusOutput == null) {
            return BackupStatus.IN_PROGRESS;
        }
        String lower = statusOutput.toLowerCase(Locale.ROOT);
        if (lower.contains("fail") || lower.contains("error")) {
            return BackupStatus.FAILED;
        }
        if (lower.contains("succeed") || lower.contains("success") || lower.contains("completed")
                || lower.contains("done")) {
            return BackupStatus.SUCCEEDED;
        }
        return BackupStatus.IN_PROGRESS;
    }

    private static void closeQuietly(ArtefactStore.ArtefactHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (IOException ignored) {
            // best-effort cleanup of a temp artefact file after a write failure
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describeConnect(ConnectResult result) {
        return switch (result) {
            case ConnectResult.AuthenticationFailed failed -> "authentication_failed: " + failed.reason();
            case ConnectResult.HostKeyRejected rejected -> rejected.reason().startsWith("host_key_mismatch:")
                    ? rejected.reason()
                    : "host_key_rejected: " + rejected.reason();
            case ConnectResult.TimedOut timedOut -> timedOut.reason();
            case ConnectResult.Authenticated ignored -> "authenticated";
        };
    }
}
