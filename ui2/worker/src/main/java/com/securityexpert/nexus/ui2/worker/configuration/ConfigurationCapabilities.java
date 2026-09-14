package com.securityexpert.nexus.ui2.worker.configuration;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.admission.ConfigurationCapabilityIds;

/**
 * Builds the two {@code configuration_collect} {@link Capability} registry
 * entries in Java directly -- mirrors {@code worker.inventory.InventoryCapabilities}
 * exactly. The real device contact runs through {@link ConfigurationJobExecutor}/
 * {@link ConfigurationCapabilityExecutor} over the closed {@link
 * ConfigurationReadPlan}, never through {@code StepExecutor}; these steps
 * exist only so {@link Capability#executionEligible()} reflects whether
 * that read plan's literals are actually gated (docs/design/
 * CP_CONFIGURATION_COMMAND_GATE_ENTRIES.md, docs/design/
 * PAN_CONFIGURATION_API_ROUTE_GATE_ENTRIES.md).
 */
public final class ConfigurationCapabilities {

    private ConfigurationCapabilities() {
    }

    public static Capability checkPoint(GateRegistryPort gateRegistry) {
        CapabilityStep connect = connectStep();
        List<CapabilityStep> reads = new java.util.ArrayList<>();
        for (String read : ConfigurationReadPlan.CHECK_POINT_IDENTITY_READS) {
            reads.add(execStep(read));
        }
        reads.add(execStep(ConfigurationReadPlan.CP_SHOW_CONFIGURATION));
        CapabilitySpec spec = new CapabilitySpec(ConfigurationCapabilityIds.CP_CONFIGURATION_COLLECT, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED, prepend(connect, reads),
                List.of(disconnectStep()), "14G", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    public static Capability paloAlto(GateRegistryPort gateRegistry) {
        CapabilityStep connect = connectStep();
        List<CapabilityStep> requests = List.of(xmlApiCallStep(ConfigurationReadPlan.PAN_SHOW_SYSTEM_INFO),
                xmlApiCallStep("type=config&action=show&xpath=/config"),
                xmlApiCallStep(ConfigurationReadPlan.PAN_EFFECTIVE_RUNNING),
                xmlApiCallStep(ConfigurationReadPlan.PAN_MERGED));
        CapabilitySpec spec = new CapabilitySpec(ConfigurationCapabilityIds.PAN_CONFIGURATION_COLLECT, "palo_alto",
                "pan_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED, prepend(connect, requests),
                List.of(disconnectStep()), "14G", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    public static List<Capability> all(GateRegistryPort gateRegistry) {
        return List.of(checkPoint(gateRegistry), paloAlto(gateRegistry));
    }

    private static List<CapabilityStep> prepend(CapabilityStep first, List<CapabilityStep> rest) {
        List<CapabilityStep> result = new java.util.ArrayList<>();
        result.add(first);
        result.addAll(rest);
        return result;
    }

    private static CapabilityStep connectStep() {
        return new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep disconnectStep() {
        return new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep execStep(String send) {
        return new CapabilityStep(StepKind.EXEC, "expert", send, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep xmlApiCallStep(String send) {
        return new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", send, false, Optional.empty(),
                Optional.empty(), Optional.empty());
    }
}
