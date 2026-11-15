package com.securityexpert.nexus.ui2.persistence.artefact;

/**
 * Receives every write the artefact store refuses because of a byte quota, so the refusal can be audited (table
 * {@code artefact_store_refusal}, V227). Implementations must not throw: the store records on a best-effort basis and
 * never lets a recording failure change the refusal itself.
 */
public interface ArtefactStoreRefusalRecorder {

    /**
     * @param store the store (vendor segment) the write was aimed at; null for a global pre-check without a store
     * @param scope "store" when a per-store quota refused, "global" when the global quota did
     * @param deviceId the artefact's device id, or null for a pre-check
     * @param jobId the writing job or run id, or null for a pre-check
     */
    record Refusal(String store, String scope, long usedBytes, long quotaBytes, long attemptedBytes, String deviceId,
            String jobId) {
    }

    void record(Refusal refusal);
}
