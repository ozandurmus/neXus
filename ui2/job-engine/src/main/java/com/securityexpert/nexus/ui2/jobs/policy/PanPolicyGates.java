package com.securityexpert.nexus.ui2.jobs.policy;

import java.util.List;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/** Closed set of four PO-approved Panorama policy reads. Authentication reuses the existing PAN path. */
public final class PanPolicyGates {
    private PanPolicyGates() {}
    public static final String CAPABILITY = "pan_policy_collect";
    public static final List<String> COMMANDS = List.of(
        "type=config&action=show&xpath=/config/shared",
        "type=config&action=show&xpath=/config/devices/entry[@name='localhost.localdomain']/device-group/entry[@name='<DG>']",
        "type=op&cmd=<show><devicegroups/></show>",
        "type=op&cmd=<show><dg-hierarchy></dg-hierarchy></show>");
    public static void require(GateRegistryPort registry, int index) {
        var rows = registry.findByCanonicalKey(new CanonicalCommandKey("palo_alto", "panorama",
                "not_applicable", "PAN_XML_API", COMMANDS.get(index)));
        if (rows.size() != 1 || rows.get(0).signOffState() != SignOffState.SIGNED_OFF
                || rows.get(0).actionClass() != ActionClass.CLASS_0_READ || rows.get(0).timeoutS() != 60
                || !"none".equals(rows.get(0).retryRule()))
            throw new IllegalStateException("POLICY_GATE_UNAVAILABLE");
    }
    public static void requireAll(GateRegistryPort registry) {
        for (int i = 0; i < COMMANDS.size(); i++) require(registry, i);
    }
}
