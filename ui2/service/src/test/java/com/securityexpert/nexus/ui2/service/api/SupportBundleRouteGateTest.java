package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.security.ActionDescriptor;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfigTestAccess;

/**
 * Support bundle routes (design 5.10.1, PO 2026-11-15 Q6): every route of the controller is mapped to an action, every
 * write, retrieve and delete action needs security_admin and never lists the replay viewer, and the read action does.
 */
class SupportBundleRouteGateTest {

    private static final Pattern ROUTE = Pattern.compile("@(Get|Post)Mapping\\(\\s*\"([^\"]+)\"");

    private static List<String> routes() throws IOException {
        List<String> out = new ArrayList<>();
        for (String file : new String[] {"SupportBundleController.java", "SupportBundleUploadController.java"}) {
            Path controller = Path.of(System.getProperty("user.dir"))
                    .resolve("src/main/java/com/securityexpert/nexus/ui2/service/supportbundle/" + file);
            Matcher m = ROUTE.matcher(Files.readString(controller));
            while (m.find()) out.add(m.group(1).toUpperCase() + " " + m.group(2).replaceAll("\\{[^}]+}", "*"));
        }
        return out;
    }

    @Test
    void everyRouteHasAnActionAndTheRoleRulesHold() throws IOException {
        ActionRegistry registry = new ActionRegistry();
        List<String> routes = routes();
        assertEquals(14, routes.size(), routes.toString());
        for (String route : routes) {
            String actionId = SecurityWebMvcConfigTestAccess.actionIdFor(route);
            assertNotNull(actionId, "route " + route + " has no action mapping (would fail closed)");
            ActionDescriptor d = registry.find(actionId).orElseThrow();
            if (ActionRegistry.SUPPORT_BUNDLE_READ.equals(actionId)) {
                assertTrue(route.startsWith("GET "), route);
                assertTrue(d.alternativeRoleTokens().contains(RoleToken.REPLAY_VIEWER), "the read is open to aiview, masked");
            } else {
                assertEquals(java.util.Optional.of(RoleToken.SECURITY_ADMIN), d.requiredRoleToken(), route);
                assertTrue(d.alternativeRoleTokens().isEmpty(), route + " must be security_admin only");
                assertFalse(d.alternativeRoleTokens().contains(RoleToken.REPLAY_VIEWER), route);
            }
        }
        assertEquals(Set.of(ActionRegistry.SUPPORT_BUNDLE_READ, ActionRegistry.SUPPORT_BUNDLE_REQUEST,
                ActionRegistry.SUPPORT_BUNDLE_CANCEL, ActionRegistry.SUPPORT_BUNDLE_RETRIEVE, ActionRegistry.SUPPORT_BUNDLE_DELETE,
                ActionRegistry.SUPPORT_BUNDLE_CLEANUP_WORKDIR, ActionRegistry.SUPPORT_BUNDLE_UPLOAD),
                routes.stream().map(SecurityWebMvcConfigTestAccess::actionIdFor).collect(java.util.stream.Collectors.toSet()));
    }
}
