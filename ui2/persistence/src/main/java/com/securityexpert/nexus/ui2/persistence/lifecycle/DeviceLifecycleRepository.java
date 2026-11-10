package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * License, contract and Live Patch facts read from devices (V200, docs/design/LIFECYCLE_LICENSE_STORAGE_CONTRACT.md).
 * Written by the worker's inventory from the approved lifecycle reads; read by the Lifecycle projection. Holds parsed
 * facts only: never a key, signature, authcode, serial, bound address, contract or account identifier, or raw output.
 */
public interface DeviceLifecycleRepository {

    /** The outcome of the latest attempt of one read kind on one device. */
    record Read(String deviceId, String readKind, String outcome, String reason, int itemCount, Instant observedAt) { }

    /**
     * One license, subscription, contract or Smart Licensing fact. {@code category}: LICENSE, SUBSCRIPTION,
     * SUPPORT_CONTRACT, SMART_LICENSING. {@code state}: VALID, EXPIRED, PERMANENT, NOT_LICENSED, OUT_OF_COMPLIANCE,
     * UNREGISTERED, EVAL_EXPIRED, NOT_APPLICABLE, UNKNOWN. {@code evidenceGrade}: DIRECT_DEVICE or MANAGER_REPORTED.
     * {@code expiresOn}, {@code detail} and {@code memberRef} may be null.
     */
    record LicenseItem(String readKind, String category, String label, String state, LocalDate expiresOn, String detail,
            String memberRef, String evidenceGrade, Instant observedAt) { }

    /** One row of a Check Point {@code cplp list} answer. {@code refs}: sk and CVE identifiers of the COMMENT column. */
    record LivePatchItem(String patchId, String status, String mode, Integer pidsRunning, Integer pidsTotal,
            LocalDateTime installedAt, List<String> refs, Instant observedAt) { }

    /**
     * Records the outcome of one read. With outcome COLLECTED the device's items of that kind are replaced by
     * {@code items} in the same transaction; with any other outcome the stored items are left as they are (a failed
     * read does not erase the last good answer; the read row says how old it is and why the latest attempt failed).
     */
    void recordLicenseRead(String deviceId, String readKind, String outcome, String reason, List<LicenseItem> items);

    /** The same for {@code cplp list}: the device's Live Patch rows are replaced only when the outcome is COLLECTED. */
    void recordLivePatchRead(String deviceId, String readKind, String outcome, String reason, List<LivePatchItem> items);

    Map<String, List<Read>> readsByDevice();

    Map<String, List<LicenseItem>> licenseItemsByDevice();

    Map<String, List<LivePatchItem>> livePatchByDevice();
}
