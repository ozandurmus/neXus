package com.securityexpert.nexus.ui2.persistence.script;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, append-only version row in {@code script_version} (V251). The body itself is stored encrypted at rest
 * in the artefact store (class {@code script_body}); only {@code sha256}, {@code sizeBytes}, {@code artefactRef},
 * {@code keyId}, {@code wrappedDataKey} and {@code secretFindings} live here, and neither the body nor the envelope
 * fields are ever returned by any read route.
 */
public record ScriptVersionRecord(
        String scriptId,
        int version,
        String sha256,
        long sizeBytes,
        String artefactRef,
        String keyId,
        byte[] wrappedDataKey,
        List<ScriptSecretFinding> secretFindings,
        List<String> credentialRefs,
        List<String> egressDeviceTargets,
        String createdBy,
        Instant createdAt) {

    public ScriptVersionRecord {
        Objects.requireNonNull(scriptId, "scriptId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got: " + version);
        }
        Objects.requireNonNull(sha256, "sha256");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got: " + sizeBytes);
        }
        Objects.requireNonNull(artefactRef, "artefactRef");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(wrappedDataKey, "wrappedDataKey");
        wrappedDataKey = Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
        secretFindings = List.copyOf(Objects.requireNonNull(secretFindings, "secretFindings"));
        credentialRefs = List.copyOf(Objects.requireNonNull(credentialRefs, "credentialRefs"));
        egressDeviceTargets = List.copyOf(Objects.requireNonNull(egressDeviceTargets, "egressDeviceTargets"));
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public byte[] wrappedDataKey() {
        return Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
    }

    @Override
    public String toString() {
        return "ScriptVersionRecord[scriptId=" + scriptId
                + ", version=" + version
                + ", sha256=" + sha256
                + ", sizeBytes=" + sizeBytes
                + ", secretFindingsCount=" + secretFindings.size()
                + ", credentialRefs=" + credentialRefs
                + ", egressDeviceTargets=" + egressDeviceTargets
                + ", createdBy=" + createdBy
                + ", createdAt=" + createdAt + "]";
    }
}
