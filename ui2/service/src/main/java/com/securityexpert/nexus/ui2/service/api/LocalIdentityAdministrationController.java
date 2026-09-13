package com.securityexpert.nexus.ui2.service.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * 13G ({@code PO_DECISION_RECORD_2026_09_13G}) local identity
 * administration: one resource, body-only (no path variable, matching
 * {@code /role-bindings/revoke}'s own shape). Gated by
 * {@link GateChainInterceptor} -- reached only after {@code E1}-{@code E4}
 * required {@code role:security_admin} (LIA-5); this class performs no
 * authorization check of its own.
 *
 * <p>Role assignment/revocation is deliberately absent here (LIA-3.4): the
 * screen and the CLI reach that through the existing
 * {@code POST /role-bindings} / {@code POST /role-bindings/revoke} paths.</p>
 */
@RestController
public final class LocalIdentityAdministrationController {

    public record CreateRequest(
            @JsonProperty("local_identity_name") String localIdentityName,
            @JsonProperty("initial_password") char[] initialPassword) {
    }

    public record SetPasswordRequest(
            @JsonProperty("local_identity_id") String localIdentityId,
            @JsonProperty("new_password") char[] newPassword) {
    }

    public record IdentityRequest(@JsonProperty("local_identity_id") String localIdentityId) {
    }

    private final LocalIdentityAdministrationPort localIdentityAdministration;

    public LocalIdentityAdministrationController(LocalIdentityAdministrationPort localIdentityAdministration) {
        this.localIdentityAdministration = localIdentityAdministration;
    }

    @PostMapping("/local-identities")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingAdmin(servletRequest);
        char[] password = request.initialPassword();
        try {
            LocalIdentityAdministrationPort.LocalIdentityView view =
                    localIdentityAdministration.create(actorFingerprint, request.localIdentityName(), password);
            return ResponseEntity.ok(toBody(view));
        } finally {
            zero(password);
        }
    }

    @GetMapping("/local-identities")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> identities = localIdentityAdministration.list().stream()
                .map(LocalIdentityAdministrationController::toBody).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identities", identities);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/local-identities/set-password")
    public ResponseEntity<Map<String, Object>> setPassword(@RequestBody SetPasswordRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = actingAdmin(servletRequest);
        char[] password = request.newPassword();
        try {
            LocalIdentityAdministrationPort.MutationResult result =
                    localIdentityAdministration.setPassword(actorFingerprint, request.localIdentityId(), password);
            return respond(result);
        } finally {
            zero(password);
        }
    }

    @PostMapping("/local-identities/disable")
    public ResponseEntity<Map<String, Object>> disable(@RequestBody IdentityRequest request,
            HttpServletRequest servletRequest) {
        return respond(localIdentityAdministration.disable(actingAdmin(servletRequest), request.localIdentityId()));
    }

    @PostMapping("/local-identities/enable")
    public ResponseEntity<Map<String, Object>> enable(@RequestBody IdentityRequest request,
            HttpServletRequest servletRequest) {
        return respond(localIdentityAdministration.enable(actingAdmin(servletRequest), request.localIdentityId()));
    }

    private static void zero(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static String actingAdmin(HttpServletRequest servletRequest) {
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static ResponseEntity<Map<String, Object>> respond(LocalIdentityAdministrationPort.MutationResult result) {
        if (result instanceof LocalIdentityAdministrationPort.MutationResult.NotFound) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "LOCAL_IDENTITY_NOT_FOUND");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        if (result instanceof LocalIdentityAdministrationPort.MutationResult.LastSecurityAdminRefused) {
            // 13G LIA-3.5: distinct, non-identity-bearing -- names no identity.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "LAST_SECURITY_ADMIN_REFUSED");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        LocalIdentityAdministrationPort.MutationResult.Ok ok =
                (LocalIdentityAdministrationPort.MutationResult.Ok) result;
        return ResponseEntity.ok(toBody(ok.view()));
    }

    /** 13G section 3: exactly these fields -- never a verifier, salt, password, group reference or session token. */
    private static Map<String, Object> toBody(LocalIdentityAdministrationPort.LocalIdentityView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("local_identity_id", view.localIdentityId());
        body.put("local_identity_name", view.localIdentityName());
        body.put("enabled", view.enabled());
        body.put("must_change_password", view.mustChangePassword());
        body.put("created_at", view.createdAt().toString());
        body.put("password_set_at", view.passwordSetAt().toString());
        return body;
    }
}
