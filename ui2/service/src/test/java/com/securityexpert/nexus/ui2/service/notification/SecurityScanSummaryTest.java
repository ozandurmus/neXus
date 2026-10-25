package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

class SecurityScanSummaryTest {
    @TempDir Path directory;

    @Test
    void countsOnlyRouteHonorsWatermarkAndRejectsExtraFields() throws Exception {
        var json = new ObjectMapper();
        var root = json.createObjectNode();
        root.put("schema_version", 1).put("generated_at", "2026-09-30T02:00:00Z")
            .put("scan_errors", 0).put("blocking", 1).put("passed", false);
        var counts = root.putObject("counts");
        for (String tool : List.of("semgrep", "gitleaks", "trivy", "zap")) {
            var severities = counts.putObject(tool);
            for (String severity : List.of("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                severities.putObject(severity).put("new", tool.equals("semgrep") && severity.equals("HIGH") ? 1 : 0)
                    .put("fixed", 0).put("accepted", 0).put("existing", 0);
            }
        }
        Path file = directory.resolve("synthetic.json");
        Files.writeString(file, root.toString());
        var batch = SecurityScanSummary.collect(directory, Instant.EPOCH);
        assertEquals(1, batch.lines().size());
        assertTrue(batch.lines().getFirst().contains("semgrep/HIGH new=1 fixed=0 accepted=0"));
        assertEquals(Instant.parse("2026-09-30T02:00:00Z"), batch.time());
        assertTrue(SecurityScanSummary.collect(directory, batch.time()).lines().isEmpty());
        assertTrue(NotificationMailRouter.subject("security_scan", 1).contains("Security scans"));
        root.put("source_contents", "synthetic prohibited content");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.collect(directory, Instant.EPOCH));
        root.remove("source_contents");
        root.put("blocking", "synthetic prohibited content");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.collect(directory, Instant.EPOCH));
    }

    @Test
    void acceptedReviewIsOptionalShapeCheckedAndCarriesIdsOnly() throws Exception {
        var json = new ObjectMapper();
        var root = json.createObjectNode();
        root.put("schema_version", 1).put("generated_at", "2026-10-25T02:00:00Z")
            .put("scan_errors", 0).put("blocking", 0).put("passed", true);
        var counts = root.putObject("counts");
        for (String tool : List.of("semgrep", "gitleaks", "trivy", "zap")) {
            var severities = counts.putObject(tool);
            for (String severity : List.of("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                severities.putObject(severity).put("new", 0).put("fixed", 0).put("accepted", 0).put("existing", 0);
            }
        }
        var review = root.putObject("accepted_review");
        review.put("fix_available", 1).put("due_soon", 0).put("past_review", 0).put("not_reported", 0)
            .put("attention", true);
        review.putArray("items").addObject().put("id", "CVE-2026-91415").put("package", "java-21-openjdk-headless")
            .put("status", "fix_available");
        Path file = directory.resolve("review.json");
        Files.writeString(file, root.toString());
        var line = SecurityScanSummary.collect(directory, Instant.EPOCH).lines().getFirst();
        assertTrue(line.contains("accepted-review attention=true fix_available=1"));
        assertTrue(line.contains("fix_available CVE-2026-91415 java-21-openjdk-headless"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) review.path("items").get(0)).put("package", "bad value;x");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.collect(directory, Instant.EPOCH));
        review.put("note", "extra");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.collect(directory, Instant.EPOCH));
    }
}
