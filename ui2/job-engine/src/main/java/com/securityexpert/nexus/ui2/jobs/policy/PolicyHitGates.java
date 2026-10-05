package com.securityexpert.nexus.ui2.jobs.policy;

import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Optional hit reads. DRAFTED rows never authorize transport, including authentication. */
public final class PolicyHitGates {
    private PolicyHitGates() {}
    public static final String CP_COMMAND = CpPolicyGates.COMMANDS.get(1) + " show-hits true";
    public static final String PAN_COMMAND = "type=op&cmd=<show><rule-hit-count><vsys><vsys-name><entry name='<VSYS>'><rule-base><entry name='security'><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>";
    public static boolean enabled(GateRegistryPort registry, boolean cp) {
        var rows = registry.findByCanonicalKey(new CanonicalCommandKey(cp ? "check_point" : "palo_alto",
                cp ? "cp_multi_domain_server" : "pan_firewall", cp ? "expert" : "not_applicable",
                cp ? "SSH_EXEC" : "PAN_XML_API", cp ? CP_COMMAND : PAN_COMMAND));
        return rows.size() == 1 && rows.get(0).signOffState() == SignOffState.SIGNED_OFF
                && rows.get(0).actionClass() == ActionClass.CLASS_0_READ && rows.get(0).timeoutS() == (cp ? 300 : 60)
                && "none".equals(rows.get(0).retryRule());
    }
    public static void require(GateRegistryPort registry, boolean cp) {
        if (!enabled(registry, cp)) throw new IllegalStateException("POLICY_GATE_UNAVAILABLE");
    }
}
