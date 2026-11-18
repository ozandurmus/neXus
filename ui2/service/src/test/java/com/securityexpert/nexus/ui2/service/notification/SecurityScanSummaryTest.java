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

    private com.fasterxml.jackson.databind.node.ObjectNode base(String at) {
        var root = new ObjectMapper().createObjectNode();
        root.put("schema_version", 1).put("generated_at", at).put("scan_errors", 0).put("blocking", 0).put("passed", true);
        var counts = root.putObject("counts");
        for (String tool : List.of("semgrep", "gitleaks", "trivy", "zap")) {
            var severities = counts.putObject(tool);
            for (String severity : List.of("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                severities.putObject(severity).put("new", 0).put("fixed", 0).put("accepted", 0).put("existing", 0);
            }
        }
        return root;
    }

    private static void finding(com.fasterxml.jackson.databind.node.ObjectNode root, String tool, String rule, String severity,
            String location, String status, boolean fix) {
        if (!root.has("findings")) root.putArray("findings");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("findings")).addObject().put("tool", tool).put("rule", rule)
                .put("severity", severity).put("location", location).put("status", status).put("fix_available", fix);
        root.put("findings_total", root.path("findings").size());
    }

    @Test
    void highAndMediumListIsRenderedAndBoundedInMailAndOldSchemaStillParses() throws Exception {
        var root = base("2026-11-18T02:40:00Z");
        finding(root, "trivy", "CVE-2026-0001", "CRITICAL", "registry/app: libfoo", "new", true);
        finding(root, "semgrep", "rule.x", "MEDIUM", "src/A.java", "existing", false);
        Files.writeString(directory.resolve("2026-11-18-daily-abc.json"), root.toString());
        Files.writeString(directory.resolve("2026-11-17-daily-old.json"), base("2026-11-17T02:40:00Z").toString());
        var batch = SecurityScanSummary.collect(directory, Instant.EPOCH);
        assertEquals(2, batch.lines().size());
        String old = batch.lines().get(0), current = batch.lines().get(1);
        assertEquals(2, SecurityScanSummary.parse(directory.resolve("2026-11-18-daily-abc.json")).findings().size());
        assertFalse(SecurityScanSummary.parse(directory.resolve("2026-11-17-daily-old.json")).hasFindings());
        assertFalse(old.contains("High and medium"));
        assertTrue(current.contains("High and medium findings: 2"));
        assertTrue(current.contains("CRITICAL new  trivy CVE-2026-0001  registry/app: libfoo  fix available"));
        assertTrue(current.contains("MEDIUM existing  semgrep rule.x  src/A.java"));
        // Over the mail bound the rest is referred to the console.
        for (int i = 0; i < 150; i++) finding(root, "trivy", "CVE-2026-1" + i, "MEDIUM", "registry/app: p", "existing", false);
        Files.writeString(directory.resolve("2026-11-18-daily-abc.json"), root.toString());
        String longLine = SecurityScanSummary.collect(directory, Instant.parse("2026-11-17T12:00:00Z")).lines().getFirst();
        assertTrue(longLine.contains("and 52 more: see Administration > Platform status"), longLine.substring(longLine.length() - 120));
    }

    @Test
    void findingFieldsAreShapeCheckedAndFailedListIsAccepted() throws Exception {
        var root = base("2026-11-18T02:40:00Z");
        finding(root, "semgrep", "rule.x", "MEDIUM", "src/A.java", "new", false);
        Path file = directory.resolve("2026-11-18-daily-abc.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("findings").get(0)).put("location", "src/A.java:12 token=abc");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.parse(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("findings").get(0)).put("location", "src/A.java");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("findings").get(0)).put("description", "extra");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.parse(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("findings").get(0)).remove("description");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("findings").get(0)).put("severity", "LOW");
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.parse(file));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("findings").get(0)).put("severity", "HIGH");
        root.putArray("failed").addObject().put("file", "trivy-image-0.json").put("cause", "scanner failed");
        Files.writeString(file, root.toString());
        var parsed = SecurityScanSummary.parse(file);
        assertEquals("trivy-image-0.json", parsed.failed().getFirst().file());
        assertTrue(SecurityScanSummary.collect(directory, Instant.EPOCH).lines().getFirst().contains("scan failures trivy-image-0.json (scanner failed)"));
        root.put("findings_total", 0);
        Files.writeString(file, root.toString());
        assertThrows(IOException.class, () -> SecurityScanSummary.parse(file));
    }
}
