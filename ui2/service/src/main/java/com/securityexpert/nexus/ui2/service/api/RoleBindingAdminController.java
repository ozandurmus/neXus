package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RoleBindingAdminService;

/**
 * {@code role_bindings} administration (C3 §4.3). Gated by
 * {@link GateChainInterceptor} — reached only after {@code E1}-{@code E4}
 * required {@code role:security_admin} (contract §3 placement row: no
 * UnboundID SDK type appears here, {@code DIR-5}).
 */
@RestController
public final class RoleBindingAdminController {

    public record CreateRequest(String roleToken, String groupReference, String groupReferenceKeyId) {
    }

    public record RevokeRequest(String bindingId) {
    }

    private final RoleBindingAdminService roleBindingAdminService;

    public RoleBindingAdminController(RoleBindingAdminService roleBindingAdminService) {
        this.roleBindingAdminService = roleBindingAdminService;
    }

    @PostMapping("/role-bindings")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        RoleBindingAdminService.Outcome outcome = roleBindingAdminService.create(actorFingerprint,
                request.roleToken(), request.groupReference(), request.groupReferenceKeyId(), Instant.now());
        return respond(outcome);
    }

    @PostMapping("/role-bindings/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@RequestBody RevokeRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        RoleBindingAdminService.Outcome outcome =
                roleBindingAdminService.revoke(actorFingerprint, request.bindingId(), Instant.now());
        return respond(outcome);
    }

    private static ResponseEntity<Map<String, Object>> respond(RoleBindingAdminService.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome instanceof RoleBindingAdminService.Outcome.SelfGrantRefused) {
            body.put("error", "SELF_GRANT_REFUSED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (outcome instanceof RoleBindingAdminService.Outcome.Created created) {
            body.put("binding_id", created.bindingId());
            return ResponseEntity.ok(body);
        }
        RoleBindingAdminService.Outcome.Revoked revoked = (RoleBindingAdminService.Outcome.Revoked) outcome;
        body.put("binding_id", revoked.bindingId());
        return ResponseEntity.ok(body);
    }
}
