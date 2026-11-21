package com.securityexpert.nexus.ui2.service.notification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe, dependency-free HTML and plain-text notification mail renderer with one template per
 * {@link NotificationRoute#TYPES} route type.
 *
 * <p>The mail carries only what helps an operator act: what happened, where, when and why in one short phrase.
 * Times are shown in Europe/Istanbul local time. All styles are inline on HTML tables for Outlook compatibility; no
 * external CSS, remote images, tracking pixels, or scripts are emitted, and every variable value is HTML-escaped.</p>
 */
public final class NotificationMailTemplate {

    static final int MAX_ROWS = 200;
    static final int MAX_FINDING_ROWS = 50;
    private static final int MAX_CELL_CHARS = 160;

    static final ZoneId LOCAL_ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Pattern ISO_INSTANT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z");

    private static final String FONT_STACK = "Segoe UI,-apple-system,BlinkMacSystemFont,Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO_STACK = "Consolas,ui-monospace,SFMono-Regular,Menlo,Monaco,monospace";
    private static final String JOB_PATH_PREFIX = "/operations/jobs/";
    static final String CREDENTIALS_PATH = "/?screen=administration&tab=registry&section=credentials";
    static final String CREDENTIALS_LABEL = "Administration > Credentials";

    public enum Band {
        RED("#dc2626", "#fef2f2", "#991b1b", "HIGH"),
        AMBER("#d97706", "#fffbeb", "#92400e", "WARNING"),
        NEUTRAL("#475569", "#f1f5f9", "#334155", "INFO");

        final String barHex;
        final String pillBgHex;
        final String pillTextHex;
        final String label;

        Band(String barHex, String pillBgHex, String pillTextHex, String label) {
            this.barHex = barHex;
            this.pillBgHex = pillBgHex;
            this.pillTextHex = pillTextHex;
            this.label = label;
        }
    }

    public record RenderedMail(String type, String subject, String text, String html, Band band) {}

    public record TypeTemplateSpec(
            String type,
            String title,
            Band defaultBand,
            String whatToDo,
            String screenPath,
            String screenLabel) {}

    /** One table cell; {@code path} is a path under the public base URL and turns the cell into a link. */
    record Cell(String text, String linkText, String path) {
        static Cell of(String text) {
            return new Cell(text == null ? "" : text, null, null);
        }

        static Cell link(String linkText, String path) {
            return new Cell("", linkText, path);
        }

        static Cell textWithLink(String text, String linkText, String path) {
            return new Cell(text == null ? "" : text, linkText, path);
        }
    }

    record Table(List<String> headers, List<List<Cell>> rows) {}

    private static final Map<String, TypeTemplateSpec> SPECS = buildSpecs();

    private NotificationMailTemplate() {}

    private static Map<String, TypeTemplateSpec> buildSpecs() {
        Map<String, TypeTemplateSpec> m = new LinkedHashMap<>();
        m.put("admin_event", new TypeTemplateSpec("admin_event", NotificationRoute.TITLES.get("admin_event"),
                Band.NEUTRAL,
                "Confirm each change was made by someone authorized and expected.",
                "/?screen=administration&tab=audit", "Administration > Audit log"));
        m.put("login_security", new TypeTemplateSpec("login_security", NotificationRoute.TITLES.get("login_security"),
                Band.AMBER,
                "Check that each of these sign-ins was done by an authorized operator.",
                "/?screen=administration&tab=access", "Administration > Access and identity"));
        m.put("backup_failure", new TypeTemplateSpec("backup_failure", NotificationRoute.TITLES.get("backup_failure"),
                Band.AMBER,
                "Check that each device is reachable and its backup credential works, then run the backup again.",
                "/?screen=backups", "Backups"));
        m.put("job_failure", new TypeTemplateSpec("job_failure", NotificationRoute.TITLES.get("job_failure"),
                Band.AMBER,
                "Open each job to read the reason, then retry it or reconcile the device.",
                "/?screen=operations&tab=jobs", "Operations > Jobs"));
        m.put("config_change", new TypeTemplateSpec("config_change", NotificationRoute.TITLES.get("config_change"),
                Band.NEUTRAL,
                "Compare each change with an approved change ticket.",
                "/?screen=changes", "Changes"));
        m.put("compliance_regression", new TypeTemplateSpec("compliance_regression",
                NotificationRoute.TITLES.get("compliance_regression"), Band.AMBER,
                "Open Compliance, read the failing control on each device and fix the configuration.",
                "/?screen=compliance", "Compliance"));
        m.put("device_health", new TypeTemplateSpec("device_health", NotificationRoute.TITLES.get("device_health"),
                Band.AMBER,
                "Check the network path and the SSH or TLS trust of each device before collecting again.",
                "/?screen=operations&tab=health", "Operations > Device health"));
        m.put("security_scan", new TypeTemplateSpec("security_scan", NotificationRoute.TITLES.get("security_scan"),
                Band.RED,
                "Fix the new HIGH findings first, and accept a finding only with an owner and a review date.",
                "/?screen=administration&tab=platform-status", "Administration > Platform status"));
        m.put("device_crash_file", new TypeTemplateSpec("device_crash_file",
                NotificationRoute.TITLES.get("device_crash_file"), Band.RED,
                "Open the diagnostic job and collect a support bundle if the crash repeats.",
                "/?screen=diagnostics", "Diagnostics"));
        m.put("backup_size_anomaly", new TypeTemplateSpec("backup_size_anomaly",
                NotificationRoute.TITLES.get("backup_size_anomaly"), Band.AMBER,
                "Compare the backup with the previous one and exclude the firewall logs if that is the cause.",
                "/?screen=backups", "Backups"));
        return Map.copyOf(m);
    }

    public static TypeTemplateSpec spec(String type) {
        TypeTemplateSpec spec = SPECS.get(type);
        if (spec == null) {
            throw new IllegalArgumentException("unknown notification type: " + type);
        }
        return spec;
    }

    public static Map<String, TypeTemplateSpec> specs() {
        return SPECS;
    }

    public static RenderedMail render(String type, NotificationMailRouter.Batch batch) {
        return render(type, batch, MailBrand.name(), MailBrand.publicBaseUrl(), MailBrand.environment());
    }

    public static RenderedMail render(String type, NotificationMailRouter.Batch batch, String productName,
            String baseUrl, String environment) {
        TypeTemplateSpec spec = spec(type);
        String brand = MailBrand.cleanLabel(productName, "Overwatch");
        String env = MailBrand.cleanLabel(environment, "production");
        String normalizedBaseUrl = MailBrand.normalizeBaseUrl(baseUrl);
        int eventCount = batch.lines().size();
        String subject = brand + " – " + spec.title() + ": " + eventCount + " event(s)";
        List<List<String>> rows = !batch.rows().isEmpty() || !batch.scans().isEmpty()
                ? batch.rows()
                : parseLines(type, batch.lines());
        Band band = resolveBand(spec, rows, batch.scans());
        String word = bandWord(type, band);

        String summary;
        Table table = null;
        SecurityView security = null;
        if ("security_scan".equals(type)) {
            security = securityView(rows, batch.scans());
            summary = security.summary();
        } else {
            table = project(type, rows, normalizedBaseUrl);
            summary = summarize(type, rows);
        }
        String text = buildText(spec, brand, env, normalizedBaseUrl, word, summary, table, security);
        String html = buildHtml(spec, brand, env, normalizedBaseUrl, band, word, summary, table, security);
        return new RenderedMail(type, subject, text, html, band);
    }

    /** Renders a synthetic sample mail for "Send test" and offline template previews. */
    public static RenderedMail renderSample(String type) {
        return renderSample(type, MailBrand.name(), MailBrand.publicBaseUrl(), MailBrand.environment());
    }

    public static RenderedMail renderSample(String type, String productName, String baseUrl, String environment) {
        return render(type, sampleBatch(type), productName, baseUrl, environment);
    }

    /** Writes one synthetic sample HTML file per {@link NotificationRoute#TYPES} route type into {@code directory}. */
    public static void writeSamples(java.nio.file.Path directory, String productName, String baseUrl,
            String environment) throws java.io.IOException {
        java.nio.file.Files.createDirectories(directory);
        for (String type : NotificationRoute.TYPES) {
            RenderedMail sample = renderSample(type, productName, baseUrl, environment);
            java.nio.file.Files.writeString(directory.resolve(type + ".html"), sample.html(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Fallback HTML wrapper when {@link SmtpRelaySender#send(NotificationSettings, List, String, String)} is called directly. */
    static String renderPlainFallback(String subject, String textBody) {
        String brand = MailBrand.name();
        String env = MailBrand.environment();
        String safeTitle = subject == null || subject.isBlank() ? brand + " notification" : subject;
        StringBuilder html = new StringBuilder(1024);
        html.append("<!DOCTYPE html><html lang=\"en\"><head>")
                .append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>").append(escapeHtml(safeTitle)).append("</title></head>")
                .append("<body style=\"margin:0;padding:24px;background-color:#f1f5f9;font-family:").append(FONT_STACK).append(";\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;\">")
                .append("<tr><td align=\"center\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"680\" style=\"width:100%;max-width:680px;border-collapse:collapse;background-color:#ffffff;border:1px solid #cbd5e1;\">")
                .append("<tr><td bgcolor=\"#475569\" style=\"background-color:#475569;height:6px;line-height:6px;font-size:6px;\">&nbsp;</td></tr>")
                .append("<tr><td style=\"padding:20px 24px;border-bottom:1px solid #e2e8f0;\">")
                .append("<div style=\"font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:#475569;\">")
                .append(escapeHtml(brand)).append("</div>")
                .append("<div style=\"font-size:18px;font-weight:700;color:#0f172a;margin-top:4px;\">")
                .append(escapeHtml(safeTitle)).append("</div></td></tr>")
                .append("<tr><td style=\"padding:20px 24px;font-size:13px;line-height:1.5;color:#1e293b;font-family:").append(MONO_STACK).append(";white-space:pre-wrap;\">")
                .append(escapeHtml(textBody == null ? "" : textBody)).append("</td></tr>")
                .append("<tr><td style=\"padding:14px 24px;background-color:#f8fafc;border-top:1px solid #e2e8f0;font-size:12px;color:#64748b;\">")
                .append(escapeHtml(footerLine(brand, env)))
                .append("</td></tr></table></td></tr></table></body></html>");
        return html.toString();
    }

    static String footerLine(String brand, String environment) {
        return brand + " · " + environment + " · manage in Settings > Notifications";
    }

    static String bandWord(String type, Band band) {
        if (band == Band.AMBER && ("job_failure".equals(type) || "backup_failure".equals(type))) {
            return "FAILED";
        }
        return band.label;
    }

    static Band resolveBand(TypeTemplateSpec spec, List<List<String>> rows, List<SecurityScanSummary.ScanReport> scans) {
        if ("security_scan".equals(spec.type())) {
            if (scans.isEmpty()) {
                return Band.RED;
            }
            boolean hasHigh = false;
            boolean hasMediumOrReview = false;
            for (SecurityScanSummary.ScanReport scan : scans) {
                if (!scan.passed() || scan.blocking() > 0 || scan.scanErrors() > 0) {
                    hasHigh = true;
                }
                for (var toolEntry : scan.counts().values()) {
                    for (String sev : List.of("CRITICAL", "HIGH")) {
                        SecurityScanSummary.CountCell cell = toolEntry.get(sev);
                        if (cell != null && (cell.newCount() > 0 || cell.existing() > 0)) {
                            hasHigh = true;
                        }
                    }
                    SecurityScanSummary.CountCell med = toolEntry.get("MEDIUM");
                    if (med != null && (med.newCount() > 0 || med.existing() > 0)) {
                        hasMediumOrReview = true;
                    }
                }
                for (SecurityScanSummary.Finding f : scan.findings()) {
                    if ("CRITICAL".equals(f.severity()) || "HIGH".equals(f.severity())) hasHigh = true;
                    if ("MEDIUM".equals(f.severity())) hasMediumOrReview = true;
                }
                if (scan.acceptedReview() != null && scan.acceptedReview().attention()) {
                    hasMediumOrReview = true;
                }
            }
            return hasHigh ? Band.RED : hasMediumOrReview ? Band.AMBER : Band.NEUTRAL;
        }
        if ("compliance_regression".equals(spec.type())) {
            for (List<String> row : rows) {
                if (row.size() >= 5 && ("HIGH".equalsIgnoreCase(row.get(4)) || "CRITICAL".equalsIgnoreCase(row.get(4)))) {
                    return Band.RED;
                }
            }
        }
        if ("login_security".equals(spec.type())) {
            for (List<String> row : rows) {
                if (row.size() >= 2 && isBreakGlassSuccess(row.get(1))) {
                    return Band.RED;
                }
            }
        }
        return spec.defaultBand();
    }

    // ---------------------------------------------------------------- time and wording helpers

    /** "18.11.2026 12:15" in Europe/Istanbul; a value that is not an ISO instant is returned unchanged. */
    static String localTime(String isoInstant) {
        if (isoInstant == null) return "";
        try {
            return LOCAL_TIME.format(Instant.parse(isoInstant.strip()).atZone(LOCAL_ZONE));
        } catch (RuntimeException e) {
            return isoInstant;
        }
    }

    /** Replaces every ISO instant inside free text with local time. */
    static String localizeTimes(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = ISO_INSTANT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(localTime(m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String clean = localizeTimes(text).replaceAll("\\s+", " ").strip();
        return clean.length() > MAX_CELL_CHARS ? clean.substring(0, MAX_CELL_CHARS - 3) + "..." : clean;
    }

    private static String cell(List<String> row, int idx) {
        return idx < row.size() && row.get(idx) != null ? row.get(idx) : "";
    }

    private static String humanize(String id) {
        String s = id == null ? "" : id.replace('_', ' ').strip();
        return s;
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String lowerFirst(String s) {
        if (s == null || s.length() < 2) return s == null ? "" : s;
        // Keep acronyms such as SSH and HTTPS upper case.
        return Character.isLowerCase(s.charAt(1)) ? Character.toLowerCase(s.charAt(0)) + s.substring(1) : s;
    }

    private static String plural(int n, String noun) {
        return n + " " + pluralize(noun, n);
    }

    private static String pluralize(String noun, int n) {
        if (n == 1) return noun;
        int paren = noun.indexOf(" (");
        return paren < 0 ? noun + "s" : noun.substring(0, paren) + "s" + noun.substring(paren);
    }

    private static String breakdown(Map<String, Integer> counts, boolean pluralNouns) {
        List<String> parts = new ArrayList<>();
        int other = 0;
        for (var e : counts.entrySet()) {
            if (parts.size() >= 5) {
                other += e.getValue();
                continue;
            }
            parts.add(pluralNouns ? plural(e.getValue(), e.getKey()) : e.getValue() + " " + e.getKey());
        }
        if (other > 0) parts.add(other + " other");
        return String.join(", ", parts);
    }

    private static final Map<String, String> ACTION_NOUNS = Map.ofEntries(
            Map.entry("local_credential_create", "local account creation"),
            Map.entry("local_password_change", "password change"),
            Map.entry("local_credential_admin_password_reset", "admin password reset"),
            Map.entry("local_credential_enable", "account enablement"),
            Map.entry("local_credential_disable", "account disablement"),
            Map.entry("role_binding_create", "role binding creation"),
            Map.entry("role_binding_revoke", "role binding removal"),
            Map.entry("credential_create", "credential creation"),
            Map.entry("credential_replace_secret", "credential secret replacement"),
            Map.entry("device_credential_set", "device credential change"),
            Map.entry("device_secret_set", "device secret change"),
            Map.entry("device_register", "device registration"),
            Map.entry("device_delete", "device deletion"),
            Map.entry("device_backup_target_set", "backup target change"),
            Map.entry("notification_settings_update", "notification settings change"),
            Map.entry("notification_route_update", "notification route change"),
            Map.entry("local_auth_mode_write", "sign-in mode change"),
            Map.entry("machine_session_token_refused", "refused machine token"),
            Map.entry("machine_session_roles_refused", "refused machine session"),
            Map.entry("machine_session_read_only", "read-only machine session"),
            Map.entry("local_non_break_glass_refused", "refused local sign-in"));

    private static boolean isBreakGlassSuccess(String action) {
        return action != null && action.contains("break_glass") && action.endsWith("_succeeded");
    }

    private static String actionNoun(String action) {
        String noun = ACTION_NOUNS.get(action);
        if (noun != null) return noun;
        // Sign-in events are matched by shape, not by whole-string constants (keeps identity-named literals out of the source).
        if (isBreakGlassSuccess(action)) return "break-glass sign-in";
        if (action != null && action.endsWith("_failure")) return "failed sign-in";
        if (action != null && action.endsWith("_takeover")) return "session takeover";
        return humanize(action);
    }

    /** Plain words for a job type: "cp_cluster_failover" is "failover", "inventory" is "inventory collection". */
    static String jobWord(String jobType) {
        String t = jobType == null ? "" : jobType.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("failover")) return "failover";
        if (t.contains("backup") || t.contains("export")) return "backup";
        if (t.contains("inventory")) return "inventory collection";
        if (t.contains("config")) return "configuration collection";
        if (t.contains("policy")) return "policy collection";
        if (t.contains("readiness")) return "readiness check";
        if (t.contains("diagnostic") || t.contains("syslog") || t.contains("system_log")) return "diagnostics";
        if (t.contains("discovery")) return "discovery";
        if (t.contains("license") || t.contains("lifecycle")) return "lifecycle read";
        if (t.contains("compliance")) return "compliance evaluation";
        return humanize(jobType);
    }

    private static boolean outcomeUnknown(String reason) {
        return reason != null && reason.startsWith("OUTCOME_UNKNOWN");
    }

    private static String jobLabel(String jobType, String reason) {
        String word = jobWord(jobType);
        return "failover".equals(word) && outcomeUnknown(reason) ? "failover (outcome unknown)" : word;
    }

    private static String deviationWord(String deviationClass) {
        return switch (deviationClass == null ? "" : deviationClass) {
            case "size_anomaly" -> "size jump";
            case "fleet_large" -> "larger than the fleet";
            case "firewall_logs" -> "firewall logs inside";
            default -> humanize(deviationClass);
        };
    }

    private static String healthWord(String event) {
        if (event != null && event.startsWith("unreachable")) return "Unreachable";
        return capitalize(event);
    }

    private static Cell jobCell(String ref) {
        if (ref != null && ref.startsWith(JOB_PATH_PREFIX) && ref.length() > JOB_PATH_PREFIX.length()) {
            String id = ref.substring(JOB_PATH_PREFIX.length());
            return Cell.link("Open job", "/?screen=operations&tab=jobs&job=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        }
        return Cell.of("");
    }

    /** Names the missing credential slot for a {@code credential_unresolvable} failure and links to Administration > Credentials. */
    static Cell whyCell(String rawReason) {
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.toLowerCase(java.util.Locale.ROOT).startsWith("credential_unresolvable")) {
            String detail = reason.replaceFirst("(?i)^credential_unresolvable[\\s:.-]*", "")
                    .replaceFirst("(?i)\\s*\\(Administration\\s*>\\s*Credentials\\)\\s*$", "").strip();
            String slot = credentialSlotDescription(detail);
            return Cell.textWithLink(shorten(slot), CREDENTIALS_LABEL, CREDENTIALS_PATH);
        }
        return Cell.of(shorten(reason));
    }

    private static String credentialSlotDescription(String detail) {
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("backup or receiver credential")
                || (lower.contains("backup credential") && lower.contains("receiver credential"))) {
            return "Missing Spark backup credential or backup receiver credential (backup_receiver)";
        }
        if (lower.contains("backup_receiver") || lower.contains("receiver credential")) {
            return "Missing backup receiver credential (backup_receiver)";
        }
        if (lower.contains("export passphrase") || lower.contains("export_passphrase")
                || lower.contains("passphrase credential")) {
            return "Missing export passphrase credential (export_passphrase)";
        }
        if (lower.contains("spark backup credential")) {
            return "Missing Spark backup credential";
        }
        if (lower.contains("backup credential")) {
            return "Missing backup credential";
        }
        if (lower.contains("login credential") || lower.contains("ssh credential")) {
            return "Missing login credential";
        }
        return detail.isEmpty() ? "Missing device credential" : "Missing device credential (" + detail + ")";
    }

    // ---------------------------------------------------------------- summary sentence

    private static String summarize(String type, List<List<String>> rows) {
        int n = rows.size();
        if (n == 0) return "No events.";
        switch (type) {
            case "admin_event", "login_security" -> {
                Map<String, Integer> by = new LinkedHashMap<>();
                for (List<String> r : rows) by.merge(actionNoun(cell(r, 1)), 1, Integer::sum);
                String head = "admin_event".equals(type)
                        ? plural(n, "administrative change") : plural(n, "sign-in security event");
                return head + ": " + breakdown(by, true) + ".";
            }
            case "backup_failure" -> {
                int holds = 0;
                for (List<String> r : rows) if ("credential_failure_alert".equals(cell(r, 2))) holds++;
                int failed = n - holds;
                List<String> parts = new ArrayList<>();
                if (failed > 0) parts.add(failed + " failed");
                if (holds > 0) parts.add(holds + " credential " + (holds == 1 ? "hold" : "holds"));
                return plural(n, "backup problem") + ": " + String.join(", ", parts) + ".";
            }
            case "job_failure" -> {
                Map<String, Integer> by = new LinkedHashMap<>();
                for (List<String> r : rows) by.merge(jobLabel(cell(r, 2), cell(r, 3)), 1, Integer::sum);
                return plural(n, "job") + " failed: " + breakdown(by, true) + ".";
            }
            case "config_change" -> {
                Set<String> devices = new LinkedHashSet<>();
                for (List<String> r : rows) devices.add(cell(r, 1));
                return plural(n, "configuration change") + " on " + plural(devices.size(), "device") + ".";
            }
            case "compliance_regression" -> {
                Set<String> devices = new LinkedHashSet<>();
                for (List<String> r : rows) devices.add(cell(r, 1));
                return plural(n, "compliance control") + " went from pass to fail on "
                        + plural(devices.size(), "device") + ".";
            }
            case "device_health" -> {
                Map<String, Integer> by = new LinkedHashMap<>();
                for (List<String> r : rows) by.merge(lowerFirst(healthWord(cell(r, 2))), 1, Integer::sum);
                return plural(n, "device health change") + ": " + breakdown(by, false) + ".";
            }
            case "device_crash_file" -> {
                int files = 0;
                Set<String> devices = new LinkedHashSet<>();
                for (List<String> r : rows) {
                    devices.add(cell(r, 1));
                    try { files += Integer.parseInt(cell(r, 2).strip()); } catch (NumberFormatException e) { files += 1; }
                }
                return plural(files, "new crash or dump file") + " on " + plural(devices.size(), "device") + ".";
            }
            case "backup_size_anomaly" -> {
                Map<String, Integer> by = new LinkedHashMap<>();
                for (List<String> r : rows) by.merge(deviationWord(cell(r, 2)), 1, Integer::sum);
                return plural(n, "backup") + " with an unusual size: " + breakdown(by, false) + ".";
            }
            default -> {
                return plural(n, "event") + ".";
            }
        }
    }

    // ---------------------------------------------------------------- per-type table projection

    /** Reduces the collector's raw rows to the columns that help an operator act. */
    static Table project(String type, List<List<String>> rows, String baseUrl) {
        List<String> headers;
        int linkCol = -1;
        List<List<Cell>> out = new ArrayList<>();
        switch (type) {
            case "admin_event" -> {
                headers = List.of("Time", "Change", "Target");
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(capitalize(actionNoun(cell(r, 1)))),
                            Cell.of(shorten(cell(r, 2)))));
                }
            }
            case "login_security" -> {
                headers = List.of("Time", "Event", "Detail");
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(capitalize(actionNoun(cell(r, 1)))),
                            Cell.of(shorten(cell(r, 2)))));
                }
            }
            case "backup_failure" -> {
                headers = List.of("Time", "Device", "Why", "Job");
                linkCol = 3;
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(shorten(cell(r, 1))),
                            whyCell(cell(r, 3)), jobCell(cell(r, 4))));
                }
            }
            case "job_failure" -> {
                headers = List.of("Time", "Device", "What", "Why", "Job");
                linkCol = 4;
                for (List<String> r : rows) {
                    out.add(List.of(localTime(cell(r, 0)).isEmpty() ? Cell.of("") : Cell.of(localTime(cell(r, 0))),
                            Cell.of(shorten(cell(r, 1))),
                            Cell.of(capitalize(jobLabel(cell(r, 2), cell(r, 3)))), whyCell(cell(r, 3)),
                            jobCell(cell(r, 4))));
                }
            }
            case "config_change" -> {
                headers = List.of("Time", "Device", "Change", "Details");
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(shorten(cell(r, 1))),
                            Cell.of(capitalize(humanize(cell(r, 2)))), Cell.of(shorten(cell(r, 3)))));
                }
            }
            case "compliance_regression" -> {
                headers = List.of("Time", "Device", "Control", "Severity");
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(shorten(cell(r, 1))),
                            Cell.of(shorten(cell(r, 2))), Cell.of(cell(r, 4))));
                }
            }
            case "device_health" -> {
                headers = List.of("Time", "Device", "Event", "Job");
                linkCol = 3;
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(shorten(cell(r, 1))),
                            Cell.of(healthWord(cell(r, 2))), jobCell(cell(r, 3))));
                }
            }
            case "device_crash_file" -> {
                headers = List.of("Time", "Device", "Process", "Files", "Job");
                linkCol = 4;
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(shorten(cell(r, 1))),
                            Cell.of(shorten(cell(r, 3))), Cell.of(shorten(cell(r, 2))), jobCell(cell(r, 5))));
                }
            }
            case "backup_size_anomaly" -> {
                headers = List.of("Time", "Device", "What", "Details");
                for (List<String> r : rows) {
                    out.add(List.of(Cell.of(localTime(cell(r, 0))), Cell.of(shorten(cell(r, 1))),
                            Cell.of(capitalize(deviationWord(cell(r, 2)))), Cell.of(shorten(cell(r, 3)))));
                }
            }
            default -> throw new IllegalArgumentException("unknown notification type: " + type);
        }
        if (linkCol >= 0) {
            final int col = linkCol;
            boolean anyLink = out.stream().anyMatch(r -> r.get(col).path() != null);
            if (baseUrl == null || !anyLink) {
                headers = withoutIndex(headers, col);
                List<List<Cell>> trimmed = new ArrayList<>(out.size());
                for (List<Cell> r : out) trimmed.add(withoutIndex(r, col));
                out = trimmed;
            }
        }
        return new Table(headers, out);
    }

    private static <T> List<T> withoutIndex(List<T> list, int idx) {
        List<T> copy = new ArrayList<>(list);
        copy.remove(idx);
        return copy;
    }

    // ---------------------------------------------------------------- security scan view

    record SecurityView(String summary, String scanLine, List<String> gridHeaders, List<List<String>> grid,
            String reviewSentence, List<String> findingHeaders, List<List<String>> findings, int findingsMore) {}

    private static int open(SecurityScanSummary.CountCell cell) {
        return cell == null ? 0 : cell.newCount() + cell.existing();
    }

    private static int severityRank(String sev) {
        return switch (sev) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    static SecurityView securityView(List<List<String>> findingRows, List<SecurityScanSummary.ScanReport> scans) {
        SecurityScanSummary.ScanReport scan = scans.stream()
                .max(Comparator.comparing(SecurityScanSummary.ScanReport::generatedAt)).orElse(null);
        List<String> gridHeaders = List.of();
        List<List<String>> grid = new ArrayList<>();
        String scanLine = "";
        String review = "";
        StringBuilder summary = new StringBuilder();
        if (scan != null) {
            scanLine = "Scan run " + localTime(scan.generatedAt().toString());
            int openCritical = 0, newCritical = 0, openHigh = 0, newHigh = 0, openMedium = 0, newMedium = 0;
            List<String> tools = new ArrayList<>();
            for (String tool : SecurityScanSummary.TOOLS) {
                Map<String, SecurityScanSummary.CountCell> by = scan.counts().getOrDefault(tool, Map.of());
                int c = open(by.get("CRITICAL")), h = open(by.get("HIGH")), m = open(by.get("MEDIUM"));
                if (c + h + m > 0) tools.add(tool);
                openCritical += c;
                newCritical += by.get("CRITICAL") == null ? 0 : by.get("CRITICAL").newCount();
                openHigh += h;
                newHigh += by.get("HIGH") == null ? 0 : by.get("HIGH").newCount();
                openMedium += m;
                newMedium += by.get("MEDIUM") == null ? 0 : by.get("MEDIUM").newCount();
            }
            List<String> cols = new ArrayList<>();
            for (String sev : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO", "UNKNOWN")) {
                boolean always = "HIGH".equals(sev) || "MEDIUM".equals(sev);
                boolean any = tools.stream().anyMatch(t -> open(scan.counts().getOrDefault(t, Map.of()).get(sev)) > 0);
                if (always || any) cols.add(sev);
            }
            if (!tools.isEmpty()) {
                List<String> headers = new ArrayList<>();
                headers.add("Tool");
                headers.addAll(cols);
                gridHeaders = headers;
                for (String tool : tools) {
                    List<String> row = new ArrayList<>();
                    row.add(tool);
                    for (String sev : cols) {
                        SecurityScanSummary.CountCell cellValue = scan.counts().getOrDefault(tool, Map.of()).get(sev);
                        int o = open(cellValue);
                        int nw = cellValue == null ? 0 : cellValue.newCount();
                        row.add(nw > 0 ? o + " (+" + nw + " new)" : String.valueOf(o));
                    }
                    grid.add(row);
                }
            }
            List<String> found = new ArrayList<>();
            if (openCritical > 0) found.add(openCritical + " CRITICAL" + (newCritical > 0 ? " (" + newCritical + " new)" : ""));
            if (openHigh > 0) found.add(openHigh + " HIGH" + (newHigh > 0 ? " (" + newHigh + " new)" : ""));
            if (openMedium > 0) found.add(openMedium + " MEDIUM" + (newMedium > 0 ? " (" + newMedium + " new)" : ""));
            summary.append(found.isEmpty() ? "No open HIGH or MEDIUM findings"
                    : "Open findings: " + String.join(", ", found));
            summary.append(" in the scan of ").append(localTime(scan.generatedAt().toString())).append('.');
            if (!scan.failed().isEmpty()) {
                List<String> failedTools = new ArrayList<>();
                for (SecurityScanSummary.Failed f : scan.failed()) {
                    String name = f.file().split("[-.]", 2)[0];
                    if (!failedTools.contains(name)) failedTools.add(name);
                }
                summary.append(" Scan errors: ").append(String.join(", ", failedTools)).append('.');
            } else if (scan.scanErrors() > 0) {
                summary.append(' ').append(plural(scan.scanErrors(), "scan error")).append('.');
            }
            if (scan.blocking() > 0) summary.append(" The scan is blocked by ").append(scan.blocking()).append(" finding")
                    .append(scan.blocking() == 1 ? "" : "s").append('.');
            review = reviewSentence(scan.acceptedReview());
        } else {
            summary.append("A security scan report arrived.");
        }

        List<List<String>> sorted = new ArrayList<>(findingRows);
        sorted.sort(Comparator.comparingInt(r -> severityRank(cell(r, 2))));
        List<List<String>> findings = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_FINDING_ROWS, sorted.size()); i++) {
            List<String> r = sorted.get(i);
            findings.add(List.of(cell(r, 2), shorten(cell(r, 1)), shorten(cell(r, 3)),
                    "new".equals(cell(r, 4)) ? "yes" : "", "yes".equals(cell(r, 5)) ? "yes" : "no"));
        }
        return new SecurityView(summary.toString(), scanLine, gridHeaders, grid, review,
                List.of("Severity", "Finding", "Where", "New?", "Fix available"), findings,
                Math.max(0, sorted.size() - MAX_FINDING_ROWS));
    }

    private static String reviewSentence(SecurityScanSummary.AcceptedReview rev) {
        if (rev == null) return "";
        List<String> sentences = new ArrayList<>();
        List<String> fixes = new ArrayList<>();
        for (SecurityScanSummary.ReviewItem item : rev.items()) {
            if ("fix_available".equals(item.status())) {
                fixes.add(item.id() + (item.pkg().isEmpty() ? "" : " (" + item.pkg() + ")"));
            }
        }
        if (!fixes.isEmpty()) {
            sentences.add(fixes.size() == 1
                    ? "1 accepted finding now has a fix available: " + fixes.get(0) + "."
                    : fixes.size() + " accepted findings now have a fix available: " + String.join(", ", fixes) + ".");
        } else if (rev.fixAvailable() > 0) {
            sentences.add(plural(rev.fixAvailable(), "accepted finding")
                    + (rev.fixAvailable() == 1 ? " now has" : " now have") + " a fix available.");
        }
        int due = rev.dueSoon() + rev.pastReview();
        if (due > 0) {
            sentences.add(plural(due, "accepted finding") + (due == 1 ? " is" : " are") + " due for review.");
        }
        return String.join(" ", sentences);
    }

    // ---------------------------------------------------------------- plain text

    private static String buildText(TypeTemplateSpec spec, String brand, String environment, String baseUrl,
            String word, String summary, Table table, SecurityView security) {
        StringBuilder t = new StringBuilder(1024);
        t.append(brand).append(" – ").append(spec.title()).append(" [").append(word).append("]\n\n");
        t.append(summary).append("\n\n");
        if (security != null) {
            if (!security.grid().isEmpty()) {
                t.append(String.join(" | ", security.gridHeaders())).append('\n');
                for (List<String> r : security.grid()) t.append(String.join(" | ", r)).append('\n');
                t.append('\n');
            }
            if (!security.reviewSentence().isEmpty()) t.append(security.reviewSentence()).append("\n\n");
            if (!security.findings().isEmpty()) {
                t.append(String.join(" | ", security.findingHeaders())).append('\n');
                for (List<String> r : security.findings()) {
                    t.append(String.join(" | ", r.stream().map(v -> v.isEmpty() ? "-" : v).toList())).append('\n');
                }
                if (security.findingsMore() > 0) t.append("and ").append(security.findingsMore()).append(" more\n");
                t.append('\n');
            }
        } else if (table != null && !table.rows().isEmpty()) {
            t.append(String.join(" | ", table.headers())).append('\n');
            int limit = Math.min(MAX_ROWS, table.rows().size());
            for (int i = 0; i < limit; i++) {
                List<String> parts = new ArrayList<>();
                for (Cell c : table.rows().get(i)) {
                    String v;
                    if (c.path() != null && baseUrl != null) {
                        v = c.text().isEmpty()
                                ? baseUrl + c.path()
                                : c.text() + " — " + c.linkText() + " (" + baseUrl + c.path() + ")";
                    } else if (c.linkText() != null && !c.text().isEmpty()) {
                        v = c.text() + " — " + c.linkText();
                    } else {
                        v = c.text();
                    }
                    parts.add(v.isEmpty() ? "-" : v);
                }
                t.append(String.join(" | ", parts)).append('\n');
            }
            if (table.rows().size() > MAX_ROWS) t.append("and ").append(table.rows().size() - MAX_ROWS).append(" more\n");
            t.append('\n');
        }
        t.append("What to do: ").append(spec.whatToDo()).append('\n');
        if (baseUrl != null) {
            t.append("Open ").append(spec.screenLabel()).append(": ").append(baseUrl).append(spec.screenPath()).append('\n');
        }
        t.append('\n').append(footerLine(brand, environment)).append('\n');
        return t.toString();
    }

    // ---------------------------------------------------------------- HTML

    private static String buildHtml(TypeTemplateSpec spec, String brand, String environment, String baseUrl,
            Band band, String word, String summary, Table table, SecurityView security) {
        StringBuilder html = new StringBuilder(4096);
        String titleText = brand + " – " + spec.title();
        html.append("<!DOCTYPE html><html lang=\"en\"><head>")
                .append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>").append(escapeHtml(titleText)).append("</title>")
                .append("</head>")
                .append("<body style=\"margin:0;padding:24px 12px;background-color:#f1f5f9;font-family:").append(FONT_STACK).append(";color:#0f172a;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;background-color:#f1f5f9;\">")
                .append("<tr><td align=\"center\" style=\"padding:0;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"680\" style=\"width:100%;max-width:680px;border-collapse:collapse;background-color:#ffffff;border:1px solid #cbd5e1;\">");

        html.append("<tr><td bgcolor=\"").append(band.barHex).append("\" style=\"background-color:").append(band.barHex)
                .append(";height:6px;line-height:6px;font-size:6px;\">&nbsp;</td></tr>");

        // Header: product name, type title, one severity word.
        html.append("<tr><td style=\"padding:20px 24px 16px 24px;border-bottom:1px solid #e2e8f0;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;\">")
                .append("<tr><td valign=\"top\" style=\"padding:0;\">")
                .append("<div style=\"font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:0.06em;color:#475569;\">")
                .append(escapeHtml(brand)).append("</div>")
                .append("<div style=\"font-size:20px;font-weight:700;color:#0f172a;margin-top:4px;\">")
                .append(escapeHtml(spec.title())).append("</div>")
                .append("</td>")
                .append("<td valign=\"top\" align=\"right\" style=\"padding:0;white-space:nowrap;\">")
                .append("<span style=\"display:inline-block;padding:4px 10px;background-color:").append(band.pillBgHex)
                .append(";color:").append(band.pillTextHex).append(";border:1px solid ").append(band.barHex)
                .append(";font-size:11px;font-weight:700;letter-spacing:0.04em;\">")
                .append(escapeHtml(word)).append("</span>")
                .append("</td></tr></table></td></tr>");

        html.append("<tr><td style=\"padding:16px 24px 8px 24px;\">")
                .append("<div style=\"font-size:14px;line-height:1.5;color:#1e293b;font-weight:600;\">")
                .append(escapeHtml(summary)).append("</div></td></tr>");

        html.append("<tr><td style=\"padding:8px 24px 16px 24px;\">");
        if (security != null) {
            appendSecurityContent(html, security);
        } else if (table != null) {
            appendTable(html, table, baseUrl);
        }
        html.append("</td></tr>");

        html.append("<tr><td style=\"padding:0 24px 20px 24px;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;background-color:#f8fafc;border-left:4px solid ")
                .append(band.barHex).append(";border-top:1px solid #e2e8f0;border-right:1px solid #e2e8f0;border-bottom:1px solid #e2e8f0;\">")
                .append("<tr><td style=\"padding:12px 16px;\">")
                .append("<div style=\"font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:0.04em;color:#334155;margin-bottom:4px;\">What to do</div>")
                .append("<div style=\"font-size:13px;line-height:1.5;color:#1e293b;\">")
                .append(escapeHtml(spec.whatToDo())).append("</div>");
        if (baseUrl != null) {
            html.append("<div style=\"margin-top:10px;\">")
                    .append("<a href=\"").append(escapeHtml(baseUrl + spec.screenPath()))
                    .append("\" style=\"display:inline-block;padding:7px 14px;background-color:#0f172a;color:#ffffff;text-decoration:none;font-size:12px;font-weight:600;\">")
                    .append("Open ").append(escapeHtml(spec.screenLabel())).append("</a>")
                    .append("</div>");
        }
        html.append("</td></tr></table></td></tr>");

        html.append("<tr><td bgcolor=\"#f8fafc\" style=\"padding:14px 24px;background-color:#f8fafc;border-top:1px solid #e2e8f0;font-size:12px;line-height:1.5;color:#64748b;\">")
                .append(escapeHtml(footerLine(brand, environment)))
                .append("</td></tr>");

        html.append("</table></td></tr></table></body></html>");
        return html.toString();
    }

    private static void appendSecurityContent(StringBuilder html, SecurityView sec) {
        if (!sec.grid().isEmpty()) {
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;border:1px solid #cbd5e1;font-size:12px;\">");
            appendHeaderRow(html, sec.gridHeaders());
            html.append("<tbody>");
            for (List<String> row : sec.grid()) {
                html.append("<tr>");
                for (int c = 0; c < row.size(); c++) {
                    String v = row.get(c);
                    boolean urgent = c > 0 && !v.startsWith("0") && ("CRITICAL".equals(sec.gridHeaders().get(c))
                            || "HIGH".equals(sec.gridHeaders().get(c)));
                    html.append("<td valign=\"top\" style=\"padding:8px 10px;border:1px solid #e2e8f0;color:")
                            .append(urgent ? "#991b1b" : "#1e293b").append(";")
                            .append(c == 0 ? "font-weight:600;font-family:" + MONO_STACK + ";" : "")
                            .append("\">").append(escapeHtml(v)).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        if (!sec.reviewSentence().isEmpty()) {
            html.append("<div style=\"margin-top:10px;padding:8px 10px;background-color:#f8fafc;border:1px solid #e2e8f0;font-size:12px;color:#334155;\">")
                    .append(escapeHtml(sec.reviewSentence())).append("</div>");
        }
        if (!sec.findings().isEmpty()) {
            html.append("<div style=\"height:14px;line-height:14px;font-size:14px;\">&nbsp;</div>");
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;border:1px solid #cbd5e1;font-size:12px;\">");
            appendHeaderRow(html, sec.findingHeaders());
            html.append("<tbody>");
            for (int i = 0; i < sec.findings().size(); i++) {
                appendBodyRow(html, i, sec.findings().get(i).stream().map(Cell::of).toList(), null);
            }
            if (sec.findingsMore() > 0) appendMoreRow(html, sec.findingHeaders().size(), sec.findingsMore());
            html.append("</tbody></table>");
        }
    }

    private static void appendTable(StringBuilder html, Table table, String baseUrl) {
        if (table.rows().isEmpty()) return;
        html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"border-collapse:collapse;border:1px solid #cbd5e1;font-size:12px;\">");
        appendHeaderRow(html, table.headers());
        html.append("<tbody>");
        int limit = Math.min(MAX_ROWS, table.rows().size());
        for (int i = 0; i < limit; i++) {
            appendBodyRow(html, i, table.rows().get(i), baseUrl);
        }
        if (table.rows().size() > MAX_ROWS) appendMoreRow(html, table.headers().size(), table.rows().size() - MAX_ROWS);
        html.append("</tbody></table>");
    }

    private static void appendHeaderRow(StringBuilder html, List<String> headers) {
        html.append("<thead><tr bgcolor=\"#f1f5f9\" style=\"background-color:#f1f5f9;\">");
        for (String col : headers) {
            html.append("<th align=\"left\" style=\"padding:8px 10px;border:1px solid #cbd5e1;color:#334155;font-weight:700;font-size:11px;text-transform:uppercase;letter-spacing:0.03em;\">")
                    .append(escapeHtml(col)).append("</th>");
        }
        html.append("</tr></thead>");
    }

    private static void appendBodyRow(StringBuilder html, int index, List<Cell> cells, String baseUrl) {
        String rowBg = (index % 2 == 1) ? "#f8fafc" : "#ffffff";
        html.append("<tr bgcolor=\"").append(rowBg).append("\" style=\"background-color:").append(rowBg).append(";\">");
        for (Cell c : cells) {
            html.append("<td valign=\"top\" style=\"padding:8px 10px;border:1px solid #e2e8f0;color:#1e293b;line-height:1.4;word-break:break-word;\">");
            if (c.path() != null && baseUrl != null) {
                if (!c.text().isEmpty()) {
                    html.append(escapeHtml(c.text())).append(" — ");
                }
                html.append("<a href=\"").append(escapeHtml(baseUrl + c.path()))
                        .append("\" style=\"color:#1d4ed8;text-decoration:underline;\">")
                        .append(escapeHtml(c.linkText())).append("</a>");
            } else if (c.linkText() != null && !c.text().isEmpty()) {
                html.append(escapeHtml(c.text() + " — " + c.linkText()));
            } else {
                html.append(escapeHtml(c.text()));
            }
            html.append("</td>");
        }
        html.append("</tr>");
    }

    private static void appendMoreRow(StringBuilder html, int columns, int remaining) {
        html.append("<tr bgcolor=\"#fffbeb\" style=\"background-color:#fffbeb;\">")
                .append("<td colspan=\"").append(columns)
                .append("\" style=\"padding:8px 10px;border:1px solid #e2e8f0;color:#92400e;font-weight:600;font-style:italic;\">")
                .append("and ").append(remaining).append(" more")
                .append("</td></tr>");
    }

    /** Fallback line parser when a {@link NotificationMailRouter.Batch} is supplied with lines only. */
    static List<List<String>> parseLines(String type, List<String> lines) {
        List<List<String>> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            String[] parts = line.split("  ");
            List<String> row = new ArrayList<>();
            if ("admin_event".equals(type) || "login_security".equals(type)) {
                row.add(part(parts, 0));
                row.add(part(parts, 1));
                row.add(joinFrom(parts, 2));
            } else if (("backup_failure".equals(type) || "job_failure".equals(type)) && parts.length >= 4) {
                String[] typeAndReason = parts[2].split(": ", 2);
                row.add(parts[0]);
                row.add(parts[1]);
                row.add(typeAndReason[0]);
                row.add(typeAndReason.length > 1 ? typeAndReason[1] : "");
                row.add(joinFrom(parts, 3));
            } else if ("config_change".equals(type) && parts.length >= 4) {
                String[] kindAndSummary = parts[2].split(": ", 2);
                row.add(parts[0]);
                row.add(parts[1]);
                row.add(kindAndSummary[0]);
                row.add(kindAndSummary.length > 1 ? kindAndSummary[1] : "");
                row.add(parts[3].replace("(notification ", "").replace(")", ""));
            } else if ("compliance_regression".equals(type) && parts.length >= 4) {
                String controlField = parts[2];
                int idx = controlField.indexOf(" PASS -> FAIL (");
                String control = idx > 0 ? controlField.substring(0, idx) : controlField;
                String sev = idx > 0 && controlField.endsWith(")")
                        ? controlField.substring(idx + " PASS -> FAIL (".length(), controlField.length() - 1)
                        : "";
                row.add(parts[0]);
                row.add(parts[1]);
                row.add(control);
                row.add("PASS -> FAIL");
                row.add(sev);
                row.add(parts[3]);
            } else if ("device_health".equals(type)) {
                if (parts.length == 3) {
                    row.add(parts[0]);
                    row.add("discovery / endpoint");
                    row.add(parts[1]);
                    row.add(parts[2]);
                } else {
                    row.add(part(parts, 0));
                    row.add(part(parts, 1));
                    row.add(part(parts, 2));
                    row.add(joinFrom(parts, 3));
                }
            } else if ("device_crash_file".equals(type) && parts.length >= 4) {
                row.add(parts[0]);
                row.add(parts[1]);
                row.add("");
                row.add(parts[2]);
                row.add("");
                row.add(parts[3]);
            } else if ("backup_size_anomaly".equals(type) && parts.length >= 4) {
                String[] clsAndSummary = parts[2].split(": ", 2);
                row.add(parts[0]);
                row.add(parts[1]);
                row.add(clsAndSummary[0]);
                row.add(clsAndSummary.length > 1 ? clsAndSummary[1] : "");
                row.add(parts[3]);
            } else {
                for (String p : parts) row.add(p);
            }
            out.add(List.copyOf(row));
        }
        return List.copyOf(out);
    }

    private static String part(String[] parts, int idx) {
        return idx < parts.length ? parts[idx] : "";
    }

    private static String joinFrom(String[] parts, int start) {
        if (start >= parts.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start) sb.append("  ");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Synthetic masked sample data per notification type for "Send test" and UI previews. */
    static NotificationMailRouter.Batch sampleBatch(String type) {
        Instant t1 = Instant.parse("2026-11-18T09:15:00Z");
        Instant t2 = Instant.parse("2026-11-18T09:18:00Z");
        return switch (type) {
            case "admin_event" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  device_register  FW-TANGO-04",
                            t2 + "  role_binding_create  role:backup_admin -> group-ref-01"),
                    List.of(
                            List.of(t1.toString(), "device_register", "FW-TANGO-04"),
                            List.of(t2.toString(), "role_binding_create", "role:backup_admin -> group-ref-01")),
                    2L, t2, Map.of());
            case "login_security" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  local_login_failure  actor:9f3a2c1b from 192.0.2.44",
                            t2 + "  session_login_takeover  actor:4d8e1a02 session replaced"),
                    List.of(
                            List.of(t1.toString(), "local_login_failure", "actor:9f3a2c1b from 192.0.2.44"),
                            List.of(t2.toString(), "session_login_takeover", "actor:4d8e1a02 session replaced")),
                    2L, t2, Map.of());
            case "backup_failure" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-TANGO-04  cp_backup: SSH transport timed out after 300s at 192.0.2.14  /operations/jobs/job-bk-101",
                            t2 + "  backup credential sha256:8a4f19c2 failed authentication on 2 device(s) since 2026-11-18T02:05:00Z; scheduled backups for it are held  /backups"),
                    List.of(
                            List.of(t1.toString(), "FW-TANGO-04", "cp_backup",
                                    "SSH transport timed out after 300s at 192.0.2.14", "/operations/jobs/job-bk-101"),
                            List.of(t2.toString(), "backup credential sha256:8a4f19c2", "credential_failure_alert",
                                    "failed authentication on 2 device(s) since 2026-11-18T02:05:00Z; scheduled backups for it are held",
                                    "/backups")),
                    0L, t2, Map.of());
            case "job_failure" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-BRAVO-02  inventory: connection timed out reaching 192.0.2.22  /operations/jobs/job-inv-204",
                            t2 + "  CP failover unit  cp_cluster_failover: OUTCOME_UNKNOWN peer state unconfirmed on CLS-ROMEO-01  /operations/jobs/job-fo-205"),
                    List.of(
                            List.of(t1.toString(), "FW-BRAVO-02", "inventory",
                                    "connection timed out reaching 192.0.2.22", "/operations/jobs/job-inv-204"),
                            List.of(t2.toString(), "CP failover unit", "cp_cluster_failover",
                                    "OUTCOME_UNKNOWN peer state unconfirmed on CLS-ROMEO-01", "/operations/jobs/job-fo-205")),
                    0L, t2, Map.of());
            case "config_change" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-TANGO-04  configuration_override_detected: NTP server primary changed to 192.0.2.10  (notification cfg-301)",
                            t2 + "  FW-JULIET-06  running_config_drift: syslog target updated on management plane  (notification cfg-302)"),
                    List.of(
                            List.of(t1.toString(), "FW-TANGO-04", "configuration_override_detected",
                                    "NTP server primary changed to 192.0.2.10", "cfg-301"),
                            List.of(t2.toString(), "FW-JULIET-06", "running_config_drift",
                                    "syslog target updated on management plane", "cfg-302")),
                    0L, t2, Map.of());
            case "compliance_regression" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-TANGO-04  CP-NTP-01 PASS -> FAIL (HIGH)  /compliance",
                            t2 + "  FW-BRAVO-02  PAN-SYSLOG-02 PASS -> FAIL (MEDIUM)  /compliance"),
                    List.of(
                            List.of(t1.toString(), "FW-TANGO-04", "CP-NTP-01", "PASS -> FAIL", "HIGH", "/compliance"),
                            List.of(t2.toString(), "FW-BRAVO-02", "PAN-SYSLOG-02", "PASS -> FAIL", "MEDIUM", "/compliance")),
                    0L, t2, Map.of());
            case "device_health" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-TANGO-04  unreachable after three transport failures  /operations/jobs/job-hlth-401",
                            t2 + "  FW-BRAVO-02  SSH host key mismatch  /operations/jobs/job-hlth-402"),
                    List.of(
                            List.of(t1.toString(), "FW-TANGO-04", "unreachable after three transport failures",
                                    "/operations/jobs/job-hlth-401"),
                            List.of(t2.toString(), "FW-BRAVO-02", "SSH host key mismatch",
                                    "/operations/jobs/job-hlth-402")),
                    0L, t2, Map.of());
            case "security_scan" -> {
                Map<String, Map<String, SecurityScanSummary.CountCell>> counts = new LinkedHashMap<>();
                for (String tool : SecurityScanSummary.TOOLS) {
                    Map<String, SecurityScanSummary.CountCell> sevMap = new LinkedHashMap<>();
                    for (String sev : SecurityScanSummary.SEVERITIES) {
                        int n = ("semgrep".equals(tool) && "HIGH".equals(sev)) ? 1 : 0;
                        int ex = ("trivy".equals(tool) && "MEDIUM".equals(sev)) ? 2 : 0;
                        int acc = ("trivy".equals(tool) && "HIGH".equals(sev)) ? 1 : 0;
                        sevMap.put(sev, new SecurityScanSummary.CountCell(n, 0, acc, ex));
                    }
                    counts.put(tool, sevMap);
                }
                List<SecurityScanSummary.Finding> findings = List.of(
                        new SecurityScanSummary.Finding("semgrep", "java.lang.security.audit.TLS-protocol-downgrade",
                                "HIGH", "ui2/service/TransportClient.java:84", "new", false),
                        new SecurityScanSummary.Finding("trivy", "CVE-2026-41002",
                                "MEDIUM", "nexus-ui2-service: libexpat", "existing", true));
                List<List<String>> findingRows = List.of(
                        List.of("semgrep", "java.lang.security.audit.TLS-protocol-downgrade", "HIGH",
                                "ui2/service/TransportClient.java:84", "new", "no"),
                        List.of("trivy", "CVE-2026-41002", "MEDIUM", "nexus-ui2-service: libexpat", "existing", "yes"));
                SecurityScanSummary.AcceptedReview review = new SecurityScanSummary.AcceptedReview(
                        true, 1, 0, 0, 0,
                        List.of(new SecurityScanSummary.ReviewItem("CVE-2026-30999", "openssl-libs", "fix_available")));
                SecurityScanSummary.ScanReport report = new SecurityScanSummary.ScanReport(
                        t2, 0, 1, false, counts, findings, review, List.of());
                String line = t2 + "  security scan errors=0 blocking=1; semgrep/HIGH new=1 fixed=0 accepted=0 existing=0; "
                        + "trivy/MEDIUM new=0 fixed=0 accepted=0 existing=2; trivy/HIGH new=0 fixed=0 accepted=1 existing=0; "
                        + "accepted-review attention=true fix_available=1 due_soon=0 past_review=0 not_reported=0; "
                        + "fix_available CVE-2026-30999 openssl-libs";
                yield new NotificationMailRouter.Batch(List.of(line), findingRows, List.of(report), 0L, t2, Map.of());
            }
            case "device_crash_file" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-TANGO-04  2 new crash/dump file(s), process fwk, newest at device time 2026-11-18T09:12:00Z  /operations/jobs/job-diag-501",
                            t2 + "  FW-JULIET-06  1 new crash/dump file(s), process vpnd, newest at device time 2026-11-18T09:16:30Z  /operations/jobs/job-diag-502"),
                    List.of(
                            List.of(t1.toString(), "FW-TANGO-04", "2", "fwk", "2026-11-18T09:12:00Z", "/operations/jobs/job-diag-501"),
                            List.of(t2.toString(), "FW-JULIET-06", "1", "vpnd", "2026-11-18T09:16:30Z", "/operations/jobs/job-diag-502")),
                    0L, t2, Map.of());
            case "backup_size_anomaly" -> new NotificationMailRouter.Batch(
                    List.of(
                            t1 + "  FW-TANGO-04  size_anomaly: Backup size anomaly: grew 26.0x (1.1 GB -> 28.6 GB, median of the last 3 backups)  /backups",
                            t2 + "  FW-BRAVO-02  firewall_logs: Backup contains 270.0 GiB of firewall logs (44.6% of the archive contents)  /backups"),
                    List.of(
                            List.of(t1.toString(), "FW-TANGO-04", "size_anomaly",
                                    "Backup size anomaly: grew 26.0x (1.1 GB -> 28.6 GB, median of the last 3 backups)", "/backups"),
                            List.of(t2.toString(), "FW-BRAVO-02", "firewall_logs",
                                    "Backup contains 270.0 GiB of firewall logs (44.6% of the archive contents)", "/backups")),
                    0L, t2, Map.of());
            default -> throw new IllegalArgumentException("unknown notification type: " + type);
        };
    }

    public static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
