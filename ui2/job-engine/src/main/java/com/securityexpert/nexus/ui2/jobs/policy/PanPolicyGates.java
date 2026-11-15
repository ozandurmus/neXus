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
    /**
     * The predefined App-ID catalogue read (PO approval 2026-11-14, verbatim "Onaylıyorum", option A): one call per policy
     * collection on the same Panorama session, timeout 120 s, no retry, 64 MB cap. Optional: without its signed-off gate
     * row the collection runs exactly as before and applications keep unknown default ports.
     */
    public static final String PREDEFINED_APPLICATIONS = "type=config&action=get&xpath=/config/predefined/application";
    public static boolean predefinedApplicationsAvailable(GateRegistryPort registry) {
        var rows = registry.findByCanonicalKey(new CanonicalCommandKey("palo_alto", "panorama",
                "not_applicable", "PAN_XML_API", PREDEFINED_APPLICATIONS));
        return rows.size() == 1 && rows.get(0).signOffState() == SignOffState.SIGNED_OFF
                && rows.get(0).actionClass() == ActionClass.CLASS_0_READ && rows.get(0).timeoutS() == 120
                && "none".equals(rows.get(0).retryRule());
    }
    public static void requireAll(GateRegistryPort registry) {
        for (int i = 0; i < COMMANDS.size(); i++) require(registry, i);
    }
}
