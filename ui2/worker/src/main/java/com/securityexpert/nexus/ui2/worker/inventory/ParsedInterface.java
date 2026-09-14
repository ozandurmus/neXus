package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;

/** One parsed interface, before an {@code interface_id}/{@code address_id} is assigned. See {@link ParsedAddress}. */
public record ParsedInterface(String name, Optional<String> parent, String kind, String state,
        List<ParsedAddress> addresses) {

    public ParsedInterface {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }

    public InventoryInterface toInventoryInterface() {
        List<InventoryAddress> assigned = addresses.stream()
                .map(a -> a.toInventoryAddress(UUID.randomUUID().toString()))
                .toList();
        return new InventoryInterface(UUID.randomUUID().toString(), name, parent, kind, state, assigned);
    }
}
