package com.securityexpert.nexus.ui2.service.security;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * {@code E2}'s closed action registry (contract §7). A stand-in for C4's
 * eventual registry, scoped to this movement's own actions only; a real C4
 * registry replaces this class's seeding, not {@link GateChain}'s use of
 * the {@link ActionDescriptor} shape.
 */
public final class ActionRegistry {

    public static final String ROLE_BINDING_CREATE = "role_binding_create";
    public static final String ROLE_BINDING_REVOKE = "role_binding_revoke";
    public static final String SESSION_REVOKE = "session_revoke_by_admin";
    /** A class-1 action reserved for {@code E3NeverReevaluatedInsideE4} (test 12) and for exercise in tests. */
    public static final String RECOVERY_WRITE_EXAMPLE = "recovery_write_example";
    /** B1-4b contract §4: manual device registration, {@code role:onboarding_admin} only (C3 §4.1). */
    public static final String DEVICE_REGISTER = "device_register";
    /** WORKER.md "Routes": {@code GET /devices} and {@code GET /devices/{id}} -- any authenticated session. */
    public static final String DEVICE_READ = "device_read";
    /** 13G LIA-5: local identity administration, {@code role:security_admin} only. */
    public static final String LOCAL_IDENTITY_CREATE = "local_identity_create";
    public static final String LOCAL_IDENTITY_LIST = "local_identity_list";
    public static final String LOCAL_IDENTITY_SET_PASSWORD = "local_identity_set_password";
    public static final String LOCAL_IDENTITY_DISABLE = "local_identity_disable";
    public static final String LOCAL_IDENTITY_ENABLE = "local_identity_enable";
    /** 2026-09-14 PO decision record CS-1..CS-5: credential store administration, {@code role:security_admin} only. */
    public static final String CREDENTIAL_CREATE = "credential_create";
    public static final String CREDENTIAL_LIST = "credential_list";
    public static final String CREDENTIAL_REPLACE_SECRET = "credential_replace_secret";
    public static final String CREDENTIAL_DELETE = "credential_delete";

    private final Map<String, ActionDescriptor> actions = new ConcurrentHashMap<>();

    public ActionRegistry() {
        seedActions();
    }

    private void seedActions() {
        register(new ActionDescriptor(ROLE_BINDING_CREATE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(ROLE_BINDING_REVOKE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(SESSION_REVOKE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        // role:onboarding_admin only -- role:viewer/operator get DENIED or
        // AUTHZ_NOT_EVALUATED (test 2); role:security_admin has no binding
        // for this exact token either, so it is refused the same way,
        // never granted by "admin" adjacency (test 3, C3 §4.1 separation
        // of duties).
        register(new ActionDescriptor(DEVICE_REGISTER, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        // WORKER.md "Routes": the two GET routes require only an authenticated
        // session, like the other read controllers -- empty means
        // NO_APPLICABLE_AUTHORITY at E4 (open to any authenticated session).
        register(new ActionDescriptor(DEVICE_READ, true, Optional.empty()));
        // 13G LIA-5: every local identity administration operation requires
        // role:security_admin, through this same E4 evaluation -- no second
        // authorization check exists.
        register(new ActionDescriptor(LOCAL_IDENTITY_CREATE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(LOCAL_IDENTITY_LIST, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(LOCAL_IDENTITY_SET_PASSWORD, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(LOCAL_IDENTITY_DISABLE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(LOCAL_IDENTITY_ENABLE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        // 2026-09-14 CS-1..CS-5: every credential store operation requires
        // role:security_admin, through this same E4 evaluation -- no second
        // authorization check exists.
        register(new ActionDescriptor(CREDENTIAL_CREATE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(CREDENTIAL_LIST, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(CREDENTIAL_REPLACE_SECRET, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(CREDENTIAL_DELETE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        // Class 1: never console-submittable, refused by E3 unconditionally,
        // regardless of role -- exists so E3's unconditional refusal and
        // E3-never-reevaluated-inside-E4 (test 12) are both testable without
        // a real capability registry.
        register(new ActionDescriptor(RECOVERY_WRITE_EXAMPLE, false, Optional.of(RoleToken.BACKUP_ADMIN)));
    }

    public void register(ActionDescriptor descriptor) {
        actions.put(descriptor.actionId(), descriptor);
    }

    public Optional<ActionDescriptor> find(String actionId) {
        return Optional.ofNullable(actions.get(actionId));
    }
}
