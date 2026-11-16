package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

class SoftwareVersionHistorySecurityTest {
    @Test void bothReadRoutesAreDeviceReadAndNothingWritesHistory() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        Method resolve = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        resolve.setAccessible(true);
        for (String path : List.of("/devices/device-1/software-history", "/api/v2/software-history/changes")) {
            assertEquals(ActionRegistry.DEVICE_READ, resolve.invoke(interceptor, "GET", path));
        }
        for (String method : List.of("POST", "PUT", "DELETE")) {
            assertNotEquals(ActionRegistry.DEVICE_READ, resolve.invoke(interceptor, method, "/api/v2/software-history/changes"));
        }
        assertTrue(new ActionRegistry().find(ActionRegistry.DEVICE_READ).orElseThrow().requiredRoleTokens().isEmpty());
    }
}
