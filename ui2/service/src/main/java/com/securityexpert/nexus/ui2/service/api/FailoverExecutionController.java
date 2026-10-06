package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverExecutionResult;
import com.securityexpert.nexus.ui2.service.failover.FailoverExecutionService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
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

    @PostMapping("/{clusterRef}/quarantine/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeQuarantine(
        @PathVariable String clusterRef,
        @RequestBody(required = false) AcknowledgeQuarantinePayload payload,
        HttpServletRequest servletRequest
    ) {
        String actor = actingUser(servletRequest);
        if (actor == null || actor.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "AUTHENTICATION_REQUIRED",
                "code", "ACTOR_FINGERPRINT_MISSING"
            ));
        }

        if (payload == null || payload.executionId() == null || payload.executionId().isBlank() || payload.approverId() == null || payload.reason() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "INVALID_PAYLOAD",
                "code", "MISSING_REQUIRED_FIELDS",
                "reason", "execution_id, approver_id and reason are required to acknowledge quarantine"
            ));
        }

        try {
            if (!executionService.acknowledgeQuarantine(clusterRef, payload.executionId(), actor, payload.approverId(), payload.reason()))
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "INCIDENT_CAS_MISMATCH"));
            return ResponseEntity.ok(Map.of(
                "status", "INCIDENT_RELEASED",
                "quarantine_active", executionService.isClusterQuarantined(clusterRef),
                "cluster_id", opaqueClusterId(clusterRef),
                "acknowledged_by", actor,
                "approved_by", payload.approverId()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "INVALID_ARGUMENT",
                "reason", ex.getMessage()
            ));
        }
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

    private static String actingUser(HttpServletRequest servletRequest) {
        if (servletRequest == null) return null;
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
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
