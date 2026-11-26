package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/** ZONE_MATRIX_CONTRACT.md section 3.4: every zone-matrix route resolves to its closed action with the contract's roles. */
class ZoneMatrixSecurityTest {
    @Test void everyRouteResolvesToItsActionAndTheRolesMatchTheContract() throws Exception {
        var interceptor = new GateChainInterceptor(null, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE);
        Method resolve = GateChainInterceptor.class.getDeclaredMethod("actionIdFor", String.class, String.class);
        resolve.setAccessible(true);
        Map<String, String> routes = Map.ofEntries(
                Map.entry("GET /api/v2/zone-matrix/template", ActionRegistry.ZONE_MATRIX_READ),
                Map.entry("POST /api/v2/zone-matrix/draft/cells", ActionRegistry.ZONE_MATRIX_EDIT),
                Map.entry("POST /api/v2/zone-matrix/draft/cells/clear", ActionRegistry.ZONE_MATRIX_EDIT),
                Map.entry("POST /api/v2/zone-matrix/draft/default", ActionRegistry.ZONE_MATRIX_EDIT),
                Map.entry("POST /api/v2/zone-matrix/draft/service-sets", ActionRegistry.ZONE_MATRIX_EDIT),
                Map.entry("POST /api/v2/zone-matrix/draft/service-sets/delete", ActionRegistry.ZONE_MATRIX_EDIT),
                Map.entry("POST /api/v2/zone-matrix/draft/template-import", ActionRegistry.ZONE_MATRIX_EDIT),
                Map.entry("POST /api/v2/zone-matrix/draft/cells/confirm-reference", ActionRegistry.ZONE_MATRIX_REFERENCE_CONFIRM),
                Map.entry("GET /api/v2/zone-matrix/policies", ActionRegistry.ZONE_MATRIX_READ),
                Map.entry("POST /api/v2/zone-matrix/evaluate", ActionRegistry.ZONE_MATRIX_EVALUATE),
                Map.entry("POST /api/v2/zone-matrix/runs", ActionRegistry.ZONE_MATRIX_EVALUATE),
                Map.entry("GET /api/v2/zone-matrix/summary", ActionRegistry.ZONE_MATRIX_READ),
                Map.entry("GET /api/v2/zone-matrix/findings/4711/witness", ActionRegistry.ZONE_MATRIX_ZONES_READ));
        Map<String, String> stepOne = Map.of(
                "GET /api/v2/zone-matrix/versions", ActionRegistry.ZONE_MATRIX_READ,
                "GET /api/v2/zone-matrix/versions/00000000-0000-4000-8000-000000000001", ActionRegistry.ZONE_MATRIX_READ,
                "GET /api/v2/zone-matrix/draft", ActionRegistry.ZONE_MATRIX_READ,
                "POST /api/v2/zone-matrix/lookup", ActionRegistry.ZONE_MATRIX_ZONES_READ,
                "POST /api/v2/zone-matrix/draft/zones", ActionRegistry.ZONE_MATRIX_EDIT,
                "PUT /api/v2/zone-matrix/draft/zones/zone-1", ActionRegistry.ZONE_MATRIX_EDIT,
                "POST /api/v2/zone-matrix/draft/zones/zone-1/delete", ActionRegistry.ZONE_MATRIX_EDIT,
                "POST /api/v2/zone-matrix/draft/discard", ActionRegistry.ZONE_MATRIX_EDIT,
                "POST /api/v2/zone-matrix/draft/publish", ActionRegistry.ZONE_MATRIX_PUBLISH);
        routes = new java.util.HashMap<>(routes);
        routes.putAll(stepOne);
        for (var route : routes.entrySet()) {
            var parts = route.getKey().split(" ", 2);
            assertEquals(route.getValue(), resolve.invoke(interceptor, parts[0], parts[1]), route.getKey());
        }
        var registry = new ActionRegistry();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.COMPLIANCE_ADMIN, RoleToken.ONBOARDING_ADMIN, RoleToken.VIEWER,
                RoleToken.REPLAY_VIEWER), registry.find(ActionRegistry.ZONE_MATRIX_READ).orElseThrow().requiredRoleTokens());
        for (String action : new String[]{ActionRegistry.ZONE_MATRIX_ZONES_READ, ActionRegistry.ZONE_MATRIX_EDIT,
                ActionRegistry.ZONE_MATRIX_PUBLISH, ActionRegistry.ZONE_MATRIX_EVALUATE}) {
            var tokens = registry.find(action).orElseThrow().requiredRoleTokens();
            assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.COMPLIANCE_ADMIN), tokens, action);
            assertFalse(tokens.contains(RoleToken.REPLAY_VIEWER), action);
        }
        // step 4b-2b: the stored summary is the existing read action; viewer, onboarding, compliance and security roles read it,
        // replay_viewer reads it masked (controller), no other role reads it.
        assertEquals(ActionRegistry.ZONE_MATRIX_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/zone-matrix/summary"));
        var summaryRoles = registry.find(ActionRegistry.ZONE_MATRIX_READ).orElseThrow().requiredRoleTokens();
        for (String role : new String[]{RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN, RoleToken.BACKUP_ADMIN}) {
            assertFalse(summaryRoles.contains(role), role);
        }
        // step 4b-2 (section 5.1): the on-demand durable run is the existing evaluate action; security and compliance
        // administrators start it, replay_viewer (aiview) and every other role do not.
        assertEquals(ActionRegistry.ZONE_MATRIX_EVALUATE, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/zone-matrix/runs"));
        assertEquals(SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/zone-matrix/evaluate"),
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("POST /api/v2/zone-matrix/runs"), "the run route is gated exactly as /evaluate");
        var runRoles = registry.find(ActionRegistry.ZONE_MATRIX_EVALUATE).orElseThrow().requiredRoleTokens();
        for (String role : new String[]{RoleToken.REPLAY_VIEWER, RoleToken.VIEWER, RoleToken.OPERATOR, RoleToken.OPERATION_ADMIN,
                RoleToken.BACKUP_ADMIN, RoleToken.ONBOARDING_ADMIN}) {
            assertFalse(runRoles.contains(role), role);
        }
        // step 4b-2 (sections 3.4, 7.1, 8.1; ZM-33 route-level pin): the stored witness read of one finding is the existing
        // zone_matrix_zones_read action; security and compliance administrators only. viewer, onboarding_admin and every
        // other role are refused at the gate, and replay_viewer (aiview) is never in the action (the controller refuses it too).
        assertEquals(ActionRegistry.ZONE_MATRIX_ZONES_READ,
                SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET /api/v2/zone-matrix/findings/*/witness"));
        var witnessRoles = registry.find(ActionRegistry.ZONE_MATRIX_ZONES_READ).orElseThrow().requiredRoleTokens();
        assertEquals(Set.of(RoleToken.SECURITY_ADMIN, RoleToken.COMPLIANCE_ADMIN), witnessRoles);
        for (String role : new String[]{RoleToken.REPLAY_VIEWER, RoleToken.VIEWER, RoleToken.ONBOARDING_ADMIN, RoleToken.OPERATOR,
                RoleToken.OPERATION_ADMIN, RoleToken.BACKUP_ADMIN}) {
            assertFalse(witnessRoles.contains(role), role);
        }
        // section 3.4: only compliance_admin confirms a reference (ZM-23)
        assertEquals(Set.of(RoleToken.COMPLIANCE_ADMIN),
                registry.find(ActionRegistry.ZONE_MATRIX_REFERENCE_CONFIRM).orElseThrow().requiredRoleTokens());
    }

    /**
     * ZM-37: the zone matrix route set has no route that deletes or changes a run or a finding. The one non-GET run route is
     * {@code POST /api/v2/zone-matrix/runs}, which only starts a new run (section 5.1 on demand) and has no path variable.
     */
    @Test void zm37_noZoneMatrixRouteMutatesOrDeletesARunOrAFinding() {
        for (String route : SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.keySet()) {
            if (!route.contains(" /api/v2/zone-matrix/")) continue;
            String method = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1);
            assertNotEquals("DELETE", method, route);
            assertNotEquals("PATCH", method, route);
            if (route.equals("POST /api/v2/zone-matrix/runs")) continue;
            if (path.contains("/runs") || path.contains("/findings") || path.startsWith("/api/v2/zone-matrix/summary")) {
                assertEquals("GET", method, route + " must be read-only");
            }
        }
    }
}
