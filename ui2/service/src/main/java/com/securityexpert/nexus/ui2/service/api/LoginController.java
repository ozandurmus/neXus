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

import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;

/**
 * {@code POST /login} (contract §3 placement row: {@code service.api},
 * never a UnboundID SDK type directly, {@code DIR-5}). Never runs the
 * {@code E1}-{@code E6} chain — there is no session yet; this endpoint is
 * where one gets created.
 */
@RestController
public final class LoginController {

    public record LoginRequest(String username, char[] password) {
    }

    private final LdapOperatorBindPort ldapOperatorBindPort;
    private final LoginFlow loginFlow;

    public LoginController(LdapOperatorBindPort ldapOperatorBindPort, LoginFlow loginFlow) {
        this.ldapOperatorBindPort = ldapOperatorBindPort;
        this.loginFlow = loginFlow;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request,
            HttpServletResponse response) {
        Result<LdapOperatorBindPort.OperatorBindOutcome> bindResult =
                ldapOperatorBindPort.bind(request.username(), request.password());

        if (bindResult instanceof Result.Err<LdapOperatorBindPort.OperatorBindOutcome> err) {
            return switch (err.code()) {
                case LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE ->
                        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(errorBody("DIRECTORY_UNAVAILABLE"));
                default -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("INVALID_CREDENTIALS"));
            };
        }

        LdapOperatorBindPort.OperatorBindOutcome outcome = ((Result.Ok<LdapOperatorBindPort.OperatorBindOutcome>) bindResult).value();
        LoginFlow.LoginResult result = loginFlow.login(outcome.actorFingerprint(), Instant.now());

        if (result instanceof LoginFlow.LoginResult.Conflict conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(LoginFlow.conflictBody(conflict));
        }
        LoginFlow.LoginResult.NewSession newSession = (LoginFlow.LoginResult.NewSession) result;
        setSessionCookie(response, newSession.rawCookieValue());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
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
