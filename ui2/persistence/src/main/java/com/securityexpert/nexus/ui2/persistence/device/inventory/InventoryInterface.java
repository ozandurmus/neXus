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
 * @param macAddress the {@code device_interface.mac_address} column (V148): the link-layer address the
 *               device itself reports -- Check Point {@code ip -o link show} {@code link/ether}, Palo Alto
 *               {@code show interface all} {@code hw/entry/mac}; empty when not reported, never inferred
 * @param zone   the {@code device_interface.zone} column (V148): Palo Alto {@code ifnet/entry/zone};
 *               empty when the interface carries no zone or the vendor has no such field
 * @param routingContext the {@code device_interface.routing_context} column (V184): the Palo Alto (legacy engine)
 *               virtual router the interface forwards through; empty when not established (UNKNOWN)
 * @param evidenceGrade the {@code device_interface.evidence_grade} column (V184): {@link #GRADE_RUNTIME} or
 *               {@link #GRADE_CONFIG_DERIVED}; empty when not recorded (UNKNOWN)
 */
public record InventoryInterface(String interfaceId, String name, Optional<String> parent, String kind, String state,
        List<InventoryAddress> addresses, Optional<Integer> vlanId, Optional<String> macAddress, Optional<String> zone,
        Optional<String> routingContext, Optional<String> evidenceGrade) {

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

    /** V184: the interface came from a runtime read of the device. */
    public static final String GRADE_RUNTIME = "RUNTIME";
    /** V184: the interface was derived from stored configuration (PAN-OS 12, PO 2026-10-24), not a runtime read. */
    public static final String GRADE_CONFIG_DERIVED = "CONFIG_DERIVED";

    public InventoryInterface {
        Objects.requireNonNull(interfaceId, "interfaceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        vlanId = vlanId == null ? Optional.empty() : vlanId;
        macAddress = macAddress == null ? Optional.empty() : macAddress;
        zone = zone == null ? Optional.empty() : zone;
        routingContext = routingContext == null ? Optional.empty()
                : routingContext.filter(r -> !r.isBlank() && r.length() <= 128);
        evidenceGrade = evidenceGrade == null ? Optional.empty()
                : evidenceGrade.filter(g -> GRADE_RUNTIME.equals(g) || GRADE_CONFIG_DERIVED.equals(g));
    }

    /** Pre-V184 shape: routing context and evidence grade not recorded (UNKNOWN). */
    public InventoryInterface(String interfaceId, String name, Optional<String> parent, String kind, String state,
            List<InventoryAddress> addresses, Optional<Integer> vlanId, Optional<String> macAddress, Optional<String> zone) {
        this(interfaceId, name, parent, kind, state, addresses, vlanId, macAddress, zone, Optional.empty(), Optional.empty());
    }

    /** Pre-V148 shape: no link-layer address and no zone recorded. */
    public InventoryInterface(String interfaceId, String name, Optional<String> parent, String kind, String state,
            List<InventoryAddress> addresses, Optional<Integer> vlanId) {
        this(interfaceId, name, parent, kind, state, addresses, vlanId, Optional.empty(), Optional.empty());
    }

    /** Pre-V17 shape, kept so every existing caller that never mentions a VLAN id keeps compiling unchanged. */
    public InventoryInterface(String interfaceId, String name, Optional<String> parent, String kind, String state,
            List<InventoryAddress> addresses) {
        this(interfaceId, name, parent, kind, state, addresses, Optional.empty());
    }

    /** This interface with the link facts a separate read reported for it (an absent value keeps the current one). */
    public InventoryInterface withLinkFacts(Optional<String> mac, Optional<String> zoneName) {
        return new InventoryInterface(interfaceId, name, parent, kind, state, addresses, vlanId,
                mac.isPresent() ? mac : macAddress, zoneName.isPresent() ? zoneName : zone, routingContext, evidenceGrade);
    }

    /** V184: this interface with its routing context and evidence grade (an absent value keeps the current one). */
    public InventoryInterface withRouting(Optional<String> context, Optional<String> grade) {
        return new InventoryInterface(interfaceId, name, parent, kind, state, addresses, vlanId, macAddress, zone,
                context.isPresent() ? context : routingContext, grade.isPresent() ? grade : evidenceGrade);
    }
}
