package com.securityexpert.nexus.ui2.capability;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * A registered, gate-resolved capability (C4 §2, §3). This is the compiled
 * form {@link CapabilityRegistryLoader} produces from a {@link
 * CapabilitySpec} plus a fresh {@link GateRegistryPort} query per step; the
 * registry never accepts an open-ended action/step type at runtime, and a
 * capability's {@link #executionEligible} flag is computed once at load,
 * re-derivable at claim time by re-running the same resolution (C4 §3.5).
 */
public record Capability(
        String id,
        String vendor,
        String platformRoleScope,
        TransportKind transportKind,
        MaturityState maturityState,
        List<CapabilityStep> steps,
        List<CapabilityStep> finallySteps,
        Map<CapabilityStep, GateResolution> stepResolutions,
        boolean executionEligible) {

    public Capability {
        Objects.requireNonNull(id, "id");
        steps = steps == null ? List.of() : List.copyOf(steps);
        finallySteps = finallySteps == null ? List.of() : List.copyOf(finallySteps);
        stepResolutions = stepResolutions == null ? Map.of() : Map.copyOf(stepResolutions);
    }

    /**
     * C4 §6 worked example: a resolved step's {@code action_class} is read
     * from the gate row, never author-declared. Only meaningful for a
     * step whose resolution is {@link GateResolution.Known}.
     */
    /** Every step, including {@code finally} -- C4 §3.5: "finally steps included." */
    public List<CapabilityStep> allSteps() {
        return java.util.stream.Stream.concat(steps.stream(), finallySteps.stream()).toList();
    }

    public ActionClass resolvedActionClass(CapabilityStep step) {
        GateResolution resolution = stepResolutions.get(step);
        if (resolution instanceof GateResolution.Known known) {
            return known.actionClass();
        }
        return null;
    }

    /**
     * The capability's own job-level {@code action_class} (C2 §2.1: "read
     * from the capability/profile the job references"), for a capability
     * whose steps all resolve to the same class -- the highest-severity
     * resolved class across every step (worst case if steps genuinely
     * differ; this movement's own first capability is uniformly
     * {@code CLASS_0_READ}). Defaults to {@code CLASS_0_READ} when no step
     * has resolved {@code KNOWN} yet (an execution-ineligible capability
     * never reaches this call site via {@link
     * com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService},
     * which refuses it first).
     */
    public ActionClass resolvedActionClassOrDeclaredClass() {
        return stepResolutions.values().stream()
                .filter(GateResolution.Known.class::isInstance)
                .map(r -> ((GateResolution.Known) r).actionClass())
                .max(java.util.Comparator.comparingInt(Capability::severityRank))
                .orElse(ActionClass.CLASS_0_READ);
    }

    private static int severityRank(ActionClass actionClass) {
        return switch (actionClass) {
            case CLASS_0_READ -> 0;
            case CLASS_1_RECOVERY_WRITE -> 1;
            case CLASS_1B_CONTROLLED_RESTORE_WRITE -> 2;
            case CLASS_2_OPERATIONAL_STATE_CHANGE -> 3;
            case CLASS_3_CONFIGURATION_WRITE -> 4;
            case CLASS_4_POLICY_DEPLOYMENT -> 5;
        };
    }
}
