package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The packaged frontend must be the one `frontendBuild` produces from
 * `ui2/frontend/src/`, not a bundle carried over from an earlier build. This
 * runs against the classpath resources `:service:processResources` actually
 * produced for this test run (Gradle's `test`/`unitTest` task depends on it),
 * so a stale, committed bundle cannot pass it the way a mocked or hand-built
 * fixture could.
 */
class FrontendBundleFreshnessTest {

    /**
     * A label introduced by the frontend source commits this bundle must
     * reflect (AdministrationScreen's "Project plan" tab). It is absent from
     * every frontend bundle built before those commits, so its presence here
     * is evidence the shipped asset was actually built from current source
     * rather than replayed from an older one.
     */
    private static final String MARKER_PRESENT_ONLY_IN_CURRENT_SOURCE = "Project plan";

    @Test
    void packagedFrontendCarriesAStringFromCurrentSource() throws IOException {
        String indexHtml = readClasspathResource("/static/index.html");
        String scriptSrc = extractModuleScriptSrc(indexHtml);

        // The path in index.html is server-root-relative (Vite serves it from
        // "/"), while the same file is packaged under the "static/" classpath
        // prefix Spring Boot's static-resource handler expects.
        String bundledScript = readClasspathResource("/static" + scriptSrc);
        assertTrue(
                bundledScript.contains(MARKER_PRESENT_ONLY_IN_CURRENT_SOURCE),
                "the packaged frontend script does not carry '" + MARKER_PRESENT_ONLY_IN_CURRENT_SOURCE
                        + "'; the classpath static/ resources were not built from the current frontend source");
    }

    private static String extractModuleScriptSrc(String indexHtml) {
        var matcher = java.util.regex.Pattern.compile("<script[^>]*type=\"module\"[^>]*src=\"([^\"]+)\"").matcher(indexHtml);
        assertTrue(matcher.find(), "static/index.html does not reference a module script");
        return matcher.group(1);
    }

    private static String readClasspathResource(String path) throws IOException {
        try (InputStream in = FrontendBundleFreshnessTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath resource " + path + " is missing -- was the frontend built?");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * AC-3: the source tree itself must never carry a prebuilt copy again --
     * the whole point is that `ui2/service/src/main/resources/static/` stays
     * empty and the frontend arrives only through `frontendBuild`'s output.
     * Checked here too (not only from the repository-root Python suite) so
     * this module's own gate fails on the regression without depending on
     * another test file being run in the same session.
     */
    @Test
    void noPrebuiltFrontendBundleIsCommittedUnderServiceResources() throws IOException {
        Path serviceModuleRoot = Path.of("").toAbsolutePath();
        Path committedStatic = serviceModuleRoot.resolve("src/main/resources/static");
        if (!Files.exists(committedStatic)) {
            return;
        }
        try (Stream<Path> files = Files.walk(committedStatic)) {
            assertTrue(
                    files.noneMatch(Files::isRegularFile),
                    committedStatic + " carries a committed file; the frontend must come from frontendBuild's "
                            + "output at build time, not from a copy checked into source control");
        }
    }
}
