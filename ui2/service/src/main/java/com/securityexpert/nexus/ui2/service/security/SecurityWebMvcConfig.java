package com.securityexpert.nexus.ui2.service.security;

import java.util.Map;

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
            Map.entry("POST /role-bindings", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("POST /role-bindings/revoke", ActionRegistry.ROLE_BINDING_REVOKE),
            Map.entry("POST /sessions/revoke", ActionRegistry.SESSION_REVOKE),
            Map.entry("POST /devices/add-single", ActionRegistry.DEVICE_REGISTER),
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
            Map.entry("POST /devices/*/inventory/collect", ActionRegistry.DEVICE_INVENTORY_COLLECT),
            Map.entry("GET /clusters/*/inventory", ActionRegistry.DEVICE_READ),
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
            Map.entry("POST /discovery/runs", ActionRegistry.DISCOVERY_RUN_START),
            Map.entry("GET /discovery/runs/*", ActionRegistry.DISCOVERY_RUN_READ),
            Map.entry("POST /discovery/runs/*/import", ActionRegistry.DISCOVERY_RUN_IMPORT));

    private final GateChain gateChain;

    public SecurityWebMvcConfig(GateChain gateChain) {
        this.gateChain = gateChain;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GateChainInterceptor(gateChain, ACTION_ID_BY_ROUTE));
    }
}
