package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The worker switch of the lifecycle reads, groups A and B (docs/design/LIFECYCLE_READS_GROUP_A_COMMAND_GATE_ENTRIES.md, PO
 * chat approval 2026-11-10: "Yalnız A grubu"; docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md, "d2, onayla d3,onayla
 * onayla" and "Evet, dördü de"). Measure-first: every read is off unless BOTH settings name it.
 *
 * <ul>
 * <li>{@value #KINDS_VARIABLE}: comma-separated read kinds from the closed set of {@link Kind}. Unset or blank: none.
 * An unknown token is ignored and logged once at WARNING.</li>
 * <li>{@value #DEVICES_VARIABLE}: comma-separated neXus device ids, or {@code *}. Unset or blank: none.</li>
 * <li>{@value #MC_MAX_VARIABLE}: how many managed devices' licenses one Management Center inventory run reads
 * (default 1); it bounds {@code mc_device_license} (group A) and {@code mc_show_licenses} (group B).</li>
 * <li>{@value #PAN_EXTENDED_DEVICES_VARIABLE}: comma-separated neXus device ids, or {@code *}: the Palo Alto devices outside
 * the first scope (a firewall on PAN-OS 12 or later, a firewall whose version is unknown, a Panorama) that get the license and
 * support reads (PO 2026-11-10, "Evet, aç"; group B record, amendment C). Unset or blank: none, as before.</li>
 * </ul>
 *
 * <p>A read is sent only when its kind is enabled and the inventory target's device id is listed (or {@code *}).
 * Settings are read once, when the switch is built; changing them needs a worker restart.</p>
 */
public final class LifecycleReadSwitch {

    public static final String KINDS_VARIABLE = "UI2_LIFECYCLE_READS";
    public static final String DEVICES_VARIABLE = "UI2_LIFECYCLE_READ_DEVICES";
    public static final String MC_MAX_VARIABLE = "UI2_LIFECYCLE_MC_LICENSE_MAX";
    public static final String PAN_EXTENDED_DEVICES_VARIABLE = "UI2_LIFECYCLE_PAN_EXTENDED_DEVICES";
    public static final int DEFAULT_MC_LICENSE_MAX = 1;

    private static final System.Logger LOG = System.getLogger(LifecycleReadSwitch.class.getName());

    /** The closed set of approved read kinds (one per record entry; the VSX and management-server forms share a kind). */
    public enum Kind {
        CP_CPLIC_PRINT("cp_cplic_print"),
        CP_CPLP_LIST("cp_cplp_list"),
        CP_SPARK_SHOW_LICENSE("cp_spark_show_license"),
        ASA_SHOW_LICENSE_ALL("asa_show_license_all"),
        MC_DEVICE_LICENSE("mc_device_license"),
        MC_SYSTEM_VERSION("mc_system_version"),
        INFOBLOX_LICENSE_GRIDWIDE("infoblox_license_gridwide"),
        // Group B (PO 2026-11-10, docs/design/LIFECYCLE_READS_GROUP_B_COMMAND_GATE_ENTRIES.md, V199).
        PAN_LICENSE_INFO("pan_license_info"),
        PAN_SUPPORT_INFO("pan_support_info"),
        FGT_UPDATE_INFO_CONTRACT("fgt_update_info_contract"),
        FGT_AUTOUPDATE_VERSIONS("fgt_autoupdate_versions"),
        FMG_FMUPDATE_DBCONTRACT("fmg_fmupdate_dbcontract"),
        MC_SHOW_LICENSES("mc_show_licenses"),
        INFOBLOX_UPGRADESTATUS_GRID("infoblox_upgradestatus_grid"),
        // PO chat approval 2026-11-10 ("Evet, VNODE dene"), V201: the member-level entries of the same object.
        INFOBLOX_UPGRADESTATUS_VNODE("infoblox_upgradestatus_vnode");

        private final String token;

        Kind(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        static Kind ofToken(String token) {
            for (Kind kind : values()) {
                if (kind.token.equals(token)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** Nothing is ever sent. */
    public static final LifecycleReadSwitch OFF = new LifecycleReadSwitch(EnumSet.noneOf(Kind.class), DeviceList.NONE,
            DEFAULT_MC_LICENSE_MAX, DeviceList.NONE);

    /** A list of device ids, or every device. */
    private record DeviceList(Set<String> ids, boolean all) {
        static final DeviceList NONE = new DeviceList(Set.of(), false);

        static DeviceList parse(String raw) {
            Set<String> ids = new LinkedHashSet<>();
            boolean all = false;
            if (raw != null && !raw.isBlank()) {
                for (String piece : raw.split(",")) {
                    String id = piece.strip();
                    if ("*".equals(id)) {
                        all = true;
                    } else if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
            return new DeviceList(Set.copyOf(ids), all);
        }

        boolean contains(String deviceId) {
            return all || (deviceId != null && ids.contains(deviceId));
        }
    }

    private final Set<Kind> kinds;
    private final DeviceList devices;
    private final int mcLicenseMax;
    private final DeviceList panExtended;

    private LifecycleReadSwitch(Set<Kind> kinds, DeviceList devices, int mcLicenseMax, DeviceList panExtended) {
        this.kinds = kinds;
        this.devices = devices;
        this.mcLicenseMax = mcLicenseMax;
        this.panExtended = panExtended;
    }

    /** From the worker's environment. */
    public static LifecycleReadSwitch fromEnvironment() {
        return from(System::getenv);
    }

    /** From any source of settings (a test drives it with a map's {@code get}). */
    public static LifecycleReadSwitch from(Function<String, String> source) {
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        String rawKinds = source.apply(KINDS_VARIABLE);
        if (rawKinds != null && !rawKinds.isBlank()) {
            Set<String> unknown = new LinkedHashSet<>();
            for (String piece : rawKinds.split(",")) {
                String token = piece.strip().toLowerCase(Locale.ROOT);
                if (token.isEmpty()) {
                    continue;
                }
                Kind kind = Kind.ofToken(token);
                if (kind == null) {
                    unknown.add(token.length() > 40 ? token.substring(0, 40) : token);
                } else {
                    kinds.add(kind);
                }
            }
            if (!unknown.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_MEASURE] {0} ignored unknown read kind(s): {1}", KINDS_VARIABLE, unknown);
            }
        }
        DeviceList devices = DeviceList.parse(source.apply(DEVICES_VARIABLE));
        int max = DEFAULT_MC_LICENSE_MAX;
        String rawMax = source.apply(MC_MAX_VARIABLE);
        if (rawMax != null && !rawMax.isBlank()) {
            try {
                int parsed = Integer.parseInt(rawMax.strip());
                if (parsed >= 0) {
                    max = parsed;
                } else {
                    LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_MEASURE] {0} is negative; the default {1} is used", MC_MAX_VARIABLE, max);
                }
            } catch (NumberFormatException e) {
                LOG.log(System.Logger.Level.WARNING, "[LIFECYCLE_MEASURE] {0} is not a number; the default {1} is used", MC_MAX_VARIABLE, max);
            }
        }
        return new LifecycleReadSwitch(kinds, devices, max, DeviceList.parse(source.apply(PAN_EXTENDED_DEVICES_VARIABLE)));
    }

    /** True when {@code kind} is enabled and {@code deviceId} is listed (or every device is). A null id matches only {@code *}. */
    public boolean allows(Kind kind, String deviceId) {
        if (!kinds.contains(kind)) {
            return false;
        }
        return devices.contains(deviceId);
    }

    /**
     * True when {@code deviceId} is a Palo Alto device outside the first scope (PAN-OS 12 or later, version unknown, or a
     * Panorama) that may get the license and support reads. It widens nothing by itself: the kind and the device must
     * still pass {@link #allows}.
     */
    public boolean allowsPanExtended(String deviceId) {
        return panExtended.contains(deviceId);
    }

    /** True when any kind is enabled for {@code deviceId}. */
    public boolean anyFor(String deviceId) {
        for (Kind kind : kinds) {
            if (allows(kind, deviceId)) {
                return true;
            }
        }
        return false;
    }

    /** How many managed devices' licenses one Management Center inventory run reads. */
    public int mcLicenseMax() {
        return mcLicenseMax;
    }
}
