package com.securityexpert.nexus.ui2.worker.configuration;

import java.util.List;

/** One configuration-collect device-contact outcome -- mirrors {@code worker.inventory.InventoryResult}. */
public sealed interface ConfigurationResult {

    record Completed(List<ConfigurationRunData> runs) implements ConfigurationResult {
    }

    /** Refuses before any contact -- {@code connect}/the API key dance is never attempted. */
    record CredentialUnresolvable(String reason) implements ConfigurationResult {
    }

    /** Connect itself failed (host-key rejection, timeout, authentication, key generation). */
    record ConnectFailed(String reason) implements ConfigurationResult {
    }

    /** 13F ID-M3: the strict-refuse identity posture refused this contact after a mismatch. */
    record IdentityMismatchRefused(String reason) implements ConfigurationResult {
    }

    /** The artefact store write or the streaming XML parse itself failed. */
    record ArtefactStoreFailed(String reason) implements ConfigurationResult {
    }
}
