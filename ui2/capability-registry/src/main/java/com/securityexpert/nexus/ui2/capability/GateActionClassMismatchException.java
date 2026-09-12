package com.securityexpert.nexus.ui2.capability;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * C4 §3.3 step 5: the spec author's own declared {@code action_class} on a
 * step (present for readability/review only, never for resolution)
 * disagrees with the resolved gate row's {@code action_class}. A spec
 * defect to fix, never a silent override in either direction.
 */
public final class GateActionClassMismatchException extends RuntimeException {

    public GateActionClassMismatchException(String gateId, ActionClass declared, ActionClass fromGateRow) {
        super("GATE_ACTION_CLASS_MISMATCH: step declares action_class=" + declared
                + " but gate " + gateId + " resolves action_class=" + fromGateRow
                + " -- a spec defect to fix, never a runtime override (C4 §3.3 step 5)");
    }
}
