package com.securityexpert.nexus.ui2.service.security;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The single, route-wide Spring MVC adapter for {@link GateChain} (contract
 * §3 placement row: only {@code service} module may hold a controller/
 * interceptor). Extracts a servlet-independent {@link GateRequest}, runs
 * the chain, and either lets the request proceed (storing the resolved
 * actor fingerprint as a request attribute controllers read) or writes the
 * refusal envelope directly, never invoking the handler (AC-4: "a mutating
 * request that fails any gate never reaches the handler").
 *
 * <p>{@code action_id} resolution here is a simple {@code "METHOD path"} →
 * {@code action_id} route map, this movement's own stand-in for a real
 * {@code C4} route→action mapping (deferred, contract §2). A route absent
 * from both that map and the explicit non-RBAC exception set is refused:
 * adding a controller cannot infer open access.</p>
 *
 * <p>A path-variable route (WORKER.md "Routes": {@code GET /devices/{id}},
 * this map's first one) is looked up by its exact route first and, only on
 * a miss, by replacing exactly one path segment with a single {@code *}
 * wildcard and re-checking the map -- tried from the last segment inward
 * (e.g. {@code "GET /devices/*"} for {@code GET /devices/{id}}, then
 * {@code "GET /devices/*​/inventory"} for
 * {@code GET /devices/{id}/inventory}, NXS-LOCAL-0160 "Routes": the id is
 * not always the last segment). Always one segment wildcarded at a time,
 * never two at once and
 * never a prefix match, so every other, non-wildcarded route entry keeps
 * matching exactly as it always has, and the original last-segment-only
 * routes ({@code GET /devices/*}) still resolve on their very first
 * wildcard attempt.</p>
 */
public final class GateChainInterceptor implements HandlerInterceptor {

    public static final String ACTOR_FINGERPRINT_ATTRIBUTE = "ui2.gate.actorFingerprint";
    public static final String SESSION_ID_ATTRIBUTE = "ui2.gate.sessionId";
    public static final String IS_REPLAY_VIEWER_ATTRIBUTE = "ui2.gate.isReplayViewer";

    private final RouteActionGate gate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GateChainInterceptor(GateChain gateChain, Map<String, String> actionIdByRoute) {
        this(gateChain, actionIdByRoute, Set.of(), null, null);
    }

    public GateChainInterceptor(GateChain gateChain, Map<String, String> actionIdByRoute,
            Set<String> explicitlyUngatedRoutes) {
        this(gateChain, actionIdByRoute, explicitlyUngatedRoutes, null, null);
    }

    public GateChainInterceptor(GateChain gateChain, Map<String, String> actionIdByRoute,
            Set<String> explicitlyUngatedRoutes, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver) {
        this.gate = new RouteActionGate(gateChain, actionIdByRoute, explicitlyUngatedRoutes, localIdentityResolver,
                localRoleTokenResolver);
    }

    /**
     * The decision itself lives in {@link RouteActionGate}, shared with {@link NexusRouteActionAuthorizationManager}; this
     * interceptor is the rollback path ({@code ui2.security.spring-authz-manager.enabled=false}).
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        RouteActionGate.Verdict verdict = gate.decide(request, handler instanceof ResourceHttpRequestHandler);
        if (verdict.permitted()) {
            verdict.attributes().forEach(request::setAttribute);
            return true;
        }
        response.setStatus(verdict.status());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), verdict.body());
        return false;
    }

    private String actionIdFor(String method, String servletPath) {
        return gate.actionIdFor(method, servletPath);
    }
}
