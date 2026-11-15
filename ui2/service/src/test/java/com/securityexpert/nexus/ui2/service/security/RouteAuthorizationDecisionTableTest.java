package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.ReflectionUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * Spring Security migration Step 5 (docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md): the route-to-action
 * authorization decision must not change by a single byte when it moves out of {@link GateChainInterceptor} into the Spring
 * Security {@code AuthorizationManager}. This table enumerates every route the controllers register (method and pattern,
 * HEAD for every GET, plus every mapped and explicitly ungated route and a few static-resource and unmapped targets) against
 * every session kind (anonymous, unknown cookie, expired, revoked, a role-less identity, each of the eight built-in roles, the
 * root identity, a machine session, a missing CSRF token, a missing Origin, a must-change-password identity) and records the
 * decision: permit (with the request attributes set for the controller) or refuse (status, content type, body), plus the
 * {@code authz_decisions} rows and machine-refusal audit rows the decision wrote.
 *
 * <p>{@code route-authorization-decision-table.golden.txt} was recorded from {@link GateChainInterceptor} <em>before</em> the
 * decision logic moved. Both implementations (the interceptor kept as the rollback path, and
 * {@link NexusRouteActionAuthorizationManager} behind the Spring Security filter chain) must reproduce it exactly. A change
 * of any line is a change of authorization behaviour: record a fresh transcript with {@code UI2_DECISION_TABLE_RECORD=<path>}
 * only for an intended change, and state the reason in the commit.</p>
 *
 * <p>Break-glass mode on or off does not enter this table on purpose: it changes who may <em>sign in</em>
 * ({@code LocalAuthenticationProvider}), not what an established session may do; {@link GateChain} never reads it. The two
 * booted-application goldens ({@code HttpSurfaceCharacterizationTest}) cover both modes end to end.</p>
 */
class RouteAuthorizationDecisionTableTest {

    static final String GOLDEN = "/authorization/route-authorization-decision-table.golden.txt";
    private static final String ORIGIN = "https://nexus.example.invalid";

    /** One session kind: the cookie it presents and how it behaves on state-changing requests. */
    record Kind(String name, String cookie, boolean csrf, boolean origin) {
    }

    /** A route to decide: the request, and whether Spring MVC would have handed it to the static-resource handler. */
    record Route(String method, String path, boolean resource) {
        String label() {
            return method + " " + path;
        }
    }

    record Verdict(String text) {
    }

    interface Driver {
        /** Decides one request exactly as the real dispatch would; fills the request attributes it sets on permit. */
        String decide(MockHttpServletRequest request, Route route, MockHttpServletResponse response) throws Exception;
    }

    // ------------------------------------------------------------------
    // The fixture: real GateChain and ActionRegistry over in-memory fakes
    // ------------------------------------------------------------------

    static final class Fixture {
        final ActionRegistry registry = new ActionRegistry();
        final Map<String, SessionRecord> sessionsByCookieHash = new LinkedHashMap<>();
        final Map<String, Set<String>> rolesByActor = new LinkedHashMap<>();
        final Set<String> rootActors = new LinkedHashSet<>();
        final List<String> authzRows = new ArrayList<>();
        final AtomicInteger machineAudits = new AtomicInteger();
        final List<Kind> kinds = new ArrayList<>();
        final GateChain gateChain;
        final LocalIdentityResolver identityResolver;
        final LocalRoleTokenResolver roleTokenResolver;

        Fixture() {
            Instant now = Instant.now();
            SessionRepository sessions = mock(SessionRepository.class);
            when(sessions.findBySessionId(anyString(), any(Instant.class)))
                    .thenAnswer(inv -> Optional.ofNullable(sessionsByCookieHash.get(inv.getArgument(0))));
            org.mockito.Mockito.doAnswer(inv -> {
                machineAudits.incrementAndGet();
                return null;
            }).when(sessions).auditMachineRefusal(anyString());

            AuthzDecisionRepository decisions = mock(AuthzDecisionRepository.class);
            when(decisions.insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                        authzRows.add(inv.getArgument(2) + ":" + inv.getArgument(4));
                        return 1L;
                    });

