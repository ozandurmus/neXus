package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    public record CreateRequest(@JsonProperty("role_token") String roleToken,
            @JsonProperty("selection_handle") String selectionHandle,
            @JsonProperty("directory_profile_id") String directoryProfileId,
            @JsonProperty("binding_kind") DirectoryBindingKind bindingKind,
            @JsonProperty("local_identity_id") String localIdentityId,
            @JsonProperty("group_reference") String groupReference,
            @JsonProperty("group_reference_key_id") String groupReferenceKeyId) {
        public CreateRequest(String roleToken, String groupReference, String groupReferenceKeyId) {
            this(roleToken, null, null, null, null, groupReference, groupReferenceKeyId);
        }
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
        if (request.bindingKind() == null || request.bindingKind() == DirectoryBindingKind.LEGACY) {
            // Existing local UI submits its known local id in the old field; server proves that namespace.
            // It cannot submit a directory reference through this compatibility branch.
            if (request.selectionHandle() != null) return respond(new RoleBindingAdminService.Outcome.NotEvaluated());
            return respond(roleBindingAdminService.createLocal(actorFingerprint,
                    (String) servletRequest.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE), request.roleToken(),
                    request.localIdentityId() == null ? request.groupReference() : request.localIdentityId(), Instant.now()));
        }
        if (request.groupReference() != null || request.localIdentityId() != null) {
            return respond(new RoleBindingAdminService.Outcome.NotEvaluated());
        }
        RoleBindingAdminService.Outcome outcome = roleBindingAdminService.createDirectory(actorFingerprint,
                (String) servletRequest.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE), request.roleToken(),
                request.selectionHandle(), request.directoryProfileId(), request.bindingKind(), Instant.now());
        return respond(outcome);
    }

    @PostMapping("/role-bindings/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@RequestBody RevokeRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        RoleBindingAdminService.Outcome outcome =
                roleBindingAdminService.revoke(actorFingerprint,
                        (String) servletRequest.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE), request.bindingId(), Instant.now());
        return respond(outcome);
    }

    public record SelectionRequest(String directoryProfileId, DirectoryBindingKind bindingKind) { }

    @PostMapping("/role-bindings/selections")
    public List<RoleBindingAdminService.SelectionView> selections(@RequestBody SelectionRequest request,
            HttpServletRequest servletRequest) {
        return roleBindingAdminService.resolveSelections(
                (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE),
                (String) servletRequest.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE),
                request.directoryProfileId(), request.bindingKind(), Instant.now());
    }

    private static ResponseEntity<Map<String, Object>> respond(RoleBindingAdminService.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome instanceof RoleBindingAdminService.Outcome.NotEvaluated) {
            body.put("error", "AUTHZ_NOT_EVALUATED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (outcome instanceof RoleBindingAdminService.Outcome.SelfGrantRefused) {
            body.put("error", "SELF_GRANT_REFUSED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        if (outcome instanceof RoleBindingAdminService.Outcome.LastSecurityAdminRefused) {
            // 13G LIA-3.5: distinct, non-identity-bearing -- names no
            // binding, no identity, no group reference.
            body.put("error", "LAST_SECURITY_ADMIN_REFUSED");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
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
