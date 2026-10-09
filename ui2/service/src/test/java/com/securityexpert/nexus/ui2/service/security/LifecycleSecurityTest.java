package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.platform.RoleToken;

class LifecycleSecurityTest {
    @Test void everyReadAndWriteRouteResolvesToTheCorrectClosedAction() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        Method resolve = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        resolve.setAccessible(true);
        for (String path : List.of("/api/v2/lifecycle", "/api/v2/lifecycle/catalog", "/devices/device-1/lifecycle")) {
            assertEquals(ActionRegistry.DEVICE_READ, resolve.invoke(interceptor, "GET", path));
        }
        for (String route : List.of("POST /api/v2/lifecycle/catalog", "POST /api/v2/lifecycle/catalog/import",
                "PUT /api/v2/lifecycle/catalog/row-1", "POST /api/v2/lifecycle/catalog/row-1/delete")) {
            var parts = route.split(" ", 2);
            assertEquals(ActionRegistry.LIFECYCLE_CATALOG_WRITE, resolve.invoke(interceptor, parts[0], parts[1]));
        }
        var action = new ActionRegistry().find(ActionRegistry.LIFECYCLE_CATALOG_WRITE).orElseThrow();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN), action.requiredRoleTokens());
        assertFalse(action.requiredRoleTokens().contains(RoleToken.REPLAY_VIEWER));
        assertTrue(new ActionRegistry().find(ActionRegistry.DEVICE_READ).orElseThrow().requiredRoleTokens().isEmpty());
    }
}
