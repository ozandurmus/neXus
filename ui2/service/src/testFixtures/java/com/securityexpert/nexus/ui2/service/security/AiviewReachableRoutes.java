package com.securityexpert.nexus.ui2.service.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * The one enumeration of the service's routes and of which of them the aiview persona ({@code role:replay_viewer}) may
 * call, shared by the static masking leak audit ({@code AiviewMaskingLeakAuditTest}, service tests) and the HTTP
 * raw-versus-masked sweep ({@code AiviewRawVersusMaskedSweepTest}, integration tests), so the two can never audit
 * different route sets.
 */
public final class AiviewReachableRoutes {

    /**
     * One mapped route. {@code key} is {@code "GET /a/*}/b"}-shaped (path variables as {@code *}); {@code pathTemplate}
     * keeps the variable names ({@code /a/{device_id}/b}) so a caller can substitute real ids.
     */
    public record Endpoint(String key, Method handler, String actionId, String method, String pathTemplate) {
    }

    private AiviewReachableRoutes() {
    }

    /** Every mapped route of every controller in the service, with its gate action (null when the route is ungated). */
    public static List<Endpoint> all() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
        List<Endpoint> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.securityexpert.nexus.ui2.service")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping cls = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = cls == null || cls.path().length == 0 ? new String[] {""} : cls.path();
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] paths = rm.path().length == 0 ? new String[] {""} : rm.path();
                for (var verb : rm.method()) {
                    for (String p : prefixes) for (String q : paths) {
                        String template = (p + q).replaceAll("\\{[^}/]+}", "*");
                        String action = actionFor(verb.name(), template);
                        out.add(new Endpoint(verb.name() + " " + template, m, action, verb.name(), p + q));
                    }
                }
            }
        }
        return out;
    }

    /** The routes whose gate action the aiview persona may call. */
    public static List<Endpoint> reachable() throws Exception {
        return all().stream().filter(e -> e.actionId() != null && aiviewMayCall(e.actionId())).toList();
    }

    public static String actionFor(String method, String path) {
        var routes = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE;
        String exact = routes.get(method + " " + path);
        if (exact != null) return exact;
        String[] seg = path.split("/", -1);
        for (int i = seg.length - 1; i >= 1; i--) {
            if (seg[i].isEmpty()) continue;
            String[] w = seg.clone();
            w[i] = "*";
            String c = routes.get(method + " " + String.join("/", w));
            if (c != null) return c;
        }
        return null;
    }

    public static boolean aiviewMayCall(String actionId) {
        var d = new ActionRegistry().find(actionId);
        if (d.isEmpty()) return false;
        return d.get().requiredRoleToken().isEmpty() || d.get().requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER);
    }
}
