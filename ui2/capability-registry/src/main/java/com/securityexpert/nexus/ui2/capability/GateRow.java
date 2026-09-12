package com.securityexpert.nexus.ui2.capability;

import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * A {@code gate_registry} row (C4 §3.2) -- the runtime, queryable form of
 * one {@code docs/AI_DEVELOPMENT_PROTOCOL.md} "Network-device command gate"
 * entry. {@code gate_id} values are source-committed (adjudication F2: the
 * table is created in this movement's own {@code V4} migration, seeded from
 * a version-controlled fixture) -- never an author-typed free-text string a
 * capability spec can invent (C4 §3.2).
 */
public record GateRow(
        String gateId,
        String vendor,
        String platformRoleScope,
        String shellContext,
        String transportKind,
        String canonicalCommandKey,
        ActionClass actionClass,
        SignOffState signOffState,
        int timeoutS,
        String retryRule,
        String maxFrequency,
        String sessionReuseRule,
        String unsupportedBehaviorRef,
        String secretOutputRisk,
        List<String> safeTelemetryFields,
        String sourceDocumentPointer) {

    public GateRow {
        Objects.requireNonNull(gateId, "gateId");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(platformRoleScope, "platformRoleScope");
        Objects.requireNonNull(shellContext, "shellContext");
        Objects.requireNonNull(transportKind, "transportKind");
        Objects.requireNonNull(canonicalCommandKey, "canonicalCommandKey");
        Objects.requireNonNull(actionClass, "actionClass");
        Objects.requireNonNull(signOffState, "signOffState");
        safeTelemetryFields = safeTelemetryFields == null ? List.of() : List.copyOf(safeTelemetryFields);
    }

    /**
     * The key this row answers for -- compared against a step's own
     * {@link CanonicalCommandKey} by exact record equality only (C4 §3.3
     * step 3).
     */
    public CanonicalCommandKey key() {
        return new CanonicalCommandKey(vendor, platformRoleScope, shellContext, transportKind, canonicalCommandKey);
    }

    /**
     * C4 §3.3 step 7: a class 2/3/4 row can never be {@code SIGNED_OFF} by
     * construction (AGENTS.md "No new class 2, 3 or 4 command"). Enforced
     * at gate-row creation (here) rather than re-checked per step
     * resolution.
     */
    public boolean violatesWriteMarkerDenylist() {
        boolean isSignedOff = signOffState == SignOffState.SIGNED_OFF;
        boolean isHighClass = actionClass == ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE
                || actionClass == ActionClass.CLASS_3_CONFIGURATION_WRITE
                || actionClass == ActionClass.CLASS_4_POLICY_DEPLOYMENT;
        return isSignedOff && isHighClass;
    }
}
