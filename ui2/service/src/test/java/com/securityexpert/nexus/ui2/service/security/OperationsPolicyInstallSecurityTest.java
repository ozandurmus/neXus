package com.securityexpert.nexus.ui2.service.security;

import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.service.api.PolicyInstallMonitoringController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Architecture and security assertions for Operations &gt; Policy installation Phase 1
 * ({@code docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md}, FROZEN):
 * GET-only endpoint, open to any authenticated role, and zero {@link ActionRegistry} write actions
 * for policy verification or installation.
 */
class OperationsPolicyInstallSecurityTest {

    @Test
    void policyInstallMonitoringEndpointIsGetOnlyInControllerAndRouteMap() {
        assertEquals(
                ActionRegistry.POLICY_INSTALL_READ,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/operations/policy-install/status")
        );

        for (String routeKey : SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet()) {
            if (routeKey.contains("policy-install")) {
                assertTrue(
                        routeKey.startsWith("GET "),
                        "Every policy-install route in P1 must be GET-only, found: " + routeKey
                );
            }
        }

        int getMappings = 0;
        for (Method method : PolicyInstallMonitoringController.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(PostMapping.class), "POST mapping forbidden in P1: " + method.getName());
            assertFalse(method.isAnnotationPresent(PutMapping.class), "PUT mapping forbidden in P1: " + method.getName());
            assertFalse(method.isAnnotationPresent(DeleteMapping.class), "DELETE mapping forbidden in P1: " + method.getName());
            assertFalse(method.isAnnotationPresent(PatchMapping.class), "PATCH mapping forbidden in P1: " + method.getName());
            if (method.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping rm = method.getAnnotation(RequestMapping.class);
                for (RequestMethod m : rm.method()) {
                    assertEquals(RequestMethod.GET, m, "Non-GET RequestMethod forbidden in P1: " + m);
                }
            }
            if (method.isAnnotationPresent(GetMapping.class)) {
                getMappings++;
            }
        }
        assertEquals(1, getMappings);
    }

    @Test
    void noPolicyInstallWriteActionWasAddedToActionRegistry() throws Exception {
        ActionRegistry registry = new ActionRegistry();
        ActionDescriptor readDescriptor = registry.find(ActionRegistry.POLICY_INSTALL_READ).orElseThrow();
        assertTrue(readDescriptor.consoleSubmittable());
        assertTrue(
                readDescriptor.requiredRoleTokens().isEmpty(),
                "policy_install_read must be open to any authenticated role at E4"
        );

        Set<String> forbiddenWriteActions = Set.of(
                "policy_verify_start",
                "policy_install_request",
                "policy_install_approve",
                "policy_install_start",
                "policy_install_schedule_set",
                "policy_install_kill_switch"
        );
        for (String forbidden : forbiddenWriteActions) {
            assertTrue(
                    registry.find(forbidden).isEmpty(),
                    "P2-P4 write/verify action must not exist in ActionRegistry in P1: " + forbidden
            );
        }

        Field actionsField = ActionRegistry.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ActionDescriptor> allActions = (Map<String, ActionDescriptor>) actionsField.get(registry);
        for (ActionDescriptor descriptor : allActions.values()) {
            assertFalse(
                    descriptor.actionId().startsWith("policy_install_") && !ActionRegistry.POLICY_INSTALL_READ.equals(descriptor.actionId()),
                    "No policy_install_* write action may be registered in ActionRegistry in P1: " + descriptor.actionId()
            );
            assertFalse(
                    descriptor.actionId().startsWith("policy_verify_"),
                    "No policy_verify_* action may be registered in ActionRegistry in P1: " + descriptor.actionId()
            );
        }
    }
}
