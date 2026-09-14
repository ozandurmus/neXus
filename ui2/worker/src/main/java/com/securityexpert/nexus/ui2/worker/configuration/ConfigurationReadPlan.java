package com.securityexpert.nexus.ui2.worker.configuration;

import java.util.List;
import java.util.Map;

/**
 * The closed set of literal commands/calls {@code configuration_collect}
 * may ever send (14G CG-1/CG-4), transcribed from {@code
 * PO_DECISION_RECORD_2026_09_14G_CONFIGURATION_COLLECTION_MEASURED_FORMS.md}
 * exactly -- the same closed-set-of-literals pattern {@code
 * worker.inventory.InventoryReadPlan} already establishes.
 *
 * <p><b>Check Point (CG-1).</b> One read per host, no per-virtual-system
 * repetition (Gaia configuration is host-level; measured identical inside
 * {@code vsenv}): three identity-refresh reads, then the one Gaia
 * configuration read, all issued bare from the Expert shell via {@code
 * clish -c}.</p>
 *
 * <p><b>Palo Alto (CG-4).</b> Per firewall, in order: identity refresh,
 * local active configuration (small), {@code effective-running} (the
 * configuration of record, streamed -- 11.8 MB measured), {@code merged}
 * (the device-local overlay). {@link #PAN_ACTIVE_FORM_PARAMS} is a direct
 * {@code type=config&action=show&xpath=/config} form body, not an {@code
 * op}-type {@code cmd=} call like the other three.</p>
 */
public final class ConfigurationReadPlan {

    private ConfigurationReadPlan() {
    }

    // -- Check Point (CG-1) --------------------------------------------------

    public static final String CP_SHOW_HOSTNAME = "clish -c 'show hostname'";
    public static final String CP_SHOW_VERSION_ALL = "clish -c 'show version all'";
    public static final String CP_CPSTAT_OS_HW_INFO = "clish -c 'cpstat os -f hw_info'";
    public static final String CP_SHOW_CONFIGURATION = "clish -c 'show configuration'";

    /** CG-1's exact identity-refresh order, issued before the configuration read. */
    public static final List<String> CHECK_POINT_IDENTITY_READS =
            List.of(CP_SHOW_HOSTNAME, CP_SHOW_VERSION_ALL, CP_CPSTAT_OS_HW_INFO);

    // -- Palo Alto (CG-4) ------------------------------------------------------

    public static final String PAN_SHOW_SYSTEM_INFO = "<show><system><info/></system></show>";
    public static final String PAN_EFFECTIVE_RUNNING = "<show><config><effective-running/></config></show>";
    public static final String PAN_MERGED = "<show><config><merged/></config></show>";

    /** CG-4's active-configuration form: a direct {@code type=config} call, never an {@code op}-type {@code cmd=}. */
    public static final Map<String, String> PAN_ACTIVE_FORM_PARAMS =
            Map.of("type", "config", "action", "show", "xpath", "/config");

    /** CG-4's exact order after identity refresh: active, effective-running, merged. */
    public static final List<String> PALO_ALTO_XML_READS = List.of(PAN_EFFECTIVE_RUNNING, PAN_MERGED);
}
