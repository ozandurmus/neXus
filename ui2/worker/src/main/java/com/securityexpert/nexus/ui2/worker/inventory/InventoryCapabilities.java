package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;

/**
 * Builds the two {@code inventory_collect} {@link Capability} registry
 * entries in Java directly, the same way {@code worker.confirm.
 * ConfirmCapabilities} does -- at this maturity no production loader scans
 * the classpath for the committed {@code capabilities/*.yaml} fixtures
 * (those exist for the gate-authoring/documentation purpose {@code
 * cp_inventory_collect.yaml}/{@code pan_inventory_collect.yaml}'s own
 * header comments name). Both capabilities' step list is a placeholder
 * connect/disconnect pair with no gate reference -- the real device
 * contact runs through {@link InventoryJobExecutor}/{@link
 * InventoryCapabilityExecutor} over the closed {@link InventoryReadPlan},
 * never through {@link com.securityexpert.nexus.ui2.jobs.executor.StepExecutor}.
 * Both stay {@link MaturityState#CAP_OFFLINE} -- 14C §5: no gate row is
 * authored, no live run is dispatched, until the measurement lands.
 */
public final class InventoryCapabilities {

    private InventoryCapabilities() {
    }

    public static Capability checkPoint() {
        return load(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC);
    }

    public static Capability paloAlto() {
        return load(InventoryCapabilityIds.PAN_INVENTORY_COLLECT, "palo_alto", "pan_firewall",
                TransportKind.PAN_XML_API);
    }

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
