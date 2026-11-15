package com.securityexpert.nexus.ui2.jobs.supportbundle;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The closed catalogue of support bundle artefact kinds (SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md section 5.2).
 * The browser and the CLI send kinds, never commands. {@code enabled} turns on as each vendor step lands: S2 (Check
 * Point light items), S3 (Palo Alto), S4 (Cisco ASA) and S5 (FortiGate).
 */
public enum SupportBundleKind {
    CPDATA_COLLECTOR_PRESENT("cpdata_collector_present", "check_point", 0, "low", true, true, false),
    CP_GAIA_EXTRAS("cp_gaia_extras", "check_point", 1, "low", true, true, false),
    CP_HCP_LATEST("cp_hcp_latest", "check_point", 2, "none", true, true, false),
    CP_HCP_RUN("cp_hcp_run", "check_point", 3, "medium", false, false, true),
    CP_CPINFO("cp_cpinfo", "check_point", 4, "high", false, false, true),
    PAN_TSF("pan_tsf", "palo_alto", 1, "unknown", true, true, false),
    ASA_CRASHINFO("asa_crashinfo", "cisco_asa", 1, "low", true, true, false),
    ASA_SHOW_TECH("asa_show_tech", "cisco_asa", 2, "unknown", true, true, false),
    FGT_CRASHLOG("fgt_crashlog", "fortinet", 1, "low", true, false, false),
    FGT_TAC_REPORT("fgt_tac_report", "fortinet", 2, "unknown", true, false, false);

    private final String id;
    private final String vendor;
    private final int order;
    private final String load;
    private final boolean defaultTicked;
    private final boolean enabled;
    private final boolean heavy;

    SupportBundleKind(String id, String vendor, int order, String load, boolean defaultTicked, boolean enabled,
            boolean heavy) {
        this.id = id; this.vendor = vendor; this.order = order; this.load = load; this.defaultTicked = defaultTicked;
        this.enabled = enabled; this.heavy = heavy;
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

    public static Optional<SupportBundleKind> fromId(String id) {
        return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst();
    }

    public static List<SupportBundleKind> forVendor(String vendor) {
        return Arrays.stream(values()).filter(k -> k.vendor.equals(vendor)).toList();
    }
}
