package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;

/** One inventory-collect device-contact outcome, mirrors {@code worker.confirm.ConfirmResult}'s shape. */
public sealed interface InventoryResult {

    record Completed(List<InventoryContext> contexts) implements InventoryResult {
    }

    /** Refuses before any contact -- {@code connect}/the API key dance is never attempted. */
    record CredentialUnresolvable(String reason) implements InventoryResult {
    }

    /** Connect itself failed (host-key rejection, timeout, authentication, key generation). */
    record ConnectFailed(String reason) implements InventoryResult {
    }

    /** 13F ID-M3: the strict-refuse identity posture refused this contact after a mismatch. */
    record IdentityMismatchRefused(String reason) implements InventoryResult {
    }
}
