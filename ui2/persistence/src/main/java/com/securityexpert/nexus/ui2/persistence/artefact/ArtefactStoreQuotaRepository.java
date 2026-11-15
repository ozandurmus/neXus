package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Map;

/**
 * V225: the optional per-store byte quotas of the artefact store, keyed by store name (the first directory level under
 * the artefact root). A store with no entry has no per-store quota; the global quota applies to every store regardless.
 */
public interface ArtefactStoreQuotaRepository {

    /** Every configured per-store quota, store name to bytes. */
    Map<String, Long> findAll();

    /** Audited insert-or-update of one store's quota; {@code quotaBytes} must be positive. */
    void set(String storeName, long quotaBytes, String actorFingerprint, String actionId);

    /** Audited removal of one store's quota (back to unlimited); false when none was set. */
    boolean clear(String storeName, String actorFingerprint, String actionId);
}
