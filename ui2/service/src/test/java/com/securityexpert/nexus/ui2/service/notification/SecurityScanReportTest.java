package com.securityexpert.nexus.ui2.service.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class SecurityScanReportTest {
    @TempDir Path directory;

    private static NotificationSettings settings(boolean smtp, boolean route, String recipients) {
        return new NotificationSettings(false, "", 514, "udp", 1, smtp, smtp ? "relay.example.invalid" : "", 25, false,
                smtp ? "nexus@example.invalid" : "", recipients, false, false, Instant.EPOCH,
                List.of(new NotificationRoute("security_scan", route, "", null, null)), NotificationSettings.IDENTITY_MASKED);
    }

    private void write(String name, String at, boolean withFinding) throws Exception {
        var root = new ObjectMapper().createObjectNode();
        root.put("schema_version", 1).put("generated_at", at).put("scan_errors", 0).put("blocking", 0).put("passed", true);
        var counts = root.putObject("counts");
        for (String tool : List.of("semgrep", "gitleaks", "trivy", "zap")) {
            var severities = counts.putObject(tool);
            for (String severity : List.of("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")) {
                severities.putObject(severity).put("new", 0).put("fixed", 0).put("accepted", 0).put("existing", 0);
            }
        }
        if (withFinding) {
            root.putArray("findings").addObject().put("tool", "trivy").put("rule", "CVE-2026-0001").put("severity", "HIGH")
                    .put("location", "registry/app: libfoo").put("status", "new").put("fix_available", true);
            root.put("findings_total", 1);
        }
        Files.writeString(directory.resolve(name), root.toString());
    }

    @Test
    void latestDailyOnlyAndStaleAfterTwentySixHours() throws Exception {
        write("2026-11-17-daily-a1.json", "2026-11-17T02:40:00Z", false);
        write("2026-11-18-daily-b2.json", "2026-11-18T02:40:00Z", true);
        write("2026-11-18-dast-c3.json", "2026-11-18T03:40:00Z", false);
        Map<String, Object> fresh = SecurityScanReport.latest(directory, Instant.parse("2026-11-18T08:40:00Z"), settings(true, true, "a@example.invalid"));
        assertEquals(true, fresh.get("available"));
        assertEquals("2026-11-18T02:40:00Z", fresh.get("generated_at"));
        assertEquals(6L, fresh.get("age_hours"));
        assertEquals(false, fresh.get("stale"));
        assertEquals(1, ((List<?>) fresh.get("findings")).size());
        assertEquals(true, ((Map<?, ?>) fresh.get("mail")).get("delivering"));
        Map<String, Object> stale = SecurityScanReport.latest(directory, Instant.parse("2026-11-19T04:41:00Z"), settings(true, true, "a@example.invalid"));
        assertEquals(true, stale.get("stale"));
    }

    @Test
    void mailStateNamesWhatIsMissingAndAbsentOrBrokenSummariesAreReported() throws Exception {
        Map<String, Object> none = SecurityScanReport.latest(directory, Instant.now(), settings(false, false, ""));
        assertEquals(false, none.get("available"));
        assertEquals("NO_DAILY_SUMMARY", none.get("reason"));
        Map<?, ?> mail = (Map<?, ?>) none.get("mail");
        assertEquals(false, mail.get("route_enabled"));
        assertEquals(false, mail.get("smtp_configured"));
        assertEquals(false, mail.get("recipients_configured"));
        assertEquals(false, mail.get("delivering"));
        assertEquals(false, SecurityScanReport.mail(settings(true, false, "a@example.invalid")).get("delivering"));
        assertEquals(false, SecurityScanReport.mail(settings(true, true, "")).get("delivering"));
        Files.writeString(directory.resolve("2026-11-18-daily-bad.json"), "{\"schema_version\":1,\"leak\":\"x\"}");
        assertEquals("SUMMARY_UNREADABLE", SecurityScanReport.latest(directory, Instant.now(), settings(true, true, "a@example.invalid")).get("reason"));
    }
}
