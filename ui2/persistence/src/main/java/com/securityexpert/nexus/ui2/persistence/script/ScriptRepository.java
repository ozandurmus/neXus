package com.securityexpert.nexus.ui2.persistence.script;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@code script}, {@code script_version} (V251) and {@code script_run} (V258).
 */
public interface ScriptRepository {

    List<ScriptRecord> findAll();

    Optional<ScriptRecord> findById(String scriptId);

    Optional<ScriptRecord> findByName(String name);

    List<ScriptVersionRecord> findVersions(String scriptId);

    Optional<ScriptVersionRecord> findVersion(String scriptId, int version);

    ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actorFingerprint, String actionId);

    ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actorFingerprint, String actionId);

    Optional<ScriptRecord> updateSettings(
            String scriptId,
            boolean enabled,
            Optional<String> schedule,
            int timeoutSeconds,
            List<String> egress,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            String notifyMode,
            String runAs,
            String actorFingerprint,
            String actionId);

    /**
     * {@link #updateSettings(String, boolean, Optional, int, List, List, List, String, String, String, String)} plus the
     * V266 per-script "mail on failure only" option (meaningful with notify {@code smtp} or {@code both}).
     */
    default Optional<ScriptRecord> updateSettings(
            String scriptId,
            boolean enabled,
            Optional<String> schedule,
            int timeoutSeconds,
            List<String> egress,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            String notifyMode,
            boolean notifyMailOnFailureOnly,
            String runAs,
            String actorFingerprint,
            String actionId) {
        return updateSettings(scriptId, enabled, schedule, timeoutSeconds, egress, egressDeviceTargets, credentialRefs,
                notifyMode, runAs, actorFingerprint, actionId);
    }

    Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actorFingerprint, String actionId);

    default List<String> findEnrolledDeviceIdsByEndpointHosts(List<String> hosts) {
        return List.of();
    }

    default Optional<Instant> findLastScheduledSlot(String scriptId) {
        return Optional.empty();
    }

    default Optional<ScriptRunRecord> admitRun(
            String jobId,
            String scriptId,
            int version,
            String triggerKind,
            Optional<Instant> scheduledSlot,
            String runAs,
            List<String> egressDeclared,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            Instant requestedAt,
            String requestedBy,
            String actionId) {
        return Optional.empty();
    }

    default Optional<ScriptRunRecord> findRunByJobId(String jobId) {
        return Optional.empty();
    }

    default List<ScriptRunRecord> findRunsByScriptId(String scriptId, int limit) {
        return List.of();
    }

    /**
     * The most recent FINISHED run of a script (a run with a recorded outcome and finish time), or empty when the
     * script has never finished a run. A queued or executing run is never returned. One bounded read, no history scan.
     */
    default Optional<ScriptRunRecord> findLastFinishedRun(String scriptId) {
        return Optional.empty();
    }

    /**
     * The lease view the service checks before it releases anything to the broker
     * ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) item 4).
     */
    record RunLease(
            String jobId,
            String jobState,
            long leaseEpoch,
            String leaseWorkerId,
            String runState,
            Optional<Long> dispatchEpoch,
            Optional<String> sessionId) {
    }

    default Optional<RunLease> findRunLease(String jobId) {
        return Optional.empty();
    }

    /**
     * Marks the run RUNNING for lease epoch {@code epoch} with the new session; refused (false) when the run already
     * ended or was already dispatched for this or a later epoch.
     */
    default boolean beginDispatch(
            String jobId,
            long epoch,
            String sessionId,
            List<String> credentialRefsUsed,
            Instant startedAt,
            String actorFingerprint) {
        return false;
    }

    /** True when the {@code script_runner} broker heartbeated its module row within the last 60 seconds. */
    default boolean runnerAvailable() {
        return false;
    }

    /** One audit row for an output download, before the first byte ({@code audit_script_output_download}). */
    default void auditOutputDownload(String jobId, String actorFingerprint) {
        throw new UnsupportedOperationException("auditOutputDownload");
    }

    /** A terminal run whose output is past the 30-day raw-evidence retention window. */
    record ExpiredRunOutput(String jobId, Optional<String> outputArtefactRef) {
    }

    /** Terminal runs finished (or requested) before {@code cutoff} that still hold stored output or inline tail. */
    default List<ExpiredRunOutput> findRunsWithOutputOlderThan(Instant cutoff, int limit) {
        return List.of();
    }

    /** Clears {@code output_artefact_ref}, {@code output_key_id}, {@code output_wrapped_data_key} and {@code output_tail}. */
    default boolean clearExpiredRunOutput(String jobId, String actorFingerprint) {
        return false;
    }

    default boolean completeRun(
            String jobId,
            String state,
            String outcome,
            Instant finishedAt,
            long durationMs,
            Integer exitCode,
            int stdoutBytes,
            int stderrBytes,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Optional<String> outputArtefactRef,
            Optional<String> outputKeyId,
            byte[] outputWrappedDataKey,
            Optional<String> outputSha256,
            Optional<Long> outputSizeBytes,
            Optional<String> outputTail,
            List<String> egressDeviceTargets,
            List<String> credentialRefsUsed,
            Optional<String> failureReason,
            String actorFingerprint) {
        return false;
    }
}
