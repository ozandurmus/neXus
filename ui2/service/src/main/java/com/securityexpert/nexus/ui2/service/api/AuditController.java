package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.audit.AuditEntryDetail;
import com.securityexpert.nexus.ui2.service.audit.AuditEntrySummary;
import com.securityexpert.nexus.ui2.service.audit.AuditFieldProjection;
import com.securityexpert.nexus.ui2.service.audit.AuditService;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.GateOutcome;
import com.securityexpert.nexus.ui2.service.security.GateRequest;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * {@code GET /api/audit} and {@code GET /api/audit/{audit_id}}
 * (`UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §4). Unlike every other
 * controller in this package, this one is <b>not</b> in
 * {@code SecurityWebMvcConfig.ACTION_ID_BY_ROUTE}: the contract's own §2/§5.1
 * requirement -- "the front end computes no scope of its own... decided by
 * the server on every request" between two distinct registered actions,
 * {@link ActionRegistry#AUDIT_READ_OWN} and {@link ActionRegistry#AUDIT_READ_ALL}
 * -- cannot be expressed by that map's fixed one-route-to-one-action shape.
 * This controller instead drives {@link GateChain} itself, through the same
 * {@link GateChainInterceptor#toGateRequest} extraction every other route's
 * interceptor pass uses, so the full {@code E1}-{@code E4} chain still runs
 * before either handler does any work, and {@code E4} still writes exactly
 * one {@code authz_decisions} row per request (AC-6).
 */
@RestController
public final class AuditController {

    private final AuditService auditService;
    private final GateChain gateChain;
    private final RbacEvaluator rbacEvaluator;

    public AuditController(AuditService auditService, GateChain gateChain, RbacEvaluator rbacEvaluator) {
        this.auditService = auditService;
        this.gateChain = gateChain;
        this.rbacEvaluator = rbacEvaluator;
    }

    @GetMapping("/api/audit")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(name = "table_name", required = false) String tableName,
            @RequestParam(name = "row_pk", required = false) String rowPk,
            @RequestParam(name = "operation", required = false) String operation,
            @RequestParam(name = "actor_fingerprint", required = false) String actorFingerprintFilter,
            @RequestParam(name = "action_id", required = false) String actionIdFilter,
            @RequestParam(name = "correlation_run_id", required = false) String correlationRunId,
            @RequestParam(name = "occurred_from", required = false) String occurredFrom,
            @RequestParam(name = "occurred_to", required = false) String occurredTo,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletRequest servletRequest) {
        Instant now = Instant.now();
        Authorization auth = authorize(servletRequest, now);
        if (auth.refused() != null) {
            return auth.refused();
        }

        Optional<Instant> occurredFromValue;
        Optional<Instant> occurredToValue;
        Optional<Long> cursorValue;
        int limitValue;
        try {
            occurredFromValue = parseInstant(occurredFrom);
            occurredToValue = parseInstant(occurredTo);
            cursorValue = parseLong(cursor);
            limitValue = limit == null || limit.isBlank() ? AuditService.DEFAULT_LIMIT : Integer.parseInt(limit.strip());
        } catch (RuntimeException malformed) {
            return validationFailed("invalid_query_parameter");
        }

        AuditService.ListRequest request = new AuditService.ListRequest(
                auth.scopeAll(), auth.actorFingerprint(),
                Optional.ofNullable(tableName), Optional.ofNullable(rowPk), Optional.ofNullable(operation),
                Optional.ofNullable(actorFingerprintFilter), Optional.ofNullable(actionIdFilter),
                Optional.ofNullable(correlationRunId), occurredFromValue, occurredToValue, cursorValue, limitValue);

        AuditService.ListOutcome outcome = auditService.list(request);
        return switch (outcome) {
            case AuditService.ListOutcome.Ok ok -> ResponseEntity.ok(toListBody(ok));
            case AuditService.ListOutcome.ValidationFailed failed -> validationFailed(failed.reasonCode());
            case AuditService.ListOutcome.ScopeRefused refused -> refusalEnvelope(auth, refused.reasonCode());
        };
    }

    @GetMapping("/api/audit/{auditId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String auditId, HttpServletRequest servletRequest) {
        Instant now = Instant.now();
        Authorization auth = authorize(servletRequest, now);
        if (auth.refused() != null) {
            return auth.refused();
        }
        long id;
        try {
            id = Long.parseLong(auditId);
        } catch (NumberFormatException notANumber) {
            return notFound();
        }
        AuditService.DetailOutcome outcome = auditService.get(auth.scopeAll(), auth.actorFingerprint(), id);
        return switch (outcome) {
            case AuditService.DetailOutcome.Ok ok -> ResponseEntity.ok(toDetailBody(ok.detail()));
            case AuditService.DetailOutcome.NotFound ignored -> notFound();
        };
    }

    // -----------------------------------------------------------------
    // Authorization -- the own/all resolution contract §2/§5.1 requires.
    // -----------------------------------------------------------------

    private record Authorization(boolean scopeAll, String actorFingerprint, String actionId,
            ResponseEntity<Map<String, Object>> refused) {
    }

    private Authorization authorize(HttpServletRequest servletRequest, Instant now) {
        GateRequest probe = GateChainInterceptor.toGateRequest(servletRequest, ActionRegistry.AUDIT_READ_OWN);
        GateOutcome outcome = gateChain.evaluate(probe, now,
                actorFingerprint -> actorHoldsSecurityAdmin(actorFingerprint, now)
                        ? ActionRegistry.AUDIT_READ_ALL
                        : ActionRegistry.AUDIT_READ_OWN);
        if (outcome instanceof GateOutcome.Refused refused) {
            return new Authorization(false, null, null,
                    ResponseEntity.status(refused.httpStatus()).body(refused.body()));
        }
        GateOutcome.Proceed proceed = (GateOutcome.Proceed) outcome;
        boolean scopeAll = actorHoldsSecurityAdmin(proceed.actorFingerprint(), now);
        String actionId = scopeAll ? ActionRegistry.AUDIT_READ_ALL : ActionRegistry.AUDIT_READ_OWN;
        return new Authorization(scopeAll, proceed.actorFingerprint(), actionId, null);
    }

    /** A pure, side-effect-free probe (§2: never invented, always the real E4 computation) -- writes no decision row itself. */
    private boolean actorHoldsSecurityAdmin(String actorFingerprint, Instant now) {
        return rbacEvaluator.evaluate(actorFingerprint, Optional.of(RoleToken.SECURITY_ADMIN), now)
                .outcome().proceeds();
    }

    /**
     * §5.2's "ignored-and-refused, not silently widened" for {@code
     * actor_fingerprint} under {@code read_own} -- the same {@code
     * actor_not_in_required_group} reason_code §4.3 already uses, applied
     * post-gate since it is a filter-scope rule, not a session/role gate;
     * {@code decision_id} is omitted (none was minted for this refusal).
     */
    private static ResponseEntity<Map<String, Object>> refusalEnvelope(Authorization auth, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "ACTION_REFUSED");
        body.put("action_id", auth.actionId());
        body.put("outcome", "DENIED");
        body.put("reason_code", reasonCode);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    private static ResponseEntity<Map<String, Object>> validationFailed(String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_FAILED");
        body.put("reason_code", reasonCode);
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // -----------------------------------------------------------------
    // Response shaping -- contract §3.1 (metadata), §3.2 (field projection).
    // -----------------------------------------------------------------

    private static Map<String, Object> toListBody(AuditService.ListOutcome.Ok ok) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", ok.entries().stream().map(AuditController::toSummaryBody).toList());
        body.put("next_cursor", ok.nextCursor().map(String::valueOf).orElse(null));
        return body;
    }

    private static Map<String, Object> toSummaryBody(AuditEntrySummary summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audit_id", summary.auditId());
        body.put("occurred_at", summary.occurredAt().toString());
        body.put("table_name", summary.tableName());
        body.put("row_pk", summary.rowPk());
        body.put("operation", summary.operation());
        body.put("actor_fingerprint", summary.actorFingerprint());
        body.put("action_id", summary.actionId());
        body.put("correlation_run_id", summary.correlationRunId().orElse(null));
        return body;
    }

    private static Map<String, Object> toDetailBody(AuditEntryDetail detail) {
        Map<String, Object> body = toSummaryBody(detail.summary());
        Map<String, Object> beforeFields = new LinkedHashMap<>();
        detail.beforeFields().forEach((key, projection) -> beforeFields.put(key, toProjectionBody(projection)));
        Map<String, Object> afterFields = new LinkedHashMap<>();
        detail.afterFields().forEach((key, projection) -> afterFields.put(key, toProjectionBody(projection)));
        body.put("before_fields", beforeFields);
        body.put("after_fields", afterFields);
        if (!detail.changeStates().isEmpty()) {
            Map<String, Object> changeStates = new LinkedHashMap<>();
            detail.changeStates().forEach((key, state) -> changeStates.put(key, state.name()));
            body.put("change_states", changeStates);
        }
        return body;
    }

    /** Contract §3.2's five payload shapes -- never a digest, never a raw redacted value (AC-1). */
    private static Map<String, Object> toProjectionBody(AuditFieldProjection projection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", projection.state().name());
        switch (projection.state()) {
            case PRESENT -> body.put("value", projection.value());
            case REDACTED -> {
                body.put("tier", projection.tier());
                body.put("reason", projection.reason());
            }
            case UNCLASSIFIED -> {
                body.put("table_name", projection.tableName());
                body.put("column_name", projection.columnName());
            }
            case NULL, ABSENT -> {
                // No further field for either state (§3.2 table).
            }
        }
        return body;
    }

    private static Optional<Instant> parseInstant(String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(Instant.parse(raw.strip()));
    }

    private static Optional<Long> parseLong(String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(Long.parseLong(raw.strip()));
    }
}
