package com.securityexpert.nexus.ui2.service.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

@Service
public final class JobLogQueryService {

    private static final int RECENT_LIMIT = 50;

    public record JobEvent(
            @JsonProperty("job_id") String jobId,
            @JsonProperty("job_type") String jobType,
            @JsonProperty("target_device_id") String targetDeviceId,
            @JsonProperty("state") String state,
            @JsonProperty("terminal_reason") String terminalReason,
            @JsonProperty("submitted_at") Instant submittedAt) {
    }

    private final TransactionBoundary transactionBoundary;

    public JobLogQueryService(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    public List<JobEvent> recent() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select job_id, job_type, target_device_id, state, terminal_reason, submitted_at "
                        + "from jobs order by submitted_at desc limit {0}", RECENT_LIMIT)
                .map(row -> new JobEvent(
                        row.get("job_id", String.class),
                        row.get("job_type", String.class),
                        row.get("target_device_id", String.class),
                        row.get("state", String.class),
                        row.get("terminal_reason", String.class),
                        row.get("submitted_at", Timestamp.class) != null ? row.get("submitted_at", Timestamp.class).toInstant() : null)));
    }
}
