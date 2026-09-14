package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;

/**
 * One parsed address, before an {@code address_id} is assigned (14C §4: a
 * parser is a pure function from output text to data -- id generation is
 * the executor's job, not a parser's, so a parser stays deterministic and
 * fixture-comparable without touching {@link java.util.UUID}).
 */
public record ParsedAddress(String address, String family, String role) {

    public ParsedAddress {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(role, "role");
    }

    public InventoryAddress toInventoryAddress(String addressId) {
        return new InventoryAddress(addressId, address, family, role);
    }
}
