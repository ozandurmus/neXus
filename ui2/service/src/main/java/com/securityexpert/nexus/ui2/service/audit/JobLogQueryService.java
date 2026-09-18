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
            @JsonProperty("submitted_at") Instant submittedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("duration_ms") Long durationMs) {

        public JobEvent(String jobId, String jobType, String targetDeviceId, String state, String terminalReason, Instant submittedAt) {
            this(jobId, jobType, targetDeviceId, state, terminalReason, submittedAt, null, null);
        }
    }

    private final TransactionBoundary transactionBoundary;

    public JobLogQueryService(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    public List<JobEvent> recent() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select job_id, job_type, target_device_id, state, terminal_reason, submitted_at, finished_at, "
                        + "case when finished_at is not null then extract(epoch from (finished_at - submitted_at)) * 1000 "
                        + "     else extract(epoch from (now() - submitted_at)) * 1000 end as duration_ms "
                        + "from jobs order by submitted_at desc limit {0}", RECENT_LIMIT)
                .map(row -> {
                    Number durNum = row.get("duration_ms", Number.class);
                    Long dur = durNum != null ? durNum.longValue() : null;
                    Timestamp subTs = row.get("submitted_at", Timestamp.class);
                    Timestamp finTs = row.get("finished_at", Timestamp.class);
                    return new JobEvent(
                            row.get("job_id", String.class),
                            row.get("job_type", String.class),
                            row.get("target_device_id", String.class),
                            row.get("state", String.class),
                            row.get("terminal_reason", String.class),
                            subTs != null ? subTs.toInstant() : null,
                            finTs != null ? finTs.toInstant() : null,
                            dur);
                }));
    }
}
