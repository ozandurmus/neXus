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
    /** NXS-LOCAL-0327: destructive device removal, {@code role:onboarding_admin} only. */
    public static final String DEVICE_DELETE = "device_delete";
    /** WORKER.md "Routes": {@code GET /devices} and {@code GET /devices/{id}} -- any authenticated session. */
    public static final String DEVICE_READ = "device_read";
    /**
     * NXS-LOCAL-0160 "Routes": {@code POST /devices/{id}/inventory/collect}
     * -- a class-0-read collection job, but the submission itself is a
     * write (a job row), so it keeps {@code device_register}'s own
     * {@code role:onboarding_admin} gate rather than {@link #DEVICE_READ}'s
     * open one.
     */
    public static final String DEVICE_INVENTORY_COLLECT = "device_inventory_collect";
    /** NXS-LOCAL-0165 "Routes": {@code POST /devices/{id}/configuration/collect} -- same gate as {@link #DEVICE_INVENTORY_COLLECT}. */
    public static final String DEVICE_CONFIGURATION_COLLECT = "device_configuration_collect";
    /** NXS-LOCAL-0165 "Routes": {@code GET /devices/{id}/configuration/text} -- the sanitized Check Point view; a gated read, {@code role:onboarding_admin} only (it names section/entry structure of a device's own configuration). */
    public static final String DEVICE_CONFIGURATION_TEXT_READ = "device_configuration_text_read";
    /** NXS-LOCAL-0165: {@code GET /notifications} -- any authenticated session, like {@link #DEVICE_READ}. */
    public static final String NOTIFICATIONS_READ = "notifications_read";
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
    /** 14F section 3: {@code POST /discovery/runs} -- a write action, {@code role:onboarding_admin} only (same gate as {@link #DEVICE_REGISTER}). */
    public static final String DISCOVERY_SSH_TRUST_ENROLL = "discovery_ssh_trust_enroll";
    public static final String DISCOVERY_SSH_TRUST_RE_ENROLL = "discovery_ssh_trust_re_enroll";
    public static final String DISCOVERY_PAN_TRUST_ENROLL = "discovery_pan_trust_enroll";
    public static final String DISCOVERY_PAN_TRUST_RE_ENROLL = "discovery_pan_trust_re_enroll";
    public static final String DISCOVERY_RUN_START = "discovery_run_start";
    /** 14F section 3: {@code GET /discovery/runs/{run_id}} -- any authenticated session, like {@link #DEVICE_READ}. */
    public static final String DISCOVERY_RUN_READ = "discovery_run_read";
    /** 14F section 2: {@code POST /discovery/runs/{run_id}/import} -- a write action, {@code role:onboarding_admin} only. */
    public static final String DISCOVERY_RUN_IMPORT = "discovery_run_import";
    /**
     * NXS-LOCAL-0175: {@code POST /devices/{id}/backup/collect} -- 14H
     * BK-12: {@code role:backup_admin} plus a reason of at least eight
     * characters (length validated in {@code
     * service.device.backup.BackupCollectService}), manual only, one
     * pilot device only (BK-1). This action ADMITS a job (creates a
     * {@code REQUESTED} row); the worker executes the class-1 write
     * asynchronously off this request's own path, exactly as every other
     * job-admission route in this registry does for its own capability.
     * {@code consoleSubmittable = true} here reflects BK-12's own
     * role+reason+single-pilot-device gate as this movement's explicit,
     * scoped authorization for the admission -- flagged for Product Owner
     * review at the merge gate alongside {@code AGENTS.md}'s "Network
     * action taxonomy" prose ("class 1 controlled recovery writes...
     * are never console-submittable"): this is the first action in the
     * repository to test that rule against a real, frozen, PO-authorized
     * exception rather than {@link #RECOVERY_WRITE_EXAMPLE}'s own
     * deliberately unconditional-refusal demonstration.
     */
    public static final String DEVICE_BACKUP_COLLECT = "device_backup_collect";
    /** NXS-LOCAL-0175: {@code GET /devices/{id}/backups} and {@code GET /backups} -- posture only (BK-14: never a path, never bytes), open to any authenticated session like {@link #DEVICE_READ}. */
    public static final String DEVICE_BACKUP_READ = "device_backup_read";
    public static final String DEVICE_BACKUP_TARGET_SET = "device_backup_target_set";

    /**
     * WORKER.md (movement NXS-LOCAL-0174): {@code GET /project-plan} -- any
     * authenticated session, like {@link #DEVICE_READ} (PO-NAV-5:
     * administration-only once real directory-backed authorization exists;
     * not simulated now).
     */
    public static final String PROJECT_PLAN_READ = "project_plan_read";

    public static final String RBAC_ROLE_READ = "rbac_role_read";
    public static final String RBAC_ROLE_WRITE = "rbac_role_write";
    public static final String LDAP_CONFIG_READ = "ldap_config_read";
    public static final String LDAP_CONFIG_WRITE = "ldap_config_write";
    public static final String AUDIT_LOG_READ = "audit_log_read";
    public static final String JOB_LOG_READ = "job_log_read";

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
        register(new ActionDescriptor(DEVICE_DELETE, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        // WORKER.md "Routes": the two GET routes require only an authenticated
        // session, like the other read controllers -- empty means
        // NO_APPLICABLE_AUTHORITY at E4 (open to any authenticated session).
        register(new ActionDescriptor(DEVICE_READ, true, Optional.empty()));
        register(new ActionDescriptor(DEVICE_INVENTORY_COLLECT, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        register(new ActionDescriptor(DEVICE_CONFIGURATION_COLLECT, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        register(new ActionDescriptor(DEVICE_CONFIGURATION_TEXT_READ, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        register(new ActionDescriptor(NOTIFICATIONS_READ, true, Optional.empty()));
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
        // 14F section 3: the discovery routes gate exactly like the manual
        // single-device add routes -- write actions require
        // role:onboarding_admin, the read is open to any authenticated
        // session, through this same E4 evaluation.
        register(new ActionDescriptor(DISCOVERY_SSH_TRUST_ENROLL, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(DISCOVERY_SSH_TRUST_RE_ENROLL, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(DISCOVERY_PAN_TRUST_ENROLL, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(DISCOVERY_PAN_TRUST_RE_ENROLL, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(DISCOVERY_RUN_START, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        register(new ActionDescriptor(DISCOVERY_RUN_READ, true, Optional.empty()));
        register(new ActionDescriptor(DISCOVERY_RUN_IMPORT, true, Optional.of(RoleToken.ONBOARDING_ADMIN)));
        // 14H BK-12: role:backup_admin plus a reason (length-checked in
        // BackupCollectService, not here -- E4 only evaluates the role).
        register(new ActionDescriptor(DEVICE_BACKUP_COLLECT, true, Optional.of(RoleToken.BACKUP_ADMIN)));
        register(new ActionDescriptor(DEVICE_BACKUP_READ, true, Optional.empty()));
        register(new ActionDescriptor(DEVICE_BACKUP_TARGET_SET, true, Optional.of(RoleToken.BACKUP_ADMIN)));
        // WORKER.md: same open-to-any-authenticated-session gate as DEVICE_READ.
        register(new ActionDescriptor(PROJECT_PLAN_READ, true, Optional.empty()));
        register(new ActionDescriptor(RBAC_ROLE_READ, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(RBAC_ROLE_WRITE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(LDAP_CONFIG_READ, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(LDAP_CONFIG_WRITE, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(AUDIT_LOG_READ, true, Optional.of(RoleToken.SECURITY_ADMIN)));
        register(new ActionDescriptor(JOB_LOG_READ, true, Optional.empty()));
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
