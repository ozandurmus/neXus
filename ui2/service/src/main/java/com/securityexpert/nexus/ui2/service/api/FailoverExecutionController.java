package com.securityexpert.nexus.ui2.service.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverExecutionResult;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverExecutionState;
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
        String actor = actingUser(servletRequest);
        if (actor == null || actor.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "AUTHENTICATION_REQUIRED");
            err.put("code", "ACTOR_FINGERPRINT_MISSING");
            err.put("reason", "An authenticated actor fingerprint is required to execute failover");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }

        if (payload == null || payload.tokenId() == null || payload.nonce() == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "INVALID_PAYLOAD");
            err.put("code", "MISSING_REQUIRED_FIELDS");
            err.put("reason", "token_id and nonce are required to execute failover");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        FailoverActionKind actionKind = FailoverActionKind.CONTROLLED_FAILOVER;
        if (payload.actionKind() != null && !payload.actionKind().isBlank()) {
            try {
                actionKind = FailoverActionKind.valueOf(payload.actionKind().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "INVALID_ACTION_KIND");
                err.put("code", "UNKNOWN_ACTION_KIND");
                err.put("reason", "Supported action kinds are: CONTROLLED_FAILOVER, RETURN_TO_SERVICE");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
            }
        }

        try {
            FailoverExecutionResult result = executionService.executeFailover(
                clusterRef, payload.tokenId(), payload.nonce(), actionKind, actor
            );

            Map<String, Object> body = serializeExecutionResult(result);
            HttpStatus status = switch (result.state()) {
                case SUCCEEDED, FAILED_NO_CHANGE, VENDOR_REJECTED -> HttpStatus.OK;
                case ABORTED_PRE_MUTATION -> HttpStatus.UNPROCESSABLE_ENTITY;
                case OUTCOME_UNKNOWN -> HttpStatus.CONFLICT;
                default -> HttpStatus.OK;
            };
            return ResponseEntity.status(status).body(body);

        } catch (SecurityException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "SECURITY_VERIFICATION_FAILED");
            err.put("code", "ACCESS_DENIED");
            err.put("reason", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        } catch (IllegalStateException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "EXECUTION_CONFLICT");
            err.put("code", "CONFLICT");
            err.put("reason", ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(err);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "INVALID_ARGUMENT");
            err.put("code", "BAD_REQUEST");
            err.put("reason", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }
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

        if (payload == null || payload.approverId() == null || payload.reason() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "INVALID_PAYLOAD",
                "code", "MISSING_REQUIRED_FIELDS",
                "reason", "approver_id and reason are required to acknowledge quarantine"
            ));
        }

        try {
            executionService.acknowledgeQuarantine(clusterRef, actor, payload.approverId(), payload.reason());
            return ResponseEntity.ok(Map.of(
                "status", "QUARANTINE_LIFTED",
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
