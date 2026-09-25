package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;

/** One inventory-collect device-contact outcome, mirrors {@code worker.confirm.ConfirmResult}'s shape. */
public sealed interface InventoryResult {

    /** What this read observed about the device itself (PO 2026-09-25: refreshed on every read, never filled once). */
    record ObservedIdentity(Optional<String> hostname, Optional<String> model, Optional<String> softwareVersion) {
        public static final ObservedIdentity NONE = new ObservedIdentity(Optional.empty(), Optional.empty(), Optional.empty());

        public ObservedIdentity {
            hostname = hostname == null ? Optional.empty() : hostname.filter(v -> !v.isBlank());
            model = model == null ? Optional.empty() : model.filter(v -> !v.isBlank());
            softwareVersion = softwareVersion == null ? Optional.empty() : softwareVersion.filter(v -> !v.isBlank());
        }

        public boolean isEmpty() {
            return hostname.isEmpty() && model.isEmpty() && softwareVersion.isEmpty();
        }
    }

    /** {@code haFacts} (migration V17): every {@link InventoryHaFact} the contact produced, across every context. */
    record Completed(List<InventoryContext> contexts, List<InventoryHaFact> haFacts, Optional<String> virtualSystems,
            Optional<PlatformFactsRead> platformFacts, ObservedIdentity observedIdentity) implements InventoryResult {

        public Completed {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
            haFacts = haFacts == null ? List.of() : List.copyOf(haFacts);
            virtualSystems = virtualSystems == null ? Optional.empty() : virtualSystems;
            platformFacts = platformFacts == null ? Optional.empty() : platformFacts;
            observedIdentity = observedIdentity == null ? ObservedIdentity.NONE : observedIdentity;
        }

        public Completed(List<InventoryContext> contexts, List<InventoryHaFact> haFacts, Optional<String> virtualSystems,
                Optional<PlatformFactsRead> platformFacts) {
            this(contexts, haFacts, virtualSystems, platformFacts, ObservedIdentity.NONE);
        }

        public Completed(List<InventoryContext> contexts, List<InventoryHaFact> haFacts) {
            this(contexts, haFacts, Optional.empty(), Optional.empty());
        }

        public Completed(List<InventoryContext> contexts, List<InventoryHaFact> haFacts, Optional<String> virtualSystems) {
            this(contexts, haFacts, virtualSystems, Optional.empty());
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
