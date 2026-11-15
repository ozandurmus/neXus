package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The closed catalogue of support bundle artefact kinds (SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 5.2).
 * The browser and the CLI send kinds, never commands. {@code enabled} turns on as each vendor step lands: S2 (Check
 * Point light items), S3 (Palo Alto), S4 (Cisco ASA), S5 (FortiGate) and S6 (Check Point heavy items, with the optional
 * cpdata_collector run).
 */
public enum SupportBundleKind {
    CPDATA_COLLECTOR_PRESENT("cpdata_collector_present", "check_point", 0, "low", true, true, false, false),
    CP_GAIA_EXTRAS("cp_gaia_extras", "check_point", 1, "low", true, true, false, false),
    CP_HCP_LATEST("cp_hcp_latest", "check_point", 2, "none", true, true, false, false),
    CP_HCP_RUN("cp_hcp_run", "check_point", 3, "medium", false, true, true, true),
    CP_CPINFO("cp_cpinfo", "check_point", 4, "high", false, true, true, true),
    /** Optional (PO 2026-11-15): runs only when the presence read found the tool; never ticked by default. */
    CP_CPDATA_COLLECTOR("cpdata_collector_run", "check_point", 5, "unknown", false, true, true, true),
    PAN_TSF("pan_tsf", "palo_alto", 1, "unknown", true, true, false, true),
    ASA_CRASHINFO("asa_crashinfo", "cisco_asa", 1, "low", true, true, false, false),
    ASA_SHOW_TECH("asa_show_tech", "cisco_asa", 2, "unknown", true, true, false, false),
    FGT_CRASHLOG("fgt_crashlog", "fortinet", 1, "low", true, true, false, false),
    FGT_TAC_REPORT("fgt_tac_report", "fortinet", 2, "unknown", true, true, false, false);

    private final String id;
    private final String vendor;
    private final int order;
    private final String load;
    private final boolean defaultTicked;
    private final boolean enabled;
    private final boolean heavy;
    private final boolean recoveryWrite;

    SupportBundleKind(String id, String vendor, int order, String load, boolean defaultTicked, boolean enabled,
            boolean heavy, boolean recoveryWrite) {
        this.id = id; this.vendor = vendor; this.order = order; this.load = load; this.defaultTicked = defaultTicked;
        this.enabled = enabled; this.heavy = heavy; this.recoveryWrite = recoveryWrite;
    }

    public String id() { return id; }
    public String vendor() { return vendor; }
    /** Execution order within a run, cheapest first. */
    public int order() { return order; }
    /** Expected device load: none, low, medium, high or unknown (design 5.2). */
    public String load() { return load; }
    /** PO 2026-11-15 (Q7): light items ticked, cpinfo and HCP run unticked. */
    public boolean defaultTicked() { return defaultTicked && !heavy; }
    /** False until the vendor step that implements the kind has landed; a request for it is refused. */
    public boolean enabled() { return enabled; }
    public boolean heavy() { return heavy; }
    /**
     * True when the kind sends a recovery-write gate (BW-2: the device writes a transient artefact and the run removes
     * it): the Palo Alto tech support file (P3) and the Check Point work directory items (C8-C20). A request holding such a
     * kind is admitted as class 1, as the MDS export is (V61).
     */
    public boolean recoveryWrite() { return recoveryWrite; }
    /** The kinds a Check Point management server or Multi-Domain Server can be asked for (step S6; PO Q5): the heavy items and the presence read. */
    public boolean onCheckPointManagement() {
        return this == CPDATA_COLLECTOR_PRESENT || this == CP_HCP_RUN || this == CP_CPINFO || this == CP_CPDATA_COLLECTOR;
    }
    /** True for the kinds that run once per virtual system on a VSX gateway (C10 and C14). */
    public boolean perVirtualSystem() { return this == CP_HCP_RUN || this == CP_CPINFO; }

    public static Optional<SupportBundleKind> fromId(String id) {
        return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst();
    }

    public static List<SupportBundleKind> forVendor(String vendor) {
        return Arrays.stream(values()).filter(k -> k.vendor.equals(vendor)).toList();
    }
}
