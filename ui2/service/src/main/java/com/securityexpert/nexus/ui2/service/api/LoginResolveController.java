package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.LoginFlow;

/**
 * {@code POST /login/resolve} (C3 §3.4). Never gated by {@code E1}-{@code E6}
 * — a {@code conflict_token}, not a session cookie, authorizes this call.
 */
@RestController
public final class LoginResolveController {

    public record ResolveRequest(String conflictToken, String action) {
    }

    private final LoginFlow loginFlow;

    public LoginResolveController(LoginFlow loginFlow) {
        this.loginFlow = loginFlow;
    }

    @PostMapping("/login/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestBody ResolveRequest request,
            HttpServletResponse response) {
        LoginFlow.ResolveResult result = loginFlow.resolve(request.conflictToken(), request.action(), Instant.now());

        if (result instanceof LoginFlow.ResolveResult.Refused refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "LOGIN_REFUSED_ACTIVE_SESSION");
            body.put("reason_code", refused.reasonCode());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        LoginFlow.ResolveResult.TakenOver takenOver = (LoginFlow.ResolveResult.TakenOver) result;
        LoginController.setSessionCookie(response, takenOver.rawCookieValue());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
    }
}
