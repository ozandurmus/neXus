package com.securityexpert.nexus.ui2.capability;

/**
 * C4 §3.3 step 6: more than one {@code gate_registry} row matches the same
 * canonical key. A hard validation error at spec/registry-load time, never
 * resolved by "pick first," "pick most specific," or any other implicit
 * ranking.
 */
public final class AmbiguousGateResolutionException extends RuntimeException {

    public AmbiguousGateResolutionException(CanonicalCommandKey key, int matchCount) {
        super("AMBIGUOUS_GATE_RESOLUTION: " + matchCount + " gate_registry rows match canonical key " + key
                + " -- narrow the rows' keys, never resolved by insertion order or row id (C4 §3.3 step 6)");
    }
}
