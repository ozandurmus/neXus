package com.securityexpert.nexus.ui2.worker.backup;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    private static final System.Logger LOG = System.getLogger(BackupCapabilityExecutor.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DISKSPACE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DIGEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(900);
    /** A generous safety bound on the SFTP fetch (entry 5's own timeout column) -- never a vendor-stated archive size limit. */
    // 2026-09-24: 5 GB -> 50 GB (UI2_BACKUP_MAX_ARCHIVE_BYTES): a Multi-Domain Server's Gaia backup carries every domain.
    private static final long MAX_ARCHIVE_BYTES = Long.parseLong(
            System.getenv().getOrDefault("UI2_BACKUP_MAX_ARCHIVE_BYTES", String.valueOf(50L * 1024 * 1024 * 1024)));

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

    /** V45: the device-side archives this product created and has not yet deleted; null in compositions without one. */
    private final com.securityexpert.nexus.ui2.persistence.artefact.DeviceArchiveLedger archiveLedger;

    public BackupCapabilityExecutor(DeviceTransport transport, ArtefactStore artefactStore,
            long freeSpaceThresholdBytes, Duration pollInterval, Duration runDeadline) {
        this(transport, artefactStore, freeSpaceThresholdBytes, pollInterval, runDeadline, null);
    }

    public BackupCapabilityExecutor(DeviceTransport transport, ArtefactStore artefactStore,
            long freeSpaceThresholdBytes, Duration pollInterval, Duration runDeadline,
            com.securityexpert.nexus.ui2.persistence.artefact.DeviceArchiveLedger archiveLedger) {
        this.archiveLedger = archiveLedger;
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
        // V45 (cp_backup_stale_device_archives_cleanup): before anything else, delete the archives an
        // earlier run of ours left on this device -- exactly the names the ledger holds open, and only
        // those `show backups` still lists. Best effort: a failure here is logged, never the run's fate.
        cleanupStaleArchives(session, deviceId);
        // C7 §3.3's third version source: the run's own read (2026-09-24: an MDS had no version from confirm or
        // inventory and its verified backup was refused at the store).
        Optional<String> observedVersion = BackupReadPlan.parseGaiaVersion(
                exec(session, BackupReadPlan.CP_SHOW_VERSION_ALL, DISKSPACE_TIMEOUT).output());

        // Entry 1 (BK-6): free-space precondition. Gate row cp_backup_show_diskspace, BK-7: "if the
        // Clish form fails, the Expert fallback df -P /var/log becomes primary". Measured live on the
        // first real run (2026-09-22): the Clish form answered a one-line CLI error with exit 0, and
        // the first integer of that error code was read as "329 KB free" -- so a CLI error is now
        // "the Clish form failed", never a number, and df -P /var/log is read instead.
        ExecOutcome diskspace = exec(session, BackupReadPlan.CP_SHOW_DISKSPACE, DISKSPACE_TIMEOUT);
        Optional<Long> freeBytes = parseFreeSpaceBytes(diskspace.output());
        if (freeBytes.isEmpty()) {
            ExecOutcome df = exec(session, BackupReadPlan.CP_DF_VAR_LOG, DISKSPACE_TIMEOUT);
            freeBytes = parseDfAvailableBytes(df.output());
            if (freeBytes.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING,
                        "[BACKUP_FREE_SPACE_UNPARSED] clish_shape={0} df_shape={1}",
                        maskedShape(diskspace.output()), maskedShape(df.output()));
                if (isGaiaEmbeddedCliError(diskspace.output()) || isGaiaEmbeddedCliError(df.output())) {
                    // Measured 2026-09-25: a Quantum Spark (Gaia Embedded) answers Gaia commands with "Bad parameter
                    // starting at ..."; its backup is "backup settings to sftp ..." -- a different contract, not built yet.
                    return new BackupResult.SubmitRefused("unsupported_platform: this appliance runs Gaia Embedded "
                            + "(Quantum Spark), whose backup is 'backup settings to sftp', not 'add backup local'; not built yet");
                }
                return new BackupResult.InsufficientFreeSpace(
                        "neither show diskspace nor df -P /var/log could be parsed for a free-space value; refusing (fail-closed)");
            }
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
        // Measured live (2026-09-22) and in the reference backup trail: a non-interactive
        // "add backup local" only announces that the package is being created -- the archive is
        // named by "show backup status" ("Backup file location: /var/log/CPbackup/backups/<name>")
        // once it succeeds. The submit's own output is still honoured when it does name one.
        Optional<String> archivePath = parseArchiveName(submit.output());

        // Entry 3 (BK-5): poll until terminal or the run's own deadline.
        Poll poll = pollUntilTerminalOrDeadline(session);
        BackupStatus status = poll.status();
        if (archivePath.isEmpty()) {
            archivePath = parseBackupLocation(poll.lastOutput());
        }
        if (status == BackupStatus.SUCCEEDED && archivePath.isEmpty()) {
            return new BackupResult.SubmitOutputUnparseable(
                    "neither add backup local nor show backup status named the archive; refusing rather than guessing");
        }
        String name = archivePath.orElse("<unnamed>");
        if (archivePath.isPresent() && archiveLedger != null) {
            // On record before the fetch: whatever happens from here, the next run knows what to delete.
            try {
                archiveLedger.record(deviceId, archiveBaseName(archivePath.get()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[BACKUP] archive ledger record failed: " + e.getMessage());
            }
        }
        if (status == BackupStatus.FAILED) {
            // The device's own words, digits masked, so a refusal ("backup already in progress", a full
            // disk) can be told apart from a run the device really failed -- the run's reason carries
            // the same shape, never the archive name's hostname.
            String shape = maskedShape(poll.lastOutput());
            LOG.log(System.Logger.Level.WARNING, "[BACKUP_STATUS_FAILED] status_shape=" + shape);
            return new BackupResult.SubmitRefused("show backup status reported a failed backup; status said: "
                    + shape.replaceAll("[A-Za-z0-9._-]+\\.tgz", "<archive>"));
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
            // Measured live 2026-09-22: three runs ended "sftp fetch failed: " with nothing after the colon --
            // an exception with no message. The class name is the reason then, and the stack goes to the log.
            LOG.log(System.Logger.Level.WARNING, "[BACKUP] sftp fetch threw " + e.getClass().getName(), e);
            String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            return new BackupResult.ArtefactStoreFailed("sftp fetch failed: " + message);
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
        ExecOutcome digest = exec(session, BackupReadPlan.archiveDigestCommand(name), digestTimeout(metadata.plaintextBytes()));
        Optional<String> deviceDigest = parseSha256sumOutput(digest.output());
        if (deviceDigest.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "[BACKUP_DIGEST_UNPARSED] shape={0}", maskedShape(digest.output()));
        }
        if (deviceDigest.isEmpty() || !deviceDigest.get().equalsIgnoreCase(metadata.plaintextSha256())) {
            return new BackupResult.DigestMismatch(deviceDigest.orElse("UNPARSEABLE"), metadata.plaintextSha256());
        }

        // Entry 7 (BK-7): delete the exact archive name this run created --
        // may be retried once, never a pattern, never a listing.
        ExecOutcome delete = exec(session, BackupReadPlan.deleteBackupCommand(archiveBaseName(name)), DELETE_TIMEOUT);
        if (!delete.succeeded()) {
            delete = exec(session, BackupReadPlan.deleteBackupCommand(archiveBaseName(name)), DELETE_TIMEOUT);
        }
        if (!delete.succeeded()) {
            return new BackupResult.CleanupFailed(metadata, name,
                    "delete backup " + name + " failed after its one retry (BK-7): " + delete.output());
        }

        if (archiveLedger != null) {
            try {
                archiveLedger.markDeleted(deviceId, archiveBaseName(name));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[BACKUP] archive ledger mark-deleted failed: " + e.getMessage());
            }
        }

        return new BackupResult.Completed(metadata, name, observedVersion, Optional.empty());
    }

    private void cleanupStaleArchives(TransportSession session, String deviceId) {
        if (archiveLedger == null) {
            return;
        }
        List<String> pending;
        try {
            pending = archiveLedger.pendingFor(deviceId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[BACKUP] archive ledger read failed: " + e.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        ExecOutcome listing = exec(session, BackupReadPlan.CP_SHOW_BACKUPS, POLL_TIMEOUT);
        String listed = listing.output() == null ? "" : listing.output();
        int deleted = 0;
        int gone = 0;
        for (String archive : pending) {
            if (!listed.contains(archive)) {
                gone++; // no longer on the device (an administrator removed it, or the disk was cleaned): close it
                archiveLedger.markDeleted(deviceId, archive);
                continue;
            }
            ExecOutcome delete = exec(session, BackupReadPlan.deleteBackupCommand(archive), DELETE_TIMEOUT);
            if (delete.succeeded()) {
                archiveLedger.markDeleted(deviceId, archive);
                deleted++;
            } else {
                LOG.log(System.Logger.Level.WARNING, "[BACKUP_STALE_ARCHIVE] delete refused: " + maskedShape(delete.output()));
            }
        }
        LOG.log(System.Logger.Level.INFO, "[BACKUP_STALE_ARCHIVE] pending=" + pending.size() + " deleted=" + deleted + " gone=" + gone);
    }

    private record Poll(BackupStatus status, String lastOutput) {
    }

    private Poll pollUntilTerminalOrDeadline(TransportSession session) {
        Instant deadline = Instant.now().plus(runDeadline);
        while (true) {
            ExecOutcome statusOutcome = exec(session, BackupReadPlan.CP_SHOW_BACKUP_STATUS, POLL_TIMEOUT);
            BackupStatus status = classifyStatus(statusOutcome.output());
            if (status == BackupStatus.SUCCEEDED || status == BackupStatus.FAILED) {
                return new Poll(status, statusOutcome.output());
            }
            if (!Instant.now().plus(pollInterval).isBefore(deadline)) {
                return new Poll(BackupStatus.IN_PROGRESS, statusOutcome.output()); // never terminal before the deadline -> OUTCOME_UNKNOWN
            }
            sleep(pollInterval);
        }
    }

    private static final Pattern BACKUP_LOCATION = Pattern.compile("(?i)backup file location:\\s*(\\S+\\.tgz)");

    /** "show backup status" names the archive by full path once the backup succeeded. */
    static Optional<String> parseBackupLocation(String statusOutput) {
        if (statusOutput == null) {
            return Optional.empty();
        }
        Matcher matcher = BACKUP_LOCATION.matcher(statusOutput);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** clish "delete backup" takes the file name, never a path. */
    static String archiveBaseName(String archivePathOrName) {
        int slash = archivePathOrName.lastIndexOf('/');
        return slash >= 0 ? archivePathOrName.substring(slash + 1) : archivePathOrName;
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

    /** The same CLI-error vocabulary the interactive shell uses: any of these means the command
     * was not understood, so nothing in the output is a number to trust. */
    private static final String[] CLI_ERROR_MARKERS = {"invalid command", "unknown command", "command not found",
            "syntax error", "not a valid command", "permission denied", "not authorized", "clinfr", "clicmd"};

    static boolean looksLikeCliError(String output) {
        if (output == null) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        for (String marker : CLI_ERROR_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** {@code df -P /var/log}: POSIX format, one data line, "Available" is the 4th column in 1K blocks. */
    public static Optional<Long> parseDfAvailableBytes(String dfOutput) {
        if (dfOutput == null || dfOutput.isBlank() || looksLikeCliError(dfOutput)) {
            return Optional.empty();
        }
        for (String line : dfOutput.split("\\R")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length >= 6 && tokens[1].matches("\\d+") && tokens[3].matches("\\d+")) {
                try {
                    return Optional.of(Long.parseLong(tokens[3]) * 1024L);
                } catch (NumberFormatException notANumber) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Structure-only projection for a diagnostic log: digit runs become {@code #}, whitespace collapses. */
    static String maskedShape(String output) {
        if (output == null) {
            return "<null>";
        }
        String s = output.replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    static Optional<Long> parseFreeSpaceBytes(String diskspaceOutput) {
        if (diskspaceOutput == null || diskspaceOutput.isBlank() || looksLikeCliError(diskspaceOutput)) {
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
        // The digest line may follow a banner or a shell notice (2026-09-24: one gateway's answer was UNPARSEABLE with
        // the first token read): the first line whose first token is 64 hex characters is the digest.
        for (String line : digestOutput.split("\\R")) {
            String firstToken = line.strip().split("\\s+", 2)[0];
            if (firstToken.matches("[0-9a-fA-F]{64}")) {
                return Optional.of(firstToken);
            }
        }
        return Optional.empty();
    }

    /**
     * The device-side digest's time allowance: one second per 20 MB of archive, never below {@link #DIGEST_TIMEOUT}
     * nor above 600 s (gate cp_backup_archive_digest). Measured 2026-09-25: a multi-GB archive ran past a flat 60 s.
     */
    static Duration digestTimeout(long archiveBytes) {
        long seconds = Math.max(DIGEST_TIMEOUT.toSeconds(), archiveBytes / (20L * 1024 * 1024));
        return Duration.ofSeconds(Math.min(600, seconds));
    }

    /** A Gaia Embedded (Quantum Spark) clish error answer to a Gaia command. */
    static boolean isGaiaEmbeddedCliError(String output) {
        // Only the measured Gaia Embedded phrase: a full Gaia clish answers "CLINFR0329 Invalid command" and is not Spark.
        return output != null && output.contains("Bad parameter starting at");
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
