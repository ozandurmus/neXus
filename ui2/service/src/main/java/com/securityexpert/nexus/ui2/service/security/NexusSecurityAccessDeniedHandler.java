package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes a refusal of {@link NexusRouteActionAuthorizationManager} byte for byte as {@link GateChainInterceptor} wrote it: the
 * gate's own status, {@code Content-Type: application/json}, and the gate's JSON body. Nothing else is added: no redirect, no
 * Spring Security default page, no extra header.
 */
public final class NexusSecurityAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        int status = 403;
        Object body = Map.of("error", "ACTION_REFUSED");
        if (denied instanceof AuthorizationDeniedException authorizationDenied
                && authorizationDenied.getAuthorizationResult() instanceof NexusRouteActionAuthorizationManager.Refusal refusal) {
            status = refusal.status();
            body = refusal.body();
        }
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
