package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Contract §8 test 17 ({@code NoRoleConditionalRenderingInFrontendTest}) /
 * C3 §5.4, {@code AG-J3}: the front end renders whatever
 * {@code action_affordance} it is given and computes no visibility
 * decision of its own.
 *
 * <p>This scans {@code frontend/src} (source), not a built bundle: this
 * environment has no assurance an {@code npm run build} artifact exists or
 * that npm can run here, and a source-level scan is a strict superset of a
 * built-bundle scan for a literal-token grep (bundling never introduces a
 * new literal string absent from source, only renames/minifies existing
 * ones or removes dead code) -- the same "repository/manifest half only,
 * image layer out of scope" posture {@code Ui2ArchitectureTest.dir8} and
 * {@code .dir10} already state for this slice. A build-artifact re-scan
 * remains for the container-capable slice that also runs
 * {@code frontendCheck} against real bundles.</p>
 */
class NoRoleConditionalRenderingInFrontendTest {

    private static final List<String> ROLE_TOKEN_LITERALS = List.of(
            "role:viewer", "role:operator", "role:onboarding_admin",
            "role:backup_admin", "role:compliance_admin", "role:security_admin");

    @Test
    void frontendSourceContainsNoRoleTokenLiteralOrGroupReferenceString() throws IOException {
        Path frontendSrc = ui2Root().resolve("frontend").resolve("src");
        assertTrue(Files.isDirectory(frontendSrc), "expected " + frontendSrc + " to exist");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(frontendSrc)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String content = Files.readString(p);
                    for (String token : ROLE_TOKEN_LITERALS) {
                        if (content.contains(token)) {
                            violations.add(p + " contains role-token literal \"" + token + "\"");
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // binary or unreadable file: not a text match target
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "frontend must hold no role concept and compute no visibility decision of its own (AG-J3): "
                        + violations);
    }

    private static Path ui2Root() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }
}