            RbacEvaluator rbac = mock(RbacEvaluator.class);
            when(rbac.evaluate(anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
                String actor = inv.getArgument(0);
                Optional<String> token = inv.getArgument(1);
                if (rootActors.contains(actor)) return RbacEvaluator.Decision.permittedRoot();
                if (token.isEmpty()) return RbacEvaluator.Decision.noApplicableAuthority();
                return rolesByActor.getOrDefault(actor, Set.of()).contains(token.get())
                        ? RbacEvaluator.Decision.permittedLocal("binding-" + token.get())
                        : RbacEvaluator.Decision.deniedLocalUnbound();
            });
            when(rbac.evaluateAny(anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
                String actor = inv.getArgument(0);
                Set<String> tokens = inv.getArgument(1);
                if (rootActors.contains(actor)) return RbacEvaluator.Decision.permittedRoot();
                for (String token : new TreeSet<>(tokens)) {
                    if (rolesByActor.getOrDefault(actor, Set.of()).contains(token)) {
                        return RbacEvaluator.Decision.permittedLocal("binding-" + token);
                    }
                }
                return RbacEvaluator.Decision.deniedLocalUnbound();
            });

            // One local identity per human actor, so the production-style replay-viewer lookup (resolver pair) is exercised.
            LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
            List<LocalCredentialRecord> records = new ArrayList<>();
            Map<String, List<String>> tokensByIdentity = new LinkedHashMap<>();

            addKind("anonymous", null, true, true);
            addKind("unknown-cookie", "cookie-unknown", true, true);
            addSession("expired", "cookie-expired", Set.of(RoleToken.SECURITY_ADMIN), false, false, SessionState.ACTIVE,
                    now.minusSeconds(60), now.plusSeconds(3600), true, true, records, tokensByIdentity);
            addSession("revoked", "cookie-revoked", Set.of(RoleToken.SECURITY_ADMIN), false, false, SessionState.REVOKED,
                    now.plusSeconds(3600), now.plusSeconds(7200), true, true, records, tokensByIdentity);
            addSession("no-role", "cookie-no-role", Set.of(), false, false, SessionState.ACTIVE,
                    now.plusSeconds(3600), now.plusSeconds(7200), true, true, records, tokensByIdentity);
            for (String role : RoleToken.values()) {
                String name = role.replace("role:", "role-");
                addSession(name, "cookie-" + name, Set.of(role), false, false, SessionState.ACTIVE,
                        now.plusSeconds(3600), now.plusSeconds(7200), true, true, records, tokensByIdentity);
            }
            addSession("root", "cookie-root", Set.of(), true, false, SessionState.ACTIVE,
                    now.plusSeconds(3600), now.plusSeconds(7200), true, true, records, tokensByIdentity);
            addSession("machine-replay-viewer", "cookie-machine", Set.of(RoleToken.REPLAY_VIEWER), false, true,
                    SessionState.ACTIVE, now.plusSeconds(3600), now.plusSeconds(7200), true, true, records, tokensByIdentity);
            addSession("viewer-no-csrf", "cookie-viewer-no-csrf", Set.of(RoleToken.VIEWER), false, false, SessionState.ACTIVE,
                    now.plusSeconds(3600), now.plusSeconds(7200), false, true, records, tokensByIdentity);
            addSession("viewer-no-origin", "cookie-viewer-no-origin", Set.of(RoleToken.VIEWER), false, false,
                    SessionState.ACTIVE, now.plusSeconds(3600), now.plusSeconds(7200), true, false, records, tokensByIdentity);
            // The must-change-password gate (switched on in this fixture) holds the identity to the password change path.
            addMustChange("must-change", "cookie-must-change", now, records, tokensByIdentity);

