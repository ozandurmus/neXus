package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.List;
import java.util.Optional;

/**
 * Contract §5.2. For a candidate resolved by HR-2, finds its host among the
 * candidates in the same owning domain whose own address equals its
 * management address (HL-1). CR-3a: a cluster-kind candidate's address is
 * never resolved through this search, so cluster-type candidates are
 * excluded from the search pool entirely, not merely from the result.
 */
public final class HostLinkResolver {

    private HostLinkResolver() {
    }

    /**
     * Returns {@code Optional.empty()} when host linking does not apply
     * (the candidate is not {@link HostResolution#VIRTUAL_SYSTEM_HOSTED}).
     * Otherwise returns exactly one of {@link HostLink.Linked},
     * {@link HostLink.Missing} or {@link HostLink.Ambiguous} (HL-1); in the
     * latter two cases no host identifier is recorded.
     */
    public static Optional<HostLink> resolve(CandidateRow target, List<CandidateRow> allCandidates) {
        if (target.hostResolution() != HostResolution.VIRTUAL_SYSTEM_HOSTED) {
            return Optional.empty();
        }
        List<CandidateRow> matches = allCandidates.stream()
                .filter(c -> c.objectType() != ObjectType.CLUSTER) // CR-3a
                .filter(c -> !c.key().equals(target.key()))
                .filter(c -> c.key().owningDomain().equals(target.key().owningDomain())) // HL-1: same domain
                .filter(c -> c.ownAddress().equalsTrimmed(target.managementAddress()))
                .toList();
        if (matches.size() == 1) {
            return Optional.of(new HostLink.Linked(matches.get(0).key().stableIdentifier()));
        }
        if (matches.isEmpty()) {
            return Optional.of(new HostLink.Missing());
        }
        return Optional.of(new HostLink.Ambiguous());
    }
}
