package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Contract §7 HA-0 to HA-4b: resolves each candidate's pairing outcome from
 * peer-serial claims alone. ID-5/HA-0: {@link PeerClaim} is deliberately
 * narrower than {@link RawDeviceInput} — it carries no address and no
 * display name, so nothing in this resolver can read one.
 *
 * <p>HA-4/HA-4a: a one-sided claim is {@code NOT_EVALUABLE} for the
 * <em>claimant</em> only. It never touches the claimed candidate's own
 * outcome — {@link #forwardOutcome} computes each candidate's outcome from
 * that candidate's own claim and its named peer's claim alone, so a third
 * candidate naming it has no way to reach in and change it. This is the
 * corrected reading; the contract's status block records that HA-4 was
 * drafted first as "both parties {@code NOT_EVALUABLE}" and rejected
 * because it lets an uncorroborated claim override a corroborated one.</p>
 *
 * <p>HA-4b: {@link #resolve} separately surfaces every unreciprocated
 * inbound claim on each candidate, keyed by the claimant's own serial —
 * never folded into the pairing outcome, never dropped.</p>
 */
public final class PeerPairingResolver {

    private PeerPairingResolver() {
    }

    /** HA-0: exactly what a pairing decision may see. */
    public record PeerClaim(Serial stableIdentifier, Serial peerSerialReference) {
    }

    /** HA-5: an outcome, plus HA-4b's separately surfaced unreciprocated inbound claims. */
    public record Result(PairingOutcome outcome, List<String> unreciprocatedInboundClaimantSerials) {
    }

    public static List<Result> resolve(List<PeerClaim> claims) {
        List<PairingOutcome> forward = new ArrayList<>();
        for (PeerClaim claim : claims) {
            forward.add(forwardOutcome(claim, claims));
        }

        List<List<String>> inbound = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            inbound.add(new ArrayList<>());
        }
        for (int i = 0; i < claims.size(); i++) {
            surfaceIfOneSided(i, claims, forward, inbound);
        }

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            results.add(new Result(forward.get(i), List.copyOf(inbound.get(i))));
        }
        return results;
    }

    /** HA-1 to HA-4: this candidate's own outcome, never influenced by who else claims it. */
    private static PairingOutcome forwardOutcome(PeerClaim claim, List<PeerClaim> all) {
        if (!claim.peerSerialReference().isPresent()) {
            return new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL);
        }
        if (claim.stableIdentifier().isPresent() && claim.stableIdentifier().equalsTrimmed(claim.peerSerialReference())) {
            return new PairingOutcome.NotEvaluable(PairingOutcome.Reason.SELF_REFERENTIAL);
        }
        Optional<PeerClaim> peer = all.stream()
                .filter(c -> c.stableIdentifier().isPresent())
                .filter(c -> c.stableIdentifier().equalsTrimmed(claim.peerSerialReference()))
                .findFirst();
        if (peer.isEmpty()) {
            return new PairingOutcome.NotEvaluable(PairingOutcome.Reason.PEER_NOT_FOUND);
        }
        boolean reciprocated = claim.stableIdentifier().isPresent()
                && peer.get().peerSerialReference().isPresent()
                && peer.get().peerSerialReference().equalsTrimmed(claim.stableIdentifier());
        if (reciprocated) {
            return new PairingOutcome.Paired(peer.get().stableIdentifier().value().orElseThrow());
        }
        return new PairingOutcome.NotEvaluable(PairingOutcome.Reason.ONE_SIDED_CLAIM);
    }

    /** HA-4b: where candidate {@code i}'s own outcome is a one-sided claim, surface it on its named peer. */
    private static void surfaceIfOneSided(int i, List<PeerClaim> claims, List<PairingOutcome> forward, List<List<String>> inbound) {
        if (!(forward.get(i) instanceof PairingOutcome.NotEvaluable ne) || ne.reason() != PairingOutcome.Reason.ONE_SIDED_CLAIM) {
            return;
        }
        PeerClaim claimant = claims.get(i);
        Optional<String> claimantSerial = claimant.stableIdentifier().value();
        if (claimantSerial.isEmpty()) {
            return;
        }
        for (int j = 0; j < claims.size(); j++) {
            if (j == i) {
                continue;
            }
            PeerClaim candidate = claims.get(j);
            if (candidate.stableIdentifier().isPresent() && candidate.stableIdentifier().equalsTrimmed(claimant.peerSerialReference())) {
                inbound.get(j).add(claimantSerial.get());
            }
        }
    }
}
