package com.securityexpert.nexus.ui2.worker.transport;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;

/**
 * Contract §5 / AC-11 / §8 test 12: "the worker's startup wiring fails
 * fast for any execution-eligible capability whose transport has no
 * registered adapter -- never a per-job surprise." Run once, at worker
 * composition time, over every capability the registry loaded -- never
 * lazily on first claim.
 *
 * <p>An <em>execution-ineligible</em> capability (one with an {@code
 * UNKNOWN}-resolved gate) declaring {@code xml_api_call}/{@code sftp_get}
 * is <b>not</b> refused here: C4 §3.5 already keeps it out of the claim
 * pool by construction, so a missing adapter for a capability that can
 * never be claimed is not a startup-blocking condition -- only an
 * execution-eligible capability with no adapter is.</p>
 */
public final class StartupTransportCheck {

    private StartupTransportCheck() {
    }

    public static void verify(CapabilityRegistry registry, TransportRegistry transportRegistry) {
        for (Capability capability : registry.all()) {
            if (capability.executionEligible() && !transportRegistry.hasAdapter(capability.transportKind())) {
                throw new UnimplementedTransportAtStartupException(capability.id(), capability.transportKind().name());
            }
        }
    }
}
