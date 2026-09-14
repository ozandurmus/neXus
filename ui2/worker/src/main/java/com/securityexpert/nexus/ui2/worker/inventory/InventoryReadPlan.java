package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;

/**
 * The closed set of literal commands/calls {@code inventory_collect} may
 * ever send, transcribed from {@code PO_DECISION_RECORD_2026_09_14C}
 * §3 exactly -- the same closed-set-of-literals pattern {@code
 * worker.confirm.DeviceFirstContactCommandSet} already establishes for the
 * enrollment confirm. Named here for the gate rows a later movement
 * authors (14C §5); this movement issues none of these against a real
 * device (CAP_OFFLINE, FIXTURE_ONLY).
 *
 * <p><b>Check Point.</b> One interactive session per device, paced: the
 * six {@link #CHECK_POINT_BASE_STEPS} run first, in order, against the
 * physical context; {@link #CHECK_POINT_VSX_STEP_TEMPLATES} then run once
 * per VSID the {@code vsx stat -v} response named (VS0/the physical
 * context is never re-entered, 14C §3).</p>
 *
 * <p><b>Palo Alto.</b> The four {@link #PALO_ALTO_BASE_STEPS} XML API
 * calls run unscoped -- {@code show interface all}/{@code show routing
 * route} already return every vsys's own entries in one response (the
 * shape {@code tests/fixtures/panorama/*.xml} shows), so {@code
 * worker.inventory.pan}'s parsers assign context per entry rather than
 * this movement issuing a second, narrower call per vsys. The per-vsys
 * {@code &vsys=<id>} form is still a legal, closed-set literal request
 * shape (14C §3: "per vsys, &vsys=&lt;id&gt; on the two reads above"),
 * available via {@link #paloAltoVsysFormParam} for a later measurement-
 * driven increment; this movement's own executor never sends it.</p>
 */
public final class InventoryReadPlan {

    private InventoryReadPlan() {
    }

    // -- Check Point -------------------------------------------------------

    public static final String CP_IP_ADDR_SHOW_V4 = "ip -details -4 addr show";
    public static final String CP_IP_ADDR_SHOW_V6 = "ip -6 addr show";
    public static final String CP_IP_ROUTE_SHOW = "ip -4 route show table all";
    public static final String CP_CPHAPROB_CLUSTER_IF = "cphaprob -a -m if";
    public static final String CP_CPHAPROB_STAT = "cphaprob stat";
    public static final String CP_VSX_STAT = "vsx stat -v";

    /** Section 3's exact order: interfaces (v4, then v6), routes, cluster VIPs, HA state, then VSX enumeration. */
    public static final List<String> CHECK_POINT_BASE_STEPS = List.of(
            CP_IP_ADDR_SHOW_V4, CP_IP_ADDR_SHOW_V6, CP_IP_ROUTE_SHOW, CP_CPHAPROB_CLUSTER_IF, CP_CPHAPROB_STAT,
            CP_VSX_STAT);

    private static final String CP_VSID_ADDR_AND_ROUTE_TEMPLATE = "vsenv %s; ip -4 addr show; ip -4 route show";
    private static final String CP_VSID_HA_STAT_TEMPLATE = "vsenv %s; cphaprob stat";

    /** Section 3's exact per-VSID composite lines, {@code <VSID>} substituted -- one entry per vsenv channel. */
    public static final List<String> CHECK_POINT_VSX_STEP_TEMPLATES =
            List.of(CP_VSID_ADDR_AND_ROUTE_TEMPLATE, CP_VSID_HA_STAT_TEMPLATE);

    public static List<String> checkPointVsidSteps(String vsid) {
        return List.of(String.format(CP_VSID_ADDR_AND_ROUTE_TEMPLATE, vsid), String.format(CP_VSID_HA_STAT_TEMPLATE, vsid));
    }

    // -- Palo Alto -----------------------------------------------------------

    public static final String PAN_SHOW_SYSTEM_INFO = "<show><system><info/></system></show>";
    public static final String PAN_SHOW_HA_STATE = "<show><high-availability><state/></high-availability></show>";
    public static final String PAN_SHOW_INTERFACE_ALL = "<show><interface>all</interface></show>";
    public static final String PAN_SHOW_ROUTING_ROUTE = "<show><routing><route/></routing></show>";

    /** Section 3's exact order: identity refresh, HA state, interfaces, routes. */
    public static final List<String> PALO_ALTO_BASE_STEPS =
            List.of(PAN_SHOW_SYSTEM_INFO, PAN_SHOW_HA_STATE, PAN_SHOW_INTERFACE_ALL, PAN_SHOW_ROUTING_ROUTE);

    public static final String PALO_ALTO_VSYS_FORM_PARAM_KEY = "vsys";

    /** The {@code &vsys=<id>} form param this movement declares but never sends (see class javadoc). */
    public static java.util.Map<String, String> paloAltoVsysFormParam(String vsysId) {
        return java.util.Map.of(PALO_ALTO_VSYS_FORM_PARAM_KEY, vsysId);
    }
}
