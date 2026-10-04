package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.RoleToken;

class DiagnosticSecurityRouteTest {
    @Test
    void readinessActionIsExclusiveToTheTwoVendorReadinessPosts() {
        var routes = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.entrySet().stream()
                .filter(entry -> ActionRegistry.CP_READINESS_START.equals(entry.getValue()))
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("POST /api/v2/cp-failover/units/*/readiness",
                "POST /api/v2/pan-failover/units/*/readiness"), routes);
        assertEquals(ActionRegistry.CP_FAILOVER_START,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/cp-failover/runs"));
    }

    @Test
    void executionAllowsAdminOrReplayViewerWhileControllerRestrictsTheGate() {
        var actions = new ActionRegistry();
        for (String route : new String[] { "GET /api/v2/diagnostics/targets", "GET /api/v2/diagnostics/ports",
                "GET /api/v2/diagnostics/preview",
                "POST /api/v2/diagnostics", "GET /api/v2/diagnostics/*" }) {
            String actionId = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route);
            assertEquals(route.startsWith("POST") ? Optional.of(RoleToken.SECURITY_ADMIN) : Optional.empty(),
                    actions.find(actionId).orElseThrow().requiredRoleToken());
        }
        assertEquals(java.util.Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                actions.find(ActionRegistry.FMG_DIAGNOSTIC_RUN).orElseThrow().requiredRoleTokens());
    }
}
