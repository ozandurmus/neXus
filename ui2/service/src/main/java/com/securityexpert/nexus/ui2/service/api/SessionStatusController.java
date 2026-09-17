package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.SessionHasher;

/**
 * {@code GET /session/status}: the frontend's own pre-authorization check
 * ("the application must not render any product screen until a session
 * exists", this movement's brief / C3A contract §2's flow). Deliberately
 * absent from {@code SecurityWebMvcConfig}'s route map, exactly like
 * {@code /login} and {@code /login/resolve}: it runs before -- and instead
 * of -- the {@code E1}-{@code E6} chain, since its entire purpose is to
 * answer "is there a session" for a caller that does not yet know.
 *
 * <p>This performs only session-validity reads ({@code E1}'s own checks,
 * duplicated in miniature rather than reused, since {@link com.securityexpert.nexus.ui2.service.security.GateChain}
 * always continues into {@code E2}'s action lookup) -- it mutates nothing
 * and requires no RBAC decision.</p>
 */
@RestController
public final class SessionStatusController {

    private static final String SESSION_COOKIE_NAME = "ui2_session";

    private final SessionRepository sessionRepository;
    private final LocalIdentityResolver localIdentityResolver;
    private final LocalRoleTokenResolver localRoleTokenResolver;
    private final boolean enforcePasswordChangeOnFirstLogin;
    private final com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository actorAuthzStateRepository;
    private final com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository roleBindingRepository;
    private final com.securityexpert.nexus.ui2.platform.GroupReferenceCipher groupReferenceCipher;
    private final com.securityexpert.nexus.ui2.persistence.identity.RbacRoleRepository rbacRoleRepository;

    public SessionStatusController(SessionRepository sessionRepository, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver,
            @Value("${ui2.local-auth.enforce-password-change-on-first-login:false}") boolean enforcePasswordChangeOnFirstLogin) {
        this(sessionRepository, localIdentityResolver, localRoleTokenResolver, enforcePasswordChangeOnFirstLogin,
                null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SessionStatusController(SessionRepository sessionRepository, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver,
            @Value("${ui2.local-auth.enforce-password-change-on-first-login:false}") boolean enforcePasswordChangeOnFirstLogin,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository actorAuthzStateRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository roleBindingRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.securityexpert.nexus.ui2.platform.GroupReferenceCipher groupReferenceCipher,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.securityexpert.nexus.ui2.persistence.identity.RbacRoleRepository rbacRoleRepository) {
        this.sessionRepository = sessionRepository;
        this.localIdentityResolver = localIdentityResolver;
        this.localRoleTokenResolver = localRoleTokenResolver;
        this.enforcePasswordChangeOnFirstLogin = enforcePasswordChangeOnFirstLogin;
        this.actorAuthzStateRepository = actorAuthzStateRepository;
        this.roleBindingRepository = roleBindingRepository;
        this.groupReferenceCipher = groupReferenceCipher;
        this.rbacRoleRepository = rbacRoleRepository;
    }

    @GetMapping("/session/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Optional<String> rawCookie = findSessionCookie(request);
        Optional<SessionRecord> session = rawCookie.flatMap(this::activeSession);

        Map<String, Object> body = new LinkedHashMap<>();
        if (session.isEmpty()) {
            body.put("authenticated", false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .body(body);
        }

        body.put("authenticated", true);
        body.put("csrf_token", session.get().csrfSecret());
        // NXS-LOCAL-0152 AC-3: display name, resolved role tokens, and the
        // must-change-password flag -- no credential material, no verifier,
        // no group reference, no internal identifier beyond what a local
        // identity's own display name already is.
        Optional<LocalCredentialRecord> identity = localIdentityResolver.resolve(session.get().actorFingerprint());
        java.util.Set<String> planes = new java.util.LinkedHashSet<>();
        if (identity.isPresent()) {
            LocalCredentialRecord record = identity.get();
            body.put("display_name", record.localIdentityName());
            // PO directive 2026-09-14: the flag is only surfaced (and the screen only forced) when the posture is on.
            body.put("must_change_password", enforcePasswordChangeOnFirstLogin && record.mustChangePassword());
            List<String> roleTokens = localRoleTokenResolver.resolve(record.localIdentityId()).stream()
                    .toList();
            body.put("role_tokens", roleTokens);
            collectPlanes(roleTokens, planes);
        } else if (actorAuthzStateRepository != null && roleBindingRepository != null) {
            var authzState = actorAuthzStateRepository.find(session.get().actorFingerprint());
            if (authzState.isPresent()) {
                var state = authzState.get();
                String displayName = "Directory User";
                if (state.hasDirectoryProof() && groupReferenceCipher != null) {
                    try {
                        displayName = groupReferenceCipher.decryptDirectory(state.principalReferenceEncrypted(),
                                state.directoryProfileId(), com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_PRINCIPAL,
                                state.principalReferenceKeyId());
                    } catch (Exception ignored) { }
                }
                body.put("display_name", displayName);
                body.put("must_change_password", false);
                List<String> roleTokens = new java.util.ArrayList<>();
                for (var binding : roleBindingRepository.findAllActive()) {
                    if (binding.bindingKind() == com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_GROUP) {
                        try {
                            String group = groupReferenceCipher.decryptDirectory(binding.groupReferenceEncrypted(),
                                    binding.directoryProfileId(), binding.bindingKind(), binding.groupReferenceKeyId());
                            if (state.groupReferences().contains(group)) {
                                roleTokens.add(binding.roleToken());
                            }
                        } catch (Exception ignored) { }
                    } else if (binding.bindingKind() == com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_PRINCIPAL) {
                        try {
                            String principal = groupReferenceCipher.decryptDirectory(binding.groupReferenceEncrypted(),
                                    binding.directoryProfileId(), binding.bindingKind(), binding.groupReferenceKeyId());
                            if (displayName.equals(principal)) {
                                roleTokens.add(binding.roleToken());
                            }
                        } catch (Exception ignored) { }
                    }
                }
                body.put("role_tokens", roleTokens);
                collectPlanes(roleTokens, planes);
            }
        }
        body.put("permissions", new java.util.ArrayList<>(planes));
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(body);
    }

    private void collectPlanes(List<String> roleTokens, java.util.Set<String> planes) {
        for (String token : roleTokens) {
            if (rbacRoleRepository != null) {
                var role = rbacRoleRepository.findByTokenString(token);
                if (role.isPresent() && role.get().permissions() != null && !role.get().permissions().isEmpty()) {
                    planes.addAll(role.get().permissions());
                    continue;
                }
            }
            if (token.endsWith("security_admin")) {
                planes.addAll(List.of("Devices", "Config", "Compliance", "Operations", "Admin"));
            } else if (token.endsWith("operator")) {
                planes.addAll(List.of("Devices", "Config", "Compliance", "Operations"));
            } else if (token.endsWith("viewer")) {
                planes.addAll(List.of("Devices", "Config", "Compliance"));
            } else if (token.endsWith("onboarding_admin")) {
                planes.addAll(List.of("Devices", "Config", "Admin"));
            } else if (token.endsWith("compliance_admin")) {
                planes.addAll(List.of("Compliance", "Admin"));
            } else if (token.endsWith("backup_admin")) {
                planes.addAll(List.of("Operations", "Admin"));
            }
        }
    }

    private Optional<SessionRecord> activeSession(String rawCookieValue) {
        String sessionId = SessionHasher.hash(rawCookieValue);
        return sessionRepository.findBySessionId(sessionId).filter(s -> s.isActive(Instant.now()));
    }

    private static Optional<String> findSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
