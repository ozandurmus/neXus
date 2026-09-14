package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.ConfirmCapabilityIds;

/**
 * Builds the two enrollment-confirm {@link Capability} registry entries
 * (PO_DECISION_RECORD_2026_09_14B EC-J1) directly in Java, the same way
 * {@code job-engine}'s own {@code StepExecutorTestFixtures} builds a
 * capability without a YAML round-trip -- at this maturity, neither the
 * committed {@code capabilities/*.yaml} specs (including this movement's
 * own {@code device_confirm_check_point.yaml}/{@code
 * device_confirm_palo_alto.yaml} fixtures) nor {@code cp_gaia_inventory.yaml}
 * has a production loader that scans the classpath for them; a real
 * spec-file-driven registry bootstrap is later-movement scope. Both
 * capabilities' own step list is a placeholder connect/disconnect pair with
 * no gate reference -- see {@link ConfirmJobExecutor}'s javadoc for why the
 * confirm's real device contact never runs through these steps or through
 * {@link com.securityexpert.nexus.ui2.jobs.executor.StepExecutor} at all.
 */
public final class ConfirmCapabilities {

    private ConfirmCapabilities() {
    }

    public static Capability checkPoint() {
        return load(ConfirmCapabilityIds.DEVICE_CONFIRM_CHECK_POINT, "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC);
    }

    public static Capability paloAlto() {
        return load(ConfirmCapabilityIds.DEVICE_CONFIRM_PALO_ALTO, "palo_alto", "pan_firewall",
                TransportKind.PAN_XML_API);
    }

    public static List<Capability> all() {
        return List.of(checkPoint(), paloAlto());
    }

    private static Capability load(String capabilityId, String vendor, String platformRoleScope,
            TransportKind transportKind) {
        CapabilityStep connect = new CapabilityStep(com.securityexpert.nexus.ui2.capability.StepKind.CONNECT,
                "not_applicable", null, false, Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep disconnect = new CapabilityStep(com.securityexpert.nexus.ui2.capability.StepKind.DISCONNECT,
                "not_applicable", null, false, Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec(capabilityId, vendor, platformRoleScope, transportKind,
                MaturityState.CAP_OFFLINE, List.of(connect), List.of(disconnect), "UNKNOWN", List.of(), false);
        return new CapabilityRegistryLoader(key -> List.of()).load(spec);
    }
}
