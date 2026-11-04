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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathQueryService;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathResultView;
import com.securityexpert.nexus.ui2.service.policy.PolicyBulkhead;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Path analysis stage A5a: {@code POST /api/v2/path-analysis/query} (PATH_ANALYSIS_DESIGN.md §5, §7; PO record
 * 2026-11-03 D-2, D-3, D-8). {@link GateChainInterceptor} has already applied {@code path_analysis_read} (open like the
 * inventory read; a read-only POST with the usual CSRF and Origin checks). Then, in order: 30 queries per minute per
 * actor (429), strict body validation (400), the shared {@link PolicyBulkhead} (503 {@code POLICY_BUSY}), the query.
 *
 * <p>Responses: 200 with the result body (raw for inventory roles; for aiview masked by
 * {@link PrivacyMaskingResponseBodyAdvice}); 409 {@code TOPOLOGY_NOT_BUILT} when no topology is held (never built on
 * demand); 422 {@code PSEUDONYM_REFUSED} for any aiview value that is raw, unknown or ambiguous -- one shape for all
 * three (D-3 condition 5). Every query that reaches the service writes exactly one {@code authz_decisions} row with
 * the masked query and the verdict (D-2); a result whose row cannot be written is not returned (503). Nothing else is
 * stored.</p>
 */
@RestController
public final class PathAnalysisController {

    static final int RATE_PER_MINUTE = 30;
    private static final Set<String> FIELDS = Set.of("src", "dst", "protocol", "port", "ingress_node_id",
            "ingress_interface_name", "edge_mode");
    private static final Set<String> REQUIRED = Set.of("src", "dst", "protocol", "port");
    static final String AUTHORITY = "path_analysis_query";

    private final PathQueryService paths;
    private final AuthzDecisionRepository audit;
    private final PolicyBulkhead heavy;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, ArrayDeque<Instant>> requests = new HashMap<>();

    @Autowired
    public PathAnalysisController(PathQueryService paths, AuthzDecisionRepository audit, PolicyBulkhead heavy) {
        this(paths, audit, heavy, Clock.systemUTC());
    }

    PathAnalysisController(PathQueryService paths, AuthzDecisionRepository audit, PolicyBulkhead heavy, Clock clock) {
        this.paths = paths;
        this.audit = audit;
        this.heavy = heavy;
        this.clock = clock;
    }

    @PostMapping("/api/v2/path-analysis/query")
    public ResponseEntity<?> query(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Object actor = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        Object session = request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE);
        if (!(actor instanceof String user) || user.isBlank() || !(session instanceof String sessionId)) {
            return code(401, "UNAUTHENTICATED");
        }
        if (!admit(user, clock.instant())) {
            return ResponseEntity.status(429).cacheControl(CacheControl.noStore()).header(HttpHeaders.RETRY_AFTER, "60")
                    .body(Map.of("code", "PATH_QUERY_RATE_LIMIT"));
        }
        PathQueryService.Request parsed;
        try {
            parsed = parse(body);
            PathQueryService.validate(parsed);
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
            outcome = paths.query(parsed, masked);
        } finally {
            permit.close();
        }
        if (!record(sessionId, user, outcome)) {
            return code(503, "AUDIT_UNAVAILABLE");
        }
        return switch (outcome) {
            case PathQueryService.Computed computed -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(PathResultView.body(computed.result()));
            case PathQueryService.NotBuilt notBuilt -> code(409, "TOPOLOGY_NOT_BUILT");
            case PathQueryService.Refused refused -> code(422, "PSEUDONYM_REFUSED");
        };
    }

    /** One append-only {@code authz_decisions} row: masked query, overall verdict, nothing raw (D-2). */
    private boolean record(String sessionId, String user, PathQueryService.Outcome outcome) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("query", outcome.auditQuery());
        AuthzOutcome decision = AuthzOutcome.PERMITTED;
        Optional<String> target = Optional.empty();
        switch (outcome) {
            case PathQueryService.Computed c -> {
                evidence.put("verdict", c.result().verdict());
                evidence.put("verdict_reason", c.result().reason());
                target = Optional.of(c.result().topologySnapshotId());
            }
            case PathQueryService.NotBuilt n -> {
                evidence.put("verdict", "NOT_EVALUATED");
                evidence.put("verdict_reason", "TOPOLOGY_NOT_BUILT");
            }
            case PathQueryService.Refused r -> {
                decision = AuthzOutcome.DENIED;
                evidence.put("verdict", "REFUSED");
                evidence.put("verdict_reason", r.code());
            }
        }
        try {
            audit.insert(sessionId, user, ActionRegistry.PATH_ANALYSIS_READ, target, decision, Optional.of(AUTHORITY),
                    Optional.of(json.writeValueAsString(evidence)), Optional.empty());
            return true;
        } catch (JsonProcessingException | RuntimeException failure) {
            return false;
        }
    }

    private static PathQueryService.Request parse(Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet()) || !body.keySet().containsAll(REQUIRED)) {
            throw new IllegalArgumentException("fields");
        }
        String src = token(body.get("src"), 15);
        String dst = token(body.get("dst"), 15);
        String protocol = token(body.get("protocol"), 8);
        if (!(body.get("port") instanceof Integer port)) {
            throw new IllegalArgumentException("port");
        }
        Optional<String> node = optionalToken(body.get("ingress_node_id"));
        Optional<String> iface = optionalToken(body.get("ingress_interface_name"));
        if (iface.isPresent() && node.isEmpty()) {
            throw new IllegalArgumentException("interface without unit");
        }
        Object edge = body.get("edge_mode");
        if (edge != null && !(edge instanceof Boolean)) {
            throw new IllegalArgumentException("edge_mode");
        }
        return new PathQueryService.Request(src, dst, protocol, port, node, iface, Boolean.TRUE.equals(edge));
    }

    private static Optional<String> optionalToken(Object value) {
        return value == null ? Optional.empty() : Optional.of(token(value, 128));
    }

    private static String token(Object value, int max) {
        if (!(value instanceof String s) || s.isEmpty() || s.length() > max || !s.strip().equals(s)
                || s.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("token");
        }
        return s;
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

    /** Uniform refusal shape; the code never echoes an input value. */
    private static ResponseEntity<?> code(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
    }
}
