package com.securityexpert.nexus.ui2.jobs.policy;

import java.util.List;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Exact PO-approved management templates; checked at admission and before each policy read. */
public final class CpPolicyGates {
    private CpPolicyGates() {}
    public static final String CAPABILITY = "cp_policy_collect";
    public static final List<String> COMMANDS = List.of(
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 20 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true");
    public static Capability capability(GateRegistryPort registry) {
        var steps = new java.util.ArrayList<CapabilityStep>();
        steps.add(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
        for (String command : COMMANDS) steps.add(new CapabilityStep(StepKind.EXEC, "expert", command, false,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, "check_point", "cp_multi_domain_server",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-10-01", List.of(), false));
    }
    public static void require(GateRegistryPort registry, int index) {
        var rows = registry.findByCanonicalKey(new CanonicalCommandKey("check_point", "cp_multi_domain_server",
                "expert", "SSH_EXEC", COMMANDS.get(index)));
        if (rows.size() != 1 || rows.get(0).signOffState() != SignOffState.SIGNED_OFF
                || rows.get(0).actionClass() != ActionClass.CLASS_0_READ || rows.get(0).timeoutS() != (index == 0 || index == 1 ? 300 : 60)
                || !rows.get(0).retryRule().equals(index == 0 || index == 1 ? "once on timeout with limit 50" : "none"))
            throw new IllegalStateException("POLICY_GATE_UNAVAILABLE");
    }
    public static void requireAll(GateRegistryPort registry) {
        for (int i = 0; i < COMMANDS.size(); i++) require(registry, i);
    }
}
