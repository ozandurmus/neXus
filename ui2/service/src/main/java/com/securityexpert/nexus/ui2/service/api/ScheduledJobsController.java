package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobSwitches;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Administration › Platform › Scheduled jobs (V203; PO 2026-11-10). The read ({@code scheduled_jobs_read}) is open to every
 * signed-in session and answers {@code can_write}, decided here; for the aiview persona the actor becomes its keyed user
 * token and the reason is withheld. The write ({@code scheduled_jobs_set}, operation_admin and security_admin, never
 * aiview) needs a reason and, inside business hours, the same acknowledgement as an administrator's manual job: without
 * it nothing is stored and the answer is 409 {@code BUSINESS_HOURS_CONFIRM_REQUIRED}, which the UI and the CLI
 * ({@code --business-hours-ack}) already handle.
 */
@RestController
public final class ScheduledJobsController {

    public record SetRequest(Boolean enabled, String reason) {
    }

    private final ScheduledJobSwitches switches;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;
    private final TopologyNamePseudonymizer names;

    public ScheduledJobsController(ScheduledJobSwitches switches, ActionRegistry actions, RbacEvaluator rbac,
            TopologyNamePseudonymizer names) {
        this.switches = switches;
        this.actions = actions;
        this.rbac = rbac;
        this.names = names;
    }

    @GetMapping("/api/v2/config/scheduled-jobs")
    public ResponseEntity<Map<String, Object>> read(HttpServletRequest request) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(switches.view(mayWrite(request), masked, principal(masked)));
    }

    @PutMapping("/api/v2/config/scheduled-jobs/{family}")
    public ResponseEntity<Map<String, Object>> set(@PathVariable("family") String family,
            @RequestBody(required = false) SetRequest body, HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !mayWrite(request)) {
            return error(403, "SCHEDULED_JOBS_NOT_PERMITTED",
                    "Changing scheduled jobs needs the Security Admin or Operation Admin role.", Map.of());
        }
        boolean acknowledged = ManualAdmission.currentFor(actor).map(ManualAdmission.Grant::acknowledged).orElse(false);
        var outcome = switches.set(actor, family, body == null ? null : body.enabled(), body == null ? null : body.reason(),
                acknowledged, principal(false));
        if (outcome instanceof ScheduledJobSwitches.Outcome.Refused refused) {
            Map<String, Object> extra = new LinkedHashMap<>();
            if (ScheduledJobSwitches.CONFIRM_CODE.equals(refused.code())) {
                extra.put("business_hours_start", switches.businessHours().start().toString());
                extra.put("business_hours_end", switches.businessHours().end().toString());
                extra.put("zone", JobWindowPolicy.ZONE);
                extra.put("ack_header", ManualAdmission.ACK_HEADER);
                extra.put("ack_parameter", ManualAdmission.ACK_PARAMETER);
            }
            return error(refused.status(), refused.code(), refused.message(), extra);
        }
        var changed = (ScheduledJobSwitches.Outcome.Changed) outcome;
        Map<String, Object> response = new LinkedHashMap<>(changed.family());
        response.put("cancelled_jobs", changed.cancelledJobs());
        response.put("closed_runs", changed.closedRuns());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /** The caller may switch families: holds scheduled_jobs_set and is not the aiview persona. */
    boolean mayWrite(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.SCHEDULED_JOBS_SET).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    /** System and migration actors are product names, shown as they are; a person becomes a user token for aiview. */
    private Function<String, String> principal(boolean masked) {
        return raw -> {
            if (raw == null || raw.isBlank() || !masked) return raw;
            if (raw.startsWith("system:") || raw.startsWith("migration:")) return raw;
            return names.identityTokens().user(raw.strip());
        };
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", code);
        body.put("message", message);
        body.putAll(extra);
        return ResponseEntity.status(HttpStatus.valueOf(status)).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
