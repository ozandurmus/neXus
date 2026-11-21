package com.securityexpert.nexus.ui2.service.notification;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Read-only view of the latest daily security scan for Administration > Platform status and
 * {@code GET /api/v2/security/scan-summary/latest}. The scanner writes the files; this only reads them, and only what
 * {@link SecurityScanSummary} already validated: counts, rule ids, a path or image/package, status and a fix flag.
 */
@Service
public class SecurityScanReport {
    /** The daily scan is due at 02:30; older than this means a run was missed or failed before writing. */
    public static final Duration STALE_AFTER = Duration.ofHours(26);
    private static final Pattern DAILY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-daily-[A-Za-z0-9]+\\.json");

    private final NotificationSettingsStore settingsStore;

    public SecurityScanReport(NotificationSettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    public Map<String, Object> latest() {
        Path directory = Path.of(OverwatchEnv.getOrDefault("UI2_SECURITY_SUMMARY_DIR", "/var/lib/nexus-security/notifications"));
        return latest(directory, Instant.now(), settingsStore.read());
    }

    static Map<String, Object> latest(Path directory, Instant now, NotificationSettings settings) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mail", mail(settings));
        SecurityScanSummary.Parsed newest = null;
        int unreadable = 0;
        if (Files.isDirectory(directory)) {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.sorted().toList()) {
                    if (!DAILY.matcher(path.getFileName().toString()).matches()) continue;
                    try {
                        SecurityScanSummary.Parsed parsed = SecurityScanSummary.parse(path);
                        if (newest == null || parsed.at().isAfter(newest.at())) newest = parsed;
                    } catch (IOException | RuntimeException e) {
                        unreadable++;
                    }
                }
            } catch (IOException e) {
                unreadable++;
            }
        }
        out.put("stale_after_hours", STALE_AFTER.toHours());
        if (newest == null) {
            out.put("available", false);
            out.put("reason", unreadable > 0 ? "SUMMARY_UNREADABLE" : "NO_DAILY_SUMMARY");
            return out;
        }
        long ageSeconds = Math.max(0, Duration.between(newest.at(), now).getSeconds());
        out.put("available", true);
        out.put("generated_at", newest.at().toString());
        out.put("age_hours", ageSeconds / 3600);
        out.put("stale", ageSeconds > STALE_AFTER.getSeconds());
        out.put("passed", newest.passed());
        out.put("scan_errors", newest.errors());
        out.put("blocking", newest.blocking());
        out.put("counts", newest.counts());
        out.put("findings_listed_in_summary", newest.hasFindings());
        out.put("findings_total", newest.findingsTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecurityScanSummary.Finding f : newest.findings()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", f.tool());
            row.put("rule", f.rule());
            row.put("severity", f.severity());
            row.put("location", f.location());
            row.put("status", f.status());
            row.put("fix_available", f.fixAvailable());
            rows.add(row);
        }
        out.put("findings", rows);
        List<Map<String, Object>> failed = new ArrayList<>();
        for (SecurityScanSummary.Failed f : newest.failed()) failed.add(Map.of("file", f.file(), "cause", f.cause()));
        out.put("failed", failed);
        return out;
    }

    /** Whether the daily results can reach anyone: the route is on, SMTP is configured and a recipient exists. */
    static Map<String, Object> mail(NotificationSettings settings) {
        NotificationRoute route = settings.routes() == null ? null : settings.routes().stream()
                .filter(r -> "security_scan".equals(r.type())).findFirst().orElse(null);
        boolean routeEnabled = route != null && route.enabled();
        boolean smtp = settings.smtpEnabled() && settings.smtpHost() != null && !settings.smtpHost().isBlank()
                && settings.smtpFrom() != null && !settings.smtpFrom().isBlank();
        boolean recipients = route != null && !route.effectiveRecipients(settings).isEmpty();
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("route_enabled", routeEnabled);
        mail.put("smtp_configured", smtp);
        mail.put("recipients_configured", recipients);
        mail.put("delivering", routeEnabled && smtp && recipients);
        mail.put("last_sent_at", route == null || route.lastSentAt() == null ? null : route.lastSentAt().toString());
        mail.put("last_error", route == null ? null : route.lastError());
        return mail;
    }
}
