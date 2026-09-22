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

    static Path defaultHome() {
        String env = System.getenv("NEXUS_CLI_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".nexus-cli");
    }

    static void printUsage() {
        System.out.println("  login <baseUrl> <username> [local|ldap]       (password: NEXUS_CLI_PASSWORD or console)");
        System.out.println("  logout | session");
        System.out.println("  api <GET|POST|PUT> <path> [jsonBody|@file]     the general form: any route the screen calls");
        System.out.println("  devices");
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
                    yield print(call("GET", "/backups/" + args[1] + "/compare/" + args[2], null));
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
                default -> -1;
            };
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
