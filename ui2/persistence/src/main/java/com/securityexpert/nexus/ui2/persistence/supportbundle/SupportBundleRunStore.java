package com.securityexpert.nexus.ui2.persistence.supportbundle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.HcpCopySource;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;

/** The worker's side of {@link JooqSupportBundleStore}: what an executor reads and records while it runs a request. */
public interface SupportBundleRunStore {

    Optional<RequestRow> findByJob(String jobId);

    List<ItemRow> items(UUID requestId);

    /** QUEUED to RUNNING; false when the request is not queued (cancelled meanwhile). */
    boolean markRunning(UUID requestId);

    void markItemRunning(UUID itemId);

    void finishItem(UUID itemId, String state, String outcomeCode, String outcomeDetail, List<String> gateIds);

    void completeItem(UUID itemId, BackupArtefactManifestRecord manifest, String state, String outcomeCode,
            String archiveEntryName, String redactionRuleset, List<String> gateIds);

    void finishRequest(UUID requestId, String state, String outcome, String precheckJson, Instant expiresAt);

    Optional<HcpCopySource> newestStoredHcpReport(String deviceId, Instant notBefore);
}
