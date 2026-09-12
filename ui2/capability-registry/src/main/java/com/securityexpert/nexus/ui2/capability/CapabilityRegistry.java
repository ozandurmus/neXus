package com.securityexpert.nexus.ui2.capability;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-process capability registry (C4 §2). Every capability -- eligible
 * or not -- is present here (C4 §3.5: an {@code UNKNOWN}-gated capability
 * "is present in the full spec-level registry"); {@link
 * #executionEligibleIds()} is the narrower, admission/claim-facing view.
 */
public final class CapabilityRegistry {

    private final Map<String, Capability> capabilities = new ConcurrentHashMap<>();

    public static CapabilityRegistry of(List<Capability> capabilities) {
        CapabilityRegistry registry = new CapabilityRegistry();
        capabilities.forEach(registry::register);
        return registry;
    }

    public void register(Capability capability) {
        capabilities.put(capability.id(), capability);
    }

    public Optional<Capability> find(String capabilityId) {
        return Optional.ofNullable(capabilities.get(capabilityId));
    }

    public List<Capability> all() {
        return List.copyOf(capabilities.values());
    }

    /**
     * C4 §3.5's execution-eligible view: the subset of {@code
     * capability_registry} that C2's job admission is permitted to read
     * {@code capability_id}/{@code action_class} from. A capability with
     * any {@code UNKNOWN} step is never a member, by construction, not by
     * an additional runtime check re-deriving what this document already
     * fixes at registry-compile time.
     */
    public List<String> executionEligibleIds() {
        return capabilities.values().stream()
                .filter(Capability::executionEligible)
                .map(Capability::id)
                .toList();
    }

    public boolean isExecutionEligible(String capabilityId) {
        Capability capability = capabilities.get(capabilityId);
        return capability != null && capability.executionEligible();
    }
}
