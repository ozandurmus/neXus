package com.securityexpert.nexus.ui2.capability;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * {@code resolve_gate(step) -> {KNOWN, gate_id, action_class} | {UNKNOWN, reason}}
 * (C4 §3.3), implemented exactly as the seven numbered steps state.
 * Deterministic, side-effect-free over whatever {@link GateRegistryPort}
 * returns -- this class never caches a resolution itself; a caller that
 * wants "re-evaluated fresh at claim time, never cached across a sign-off
 * change" (C4 §3.3) gets that for free by calling {@link #resolve} again.
 */
public final class GateResolver {

    private GateResolver() {
    }

    /**
     * @param key                   the step's own canonical command key, or
     *                              {@code null} for a kind/spec-declared
     *                              {@code NOT_APPLICABLE} step (C4 §3.3 step 1)
     * @param declaredActionClass   the spec author's own declared class on
     *                              the step, present for readability only
     *                              (C4 §3.3 step 5); {@code empty} if the
     *                              author declared none
     */
    public static GateResolution resolve(CanonicalCommandKey key, Optional<ActionClass> declaredActionClass,
            GateRegistryPort registry) {
        if (key == null) {
            // Step 1: NOT_APPLICABLE kinds/declarations skip resolution
            // entirely -- never looked up.
            return new GateResolution.NotApplicable();
        }

        List<GateRow> matches = registry.findByCanonicalKey(key);

        if (matches.isEmpty()) {
            // Step 4: zero rows.
            return new GateResolution.Unknown("requires gate entry");
        }
        if (matches.size() > 1) {
            // Step 6: never resolved by insertion order or row id.
            throw new AmbiguousGateResolutionException(key, matches.size());
        }

        GateRow row = matches.get(0);
        if (row.signOffState() != SignOffState.SIGNED_OFF) {
            // Step 5, second bullet: DRAFTED / SIGNED_OFF_PENDING_HARDWARE_
            // CONFIRMATION / BLOCKED / SUPERSEDED -- named with the state.
            return new GateResolution.Unknown("gate exists, " + row.signOffState() + " pending");
        }

        // Step 5, first bullet: KNOWN. action_class comes from the row,
        // never the spec author's declaration; a mismatch fails rather
        // than silently overriding either value.
        if (declaredActionClass.isPresent() && declaredActionClass.get() != row.actionClass()) {
            throw new GateActionClassMismatchException(row.gateId(), declaredActionClass.get(), row.actionClass());
        }
        // Step 7: defensive re-check -- a SIGNED_OFF class-2/3/4 row should
        // never exist by construction (enforced at gate-row creation), but
        // resolution never trusts that invariant blindly.
        if (row.violatesWriteMarkerDenylist()) {
            throw new IllegalStateException(
                    "WRITE_MARKER_DENYLIST_VIOLATION: gate " + row.gateId()
                            + " is SIGNED_OFF with action_class=" + row.actionClass()
                            + " -- no class 2/3/4 command may ever be SIGNED_OFF (AGENTS.md)");
        }

        return new GateResolution.Known(row.gateId(), row.actionClass(), row.timeoutS());
    }
}
