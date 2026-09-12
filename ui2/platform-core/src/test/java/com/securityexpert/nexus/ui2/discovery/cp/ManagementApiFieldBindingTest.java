package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Contract §6.4 FB-1/FB-2/FB-3 (AC-10): {@link ManagementApiFieldBinding}
 * is the single isolated site where a role is associated with a concrete
 * management-API field name; every entry is {@code UNVERIFIED}; no bound
 * field-name string appears anywhere else in this package's production
 * source.
 */
class ManagementApiFieldBindingTest {

    @Test
    void everyRoleIsBound() {
        assertEquals(ManagementApiFieldBinding.Role.values().length, ManagementApiFieldBinding.all().size());
    }

    @Test
    void everyEntryIsUnverified() {
        for (ManagementApiFieldBinding entry : ManagementApiFieldBinding.all()) {
            assertEquals(ManagementApiFieldBinding.Status.UNVERIFIED, entry.status(),
                    "entry for " + entry.role() + " is not UNVERIFIED");
            assertTrue(entry.apiField().isPresent());
        }
    }

    /** AC-10: no bound field-name string leaks outside this one file. */
    @Test
    void boundFieldNamesAppearNowhereElseInProductionSource() throws IOException {
        Path packageDir = discoveryCpMainSourceDir();
        assertTrue(Files.isDirectory(packageDir), "expected to find " + packageDir);

        List<String> boundFieldNames = ManagementApiFieldBinding.all().stream()
                .map(e -> e.apiField().orElseThrow())
                .toList();

        List<String> violations = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("ManagementApiFieldBinding.java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            for (String fieldName : boundFieldNames) {
                                if (content.contains("\"" + fieldName + "\"")) {
                                    violations.add(p.getFileName() + " contains bound field literal \"" + fieldName + "\"");
                                }
                            }
                        } catch (IOException e) {
                            fail("could not read " + p + ": " + e.getMessage());
                        }
                    });
        }
        assertFalse(!violations.isEmpty(), "field-binding isolation violated: " + violations);
    }

    private static Path discoveryCpMainSourceDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "securityexpert",
                "nexus", "ui2", "discovery", "cp");
    }
}
