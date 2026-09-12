package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Optional;

/**
 * Contract §6: everything the management plane can supply about one
 * object before any device is contacted (§6.1). Not a device row: no
 * evidence, no collected state, no claim about a device's current
 * condition (CR-14).
 *
 * <p>CR-0: a field modelled here as {@link Optional} may legitimately be
 * absent for a given {@link #kind()} ("{@code ?}" or "{@code —}" in the
 * §6.2 table); absence is never treated as presence.</p>
 *
 * <p>Deliberately absent from this row: any up/down, online/offline,
 * reachable/unreachable, healthy/unhealthy field, label, colour, icon or
 * sort key (CR-12, LV-1), any raw management-plane response (CR-13, T-7),
 * and any collected device state (CR-14).</p>
 */
public record CandidateRow(
        CandidateKey key,
        ObjectType objectType,
        CandidateKind kind,
        String displayName,
        Address ownAddress,
        Address managementAddress,
        Optional<ClusterReference> clusterReference,
        Optional<String> model,
        Optional<String> softwareVersion,
        Optional<String> managementPlaneConnectionState,
        Optional<HostLink> hostLink,
        ClusterLink clusterLink) {

    /** HR-1/HR-2/HR-3, computed fresh every time from the two address fields alone (HR-4). */
    public HostResolution hostResolution() {
        return HostResolution.resolve(managementAddress, ownAddress);
    }

    CandidateRow withHostLink(Optional<HostLink> newHostLink) {
        return new CandidateRow(key, objectType, kind, displayName, ownAddress, managementAddress,
                clusterReference, model, softwareVersion, managementPlaneConnectionState, newHostLink, clusterLink);
    }
}