            when(credentials.findAll()).thenReturn(records);
            identityResolver = new LocalIdentityResolver(credentials);
            roleTokenResolver = mock(LocalRoleTokenResolver.class);
            when(roleTokenResolver.resolve(anyString()))
                    .thenAnswer(inv -> tokensByIdentity.getOrDefault(inv.getArgument(0), List.of()));
            gateChain = new GateChain(sessions, registry, rbac, decisions, credentials, true);
        }

        private void addKind(String name, String cookie, boolean csrf, boolean origin) {
            kinds.add(new Kind(name, cookie, csrf, origin));
        }

        private void addSession(String name, String cookie, Set<String> roles, boolean root, boolean machine,
                SessionState state, Instant idleDeadline, Instant absoluteDeadline, boolean csrf, boolean origin,
                List<LocalCredentialRecord> records, Map<String, List<String>> tokensByIdentity) {
            String actor = LocalMechanism.actorFingerprintFor("identity-" + name);
            Instant now = Instant.now();
            sessionsByCookieHash.put(SessionHasher.hash(cookie), new SessionRecord(SessionHasher.hash(cookie), actor,
                    "csrf-" + name, state, now.minusSeconds(600), now, idleDeadline, absoluteDeadline, Optional.empty(),
                    Optional.empty(), Optional.empty(), machine));
            rolesByActor.put(actor, roles);
            if (root) rootActors.add(actor);
            records.add(record("identity-" + name, false));
            tokensByIdentity.put("identity-" + name, List.copyOf(roles));
            addKind(name, cookie, csrf, origin);
        }

        private void addMustChange(String name, String cookie, Instant now, List<LocalCredentialRecord> records,
                Map<String, List<String>> tokensByIdentity) {
            String actor = LocalMechanism.actorFingerprintFor("identity-" + name);
            sessionsByCookieHash.put(SessionHasher.hash(cookie), new SessionRecord(SessionHasher.hash(cookie), actor,
                    "csrf-" + name, SessionState.ACTIVE, now.minusSeconds(600), now, now.plusSeconds(3600),
                    now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(), false));
            rolesByActor.put(actor, Set.of(RoleToken.SECURITY_ADMIN));
            records.add(record("identity-" + name, true));
            tokensByIdentity.put("identity-" + name, List.of(RoleToken.SECURITY_ADMIN));
            addKind(name, cookie, true, true);
        }

