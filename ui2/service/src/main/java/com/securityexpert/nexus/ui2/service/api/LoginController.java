package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Mechanism;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.MechanismRegistry;

/**
 * {@code POST /login} (C3A contract §2.2: {@code {identity, credential,
 * mechanism_id}}, the operator's own explicit mechanism selection -- never
 * a server-side fallback sequence). Never runs the {@code E1}-{@code E6}
 * chain — there is no session yet; this endpoint is where one gets
 * created. The response shape branches only on {@link AttemptOutcome}'s
 * type (§2.3), never on {@code mechanism_id}, which is what makes it
 * mechanism-independent.
 */
@RestController
public final class LoginController {

    public record LoginRequest(String username, char[] password, @JsonProperty("mechanism_id") String mechanismId) {
    }

    private final MechanismRegistry mechanismRegistry;
    private final LoginFlow loginFlow;
    private final com.securityexpert.nexus.ui2.service.security.LoginAttemptThrottle directoryThrottle = new com.securityexpert.nexus.ui2.service.security.LoginAttemptThrottle();

    public LoginController(MechanismRegistry mechanismRegistry, LoginFlow loginFlow) {
        this.mechanismRegistry = mechanismRegistry;
        this.loginFlow = loginFlow;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request,
            HttpServletResponse response, jakarta.servlet.http.HttpServletRequest servletRequest) {
        try {
            String mechId = request.mechanismId() != null && !request.mechanismId().isBlank() ? request.mechanismId() : "local";
            Optional<Mechanism> mechanism = mechanismRegistry.find(mechId);
            if (mechanism.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody("UNKNOWN_MECHANISM"));
            }

            boolean directory = "ldap".equals(mechanism.get().mechanismId());
            if (directory && !directoryThrottle.admit(servletRequest == null ? "unknown" : servletRequest.getRemoteAddr(),
                    request.username(), Instant.now())) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorBody("LOGIN_RATE_LIMITED"));
            }
            AttemptOutcome outcome = mechanism.get().attempt(request.username(), request.password());

            if (outcome instanceof AttemptOutcome.MechanismUnavailable) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody("DIRECTORY_UNAVAILABLE"));
            }
            if (outcome instanceof AttemptOutcome.Refused) {
                // §2.3/§5.3: the identical body regardless of mechanism or
                // reason -- reasonCode is internal only and never read here.
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("INVALID_CREDENTIALS"));
            }
            if (directory) directoryThrottle.succeeded(request.username());
            AttemptOutcome.Success success = (AttemptOutcome.Success) outcome;
            LoginFlow.LoginResult result = loginFlow.login(success, Instant.now());

            if (result instanceof LoginFlow.LoginResult.DirectoryUnavailable) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody("DIRECTORY_UNAVAILABLE"));
            }
            if (result instanceof LoginFlow.LoginResult.Conflict conflict) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(LoginFlow.conflictBody(conflict));
            }
            LoginFlow.LoginResult.NewSession newSession = (LoginFlow.LoginResult.NewSession) result;
            setSessionCookie(response, newSession.rawCookieValue());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            return ResponseEntity.ok(body);
        } finally {
            if (request.password() != null) java.util.Arrays.fill(request.password(), '\0');
        }
    }

    public ResponseEntity<Map<String, Object>> login(LoginRequest request, HttpServletResponse response) {
        return login(request, response, null);
    }

    static void setSessionCookie(HttpServletResponse response, String rawCookieValue) {
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("ui2_session", rawCookieValue);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    private static Map<String, Object> errorBody(String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
