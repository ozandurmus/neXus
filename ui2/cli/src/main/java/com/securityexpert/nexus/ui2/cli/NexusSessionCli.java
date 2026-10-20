package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The session-authenticated half of the CLI (backlog
 * {@code cli_parity_for_every_ui_action}, Product Owner P1 2026-09-22: "the
 * product must not be UI-bound; every button has a CLI counterpart").
 *
 * <p>{@code login} takes the password from {@code NEXUS_CLI_PASSWORD} or the
 * console, never from an argument, and stores the session cookie and CSRF
 * token in {@code $NEXUS_CLI_HOME/session.json} (default
 * {@code ~/.nexus-cli}, mode 0600). Every other command is the same HTTP
 * call the screen makes, through the same route map and role gates -- the
 * CLI has no side door. {@code api} is the general form; the named commands
 * are the screen's buttons spelled out.</p>
 */
final class NexusSessionCli {

    static final String SESSION_COOKIE = "ui2_session";
    private static final Pattern SET_COOKIE = Pattern.compile("(?:^|;\\s*)" + SESSION_COOKIE + "=([^;]+)");

    record Session(String baseUrl, String cookie, String csrf) {
    }

    private final Path home;
    private final HttpClient client;

    NexusSessionCli(Path home) {
        this.home = home;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpClient client() {
        return client;
    }

    static Path defaultHome() {
        String env = System.getenv("NEXUS_CLI_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".nexus-cli");
    }

    static void printUsage() {
        System.out.println("  login <baseUrl> <username> [local|ldap]       (password: NEXUS_CLI_PASSWORD or console)");
        System.out.println("  logout | session");
        System.out.println("  api <GET|POST|PUT> <path> [jsonBody|@file]     the general form: any route the screen calls");
        System.out.println("  devices");
        System.out.println("  system-pods | storage                          service view (pods, CPU, memory) and storage use");
        System.out.println("  notifications | notifications-test <syslog|mail>  remote logging / SMTP relay settings and a test send");
        System.out.println("  backups [deviceId]                             fleet or one device's history (with baseline)");
        System.out.println("  backup-run <deviceId> <reason> [backup|snapshot]");
        System.out.println("  backup-run-all <reason>");
        System.out.println("  backup-download <artefactId> <reason> <destPath>");
        System.out.println("  backup-contents <artefactId> | backup-relist <artefactId>");
        System.out.println("  backup-compare <olderArtefactId> <newerArtefactId>");
        System.out.println("  backup-baseline <deviceId> <artefactId|clear>");
        System.out.println("  backup-target <deviceId> on|off");
        System.out.println("  policy | policy-set <on|off> <cron> <retentionDays> <snapshotDepth>");
        System.out.println("  jobs [state=.. job_type=.. device_id=.. since=.. until=.. q=.. page=.. page_size=..]");
        System.out.println("  jobs-export <dest.csv> [same filters]");
        System.out.println("  inventory-collect <deviceId> | configuration-collect <deviceId>");
        System.out.println("  syslog-measure-start                           measure system/config logs on every enrolled Palo Alto firewall");
        System.out.println("  syslog-collect-start [deviceId]                start the incremental system-log collection (one device, or every supported device)");
        System.out.println("  syslog-spike-review <deviceId>                 one-off Spike Detective layout review of one Check Point gateway (summary in syslog-measurements)");
        System.out.println("  syslog-sources <deviceId>                      per source: cursor, last outcome and run, FAST_ROTATING, horizon, BACKLOG/GAP/UNKNOWN");
        System.out.println("  syslog-chunks <deviceId> [source] [yyyy-MM-dd]  stored chunks: sizes, lines, line times, redactions, outcome");
        System.out.println("  syslog-view <chunkId> [offset=N] [limit=N] [unmasked] [all]   one page (or all pages) of a chunk, masked by default;");
        System.out.println("                                                 unmasked needs system_log_read_unmasked and is on the audit ledger per page");
        System.out.println("  syslog-gaps [deviceId] | syslog-crash-files [deviceId] | syslog-hcp <deviceId>");
        System.out.println("  syslog-spike-data <deviceId>                   Spike Detective directories: time, member classes, sizes");
        System.out.println("  syslog-settings                                collection settings: retention, caps, the management-server and spike-data switches");
        System.out.println("  syslog-settings-set <key>=<value> ...          security_admin; e.g. spike_data_enabled=true raw_retention_days=45 (audited)");
        System.out.println("  syslog-measurements [deviceId] [vendor=..] [source=..] [limit=..]   recorded measurement summaries");
        System.out.println("                                                 (Palo Alto and the numeric Check Point summaries)");
        System.out.println("  pa-cases | pa-case <caseId>                    Packet Analysis cases (expiry: 7 days)");
        System.out.println("  pa-case-create <title> [--payload-zeroing]");
        System.out.println("  pa-upload <caseId> <capture.pcap|.pcapng>      streamed; 2 GB per file");
        System.out.println("  pa-telemetry-upload <caseId> <file.txt> [zone=<id>] [offset=<sec>] [start=<local time>]");
        System.out.println("                                                 host telemetry (sar, vmstat, pidstat, qdisc/softnet, syslog); 200 MB per file");
        System.out.println("  pa-telemetry-align <telemetryId> [zone=<id>] [offset=<sec>] [start=<yyyy-MM-dd HH:mm:ss>]   (no zone = UTC assumed)");
        System.out.println("  pa-telemetry-delete <telemetryId>");
        System.out.println("  pa-analyze <caseId> | pa-run <jobId> | pa-report <jobId> [dest.json]");
        System.out.println("  pa-state <caseId> | pa-flows <caseId> [k=v...] | pa-frames <caseId> [k=v...]");
        System.out.println("  pa-diagnosis <caseId> [k=v...] | pa-charts <caseId> [k=v...] | pa-ladder <caseId> [k=v...]");
        System.out.println("  pa-waterfall <caseId> [k=v...] | pa-timeseries <caseId> [k=v...]");
        System.out.println("  pa-topology-overlay <caseId> [k=v...] | pa-topology-inspect <caseId> [k=v...]");
        System.out.println("  pa-topology-save <caseId> <jsonBody|@file>");
        System.out.println("  pa-findings <caseId> [k=v...] | pa-events <caseId> [k=v...] | pa-dns <caseId> [k=v...]");
        System.out.println("  pa-correlation <caseId>                        neXus inventory & policy correlation");
        System.out.println("  pa-field-diff <caseId> [k=v...]                security_admin; ledgered");
        System.out.println("  pa-export-findings <caseId> [dest.json]");
        System.out.println("  pa-export <caseId> <html|json|csv|xlsx> <dest>  security_admin; ledgered (raw addresses)");
        System.out.println("  pa-expert-report <caseId> <dest.xlsx> [start=<epoch>] [end=<epoch>] [top=<n>]");
        System.out.println("                                                 expert workbook; masked unless security_admin; ledgered");
        System.out.println("  pa-session-evidence <caseId> point=<id> start=<epoch> end=<epoch>");
        System.out.println("  pa-reference-template <caseId> [dest.json]");
        System.out.println("  pa-compare <caseId> [--reference <file.json>] [--against <analysis-export.json>]");
        System.out.println("  pa-download <captureId> <destPath>             security_admin; ledgered");
        System.out.println("  pa-delete <caseId>");
        SanitizerCli.printUsage();
    }

    /** @return exit code, or -1 when {@code args[0]} is not one of this class's commands */
    int run(String[] args) {
        try {
            return switch (args[0]) {
                case "login" -> login(args);
                case "logout" -> logout();
                case "session" -> print(call("GET", "/session/status", null));
                case "api" -> api(args);
                case "devices" -> print(call("GET", "/devices", null));
                case "system-pods" -> print(call("GET", "/api/v2/system/pods", null));
                case "storage" -> print(call("GET", "/api/v2/system/storage", null));
                case "notifications" -> print(call("GET", "/api/v2/config/notifications", null));
                case "notifications-test" -> {
                    require(args, 2, "notifications-test <syslog|mail>");
                    yield print(call("POST", "/api/v2/config/notifications/test-" + ("mail".equals(args[1]) ? "mail" : "syslog"), "{}"));
                }
                case "backups" -> print(call("GET", args.length > 1 ? "/devices/" + args[1] + "/backups" : "/backups", null));
                case "backup-run" -> {
                    require(args, 3, "backup-run <deviceId> <reason> [backup|snapshot]");
                    yield print(call("POST", "/devices/" + args[1] + "/backup/collect",
                            json(Map.of("reason", args[2], "type", args.length > 3 ? args[3] : "backup"))));
                }
                case "backup-run-all" -> {
                    require(args, 2, "backup-run-all <reason>");
                    yield print(call("POST", "/backups/collect-all", json(Map.of("reason", args[1]))));
                }
                case "backup-download" -> backupDownload(args);
                case "backup-contents" -> {
                    require(args, 2, "backup-contents <artefactId>");
                    yield print(call("GET", "/backups/" + args[1] + "/entries", null));
                }
                case "backup-relist" -> {
                    require(args, 2, "backup-relist <artefactId>");
                    yield print(call("POST", "/backups/" + args[1] + "/relist", "{}"));
                }
                case "backup-compare" -> {
                    require(args, 3, "backup-compare <olderArtefactId> <newerArtefactId>");
                    yield print(call("GET", "/backups/" + args[1] + "/compare?with=" + args[2], null));
                }
                case "backup-baseline" -> {
                    require(args, 3, "backup-baseline <deviceId> <artefactId|clear>");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("artefact_id", "clear".equals(args[2]) ? null : args[2]);
                    yield print(call("PUT", "/devices/" + args[1] + "/backup-baseline", json(body)));
                }
                case "backup-target" -> {
                    require(args, 3, "backup-target <deviceId> on|off");
                    yield print(call("PUT", "/devices/" + args[1] + "/backup-target", json(Map.of("enabled", "on".equals(args[2])))));
                }
                case "policy" -> print(call("GET", "/api/v2/backups/policies", null));
                case "policy-set" -> {
                    require(args, 5, "policy-set <on|off> <cron> <retentionDays> <snapshotDepth>");
                    yield print(call("PUT", "/api/v2/backups/policies", json(Map.of(
                            "schedule_enabled", "on".equals(args[1]), "daily_backup_cron", args[2],
                            "backup_retention_days", Integer.parseInt(args[3]), "snapshot_retention_depth", Integer.parseInt(args[4])))));
                }
                case "jobs" -> print(call("GET", "/api/v2/jobs" + queryString(args, 1), null));
                case "jobs-export" -> {
                    require(args, 2, "jobs-export <dest.csv> [filters]");
                    yield saveTo(call("GET", "/api/v2/jobs/export.csv" + queryString(args, 2), null), Path.of(args[1]));
                }
                case "inventory-collect" -> {
                    require(args, 2, "inventory-collect <deviceId>");
                    yield print(call("POST", "/devices/" + args[1] + "/inventory/collect", "{}"));
                }
                case "configuration-collect" -> {
                    require(args, 2, "configuration-collect <deviceId>");
                    yield print(call("POST", "/devices/" + args[1] + "/configuration/collect", "{}"));
                }
                case "syslog-measure-start" -> print(call("POST", "/api/v2/diagnostics/system-log-measurements/start", "{}"));
                case "syslog-collect-start" -> print(call("POST", "/api/v2/system-logs/collect",
                        args.length > 1 ? json(Map.of("device_id", args[1])) : "{}"));
                case "syslog-spike-review" -> {
                    require(args, 2, "syslog-spike-review <deviceId>");
                    yield print(call("POST", "/api/v2/diagnostics/system-log-measurements/spike-layout-review",
                            json(Map.of("device_id", args[1]))));
                }
                case "syslog-measurements" -> print(call("GET", "/api/v2/diagnostics/system-log-measurements"
                        + queryString(measurementFilters(args), 0), null));
                case "syslog-sources" -> {
                    require(args, 2, "syslog-sources <deviceId>");
                    yield print(call("GET", SYSLOG + "/" + opaque(args[1]) + "/sources", null));
                }
                case "syslog-chunks" -> {
                    require(args, 2, "syslog-chunks <deviceId> [source] [yyyy-MM-dd]");
                    yield print(call("GET", SYSLOG + "/" + opaque(args[1]) + "/chunks" + chunkFilters(args), null));
                }
                case "syslog-view" -> syslogView(args);
                case "syslog-gaps" -> print(call("GET", SYSLOG + "/gaps" + optionalDevice(args), null));
                case "syslog-crash-files" -> print(call("GET", SYSLOG + "/crash-files" + optionalDevice(args), null));
                case "syslog-hcp" -> {
                    require(args, 2, "syslog-hcp <deviceId>");
                    yield print(call("GET", SYSLOG + "/" + opaque(args[1]) + "/hcp", null));
                }
                case "syslog-spike-data" -> {
                    require(args, 2, "syslog-spike-data <deviceId>");
                    yield print(call("GET", SYSLOG + "/" + opaque(args[1]) + "/spike-data", null));
                }
                case "syslog-settings" -> print(call("GET", SYSLOG_SETTINGS, null));
                case "syslog-settings-set" -> {
                    require(args, 2, "syslog-settings-set <key>=<value> [<key>=<value> ...]");
                    yield print(call("PUT", SYSLOG_SETTINGS, json(settingsBody(args))));
                }
                case "pa-cases" -> print(call("GET", PA + "/cases", null));
                case "pa-case" -> {
                    require(args, 2, "pa-case <caseId>");
                    yield print(call("GET", PA + "/cases/" + id(args[1]), null));
                }
                case "pa-case-create" -> {
                    require(args, 2, "pa-case-create <title> [--payload-zeroing]");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("title", args[1]);
                    body.put("payload_zeroing", args.length > 2 && "--payload-zeroing".equals(args[2]));
                    yield print(call("POST", PA + "/cases", json(body)));
                }
                case "pa-upload" -> packetUpload(args);
                case "pa-telemetry-upload" -> packetTelemetryUpload(args);
                case "pa-telemetry-align" -> {
                    String usage = "pa-telemetry-align <telemetryId> [zone=<id>] [offset=<sec>] [start=<yyyy-MM-dd HH:mm:ss>]";
                    require(args, 2, usage);
                    yield print(call("PUT", PA + "/telemetry/" + id(args[1]), alignmentBody(args, 2, usage)));
                }
                case "pa-telemetry-delete" -> {
                    require(args, 2, "pa-telemetry-delete <telemetryId>");
                    yield print(call("DELETE", PA + "/telemetry/" + id(args[1]), null));
                }
                case "pa-analyze" -> {
                    require(args, 2, "pa-analyze <caseId>");
                    yield print(call("POST", PA + "/cases/" + id(args[1]) + "/analyze", "{}"));
                }
                case "pa-run" -> {
                    require(args, 2, "pa-run <jobId>");
                    yield print(call("GET", PA + "/runs/" + id(args[1]), null));
                }
                case "pa-report" -> {
                    require(args, 2, "pa-report <jobId> [dest.json]");
                    var response = call("GET", PA + "/runs/" + id(args[1]) + "/report", null);
                    yield args.length > 2 ? saveTo(response, Path.of(args[2])) : print(response);
                }
                case "pa-state" -> packetCaseRead(args, "/state");
                case "pa-flows" -> packetCaseRead(args, "/flows");
                case "pa-frames" -> packetCaseRead(args, "/frames");
                case "pa-diagnosis" -> packetCaseRead(args, "/diagnosis");
                case "pa-charts" -> packetCaseRead(args, "/charts");
                case "pa-ladder" -> packetCaseRead(args, "/ladder");
                case "pa-waterfall" -> packetCaseRead(args, "/waterfall");
                case "pa-timeseries" -> packetCaseRead(args, "/timeseries");
                case "pa-topology-overlay" -> packetCaseRead(args, "/topology/overlay");
                case "pa-topology-inspect" -> packetCaseRead(args, "/topology/inspect");
                case "pa-findings" -> packetCaseRead(args, "/findings");
                case "pa-events" -> packetCaseRead(args, "/events");
                case "pa-dns" -> packetCaseRead(args, "/dns");
                case "pa-correlation" -> packetCaseRead(args, "/correlation");
                case "pa-field-diff" -> packetCaseRead(args, "/field-diff");
                case "pa-export-findings" -> {
                    require(args, 2, "pa-export-findings <caseId> [dest.json]");
                    var response = call("GET", PA + "/cases/" + id(args[1]) + "/export/findings", null);
                    yield args.length > 2 ? saveTo(response, Path.of(args[2])) : print(response);
                }
                case "pa-export" -> {
                    require(args, 4, "pa-export <caseId> <html|json|csv|xlsx> <dest>");
                    if (!List.of("html", "json", "csv", "xlsx").contains(args[2])) {
                        throw new UsageException("format must be html, json, csv or xlsx, got: " + args[2]);
                    }
                    yield saveTo(call("GET", PA + "/cases/" + id(args[1]) + "/export/download?format=" + args[2], null),
                            Path.of(args[3]));
                }
                case "pa-expert-report" -> packetExpertReport(args);
                case "pa-session-evidence" -> packetCaseRead(args, "/sessions/evidence");
                case "pa-reference-template" -> {
                    require(args, 2, "pa-reference-template <caseId> [dest.json]");
                    var response = call("GET", PA + "/cases/" + id(args[1]) + "/reference/template", null);
                    yield args.length > 2 ? saveTo(response, Path.of(args[2])) : print(response);
                }
                case "pa-compare" -> packetCompare(args);
                case "pa-topology-save" -> {
                    require(args, 3, "pa-topology-save <caseId> <jsonBody|@file>");
                    String body = args[2].startsWith("@") ? Files.readString(Path.of(args[2].substring(1))) : args[2];
                    yield print(call("PUT", PA + "/cases/" + id(args[1]) + "/topology", body));
                }
                case "pa-download" -> packetDownload(args);
                case "pa-delete" -> {
                    require(args, 2, "pa-delete <caseId>");
                    yield print(call("DELETE", PA + "/cases/" + id(args[1]), null));
                }
                default -> new SanitizerCli(this).run(args);
            };
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage().startsWith("usage: ") ? e.getMessage() : "usage: " + e.getMessage());
            return 2;
        } catch (UsageException e) {
            System.err.println("usage: " + e.getMessage());
            return 2;
        } catch (IOException | InterruptedException e) {
            System.err.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) {
            throw new UsageException(usage);
        }
    }

    // ---- session ----------------------------------------------------------------------------------

    private int login(String[] args) throws IOException, InterruptedException {
        require(args, 3, "login <baseUrl> <username> [local|ldap]");
        String baseUrl = args[1].replaceAll("/+$", "");
        String mechanism = args.length > 3 ? args[3] : "local";
        char[] password = readPassword();
        if (password == null || password.length == 0) {
            System.err.println("LOGIN_REFUSED: no password (set NEXUS_CLI_PASSWORD or run on a console)");
            return 2;
        }
        String body;
        try {
            body = json(Map.of("username", args[2], "password", new String(password), "mechanism_id", mechanism));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            System.err.println("LOGIN_REFUSED: HTTP " + response.statusCode() + " " + snippet(response.body()));
            return 1;
        }
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .map(SET_COOKIE::matcher).filter(Matcher::find).map(m -> m.group(1)).findFirst().orElse(null);
        if (cookie == null) {
            System.err.println("LOGIN_REFUSED: no " + SESSION_COOKIE + " cookie in the response");
            return 1;
        }
        HttpResponse<byte[]> status = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/session/status"))
                .header("Cookie", SESSION_COOKIE + "=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        String csrf = jsonString(new String(status.body(), StandardCharsets.UTF_8), "csrf_token").orElse("");
        saveSession(new Session(baseUrl, cookie, csrf));
        System.out.println("logged in to " + baseUrl + " as " + args[2] + "; session stored under " + home);
        return 0;
    }

    private int logout() throws IOException, InterruptedException {
        Optional<Session> session = loadSession();
        if (session.isPresent()) {
            call("POST", "/session/logout", "{}");
        }
        Files.deleteIfExists(sessionFile());
        System.out.println("logged out");
        return 0;
    }

    private static char[] readPassword() {
        String env = System.getenv("NEXUS_CLI_PASSWORD");
        if (env != null && !env.isEmpty()) {
            return env.toCharArray();
        }
        java.io.Console console = System.console();
        return console == null ? null : console.readPassword("password: ");
    }

    Path sessionFile() {
        return home.resolve("session.json");
    }

    void saveSession(Session session) throws IOException {
        Files.createDirectories(home);
        Path file = sessionFile();
        Files.writeString(file, json(Map.of("base_url", session.baseUrl(), "cookie", session.cookie(), "csrf", session.csrf())));
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX filesystem; the directory is the user's own
        }
    }

    Optional<Session> loadSession() throws IOException {
        Path file = sessionFile();
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String text = Files.readString(file);
        return Optional.of(new Session(jsonString(text, "base_url").orElse(""), jsonString(text, "cookie").orElse(""),
                jsonString(text, "csrf").orElse("")));
    }

    // ---- http -------------------------------------------------------------------------------------

    private int api(String[] args) throws IOException, InterruptedException {
        require(args, 3, "api <GET|POST|PUT> <path> [jsonBody|@file]");
        String body = null;
        if (args.length > 3) {
            body = args[3].startsWith("@") ? Files.readString(Path.of(args[3].substring(1))) : args[3];
        }
        return print(call(args[1].toUpperCase(java.util.Locale.ROOT), args[2], body));
    }

    HttpResponse<byte[]> call(String method, String path, String jsonBody) throws IOException, InterruptedException {
        Session session = loadSession().orElseThrow(() -> new UsageException("not logged in: run 'login <baseUrl> <username>' first"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(session.baseUrl() + path))
                .header("Cookie", SESSION_COOKIE + "=" + session.cookie())
                .header("Accept", "*/*");
        if (!"GET".equals(method)) {
            request.header("X-CSRF-Token", session.csrf()).header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private int backupDownload(String[] args) throws IOException, InterruptedException {
        require(args, 4, "backup-download <artefactId> <reason> <destPath>");
        Session session = loadSession().orElseThrow(() -> new UsageException("not logged in"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + "/backups/" + args[1] + "/download"))
                .header("Cookie", SESSION_COOKIE + "=" + session.cookie())
                .header("X-CSRF-Token", session.csrf()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("reason", args[2])))).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream in = response.body()) {
                System.err.println("REFUSED: HTTP " + response.statusCode() + " " + snippet(in.readNBytes(2000)));
            }
            return 1;
        }
        Path dest = Path.of(args[3]);
        try (InputStream in = response.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("downloaded " + Files.size(dest) + " bytes to " + dest);
        System.out.println("WARNING: the archive may carry secret-bearing files; the copy is yours to protect from here on.");
        return 0;
    }

    // ---- packet analysis (PO decision record 2026-10-15; same routes and gates as the screen) ---------------

    static final String PA = "/api/v2/packet-analysis";
    private static final Pattern OPAQUE_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    static String id(String value) {
        if (!OPAQUE_ID.matcher(value).matches()) throw new UsageException("identifiers are opaque UUIDs, got: " + value);
        return value;
    }

    private int packetCaseRead(String[] args, String subPath) throws IOException, InterruptedException {
        require(args, 2, args[0] + " <caseId> [key=value...]");
        return print(call("GET", PA + "/cases/" + id(args[1]) + subPath + queryString(args, 2), null));
    }

    /**
     * The expert report workbook of a case. The optional {@code start}/{@code end} (epoch seconds) select the event
     * window explicitly, {@code top} the number of longest transactions; the service decides raw or masked values.
     */
    private int packetExpertReport(String[] args) throws IOException, InterruptedException {
        String usage = "pa-expert-report <caseId> <dest.xlsx> [start=<epoch>] [end=<epoch>] [top=<n>]";
        require(args, 3, usage);
        for (int i = 3; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            String key = eq > 0 ? args[i].substring(0, eq) : args[i];
            String value = eq > 0 ? args[i].substring(eq + 1) : "";
            if (!List.of("start", "end", "top").contains(key) || value.isEmpty() || !value.matches("[0-9]+(\\.[0-9]+)?")) {
                throw new UsageException(usage);
            }
        }
        return saveTo(call("GET", PA + "/cases/" + id(args[1]) + "/export/expert-report" + queryString(args, 3), null),
                Path.of(args[2]));
    }

    /** The upload body, streamed from disk: one multipart part named {@code file}, never read into memory. */
    static HttpRequest.BodyPublisher multipartFile(Path file, String boundary) throws IOException {
        String name = file.getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n", StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
    }

    /** Compares the saved report with a reference template and/or a previous analysis export (both JSON files). */
    private int packetCompare(String[] args) throws IOException, InterruptedException {
        String usage = "pa-compare <caseId> [--reference <file.json>] [--against <analysis-export.json>]";
        require(args, 4, usage);
        StringBuilder body = new StringBuilder("{");
        boolean reference = false;
        boolean against = false;
        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new UsageException(usage);
            boolean isReference = "--reference".equals(args[i]);
            if (!isReference && !"--against".equals(args[i])) throw new UsageException(usage);
            if (isReference ? reference : against) throw new UsageException(usage);
            Path file = Path.of(args[i + 1]);
            if (!Files.isRegularFile(file)) throw new UsageException("no such file: " + file);
            if (reference || against) body.append(',');
            body.append(isReference ? "\"reference\":" : "\"against\":").append(Files.readString(file, StandardCharsets.UTF_8));
            if (isReference) reference = true; else against = true;
        }
        return print(call("POST", PA + "/cases/" + id(args[1]) + "/compare", body.append('}').toString()));
    }

    private int packetUpload(String[] args) throws IOException, InterruptedException {
        require(args, 3, "pa-upload <caseId> <capture.pcap|.pcapng>");
        Session session = loadSession().orElseThrow(() -> new UsageException("not logged in"));
        Path file = Path.of(args[2]);
        if (!Files.isRegularFile(file)) throw new UsageException("no such file: " + file);
        String boundary = "nexus-cli-" + java.util.UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + PA + "/cases/" + id(args[1]) + "/captures"))
                .header("Cookie", SESSION_COOKIE + "=" + session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartFile(file, boundary)).build();
        System.err.println("uploading " + Files.size(file) + " bytes");
        return print(client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    /**
     * Uploads a host telemetry text file (streamed from disk). The upload itself carries no alignment; when {@code zone},
     * {@code offset} or {@code start} are given they are set right after, so the file is never left half-described.
     */
    private int packetTelemetryUpload(String[] args) throws IOException, InterruptedException {
        String usage = "pa-telemetry-upload <caseId> <file.txt> [zone=<id>] [offset=<sec>] [start=<yyyy-MM-dd HH:mm:ss>]";
        require(args, 3, usage);
        String body = args.length > 3 ? alignmentBody(args, 3, usage) : null;
        Session session = loadSession().orElseThrow(() -> new UsageException("not logged in"));
        Path file = Path.of(args[2]);
        if (!Files.isRegularFile(file)) throw new UsageException("no such file: " + file);
        String boundary = "nexus-cli-" + java.util.UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + PA + "/cases/" + id(args[1]) + "/telemetry"))
                .header("Cookie", SESSION_COOKIE + "=" + session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartFile(file, boundary)).build();
        System.err.println("uploading " + Files.size(file) + " bytes");
        HttpResponse<byte[]> uploaded = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = print(uploaded);
        if (status != 0 || body == null) return status;
        String telemetryId = jsonString(new String(uploaded.body(), StandardCharsets.UTF_8), "telemetry_id")
                .orElseThrow(() -> new IOException("the service did not return a telemetry id"));
        return print(call("PUT", PA + "/telemetry/" + id(telemetryId), body));
    }

    /** {@code zone=}, {@code offset=} and {@code start=} as the alignment JSON; anything else is a usage error. */
    private static String alignmentBody(String[] args, int from, String usage) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            String key = eq > 0 ? args[i].substring(0, eq) : args[i];
            String value = eq > 0 ? args[i].substring(eq + 1) : "";
            if (value.isEmpty() || body.size() >= 3) throw new UsageException(usage);
            switch (key) {
                case "zone" -> body.put("time_zone", value);
                case "offset" -> {
                    if (!value.matches("[+-]?[0-9]{1,6}")) throw new UsageException(usage);
                    body.put("offset_seconds", Integer.parseInt(value.startsWith("+") ? value.substring(1) : value));
                }
                case "start" -> body.put("anchor_local", value);
                default -> throw new UsageException(usage);
            }
        }
        return json(body);
    }

    private int packetDownload(String[] args) throws IOException, InterruptedException {
        require(args, 3, "pa-download <captureId> <destPath>");
        Session session = loadSession().orElseThrow(() -> new UsageException("not logged in"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + PA + "/captures/" + id(args[1]) + "/download"))
                .header("Cookie", SESSION_COOKIE + "=" + session.cookie()).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream in = response.body()) {
                System.err.println("REFUSED: HTTP " + response.statusCode() + " " + snippet(in.readNBytes(2000)));
            }
            return response.statusCode() == 401 || response.statusCode() == 403 ? 3 : 1;
        }
        Path dest = Path.of(args[2]);
        try (InputStream in = response.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("downloaded " + Files.size(dest) + " bytes to " + dest);
        System.out.println("WARNING: a capture is raw evidence (7-day retention in neXus); the copy is yours to protect.");
        return 0;
    }

    private static int print(HttpResponse<byte[]> response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() / 100 == 2) {
            System.out.println(body);
            return 0;
        }
        System.err.println("HTTP " + response.statusCode() + ": " + body);
        return response.statusCode() == 401 || response.statusCode() == 403 ? 3 : 1;
    }

    private static int saveTo(HttpResponse<byte[]> response, Path dest) throws IOException {
        if (response.statusCode() / 100 != 2) {
            return print(response);
        }
        Files.write(dest, response.body());
        System.out.println("wrote " + response.body().length + " bytes to " + dest);
        return 0;
    }

    // ---- system logs, phase B read side (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14) ---------------------------------

    static final String SYSLOG = "/api/v2/system-logs";
    /** The settings row (section 14, {@code syslog-settings} / {@code syslog-settings-set}; security_admin). */
    static final String SYSLOG_SETTINGS = "/api/v2/config/system-logs";
    private static final Pattern OPAQUE_TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    /** {@code syslog-view ... all} stops after this many pages (256 KiB each at most): a runaway guard, not a limit of the route. */
    private static final int SYSLOG_ALL_PAGE_CAP = 400;

    static String opaque(String value) {
        if (!OPAQUE_TOKEN.matcher(value).matches()) throw new UsageException("identifiers are opaque tokens (letters, digits, _ . : -), got: " + value);
        return value;
    }

    /** {@code ?deviceId=..} for the lists that take an optional device. */
    static String optionalDevice(String[] args) {
        return args.length > 1 ? "?deviceId=" + java.net.URLEncoder.encode(opaque(args[1]), StandardCharsets.UTF_8) : "";
    }

    /** {@code syslog-chunks <deviceId> [source] [day]}: a word shaped yyyy-MM-dd is the day, any other word the source. */
    static String chunkFilters(String[] args) {
        String source = null, day = null;
        for (int i = 2; i < args.length; i++) {
            if (DAY.matcher(args[i]).matches() && day == null) day = args[i];
            else if (source == null && OPAQUE_TOKEN.matcher(args[i]).matches() && !DAY.matcher(args[i]).matches()) source = args[i];
            else throw new UsageException("syslog-chunks <deviceId> [source] [yyyy-MM-dd], got: " + args[i]);
        }
        List<String> pairs = new ArrayList<>();
        if (source != null) pairs.add("source=" + source);
        if (day != null) pairs.add("day=" + day);
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }

    /**
     * {@code syslog-view <chunkId> [offset=N] [limit=N] [unmasked] [all]}: the page text goes to standard output (so it can
     * be piped), the paging facts to standard error. Masked unless {@code unmasked} is given; the route refuses an
     * unmasked request from a session without the right, and writes a ledger row for every unmasked page.
     */
    private int syslogView(String[] args) throws IOException, InterruptedException {
        String usage = "syslog-view <chunkId> [offset=N] [limit=N] [unmasked] [all]";
        require(args, 2, usage);
        String chunk = opaque(args[1]);
        long offset = 0;
        String limit = null;
        boolean unmasked = false, all = false;
        for (int i = 2; i < args.length; i++) {
            if ("unmasked".equals(args[i])) unmasked = true;
            else if ("all".equals(args[i])) all = true;
            else if (args[i].startsWith("offset=") && args[i].substring(7).matches("[0-9]{1,18}")) offset = Long.parseLong(args[i].substring(7));
            else if (args[i].startsWith("limit=") && args[i].substring(6).matches("[0-9]{1,9}")) limit = args[i].substring(6);
            else throw new UsageException(usage + ", got: " + args[i]);
        }
        if (unmasked) System.err.println("NOTICE: unmasked view; every page is recorded on the audit ledger.");
        for (int page = 1; page <= (all ? SYSLOG_ALL_PAGE_CAP : 1); page++) {
            String query = "?offset=" + offset + (limit == null ? "" : "&limit=" + limit) + (unmasked ? "&unmasked=true" : "");
            HttpResponse<byte[]> response = call("GET", SYSLOG + "/chunks/" + chunk + "/text" + query, null);
            if (response.statusCode() / 100 != 2) return print(response);
            System.out.write(response.body(), 0, response.body().length);
            System.out.flush();
            String next = response.headers().firstValue("X-Syslog-Next-Offset").orElse("");
            boolean eof = "true".equals(response.headers().firstValue("X-Syslog-Eof").orElse("true"));
            System.err.println("-- masked=" + response.headers().firstValue("X-Syslog-Masked").orElse("?")
                    + response.headers().firstValue("X-Syslog-Mask-Rules").map(r -> " rules=" + r).orElse("")
                    + " offset=" + offset + " next_offset=" + next + " lines=" + response.headers().firstValue("X-Syslog-Lines").orElse("?")
                    + " eof=" + eof);
            if (eof || !next.matches("[0-9]{1,18}") || Long.parseLong(next) <= offset) return 0;
            if (!all) return 0;
            offset = Long.parseLong(next);
        }
        System.err.println("-- stopped after " + SYSLOG_ALL_PAGE_CAP + " pages; continue with offset=" + offset);
        return 0;
    }

    /**
     * {@code syslog-settings-set <key>=<value> ...}: every argument is one documented setting. {@code true}/{@code false}
     * become JSON booleans and a run of digits a JSON number, so the service sees the same types the screen sends; any
     * other value is sent as a string and refused by the service with the key named. Keys are the service's own
     * closed list; a key outside it is refused there too, so a misspelt key never silently changes nothing.
     */
    static Map<String, Object> settingsBody(String[] args) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            String key = eq > 0 ? args[i].substring(0, eq) : "";
            if (!key.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new UsageException("syslog-settings-set <key>=<value> [<key>=<value> ...], got: " + args[i]);
            }
            String value = args[i].substring(eq + 1).strip();
            Object typed;
            if ("true".equals(value) || "false".equals(value)) typed = Boolean.valueOf(value);
            else if (value.matches("[0-9]{1,18}")) typed = Long.valueOf(value);
            else typed = value;
            body.put(key, typed);
        }
        return body;
    }

    /** {@code syslog-measurements [deviceId] [vendor=..] [source=..] [limit=..]}: a bare word is the device id. */
    static String[] measurementFilters(String[] args) {
        List<String> filters = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].indexOf('=') > 0) {
                filters.add(args[i]);
            } else if (filters.stream().noneMatch(f -> f.startsWith("device_id="))) {
                filters.add("device_id=" + args[i]);
            } else {
                throw new UsageException("syslog-measurements [deviceId] [vendor=..] [source=..] [limit=..], got: " + args[i]);
            }
        }
        return filters.toArray(new String[0]);
    }

    static String queryString(String[] args, int from) {
        List<String> pairs = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                throw new UsageException("filters are key=value pairs, got: " + args[i]);
            }
            pairs.add(java.net.URLEncoder.encode(args[i].substring(0, eq), StandardCharsets.UTF_8) + "="
                    + java.net.URLEncoder.encode(args[i].substring(eq + 1), StandardCharsets.UTF_8));
        }
        return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
    }

    // ---- tiny json (no dependency in this module) ------------------------------------------------

    static String json(Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    static Optional<String> jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? Optional.of(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\")) : Optional.empty();
    }

    private static String snippet(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8).strip();
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
