package com.securityexpert.nexus.ui2.jobs.evidence;

import java.util.Optional;

/**
 * One {@code provenance_records} row (contract §7; C1 §5). {@code
 * sanitizedFragment} is {@code empty} by default -- populated only when the
 * capability's {@code evidence_shape_ref} explicitly permits one (contract
 * §7: "by default none is written"). The raw response itself is never a
 * field here: only its {@code fingerprintSha256} survives past this
 * boundary (raw-evidence law).
 */
public record ProvenanceRecordData(
        String provenanceId,
        String runId,
        String stepId,
        String parserVersion,
        String capabilityVersion,
        Optional<String> captureArtifactId,
        String sourceLocation,
        Optional<String> sanitizedFragment,
        String fingerprintSha256) {

    public ProvenanceRecordData {
        captureArtifactId = captureArtifactId == null ? Optional.empty() : captureArtifactId;
        sanitizedFragment = sanitizedFragment == null ? Optional.empty() : sanitizedFragment;
    }
}
