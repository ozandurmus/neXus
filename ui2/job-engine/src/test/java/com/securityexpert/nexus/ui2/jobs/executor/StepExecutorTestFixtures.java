package com.securityexpert.nexus.ui2.jobs.executor;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.capability.StepKind;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Builds small, in-memory capabilities for {@link StepExecutor} tests -- no YAML, no database. */
final class StepExecutorTestFixtures {

    private StepExecutorTestFixtures() {
    }

    /** connect -> exec("show version", read) -> disconnect. Mirrors the shipped cp_gaia_inventory shape, minimally. */
    static Capability readOnlyTwoStepCapability() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show version", false,
                Optional.of(ActionClass.CLASS_0_READ), Optional.of("^Product version.*$"), Optional.of(30));
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());

        CapabilitySpec spec = new CapabilitySpec("test_cp_inventory", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect, exec), List.of(disconnect),
                "UNKNOWN", List.of(), false);

        GateRegistryPort gateRegistry = key -> {
            if (key.commandKey().equals("show version")) {
                return List.of(new GateRow("test_show_version", "check_point", "cp_gaia_gateway", "clish",
                        "SSH_EXEC", "show version", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 30, null,
                        null, null, null, null, List.of(), "test fixture"));
            }
            return List.of();
        };

        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }

    /** connect -> exec("add backup local", recovery-write) -> disconnect. For OUTCOME_UNKNOWN / boundary tests. */
    static Capability writeStepCapability() {
        CapabilityStep connect = new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "expert_via_clish_c", "add backup local", false,
                Optional.of(ActionClass.CLASS_1_RECOVERY_WRITE), Optional.of("^OK.*$"), Optional.of(900));
        CapabilityStep disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                Optional.empty(), Optional.empty(), Optional.empty());

        CapabilitySpec spec = new CapabilitySpec("test_backup_write", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(connect, exec), List.of(disconnect),
                "UNKNOWN", List.of(), false);

        GateRegistryPort gateRegistry = key -> {
            if (key.commandKey().equals("add backup local")) {
                return List.of(new GateRow("test_add_backup_local", "check_point", "cp_gaia_gateway",
                        "expert_via_clish_c", "SSH_EXEC", "add backup local", ActionClass.CLASS_1_RECOVERY_WRITE,
                        SignOffState.SIGNED_OFF, 900, null, null, null, null, null, List.of(), "test fixture"));
            }
            return List.of();
        };

        return new CapabilityRegistryLoader(gateRegistry).load(spec);
    }
}
