package com.securityexpert.nexus.ui2.service.security;

import java.util.Map;
import java.util.Set;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link GateChainInterceptor} against every route this movement
 * gates (contract §7: "{@code E1}-{@code E6} run in one interceptor chain
 * no route can bypass"). {@code /login} and {@code /login/resolve} are
 * deliberately absent from the route map: they run before any session
 * exists (C3 §3.4).
 *
 * <p>No {@code bootJar} is produced at B1 scope (contract §2 skeleton
 * note carried over from B1-1), so this configuration is exercised by
 * Spring context tests only, never a running server, in this slice.</p>
 */
public final class SecurityWebMvcConfig implements WebMvcConfigurer {

    static final Map<String, String> ACTION_ID_BY_ROUTE = Map.ofEntries(
            Map.entry("POST /role-bindings/selections", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("GET /role-bindings", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("POST /role-bindings", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("POST /role-bindings/revoke", ActionRegistry.ROLE_BINDING_REVOKE),
            Map.entry("GET /sessions", ActionRegistry.SESSION_REVOKE),
            Map.entry("POST /sessions/revoke", ActionRegistry.SESSION_REVOKE),
            Map.entry("POST /devices/add-single", ActionRegistry.DEVICE_REGISTER),
            Map.entry("POST /devices/*/confirm", ActionRegistry.DEVICE_REGISTER),
            Map.entry("POST /devices/*/delete", ActionRegistry.DEVICE_DELETE),
            // V64: a device's second secret (a credential-store reference, never a value)
            Map.entry("POST /devices/*/secrets/*", ActionRegistry.DEVICE_REGISTER),
            // GET /devices/{id} is this route map's first path-variable route --
            // "GET /devices/*" is GateChainInterceptor's one-segment wildcard
            // suffix, matched only after an exact-route lookup misses.
            Map.entry("GET /devices", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*", ActionRegistry.DEVICE_READ),
            // NXS-LOCAL-0160 "Routes": the id segment is not the route's
            // last segment for these three -- GateChainInterceptor's
            // wildcard fallback tries every interior segment, not only the
            // last one, so a wildcard entry naming the id's own position
            // ("*") is enough; no change to any single-segment route above.
            Map.entry("GET /devices/*/inventory", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/management-tree", ActionRegistry.DEVICE_READ),
            Map.entry("POST /devices/*/management-tree/acknowledge", ActionRegistry.DISCOVERY_ACKNOWLEDGE),
            Map.entry("POST /devices/*/inventory/collect", ActionRegistry.DEVICE_INVENTORY_COLLECT),
            Map.entry("POST /devices/inventory/collect-all", ActionRegistry.DEVICE_INVENTORY_COLLECT),
            Map.entry("GET /clusters/*/inventory", ActionRegistry.DEVICE_READ),
            // NXS-LOCAL-0165 "Routes": same wildcard shapes as the inventory
            // routes above; /devices/*/configuration/text is a four-segment
            // route (GateChainInterceptor tries a single-segment wildcard at
            // every position, so the id at position 2 resolves the same way
            // /discovery/runs/*/import's own position-3 wildcard already does).
            Map.entry("GET /configuration", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/configuration", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/configuration/text", ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ),
            Map.entry("POST /devices/*/configuration/collect", ActionRegistry.DEVICE_CONFIGURATION_COLLECT),
            Map.entry("POST /devices/configuration/collect-all", ActionRegistry.DEVICE_CONFIGURATION_COLLECT),
            Map.entry("GET /compliance", ActionRegistry.DEVICE_READ),
            Map.entry("GET /compliance/overview", ActionRegistry.DEVICE_READ),
            Map.entry("GET /compliance/controls", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/compliance", ActionRegistry.DEVICE_READ),
            Map.entry("POST /compliance/evaluate", ActionRegistry.DEVICE_READ),
            Map.entry("GET /notifications", ActionRegistry.NOTIFICATIONS_READ),
            Map.entry("GET /roles", ActionRegistry.RBAC_ROLE_READ),
            Map.entry("POST /roles", ActionRegistry.RBAC_ROLE_WRITE),
            Map.entry("PUT /roles/*", ActionRegistry.RBAC_ROLE_WRITE),
            Map.entry("DELETE /roles/*", ActionRegistry.RBAC_ROLE_WRITE),
            Map.entry("GET /api/v2/config/ldap", ActionRegistry.LDAP_CONFIG_READ),
            Map.entry("PUT /api/v2/config/ldap", ActionRegistry.LDAP_CONFIG_WRITE),
            Map.entry("GET /api/v2/config/notifications", ActionRegistry.NOTIFICATION_CONFIG_READ),
            Map.entry("PUT /api/v2/config/notifications", ActionRegistry.NOTIFICATION_CONFIG_WRITE),
            Map.entry("POST /api/v2/config/notifications/test-syslog", ActionRegistry.NOTIFICATION_CONFIG_WRITE),
            Map.entry("POST /api/v2/config/notifications/test-mail", ActionRegistry.NOTIFICATION_CONFIG_WRITE),
            Map.entry("GET /api/v2/system/pods", ActionRegistry.SYSTEM_STATUS_READ),
            Map.entry("GET /api/v2/overview", ActionRegistry.SYSTEM_STATUS_READ),
            Map.entry("GET /api/v2/search", ActionRegistry.GLOBAL_SEARCH_READ),
            Map.entry("GET /api/v2/system/storage", ActionRegistry.SYSTEM_STATUS_READ),
            Map.entry("GET /audit-logs", ActionRegistry.AUDIT_LOG_READ),
            Map.entry("GET /api/v2/jobs", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /api/v2/jobs/facets", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /api/v2/jobs/stats", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /api/v2/jobs/export.csv", ActionRegistry.JOB_LOG_READ),
            // 13G: one resource, body-only (no path variable), matching
            // /role-bindings/revoke's own shape.
            Map.entry("POST /local-identities", ActionRegistry.LOCAL_IDENTITY_CREATE),
            Map.entry("GET /local-identities", ActionRegistry.LOCAL_IDENTITY_LIST),
            Map.entry("POST /local-identities/set-password", ActionRegistry.LOCAL_IDENTITY_SET_PASSWORD),
            Map.entry("POST /local-identities/disable", ActionRegistry.LOCAL_IDENTITY_DISABLE),
            Map.entry("POST /local-identities/enable", ActionRegistry.LOCAL_IDENTITY_ENABLE),
            // 2026-09-14 CS-1..CS-5: one resource, body-only (no path
            // variable), matching /local-identities' own shape.
            Map.entry("POST /credentials", ActionRegistry.CREDENTIAL_CREATE),
            Map.entry("GET /credentials", ActionRegistry.CREDENTIAL_LIST),
            Map.entry("POST /credentials/replace-secret", ActionRegistry.CREDENTIAL_REPLACE_SECRET),
            Map.entry("POST /credentials/delete", ActionRegistry.CREDENTIAL_DELETE),
            // 14F section 3: body-only start (no path variable, matching
            // /devices/add-single's own shape), a single-segment wildcard
            // read, and a two-segment-deep wildcard import (the run id is
            // not the route's last segment, matching /devices/*/inventory/
            // collect's own wildcard shape above).
            Map.entry("POST /discovery/ssh-trust/enroll", ActionRegistry.DISCOVERY_SSH_TRUST_ENROLL),
            Map.entry("POST /discovery/ssh-trust/re-enroll", ActionRegistry.DISCOVERY_SSH_TRUST_RE_ENROLL),
            Map.entry("POST /discovery/pan-trust/enroll", ActionRegistry.DISCOVERY_PAN_TRUST_ENROLL),
            Map.entry("POST /discovery/pan-trust/re-enroll", ActionRegistry.DISCOVERY_PAN_TRUST_RE_ENROLL),
            Map.entry("POST /discovery/runs", ActionRegistry.DISCOVERY_RUN_START),
            Map.entry("GET /discovery/runs/*", ActionRegistry.DISCOVERY_RUN_READ),
            Map.entry("POST /discovery/runs/*/import", ActionRegistry.DISCOVERY_RUN_IMPORT),
            // NXS-LOCAL-0175 "Service and screen": same wildcard shapes as
            // the inventory/configuration routes above. BK-14: no route
            // here ever returns an artefact byte, path or decrypt
            // affordance -- these three surface posture rows only.
            Map.entry("POST /devices/*/backup/collect", ActionRegistry.DEVICE_BACKUP_COLLECT),
            Map.entry("GET /devices/*/backups", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("GET /backups", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("POST /backups/collect-all", ActionRegistry.DEVICE_BACKUP_COLLECT),
            Map.entry("POST /api/v2/backups/*/run", ActionRegistry.DEVICE_BACKUP_COLLECT),
            Map.entry("PUT /devices/*/backup-target", ActionRegistry.DEVICE_BACKUP_TARGET_SET),
            // PO decision record 2026-09-22: the one route that returns artefact bytes, role-gated.
            Map.entry("POST /backups/*/download", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            Map.entry("POST /backups/*/download-ticket", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            Map.entry("GET /backups/*/download", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            // V41 content listing and compare: posture reads (names/sizes/digests, never content).
            Map.entry("GET /backups/*/entries", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("GET /backups/*/compare", ActionRegistry.DEVICE_BACKUP_READ),
            // "List now" decrypts on the service: gated like the download.
            Map.entry("POST /backups/*/relist", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            // Measured live (2026-09-22): both routes answered 403 ACTION_MAPPING_REQUIRED, so the
            // Backups screen read "Policy unavailable" -- the routes existed, their mapping did not.
            Map.entry("GET /api/v2/backups/policies", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("PUT /api/v2/backups/policies", ActionRegistry.DEVICE_BACKUP_POLICY_SET),
            Map.entry("PUT /devices/*/backup-baseline", ActionRegistry.DEVICE_BACKUP_BASELINE_SET),
            Map.entry("GET /api/v2/backups/deviations", ActionRegistry.DEVICE_BACKUP_READ),
            // WORKER.md (movement NXS-LOCAL-0174): body-only, no path variable,
            // same shape as /notifications' own route.
            Map.entry("GET /project-plan", ActionRegistry.PROJECT_PLAN_READ));

    /**
     * Routes whose endpoint performs its own deliberately non-RBAC security
     * check. Every other route must have an action mapping and is refused by
     * {@link GateChainInterceptor}; this prevents a new controller from
     * becoming open merely because its mapping was omitted here.
     */
    static final Set<String> EXPLICITLY_UNGATED_ROUTES = Set.of(
            "GET /",
            "POST /login",
            "POST /auth/login",
            "POST /login/resolve",
            "POST /session/logout",
            "GET /session/status",
            "GET /error",
            "POST /error",
            "POST /local-credentials/change-password",
            "GET /healthz");

    private final GateChain gateChain;
    private final LocalIdentityResolver localIdentityResolver;
    private final LocalRoleTokenResolver localRoleTokenResolver;

    public SecurityWebMvcConfig(GateChain gateChain) {
        this(gateChain, null, null);
    }

    public SecurityWebMvcConfig(GateChain gateChain, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver) {
        this.gateChain = gateChain;
        this.localIdentityResolver = localIdentityResolver;
        this.localRoleTokenResolver = localRoleTokenResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GateChainInterceptor(gateChain, ACTION_ID_BY_ROUTE, EXPLICITLY_UNGATED_ROUTES,
                localIdentityResolver, localRoleTokenResolver));
    }
}
