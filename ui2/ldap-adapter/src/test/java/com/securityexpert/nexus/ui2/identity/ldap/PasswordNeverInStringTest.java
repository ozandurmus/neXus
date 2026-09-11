package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Contract §8 test 16 / C3 §2.3: a static scan of the login path's source —
 * fails if any {@code String} constructor/method receives the raw
 * password. This is the module-local half of the test; the same scan is
 * not repeated by the architecture-tests module (which already inspects
 * bytecode, not source text, for its own DIR rules).
 *
 * <p>Every variable this class inspects that could hold the raw password is
 * named {@code password} in {@link UnboundIdOperatorBindAdapter} and
 * {@link UnboundIdRevalidationAdapter}; this scan asserts none of those
 * files contains the pattern {@code new String(password} or
 * {@code String.valueOf(password}.</p>
 */
class PasswordNeverInStringTest {

    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("new\\s+String\\s*\\(\\s*password"),
            Pattern.compile("String\\s*\\.\\s*valueOf\\s*\\(\\s*password"),
            Pattern.compile("password\\s*\\.\\s*toString\\s*\\("));

    @Test
    void loginPathSourceNeverConstructsAStringFromThePassword() throws IOException {
        Path srcDir = Paths.get(System.getProperty("user.dir"))
                .resolve("src/main/java/com/securityexpert/nexus/ui2/identity/ldap");
        assertTrue(Files.isDirectory(srcDir), "expected " + srcDir + " to exist");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(srcDir)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    for (Pattern pattern : FORBIDDEN) {
                        if (pattern.matcher(content).find()) {
                            violations.add(p + " matches forbidden pattern " + pattern.pattern());
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOExceptionForTest(e);
                }
            });
        }
        assertTrue(violations.isEmpty(), "password must never reach a String: " + violations);
    }

    private static final class UncheckedIOExceptionForTest extends RuntimeException {
        UncheckedIOExceptionForTest(IOException cause) {
            super(cause);
        }
    }
}
