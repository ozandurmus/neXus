package com.securityexpert.nexus.ui2.service.security;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/**
 * Opens the administrator's manual-submission grant ({@link ManualAdmission}) for one mutating request, after
 * {@link GateChainInterceptor} has authenticated and authorized it, and always closes it when the request completes.
 * The acknowledgement is the header {@link ManualAdmission#ACK_HEADER}{@code : true} or the query parameter
 * {@code business_hours_ack=true}; it is read from the request line and headers only, never from the body (a
 * multipart upload must not be spooled before authorization). Safe methods never get a grant; a replay viewer never
 * does either.
 */
public final class ManualAdmissionInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AdminWindowBypass bypass;

    public ManualAdmissionInterceptor(AdminWindowBypass bypass) {
        this.bypass = java.util.Objects.requireNonNull(bypass, "bypass");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ManualAdmission.close();
        if (SAFE_METHODS.contains(request.getMethod())) return true;
        if (Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE))) return true;
        Object actor = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (!(actor instanceof String fingerprint)) return true;
        bypass.role(fingerprint).ifPresent(role ->
                ManualAdmission.open(new ManualAdmission.Grant(fingerprint, role, acknowledged(request))));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ManualAdmission.close();
    }

    static boolean acknowledged(HttpServletRequest request) {
        if ("true".equalsIgnoreCase(String.valueOf(request.getHeader(ManualAdmission.ACK_HEADER)).trim())) return true;
        String query = request.getQueryString();
        if (query == null) return false;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String name = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (ManualAdmission.ACK_PARAMETER.equals(name) && "true".equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
