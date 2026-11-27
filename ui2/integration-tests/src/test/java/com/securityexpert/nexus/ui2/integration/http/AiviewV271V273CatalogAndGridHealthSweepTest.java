package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Real-PostgreSQL aiview masking sweep for V271 (Management Center {@code grid_member.health_*}) and V273
 * ({@code log_template_catalog} / {@code log_template}) under backlog {@code aiview_masking_leak_audit}.
 *
 * <p>Verifies under {@code aiview} ({@code role:replay_viewer}):
 * <ul>
 *   <li>{@code GET /api/v2/config/log-detection/catalogs} and
 *       {@code GET /api/v2/config/log-detection/catalogs/{version}} (with and without {@code ?from=}) are refused with
 *       {@code 403} per {@link ActionRegistry#LOG_DETECTION_SETTINGS_READ} ({@code security_admin} only) and return
 *       zero planted {@code created_by}/{@code frozen_by} values or other identities.</li>
 *   <li>{@code GET /devices/{id}/inventory} on a seeded Management Center exposes the V271 {@code health_state},
 *       {@code health_errors}, {@code health_warnings}, {@code health_checked_at}, and {@code health_messages} fields
 *       with {@code 200} while masking every planted member hostname, IP address, and serial so zero raw values
 *       survive.</li>
 * </ul>
 *
 * <p>Output reports only route template, HTTP status, value class, and integer count -- never any planted raw value.</p>
 */
class AiviewV271V273CatalogAndGridHealthSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Planted raw value categories; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, ADDRESS, SERIAL, PRINCIPAL
    }

    /** Surviving raw values pinned by route template and value class: must remain empty. */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.of();

    /** Routes that must refuse aiview (403) without leaking any planted raw value. */
    static final Set<String> MUST_BE_REFUSED = Set.of(
            "GET /api/v2/config/log-detection",
            "GET /api/v2/config/log-detection/catalogs",
            "GET /api/v2/config/log-detection/catalogs/*");

    /** Markers that must be observed on populated 200 views so zero leak counts are proven over seeded V271 data. */
    static final Map<String, Set<String>> COVERAGE = Map.of(
            "GET /devices/*/inventory", Set.of(
                    "health-state-warning",
                    "health-state-error",
                    "health-checked-at",
                    "health-messages-present",
                    "managed-device-pseudonym"));

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static Estate estate;

    private static final Map<String, ValueClass> rawValues = new LinkedHashMap<>();

    // ------------------------------------------------------------------ boot

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_v271_v273");
        work = Files.createTempDirectory("ui2-aiview-v271-v273-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        var store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));
        estate = Estate.generate();
        seed(estate);
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> useTestArtefactStore(bf, store)))
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--ui2.db.url=" + fixture.databaseJdbcUrl(),
                        "--ui2.db.user-file=" + fixture.appUserSecretFile(),
                        "--ui2.db.password-file=" + fixture.appPasswordSecretFile(),
                        "--ui2.db.migrate-user-file=" + fixture.migrateUserSecretFile(),
                        "--ui2.db.migrate-password-file=" + fixture.migratePasswordSecretFile(),
                        "--ui2.role-binding.group-reference-key-file=" + keyFile("group_ref.key"),
                        "--ui2.credential-store.key-file=" + keyFile("credential.key"),
                        "--ui2.failover.master-key-file=" + work.resolve("failover.key"),
                        "--ui2.packet-analysis.root=" + Files.createDirectory(work.resolve("pa")),
                        "--ui2.sanitizer.work-dir=" + Files.createDirectory(work.resolve("sanitizer")),
                        "--ui2.scheduling.enabled=false");
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory,
            com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore store) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess", new DeviceCompositionConfiguration.ArtefactStoreAccess(store));
        registry.removeBeanDefinition("machineConnector");
    }

    @AfterAll
    static void shutDown() throws IOException {
        if (context != null) context.close();
        if (fixture != null) fixture.close();
        if (work != null) {
            try (var paths = Files.walk(work)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static Path keyFile(String name) throws IOException {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        Path file = work.resolve(name);
        Files.writeString(file, Base64.getEncoder().encodeToString(key));
        return file;
    }

    // ------------------------------------------------------------------ synthetic estate

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** A token starting with a letter so it can never equal a digits-only pseudonym suffix. */
    private static String tag() {
        return "x" + hex(3);
    }

    record Device(String id, String vendor, String role, String hostname, String serial, String address) {
    }

    record Estate(
            Device mc,
            Device managedA,
            Device managedB,
            String catalogCreator,
            String catalogFreezer,
            String settingsUpdater,
            String checkedAtA,
            String checkedAtB) {

        static Estate generate() {
            int base = 20 + RANDOM.nextInt(140);
            Device mc = new Device(Ui2Rows.opaqueId("dev"), "bluecoat", "management_server",
                    "mc" + tag() + "-ctrl01", "MC" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + base);
            Device managedA = new Device(Ui2Rows.opaqueId("dev"), "bluecoat", "gateway",
                    "sg" + tag() + "-edge01", "SG" + hex(5).toUpperCase(Locale.ROOT), "198.51.100." + base);
            Device managedB = new Device(Ui2Rows.opaqueId("dev"), "bluecoat", "gateway",
                    "sg" + tag() + "-edge02", "SG" + hex(5).toUpperCase(Locale.ROOT), "198.51.100." + (base + 1));
            return new Estate(
                    mc,
                    managedA,
                    managedB,
                    "catadm" + tag() + ".build",
                    "catadm" + tag() + ".freeze",
                    "logadm" + tag() + ".cfg",
                    "2026-11-26T08:30:00Z",
                    "2026-11-26T08:31:00Z");
        }

        List<Device> devices() {
            return List.of(mc, managedA, managedB);
        }
    }

    // ------------------------------------------------------------------ seeding

    private static void seed(Estate e) throws Exception {
        rawValues.clear();
        for (Device d : e.devices()) {
            rawValues.put(d.hostname(), ValueClass.HOSTNAME);
            rawValues.put(d.serial(), ValueClass.SERIAL);
            rawValues.put(d.address(), ValueClass.ADDRESS);
        }
        rawValues.put(e.catalogCreator(), ValueClass.PRINCIPAL);
        rawValues.put(e.catalogFreezer(), ValueClass.PRINCIPAL);
        rawValues.put(e.settingsUpdater(), ValueClass.PRINCIPAL);

        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, Ui2Rows.ACTOR, () -> {
                for (Device d : e.devices()) {
                    exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                            + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                            + "observed_hostname, observed_model, observed_software_version, recorded_identity_primary, "
                            + "recorded_identity_recorded_at, backup_target, discovery_match_key) "
                            + "values (?, ?, 'manual', true, 'ENROLLED', false, ?, ?, 'NONE', 'NONE', ?, "
                            + "'synthetic-model', '7.3.12', ?, now(), false, ?)",
                            d.id(), d.vendor(), credential, d.role(), d.hostname(), d.serial(),
                            d.vendor() + "|" + d.serial());
                    exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'https_api', ?)",
                            Ui2Rows.opaqueId("ep"), d.id(), d.address());
                    exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                            + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                }

                // V271: Management Center inventory run with two grid_member rows carrying health_* columns and planted identities.
                String jobId = Ui2Rows.opaqueId("job");
                exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, submitted_by_actor_fingerprint, "
                        + "state, action_class, finished_at) values (?, 'inventory_collect', 'device', ?, ?, 'COMPLETED', 'read', now())",
                        jobId, e.mc().id(), Ui2Rows.ACTOR);
                String runId = Ui2Rows.opaqueId("inv");
                exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count, virtual_systems) "
                        + "values (?, ?, ?, now(), 0, ?)",
                        runId, e.mc().id(), jobId, e.managedA().hostname() + ", " + e.managedB().hostname());

                String healthMsgA = "Disk usage elevated on " + e.managedA().hostname()
                        + " (" + e.managedA().address() + ", serial " + e.managedA().serial() + ")\n"
                        + "NTP drift warning";
                String healthMsgB = "Link fault on " + e.managedB().hostname()
                        + " at " + e.managedB().address() + " (serial " + e.managedB().serial() + ")";

                exec(c, "insert into grid_member(member_id, run_id, host_name, vip_address, platform, hardware_type, hypervisor, "
                        + "grid_master, master_candidate, ha_enabled, node_status, services, "
                        + "health_state, health_errors, health_warnings, health_checked_at, health_messages) "
                        + "values (?, ?, ?, ?, 'SGOS', 'ProxySG', 'hardware', false, false, false, 'WORKING', 'PROXY=WORKING', "
                        + "'WARNING', 1, 2, ?, ?)",
                        Ui2Rows.opaqueId("gm"), runId, e.managedA().hostname(), e.managedA().address(),
                        e.checkedAtA(), healthMsgA);
                exec(c, "insert into grid_member(member_id, run_id, host_name, vip_address, platform, hardware_type, hypervisor, "
                        + "grid_master, master_candidate, ha_enabled, node_status, services, "
                        + "health_state, health_errors, health_warnings, health_checked_at, health_messages) "
                        + "values (?, ?, ?, ?, 'SGOS', 'ProxySG', 'hardware', false, false, false, 'WORKING', 'PROXY=WARNING', "
                        + "'ERROR', 3, 0, ?, ?)",
                        Ui2Rows.opaqueId("gm"), runId, e.managedB().hostname(), e.managedB().address(),
                        e.checkedAtB(), healthMsgB);

                // V272/V273: settings updated_by + log_template_catalog (cat-1 FROZEN, cat-2 DRAFT) + log_template rows.
                exec(c, "update log_detection_settings set enabled = false, p2_enabled = false, updated_by = ?, updated_at = now() "
                        + "where settings_id = 'default'", e.settingsUpdater());

                String manifest1 = "{\"normaliser_version\":\"n1\",\"masking_rule_set\":\"m1\",\"window_days\":14}";
                String diff1 = "{\"added\":1,\"removed\":0}";
                exec(c, "insert into log_template_catalog(catalog_version, state, previous_version, manifest, digest, "
                        + "template_count, diff, created_by) values ('cat-1', 'DRAFT', null, ?::jsonb, ?, 1, ?::jsonb, ?)",
                        manifest1, "a".repeat(64), diff1, e.catalogCreator());
                exec(c, "insert into log_template(catalog_version, template_id, source_key, template_text, match_form, "
                        + "token_count, marker_id, severity_weight, template_class, provenance, line_count, "
                        + "prevalence_devices, cohort_devices, fleet_first_seen) "
                        + "values ('cat-1', ?, 'syslog_kernel', 'kernel link state changed on <W>', "
                        + "'kernel link state changed on k-0123456789ab', 6, 'kernel.link_down', 0.50, 'MARKER', "
                        + "'MEASURED', 42, 1, 2, current_date - 5)",
                        "t0" + hex(15));
                exec(c, "update log_template_catalog set state = 'FROZEN', frozen_at = now(), frozen_by = ? "
                        + "where catalog_version = 'cat-1'", e.catalogFreezer());

                String manifest2 = "{\"normaliser_version\":\"n1\",\"masking_rule_set\":\"m1\",\"window_days\":14}";
                String diff2 = "{\"added\":1,\"removed\":0}";
                exec(c, "insert into log_template_catalog(catalog_version, state, previous_version, manifest, digest, "
                        + "template_count, diff, created_by) values ('cat-2', 'DRAFT', 'cat-1', ?::jsonb, ?, 1, ?::jsonb, ?)",
                        manifest2, "b".repeat(64), diff2, e.catalogCreator());
                exec(c, "insert into log_template(catalog_version, template_id, source_key, template_text, match_form, "
                        + "token_count, marker_id, severity_weight, template_class, provenance, line_count, "
                        + "prevalence_devices, cohort_devices, fleet_first_seen) "
                        + "values ('cat-2', ?, 'syslog_kernel', 'kernel interface reset on <W>', "
                        + "'kernel interface reset on k-abcdef012345', 5, 'kernel.if_reset', 0.40, 'MARKER', "
                        + "'MEASURED', 17, 1, 2, current_date - 2)",
                        "t1" + hex(15));
            });
        }
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void audited(Connection c, String actor, Work work) throws Exception {
        c.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(c, actor, Ui2Rows.ACTION);
            work.run();
            c.commit();
        } catch (Exception failure) {
            c.rollback();
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            st.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ leak counting

    static List<Pattern> forms(String value, ValueClass cls) {
        List<Pattern> out = new ArrayList<>();
        if (cls == ValueClass.ADDRESS) {
            out.add(Pattern.compile("(?<![0-9.])" + Pattern.quote(value) + "(?![0-9])"));
            out.add(Pattern.compile("(?<![0-9-])" + Pattern.quote(value.replace('.', '-')) + "(?![0-9])"));
        } else {
            out.add(Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    static Map<ValueClass, Integer> count(String body, Map<String, ValueClass> raw) {
        Map<ValueClass, Integer> found = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            int n = 0;
            for (Pattern p : forms(entry.getKey(), entry.getValue())) {
                Matcher m = p.matcher(body);
                while (m.find()) n++;
            }
            if (n > 0) found.merge(entry.getValue(), n, Integer::sum);
        }
        return found;
    }

    // ------------------------------------------------------------------ HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String body) {
    }

    private static final Map<String, Integer> SENT = new TreeMap<>();

    private static Resp get(String target, String cookie) throws Exception {
        SENT.merge("GET", 1, Integer::sum);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String login() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "aiview login");
        for (String cookie : response.headers().allValues("set-cookie")) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("no session cookie on the aiview login");
    }

    record Call(String key, String path, String detail, int expectedStatus) {
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    void v273CatalogsAreRefusedAndV271GridHealthIsMaskedForAiview() throws Exception {
        SENT.clear();
        String cookie = login();
        Estate e = estate;

        // Confirm static authorization contract: catalog/settings reads are security_admin only, never aiview.
        assertFalse(AiviewReachableRoutes.aiviewMayCall(ActionRegistry.LOG_DETECTION_SETTINGS_READ),
                "LOG_DETECTION_SETTINGS_READ must not be reachable by aiview");
        Map<String, Boolean> reachable = new TreeMap<>();
        for (var ep : AiviewReachableRoutes.all()) {
            if (ep.method().equals("GET")) {
                boolean may = ep.actionId() != null && AiviewReachableRoutes.aiviewMayCall(ep.actionId());
                reachable.merge(ep.key(), may, (a, b) -> a || b);
            }
        }
        for (String refusedRoute : MUST_BE_REFUSED) {
            assertEquals(Boolean.FALSE, reachable.get(refusedRoute),
                    "static route audit must refuse aiview on " + refusedRoute);
        }
        assertEquals(Boolean.TRUE, reachable.get("GET /devices/*/inventory"),
                "static route audit says aiview can reach GET /devices/*/inventory");

        List<Call> calls = List.of(
                // V273 catalog and V272/V273 settings routes: must refuse aiview (403) with zero leaked values.
                new Call("GET /api/v2/config/log-detection", "/api/v2/config/log-detection", "settings-refused", 403),
                new Call("GET /api/v2/config/log-detection/catalogs", "/api/v2/config/log-detection/catalogs",
                        "catalog-list-refused", 403),
                new Call("GET /api/v2/config/log-detection/catalogs/*", "/api/v2/config/log-detection/catalogs/cat-1",
                        "catalog-one-refused", 403),
                new Call("GET /api/v2/config/log-detection/catalogs/*",
                        "/api/v2/config/log-detection/catalogs/cat-2?from=cat-1", "catalog-diff-refused", 403),
                // V271 grid_member health_* read path and device summaries: 200, strictly masked.
                new Call("GET /devices/*/inventory", "/devices/" + e.mc().id() + "/inventory", "mc-inventory", 200),
                new Call("GET /devices/*", "/devices/" + e.mc().id(), "mc-device", 200),
                new Call("GET /devices", "/devices", "devices-list", 200));

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int totalLeaks = 0;

        for (Call call : calls) {
            Resp resp = get(call.path(), cookie);
            assertEquals(call.expectedStatus(), resp.status(), call.key() + " (" + call.detail() + ") HTTP status");
            assertFalse(resp.body().isBlank(), "response body is non-blank: " + call.key() + " (" + call.detail() + ")");

            Map<ValueClass, Integer> found = count(resp.body(), rawValues);
            if (!found.isEmpty()) {
                leaks.computeIfAbsent(call.key(), k -> new TreeMap<>());
                found.forEach((cls, n) -> leaks.get(call.key()).merge(cls, n, Integer::sum));
            }
            int sum = found.values().stream().mapToInt(Integer::intValue).sum();
            totalLeaks += sum;
            report.add(String.format(Locale.ROOT, "AIVIEW_V271_V273 %3d %-44s %-22s raw=%s",
                    resp.status(), call.key(), call.detail(), found.isEmpty() ? "0" : found.toString()));

            if ("mc-inventory".equals(call.detail())) {
                verifyGridHealthBody(resp.body(), e, seen.computeIfAbsent(call.key(), k -> new TreeSet<>()));
            }
        }

        report.forEach(System.out::println);
        System.out.println("AIVIEW_V271_V273_SUMMARY calls=" + calls.size() + " planted_values=" + rawValues.size()
                + " surviving_raw_occurrences=" + totalLeaks + " sent=" + SENT);

        assertEquals(Map.of("GET", calls.size()), SENT, "only GET requests are sent after login");
        List<String> uncovered = new ArrayList<>();
        COVERAGE.forEach((route, expected) -> {
            if (!seen.getOrDefault(route, Set.of()).containsAll(expected)) {
                uncovered.add(route + " expected " + expected + " saw " + seen.get(route));
            }
        });
        assertEquals(List.of(), uncovered, "all V271 grid_member health fields must appear on GET /devices/*/inventory");
        assertEquals(KNOWN_LEAKS, leaks,
                "zero planted raw values may survive in any V271/V273 aiview response");
    }

    private static void verifyGridHealthBody(String body, Estate e, Set<String> markersSeen) throws Exception {
        JsonNode root = JSON.readTree(body);
        JsonNode members = root.path("grid_members");
        assertTrue(members.isArray() && members.size() == 2, "two seeded grid_member rows returned");
        for (JsonNode member : members) {
            String maskedName = member.path("virtual_system").asText();
            assertTrue(maskedName.startsWith("MD-"), "grid_member virtual_system is pseudonymised to MD-*");
            markersSeen.add("managed-device-pseudonym");

            String state = member.path("health_state").asText();
            if ("WARNING".equals(state)) {
                markersSeen.add("health-state-warning");
                assertEquals(1, member.path("health_errors").asInt(), "WARNING member health_errors");
                assertEquals(2, member.path("health_warnings").asInt(), "WARNING member health_warnings");
                if (e.checkedAtA().equals(member.path("health_checked_at").asText())) {
                    markersSeen.add("health-checked-at");
                }
            } else if ("ERROR".equals(state)) {
                markersSeen.add("health-state-error");
                assertEquals(3, member.path("health_errors").asInt(), "ERROR member health_errors");
                assertEquals(0, member.path("health_warnings").asInt(), "ERROR member health_warnings");
                assertEquals(e.checkedAtB(), member.path("health_checked_at").asText(), "ERROR member health_checked_at");
            } else {
                throw new AssertionError("unexpected health_state: " + state);
            }
            JsonNode messages = member.path("health_messages");
            assertTrue(messages.isArray() && !messages.isEmpty(), "health_messages present as non-empty array");
            markersSeen.add("health-messages-present");
        }
    }

    @Test
    void counterMatchesAddressesHostnamesSerialsAndPrincipalsWithoutPrefixFalsePositives() {
        Map<String, ValueClass> raw = Map.of(
                "198.51.100.42", ValueClass.ADDRESS,
                "sgx1a2b3c-edge01", ValueClass.HOSTNAME,
                "SGABCDEF0123", ValueClass.SERIAL,
                "catadmx1a2b3c.build", ValueClass.PRINCIPAL);
        assertEquals(Map.of(
                        ValueClass.ADDRESS, 2,
                        ValueClass.HOSTNAME, 1,
                        ValueClass.SERIAL, 1,
                        ValueClass.PRINCIPAL, 1),
                count("[\"198.51.100.42\",\"198-51-100-42\",\"198.51.100.420\",\"SGX1A2B3C-EDGE01\",\"sgabcdef0123\",\"catadmx1a2b3c.build\"]",
                        raw));
        assertEquals(Map.of(), count("{\"virtual_system\":\"MD-ALPHA-01\",\"address\":\"240.18.44.42\"}", raw));
    }
}
