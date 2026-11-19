package com.securityexpert.nexus.ui2.persistence.staticroute;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for Phase R0 static-route previews (V252 {@code static_route_preview}).
 * Zero writes exist in Phase R0; {@code mutationPossible} is unconditionally {@code false}.
 */
public interface StaticRoutePreviewRepository {

    String STATE_QUEUED = "QUEUED";
    String STATE_RUNNING = "RUNNING";
    String STATE_DONE = "DONE";
    String STATE_STOPPED = "STOPPED";

    String VERDICT_PENDING = "PENDING";
    String VERDICT_PASS = "PASS";
    String VERDICT_FAIL = "FAIL";
    String VERDICT_UNKNOWN = "UNKNOWN";

    record CreateSpec(
            String previewId,
            String idempotencyKey,
            String requestPayloadSha256,
            String requestedBy,
            String unitId,
            String targetDeviceId,
            String vendor,
            String routingContext,
            String operation,
            String destinationCidr,
            String nextHopIp,
            String interfaceName,
            Integer distance,
            Integer metric,
            Integer priority,
            String routeName,
            String deviceComment,
            String representativeHost,
            String changeTicketRef,
            String reason,
            String preChecksJson,
            String commandPlanJson,
            String inversePlanJson) {
    }

    record PreviewRow(
            String previewId,
            String idempotencyKey,
            String requestPayloadSha256,
            String requestedBy,
            Instant requestedAt,
            String unitId,
            String targetDeviceId,
            String vendor,
            String routingContext,
            String operation,
            String destinationCidr,
            String nextHopIp,
            Optional<String> interfaceName,
            Optional<Integer> distance,
            Optional<Integer> metric,
            Optional<Integer> priority,
            Optional<String> routeName,
            Optional<String> deviceComment,
            String representativeHost,
            Optional<String> changeTicketRef,
            Optional<String> reason,
            Optional<String> jobId,
            Optional<String> jobState,
            String state,
            String verdict,
            Optional<String> stopCode,
            boolean mutationPossible,
            Optional<String> preRouteTableSha256,
            String preChecksJson,
            Optional<String> beforePathJson,
            String commandPlanJson,
            String inversePlanJson,
            Optional<Instant> completedAt) {
        public PreviewRow {
            if (mutationPossible) {
                throw new IllegalArgumentException("mutationPossible must be false in Phase R0");
            }
        }
    }

    Optional<PreviewRow> findByIdempotencyKey(String idempotencyKey);

    void create(CreateSpec spec);

    boolean attachJob(String previewId, String jobId);

    Optional<PreviewRow> findByJob(String jobId);

    boolean markRunning(String jobId, long leaseEpoch);

    boolean recordOutcome(
            String jobId,
            long leaseEpoch,
            String state,
            String verdict,
            String stopCode,
            String preRouteTableSha256,
            String preChecksJson,
            String beforePathJson,
            String commandPlanJson,
            String inversePlanJson);

    boolean abandonByJob(String jobId, String stopCode);

    void abandonById(String previewId, String stopCode);

    Optional<PreviewRow> find(String previewId);

    List<PreviewRow> listRecent(int limit);
}
