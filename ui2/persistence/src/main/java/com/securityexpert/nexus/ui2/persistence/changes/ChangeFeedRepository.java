package com.securityexpert.nexus.ui2.persistence.changes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The read-only index reads behind Operations > Change feed ("who changed what, when", mockup screen 3 of
 * 2026-10-17). Every method is bounded by a time range and a row limit, selects index columns and counters only, and
 * never writes. Sources it reads: {@code jobs}, {@code device_policy_install} (V60), {@code device_crash_alert} (V160),
 * {@code policy_change_evidence} (V163) and the {@code system_log_chunk} index (V160/V161) for the two stored sources
 * the feed parses ({@code pan_config}, {@code cp_cpwd_elg}). The policy rule history is read through
 * {@code PolicyChangeRepository}, not here.
 */
public interface ChangeFeedRepository {

    /** One job row; {@code finishedAt} and {@code outcome} are null while the job runs. */
    record JobRow(String jobId, String jobType, String targetDeviceId, String targetKind, String targetRef,
            String submittedBy, String state, String outcome, String terminalReason, Instant submittedAt, Instant finishedAt) {
    }

    /** The latest policy install a device reported (one row per device, V60): the time as parsed, the text as reported. */
    record PolicyInstallRow(String deviceId, String policyName, String installedAtText, Instant installedAt, String sourceRead,
            Instant observedAt) {
    }

    /** One crash/dump alert (one per device, run and process). */
    record CrashAlertRow(String alertId, String deviceId, String process, int fileCount, long newestMtime, String jobId,
            Instant createdAt) {
    }

    /**
     * One V163 evidence row. {@code changeTime} is the stored wall-clock text ({@code yyyy-MM-dd HH:mm:ss}) with
     * {@code timeBasis} saying whether it is the device clock or UTC; {@code adminRef} is an HMAC, {@code masked:<n>}
     * or {@code UNKNOWN}, never a name.
     */
    record EvidenceRow(String evidenceId, String sourceId, String vendor, String evidenceKind, String containerRef, String jobId,
            Instant observedAt, String entryRef, String changeTime, String timeBasis, String adminRef, String commandClass,
            String resultClass, Integer addedCount, Integer modifiedCount, Integer deletedCount, boolean objectChangesTruncated) {
    }

    /** A stored chunk with what the artefact store needs to read it; {@code recoveryVolumePath} may be null. */
    record ChunkRow(String chunkId, String deviceId, String sourceKey, String jobId, Instant collectedAt, long bytes, long lines,
            String artefactRef, byte[] wrappedDataKey, String recoveryVolumePath) {
    }

    /** Jobs submitted in {@code [from, to)}, newest first, at most {@code limit}; one device or the estate. */
    List<JobRow> jobs(Instant from, Instant to, Optional<String> deviceId, int limit);

    /** Devices whose last reported policy install time lies in {@code [from, to)}, newest first, at most {@code limit}. */
    List<PolicyInstallRow> policyInstalls(Instant from, Instant to, Optional<String> deviceId, int limit);

    /** Crash/dump alerts recorded in {@code [from, to)}, newest first, at most {@code limit}. */
    List<CrashAlertRow> crashAlerts(Instant from, Instant to, Optional<String> deviceId, int limit);

    /** V163 evidence rows observed in {@code [from, to)} (by {@code observed_at}), newest first, at most {@code limit}. */
    List<EvidenceRow> evidence(Instant from, Instant to, int limit);

    /** How many V163 evidence rows of one kind exist at all: 0 says the reader never stored anything (it is off by default). */
    long evidenceCount(String evidenceKind);

    /**
     * Live (not pruned) chunks of one source collected in {@code [from, to)}, newest first, at most {@code limit}; one
     * device or the estate. The collection time bounds the read: a chunk collected at 06:05 holds the lines since the
     * previous run, so callers widen {@code to} by one job window.
     */
    List<ChunkRow> chunks(String sourceKey, Instant from, Instant to, Optional<String> deviceId, int limit);
}
