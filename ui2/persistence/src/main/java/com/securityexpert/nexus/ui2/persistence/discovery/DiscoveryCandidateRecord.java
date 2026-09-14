package com.securityexpert.nexus.ui2.persistence.discovery;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@code discovery_candidate} row (14F DR-2) -- exactly the candidate-row
 * fields the two FROZEN discovery contracts define, plus {@code importable}
 * (import contract §2 kind table) and, after import, {@code importOutcome}
 * (RD-5). Never a device: only import creates a {@code devices} row.
 *
 * <p>{@link #toString()} omits every {@code CLASS 2} field ({@link
 * #displayName()}, {@link #ownAddress()}, {@link #managementAddress()}) --
 * the same discipline {@link DiscoveryRun#toString()} applies.</p>
 */
public record DiscoveryCandidateRecord(
        String candidateId,
        String runId,
        String vendor,
        String stableIdentifier,
        Optional<String> owningDomain,
        String kind,
        String displayName,
        Optional<String> ownAddress,
        Optional<String> managementAddress,
        Optional<String> clusterReference,
        Optional<String> parentCandidateId,
        Optional<String> model,
        Optional<String> softwareVersion,
        Optional<String> connectionState,
        boolean importable,
        Optional<String> importOutcome) {

    public DiscoveryCandidateRecord {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(stableIdentifier, "stableIdentifier");
        Objects.requireNonNull(kind, "kind");
    }

    @Override
    public String toString() {
        return "DiscoveryCandidateRecord[candidateId=" + candidateId + ", runId=" + runId + ", vendor=" + vendor
                + ", kind=" + kind + ", importable=" + importable + ", importOutcome=" + importOutcome + "]";
    }
}
