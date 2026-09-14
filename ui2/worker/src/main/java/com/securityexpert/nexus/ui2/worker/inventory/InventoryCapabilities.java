package com.securityexpert.nexus.ui2.worker.inventory;

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
import com.securityexpert.nexus.ui2.jobs.admission.InventoryCapabilityIds;

/**
 * Builds the two {@code inventory_collect} {@link Capability} registry
 * entries in Java directly, the same way {@code worker.confirm.
 * ConfirmCapabilities} does -- at this maturity no production loader scans
 * the classpath for the committed {@code capabilities/*.yaml} fixtures
 * (those exist for the gate-authoring/documentation purpose {@code
 * cp_inventory_collect.yaml}/{@code pan_inventory_collect.yaml}'s own
 * header comments name; keep the two literal sets in sync).
 *
 * <p>NXS-LOCAL-0164: the step lists below now carry the real 14D CF-3/
 * 14E PF-1 literals with real gate references, resolved against the
 * caller-supplied {@link GateRegistryPort} (the runtime {@code
 * gate_registry} table, V15-seeded) instead of an empty one -- C4
 * execution eligibility is computed from gate resolution, not a
 * connect-only placeholder that was execution-eligible by construction.
 * The real device contact still runs through {@link InventoryJobExecutor}/
 * {@link InventoryCapabilityExecutor} over the closed {@link
 * InventoryReadPlan}, never through {@code StepExecutor} -- these steps
 * exist so {@link Capability#executionEligible()} reflects whether that
 * read plan's literals are actually gated, not so {@code StepExecutor}
 * runs them.</p>
 */
public final class InventoryCapabilities {

    private InventoryCapabilities() {
    }

    public static Capability checkPoint(GateRegistryPort gateRegistry) {
        CapabilityStep connect = connectStep();
        List<CapabilityStep> physicalReads = InventoryReadPlan.CHECK_POINT_PHYSICAL_READS.stream()
                .map(read -> execStep("expert", read))
                .toList();
        CapabilitySpec spec = new CapabilitySpec(InventoryCapabilityIds.CP_INVENTORY_COLLECT, "check_point",
                "cp_gaia_gateway", TransportKind.SSH_EXEC, MaturityState.CAP_VALIDATED,
                prepend(connect, physicalReads), List.of(disconnectStep()), "14D", List.of(), false);
        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    public static Capability paloAlto(GateRegistryPort gateRegistry) {
        CapabilityStep connect = connectStep();
        List<CapabilityStep> requests = InventoryReadPlan.PALO_ALTO_BASE_STEPS.stream()
                .map(send -> xmlApiCallStep(send))
                .toList();
        CapabilitySpec spec = new CapabilitySpec(InventoryCapabilityIds.PAN_INVENTORY_COLLECT, "palo_alto",
                "pan_firewall", TransportKind.PAN_XML_API, MaturityState.CAP_VALIDATED,
                prepend(connect, requests), List.of(disconnectStep()), "14E", List.of(), false);
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

    private static CapabilityStep execStep(String shellContext, String send) {
        return new CapabilityStep(StepKind.EXEC, shellContext, send, false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static CapabilityStep xmlApiCallStep(String send) {
        return new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", send, false, Optional.empty(),
                Optional.empty(), Optional.empty());
    }
}
