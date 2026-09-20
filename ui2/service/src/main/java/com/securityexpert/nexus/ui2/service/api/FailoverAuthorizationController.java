package com.securityexpert.nexus.ui2.service.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverAuthorizationRequest;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesAuthorizationResult;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverActionStep;
import com.securityexpert.nexus.ui2.jobs.failover.plan.FailoverExecutionPlan;
import com.securityexpert.nexus.ui2.service.failover.FailoverAuthorizationService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for Failover Phase B: Cryptographic 4-Eyes Authorization and Dry-Run Plan Compilation.
 * Strictly read-only disclosure: ZERO device mutations are permitted or executed.
 */
@RestController
@RequestMapping("/api/v2/failover")
public class FailoverAuthorizationController {

    public record AuthorizePayload(
        @JsonProperty("approver_id") String approverId,
        @JsonProperty("reason") String reason,
        @JsonProperty("maintenance_window_ref") String maintenanceWindowRef,
        @JsonProperty("assessment_digest") String assessmentDigest,
        @JsonProperty("nonce") String nonce
    ) {}

    public record DryRunPayload(
        @JsonProperty("token_id") String tokenId,
        @JsonProperty("nonce") String nonce
    ) {}

    private final FailoverAuthorizationService authorizationService;

    public FailoverAuthorizationController(FailoverAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/{clusterRef}/authorize")
    public ResponseEntity<Map<String, Object>> authorize(
        @PathVariable String clusterRef,
        @RequestBody(required = false) AuthorizePayload payload,
        HttpServletRequest servletRequest
    ) {
        String requesterId = actingUser(servletRequest);
        if (requesterId == null || requesterId.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "AUTHENTICATION_REQUIRED");
            err.put("code", "ACTOR_FINGERPRINT_MISSING");
            err.put("reason", "An authenticated actor fingerprint is required to request failover authorization");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }

        if (payload == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "INVALID_PAYLOAD");
            err.put("code", "MISSING_REQUEST_BODY");
            err.put("reason", "An authorization payload is required");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        FailoverAuthorizationRequest request = new FailoverAuthorizationRequest(
            clusterRef,
            requesterId,
            payload.approverId() != null ? payload.approverId() : "",
            payload.reason() != null ? payload.reason() : "",
            payload.maintenanceWindowRef() != null ? payload.maintenanceWindowRef() : "",
            payload.assessmentDigest() != null ? payload.assessmentDigest() : "",
            payload.nonce() != null ? payload.nonce() : ""
        );

        FourEyesAuthorizationResult result = authorizationService.authorizeFailover(request);
        return switch (result) {
            case FourEyesAuthorizationResult.Authorized authorized -> {
                FailoverLeaseToken token = authorized.leaseToken();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "AUTHORIZED");
                body.put("token_id", token.tokenId());
                body.put("cluster_id", opaqueClusterId(token.clusterRef()));
                body.put("requester_id", token.requesterId());
                body.put("approver_id", token.approverId());
                body.put("assessment_digest", token.assessmentDigest());
                body.put("issued_at", token.issuedAt().toString());
                body.put("expires_at", token.expiresAt().toString());
                body.put("token_signature", token.tokenSignature());
                yield ResponseEntity.ok(body);
            }
            case FourEyesAuthorizationResult.Refused refused -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "AUTHORIZATION_REFUSED");
                body.put("code", refused.code());
                body.put("reason", refused.reason());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    @PostMapping("/{clusterRef}/dry-run")
    public ResponseEntity<Map<String, Object>> compileDryRun(
        @PathVariable String clusterRef,
        @RequestBody(required = false) DryRunPayload payload,
        HttpServletRequest servletRequest
    ) {
        String actor = actingUser(servletRequest);
        if (actor == null || actor.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "AUTHENTICATION_REQUIRED");
            err.put("code", "ACTOR_FINGERPRINT_MISSING");
            err.put("reason", "An authenticated actor is required to compile a dry-run plan");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }

        if (payload == null || payload.tokenId() == null || payload.nonce() == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "INVALID_PAYLOAD");
            err.put("code", "MISSING_LEASE_TOKEN");
            err.put("reason", "token_id and nonce are required to execute a dry-run");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
        }

        try {
            FailoverExecutionPlan plan = authorizationService.executeDryRun(clusterRef, payload.tokenId(), payload.nonce());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plan_id", plan.planId());
            body.put("cluster_id", opaqueClusterId(plan.clusterId()));
            body.put("masked_cluster_name", plan.maskedClusterName());
            body.put("vendor", plan.vendor());
            body.put("ha_mode", plan.haMode());
            body.put("plan_type", plan.planType());
            body.put("mutation_authorized", plan.mutationAuthorized());
            body.put("session_continuity_risk", plan.sessionContinuityRisk());
            body.put("preemption_behavior", plan.preemptionBehavior());
            body.put("compiled_at", plan.compiledAt().toString());

            List<Map<String, Object>> transitions = plan.transitionSteps().stream().map(this::serializeStep).toList();
            List<Map<String, Object>> reversals = plan.reversalSteps().stream().map(this::serializeStep).toList();
            body.put("transition_steps", transitions);
            body.put("reversal_steps", reversals);

            return ResponseEntity.ok(body);
        } catch (SecurityException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "SECURITY_VERIFICATION_FAILED");
            err.put("code", "INVALID_OR_EXPIRED_LEASE");
            err.put("reason", ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        } catch (IllegalStateException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "LEASE_ALREADY_CONSUMED");
            err.put("code", "TOKEN_ALREADY_CONSUMED");
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

    private Map<String, Object> serializeStep(FailoverActionStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step_number", step.stepNumber());
        map.put("target_member_id", step.targetMemberId());
        map.put("target_member", step.targetMember());
        map.put("target_member_masked_name", step.targetMemberMaskedName());
        map.put("action_kind", step.actionKind());
        map.put("command", step.command());
        map.put("description", step.description());
        map.put("risk_level", step.riskLevel());
        return map;
    }

    private static String actingUser(HttpServletRequest servletRequest) {
        if (servletRequest == null) return null;
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static String opaqueClusterId(String clusterRef) {
        if (clusterRef == null || clusterRef.isBlank()) {
            return "UNKNOWN_CLUSTER";
        }
        if (clusterRef.length() == 36) {
            try {
                UUID.fromString(clusterRef);
                return clusterRef;
            } catch (IllegalArgumentException ignored) {}
        }
        return UUID.nameUUIDFromBytes(clusterRef.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
