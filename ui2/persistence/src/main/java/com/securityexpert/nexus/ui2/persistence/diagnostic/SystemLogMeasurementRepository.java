package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase A system-log measurement summaries (V151, DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4): one row per
 * measured source per job. The summary is counts, field names, formats and enum-like values only -- never a log
 * entry.
 */
public interface SystemLogMeasurementRepository {

    record Measurement(String measurementId, String deviceId, String vendor, String source, Instant measuredAt,
            String summaryJson, String jobId) {
    }

    /** Fenced to the job's current lease: a stale worker's write records nothing and returns {@code false}. */
    boolean record(String jobId, long leaseEpoch, String deviceId, String vendor, String source, String summaryJson);

    /** Newest first, at most {@code limit} rows; one device when {@code deviceId} is present. */
    List<Measurement> list(Optional<String> deviceId, int limit);
}
