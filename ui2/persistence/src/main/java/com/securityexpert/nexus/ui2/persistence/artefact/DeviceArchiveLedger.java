package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.List;

/**
 * V45: the archive names a Check Point backup run created on the device and
 * has not yet deleted. The next run deletes exactly these -- never a pattern.
 */
public interface DeviceArchiveLedger {

    /** Idempotent: a name already on record stays as it is. */
    void record(String deviceId, String archiveName);

    void markDeleted(String deviceId, String archiveName);

    /** Names recorded for the device whose delete never succeeded. */
    List<String> pendingFor(String deviceId);
}
