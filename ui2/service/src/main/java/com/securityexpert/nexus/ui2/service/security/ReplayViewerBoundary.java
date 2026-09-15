package com.securityexpert.nexus.ui2.service.security;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.securityexpert.nexus.ui2.persistence.identity.ReplaySessionRepository;
import com.securityexpert.nexus.ui2.service.api.DeviceRegistrationController;
import com.securityexpert.nexus.ui2.service.device.DeviceQueryService;

/** One C9 funnel after E4: a closed route set, local opaque lookup, and projection before serialization. */
@RestControllerAdvice
public final class ReplayViewerBoundary implements ResponseBodyAdvice<Object> {
    private static final String PROJECTOR_ATTRIBUTE = "ui2.replay.projector";
    private final ReplaySessionRepository sessions;
    private final DeviceQueryService devices;

    public ReplayViewerBoundary(ReplaySessionRepository sessions, DeviceQueryService devices) {
        this.sessions = sessions;
        this.devices = devices;
    }

    public boolean isReplay(String sessionId) {
        return sessions.isReplay(sessionId);
    }

    public boolean afterGate(HttpServletRequest request) {
        if ("POST".equals(request.getMethod())
                && "/session/replay/deactivate".equals(request.getServletPath())) {
            return true;
        }
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        if (!path.equals("/devices") && !path.matches("/devices/[^/]+")) {
            return false;
        }
        ReplayProjector projector = new ReplayProjector(sessions.projectionKey());
        request.setAttribute(PROJECTOR_ATTRIBUTE, projector);
        if (!path.equals("/devices")) {
            Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (!(variables instanceof Map<?, ?> original) || !(original.get("deviceId") instanceof String requested)) {
                return false;
            }
            // ponytail: local O(n) lookup; add a server-only index if the v1 device list becomes large.
            String rawId = devices.listDevices().stream().map(device -> device.deviceId())
                    .filter(id -> projector.pseudonym("device", id).equals(requested))
                    .findFirst().orElse(null);
            if (rawId == null) {
                return false; // Raw identities and unknown pseudonyms never become a raw-value lookup oracle.
            }
            Map<String, Object> rewritten = new LinkedHashMap<>();
            original.forEach((key, value) -> rewritten.put(key.toString(), value));
            rewritten.put("deviceId", rawId);
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, rewritten);
        }
        return true;
    }

    public static Map<String, Object> refusal() {
        return Map.of("error", "ACTION_REFUSED", "outcome", "DENIED", "reason_code", "replay_surface_refused");
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        Object value = servlet.getServletRequest().getAttribute(PROJECTOR_ATTRIBUTE);
        if (!(value instanceof ReplayProjector projector)) {
            return body;
        }
        try {
            if (returnType.getContainingClass() != DeviceRegistrationController.class) {
                throw new IllegalArgumentException("unclassified replay handler");
            }
            return projector.project(body);
        } catch (RuntimeException failure) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return refusal();
        }
    }
}
