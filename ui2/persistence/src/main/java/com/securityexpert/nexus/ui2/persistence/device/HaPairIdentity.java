package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Objects;
import java.util.Optional;

/**
 * A failover pair's serial identity (V206, failover Phase 0, PO 2026-11-13): the two unit serials recorded the first
 * time both enrolled members of the pair reported two different serials. Afterwards the pair is only compared with
 * what its members report, never re-bound. For a Cisco ASA pair the two device rows are address slots that follow the
 * active/standby role, so the comparison is on the pair's serial set, never per address.
 *
 * <p>Serials are opaque: compared with exact string equality only (AGENTS.md identity law). The low/high order is a
 * storage representation, not a meaning.
 */
public record HaPairIdentity(String clusterMemberRef, String vendor, String serialLow, String serialHigh, State lastState) {

    /** MATCH: both members report exactly the recorded two serials; MISMATCH: a reported serial is not one of them;
     *  MISSING: a member reported no serial yet; AMBIGUOUS: both members reported the same serial. */
    public enum State { MATCH, MISMATCH, MISSING, AMBIGUOUS }

    public HaPairIdentity {
        Objects.requireNonNull(clusterMemberRef, "clusterMemberRef");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(serialLow, "serialLow");
        Objects.requireNonNull(serialHigh, "serialHigh");
        Objects.requireNonNull(lastState, "lastState");
        if (serialLow.compareTo(serialHigh) >= 0) {
            throw new IllegalArgumentException("serialLow must sort before serialHigh");
        }
    }

    /** What one observation of the pair means: the state, and the pair to record when none is recorded yet. */
    public record Decision(State state, Optional<String[]> toRecord) {
    }

    /**
     * Decides from the recorded pair (if any) and the latest serial each of the two members reported. A pair is
     * recorded only on a first observation with two present, different serials.
     */
    public static Decision decide(Optional<HaPairIdentity> recorded, Optional<String> serialA, Optional<String> serialB) {
        Optional<String> a = serialA.map(String::strip).filter(s -> !s.isEmpty());
        Optional<String> b = serialB.map(String::strip).filter(s -> !s.isEmpty());
        if (a.isEmpty() || b.isEmpty()) {
            return new Decision(State.MISSING, Optional.empty());
        }
        if (a.get().equals(b.get())) {
            return new Decision(State.AMBIGUOUS, Optional.empty());
        }
        String low = a.get().compareTo(b.get()) < 0 ? a.get() : b.get();
        String high = low.equals(a.get()) ? b.get() : a.get();
        if (recorded.isEmpty()) {
            return new Decision(State.MATCH, Optional.of(new String[] {low, high}));
        }
        boolean same = recorded.get().serialLow().equals(low) && recorded.get().serialHigh().equals(high);
        return new Decision(same ? State.MATCH : State.MISMATCH, Optional.empty());
    }
}
