package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Optional;

/**
 * One object as read from a per-domain object query (§T-2), before
 * classification or relationship resolution. CR-5: {@code owningDomain}
 * (carried inside {@link CandidateKey}) is supplied by the enumeration
 * context that issued the query — not by the object.
 *
 * <p>CS-2: {@code connectionTableChannelState} is carried under its own
 * name, populated from the §7.4 connection-table read, never merged with
 * {@code managementPlaneConnectionState} (a different field, on a different
 * plane, §7.1).</p>
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
        Optional<String> managementPlaneConnectionState,
        Optional<ConnectionTableChannelState> connectionTableChannelState) {
}
