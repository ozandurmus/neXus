package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The answer for an unauthenticated request Spring Security itself turns away: {@code 401 SESSION_INVALID} JSON, the answer
 * {@link GateChain} E1 gives. Not reachable today (anonymous authentication is off and the route decision refuses through
 * {@link NexusSecurityAccessDeniedHandler}); it is set so that no Spring default (redirect or {@code WWW-Authenticate}) can
 * ever replace a neXus answer.
 */
public final class NexusSecurityAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), Map.of("error", "SESSION_INVALID"));
    }
}
