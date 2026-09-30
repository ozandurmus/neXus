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
}
