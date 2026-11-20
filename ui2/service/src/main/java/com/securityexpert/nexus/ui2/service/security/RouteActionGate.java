package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The route-to-action decision, in one place: resolves the request's {@code action_id} from the route map (exact route, then
 * one wildcarded segment at a time, last segment inward), refuses a route that has neither an action nor an explicit
 * exemption ({@code 403 ACTION_MAPPING_REQUIRED}), refuses a machine-session write first, and otherwise runs {@link GateChain}
 * (E1 to E6). It is servlet-container free apart from reading the request, and writes nothing: the caller turns the
 * {@link Verdict} into a response.
 *
 * <p>Migration plan Step 5: {@link NexusRouteActionAuthorizationManager} (Spring Security) and, as the rollback path,
 * {@link GateChainInterceptor} (Spring MVC) both call {@link #decide}, so the route map ({@code ActionRegistry} actions through
 * {@code SecurityWebMvcConfig.ACTION_ID_BY_ROUTE}) and the decision exist once. The caller says whether Spring MVC selected a
 * static-resource handler for the request: static files carry no product action, but anything under {@code /api/} that is not a
 * controller is refused.</p>
 */
public final class RouteActionGate {

    /** A permit carries the request attributes the controller reads; a refusal carries the response to write. */
    public record Verdict(boolean permitted, int status, Object body, Map<String, Object> attributes) {

        static Verdict permit(Map<String, Object> attributes) {
            return new Verdict(true, 0, null, attributes);
        }

        static Verdict refuse(int status, Object body) {
            return new Verdict(false, status, body, Map.of());
        }
    }

    private record ReplayViewerAnswer(boolean value, Instant until) {
    }

    private final java.util.concurrent.ConcurrentHashMap<Object, ReplayViewerAnswer> replayViewerBySession =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final GateChain gateChain;
    private final Map<String, String> actionIdByRoute;
    private final Set<String> explicitlyUngatedRoutes;
    private final LocalIdentityResolver localIdentityResolver;
    private final LocalRoleTokenResolver localRoleTokenResolver;

    public RouteActionGate(GateChain gateChain, Map<String, String> actionIdByRoute, Set<String> explicitlyUngatedRoutes,
            LocalIdentityResolver localIdentityResolver, LocalRoleTokenResolver localRoleTokenResolver) {
        this.gateChain = gateChain;
        this.actionIdByRoute = actionIdByRoute;
        this.explicitlyUngatedRoutes = explicitlyUngatedRoutes;
        this.localIdentityResolver = localIdentityResolver;
        this.localRoleTokenResolver = localRoleTokenResolver;
    }

    public Verdict decide(HttpServletRequest request, boolean resourceHandler) {
        String path = (request.getServletPath().length() > 1 && request.getServletPath().endsWith("/"))
                ? request.getServletPath().substring(0, request.getServletPath().length() - 1)
                : request.getServletPath();
        Optional<GateOutcome.Refused> machineRefusal = gateChain == null ? Optional.empty()
                : gateChain.machineWriteRefusal(findSessionCookie(request), request.getMethod(), Instant.now(),
                        actionIdFor(request.getMethod(), path));
        if (machineRefusal.isPresent()) {
            return Verdict.refuse(403, machineRefusal.get().body());
        }
        // Static resources are selected by Spring's resource handler, not a controller. They carry no product action and
        // must not need RBAC.
        if (resourceHandler) {
            if (request.getServletPath().startsWith("/api/")) {
                return Verdict.refuse(403, Map.of("error", "ACTION_MAPPING_REQUIRED"));
            }
            return Verdict.permit(Map.of());
        }
        String actionId = actionIdFor(request.getMethod(), path);
        if (actionId == null) {
            if (explicitlyUngatedRoutes.contains(request.getMethod() + " " + path)) {
                return Verdict.permit(Map.of());
            }
            return Verdict.refuse(403, Map.of("error", "ACTION_MAPPING_REQUIRED"));
        }

        GateRequest gateRequest = new GateRequest(
                request.getMethod(),
                findSessionCookie(request),
                Optional.ofNullable(request.getHeader("X-CSRF-Token")),
                Optional.ofNullable(request.getHeader("Origin")),
                actionId,
                ActionRegistry.JOB_TRANSCRIPT_READ.equals(actionId)
                        ? Optional.of(path.split("/")[2])
                        // getParameter can make the servlet container spool a multipart file before authorization.
                        : ActionRegistry.POLICY_OFFLINE_WRITE.equals(actionId)
                                || ActionRegistry.CUTOVER_ASSESSMENT_WRITE.equals(actionId)
                                || ActionRegistry.PACKET_ANALYSIS_RUN.equals(actionId)
                                || ActionRegistry.DATA_SANITIZE_RUN.equals(actionId)
                                || ActionRegistry.DATA_SANITIZE_REVERT.equals(actionId)
                                || ActionRegistry.SCRIPT_WRITE.equals(actionId) ? Optional.empty()
                        : Optional.ofNullable(request.getParameter("target_ref")));

        GateOutcome outcome = gateChain.evaluate(gateRequest, Instant.now());
        if (outcome instanceof GateOutcome.Proceed proceed) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, proceed.actorFingerprint());
            attributes.put(GateChainInterceptor.SESSION_ID_ATTRIBUTE, proceed.sessionId());
            if (gateChain != null || (localIdentityResolver != null && localRoleTokenResolver != null)) {
                // The replay-viewer answer per session, kept 60 s: two lookups on every request otherwise.
                ReplayViewerAnswer cached = replayViewerBySession.get(proceed.sessionId());
                boolean replayViewer;
                if (cached != null && cached.until().isAfter(Instant.now())) {
                    replayViewer = cached.value();
                } else {
                    replayViewer = false;
                    try {
                        if (localIdentityResolver != null && localRoleTokenResolver != null) {
                            var identity = localIdentityResolver.resolve(proceed.actorFingerprint());
                            if (identity.isPresent()) {
                                replayViewer = localRoleTokenResolver.resolve(identity.get().localIdentityId())
                                        .contains(com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER);
                            } else if (gateChain != null) {
                                replayViewer = gateChain.isReplayViewer(proceed.actorFingerprint());
                            }
                        } else if (gateChain != null) {
                            replayViewer = gateChain.isReplayViewer(proceed.actorFingerprint());
                        }
                        if (replayViewerBySession.size() > 10_000) {
                            replayViewerBySession.clear();
                        }
                        replayViewerBySession.put(proceed.sessionId(), new ReplayViewerAnswer(replayViewer, Instant.now().plusSeconds(60)));
                    } catch (Exception ignored) {
                        // fail toward masking: an unresolvable identity is treated as a replay viewer
                        replayViewer = true;
                    }
                }
                if (replayViewer) {
                    attributes.put(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
                }
            }
            return Verdict.permit(attributes);
        }
        GateOutcome.Refused refused = (GateOutcome.Refused) outcome;
        return Verdict.refuse(refused.httpStatus(), refused.body());
    }

    String actionIdFor(String method, String servletPath) {
        String exact = actionIdByRoute.get(method + " " + servletPath);
        if (exact != null) {
            return exact;
        }
        String[] segments = servletPath.split("/", -1);
        for (int i = segments.length - 1; i >= 1; i--) {
            if (segments[i].isEmpty()) {
                continue;
            }
            String[] wildcarded = segments.clone();
            wildcarded[i] = "*";
            String candidate = actionIdByRoute.get(method + " " + String.join("/", wildcarded));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
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
