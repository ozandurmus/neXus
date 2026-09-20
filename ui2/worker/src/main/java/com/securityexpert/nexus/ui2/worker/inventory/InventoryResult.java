package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;

/** One inventory-collect device-contact outcome, mirrors {@code worker.confirm.ConfirmResult}'s shape. */
public sealed interface InventoryResult {

    /** {@code haFacts} (migration V17): every {@link InventoryHaFact} the contact produced, across every context. */
    record Completed(List<InventoryContext> contexts, List<InventoryHaFact> haFacts, Optional<String> virtualSystems) implements InventoryResult {

        public Completed {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
            haFacts = haFacts == null ? List.of() : List.copyOf(haFacts);
            virtualSystems = virtualSystems == null ? Optional.empty() : virtualSystems;
        }

        public Completed(List<InventoryContext> contexts, List<InventoryHaFact> haFacts) {
            this(contexts, haFacts, Optional.empty());
        }

        /** Pre-V17 shape, kept so a caller that never mentions HA facts keeps compiling unchanged. */
        public Completed(List<InventoryContext> contexts) {
            this(contexts, List.of(), Optional.empty());
        }
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
