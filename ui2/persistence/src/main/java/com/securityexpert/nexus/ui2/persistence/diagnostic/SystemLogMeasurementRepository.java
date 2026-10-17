package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase A system-log measurement summaries (V151, V159; DEVICE_SYSTEM_LOG_PROGRAMME_CONTRACT.md §4): one row per
 * measured source per job. The summary is counts, sizes, times, field names, formats, enum-like values and name
 * classes only -- never a log entry, a path, a file name, an address or a host name. Palo Alto sources are
 * {@code pan_system} and {@code pan_config}; Check Point sources are the {@code cp_syslog_measure_*} gate ids.
 */
public interface SystemLogMeasurementRepository {

    record Measurement(String measurementId, String deviceId, String vendor, String source, Instant measuredAt,
            String summaryJson, String jobId) {
    }

    /** Fenced to the job's current lease: a stale worker's write records nothing and returns {@code false}. */
    boolean record(String jobId, long leaseEpoch, String deviceId, String vendor, String source, String summaryJson);

    /** Newest first, at most {@code limit} rows; one device, vendor and/or source when present. */
    List<Measurement> list(Optional<String> deviceId, Optional<String> vendor, Optional<String> source, int limit);

    default List<Measurement> list(Optional<String> deviceId, int limit) {
        return list(deviceId, Optional.empty(), Optional.empty(), limit);
    }
}
