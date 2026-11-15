package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.time.Duration;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Shared gate resolution of the support bundle vendor steps: the live row must exist, be signed off, and have the expected class. */
final class SupportBundleGates {
    private SupportBundleGates() {}

    static Optional<GateResolution.Known> resolve(CanonicalCommandKey key, String expectedGate, ActionClass expectedClass,
            GateRegistryPort registry) {
        if (registry == null) return Optional.empty();
        try {
            var resolved = GateResolver.resolve(key, Optional.of(expectedClass), registry);
            return resolved instanceof GateResolution.Known known && expectedGate.equals(known.gateId())
                    && known.actionClass() == expectedClass && known.timeoutS() > 0
                    ? Optional.of(known) : Optional.empty();
        } catch (RuntimeException invalidGate) {
            return Optional.empty();
        }
    }

    static Duration timeout(GateResolution.Known gate) {
        return Duration.ofSeconds(gate.timeoutS());
    }
}
