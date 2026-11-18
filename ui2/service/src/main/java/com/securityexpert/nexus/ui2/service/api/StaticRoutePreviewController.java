package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidationException;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.ConflictException;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.NotFoundException;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.PreviewSubmissionRequest;
import com.securityexpert.nexus.ui2.service.staticroute.StaticRoutePreviewService.SubmissionOutcome;

/**
 * Operations > Static routes, Phase R0 controller:
 * <ul>
 *   <li>{@code POST /api/v2/operations/static-route/preview} ({@link ActionRegistry#STATIC_ROUTE_PREVIEW})</li>
 *   <li>{@code GET /api/v2/operations/static-route/preview} ({@link ActionRegistry#STATIC_ROUTE_READ})</li>
 *   <li>{@code GET /api/v2/operations/static-route/preview/{previewId}} ({@link ActionRegistry#STATIC_ROUTE_READ})</li>
 * </ul>
 *
 * <p>Phase R0 is strictly read-only ({@code CLASS_0}): no write route exists on this controller or anywhere in the
 * service, and {@code mutation_possible} is unconditionally {@code false}.
 */
@RestController
public final class StaticRoutePreviewController {

    private static final String AUTHORITY = "static_route_r0_preview";
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "idempotency_key", "idempotencyKey",
            "unit_id", "unitId",
            "vendor",
            "routing_context", "routingContext",
            "operation",
            "destination_prefix", "destinationPrefix",
            "next_hop_ip", "nextHopIp",
            "interface_name", "interfaceName",
            "distance",
            "metric",
            "priority",
            "route_name", "routeName",
            "device_comment", "deviceComment",
            "representative_host", "representativeHost",
            "change_ticket_ref", "changeTicketRef",
            "reason");

    private final StaticRoutePreviewService service;
    private final AuthzDecisionRepository audit;

    @Autowired
    public StaticRoutePreviewController(StaticRoutePreviewService service, AuthzDecisionRepository audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/api/v2/operations/static-route/preview")
    public ResponseEntity<?> createPreview(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        Object actorObj = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        Object sessionObj = request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE);
        if (!(actorObj instanceof String actor) || actor.isBlank() || !(sessionObj instanceof String sessionId)) {
            return error(401, "UNAUTHENTICATED", "Authentication is required.");
        }
        if (body == null || body.isEmpty()) {
            return error(400, "INVALID_REQUEST", "Request body is required.");
        }
        for (String key : body.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                return error(400, "UNKNOWN_FIELD", "Unsupported request field: " + key);
            }
        }

        PreviewSubmissionRequest parsed;
        try {
            String idemKey = firstNonBlank(idempotencyKeyHeader,
                    stringField(body, "idempotency_key", "idempotencyKey"));
            parsed = new PreviewSubmissionRequest(
                    idemKey,
                    stringField(body, "unit_id", "unitId"),
                    stringField(body, "vendor"),
                    stringField(body, "routing_context", "routingContext"),
                    stringField(body, "operation"),
                    stringField(body, "destination_prefix", "destinationPrefix"),
                    stringField(body, "next_hop_ip", "nextHopIp"),
                    stringField(body, "interface_name", "interfaceName"),
                    intField(body, "distance"),
                    intField(body, "metric"),
                    intField(body, "priority"),
                    stringField(body, "route_name", "routeName"),
                    stringField(body, "device_comment", "deviceComment"),
                    stringField(body, "representative_host", "representativeHost"),
                    stringField(body, "change_ticket_ref", "changeTicketRef"),
                    stringField(body, "reason"));
        } catch (IllegalArgumentException badType) {
            return error(400, "INVALID_FIELD_TYPE", badType.getMessage());
        }

        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        try {
            SubmissionOutcome outcome = service.submitPreview(parsed, actor, masked);
            recordAudit(sessionId, actor, parsed.unitId(), "PREVIEW_SUBMITTED");
            int httpStatus = outcome.deduplicated() ? 200 : 202;
            return ResponseEntity.status(httpStatus)
                    .cacheControl(CacheControl.noStore())
                    .body(outcome.body());
        } catch (ValidationException invalid) {
            recordAudit(sessionId, actor, parsed.unitId(), invalid.code());
            int status = "FORTIMANAGER_MANAGED".equals(invalid.code())
                    || "ROUTING_CONTEXT_UNSUPPORTED".equals(invalid.code())
                    || "DEVICE_NOT_ELIGIBLE".equals(invalid.code())
                    ? 422 : 400;
            return error(status, invalid.code(), invalid.getMessage());
        } catch (ConflictException conflict) {
            recordAudit(sessionId, actor, parsed.unitId(), conflict.code());
            return error(409, conflict.code(), conflict.getMessage());
        } catch (NotFoundException notFound) {
            recordAudit(sessionId, actor, parsed.unitId(), notFound.code());
            return error(404, notFound.code(), notFound.getMessage());
        }
    }

    @GetMapping("/api/v2/operations/static-route/preview")
    public ResponseEntity<?> listPreviews(HttpServletRequest request) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.listPreviewsAndTargets(masked));
    }

    @GetMapping("/api/v2/operations/static-route/preview/{previewId}")
    public ResponseEntity<?> getPreview(@PathVariable("previewId") String previewId, HttpServletRequest request) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        Optional<Map<String, Object>> found = service.getPreview(previewId, masked);
        if (found.isEmpty()) {
            return error(404, "PREVIEW_NOT_FOUND", "Static-route preview not found.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(found.get());
    }

    private void recordAudit(String sessionId, String actor, String targetRef, String reasonCode) {
        try {
            audit.insert(
                    sessionId,
                    actor,
                    ActionRegistry.STATIC_ROUTE_PREVIEW,
                    Optional.ofNullable(targetRef).filter(s -> !s.isBlank()),
                    AuthzOutcome.PERMITTED,
                    Optional.of(AUTHORITY),
                    Optional.ofNullable(reasonCode),
                    Optional.empty());
        } catch (RuntimeException ignored) {
            // Best-effort secondary controller audit; GateChain already recorded the E4 decision row.
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        if (b != null && !b.isBlank()) {
            return b.strip();
        }
        return null;
    }

    private static String stringField(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object v = body.get(key);
            if (v != null) {
                if (!(v instanceof String s)) {
                    throw new IllegalArgumentException("Field '" + key + "' must be a string.");
                }
                return s;
            }
        }
        return null;
    }

    private static Integer intField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            long l = n.longValue();
            if (n.doubleValue() != (double) l || l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Field '" + key + "' must be an integer.");
            }
            return (int) l;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Field '" + key + "' must be an integer.");
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("code", code);
        body.put("message", message);
        body.put("mutation_possible", false);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
