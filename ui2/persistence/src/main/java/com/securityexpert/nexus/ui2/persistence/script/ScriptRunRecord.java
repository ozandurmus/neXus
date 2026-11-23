package com.securityexpert.nexus.ui2.persistence.script;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable ledger record for a script execution run in {@code script_run} (V258,
 * {@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.3, 3.4); {@code notification} is the V266 run-end notification
 * outcome (section 3.5), empty until the notification forwarder records it.
 */
public record ScriptRunRecord(
        String jobId,
        String scriptId,
        int version,
        String state,
        Optional<String> outcome,
        String triggerKind,
        Optional<Instant> scheduledSlot,
        String runAs,
        String requestedBy,
        Instant requestedAt,
        Optional<Instant> startedAt,
        Optional<Instant> finishedAt,
        Optional<Long> durationMs,
        Optional<Integer> exitCode,
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
        List<String> egressDeclared,
        List<String> egressDeviceTargets,
        List<String> credentialRefsUsed,
        Optional<String> sessionId,
        Optional<String> failureReason,
        Optional<ScriptRunNotification> notification) {

    /** Without a recorded V266 notification outcome. */
    public ScriptRunRecord(
            String jobId,
            String scriptId,
            int version,
            String state,
            Optional<String> outcome,
            String triggerKind,
            Optional<Instant> scheduledSlot,
            String runAs,
            String requestedBy,
            Instant requestedAt,
            Optional<Instant> startedAt,
            Optional<Instant> finishedAt,
            Optional<Long> durationMs,
            Optional<Integer> exitCode,
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
            List<String> egressDeclared,
            List<String> egressDeviceTargets,
            List<String> credentialRefsUsed,
            Optional<String> sessionId,
            Optional<String> failureReason) {
        this(jobId, scriptId, version, state, outcome, triggerKind, scheduledSlot, runAs, requestedBy, requestedAt,
                startedAt, finishedAt, durationMs, exitCode, stdoutBytes, stderrBytes, stdoutTruncated, stderrTruncated,
                outputArtefactRef, outputKeyId, outputWrappedDataKey, outputSha256, outputSizeBytes, outputTail,
                egressDeclared, egressDeviceTargets, credentialRefsUsed, sessionId, failureReason, Optional.empty());
    }

    public ScriptRunRecord {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(scriptId, "scriptId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got: " + version);
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(triggerKind, "triggerKind");
        Objects.requireNonNull(scheduledSlot, "scheduledSlot");
        Objects.requireNonNull(runAs, "runAs");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(durationMs, "durationMs");
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(outputArtefactRef, "outputArtefactRef");
        Objects.requireNonNull(outputKeyId, "outputKeyId");
        outputWrappedDataKey = outputWrappedDataKey == null ? null : outputWrappedDataKey.clone();
        Objects.requireNonNull(outputSha256, "outputSha256");
        Objects.requireNonNull(outputSizeBytes, "outputSizeBytes");
        Objects.requireNonNull(outputTail, "outputTail");
        egressDeclared = egressDeclared == null ? List.of() : List.copyOf(egressDeclared);
        egressDeviceTargets = egressDeviceTargets == null ? List.of() : List.copyOf(egressDeviceTargets);
        credentialRefsUsed = credentialRefsUsed == null ? List.of() : List.copyOf(credentialRefsUsed);
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(failureReason, "failureReason");
        notification = notification == null ? Optional.empty() : notification;
    }

    @Override
    public byte[] outputWrappedDataKey() {
        return outputWrappedDataKey == null ? null : outputWrappedDataKey.clone();
    }

    @Override
    public String toString() {
        return "ScriptRunRecord[jobId=" + jobId
                + ", scriptId=" + scriptId
                + ", version=" + version
                + ", state=" + state
                + ", outcome=" + outcome.orElse("-")
                + ", triggerKind=" + triggerKind
                + ", scheduledSlot=" + scheduledSlot.map(Instant::toString).orElse("-")
                + ", runAs=" + runAs
                + ", requestedBy=" + requestedBy
                + ", requestedAt=" + requestedAt
                + ", startedAt=" + startedAt.map(Instant::toString).orElse("-")
                + ", finishedAt=" + finishedAt.map(Instant::toString).orElse("-")
                + ", durationMs=" + durationMs.map(Object::toString).orElse("-")
                + ", exitCode=" + exitCode.map(Object::toString).orElse("-")
                + ", stdoutBytes=" + stdoutBytes
                + ", stderrBytes=" + stderrBytes
                + ", stdoutTruncated=" + stdoutTruncated
                + ", stderrTruncated=" + stderrTruncated
                + ", outputArtefactRef=" + outputArtefactRef.orElse("-")
                + ", outputKeyId=" + outputKeyId.orElse("-")
                + ", outputWrappedDataKey=[REDACTED]"
                + ", outputSha256=" + outputSha256.orElse("-")
                + ", outputSizeBytes=" + outputSizeBytes.map(Object::toString).orElse("-")
                + ", egressDeviceTargets=" + egressDeviceTargets
                + ", credentialRefsUsed=" + credentialRefsUsed
                + ", failureReason=" + failureReason.orElse("-")
                + ", notification=" + notification.map(Object::toString).orElse("-") + "]";
    }
}
