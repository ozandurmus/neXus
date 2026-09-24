package com.securityexpert.nexus.ui2.worker.discovery;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.DiscoveryCapabilityIds;

/**
 * Builds the two {@code discovery_enumerate} {@link Capability} registry
 * entries in Java directly, the same way {@code worker.inventory.
 * InventoryCapabilities} does -- see that class's javadoc for why: no
 * production loader scans the committed {@code capabilities/*.yaml}
 * fixtures at this maturity; those exist for the gate-authoring/
 * documentation purpose their own header comments name. Both stay {@link
 * MaturityState#CAP_OFFLINE} (14F: the command gates are APPROVED but the
 * registry has no state for "approved, bindings UNVERIFIED" -- see the
 * YAML fixtures' own comments).
 */
public final class DiscoveryCapabilities {

    private DiscoveryCapabilities() {
    }

    public static Capability checkPoint() {
        return load(DiscoveryCapabilityIds.CP_DISCOVERY_ENUMERATE, "check_point", "cp_multi_domain_server",
                TransportKind.SSH_EXEC);
    }

    public static Capability paloAlto() {
        return load(DiscoveryCapabilityIds.PAN_DISCOVERY_ENUMERATE, "palo_alto", "pan_panorama",
                TransportKind.PAN_XML_API);
    }

    /**
     * The Radware discovery capability is registered on the service only, like the other HTTPS jobs
     * (DEVICE_CONFIRM_HTTPS, HTTPS_VENDOR_BACKUP): the worker has no HTTPS transport adapter in its registry -- the
     * HTTPS client is called directly -- so the startup transport check would refuse it (2026-09-24, crash on rollout).
     */
    public static List<Capability> all() {
        return List.of(checkPoint(), paloAlto());
    }

    private static Capability load(String capabilityId, String vendor, String platformRoleScope,
            TransportKind transportKind) {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, platformRoleScope, transportKind,
                MaturityState.CAP_OFFLINE, List.of(connect), List.of(disconnect), "UNKNOWN", List.of(), false);
        return new CapabilityRegistryLoader(key -> List.of()).load(spec);
    }
}
