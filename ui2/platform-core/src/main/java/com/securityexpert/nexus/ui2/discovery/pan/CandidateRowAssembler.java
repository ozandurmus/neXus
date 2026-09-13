package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns one enumeration's {@link RawDeviceInput} list into the returned
 * candidate set: resolves every device's HA pairing outcome in one pass
 * (§7), then flattens each device into its own row plus one row per nested
 * virtual-system entry (VS-4), each virtual-system row's host link read
 * directly off the nesting (VS-3) — no second pass, no search. §10:
 * every input produces exactly one row, plus one per nested virtual system;
 * nothing is dropped for a missing serial (DI-4), a non-established or
 * absent connection state (DI-5), or a {@code NOT_EVALUABLE} outcome
 * (DI-6). No deduplication (DI-7).
 */
public final class CandidateRowAssembler {

    private CandidateRowAssembler() {
    }

    public static List<CandidateRow> assemble(List<RawDeviceInput> devices) {
        List<PeerPairingResolver.PeerClaim> claims = devices.stream()
                .map(d -> new PeerPairingResolver.PeerClaim(d.stableIdentifier(), d.peerSerialReference()))
                .toList();
        List<PeerPairingResolver.Result> pairingResults = PeerPairingResolver.resolve(claims);

        List<CandidateRow> rows = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            RawDeviceInput device = devices.get(i);
            rows.add(toDeviceRow(device, pairingResults.get(i)));
            for (RawVirtualSystemInput vs : device.virtualSystems()) {
                rows.add(toVirtualSystemRow(vs, device.stableIdentifier()));
            }
        }
        return List.copyOf(rows);
    }

    private static CandidateRow toDeviceRow(RawDeviceInput device, PeerPairingResolver.Result pairing) {
        return new CandidateRow(device.stableIdentifier(), device.displayName(),
                Optional.of(device.deviceTypeMarker()), device.ownIpv4Address(), device.ownIpv6Address(),
                device.peerSerialReference(), device.connectionState(), device.connectionTimestamp(),
                device.certificateStatus(), device.certificateExpiration(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), pairing.outcome(),
                pairing.unreciprocatedInboundClaimantSerials());
    }

    private static CandidateRow toVirtualSystemRow(RawVirtualSystemInput vs, Serial parentStableIdentifier) {
        return new CandidateRow(vs.stableIdentifier(), vs.displayName(), Optional.empty(), Optional.empty(),
                Optional.empty(), Serial.absent(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(new HostLink(parentStableIdentifier)),
                vs.sharedPolicyElementOne(), vs.sharedPolicyElementTwo(), vs.sharedPolicyElementThree(),
                new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL), List.of());
    }
}
