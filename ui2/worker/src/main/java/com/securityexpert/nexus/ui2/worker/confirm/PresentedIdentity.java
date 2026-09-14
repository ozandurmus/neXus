package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;

/**
 * The identity presented at connection (contract EC-5) -- Check Point: the
 * SSH host-key fingerprint ({@code primary}) and the observed hostname
 * ({@code secondary}); Palo Alto: the serial ({@code primary}) and the TLS
 * certificate identity ({@code secondary}). {@code primary} is also the
 * token a corroborating peer must name back (PF-2) -- the one vendor
 * stable identifier every read carries, per {@code DEVICE_FIRST_CONTACT_
 * COMMAND_GATE_ENTRIES.md} entries 2/4's own field list.
 */
public record PresentedIdentity(String primary, Optional<String> secondary) {

    public PresentedIdentity {
        java.util.Objects.requireNonNull(primary, "primary");
        java.util.Objects.requireNonNull(secondary, "secondary");
    }
}
