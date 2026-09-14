package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 14I OR-1 / AC-4: "no HTTP route of any kind returns bytes, a path or a
 * decrypt affordance." This test greps every {@code service} source file
 * for a route mapping that looks like an artefact retrieval/download and
 * fails if one exists -- the operator's only retrieval path is {@code
 * ui2/cli} (OR-2), never HTTP.
 */
class BackupNoHttpRetrievalRouteTest {

    /** Any {@code @*Mapping} route whose path suggests bytes/download/retrieval/decrypt leaving the service. */
    private static final Pattern SUSPECT_ROUTE = Pattern.compile(
            "@(?:Get|Post)Mapping\\([^)]*(?:retriev|download|decrypt|/backup/read|/backup/fetch)[^)]*\\)",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noServiceRouteRetrievesOrDownloadsAnArtefact() throws IOException {
        Path serviceMain = Path.of(System.getProperty("user.dir")).resolve("src/main/java");
        List<String> offenders = new ArrayList<>();
        if (Files.isDirectory(serviceMain)) {
            try (Stream<Path> files = Files.walk(serviceMain)) {
                for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(path);
                    if (SUSPECT_ROUTE.matcher(content).find()) {
                        offenders.add(path.toString());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "14I OR-1: no HTTP route may retrieve/download/decrypt an artefact, but found: " + offenders);
    }
}
