package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.securityexpert.nexus.ui2.service.api.EstateAsOfController;

/**
 * Security and read-only boundary assertions for {@code GET /api/v2/estate/as-of}.
 */
class EstateAsOfSecurityRouteTest {

    @Test
    void estateAsOfRouteIsGetOnlyAndMappedToDeviceRead() {
        assertEquals(
                ActionRegistry.DEVICE_READ,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/estate/as-of")
        );

        for (String routeKey : SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet()) {
            if (routeKey.contains("/estate/")) {
                assertTrue(routeKey.startsWith("GET "), "Every estate rewind route must be GET-only, found: " + routeKey);
            }
        }

        int getMappings = 0;
        for (Method method : EstateAsOfController.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(PostMapping.class));
            assertFalse(method.isAnnotationPresent(PutMapping.class));
            assertFalse(method.isAnnotationPresent(DeleteMapping.class));
            assertFalse(method.isAnnotationPresent(PatchMapping.class));
            if (method.isAnnotationPresent(RequestMapping.class)) {
                for (RequestMethod rm : method.getAnnotation(RequestMapping.class).method()) {
                    assertEquals(RequestMethod.GET, rm);
                }
            }
            if (method.isAnnotationPresent(GetMapping.class)) {
                getMappings++;
            }
        }
        assertEquals(1, getMappings);
    }
}
