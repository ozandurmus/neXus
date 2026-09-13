package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * C3B contract (docs/design/UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md,
 * FROZEN 2026-09-13) §4 acceptance test 3: BOOT-3's two halves, each
 * proved by a whole-tree source scan (the same tool
 * {@code NoRoleConditionalRenderingInFrontendTest} already uses for a
 * literal-token invariant).
 */
class NoHardcodedBootstrapVerifierOrPasswordTest {

    /** BOOT-3: only these two files may construct a {@code Verifier} directly -- everywhere else goes through {@code Argon2PasswordHasher.hash(...)}. */
    private static final Set<String> FILES_ALLOWED_TO_CONSTRUCT_A_VERIFIER = Set.of(
            "platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/Argon2PasswordHasher.java",
            "persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/identity/LocalCredentialRecord.java");

    private static final Pattern VERIFIER_CONSTRUCTION = Pattern.compile("new\\s+(Argon2PasswordHasher\\.)?Verifier\\s*\\(");

    /** BOOT-3a: the one file the two initial passwords may be spelled out in. */
    private static final String PASSWORD_CONSTANT_HOME =
            "service/src/main/java/com/securityexpert/nexus/ui2/service/boot/BootstrapCredentialDefaults.java";

    private static final Path BOOTSTRAP_SEEDING_PACKAGE =
            Paths.get("service/src/main/java/com/securityexpert/nexus/ui2/service/boot");

    @Test
    void noMigrationInsertsIntoLocalCredentials() throws IOException {
        Path migrations = ui2Root().resolve("service/src/main/resources/db/migration");
        assertTrue(Files.isDirectory(migrations), "expected " + migrations + " to exist");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(migrations)) {
            files.filter(p -> p.toString().endsWith(".sql")).forEach(p -> {
                try {
                    String content = Files.readString(p).toLowerCase(java.util.Locale.ROOT);
                    if (content.contains("insert into local_credentials")) {
                        violations.add(p + " inserts into local_credentials");
                    }
                } catch (IOException e) {
                    throw new UncheckedIOExceptionForTest(e);
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "no migration may seed local_credentials: BOOT-3 forbids a verifier literal, and a migration is "
                        + "where one would end up: " + violations);
    }

    @Test
    void onlyTheHasherAndTheReadRecordConstructAVerifierDirectly() throws IOException {
        Path ui2Root = ui2Root();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ui2Root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(java.io.File.separator + "build" + java.io.File.separator))
                    .forEach(p -> {
                        String relative = ui2Root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                        try {
                            String content = Files.readString(p);
                            if (VERIFIER_CONSTRUCTION.matcher(content).find()
                                    && !FILES_ALLOWED_TO_CONSTRUCT_A_VERIFIER.contains(relative)) {
                                violations.add(relative);
                            }
                        } catch (IOException | RuntimeException ignored) {
                            // binary or unreadable file: not a text match target
                        }
                    });
        }
        assertTrue(violations.isEmpty(),
                "no verifier literal may exist anywhere in the tracked tree (BOOT-3): a Verifier must be produced "
                        + "by Argon2PasswordHasher.hash(...), never constructed directly, outside "
                        + FILES_ALLOWED_TO_CONSTRUCT_A_VERIFIER + ". Found direct construction in: " + violations);
    }

    @Test
    void theInitialPasswordsAreNeverRespelledOutsideTheirSingleNamedConstantHome() throws IOException {
        Path packageDir = ui2Root().resolve(BOOTSTRAP_SEEDING_PACKAGE);
        assertTrue(Files.isDirectory(packageDir), "expected " + packageDir + " to exist");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(packageDir)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String relative = ui2Root().relativize(p).toString().replace(java.io.File.separatorChar, '/');
                if (relative.equals(PASSWORD_CONSTANT_HOME)) {
                    return; // the one permitted home (BOOT-3a)
                }
                try {
                    String content = Files.readString(p);
                    // Quoted-literal form only (BOOT-3a is about a re-spelled
                    // *value*, i.e. a string literal in code) -- a javadoc
                    // sentence naming the identity ("creates nexusadmin and
                    // claudeadmin") is prose, not a re-spelling.
                    if (content.contains("\"nexusadmin\"") || content.contains("\"claudeadmin\"")) {
                        violations.add(relative);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOExceptionForTest(e);
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "the bootstrap identity names/passwords must be referenced from " + PASSWORD_CONSTANT_HOME
                        + " (BOOT-3a), never re-spelled in the same package's other files: " + violations);
    }

    private static Path ui2Root() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    private static final class UncheckedIOExceptionForTest extends RuntimeException {
        UncheckedIOExceptionForTest(IOException cause) {
            super(cause);
        }
    }
}
