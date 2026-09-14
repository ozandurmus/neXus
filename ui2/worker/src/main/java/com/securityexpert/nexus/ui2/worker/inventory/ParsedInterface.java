package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;

/**
 * One parsed interface, before an {@code interface_id}/{@code address_id}
 * is assigned. See {@link ParsedAddress}. {@code vlanId} (14D PR-2: read
 * from a {@code vlan protocol 802.1Q id <n>} detail line when present) has
 * no {@code device_interface} column to persist into -- it stays a
 * worker-only parse result, dropped by {@link #toInventoryInterface()},
 * exactly as {@code CheckPointHaStateParser}'s per-VSID HA role stays
 * unpersisted for the same reason.
 */
public record ParsedInterface(String name, Optional<String> parent, String kind, String state,
        List<ParsedAddress> addresses, Optional<Integer> vlanId) {

    public ParsedInterface {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        vlanId = vlanId == null ? Optional.empty() : vlanId;
    }

    public InventoryInterface toInventoryInterface() {
        List<InventoryAddress> assigned = addresses.stream()
                .map(a -> a.toInventoryAddress(UUID.randomUUID().toString()))
                .toList();
        return new InventoryInterface(UUID.randomUUID().toString(), name, parent, kind, state, assigned);
    }
}
