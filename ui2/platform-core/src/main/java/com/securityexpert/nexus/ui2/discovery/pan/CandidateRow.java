package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.List;
import java.util.Optional;

/**
 * CL-1: the one candidate shape this contract defines — used, unmodified,
 * for a physical device entry and for a virtual-system entry nested inside
 * one (VS-4). A role VS-2 does not apply to a device row, or that a device
 * entry's own roles do not apply to a virtual-system row, is simply
 * {@code Optional.empty()} on that row — never repaired, never inferred.
 *
 * <p>Deliberately absent from this row: any up/down, online/offline,
 * reachable/unreachable, healthy/unhealthy field, label, colour, icon or
 * sort key (§8 LV-1), any value derived from {@link #connectionState()},
 * {@link #connectionTimestamp()}, {@link #certificateStatus()} or
 * {@link #certificateExpiration()} (LV-1 to LV-3, CS-1/CS-2), and any kind
 * or classification field (CL-1).</p>
 */
public record CandidateRow(
        Serial stableIdentifier,
        String displayName,
        Optional<String> deviceTypeMarker,
        Optional<String> ownIpv4Address,
        Optional<String> ownIpv6Address,
        Serial peerSerialReference,
        Optional<String> connectionState,
        Optional<String> connectionTimestamp,
        Optional<String> certificateStatus,
        Optional<String> certificateExpiration,
        Optional<HostLink> hostLink,
        Optional<String> sharedPolicyElementOne,
        Optional<String> sharedPolicyElementTwo,
        Optional<String> sharedPolicyElementThree,
        PairingOutcome pairingOutcome,
        List<String> unreciprocatedInboundClaimantSerials) {
}
