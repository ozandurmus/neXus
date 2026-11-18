package com.securityexpert.nexus.ui2.persistence.script;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator script record in {@code script} (V251, SCRIPT_EXECUTION_CONTRACT.md section 3.1 and Amendment 2026-11-18).
 */
public record ScriptRecord(
        String scriptId,
        String name,
        String kind,
        int currentVersion,
        boolean enabled,
        Optional<String> schedule,
        int timeoutSeconds,
        List<String> egress,
        List<String> egressDeviceTargets,
        List<String> credentialRefs,
        String notifyMode,
        String runAs,
        String createdByActorFingerprint,
        Instant createdAt,
        Instant updatedAt) {

    public ScriptRecord {
        Objects.requireNonNull(scriptId, "scriptId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (currentVersion < 1) {
            throw new IllegalArgumentException("currentVersion must be >= 1, got: " + currentVersion);
        }
        Objects.requireNonNull(schedule, "schedule");
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600, got: " + timeoutSeconds);
        }
        egress = List.copyOf(Objects.requireNonNull(egress, "egress"));
        egressDeviceTargets = List.copyOf(Objects.requireNonNull(egressDeviceTargets, "egressDeviceTargets"));
        credentialRefs = List.copyOf(Objects.requireNonNull(credentialRefs, "credentialRefs"));
        Objects.requireNonNull(notifyMode, "notifyMode");
        Objects.requireNonNull(runAs, "runAs");
        Objects.requireNonNull(createdByActorFingerprint, "createdByActorFingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public String toString() {
        return "ScriptRecord[scriptId=" + scriptId
                + ", name=" + name
                + ", kind=" + kind
                + ", currentVersion=" + currentVersion
                + ", enabled=" + enabled
                + ", schedule=" + schedule.orElse("-")
                + ", timeoutSeconds=" + timeoutSeconds
                + ", egressCount=" + egress.size()
                + ", egressDeviceTargets=" + egressDeviceTargets
                + ", credentialRefs=" + credentialRefs
                + ", notifyMode=" + notifyMode
                + ", runAs=" + runAs
                + ", createdByActorFingerprint=" + createdByActorFingerprint
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + "]";
    }
}
