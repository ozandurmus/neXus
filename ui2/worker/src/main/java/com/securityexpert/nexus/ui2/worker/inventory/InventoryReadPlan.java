package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The closed set of literal commands/calls {@code inventory_collect} may
 * ever send, transcribed from {@code PO_DECISION_RECORD_2026_09_14D}
 * CF-1..CF-4 exactly (successor to {@code PO_DECISION_RECORD_2026_09_14C}
 * §3, which this class no longer mirrors) -- the same closed-set-of-
 * literals pattern {@code worker.confirm.DeviceFirstContactCommandSet}
 * already establishes for the enrollment confirm. Named here for the gate
 * rows a later movement authors (14D §3); this movement issues none of
 * these against a real device (CAP_OFFLINE, FIXTURE_ONLY).
 *
 * <p><b>Check Point (CF-1..CF-4).</b> The landing shell is Expert; reads
 * run as typed, no {@code clish -c}, no {@code expert} step (CF-1). A
 * fresh session may land in a non-zero virtual-system context, so no read
 * ever relies on the session's own landing context (CF-2): the physical
 * read set ({@link #CHECK_POINT_PHYSICAL_READS}) is issued through {@link
 * #checkPointPhysicalCommand} -- {@code bash -lc 'vsenv 0 && <read>'} on a
 * VSX host, bare on a non-VSX gateway (where {@code vsenv} does not
 * exist) -- and the per-virtual-system reads are issued through {@link
 * #checkPointVsidSteps} as three {@code bash -lc 'vsenv <VSID> && ...'}
 * composites, the VSID validated as digits before substitution. The
 * sourced-profile form is never used (it does not define {@code vsenv},
 * a shell function of the interactive login profile, not a binary).</p>
 *
 * <p>Executor order (14D §3's gate-consequence note): {@code vsx stat -v}
 * runs first, bare, to decide VSX by text (CF-4) before any other read's
 * form is chosen; the remaining five physical reads then run wrapped or
 * bare per that decision; per-VSID reads run last, one VSID at a time,
 * VS0 never re-entered.</p>
 *
 * <p><b>Palo Alto (14E PF-1..PF-3, PM-1..PM-4).</b> The four
 * {@link #PALO_ALTO_BASE_STEPS} XML API calls run unscoped -- {@code show
 * interface all}/{@code show routing route} already return every vsys's
 * own entries in one response, so {@code worker.inventory.pan}'s parsers
 * assign context per entry rather than this movement issuing a second,
 * narrower call per vsys. PF-2 measured that {@code &vsys=<id>} narrows
 * only the interface list and leaves the route list byte-identical, so
 * that form is dropped from the closed set entirely -- there is no
 * per-vsys request literal left to declare, measured or otherwise.</p>
 */
public final class InventoryReadPlan {

    private static final Pattern DIGITS_ONLY = Pattern.compile("\\d+");

    private InventoryReadPlan() {
    }

    // -- Check Point (14D CF-1..CF-4) ---------------------------------------

    public static final String CP_IP_ADDR_SHOW_V4 = "ip -details -4 addr show";
    public static final String CP_IP_ADDR_SHOW_V6 = "ip -6 addr show";
    public static final String CP_IP_ROUTE_SHOW = "ip -4 route show table all";
    public static final String CP_CPHAPROB_STAT = "cphaprob stat";
    /** Product Owner direction (2026-09-21): {@code cphaprob -a if} is the correct command. An earlier trial of
     * this same literal, on four sampled devices, returned zero bytes -- identical to {@code cphaprob -a -m if}
     * -- so the diagnostic log at the call site stays on to gather more devices before drawing a conclusion.
     * Tracked as cp_cluster_vip_never_observed_in_fleet. */
    public static final String CP_CPHAPROB_CLUSTER_IF = "cphaprob -a if";
    public static final String CP_VSX_STAT = "vsx stat -v";

    /** CF-3's exact physical read order: interfaces (v4, then v6), routes, HA state, cluster VIPs, then VSX enumeration. */
    public static final List<String> CHECK_POINT_PHYSICAL_READS = List.of(
            CP_IP_ADDR_SHOW_V4, CP_IP_ADDR_SHOW_V6, CP_IP_ROUTE_SHOW, CP_CPHAPROB_STAT, CP_CPHAPROB_CLUSTER_IF,
            CP_VSX_STAT);

    private static final String CP_VSID_ADDR_AND_ROUTE_READS = "ip -4 addr show && ip -4 route show";

    /**
     * CF-2's login-shell wrapper: {@code bash -lc 'vsenv 0 && <read>'} on a
     * VSX host, the bare literal on a non-VSX gateway.
     */
    public static String checkPointPhysicalCommand(String read, boolean vsxHost) {
        return vsxHost ? vsenvWrap("0", read) : read;
    }

    /** {@link #CHECK_POINT_PHYSICAL_READS}, each wrapped through {@link #checkPointPhysicalCommand}. */
    public static List<String> checkPointPhysicalSteps(boolean vsxHost) {
        return CHECK_POINT_PHYSICAL_READS.stream().map(read -> checkPointPhysicalCommand(read, vsxHost)).toList();
    }

    /**
     * CF-2's exact per-VSID composite lines, {@code <VSID>} substituted
     * after digit validation -- one {@code bash -lc} call per line, in
     * CF-3's per-virtual-system order: addresses+routes, cluster VIPs, HA
     * state.
     */
    public static List<String> checkPointVsidSteps(String vsid) {
        String validated = requireDigitsOnly(vsid);
        return List.of(
                vsenvWrap(validated, CP_VSID_ADDR_AND_ROUTE_READS),
                vsenvWrap(validated, CP_CPHAPROB_CLUSTER_IF),
                vsenvWrap(validated, CP_CPHAPROB_STAT));
    }

    private static String vsenvWrap(String vsid, String reads) {
        return "bash -lc 'vsenv " + vsid + " && " + reads + "'";
    }

    private static String requireDigitsOnly(String vsid) {
        if (vsid == null || !DIGITS_ONLY.matcher(vsid).matches()) {
            throw new IllegalArgumentException("VSID must be digits only: " + vsid);
        }
        return vsid;
    }

    // -- Palo Alto -----------------------------------------------------------

    public static final String PAN_SHOW_SYSTEM_INFO = "<show><system><info/></system></show>";
    public static final String PAN_SHOW_HA_STATE = "<show><high-availability><state/></high-availability></show>";
    public static final String PAN_SHOW_INTERFACE_ALL = "<show><interface>all</interface></show>";
    public static final String PAN_SHOW_ROUTING_ROUTE = "<show><routing><route/></routing></show>";

    /** 14E PF-1's exact order: identity refresh, HA state, interfaces, routes. Each once, unscoped (PF-2). */
    public static final List<String> PALO_ALTO_BASE_STEPS =
            List.of(PAN_SHOW_SYSTEM_INFO, PAN_SHOW_HA_STATE, PAN_SHOW_INTERFACE_ALL, PAN_SHOW_ROUTING_ROUTE);
}
