package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.platform.RoleToken;

class SshHostKeySecurityRouteTest {
    @Test void readersIncludeAiviewButOnlySecurityAdminCanChangeTrust() {
        var actions = new ActionRegistry();
        var routes = SecurityWebMvcConfig.ACTION_ID_BY_ROUTE;
        assertEquals(Optional.empty(), actions.find(routes.get("GET /api/v2/ssh-trust")).orElseThrow().requiredRoleToken());
        for (String route : new String[]{"PUT /api/v2/ssh-trust/mode", "POST /api/v2/ssh-trust/observations/*/accept",
                "POST /api/v2/ssh-trust/observations/*/reject"}) {
            assertEquals(Optional.of(RoleToken.SECURITY_ADMIN), actions.find(routes.get(route)).orElseThrow().requiredRoleToken());
            assertTrue(route.chars().filter(c -> c == '*').count() <= 1);
        }
    }
}
