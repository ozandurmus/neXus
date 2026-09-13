package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * CS-6b/AC-6: the channel port is derived from an observed established row
 * or supplied by configuration -- never a numeric literal in the adapter's
 * production sources. Scans for the shape a hard-coded port would take:
 * a "port"-named identifier immediately followed by an integer literal
 * (assignment, constructor argument or map/record positional argument).
 */
class NoNumericPortLiteralTest {

    private static final Pattern PORT_LITERAL = Pattern.compile("(?i)port\\w*\\s*[:=(,]\\s*(?!0\\b)-?\\d+");

    @Test
    void adapterProductionSourceContainsNoNumericPortLiteral() throws IOException {
        Path packageDir = adapterMainSourceDir();
        assertTrue(Files.isDirectory(packageDir), "expected to find " + packageDir);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            Matcher matcher = PORT_LITERAL.matcher(content);
                            while (matcher.find()) {
                                violations.add(p.getFileName() + ": \"" + matcher.group() + "\"");
                            }
                        } catch (IOException e) {
                            fail("could not read " + p + ": " + e.getMessage());
                        }
                    });
        }
        assertTrue(violations.isEmpty(), "numeric port literal found in adapter production source: " + violations);
    }

    private static Path adapterMainSourceDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "securityexpert",
                "nexus", "ui2", "worker", "discovery", "cp");
    }
}
