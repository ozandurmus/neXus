package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

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
 * from the map is outside this chain's scope entirely (e.g.
 * {@code /healthz}, {@code /login}) — never gated, never refused here.</p>
 *
 * <p>A path-variable route (WORKER.md "Routes": {@code GET /devices/{id}},
 * this map's first one) is looked up by its exact route first and, only on
 * a miss, by replacing its last path segment with a single {@code *}
 * wildcard (e.g. {@code "GET /devices/*"}) -- one segment only, never a
 * multi-segment or prefix match, so every other, non-wildcarded route entry
 * keeps matching exactly as it always has.</p>
 */
public final class GateChainInterceptor implements HandlerInterceptor {

    public static final String ACTOR_FINGERPRINT_ATTRIBUTE = "ui2.gate.actorFingerprint";
    public static final String SESSION_ID_ATTRIBUTE = "ui2.gate.sessionId";

    private final GateChain gateChain;
    private final Map<String, String> actionIdByRoute;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GateChainInterceptor(GateChain gateChain, Map<String, String> actionIdByRoute) {
        this.gateChain = gateChain;
        this.actionIdByRoute = actionIdByRoute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String actionId = actionIdFor(request.getMethod(), request.getServletPath());
        if (actionId == null) {
            return true;
        }

        GateRequest gateRequest = new GateRequest(
                request.getMethod(),
                findSessionCookie(request),
                Optional.ofNullable(request.getHeader("X-CSRF-Token")),
                Optional.ofNullable(request.getHeader("Origin")),
                actionId,
                Optional.ofNullable(request.getParameter("target_ref")));

        GateOutcome outcome = gateChain.evaluate(gateRequest, Instant.now());
        if (outcome instanceof GateOutcome.Proceed proceed) {
            request.setAttribute(ACTOR_FINGERPRINT_ATTRIBUTE, proceed.actorFingerprint());
            request.setAttribute(SESSION_ID_ATTRIBUTE, proceed.sessionId());
            return true;
        }
        GateOutcome.Refused refused = (GateOutcome.Refused) outcome;
        response.setStatus(refused.httpStatus());
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), refused.body());
        return false;
    }

    private String actionIdFor(String method, String servletPath) {
        String exact = actionIdByRoute.get(method + " " + servletPath);
        if (exact != null) {
            return exact;
        }
        int lastSlash = servletPath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        return actionIdByRoute.get(method + " " + servletPath.substring(0, lastSlash) + "/*");
    }

    private static Optional<String> findSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if ("ui2_session".equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
