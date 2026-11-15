package com.securityexpert.nexus.ui2.service.security;

import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Spring Security migration Step 5 (docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md): the route-to-action
 * authorization decision, as the {@code AuthorizationManager} behind {@code anyRequest()} of the security filter chain. It
 * replaces {@link GateChainInterceptor} in the request path and decides exactly what that interceptor decided: the route map
 * and the explicit exemptions ({@code SecurityWebMvcConfig.ACTION_ID_BY_ROUTE}, {@code EXPLICITLY_UNGATED_ROUTES}) and
 * {@link GateChain} E1 to E6 run inside {@link RouteActionGate}, the one shared implementation. A refusal is carried to
 * {@link NexusSecurityAccessDeniedHandler} unchanged (status and body); a permit sets the actor, session and replay-viewer
 * request attributes before the controller and {@code ManualAdmissionInterceptor} run.
 *
 * <p>The interceptor only ever ran for a request Spring MVC had a handler for. So that the same requests are judged here,
 * {@link HandlerKindResolver} asks the handler mappings the same question the dispatcher will ask: a request no handler
 * serves (404, 405, an unsupported media type) is not judged, it reaches the dispatcher and is answered as before.</p>
 *
 * <p>The decision does not use the {@code Authentication} in the {@code SecurityContext}: {@link GateChain} stays the single
 * authority and reads the {@code ui2_session} cookie itself.</p>
 */
public final class NexusRouteActionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** What Spring MVC would hand the request to. */
    public enum HandlerKind {
        /** A controller or any other non-static handler. */
        CONTROLLER,
        /** The static-resource handler. */
        RESOURCE,
        /** No handler: the dispatcher answers 404 or 405 itself and the interceptor never ran. */
        NONE
    }

    @FunctionalInterface
    public interface HandlerKindResolver {
        HandlerKind resolve(HttpServletRequest request);
    }

    /** A refusal that is to be written exactly as the interceptor wrote it. */
    public static final class Refusal extends AuthorizationDecision {
        private final int status;
        private final Object body;

        Refusal(int status, Object body) {
            super(false);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public Object body() {
            return body;
        }
    }

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);

    private final RouteActionGate gate;
    private final HandlerKindResolver handlerKinds;

    public NexusRouteActionAuthorizationManager(RouteActionGate gate, HandlerKindResolver handlerKinds) {
        this.gate = java.util.Objects.requireNonNull(gate, "gate");
        this.handlerKinds = java.util.Objects.requireNonNull(handlerKinds, "handlerKinds");
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        return decide(context.getRequest());
    }

    AuthorizationResult decide(HttpServletRequest request) {
        HandlerKind kind = handlerKinds.resolve(request);
        if (kind == HandlerKind.NONE) {
            return GRANTED;
        }
        RouteActionGate.Verdict verdict;
        try {
            verdict = gate.decide(request, kind == HandlerKind.RESOURCE);
        } catch (RuntimeException failure) {
            // The Spring MVC advice that answered a saturated connection pool 503 DATABASE_BUSY for a failure inside the
            // interceptor does not see a failure raised in the filter chain; answer the same here.
            if (hasCause(failure, SQLTransientConnectionException.class)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "DATABASE_BUSY");
                body.put("message", "database busy");
                return new Refusal(503, body);
            }
            throw failure;
        }
        if (verdict.permitted()) {
            verdict.attributes().forEach(request::setAttribute);
            return GRANTED;
        }
        return new Refusal(verdict.status(), verdict.body());
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }
}
