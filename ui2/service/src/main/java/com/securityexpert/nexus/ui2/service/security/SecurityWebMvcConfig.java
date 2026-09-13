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
            Map.entry("POST /devices", ActionRegistry.DEVICE_REGISTER),
            // 13G: one resource, body-only (no path variable), matching
            // /role-bindings/revoke's own shape.
            Map.entry("POST /local-identities", ActionRegistry.LOCAL_IDENTITY_CREATE),
            Map.entry("GET /local-identities", ActionRegistry.LOCAL_IDENTITY_LIST),
            Map.entry("POST /local-identities/set-password", ActionRegistry.LOCAL_IDENTITY_SET_PASSWORD),
            Map.entry("POST /local-identities/disable", ActionRegistry.LOCAL_IDENTITY_DISABLE),
            Map.entry("POST /local-identities/enable", ActionRegistry.LOCAL_IDENTITY_ENABLE));

    private final GateChain gateChain;

    public SecurityWebMvcConfig(GateChain gateChain) {
        this.gateChain = gateChain;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GateChainInterceptor(gateChain, ACTION_ID_BY_ROUTE));
    }
}
