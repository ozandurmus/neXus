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
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 500 offset '<N>' details-level standard use-object-dictionary true",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-access-rulebase name '<LAYER>' limit 100 offset '<N>' details-level full use-object-dictionary true show-hits true",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-times limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-time-groups limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-gateways-and-servers limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-hosts limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-networks limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-groups limit 50 offset '<N>' details-level full dereference-group-members false",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-groups-with-exclusion limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-address-ranges limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-tcp limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-udp limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-icmp limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-icmp6 limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-other limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-sctp limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-dce-rpc limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-services-rpc limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-service-groups limit 50 offset '<N>' details-level full dereference-group-members false",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-access-roles limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-dynamic-objects limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-dns-domains limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-security-zones limit 50 offset '<N>' details-level full",
        "mgmt_cli -r true -d '<DOMAIN>' -f json show-unused-objects limit 50 offset '<N>' details-level full");
    public static final int PACKAGES_50 = 4;
    public static final int OBJECT_BASE = 5;
    public static final List<String> OBJECT_TYPES = List.of("times", "time-groups", "gateways-and-servers", "hosts", "networks", "groups", "groups-with-exclusion", "address-ranges", "services-tcp", "services-udp", "services-icmp", "services-icmp6", "services-other", "services-sctp", "services-dce-rpc", "services-rpc", "service-groups", "access-roles", "dynamic-objects", "dns-domains", "security-zones", "unused-objects");
    public static Capability capability(GateRegistryPort registry) {
        var steps = new java.util.ArrayList<CapabilityStep>();
        steps.add(new CapabilityStep(StepKind.CONNECT, "not_applicable", null, false, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
        for (String command : COMMANDS) {
            if (command.equals(COMMANDS.get(3))) continue; // Optional hit gate is checked immediately before transport.
            steps.add(new CapabilityStep(StepKind.EXEC, "expert", command, false,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
        }
        var disconnect = new CapabilityStep(StepKind.DISCONNECT, "not_applicable", null, false,
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        return new CapabilityRegistryLoader(registry).load(new CapabilitySpec(CAPABILITY, "check_point", "cp_multi_domain_server",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, steps, List.of(disconnect), "2026-10-01", List.of(), false));
    }
    public static void require(GateRegistryPort registry, int index) {
        var rows = registry.findByCanonicalKey(new CanonicalCommandKey("check_point", "cp_multi_domain_server",
                "expert", "SSH_EXEC", COMMANDS.get(index)));
        if (rows.size() != 1 || rows.get(0).signOffState() != SignOffState.SIGNED_OFF
                || rows.get(0).actionClass() != ActionClass.CLASS_0_READ || rows.get(0).timeoutS() != (index == 2 ? 60 : 300)
                || !rows.get(0).retryRule().equals(index == 0 || index == 1 ? "once on timeout with limit 50" : "none"))
            throw new IllegalStateException("POLICY_GATE_UNAVAILABLE");
    }
    public static void requireAll(GateRegistryPort registry) {
        for (int i = 0; i < COMMANDS.size(); i++) if (i != 3) require(registry, i);
    }
}
