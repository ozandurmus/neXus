package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

/**
 * The two conditions of the Management Center managed-device health read ({@code GET /api/devices/status}, candidate C1 of
 * docs/design/MC_DEVICE_HEALTH_READ_PROPOSAL_2026_11_24.md): the worker switch {@value #SWITCH_VARIABLE} (default off) AND an
 * approved gate row for that exact request. Until the Product Owner approves the exact literal and the row exists, the request is
 * never sent: the managed devices' health is recorded UNKNOWN with the reason {@code GATE_NOT_APPROVED}, the inventory run is
 * unaffected and nothing is ever recorded as OK.
 *
 * <p>The gate is looked up at the moment of every run (through {@link GateResolver}, never cached), like every other gated read;
 * a missing row, a row that is not signed off, an ambiguous lookup, a row that is not a read, or a registry that cannot answer are
 * all "not approved". The switch is read once, when the executor is built; changing it needs a worker restart.</p>
 */
public final class McDeviceHealthRead {

    public static final String SWITCH_VARIABLE = "UI2_MC_DEVICE_HEALTH_READ";

    /** The exact gate key of the request: vendor, scope, shell, transport and the literal method and path. */
    public static final CanonicalCommandKey GATE_KEY = new CanonicalCommandKey("bluecoat", "bluecoat_management_center",
            "not_applicable", "HTTPS", "GET " + HttpsVendorPlan.MC_DEVICES_STATUS);

    /** Never read. */
    public static final McDeviceHealthRead OFF = new McDeviceHealthRead(false, null);

    private static final System.Logger LOG = System.getLogger(McDeviceHealthRead.class.getName());

    private final boolean enabled;
    private final GateRegistryPort gates;

    private McDeviceHealthRead(boolean enabled, GateRegistryPort gates) {
        this.enabled = enabled;
        this.gates = gates;
    }

    /** From the worker's environment; {@code gates} is the live gate registry. */
    public static McDeviceHealthRead fromEnvironment(GateRegistryPort gates) {
        return from(OverwatchEnv::get, gates);
    }

    /** From any source of settings (a test drives it with a map's {@code get}). */
    public static McDeviceHealthRead from(Function<String, String> source, GateRegistryPort gates) {
        String raw = source.apply(SWITCH_VARIABLE);
        boolean on = raw != null && switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "on", "yes" -> true;
            default -> false;
        };
        return new McDeviceHealthRead(on, gates);
    }

    /** True when the switch is on. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * True when the registry answers exactly one SIGNED_OFF read row for the request. Re-queried on every call; fails closed on
     * any doubt.
     */
    public boolean gateApproved() {
        if (gates == null) {
            return false;
        }
        try {
            GateResolution resolution = GateResolver.resolve(GATE_KEY, Optional.of(ActionClass.CLASS_0_READ), gates);
            return resolution instanceof GateResolution.Known known && known.actionClass() == ActionClass.CLASS_0_READ;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[MC_DEVICE_HEALTH] gate lookup not usable: {0}", e.getClass().getSimpleName());
            return false;
        }
    }
}
