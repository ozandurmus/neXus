package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.ActionDescriptor;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfigTestAccess;

/**
 * PO decision record 2026-09-22 replaced 14I OR-1's "never a download route"
 * with "never an ungated download route": every service route whose path
 * suggests bytes leaving the service must be mapped to an action that
 * requires {@code role:backup_admin}.
 */
class BackupDownloadRouteGateTest {

    private static final Pattern SUSPECT_ROUTE = Pattern.compile(
            "@(?:Get|Post)Mapping\\(\\s*\"([^\"]*(?:retriev|download|decrypt|/backup/read|/backup/fetch)[^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    @Test
    void everyDownloadRouteIsMappedToARoleBackupAdminAction() throws IOException {
        Path serviceMain = Path.of(System.getProperty("user.dir")).resolve("src/main/java");
        List<String> routes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(serviceMain)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = SUSPECT_ROUTE.matcher(Files.readString(path));
                while (matcher.find()) {
                    routes.add(matcher.group(1));
                }
            }
        }
        assertEquals(List.of("/backups/{artefactId}/download"), routes,
                "the decision record names exactly one download route");

        ActionRegistry registry = new ActionRegistry();
        for (String route : routes) {
            String pattern = "POST " + route.replaceAll("\\{[^}]+}", "*");
            String actionId = SecurityWebMvcConfigTestAccess.actionIdFor(pattern);
            assertTrue(actionId != null, "download route " + pattern + " has no action mapping (would fail closed)");
            Optional<ActionDescriptor> descriptor = registry.find(actionId);
            assertTrue(descriptor.isPresent(), "action " + actionId + " is not registered");
            assertEquals(Optional.of(RoleToken.BACKUP_ADMIN), descriptor.get().requiredRoleToken(),
                    "download route " + pattern + " must require role:backup_admin");
        }
    }
}
