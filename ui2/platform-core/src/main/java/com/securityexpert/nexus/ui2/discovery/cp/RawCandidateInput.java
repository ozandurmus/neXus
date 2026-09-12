package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Optional;

/**
 * One object as read from a per-domain object query (§T-2), before
 * classification or relationship resolution. CR-5: {@code owningDomain}
 * (carried inside {@link CandidateKey}) is supplied by the enumeration
 * context that issued the query — not by the object.
 */
public record RawCandidateInput(
        CandidateKey key,
        ObjectType objectType,
        ClassificationFlags flags,
        String displayName,
        Address ownAddress,
        Address managementAddress,
        Optional<ClusterReference> clusterReference,
        Optional<String> model,
        Optional<String> softwareVersion,
        Optional<String> managementPlaneConnectionState) {
}
