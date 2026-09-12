package com.securityexpert.nexus.ui2.capability;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a validated {@link CapabilitySpec} into a compiled {@link
 * Capability} by resolving every step's (and every {@code finally} step's)
 * gate against a fresh {@link GateRegistryPort} query (C4 §3.3, §3.5;
 * contract §3 items 1-3). The execution-eligible flag is the AND over
 * every step's resolution being {@code KNOWN} or {@code NOT_APPLICABLE} --
 * a capability with one {@code UNKNOWN} step still compiles, still runs its
 * parser against fixtures ({@code CAP-OFFLINE} reachable, C4 §3.5); only
 * {@link Capability#executionEligible()} gates whether the claim query's
 * eligible {@code job_type} set includes it (contract §3).
 */
public final class CapabilityRegistryLoader {

    private final GateRegistryPort gateRegistry;

    public CapabilityRegistryLoader(GateRegistryPort gateRegistry) {
        this.gateRegistry = gateRegistry;
    }

    public Capability load(CapabilitySpec spec) {
        CapabilitySpecValidator.validate(spec);
        CapabilitySpecValidator.validateTransportEvidence(spec);

        Map<CapabilityStep, GateResolution> resolutions = new LinkedHashMap<>();
        boolean eligible = true;
        for (CapabilityStep step : spec.allSteps()) {
            var key = step.canonicalKey(spec.vendor(), spec.platformRoleScope(), spec.transportKind());
            GateResolution resolution = GateResolver.resolve(key, step.declaredActionClass(), gateRegistry);
            resolutions.put(step, resolution);
            eligible = eligible && resolution.executionEligible();
        }

        return new Capability(spec.capabilityId(), spec.vendor(), spec.platformRoleScope(), spec.transportKind(),
                spec.maturityState(), spec.steps(), spec.finallySteps(), resolutions, eligible);
    }

    public List<Capability> loadAll(List<CapabilitySpec> specs) {
        List<Capability> result = new java.util.ArrayList<>();
        Map<String, Capability> byId = new HashMap<>();
        for (CapabilitySpec spec : specs) {
            Capability capability = load(spec);
            if (byId.put(capability.id(), capability) != null) {
                throw new CapabilityValidationException("DUPLICATE_CAPABILITY_ID",
                        "more than one capability spec declares capability_id=" + capability.id());
            }
            result.add(capability);
        }
        return List.copyOf(result);
    }
}
