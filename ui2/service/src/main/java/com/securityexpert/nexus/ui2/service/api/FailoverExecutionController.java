package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverExecutionResult;
import com.securityexpert.nexus.ui2.service.failover.FailoverExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST API Controller for Phase C: Controlled Manual Failover Execution.
 * Strictly gated behind authenticated actor fingerprint and validated 4-eyes lease token.
 */
@RestController
@RequestMapping("/api/v2/failover")
public class FailoverExecutionController {

    public record ExecutePayload(
        @JsonProperty("token_id") String tokenId,
        @JsonProperty("nonce") String nonce,
        @JsonProperty("action_kind") String actionKind
    ) {}

    public record AcknowledgeQuarantinePayload(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("approver_id") String approverId,
        @JsonProperty("reason") String reason
    ) {}

    private final FailoverExecutionService executionService;

    public FailoverExecutionController(FailoverExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/{clusterRef}/execute")
    public ResponseEntity<Map<String, Object>> executeFailover(
        @PathVariable String clusterRef,
        @RequestBody(required = false) ExecutePayload payload,
        HttpServletRequest servletRequest
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "error", FailoverMutationSwitch.GENERIC_DISABLED,
            "reason", "Generic failover execution is disabled"
        ));
    }

    @GetMapping("/{clusterRef}/executions/{executionId}")
    public ResponseEntity<Map<String, Object>> getExecution(
        @PathVariable String clusterRef,
        @PathVariable String executionId
    ) {
        return executionService.getExecution(executionId)
            .map(res -> ResponseEntity.ok(serializeExecutionResult(res)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "reason", "Execution record not found with ID: " + executionId
            )));
    }

    /** The only release path is the server-side-actor route of the CP failover API (PO decision 2026-10-20 item 1). */
    public static final String GENERIC_INCIDENT_RELEASE_REMOVED = "GENERIC_INCIDENT_RELEASE_REMOVED";

    /**
     * Refused unconditionally: this generic route took the approver from the request body. A failover incident
     * is released only through {@code POST /api/v2/cp-failover/runs/{runId}/incident/release} (security_admin,
     * actor from the session, mandatory reason). The route also stays absent from the action map (403).
     */
    @PostMapping("/{clusterRef}/quarantine/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeQuarantine(
        @PathVariable String clusterRef,
        @RequestBody(required = false) AcknowledgeQuarantinePayload payload,
        HttpServletRequest servletRequest
    ) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
            "error", GENERIC_INCIDENT_RELEASE_REMOVED,
            "code", GENERIC_INCIDENT_RELEASE_REMOVED,
            "reason", "Release the incident from the failover screen; the approver is never taken from the request"
        ));
    }

    private Map<String, Object> serializeExecutionResult(FailoverExecutionResult res) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("execution_id", res.executionId());
        map.put("cluster_id", opaqueClusterId(res.clusterRef()));
        map.put("masked_cluster_name", res.maskedClusterName());
        map.put("vendor", res.vendor());
        map.put("action_kind", res.actionKind().name());
        map.put("state", res.state().name());
        map.put("requested_at", res.requestedAt().toString());
        map.put("boundary_crossed_at", res.boundaryCrossedAt() != null ? res.boundaryCrossedAt().toString() : null);
        map.put("completed_at", res.completedAt().toString());
        map.put("target_member_id", res.targetMemberId());
        map.put("target_member_masked_name", res.targetMemberMaskedName());
        map.put("requester_id", res.requesterId());
        map.put("approver_id", res.approverId());
        map.put("quarantine_active", res.quarantineActive());
        map.put("summary", res.summary());
        return map;
    }

    private static String opaqueClusterId(String clusterRef) {
        if (clusterRef == null || clusterRef.isBlank()) return "UNKNOWN_CLUSTER";
        if (clusterRef.length() == 36) {
            try {
                UUID.fromString(clusterRef);
                return clusterRef;
            } catch (IllegalArgumentException ignored) {}
        }
        return UUID.nameUUIDFromBytes(clusterRef.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
