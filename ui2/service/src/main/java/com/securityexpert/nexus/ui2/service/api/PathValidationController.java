package com.securityexpert.nexus.ui2.service.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathQueryService;
import com.securityexpert.nexus.ui2.service.pathanalysis.validation.PathValidationService;
import com.securityexpert.nexus.ui2.service.policy.PolicyBulkhead;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Path analysis stage A6 (PATH_ANALYSIS_DESIGN.md §7; PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md rows 2-5, RATIFIED):
 * {@code POST /api/v2/path-analysis/validate} and {@code GET /api/v2/path-analysis/validate/{id}}.
 * {@link GateChainInterceptor} has already applied {@code path_analysis_validate} (security_admin, operation_admin,
 * aiview under the CLASS_0 diagnostic allowance).
 *
 * <p><b>No browser-to-device path.</b> The body is the path query itself (the A5a fields, with the same strict parser
 * and the same aiview masked-only resolution) plus an optional {@code branch_id} of the computed result. Nothing else is
 * accepted: no command, gate, context, VSID, zone, virtual router or rule. The server computes the path again and the
 * plan of every hop comes only from that result.</p>
 *
 * <p>Order: 6 requests per minute per actor (429), strict body validation (400), the shared {@link PolicyBulkhead}
 * (503), the path computation (409 {@code TOPOLOGY_NOT_BUILT}, 422 {@code PSEUDONYM_REFUSED} as the query), the plan
 * (404 {@code BRANCH_NOT_FOUND}), one {@code authz_decisions} row (503 when it cannot be written: then nothing is
 * admitted), then the jobs. 202 with the validation (hop states and opaque ids only). Outside a job window the shared
 * {@link JobWindowAdvice} answers 409 before anything is written.</p>
 */
@RestController
public final class PathValidationController {

    static final int RATE_PER_MINUTE = 6;
    static final String AUTHORITY = "path_analysis_validation";

    private final PathQueryService paths;
    private final PathValidationService validations;
    private final AuthzDecisionRepository audit;
    private final PolicyBulkhead heavy;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ArrayDeque<Instant>> requests = new HashMap<>();

    @Autowired
    public PathValidationController(PathQueryService paths, PathValidationService validations,
            AuthzDecisionRepository audit, PolicyBulkhead heavy) {
        this(paths, validations, audit, heavy, Clock.systemUTC());
    }

    PathValidationController(PathQueryService paths, PathValidationService validations, AuthzDecisionRepository audit,
            PolicyBulkhead heavy, Clock clock) {
        this.paths = paths;
        this.validations = validations;
        this.audit = audit;
        this.heavy = heavy;
        this.clock = clock;
    }

    @PostMapping("/api/v2/path-analysis/validate")
    public ResponseEntity<?> validate(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Object actor = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        Object session = request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE);
        if (!(actor instanceof String user) || user.isBlank() || !(session instanceof String sessionId)) {
            return code(401, "UNAUTHENTICATED");
        }
        if (!admit(user, clock.instant())) {
            return ResponseEntity.status(429).cacheControl(CacheControl.noStore()).header(HttpHeaders.RETRY_AFTER, "60")
                    .body(Map.of("code", "PATH_VALIDATION_RATE_LIMIT"));
        }
        PathQueryService.Request query;
        Optional<String> branch;
        try {
            if (body == null) throw new IllegalArgumentException("body");
            Map<String, Object> rest = new LinkedHashMap<>(body);
            Object branchValue = rest.remove("branch_id");
            if (branchValue != null && !(branchValue instanceof String b && b.matches("b[0-9]{1,3}"))) {
                throw new IllegalArgumentException("branch_id");
            }
            branch = Optional.ofNullable((String) branchValue);
            query = PathAnalysisController.parse(rest);
            PathQueryService.validate(query);
        } catch (IllegalArgumentException invalid) {
            return code(400, "INVALID_REQUEST");
        }
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        PathQueryService.Outcome outcome;
        var permit = heavy.tryEnter();
        if (permit == null) {
            return PolicyBulkhead.busy();
        }
        try {
            outcome = paths.query(query, masked);
        } finally {
            permit.close();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("query", outcome.auditQuery());
        switch (outcome) {
            case PathQueryService.NotBuilt notBuilt -> {
                evidence.put("validation", "NOT_EVALUATED");
                evidence.put("reason", "TOPOLOGY_NOT_BUILT");
                return record(sessionId, user, AuthzOutcome.PERMITTED, Optional.empty(), evidence)
                        ? code(409, "TOPOLOGY_NOT_BUILT") : code(503, "AUDIT_UNAVAILABLE");
            }
            case PathQueryService.Refused refused -> {
                evidence.put("validation", "REFUSED");
                evidence.put("reason", refused.code());
                return record(sessionId, user, AuthzOutcome.DENIED, Optional.empty(), evidence)
                        ? code(422, "PSEUDONYM_REFUSED") : code(503, "AUDIT_UNAVAILABLE");
            }
            case PathQueryService.Computed computed -> {
                var prepared = validations.prepare(computed.result(), branch, user);
                if (prepared.isEmpty()) {
                    evidence.put("validation", "REFUSED");
                    evidence.put("reason", "BRANCH_NOT_FOUND");
                    return record(sessionId, user, AuthzOutcome.DENIED, Optional.empty(), evidence)
                            ? code(404, "BRANCH_NOT_FOUND") : code(503, "AUDIT_UNAVAILABLE");
                }
                var plan = prepared.get().plan();
                evidence.put("validation", "SUBMITTED");
                evidence.put("validation_id", prepared.get().validationId());
                evidence.put("verdict", computed.result().verdict());
                evidence.put("branch_id", plan.branchId());
                evidence.put("hops_planned", plan.hops().size());
                evidence.put("hops_runnable", plan.runnable());
                evidence.put("hops_not_validated", plan.hopsNotValidated());
                if (!record(sessionId, user, AuthzOutcome.PERMITTED, Optional.of(prepared.get().validationId()), evidence)) {
                    return code(503, "AUDIT_UNAVAILABLE");
                }
                return ResponseEntity.status(202).cacheControl(CacheControl.noStore())
                        .body(validations.submit(prepared.get(), user));
            }
        }
    }

    @GetMapping("/api/v2/path-analysis/validate/{validationId}")
    public ResponseEntity<?> result(@PathVariable String validationId, HttpServletRequest request) {
        if (!(request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE) instanceof String)) {
            return code(401, "UNAUTHENTICATED");
        }
        return validations.read(validationId).<ResponseEntity<?>>map(body -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(body)).orElseGet(() -> code(404, "NOT_FOUND"));
    }

    /** One append-only {@code authz_decisions} row: masked query, outcome and counts, nothing raw. */
    private boolean record(String sessionId, String user, AuthzOutcome decision, Optional<String> target,
            Map<String, Object> evidence) {
        try {
            audit.insert(sessionId, user, ActionRegistry.PATH_ANALYSIS_VALIDATE, target, decision, Optional.of(AUTHORITY),
                    Optional.of(json.writeValueAsString(evidence)), Optional.empty());
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private synchronized boolean admit(String actor, Instant now) {
        Instant cutoff = now.minusSeconds(60);
        requests.values().forEach(times -> {
            while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
                times.removeFirst();
            }
        });
        requests.values().removeIf(ArrayDeque::isEmpty);
        if (!requests.containsKey(actor) && requests.size() >= 10_000) {
            return false;
        }
        var times = requests.computeIfAbsent(actor, key -> new ArrayDeque<>());
        if (times.size() >= RATE_PER_MINUTE) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    private static ResponseEntity<?> code(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
    }
}
