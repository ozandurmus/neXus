package com.securityexpert.nexus.ui2.capability;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * {@code resolve_gate(step) -> {KNOWN, gate_id, action_class} | {UNKNOWN, reason}}
 * (C4 §3.3). The "more than one row" case is not a member of this type: it
 * is a hard load-time error ({@link AmbiguousGateResolutionException}), per
 * C4 §3.3 step 6 -- never a runtime condition a caller routes around by
 * switching over a third variant.
 */
public sealed interface GateResolution {

    record Known(String gateId, ActionClass actionClass, int timeoutS) implements GateResolution {
    }

    record Unknown(String reason) implements GateResolution {
    }

    record NotApplicable() implements GateResolution {
    }

    default boolean executionEligible() {
        return this instanceof Known || this instanceof NotApplicable;
    }
}
