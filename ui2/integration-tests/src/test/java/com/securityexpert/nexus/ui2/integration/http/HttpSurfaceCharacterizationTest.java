package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.KeepAliveHttp;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;

/**
 * Characterization of the real HTTP surface of the service: the status code, the response headers and the shape of the
 * body of a representative request per gate class, per login path and per machine path, taken from the booted
 * application (real Tomcat on the main port and on the internal port 8086, real servlet filter chain, real
 * {@code GateChainInterceptor}, real PostgreSQL 16 and Flyway), not from a stand-alone MockMvc.
 *
 * <p>This suite exists to prove that a change below the controllers (the Spring Security migration,
 * docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md, Step 1) changes nothing a client can see. The expected
 * transcript is the committed golden file {@code characterization/http-surface.golden.txt}; it was recorded on the
 * code <em>before</em> any Spring Security dependency existed. Run with the environment variable
 * {@code UI2_CHARACTERIZATION_RECORD=<path>} to write a fresh transcript to that path instead of comparing; an
 * intentional behaviour change updates the golden file in the same commit, with its reason.</p>
 *
 * <p>Values that differ per run (dates, tokens, cookie values, identifiers, content lengths) are normalized; a
 * response header or status that appears, disappears or changes is not. All identities are the documented
 * bootstrap defaults of a throwaway database; the machine tokens are random synthetic values.</p>
 */
class HttpSurfaceCharacterizationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int INTERNAL_PORT = 8086;
    private static final String GOLDEN = "/characterization/http-surface.golden.txt";

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static final List<Entry> transcript = new ArrayList<>();
    /** Migration plan Step 3: the second golden, taken after the default-mode scenario with the local-account scope switched. */
    private static final List<Entry> breakGlassTranscript = new ArrayList<>();
    private static List<Entry> current = transcript;
    private static final String BREAK_GLASS_GOLDEN = "/characterization/http-surface-break-glass.golden.txt";
    private static Locale previousLocale;
    private static java.util.TimeZone previousTimeZone;

    record Entry(String label, Resp resp) {
    }

    record Resp(int status, Map<String, List<String>> headers, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("response is not JSON", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Boot
    // ------------------------------------------------------------------

    @BeforeAll
    static void bootAndRunScenario() throws Exception {
        // The error page resolves its Content-Language from the JVM default when the request carries no Accept-Language,
        // and dates in bodies follow the default zone: pin both so the transcript does not depend on the host.
        previousLocale = Locale.getDefault();
        previousTimeZone = java.util.TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        fixture = Ui2PostgresFixture.createAndMigrate("http_surface");
        work = Files.createTempDirectory("ui2-http-surface-");
        String e2eToken = randomToken();
        String probeToken = randomToken();
        Path groupKey = keyFile("group_ref.key");
        Path credentialKey = keyFile("credential.key");
        context = new SpringApplicationBuilder(Ui2Application.class)
                .resourceLoader(new org.springframework.core.io.DefaultResourceLoader(consoleBundleLoader()))
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--ui2.db.url=" + fixture.databaseJdbcUrl(),
                        "--ui2.db.user-file=" + fixture.appUserSecretFile(),
                        "--ui2.db.password-file=" + fixture.appPasswordSecretFile(),
                        "--ui2.db.migrate-user-file=" + fixture.migrateUserSecretFile(),
                        "--ui2.db.migrate-password-file=" + fixture.migratePasswordSecretFile(),
                        "--ui2.role-binding.group-reference-key-file=" + groupKey,
                        "--ui2.credential-store.key-file=" + credentialKey,
                        "--ui2.failover.master-key-file=" + work.resolve("failover.key"),
                        "--ui2.packet-analysis.root=" + Files.createDirectory(work.resolve("pa")),
                        "--ui2.sanitizer.work-dir=" + Files.createDirectory(work.resolve("sanitizer")),
                        "--ui2.scheduling.enabled=false",
                        "--UI2_E2E_MACHINE_TOKEN_SHA256=" + sha256(e2eToken),
                        "--UI2_PROBE_MACHINE_TOKEN_SHA256=" + sha256(probeToken))
                ;
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
        try {
            runScenario(e2eToken, probeToken);
        } finally {
            String record = System.getenv("UI2_CHARACTERIZATION_RECORD");
            if (record != null && !record.isBlank()) {
                Files.writeString(Path.of(record), render(transcript));
                Files.writeString(Path.of(record + ".break-glass"), render(breakGlassTranscript));
            }
        }
    }

    @AfterAll
    static void shutDown() throws IOException {
        if (previousLocale != null) Locale.setDefault(previousLocale);
        if (previousTimeZone != null) java.util.TimeZone.setDefault(previousTimeZone);
        if (context != null) context.close();
        if (fixture != null) fixture.close();
        if (work != null) {
            try (var paths = Files.walk(work)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * The transcript must not depend on how the console bundle was produced: a CI job may package an empty prebuilt
     * dist directory ({@code -PfrontendPrebuilt=true}), a developer machine a real Vite build. The loader is parent-first,
     * so a real {@code static/} bundle on the classpath wins; only when there is none does the tiny fixture bundle below
     * stand in, serving the same routes with the same headers.
     */
    private static ClassLoader consoleBundleLoader() throws IOException {
        ClassLoader app = HttpSurfaceCharacterizationTest.class.getClassLoader();
        if (app.getResource("static/index.html") != null && hasJsAsset(app)) {
            return app;
        }
        Path root = Files.createDirectories(work.resolve("console-bundle"));
        Files.createDirectories(root.resolve("static/assets"));
        if (app.getResource("static/index.html") == null) {
            Files.writeString(root.resolve("static/index.html"),
                    "<!doctype html><html><head><title>fixture</title></head><body><div id=\"root\"></div></body></html>\n");
        }
        Files.writeString(root.resolve("static/assets/index-fixture0.js"), "console.log('fixture bundle');\n");
        return new java.net.URLClassLoader(new java.net.URL[] {root.toUri().toURL()}, app);
    }

    private static boolean hasJsAsset(ClassLoader loader) throws IOException {
        return new org.springframework.core.io.support.PathMatchingResourcePatternResolver(loader)
                .getResources("classpath*:static/assets/*.js").length > 0;
    }

    private static Path keyFile(String name) throws IOException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Path file = work.resolve(name);
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        return file;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------
    // The scenario
    // ------------------------------------------------------------------

    private static final Map<String, String> JSON_BODY = Map.of("Content-Type", "application/json");

    private static void runScenario(String e2eToken, String probeToken) throws Exception {
        anonymousSurface();
        loginFailures();
        administratorSession();
        viewerSession();
        replayViewerSession();
        asyncAndMultipartSurface();
        machineSessions(e2eToken, probeToken);
        oddRequestTargets();
        current = breakGlassTranscript;
        try {
            breakGlassOnlyMode();
        } finally {
            current = transcript;
        }
        keepAliveProbes();
    }

    // ------------------------------------------------------------------
    // Keep-alive probes (not part of either golden transcript)
    // ------------------------------------------------------------------

    /** Label ("anonymous GET /jobs/{id}/transcript") to the status of the first response on the persistent connection. */
    private static final Map<String, Integer> keepAliveStatus = new LinkedHashMap<>();
    /** Label to what went wrong: the first response did not end, or the same connection could not serve a second request. */
    private static final Map<String, String> keepAliveFailures = new LinkedHashMap<>();
    private static final int KEEP_ALIVE_READ_TIMEOUT_MS = 5_000;
    private static final String BUNDLE_ID = "00000000-0000-4000-8000-000000000002";

    /**
     * Added 2026-11-15 after the batch 12 preview e2e stalled on the aiview transcript denial. Every route that leaves the plain
     * request/response shape (each StreamingResponseBody download, whose refusals and answers can run through an ASYNC or
     * ERROR dispatch) and the transcript read, asked the way the console's browser asks: one persistent HTTP/1.1 connection,
     * a Chromium fetch's headers, the response read by its own framing. Each answer must end inside the read timeout and the
     * same connection must then answer {@code GET /healthz}. The golden suite above uses {@code Connection: close}, where a
     * body that never ends would still look complete the moment the server closes; these probes cannot be fooled that way.
     * Run last, so the transcripts above see the database exactly as they did before.
     */
    private static void keepAliveProbes() throws Exception {
        String jobId = seedBackupJobWithTranscript();
        String origin = "http://127.0.0.1:" + port;
        List<String[]> routes = List.of(
                new String[] {"GET", "/jobs/" + jobId + "/transcript", null},
                new String[] {"GET", "/api/v2/policy/changes.csv", null},
                new String[] {"GET", "/api/v2/reports/runs/" + RUN_ID + "/download", null},
                new String[] {"GET", "/backups/" + ARTEFACT + "/download?ticket=bad", null},
                new String[] {"POST", "/backups/" + ARTEFACT + "/download", "{\"reason\":\"synthetic keep-alive probe\"}"},
                new String[] {"GET", "/api/v2/sanitize/jobs/no-such-job/download", null},
                new String[] {"GET", "/api/v2/sanitize/reverts/no-such-revert/download", null},
                new String[] {"GET", "/api/v2/packet-analysis/captures/no-such-capture/download", null},
                new String[] {"GET", "/api/v2/support-bundles/" + BUNDLE_ID + "/download?ticket=bad", null},
                new String[] {"POST", "/api/v2/support-bundles/" + BUNDLE_ID + "/download",
                        "{\"reason\":\"synthetic keep-alive probe\"}"});
        String[][] identities = {{"anonymous", null}, {"viewer", "claudeadmin"}, {"replay viewer", "aiview"}};
        for (String[] identity : identities) {
            String cookie = null;
            String csrf = null;
            if (identity[1] != null) {
                try (KeepAliveHttp http = new KeepAliveHttp(port, KEEP_ALIVE_READ_TIMEOUT_MS)) {
                    KeepAliveHttp.Response login = http.exchange("POST", "/login", JSON_BODY,
                            JSON.writeValueAsBytes(Map.of("username", identity[1], "password", identity[1])));
                    cookie = sessionCookie(new Resp(login.status(), login.headers(), login.body()));
                    assertNotNull(cookie, identity[0] + " login");
                    // No Accept-Encoding: the status body stays plain JSON.
                    csrf = JSON.readTree(http.exchange("GET", "/session/status", Map.of("Cookie", "ui2_session=" + cookie), null)
                            .body()).path("csrf_token").asText("");
                }
            }
            for (String[] route : routes) {
                String label = identity[0] + " " + route[0] + " " + route[1].replace(jobId, "{id}");
                Map<String, String> headers = KeepAliveHttp.browserHeaders(cookie, origin);
                if (route[2] != null) {
                    headers.put("Origin", origin);
                    headers.put("Content-Type", "application/json");
                    if (csrf != null) headers.put("X-CSRF-Token", csrf);
                }
                try (KeepAliveHttp http = new KeepAliveHttp(port, KEEP_ALIVE_READ_TIMEOUT_MS)) {
                    KeepAliveHttp.Response first = http.exchange(route[0], route[1], headers,
                            route[2] == null ? null : route[2].getBytes(StandardCharsets.UTF_8));
                    keepAliveStatus.put(label, first.status());
                    KeepAliveHttp.Response second = http.exchange("GET", "/healthz", Map.of(), null);
                    if (second.status() != 200) keepAliveFailures.put(label, "second request answered " + second.status());
                } catch (IOException e) {
                    keepAliveFailures.put(label, e.getMessage());
                }
            }
        }
    }

    /** A backup job row carrying a transcript reference, as the backup worker leaves it (the artefact itself is absent). */
    private static String seedBackupJobWithTranscript() throws Exception {
        String jobId = Ui2Rows.opaqueId("job");
        try (var c = fixture.migrateConnection()) {
            String device = Ui2Rows.insertDevice(c, Ui2Rows.insertCredentialReference(c), "ENROLLED");
            c.setAutoCommit(false);
            Ui2Rows.setAuditContext(c, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var st = c.prepareStatement("insert into jobs(job_id, job_type, target_kind, target_device_id, "
                    + "submitted_by_actor_fingerprint, state, action_class, transcript_artefact_ref, transcript_artefact_key) "
                    + "values (?, 'backup', 'device', ?, ?, 'COMPLETED', 'read', 'synthetic-transcript-ref', ?)")) {
                st.setString(1, jobId);
                st.setString(2, device);
                st.setString(3, Ui2Rows.ACTOR);
                st.setBytes(4, new byte[32]);
                st.executeUpdate();
            }
            c.commit();
        }
        return jobId;
    }

    /**
     * Migration plan Step 3 / PO decision D4. Runs after everything above, into its own transcript, so the default-mode golden
     * is untouched. Switching on, local logins under the mode, switching back off (by the administrator who switched it on).
     */
    private static void breakGlassOnlyMode() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        String mode = "/api/v2/config/local-auth-mode";
        // A local administrator that is not the root identity cannot switch the mode on: it would cut off their own next sign-in.
        String viewerCookie = sessionCookie(login("bg viewer login (default mode)", "claudeadmin", "claudeadmin", null));
        String viewerCsrf = authed("bg viewer GET /session/status", "GET", port, "/session/status", viewerCookie, null, null, null)
                .json().path("csrf_token").asText("");
        authed("bg viewer GET mode (refused)", "GET", port, mode, viewerCookie, null, null, null);
        authed("bg viewer PUT mode (refused)", "PUT", port, mode, viewerCookie, viewerCsrf, origin, "{\"mode\":\"BREAK_GLASS_ONLY\"}");
        authed("bg viewer POST /session/logout", "POST", port, "/session/logout", viewerCookie, viewerCsrf, origin, null);

        String cookie = sessionCookie(login("bg admin login (default mode)", "nexusadmin", "nexusadmin", null));
        assertNotNull(cookie);
        String csrf = authed("bg admin GET /session/status", "GET", port, "/session/status", cookie, null, null, null)
                .json().path("csrf_token").asText("");
        authed("bg admin GET mode", "GET", port, mode, cookie, null, null, null);
        authed("bg admin PUT mode no csrf", "PUT", port, mode, cookie, null, origin, "{\"mode\":\"BREAK_GLASS_ONLY\"}");
        authed("bg admin PUT mode empty body", "PUT", port, mode, cookie, csrf, origin, "{}");
        authed("bg admin PUT mode unknown value", "PUT", port, mode, cookie, csrf, origin, "{\"mode\":\"NOBODY\"}");
        authed("bg admin PUT mode extra field", "PUT", port, mode, cookie, csrf, origin, "{\"mode\":\"ALL_LOCAL\",\"x\":1}");
        authed("bg admin PUT mode BREAK_GLASS_ONLY", "PUT", port, mode, cookie, csrf, origin, "{\"mode\":\"BREAK_GLASS_ONLY\"}");
        authed("bg admin GET mode after", "GET", port, mode, cookie, null, null, null);
        authed("bg admin POST /session/logout", "POST", port, "/session/logout", cookie, csrf, origin, null);

        // Under BREAK_GLASS_ONLY: the refusals are indistinguishable from a wrong password.
        login("bg claudeadmin right password (refused)", "claudeadmin", "claudeadmin", null);
        login("bg claudeadmin wrong password", "claudeadmin", "not-the-password", null);
        login("bg unknown user", "nobody-here", "nobody-here", null);
        login("bg aiview-e2e interactive (machine only)", "aiview-e2e", "aiview-e2e", null);
        String replay = sessionCookie(login("bg aiview login", "aiview", "aiview", null));
        assertNotNull(replay);
        String replayCsrf = authed("bg aiview GET /session/status", "GET", port, "/session/status", replay, null, null, null)
                .json().path("csrf_token").asText("");
        authed("bg aiview GET mode (refused)", "GET", port, mode, replay, null, null, null);
        authed("bg aiview POST /session/logout", "POST", port, "/session/logout", replay, replayCsrf, origin, null);
        String root = sessionCookie(login("bg nexusadmin login (break-glass)", "nexusadmin", "nexusadmin", null));
        assertNotNull(root);
        String rootCsrf = authed("bg nexusadmin GET /session/status", "GET", port, "/session/status", root, null, null, null)
                .json().path("csrf_token").asText("");
        authed("bg nexusadmin PUT mode ALL_LOCAL", "PUT", port, mode, root, rootCsrf, origin, "{\"mode\":\"ALL_LOCAL\"}");
        authed("bg nexusadmin POST /session/logout", "POST", port, "/session/logout", root, rootCsrf, origin, null);

        // Back to the default: every local account signs in again.
        String again = sessionCookie(login("bg claudeadmin login (default mode again)", "claudeadmin", "claudeadmin", null));
        assertNotNull(again);
        String againCsrf = authed("bg claudeadmin GET /session/status", "GET", port, "/session/status", again, null, null, null)
                .json().path("csrf_token").asText("");
        authed("bg claudeadmin POST /session/logout", "POST", port, "/session/logout", again, againCsrf, origin, null);
    }

    private static void anonymousSurface() throws Exception {
        get("anon GET /healthz", "/healthz");
        step("anon HEAD /healthz", "HEAD", port, "/healthz", Map.of(), null);
        step("anon POST /healthz", "POST", port, "/healthz", Map.of(), null);
        step("anon PUT /healthz", "PUT", port, "/healthz", Map.of(), null);
        step("anon TRACE /healthz", "TRACE", port, "/healthz", Map.of(), null);
        get("anon GET /", "/");
        get("anon GET /index.html", "/index.html");
        step("anon GET / gzip", "GET", port, "/", Map.of("Accept-Encoding", "gzip"), null);
        String asset = firstAsset();
        Resp assetResp = get("anon GET hashed asset", asset);
        String lastModified = assetResp.header("last-modified");
        if (lastModified != null) {
            step("anon GET hashed asset conditional", "GET", port, asset, Map.of("If-Modified-Since", lastModified), null);
        }
        get("anon GET missing asset", "/assets/does-not-exist.js");
        get("anon GET missing static file", "/does-not-exist.txt");
        get("anon GET /session/status", "/session/status");
        step("anon GET /session/status garbage cookie", "GET", port, "/session/status",
                Map.of("Cookie", "ui2_session=garbage"), null);
        get("anon GET /devices (gated read)", "/devices");
        get("anon GET /api/v2/overview (gated read)", "/api/v2/overview");
        get("anon GET /sessions (admin read)", "/sessions");
        post("anon POST /devices/add-single (gated write)", port, "/devices/add-single", JSON_BODY, "{}");
        post("anon POST /role-bindings (admin write)", port, "/role-bindings", JSON_BODY, "{}");
        get("anon GET unmapped route", "/no-such-route");
        get("anon GET unmapped /api route", "/api/no-such-route");
        post("anon POST unmapped route", port, "/no-such-route", JSON_BODY, "{}");
        step("anon DELETE unmapped route", "DELETE", port, "/no-such-route", Map.of(), null);
        post("anon POST /session/logout", port, "/session/logout", Map.of(), null);
        post("anon POST /local-credentials/change-password", port, "/local-credentials/change-password", JSON_BODY, "{}");
        get("anon GET /error", "/error");
        post("anon POST /error", port, "/error", Map.of(), null);
        step("anon OPTIONS /devices", "OPTIONS", port, "/devices", Map.of(), null);
        step("anon OPTIONS /devices cors preflight", "OPTIONS", port, "/devices", Map.of("Origin", "http://example.invalid",
                "Access-Control-Request-Method", "GET"), null);
        step("anon OPTIONS /", "OPTIONS", port, "/", Map.of(), null);
        get("anon GET /internal/machine-session (main port)", "/internal/machine-session");
        post("anon POST /internal/machine-session (main port)", port, "/internal/machine-session", Map.of(), null);
        get("anon GET /internal (main port)", "/internal");
        post("anon POST /internal/advisories/paloalto (main port)", port, "/internal/advisories/paloalto", JSON_BODY, "{}");
        step("anon GET /devices with Origin header", "GET", port, "/devices", Map.of("Origin", "http://example.invalid"), null);
    }

    private static void loginFailures() throws Exception {
        login("login bad password", "nexusadmin", "wrong-password", null);
        login("login unknown user", "nobody", "wrong-password", null);
        login("login unknown mechanism", "nexusadmin", "nexusadmin", "saml");
        login("login ldap not configured", "someone", "wrong-password", "ldap");
        post("login empty object", port, "/login", JSON_BODY, "{}");
        post("login malformed json", port, "/login", JSON_BODY, "{not json");
        post("login empty body", port, "/login", JSON_BODY, null);
        post("login text/plain body", port, "/login", Map.of("Content-Type", "text/plain"), "x");
        get("login GET", "/login");
        post("login resolve bogus token", port, "/login/resolve", JSON_BODY, "{\"conflictToken\":\"bogus\",\"action\":\"takeover\"}");
        post("login resolve empty", port, "/login/resolve", JSON_BODY, "{}");
    }

    private static void administratorSession() throws Exception {
        Resp ok = login("admin login", "nexusadmin", "nexusadmin", null);
        String cookie = sessionCookie(ok);
        assertNotNull(cookie, "the administrator login must issue a session cookie");
        Resp conflict = login("admin login again (active session)", "nexusadmin", "nexusadmin", null);
        String conflictToken = conflict.json().path("conflict_token").asText("");
        post("admin resolve with refusal", port, "/login/resolve", JSON_BODY,
                "{\"conflictToken\":\"" + conflictToken + "\",\"action\":\"refuse\"}");
        Resp again = login("admin login again after refusal", "nexusadmin", "nexusadmin", null);
        String secondToken = again.json().path("conflict_token").asText("");
        Resp takeover = post("admin resolve takeover", port, "/login/resolve", JSON_BODY,
                "{\"conflictToken\":\"" + secondToken + "\",\"action\":\"takeover\"}");
        String newCookie = sessionCookie(takeover);
        assertNotNull(newCookie, "a takeover must issue a new session cookie");
        authed("admin old cookie after takeover", "GET", port, "/devices", cookie, null, null, null);
        Resp status = authed("admin GET /session/status", "GET", port, "/session/status", newCookie, null, null, null);
        String csrf = status.json().path("csrf_token").asText("");
        assertFalse(csrf.isEmpty(), "an authenticated status carries the CSRF token");
        String origin = "http://127.0.0.1:" + port;

        authed("admin GET /healthz", "GET", port, "/healthz", newCookie, null, null, null);
        authed("admin GET /", "GET", port, "/", newCookie, null, null, null);
        authed("admin GET hashed asset", "GET", port, firstAsset(), newCookie, null, null, null);
        authed("admin GET /devices", "GET", port, "/devices", newCookie, null, null, null);
        authed("admin GET /api/v2/overview", "GET", port, "/api/v2/overview", newCookie, null, null, null);
        authed("admin GET /sessions", "GET", port, "/sessions", newCookie, null, null, null);
        authed("admin GET /role-bindings", "GET", port, "/role-bindings", newCookie, null, null, null);
        authed("admin GET /api/v2/lifecycle", "GET", port, "/api/v2/lifecycle", newCookie, null, null, null);
        authed("admin GET unmapped route", "GET", port, "/no-such-route", newCookie, null, null, null);
        authed("admin GET unmapped /api route", "GET", port, "/api/no-such-route", newCookie, null, null, null);
        authed("admin GET unmapped POST-only route", "GET", port, "/devices/add-single", newCookie, null, null, null);
        authed("admin POST unmapped route", "POST", port, "/no-such-route", newCookie, csrf, origin, "{}");
        authed("admin POST /devices/add-single no csrf", "POST", port, "/devices/add-single", newCookie, null, origin, "{}");
        authed("admin POST /devices/add-single wrong csrf", "POST", port, "/devices/add-single", newCookie, "wrong", origin, "{}");
        authed("admin POST /devices/add-single no origin", "POST", port, "/devices/add-single", newCookie, csrf, null, "{}");
        authed("admin POST /devices/add-single empty object", "POST", port, "/devices/add-single", newCookie, csrf, origin, "{}");
        authed("admin POST /devices/add-single malformed json", "POST", port, "/devices/add-single", newCookie, csrf, origin, "{bad");
        authed("admin PUT /devices/add-single", "PUT", port, "/devices/add-single", newCookie, csrf, origin, "{}");
        authed("admin POST /role-bindings empty object", "POST", port, "/role-bindings", newCookie, csrf, origin, "{}");
        // PO 2026-11-15: sign-out requires the CSRF token. A refused sign-out leaves the session live, so both refusals
        // run against it and a valid one ends it before the next sign-in.
        authed("admin POST /session/logout no csrf", "POST", port, "/session/logout", newCookie, null, origin, null);
        authed("admin POST /session/logout wrong csrf", "POST", port, "/session/logout", newCookie, "wrong", origin, null);
        authed("admin POST /session/logout first session", "POST", port, "/session/logout", newCookie, csrf, origin, null);
        String thirdCookie = sessionCookie(login("admin login after logout", "nexusadmin", "nexusadmin", null));
        assertNotNull(thirdCookie);
        String thirdCsrf = authed("admin GET /session/status second session", "GET", port, "/session/status", thirdCookie,
                null, null, null).json().path("csrf_token").asText("");
        authed("admin POST /session/logout", "POST", port, "/session/logout", thirdCookie, thirdCsrf, origin, null);
        authed("admin GET /session/status after logout", "GET", port, "/session/status", thirdCookie, null, null, null);
        authed("admin GET /devices after logout", "GET", port, "/devices", thirdCookie, null, null, null);
    }

    private static void viewerSession() throws Exception {
        String cookie = sessionCookie(login("viewer login", "claudeadmin", "claudeadmin", null));
        assertNotNull(cookie);
        Resp status = authed("viewer GET /session/status", "GET", port, "/session/status", cookie, null, null, null);
        String csrf = status.json().path("csrf_token").asText("");
        String origin = "http://127.0.0.1:" + port;
        authed("viewer GET /devices", "GET", port, "/devices", cookie, null, null, null);
        authed("viewer GET /api/v2/overview", "GET", port, "/api/v2/overview", cookie, null, null, null);
        authed("viewer GET /sessions (refused)", "GET", port, "/sessions", cookie, null, null, null);
        authed("viewer GET /role-bindings (refused)", "GET", port, "/role-bindings", cookie, null, null, null);
        authed("viewer POST /devices/add-single (refused)", "POST", port, "/devices/add-single", cookie, csrf, origin, "{}");
        authed("viewer POST /role-bindings (refused)", "POST", port, "/role-bindings", cookie, csrf, origin, "{}");
        authed("viewer POST /session/logout", "POST", port, "/session/logout", cookie, csrf, origin, null);
    }

    private static void replayViewerSession() throws Exception {
        String cookie = sessionCookie(login("replay viewer login", "aiview", "aiview", null));
        assertNotNull(cookie);
        Resp status = authed("replay viewer GET /session/status", "GET", port, "/session/status", cookie, null, null, null);
        String csrf = status.json().path("csrf_token").asText("");
        String origin = "http://127.0.0.1:" + port;
        authed("replay viewer GET /devices", "GET", port, "/devices", cookie, null, null, null);
        authed("replay viewer GET /api/v2/overview", "GET", port, "/api/v2/overview", cookie, null, null, null);
        authed("replay viewer GET /sessions (refused)", "GET", port, "/sessions", cookie, null, null, null);
        authed("replay viewer POST /devices/add-single (refused)", "POST", port, "/devices/add-single", cookie, csrf, origin, "{}");
        authed("replay viewer POST /session/logout", "POST", port, "/session/logout", cookie, csrf, origin, null);
    }

    private static void machineSessions(String e2eToken, String probeToken) throws Exception {
        Map<String, String> noToken = Map.of();
        step("machine 8086 no token", "POST", INTERNAL_PORT, "/internal/machine-session", noToken, null);
        step("machine 8086 wrong token", "POST", INTERNAL_PORT, "/internal/machine-session",
                Map.of("X-Nexus-Machine-Token", "wrong-token"), null);
        step("machine 8086 e2e token wrong purpose", "POST", INTERNAL_PORT, "/internal/machine-session",
                Map.of("X-Nexus-Machine-Token", e2eToken, "X-Nexus-Machine-Purpose", "other"), null);
        Resp probe = step("machine 8086 probe token", "POST", INTERNAL_PORT, "/internal/machine-session",
                Map.of("X-Nexus-Machine-Token", probeToken), null);
        assertNotNull(sessionCookie(probe), "the probe token must issue a machine session");
        Resp e2e = step("machine 8086 e2e token", "POST", INTERNAL_PORT, "/internal/machine-session",
                Map.of("X-Nexus-Machine-Token", e2eToken), null);
        String cookie = sessionCookie(e2e);
        assertNotNull(cookie, "the e2e token must issue a machine session");
        String csrf = e2e.json().path("csrf_token").asText("");
        String origin = "http://127.0.0.1:" + port;

        step("machine 8086 GET machine-session", "GET", INTERNAL_PORT, "/internal/machine-session", noToken, null);
        step("machine 8086 GET /healthz", "GET", INTERNAL_PORT, "/healthz", noToken, null);
        step("machine 8086 POST /login", "POST", INTERNAL_PORT, "/login", JSON_BODY, "{}");
        step("machine 8086 GET /devices with machine cookie", "GET", INTERNAL_PORT, "/devices",
                Map.of("Cookie", "ui2_session=" + cookie), null);
        step("machine 8086 POST /internal/advisories/paloalto no token", "POST", INTERNAL_PORT,
                "/internal/advisories/paloalto", JSON_BODY, "{}");
        step("machine 8086 POST /internal/advisories/unknown", "POST", INTERNAL_PORT,
                "/internal/advisories/unknown", JSON_BODY, "{}");
        step("machine 8086 POST /internal/lifecycle-sources/cisco_eol no token", "POST", INTERNAL_PORT,
                "/internal/lifecycle-sources/cisco_eol", JSON_BODY, "{}");

        authed("machine GET /session/status", "GET", port, "/session/status", cookie, null, null, null);
        authed("machine GET /devices", "GET", port, "/devices", cookie, null, null, null);
        authed("machine GET /api/v2/overview", "GET", port, "/api/v2/overview", cookie, null, null, null);
        authed("machine GET /sessions (refused)", "GET", port, "/sessions", cookie, null, null, null);
        authed("machine GET unmapped route", "GET", port, "/no-such-route", cookie, null, null, null);
        authed("machine POST /devices/add-single (read-only session)", "POST", port, "/devices/add-single", cookie, csrf, origin, "{}");
        authed("machine PUT /devices/add-single (read-only session)", "PUT", port, "/devices/add-single", cookie, csrf, origin, "{}");
        authed("machine DELETE unmapped (read-only session)", "DELETE", port, "/no-such-route", cookie, csrf, origin, null);
        authed("machine POST /role-bindings (read-only session)", "POST", port, "/role-bindings", cookie, csrf, origin, "{}");
        authed("machine POST /session/logout", "POST", port, "/session/logout", cookie, csrf, origin, null);
    }

    private static final String BOUNDARY = "----ui2characterization7d3c";
    private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;
    private static final String ARTEFACT = "0123456789abcdef";
    private static final String RUN_ID = "00000000-0000-4000-8000-000000000001";

    /**
     * Added after the Spring Security Step 1 review: the routes that leave the plain request/response shape. Streaming
     * routes (StreamingResponseBody: the container dispatches again with DispatcherType.ASYNC after the controller
     * returns), multipart upload routes (the body is read straight off the servlet input stream), and the error dispatch
     * (DispatcherType.ERROR) that follows a sendError from inside such a route. Recorded on the code before the Spring
     * Security dependency existed.
     */
    private static void asyncAndMultipartSurface() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        String csv = "/api/v2/policy/changes.csv";
        String download = "/api/v2/reports/runs/" + RUN_ID + "/download";
        String ticketUrl = "/backups/" + ARTEFACT + "/download?ticket=bad";
        String offline = "/api/v2/policy/policies/no-such-policy/offline-analyses";
        String assess = "/api/v2/cutover/segments/no-such-segment/assessments";
        String file = multipart(part("file", "sample.txt", "synthetic line\n"));
        String csvFile = multipart(part("mapping", null, "{}"), part("file", "sample.csv", "a,b\n1,2\n"));
        String spec = multipart(part("spec", null, "{}"));

        // anonymous
        get("async anon GET changes.csv", csv);
        get("async anon GET reports run download", download);
        step("async anon GET backup download with ticket", "GET", port, ticketUrl, Map.of(), null);
        post("async anon POST backup download", port, "/backups/bad!/download", JSON_BODY, "{}");
        step("multipart anon POST sanitize", "POST", port, "/api/v2/sanitize", Map.of("Content-Type", MULTIPART), file);
        step("multipart anon POST offline-analyses", "POST", port, offline, Map.of("Content-Type", MULTIPART), csvFile);

        // administrator
        String cookie = sessionCookie(login("async admin login", "nexusadmin", "nexusadmin", null));
        assertNotNull(cookie);
        String csrf = authed("async admin GET /session/status", "GET", port, "/session/status", cookie, null, null, null)
                .json().path("csrf_token").asText("");
        authed("async admin GET changes.csv", "GET", port, csv, cookie, null, null, null);
        authed("async admin GET changes.csv filtered", "GET", port,
                csv + "?from=2026-01-01T00:00:00Z&limit=5&includeInitial=true", cookie, null, null, null);
        authed("async admin GET changes.csv bad filter", "GET", port, csv + "?from=not-a-date", cookie, null, null, null);
        authed("async admin GET changes.csv bad limit type", "GET", port, csv + "?limit=abc", cookie, null, null, null);
        authed("async admin HEAD changes.csv", "HEAD", port, csv, cookie, null, null, null);
        authed("async admin GET reports run download", "GET", port, download, cookie, null, null, null);
        authed("async admin GET reports run download bad id", "GET", port, "/api/v2/reports/runs/not-a-uuid/download",
                cookie, null, null, null);
        authed("async admin GET backup download with bad ticket", "GET", port, ticketUrl, cookie, null, null, null);
        authed("async admin POST backup download bad id", "POST", port, "/backups/bad!/download", cookie, csrf, origin, "{}");
        authed("async admin POST backup download no csrf", "POST", port, "/backups/" + ARTEFACT + "/download", cookie, null,
                origin, "{}");
        authed("async admin POST backup download", "POST", port, "/backups/" + ARTEFACT + "/download", cookie, csrf, origin,
                "{\"reason\":\"synthetic characterization run\"}");
        authed("async admin POST backup download malformed json", "POST", port, "/backups/" + ARTEFACT + "/download", cookie,
                csrf, origin, "{bad");

        authedRaw("multipart admin POST sanitize no csrf", "POST", "/api/v2/sanitize", cookie, null, origin, MULTIPART, file);
        authedRaw("multipart admin POST sanitize no origin", "POST", "/api/v2/sanitize", cookie, csrf, null, MULTIPART, file);
        authedRaw("multipart admin POST sanitize no file part", "POST", "/api/v2/sanitize", cookie, csrf, origin, MULTIPART,
                multipart(part("other", null, "x")));
        authedRaw("multipart admin POST sanitize empty body", "POST", "/api/v2/sanitize", cookie, csrf, origin, MULTIPART, "");
        authedRaw("multipart admin POST sanitize broken body", "POST", "/api/v2/sanitize", cookie, csrf, origin, MULTIPART,
                "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x\"\r\n\r\nno terminator");
        authedRaw("multipart admin POST sanitize no boundary", "POST", "/api/v2/sanitize", cookie, csrf, origin,
                "multipart/form-data", file);
        authedRaw("multipart admin POST sanitize json content type", "POST", "/api/v2/sanitize", cookie, csrf, origin,
                "application/json", "{}");
        authedRaw("multipart admin POST sanitize revert no file part", "POST", "/api/v2/sanitize/revert", cookie, csrf, origin,
                MULTIPART, multipart(part("other", null, "x")));
        authedRaw("multipart admin POST offline-analyses unknown policy", "POST", offline, cookie, csrf, origin, MULTIPART, csvFile);
        authedRaw("multipart admin POST offline-analyses file first", "POST", offline, cookie, csrf, origin, MULTIPART,
                multipart(part("file", "sample.csv", "a,b\n")));
        authedRaw("multipart admin POST offline-analyses json content type", "POST", offline, cookie, csrf, origin,
                "application/json", "{}");
        authedRaw("multipart admin POST cutover assessments unknown segment", "POST", assess, cookie, csrf, origin, MULTIPART, spec);
        authedRaw("multipart admin POST cutover assessments broken body", "POST", assess, cookie, csrf, origin, MULTIPART,
                "garbage");
        authed("async admin POST /session/logout", "POST", port, "/session/logout", cookie, csrf, origin, null);

        // read-only operator and replay viewer
        String viewerCookie = sessionCookie(login("async viewer login", "claudeadmin", "claudeadmin", null));
        assertNotNull(viewerCookie);
        String viewerCsrf = authed("async viewer GET /session/status", "GET", port, "/session/status", viewerCookie, null, null,
                null).json().path("csrf_token").asText("");
        authed("async viewer GET changes.csv", "GET", port, csv, viewerCookie, null, null, null);
        authed("async viewer GET reports run download", "GET", port, download, viewerCookie, null, null, null);
        authed("async viewer POST backup download", "POST", port, "/backups/" + ARTEFACT + "/download", viewerCookie, viewerCsrf,
                origin, "{}");
        authedRaw("multipart viewer POST sanitize", "POST", "/api/v2/sanitize", viewerCookie, viewerCsrf, origin, MULTIPART, file);
        authedRaw("multipart viewer POST offline-analyses", "POST", offline, viewerCookie, viewerCsrf, origin, MULTIPART, csvFile);
        authed("async viewer POST /session/logout", "POST", port, "/session/logout", viewerCookie, viewerCsrf, origin, null);

        String replayCookie = sessionCookie(login("async replay viewer login", "aiview", "aiview", null));
        assertNotNull(replayCookie);
        String replayCsrf = authed("async replay viewer GET /session/status", "GET", port, "/session/status", replayCookie, null,
                null, null).json().path("csrf_token").asText("");
        authed("async replay viewer GET changes.csv", "GET", port, csv, replayCookie, null, null, null);
        authed("async replay viewer GET reports run download", "GET", port, download, replayCookie, null, null, null);
        authedRaw("multipart replay viewer POST sanitize", "POST", "/api/v2/sanitize", replayCookie, replayCsrf, origin, MULTIPART, file);
        authed("async replay viewer POST /session/logout", "POST", port, "/session/logout", replayCookie, replayCsrf, origin, null);
    }

    private static String multipart(String... parts) {
        return String.join("", parts) + "--" + BOUNDARY + "--\r\n";
    }

    private static String part(String field, String filename, String value) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field + "\""
                + (filename == null ? "" : "; filename=\"" + filename + "\"")
                + (filename == null ? "" : "\r\nContent-Type: text/plain") + "\r\n\r\n" + value + "\r\n";
    }

    private static Resp authedRaw(String label, String method, String path, String cookie, String csrf, String origin,
            String contentType, String body) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "ui2_session=" + cookie);
        if (csrf != null) headers.put("X-CSRF-Token", csrf);
        if (origin != null) headers.put("Origin", origin);
        headers.put("Content-Type", contentType);
        return step(label, method, port, path, headers, body);
    }

    private static void oddRequestTargets() throws Exception {
        String[] targets = {
            "//healthz", "/healthz/", "/healthz;x=1", "/healthz;jsessionid=abc", "/./healthz", "/healthz/.", "/a/../healthz",
            "/healthz%2f", "/%2e/healthz", "/%2e%2e/healthz", "/healthz%00", "/healthz%25", "/%C3%A9", "/a%5Cb", "/a%5cb",
            "/%zz", "/healthz?x=%zz", "/healthz?x=1&x=2", "/devices;x=1", "/devices/", "//devices", "/devices%2f",
            "/devices/%2e%2e/healthz", "/session/status;x=1", "/session/status/", "/login;x=1", "/login/",
            "/" + "a".repeat(9000)
        };
        for (String target : targets) {
            String label = target.length() > 60 ? "very long target (" + target.length() + " chars)" : target;
            step("odd target GET " + label, "GET", port, target, Map.of(), null);
        }
        step("odd target POST //login", "POST", port, "//login", JSON_BODY, "{}");
        step("odd target POST /login;x=1", "POST", port, "/login;x=1", JSON_BODY, "{}");
        step("odd target absolute-form", "GET", port, "http://127.0.0.1:" + port + "/healthz", Map.of(), null);
        step("odd request duplicate cookie", "GET", port, "/session/status",
                Map.of("Cookie", "ui2_session=a; ui2_session=b"), null);
        step("odd request long cookie", "GET", port, "/devices", Map.of("Cookie", "ui2_session=" + "c".repeat(3000)), null);
        step("odd request Authorization basic header", "GET", port, "/devices",
                Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString("a:b".getBytes(StandardCharsets.UTF_8))), null);
        step("odd request X-Forwarded headers", "GET", port, "/healthz",
                Map.of("X-Forwarded-For", "192.0.2.1", "X-Forwarded-Proto", "https"), null);
        step("odd request Accept text/html on gated route", "GET", port, "/devices", Map.of("Accept", "text/html"), null);
        step("odd request Accept text/html on unmapped route", "GET", port, "/no-such-route", Map.of("Accept", "text/html"), null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Resp get(String label, String path) throws Exception {
        return step(label, "GET", port, path, Map.of(), null);
    }

    private static Resp post(String label, int onPort, String path, Map<String, String> headers, String body) throws Exception {
        return step(label, "POST", onPort, path, headers, body);
    }

    private static Resp login(String label, String user, String password, String mechanism) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", user);
        body.put("password", password);
        if (mechanism != null) body.put("mechanism_id", mechanism);
        return post(label, port, "/login", JSON_BODY, JSON.writeValueAsString(body));
    }

    private static Resp authed(String label, String method, int onPort, String path, String cookie, String csrf, String origin,
            String body) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "ui2_session=" + cookie);
        if (csrf != null) headers.put("X-CSRF-Token", csrf);
        if (origin != null) headers.put("Origin", origin);
        if (body != null) headers.put("Content-Type", "application/json");
        return step(label, method, onPort, path, headers, body);
    }

    private static String sessionCookie(Resp resp) {
        List<String> cookies = resp.headers().get("set-cookie");
        if (cookies == null) return null;
        for (String cookie : cookies) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                value = end < 0 ? value : value.substring(0, end);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String firstAsset() throws IOException {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver(context.getClassLoader());
        // Sorted, so the chosen file does not depend on the order the classpath lists a multi-chunk bundle in.
        var names = new java.util.TreeSet<String>();
        for (var resource : resolver.getResources("classpath*:static/assets/*.js")) {
            names.add(resource.getFilename());
        }
        if (!names.isEmpty()) {
            return "/assets/" + names.first();
        }
        throw new IllegalStateException("the packaged console bundle has no /assets/*.js file");
    }

    private static Resp step(String label, String method, int onPort, String target, Map<String, String> headers, String body)
            throws Exception {
        Resp resp = send(method, onPort, target, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
        current.add(new Entry(label + (onPort == INTERNAL_PORT ? " [8086]" : ""), resp));
        return resp;
    }

    /** One HTTP/1.1 request over a plain socket, so request targets that a client library would rewrite or refuse are sent as written. */
    private static Resp send(String method, int onPort, String target, Map<String, String> headers, byte[] body) throws Exception {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1:").append(onPort).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("User-Agent: ui2-http-surface-characterization\r\n");
        for (var header : headers.entrySet()) head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        if (body != null || "POST".equals(method) || "PUT".equals(method)) {
            head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        }
        head.append("\r\n");
        request.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) request.write(body);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", onPort), 10_000);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.toByteArray());
            socket.getOutputStream().flush();
            return parse(readAll(socket.getInputStream()), "HEAD".equals(method));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        try {
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        } catch (java.net.SocketException reset) {
            // a server that closes while the client still writes (an oversized request) ends the read here
        }
        return out.toByteArray();
    }

    private static Resp parse(byte[] raw, boolean head) {
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
        if (split < 0) return new Resp(-1, Map.of(), raw);
        String[] lines = new String(raw, 0, split, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, List<String>> headers = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.computeIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).trim());
        }
        byte[] payload = new byte[raw.length - split - 4];
        System.arraycopy(raw, split + 4, payload, 0, payload.length);
        if (head || status == 204 || status == 304) payload = new byte[0];
        List<String> encoding = headers.get("transfer-encoding");
        if (encoding != null && encoding.get(0).toLowerCase(Locale.ROOT).contains("chunked")) payload = dechunk(payload);
        return new Resp(status, headers, payload);
    }

    private static byte[] dechunk(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < in.length) {
            int eol = indexOf(in, "\r\n".getBytes(StandardCharsets.ISO_8859_1), pos);
            if (eol < 0) break;
            String sizeText = new String(in, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int semicolon = sizeText.indexOf(';');
            int size = Integer.parseInt((semicolon < 0 ? sizeText : sizeText.substring(0, semicolon)).trim(), 16);
            if (size == 0) break;
            out.write(in, eol + 2, size);
            pos = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Normalization and rendering
    // ------------------------------------------------------------------

    private static final Pattern HTTP_DATE = Pattern.compile("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");
    private static final Pattern TOKEN = Pattern.compile("(?=[A-Za-z0-9_-]*[0-9A-Z])[A-Za-z0-9_-]{32,}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern HEX12 = Pattern.compile("[0-9a-f]{12}");
    /** A masked user pseudonym (per-run key, so its value changes between runs). */
    private static final Pattern USER_PSEUDONYM = Pattern.compile("USR-[0-9A-F]{10}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    // A bare calendar day (e.g. lifecycle "as_of") depends on the run date, not on the HTTP surface (2026-11-16).
    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    static String render(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append("### ").append(entry.label()).append('\n');
            out.append("status: ").append(entry.resp().status()).append('\n');
            for (var header : entry.resp().headers().entrySet()) {
                for (String value : header.getValue().stream().map(v -> normalizeHeader(header.getKey(), v)).sorted().toList()) {
                    out.append("header: ").append(header.getKey()).append(": ").append(value).append('\n');
                }
            }
            out.append("body: ").append(normalizeBody(entry.resp())).append('\n').append('\n');
        }
        return out.toString();
    }

    private static String normalizeHeader(String name, String value) {
        return switch (name) {
            case "date", "last-modified" -> "<date>";
            case "content-length" -> "<n>";
            case "allow" -> java.util.Arrays.stream(value.split(",\\s*")).sorted().collect(java.util.stream.Collectors.joining(", "));
            case "etag" -> "<etag>";
            // Enterprise phase 0.3: every response carries its correlation id (a generated UUID per request).
            case "x-request-id" -> "<request-id>";
            case "set-cookie" -> HTTP_DATE.matcher(value.replaceAll("ui2_session=[^;]*", "ui2_session=<v>")).replaceAll("<date>");
            default -> HTTP_DATE.matcher(value).replaceAll("<date>");
        };
    }

    private static String normalizeBody(Resp resp) {
        if (resp.body().length == 0) return "<empty>";
        String type = resp.header("content-type");
        if (type != null && type.toLowerCase(Locale.ROOT).contains("json")) {
            try {
                return JSON.writeValueAsString(normalizeJson(JSON.readTree(resp.body())));
            } catch (IOException e) {
                return "<invalid json>";
            }
        }
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("text/plain") && resp.body().length <= 64) {
            return "text " + resp.text();
        }
        return "<" + (type == null ? "no content type" : type.toLowerCase(Locale.ROOT).replaceAll(";.*", "")) + ", non-empty>";
    }

    private static JsonNode normalizeJson(JsonNode node) {
        var factory = JSON.getNodeFactory();
        if (node.isObject()) {
            var copy = factory.objectNode();
            var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(e -> sorted.put(HEX64.matcher(e.getKey()).matches() ? "<hex64>" : e.getKey(),
                    normalizeJson(e.getValue())));
            sorted.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            var copy = factory.arrayNode();
            node.forEach(e -> copy.add(normalizeJson(e)));
            return copy;
        }
        if (node.isNumber()) return factory.textNode("<n>");
        if (node.isTextual()) {
            String value = node.asText();
            if (HEX12.matcher(value).matches()) return factory.textNode("<hex12>");
            if (USER_PSEUDONYM.matcher(value).matches()) return factory.textNode("<user-pseudonym>");
            value = TIMESTAMP.matcher(value).replaceAll("<ts>");
            if (DAY.matcher(value).matches()) value = "<day>";
            value = UUID.matcher(value).replaceAll("<id>");
            value = TOKEN.matcher(value).replaceAll("<token>");
            return factory.textNode(value);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    @Test
    void theTranscriptMatchesTheRecordedGolden() throws IOException {
        if (recording()) return; // recording run: the transcripts were written in @BeforeAll
        compareToGolden(GOLDEN, render(transcript));
    }

    @Test
    void theBreakGlassOnlyTranscriptMatchesItsGolden() throws IOException {
        if (recording()) return;
        compareToGolden(BREAK_GLASS_GOLDEN, render(breakGlassTranscript));
    }

    @Test
    void underBreakGlassOnlyARefusedRightPasswordLooksLikeAWrongOneAndLeavesTheAuditAndAlertRows() throws Exception {
        Resp right = breakGlassEntry("bg claudeadmin right password (refused)").resp();
        Resp wrong = breakGlassEntry("bg claudeadmin wrong password").resp();
        assertEquals(401, right.status());
        assertEquals(wrong.status(), right.status());
        assertEquals(wrong.json(), right.json());
        assertEquals(wrong.headers().keySet(), right.headers().keySet());
        assertEquals(200, breakGlassEntry("bg aiview login").resp().status());
        assertEquals(401, breakGlassEntry("bg aiview-e2e interactive (machine only)").resp().status());
        // the mode was switched, used and switched back, each step audited (the refusal row is written off the request thread)
        long deadline = System.currentTimeMillis() + 10_000;
        while (auditCount("local_non_break_glass_refused") < 1 && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertEquals(1, auditCount("local_non_break_glass_refused"), "only claudeadmin, whose password was right; wrong passwords are no event");
        assertEquals(2, auditCount("local_break_glass_login_succeeded"), "nexusadmin (alert) and aiview");
        assertEquals(2, auditCount("local_auth_mode_write"));
        try (var c = fixture.migrateConnection(); var st = c.createStatement();
                var rs = st.executeQuery("select count(*) from local_break_glass_event where alert and outcome = 'LOGIN_SUCCEEDED'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "only the root identity raises the alert");
        }
    }

    private static int auditCount(String action) throws Exception {
        try (var c = fixture.migrateConnection(); var st = c.createStatement();
                var rs = st.executeQuery("select count(*) from audit_log where action_id = '" + action + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Entry breakGlassEntry(String label) {
        return breakGlassTranscript.stream().filter(e -> e.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no probe named: " + label));
    }

    private static boolean recording() {
        return System.getenv("UI2_CHARACTERIZATION_RECORD") != null && !System.getenv("UI2_CHARACTERIZATION_RECORD").isBlank();
    }

    private static void compareToGolden(String resource, String actual) throws IOException {
        String golden;
        try (InputStream in = HttpSurfaceCharacterizationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "golden file " + resource + " is missing");
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (golden.equals(actual)) return;
        String[] expectedBlocks = golden.split("\n\n");
        String[] actualBlocks = actual.split("\n\n");
        for (int i = 0; i < Math.max(expectedBlocks.length, actualBlocks.length); i++) {
            String expected = i < expectedBlocks.length ? expectedBlocks[i] : "<missing>";
            String got = i < actualBlocks.length ? actualBlocks[i] : "<missing>";
            if (!expected.equals(got)) {
                // A CI log that truncates the assertion message still shows the block.
                System.err.println("HTTP SURFACE MISMATCH in " + resource + " at block " + i + " (blocks: golden " + expectedBlocks.length
                        + ", actual " + actualBlocks.length + ")\n----- expected -----\n" + expected
                        + "\n----- actual -----\n" + got + "\n----- end -----");
            }
            assertEquals(expected, got, "the HTTP surface changed at block " + i + " of " + resource);
        }
        assertEquals(golden, actual);
    }

    @Test
    void theGateClassesStillDecideAsRecorded() {
        assertEquals(200, status("anon GET /healthz"));
        assertEquals(401, status("anon GET /devices (gated read)"));
        assertEquals("SESSION_INVALID", body("anon GET /devices (gated read)").path("error").asText());
        // No handler at all: the dispatcher answers 404 before any interceptor; under /api the static handler is selected.
        assertEquals(404, status("anon GET unmapped route"));
        assertEquals(403, status("anon GET unmapped /api route"));
        assertEquals("ACTION_MAPPING_REQUIRED", body("anon GET unmapped /api route").path("error").asText());
        assertEquals(200, status("admin GET /devices"));
        assertEquals(200, status("admin GET /sessions"));
        assertEquals(401, status("admin POST /devices/add-single no csrf"));
        assertEquals(401, status("admin POST /devices/add-single no origin"));
        assertEquals(403, status("viewer GET /sessions (refused)"));
        assertEquals("ACTION_REFUSED", body("viewer GET /sessions (refused)").path("error").asText());
        assertEquals(403, status("replay viewer POST /devices/add-single (refused)"));
        assertEquals(403, status("machine POST /devices/add-single (read-only session)"));
        assertEquals("MACHINE_SESSION_READ_ONLY",
                body("machine POST /devices/add-single (read-only session)").path("error").asText());
        assertEquals(200, status("machine GET /devices"));
        assertEquals(401, status("admin GET /devices after logout"));
    }

    @Test
    void theLoginPathsStillAnswerAsRecorded() {
        assertEquals(401, status("login bad password"));
        assertEquals("INVALID_CREDENTIALS", body("login bad password").path("error").asText());
        assertEquals(status("login bad password"), status("login unknown user"));
        assertEquals(body("login bad password"), body("login unknown user"));
        assertEquals(400, status("login unknown mechanism"));
        assertEquals(200, status("admin login"));
        assertTrue(body("admin login").path("ok").asBoolean());
        assertEquals(409, status("admin login again (active session)"));
        assertEquals("LOGIN_CONFLICT", body("admin login again (active session)").path("error").asText());
        assertEquals(200, status("admin resolve takeover"));
        assertEquals(200, status("viewer login"));
        assertEquals(200, status("replay viewer login"));
        assertEquals(401, status("admin POST /session/logout no csrf"));
        assertEquals(401, status("admin POST /session/logout wrong csrf"));
        assertEquals(200, status("admin POST /session/logout first session"));
        assertEquals(200, status("admin POST /session/logout"));
    }

    @Test
    void theMachinePortStillServesOnlyItsPaths() {
        assertEquals(404, status("anon POST /internal/machine-session (main port)"));
        assertEquals(404, status("anon GET /internal (main port)"));
        assertEquals(403, status("machine 8086 no token [8086]"));
        assertEquals(403, status("machine 8086 wrong token [8086]"));
        assertEquals(200, status("machine 8086 e2e token [8086]"));
        assertEquals(200, status("machine 8086 probe token [8086]"));
        assertEquals(404, status("machine 8086 GET machine-session [8086]"));
        assertEquals(404, status("machine 8086 GET /healthz [8086]"));
        assertEquals(404, status("machine 8086 POST /login [8086]"));
        assertEquals(404, status("machine 8086 GET /devices with machine cookie [8086]"));
    }

    @Test
    void everyResponseCarriesTheSecurityHeadersAndStaticAssetsKeepTheirCaching() {
        // PO 2026-11-15: nosniff, X-Frame-Options DENY and a no-store default on every response; a response that chose its
        // own cache policy (the immutable /assets/*, the revalidated index page) keeps it.
        for (Entry entry : transcript) {
            Resp resp = entry.resp();
            if (answeredByTomcatBeforeTheFilterChain(entry)) continue;
            assertEquals("nosniff", resp.header("x-content-type-options"), entry.label() + " lacks nosniff");
            assertEquals("DENY", resp.header("x-frame-options"), entry.label() + " lacks X-Frame-Options");
            assertNotNull(resp.header("cache-control"), entry.label() + " lacks Cache-Control");
        }
        assertEquals("no-store", transcript.stream().filter(e -> e.label().equals("anon GET /healthz")).findFirst()
                .orElseThrow().resp().header("cache-control"));
        assertEquals("no-store", transcript.stream().filter(e -> e.label().equals("admin GET /session/status")).findFirst()
                .orElseThrow().resp().header("cache-control"));
        assertEquals("max-age=31536000, public, immutable", transcript.stream()
                .filter(e -> e.label().equals("anon GET hashed asset")).findFirst().orElseThrow().resp().header("cache-control"));
        assertEquals("no-cache, must-revalidate", transcript.stream().filter(e -> e.label().equals("anon GET /")).findFirst()
                .orElseThrow().resp().header("cache-control"));
    }

    /** Tomcat itself refuses a malformed request target (400) before any servlet filter runs; that answer cannot carry headers a filter writes. */
    private static boolean answeredByTomcatBeforeTheFilterChain(Entry entry) {
        return entry.label().startsWith("odd target") && entry.resp().status() == 400;
    }

    @Test
    void headAnswersLikeGetOnHealthz() {
        assertEquals(200, status("anon HEAD /healthz"));
    }

    @Test
    void noResponseCarriesAFrameworkDefaultTheServiceNeverSent() {
        for (Entry entry : transcript) {
            Map<String, List<String>> headers = entry.resp().headers();
            for (String forbidden : List.of("www-authenticate", "x-xss-protection", "pragma", "expires",
                    "strict-transport-security", "referrer-policy", "permissions-policy", "cross-origin-opener-policy",
                    "access-control-allow-origin")) {
                assertFalse(headers.containsKey(forbidden), entry.label() + " carries " + forbidden);
            }
            assertFalse(headers.containsKey("location"), entry.label() + " redirects");
            assertFalse(entry.resp().status() >= 300 && entry.resp().status() < 400 && entry.resp().status() != 304,
                    entry.label() + " answers a redirect");
            for (String cookie : headers.getOrDefault("set-cookie", List.of())) {
                assertFalse(cookie.toUpperCase(Locale.ROOT).contains("JSESSIONID"), entry.label() + " opens a servlet session");
                assertTrue(cookie.startsWith("ui2_session="), entry.label() + " sets a cookie other than ui2_session");
            }
        }
    }

    /**
     * Added with the Spring Security dependency (Step 1): the golden transcript above is unchanged, and this proves the
     * transcript was taken with {@code FilterChainProxy} really in the servlet chain, behind the machine-port filter,
     * with no generated default user.
     */
    @Test
    void springSecurityFilterChainIsActiveBehindTheMachinePortFilter() throws Exception {
        assertTrue(Class.forName("org.springframework.security.web.FilterChainProxy")
                .isInstance(context.getBean("springSecurityFilterChain")));
        var registration = context.getBean("securityFilterChainRegistration", org.springframework.boot.web.servlet.RegistrationBean.class);
        // Step 5: the order is now the filter's own (Ordered); the @Order on the bean method it used to be read from was never
        // applied to a filter bean, so the filter had run behind the security chain.
        var machinePortFilter = context.getBean("machinePortFilter");
        assertTrue(machinePortFilter instanceof org.springframework.core.Ordered);
        assertTrue(((org.springframework.core.Ordered) machinePortFilter).getOrder()
                < ((org.springframework.core.Ordered) registration).getOrder(),
                "the machine-port filter must run before the Spring Security filter");
        assertEquals(0, context.getBeanNamesForType(Class.forName("org.springframework.security.core.userdetails.UserDetailsService")).length);
        assertEquals(1, context.getBeansOfType(Class.forName("org.springframework.security.web.SecurityFilterChain")).size());
    }

    @Test
    void everyAsyncRouteAndTheTranscriptEndPromptlyOnAKeepAliveConnectionAndLeaveItUsable() {
        var probed = new java.util.HashSet<>(keepAliveStatus.keySet());
        probed.addAll(keepAliveFailures.keySet());
        assertEquals(30, probed.size(), "10 routes x 3 identities");
        assertEquals(Map.of(), keepAliveFailures);
        assertEquals(401, keepAliveStatus.get("anonymous GET /jobs/{id}/transcript"));
        assertEquals(403, keepAliveStatus.get("viewer GET /jobs/{id}/transcript"));
        assertEquals(403, keepAliveStatus.get("replay viewer GET /jobs/{id}/transcript"));
        // the same refusals the Connection: close golden recorded
        assertEquals(status("async anon GET changes.csv"), keepAliveStatus.get("anonymous GET /api/v2/policy/changes.csv"));
        assertEquals(status("async viewer GET changes.csv"), keepAliveStatus.get("viewer GET /api/v2/policy/changes.csv"));
        assertEquals(status("async replay viewer GET changes.csv"),
                keepAliveStatus.get("replay viewer GET /api/v2/policy/changes.csv"));
        assertEquals(status("async viewer POST backup download"),
                keepAliveStatus.get("viewer POST /backups/" + ARTEFACT + "/download"));
    }

    private static int status(String label) {
        return entry(label).resp().status();
    }

    private static JsonNode body(String label) {
        return entry(label).resp().json();
    }

    private static Entry entry(String label) {
        return transcript.stream().filter(e -> e.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no probe named: " + label));
    }
}
