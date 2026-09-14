package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code device_interface} row (14C D-4) plus its {@code
 * device_interface_address} children, nested here rather than kept as a
 * sibling list so one interface's addresses travel with it through {@link
 * DeviceInventoryRepository#recordRun} and back out through {@link
 * DeviceInventoryRepository#findLatestRun}.
 *
 * @param kind   {@code physical|vlan|subinterface|loopback|bond|tunnel|other}
 *               -- the shared read contract's closed vocabulary; an unknown
 *               vendor token maps to {@code "other"}, never a guess
 * @param state  {@code up|down|unknown} -- never inferred from a substring
 *               match on the raw flag list (AGENTS.md UNKNOWN/fail-closed
 *               law; 14C §4's "UP must be read from the flag list... not by
 *               substring" correction)
 * @param vlanId the {@code device_interface.vlan_id} column (migration
 *               V17): Check Point's {@code vlan protocol 802.1Q id <n>}
 *               detail line, or Palo Alto's {@code tag} leaf; empty for the
 *               vast majority of interfaces that carry no VLAN id at all
 */
public record InventoryInterface(String interfaceId, String name, Optional<String> parent, String kind, String state,
        List<InventoryAddress> addresses, Optional<Integer> vlanId) {

    public static final String KIND_PHYSICAL = "physical";
    public static final String KIND_VLAN = "vlan";
    public static final String KIND_SUBINTERFACE = "subinterface";
    public static final String KIND_LOOPBACK = "loopback";
    public static final String KIND_BOND = "bond";
    public static final String KIND_TUNNEL = "tunnel";
    public static final String KIND_OTHER = "other";

    public static final String STATE_UP = "up";
    public static final String STATE_DOWN = "down";
    public static final String STATE_UNKNOWN = "unknown";

    public InventoryInterface {
        Objects.requireNonNull(interfaceId, "interfaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        vlanId = vlanId == null ? Optional.empty() : vlanId;
    }

    /** Pre-V17 shape, kept so every existing caller that never mentions a VLAN id keeps compiling unchanged. */
    public InventoryInterface(String interfaceId, String name, Optional<String> parent, String kind, String state,
            List<InventoryAddress> addresses) {
        this(interfaceId, name, parent, kind, state, addresses, Optional.empty());
    }
}
