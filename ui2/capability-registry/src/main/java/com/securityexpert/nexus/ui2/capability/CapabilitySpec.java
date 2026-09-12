package com.securityexpert.nexus.ui2.capability;

import java.util.List;
import java.util.Objects;

/**
 * The parsed, structurally-validated form of one YAML capability spec (C4
 * §2.2's runtime target of workflow §3.1's nine fields; adjudication §2
 * answer 5: specs are YAML). This is the pre-gate-resolution object --
 * {@link CapabilityRegistryLoader} turns one of these plus a
 * {@link GateRegistryPort} into a compiled {@link Capability}.
 */
public record CapabilitySpec(
        String capabilityId,
        String vendor,
        String platformRoleScope,
        TransportKind transportKind,
        MaturityState maturityState,
        List<CapabilityStep> steps,
        List<CapabilityStep> finallySteps,
        String parserVersion,
        List<String> sourcePointers,
        boolean shellStateDependencyEvidence) {

    public CapabilitySpec {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(platformRoleScope, "platformRoleScope");
        Objects.requireNonNull(transportKind, "transportKind");
        Objects.requireNonNull(maturityState, "maturityState");
        steps = steps == null ? List.of() : List.copyOf(steps);
        finallySteps = finallySteps == null ? List.of() : List.copyOf(finallySteps);
        sourcePointers = sourcePointers == null ? List.of() : List.copyOf(sourcePointers);
    }

    /** Every step, including {@code finally} -- C4 §3.5: "finally steps included." */
    public List<CapabilityStep> allSteps() {
        return java.util.stream.Stream.concat(steps.stream(), finallySteps.stream()).toList();
    }
}
