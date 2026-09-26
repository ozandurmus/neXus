package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.RoleToken;

class DiagnosticSecurityRouteTest {
    @Test
    void everyDiagnosticRouteRequiresTheExistingSuperAdminRole() {
        var actions = new ActionRegistry();
        for (String route : new String[] { "GET /api/v2/diagnostics/targets", "GET /api/v2/diagnostics/ports",
                "GET /api/v2/diagnostics/preview",
                "POST /api/v2/diagnostics", "GET /api/v2/diagnostics/*" }) {
            String actionId = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get(route);
            assertEquals(Optional.of(RoleToken.SECURITY_ADMIN), actions.find(actionId).orElseThrow().requiredRoleToken());
        }
    }
}
