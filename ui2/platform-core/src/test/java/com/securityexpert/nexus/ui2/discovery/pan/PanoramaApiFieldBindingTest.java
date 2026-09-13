package com.securityexpert.nexus.ui2.discovery.pan;

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
 * AC-7: {@link PanoramaApiFieldBinding} is the only production class
 * containing a concrete element name/path, and every entry is
 * {@code UNVERIFIED}. Mirrors cp's {@code ManagementApiFieldBindingTest}.
 */
class PanoramaApiFieldBindingTest {

    @Test
    void everyRoleIsBound() {
        assertEquals(PanoramaApiFieldBinding.Role.values().length, PanoramaApiFieldBinding.all().size());
    }

    @Test
    void everyEntryIsUnverified() {
        for (PanoramaApiFieldBinding entry : PanoramaApiFieldBinding.all()) {
            assertEquals(PanoramaApiFieldBinding.Status.UNVERIFIED, entry.status(),
                    "entry for " + entry.role() + " is not UNVERIFIED");
            assertTrue(entry.apiFieldPath().isPresent());
        }
    }

    /** AC-7: no bound element path leaks outside this one file. */
    @Test
    void boundFieldPathsAppearNowhereElseInProductionSource() throws IOException {
        Path packageDir = discoveryPanMainSourceDir();
        assertTrue(Files.isDirectory(packageDir), "expected to find " + packageDir);

        List<String> boundFieldPaths = PanoramaApiFieldBinding.all().stream()
                .map(e -> e.apiFieldPath().orElseThrow())
                .toList();

        List<String> violations = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("PanoramaApiFieldBinding.java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            for (String fieldPath : boundFieldPaths) {
                                if (content.contains("\"" + fieldPath + "\"")) {
                                    violations.add(p.getFileName() + " contains bound field literal \"" + fieldPath + "\"");
                                }
                            }
                        } catch (IOException e) {
                            fail("could not read " + p + ": " + e.getMessage());
                        }
                    });
        }
        assertFalse(!violations.isEmpty(), "field-binding isolation violated: " + violations);
    }

    private static Path discoveryPanMainSourceDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "securityexpert",
                "nexus", "ui2", "discovery", "pan");
    }
}