        private static LocalCredentialRecord record(String identityId, boolean mustChange) {
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            return new LocalCredentialRecord(identityId, identityId, new byte[0], new byte[0], "synthetic", 1, 1, 1, 0,
                    Optional.empty(), at, at, true, "synthetic-creator", at, mustChange);
        }
    }

    // ------------------------------------------------------------------
    // The routes
    // ------------------------------------------------------------------

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}");

    /** Every method and pattern the controllers register, plus the mapped and ungated routes, plus unmapped targets. */
    static List<Route> routes() throws Exception {
        Set<String> controllerRoutes = new TreeSet<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.securityexpert.nexus.ui2.service")) {
            // Test fixtures (a probe controller declared inside a test class) are not application routes.
            if (definition.getBeanClassName().contains("Test")) continue;
            Class<?> controller = Class.forName(definition.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            List<String> prefixes = classMapping == null || classMapping.path().length == 0 ? List.of("")
                    : List.of(classMapping.path());
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(controller)) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                List<String> paths = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
                Set<String> methods = new TreeSet<>();
                for (RequestMethod requestMethod : mapping.method()) methods.add(requestMethod.name());
                if (methods.isEmpty() && classMapping != null) {
                    for (RequestMethod requestMethod : classMapping.method()) methods.add(requestMethod.name());
                }
                if (methods.isEmpty()) methods.add("GET");
                if (methods.contains("GET")) methods.add("HEAD");
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        String joined = ("/" + prefix + "/" + path).replaceAll("/+", "/");
                        if (joined.length() > 1 && joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
                        String concrete = PATH_VARIABLE.matcher(joined).replaceAll("synthetic-ref");
                        for (String httpMethod : methods) controllerRoutes.add(httpMethod + " " + concrete);
                    }
                }
            }
        }
        // Every mapped route (its wildcard filled in) and every explicitly ungated route is a controller route too.
        for (String mapped : SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet()) {
            controllerRoutes.add(mapped.replace("*", "synthetic-ref"));
        }
        controllerRoutes.addAll(SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES);
        List<Route> routes = new ArrayList<>();
        for (String route : controllerRoutes) {
            String[] parts = route.split(" ", 2);
            routes.add(new Route(parts[0], parts[1], false));
        }
        // A trailing slash is stripped by the gate before the lookup.
        routes.add(new Route("GET", "/devices/", false));
        routes.add(new Route("POST", "/session/logout/", false));
        // Mapped path, method no controller serves for it: the dispatcher answers 405 before any gate runs, so these are
        // modelled as no-handler targets elsewhere; here the gate itself sees an unmapped controller route.
        routes.add(new Route("DELETE", "/devices/synthetic-ref/credential", false));
        routes.add(new Route("PATCH", "/jobs", false));
        // Static resources and paths no controller serves: Spring MVC hands these to the resource handler.
        for (String method : List.of("GET", "HEAD", "POST", "PUT", "DELETE")) {
            routes.add(new Route(method, "/api/v2/not-a-controller-route", true));
            routes.add(new Route(method, "/api/not-a-controller-route", true));
            routes.add(new Route(method, "/unknown-page", true));
        }
        routes.add(new Route("GET", "/index.html", true));
        routes.add(new Route("GET", "/assets/index-fixture0.js", true));
        routes.add(new Route("HEAD", "/assets/index-fixture0.js", true));
        routes.add(new Route("GET", "/favicon.ico", true));
        return routes;
    }

    // ------------------------------------------------------------------
    // The transcript
    // ------------------------------------------------------------------

    static MockHttpServletRequest requestFor(Kind kind, Route route, Fixture fixture) {
        var request = new MockHttpServletRequest(route.method(), route.path());
        request.setServletPath(route.path());
        if (kind.cookie() != null) request.setCookies(new Cookie("ui2_session", kind.cookie()));
        if (kind.csrf() && kind.cookie() != null) request.addHeader("X-CSRF-Token", "csrf-" + kind.name());
        if (kind.origin()) request.addHeader("Origin", ORIGIN);
        return request;
    }

    static String transcript(Driver driver, Fixture fixture) throws Exception {
        List<Route> routes = routes();
        StringBuilder out = new StringBuilder();
        out.append("# route authorization decision table; kinds=").append(fixture.kinds.size()).append(" routes=")
                .append(routes.size()).append(" rows=").append(routes.size() * fixture.kinds.size()).append('\n');
        out.append("# columns: kind | METHOD path | PERMIT attributes | REFUSE status content-type body ; then authz rows and machine audits\n");
        for (Route route : routes) {
            for (Kind kind : fixture.kinds) {
                fixture.authzRows.clear();
                fixture.machineAudits.set(0);
                var request = requestFor(kind, route, fixture);
                var response = new MockHttpServletResponse();
                String verdict = driver.decide(request, route, response);
                out.append(kind.name()).append(" | ").append(route.label()).append(" | ").append(verdict);
                if (!fixture.authzRows.isEmpty()) out.append(" | authz=").append(fixture.authzRows);
                if (fixture.machineAudits.get() > 0) out.append(" | machine-audits=").append(fixture.machineAudits.get());
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** What the controller would see after a permit, or exactly what the client would receive after a refusal. */
    static String describe(boolean permitted, MockHttpServletRequest request, MockHttpServletResponse response, Kind kind)
            throws Exception {
        if (permitted) {
            Object actor = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
            Object session = request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE);
            Object replay = request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE);
            String sessionText = session == null ? "-" : session.equals(SessionHasher.hash(kind.cookie())) ? "match" : "MISMATCH";
            String actorText = actor == null ? "-"
                    : actor.equals(LocalMechanism.actorFingerprintFor("identity-" + kind.name())) ? "match" : "MISMATCH";
            return "PERMIT actor=" + actorText + " session=" + sessionText + " replay-viewer=" + (replay == null ? "-" : replay);
        }
        return "REFUSE " + response.getStatus() + " " + response.getContentType() + " " + response.getContentAsString();
    }

    private static String golden() throws IOException {
        try (InputStream in = RouteAuthorizationDecisionTableTest.class.getResourceAsStream(GOLDEN)) {
            assertTrue(in != null, "golden file " + GOLDEN + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void compareOrRecord(String actual) throws IOException {
        String record = System.getenv("UI2_DECISION_TABLE_RECORD");
        if (record != null && !record.isBlank()) {
            Files.writeString(Path.of(record), actual);
            return;
        }
        String expected = golden();
        if (!expected.equals(actual)) {
            String[] want = expected.split("\n");
            String[] got = actual.split("\n");
            for (int i = 0; i < Math.max(want.length, got.length); i++) {
                String w = i < want.length ? want[i] : "<missing>";
                String g = i < got.length ? got[i] : "<missing>";
                assertEquals(w, g, "first differing decision row " + (i + 1));
            }
        }
        assertEquals(expected, actual);
    }

    // ------------------------------------------------------------------
    // The drivers
    // ------------------------------------------------------------------

    static Driver interceptorDriver(Fixture fixture) {
        var interceptor = new GateChainInterceptor(fixture.gateChain, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE,
                SecurityWebMvcConfig.EXPLICITLY_UNGATED_ROUTES, fixture.identityResolver, fixture.roleTokenResolver);
        return (request, route, response) -> {
            Object handler = route.resource() ? new ResourceHttpRequestHandler() : new Object();
            boolean permitted = interceptor.preHandle(request, response, handler);
            return describe(permitted, request, response, kindOf(request, fixture));
        };
    }

    /** The Spring Security path: the manager decides, a refusal is written by the access-denied handler. */
    static Driver managerDriver(Fixture fixture) {
        var manager = new NexusRouteActionAuthorizationManager(SecurityWebMvcConfig.routeGate(fixture.gateChain,
                fixture.identityResolver, fixture.roleTokenResolver), request -> {
                    // the table's own knowledge of what the dispatcher would select for each target
                    boolean resource = request.getServletPath().startsWith("/api/v2/not-a-controller-route")
                            || request.getServletPath().startsWith("/api/not-a-controller-route")
                            || request.getServletPath().equals("/unknown-page") || request.getServletPath().equals("/index.html")
                            || request.getServletPath().startsWith("/assets/") || request.getServletPath().equals("/favicon.ico");
                    return resource ? NexusRouteActionAuthorizationManager.HandlerKind.RESOURCE
                            : NexusRouteActionAuthorizationManager.HandlerKind.CONTROLLER;
                });
        var handler = new NexusSecurityAccessDeniedHandler();
        return (request, route, response) -> {
            var result = manager.authorize(() -> null, new org.springframework.security.web.access.intercept.RequestAuthorizationContext(request));
            boolean permitted = result.isGranted();
            if (!permitted) {
                handler.handle(request, response, new org.springframework.security.authorization.AuthorizationDeniedException("denied", result));
            }
            return describe(permitted, request, response, kindOf(request, fixture));
        };
    }

    static Kind kindOf(MockHttpServletRequest request, Fixture fixture) {
        Cookie[] cookies = request.getCookies();
        String cookie = cookies == null ? null : cookies[0].getValue();
        return fixture.kinds.stream().filter(k -> java.util.Objects.equals(k.cookie(), cookie)).findFirst().orElseThrow();
    }

    @Test
    void theInterceptorReproducesTheCommittedDecisionTable() throws Exception {
        var fixture = new Fixture();
        compareOrRecord(transcript(interceptorDriver(fixture), fixture));
    }

    @Test
    void theSpringSecurityManagerReproducesTheCommittedDecisionTable() throws Exception {
        var fixture = new Fixture();
        compareOrRecord(transcript(managerDriver(fixture), fixture));
    }
}
