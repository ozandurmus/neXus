package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementApiFieldBinding;

/**
 * FB-2/AC-4: no production class in the adapter package contains a string
 * literal equal to a bound management-API field name -- every one of them
 * is read only through {@code ManagementApiFieldBinding.forRole(...).apiField()}.
 * Mirrors platform-core's own {@code ManagementApiFieldBindingTest}, pointed
 * at the worker adapter's main source tree instead.
 */
class FieldBindingIsolationTest {

    @Test
    void adapterProductionSourceContainsNoBoundFieldNameLiteral() throws IOException {
        Path packageDir = adapterMainSourceDir();
        assertTrue(Files.isDirectory(packageDir), "expected to find " + packageDir);

        List<String> boundFieldNames = ManagementApiFieldBinding.all().stream()
                .map(entry -> entry.apiField().orElseThrow())
                .toList();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir)) {
            files.filter(p -> p.toString().endsWith(".java"))
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

    private static Path adapterMainSourceDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "securityexpert",
                "nexus", "ui2", "worker", "discovery", "cp");
    }
}
