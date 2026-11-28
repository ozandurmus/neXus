package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.AiviewSweepEstate;
import com.securityexpert.nexus.ui2.integration.support.AiviewSweepEstate.Estate;
import com.securityexpert.nexus.ui2.integration.support.AiviewSweepEstate.ValueClass;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

/**
 * Sweep of the remaining unswept GET routes for backlog {@code aiview_masking_leak_audit} (AGENTS.md
 * "Mandatory AIView inspection &amp; post-parsing masking law" and PO decision record 2026-11-23 §3(b)):
 * <ol>
 *   <li>Failover preflight &amp; schedules GET routes ({@code FailoverPreflightController},
 *       {@code FailoverScheduleController}), including masked pseudonym and raw cluster/hostname oracle probes:
 *       {@code GET /api/v2/failover/checks}, {@code GET /api/v2/failover/{clusterRef}/preflight},
 *       {@code GET /api/v2/failover/{clusterRef}/schedules}, {@code GET /api/v2/failover/schedules/{scheduleId}}.</li>
 *   <li>Backup download refusal boundary ({@code BackupController}):
 *       {@code GET /backups/{artefactId}/download}.</li>
 *   <li>Admin-only GET refusal boundaries ({@code CredentialAdministrationController},
 *       {@code LocalIdentityAdministrationController}, {@code SessionAdminController}, {@code RbacRoleController}):
 *       {@code GET /credentials}, {@code GET /local-identities}, {@code GET /sessions}, {@code GET /roles}.</li>
 *   <li>Unswept configuration GET routes ({@code LocalAuthModeController}, {@code LocalPasswordPolicyController},
 *       {@code SnmpPollingSettingsController}, {@code SystemLogSettingsController}):
 *       {@code GET /api/v2/config/local-auth-mode}, {@code GET /api/v2/config/local-password-policy},
 *       {@code GET /api/v2/config/snmp-polling}, {@code GET /api/v2/config/system-logs}, tested both in default mode
 *       (refused 403 under {@code aiview}) and with {@code UI2_AIVIEW_READONLY_ADMIN} enabled (200 masked projection
 *       with {@code can_write=false} and masked {@code updated_by}).</li>
 * </ol>
 *
 * <p>All planted values come from {@link AiviewSweepEstate} (synthetic per run). Test output prints only route,
 * HTTP status, probe label, and {@link ValueClass} counts -- never planted raw values.</p>
 */
class AiviewPreflightSchedulesAndAdminRefusalSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Surviving raw values pinned by exact route/probe key and value class: empty (zero leaks permitted). */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.of();

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static Path groupRefKeyFile;
    private static Path credentialKeyFile;
    private static Path failoverKeyFile;
    private static Path paRoot;
    private static Path sanitizerDir;
    private static Estate estate;
    private static String scheduleId;
    private static String artefactId;
    private static String maskedClusterPseudonym;
    private static String sessionCookie;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_preflight_admin");
        work = Files.createTempDirectory("ui2-aiview-preflight-admin-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));
        groupRefKeyFile = keyFile("group_ref.key");
        credentialKeyFile = keyFile("credential.key");
        failoverKeyFile = work.resolve("failover.key");
        paRoot = Files.createDirectory(work.resolve("pa"));
        sanitizerDir = Files.createDirectory(work.resolve("sanitizer"));

        estate = Estate.generate();
        AiviewSweepEstate.seedInto(fixture, store, estate);
        artefactId = AiviewSweepEstate.ids.get("artefactId");
        seedPlaneRows(estate);

        context = bootApplication(false);
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
        TopologyNamePseudonymizer pseudonymizer = context.getBean(TopologyNamePseudonymizer.class);
        maskedClusterPseudonym = pseudonymizer.maskClusterName(estate.cluster());
    }

    private static ConfigurableApplicationContext bootApplication(boolean aiviewReadonlyAdmin) {
        return new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> {
                    useTestArtefactStore(bf, store);
                    if (aiviewReadonlyAdmin) {
                        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) bf;
                        if (registry.containsBeanDefinition("actionRegistry")) {
                            registry.removeBeanDefinition("actionRegistry");
                        }
                        bf.registerSingleton("actionRegistry", new ActionRegistry(true));
                    }
                }))
                .run(
                        "--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--ui2.db.url=" + fixture.databaseJdbcUrl(),
                        "--ui2.db.user-file=" + fixture.appUserSecretFile(),
                        "--ui2.db.password-file=" + fixture.appPasswordSecretFile(),
                        "--ui2.db.migrate-user-file=" + fixture.migrateUserSecretFile(),
                        "--ui2.db.migrate-password-file=" + fixture.migratePasswordSecretFile(),
                        "--ui2.role-binding.group-reference-key-file=" + groupRefKeyFile,
                        "--ui2.credential-store.key-file=" + credentialKeyFile,
                        "--ui2.failover.master-key-file=" + failoverKeyFile,
                        "--ui2.packet-analysis.root=" + paRoot,
                        "--ui2.sanitizer.work-dir=" + sanitizerDir,
                        "--ui2.scheduling.enabled=false");
    }

    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory, ArtefactStore artefactStore) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess",
                new DeviceCompositionConfiguration.ArtefactStoreAccess(artefactStore));
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

    /**
     * Seeds rows specific to this sweep's 13 routes so refusals and masked 200 responses are proven over populated
     * tables containing planted raw identities:
     * <ul>
     *   <li>One {@code failover_schedules} row and one {@code failover_schedule_ledger} row carrying the raw cluster
     *       name, planted principal, and raw hostname in the baseline/details.</li>
     *   <li>One custom {@code rbac_roles} row naming the planted principal and hostname in its description.</li>
     *   <li>Rows in {@code local_auth_mode_settings}, {@code local_password_policy_settings},
     *       {@code snmp_polling_settings}, and {@code system_log_settings} with {@code updated_by = estate.principal()}.</li>
     * </ul>
     */
    private static void seedPlaneRows(Estate e) throws Exception {
        scheduleId = "sched-" + UUID.randomUUID();
        String grantId = "grant-" + UUID.randomUUID();
        String baselineJson = JSON.writeValueAsString(Map.of(
                "clusterId", e.cluster(),
                "vendor", "CHECK_POINT",
                "haMode", "ACTIVE_STANDBY",
                "activeMemberId", e.cpMemberA().id(),
                "standbyMemberId", e.cpMemberB().id(),
                "softwareVersion", "R81.20",
                "installedPolicyHash", "0".repeat(64),
                "flapCountLast24Hours", 0,
                "baselineDigest", "1".repeat(64),
                "capturedAt", "2026-11-26T10:00:00Z"));
        try (Connection c = fixture.migrateConnection()) {
            c.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(c, Ui2Rows.ACTOR, Ui2Rows.ACTION);
                exec(c, "insert into failover_schedules("
                        + "schedule_id, cluster_ref, masked_cluster_name, vendor, command_family_id, action_kind, "
                        + "signed_mutation_target, window_start, window_end, max_start_delay_minutes, execution_deadline, "
                        + "requester_id, approver_id, grant_id, baseline_digest, baseline_json, envelope_signature, status, "
                        + "client_nonce, scheduled_at, baseline_format_version, key_id, algorithm_version, version) "
                        + "values (?, ?, 'CLS-SYNTH-01', 'CHECK_POINT', 'CP_CLUSTERXL_MUTATION_GATE', 'FAILOVER', "
                        + "?, now() + interval '1 hour', now() + interval '2 hours', 15, now() + interval '75 minutes', "
                        + "?, ?, ?, ?, ?::jsonb, ?, 'SCHEDULED', ?, now(), 2, 'key-v1', 'HMAC-SHA256-V1', 1)",
                        scheduleId, e.cluster(), e.cpMemberA().id(), e.principal(), e.principal() + ".approver",
                        grantId, "1".repeat(64), baselineJson, "2".repeat(64), "nonce-" + UUID.randomUUID());
                long tipIndex = -1;
                String tipHash = "0".repeat(64);
                try (var st = c.createStatement();
                        var rs = st.executeQuery("select entry_index, entry_hash from failover_schedule_ledger "
                                + "order by entry_index desc limit 1")) {
                    if (rs.next()) {
                        tipIndex = rs.getLong(1);
                        tipHash = rs.getString(2);
                    }
                }
                exec(c, "insert into failover_schedule_ledger("
                        + "entry_index, prev_hash, entry_hash, schedule_id, from_status, to_status, attempt_id, actor_id, details) "
                        + "values (?, ?, ?, ?, null, 'SCHEDULED', ?, ?, ?)",
                        tipIndex + 1, tipHash, "3".repeat(64), scheduleId, "attempt-" + UUID.randomUUID(), e.principal(),
                        "scheduled for " + e.cpMemberA().hostname() + " in " + e.cluster());

                exec(c, "insert into rbac_roles(id, name, token_string, description, is_system) "
                        + "values (?, 'synthetic-audit-role', 'role:synthetic_audit', ?, false)",
                        UUID.randomUUID(), "managed by " + e.principal() + " for " + e.cpMemberA().hostname());

                exec(c, "insert into local_auth_mode_settings(settings_id, mode, updated_at, updated_by) "
                        + "values ('default', 'ALL_LOCAL', now(), ?) "
                        + "on conflict (settings_id) do update set mode = excluded.mode, updated_at = now(), updated_by = excluded.updated_by",
                        e.principal());

                exec(c, "insert into local_password_policy_settings(settings_id, enabled, min_length, require_upper, "
                        + "require_lower, require_digit, require_symbol, history_count, max_age_days, updated_at, updated_by) "
                        + "values ('default', true, 12, true, true, true, false, 3, 90, now(), ?) "
                        + "on conflict (settings_id) do update set enabled = excluded.enabled, min_length = excluded.min_length, "
                        + "updated_at = now(), updated_by = excluded.updated_by",
                        e.principal());

                exec(c, "insert into snmp_polling_settings(settings_id, enabled, interval_s, default_credential_reference_id, "
                        + "scope_mode, raw_retention_days, hourly_retention_days, updated_at, updated_by) "
                        + "values ('default', true, 60, null, 'VENDORS', 7, 90, now(), ?) "
                        + "on conflict (settings_id) do update set enabled = excluded.enabled, interval_s = excluded.interval_s, "
                        + "updated_at = now(), updated_by = excluded.updated_by",
                        e.principal());

                exec(c, "insert into system_log_settings(settings_id, raw_retention_days, device_cap_bytes, hcp_retention_days, "
                        + "spike_data_retention_days, spike_data_cap_bytes, ledger_retention_days, "
                        + "include_management_servers, spike_data_enabled, updated_at, updated_by) "
                        + "values ('default', 7, 1073741824, 30, 14, 134217728, 90, false, false, now(), ?) "
                        + "on conflict (settings_id) do update set raw_retention_days = excluded.raw_retention_days, "
                        + "updated_at = now(), updated_by = excluded.updated_by",
                        e.principal());

                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            st.executeUpdate();
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }

    record Resp(int status, String body) {
    }

    private static Resp get(int targetPort, String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*");
        if (cookie != null) {
            request.header("Cookie", "ui2_session=" + cookie);
        }
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    /** Single cached aiview session cookie reused across all test methods (single-session policy). */
    private static synchronized String login() throws Exception {
        if (sessionCookie != null) return sessionCookie;
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "aiview login");
        for (String cookie : response.headers().allValues("set-cookie")) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                sessionCookie = end < 0 ? value : value.substring(0, end);
                return sessionCookie;
            }
        }
        throw new IllegalStateException("no session cookie on the aiview login");
    }

    private record Probe(String routeKey, String label, String path, int expectedStatus, String expectedError) {
    }

    @Test
    void defaultModeRefusesAllThirteenRoutesAndOracleProbesWithZeroRawLeakage() throws Exception {
        String cookie = login();
        List<Probe> probes = List.of(
                // 1. Failover preflight & schedules GET routes (unmapped -> fail-closed 403 ACTION_MAPPING_REQUIRED)
                new Probe("GET /api/v2/failover/checks", "default",
                        "/api/v2/failover/checks", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/{clusterRef}/preflight", "masked-cluster-pseudonym",
                        "/api/v2/failover/" + encode(maskedClusterPseudonym) + "/preflight", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/{clusterRef}/preflight", "raw-cluster-oracle-probe",
                        "/api/v2/failover/" + encode(estate.cluster()) + "/preflight", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/{clusterRef}/preflight", "raw-hostname-oracle-probe",
                        "/api/v2/failover/" + encode(estate.cpMemberA().hostname()) + "/preflight", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/{clusterRef}/schedules", "masked-cluster-pseudonym",
                        "/api/v2/failover/" + encode(maskedClusterPseudonym) + "/schedules", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/{clusterRef}/schedules", "raw-cluster-oracle-probe",
                        "/api/v2/failover/" + encode(estate.cluster()) + "/schedules", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/{clusterRef}/schedules", "raw-hostname-oracle-probe",
                        "/api/v2/failover/" + encode(estate.cpMemberA().hostname()) + "/schedules", 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/schedules/{scheduleId}", "seeded-schedule-id",
                        "/api/v2/failover/schedules/" + encode(scheduleId), 403, "ACTION_MAPPING_REQUIRED"),
                new Probe("GET /api/v2/failover/schedules/{scheduleId}", "raw-hostname-oracle-probe",
                        "/api/v2/failover/schedules/" + encode(estate.cpMemberA().hostname()), 403, "ACTION_MAPPING_REQUIRED"),

                // 2. Backup download refusal boundary (role:backup_admin only)
                new Probe("GET /backups/{artefactId}/download", "seeded-artefact-bare",
                        "/backups/" + encode(artefactId) + "/download", 403, "ACTION_REFUSED"),
                new Probe("GET /backups/{artefactId}/download", "seeded-artefact-with-ticket",
                        "/backups/" + encode(artefactId) + "/download?ticket=synthetic-invalid-ticket", 403, "ACTION_REFUSED"),
                new Probe("GET /backups/{artefactId}/download", "raw-hostname-oracle-probe",
                        "/backups/" + encode(estate.cpMemberA().hostname()) + "/download?ticket=synthetic-invalid-ticket", 403, "ACTION_REFUSED"),

                // 3. Admin-only GET refusal boundaries (role:security_admin only)
                new Probe("GET /credentials", "default", "/credentials", 403, "ACTION_REFUSED"),
                new Probe("GET /local-identities", "default", "/local-identities", 403, "ACTION_REFUSED"),
                new Probe("GET /sessions", "default", "/sessions", 403, "ACTION_REFUSED"),
                new Probe("GET /roles", "default", "/roles", 403, "ACTION_REFUSED"),

                // 4. Unswept configuration GET routes (default mode: UI2_AIVIEW_READONLY_ADMIN=false -> 403)
                new Probe("GET /api/v2/config/local-auth-mode", "default-flag-off",
                        "/api/v2/config/local-auth-mode", 403, "ACTION_REFUSED"),
                new Probe("GET /api/v2/config/local-password-policy", "default-flag-off",
                        "/api/v2/config/local-password-policy", 403, "ACTION_REFUSED"),
                new Probe("GET /api/v2/config/snmp-polling", "default-flag-off",
                        "/api/v2/config/snmp-polling", 403, "ACTION_REFUSED"),
                new Probe("GET /api/v2/config/system-logs", "default-flag-off",
                        "/api/v2/config/system-logs", 403, "ACTION_REFUSED"));

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, Resp> preflightResponses = new LinkedHashMap<>();
        Map<String, Resp> schedulesResponses = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        int totalLeaks = 0;

        for (Probe p : probes) {
            Resp resp = get(port, p.path(), cookie);
            assertEquals(p.expectedStatus(), resp.status(),
                    p.routeKey() + " [" + p.label() + "] expected HTTP " + p.expectedStatus());
            JsonNode json = JSON.readTree(resp.body());
            assertEquals(p.expectedError(), json.path("error").asText(),
                    p.routeKey() + " [" + p.label() + "] expected error " + p.expectedError());

            if (p.routeKey().equals("GET /api/v2/failover/{clusterRef}/preflight")) {
                preflightResponses.put(p.label(), resp);
            } else if (p.routeKey().equals("GET /api/v2/failover/{clusterRef}/schedules")) {
                schedulesResponses.put(p.label(), resp);
            }

            Map<ValueClass, Integer> found = AiviewSweepEstate.count(resp.body(), estate.raw());
            if (!found.isEmpty()) {
                leaks.put(p.routeKey() + " [" + p.label() + "]", found);
                totalLeaks += found.values().stream().mapToInt(Integer::intValue).sum();
            }
            report.add(String.format(Locale.ROOT, "AIVIEW_SWEEP_R11 %3d %-44s [%-26s] raw=%s",
                    resp.status(), p.routeKey(), p.label(), found.isEmpty() ? "0" : found.toString()));
        }

        // Verify raw-hostname oracle probes are indistinguishable from masked-pseudonym calls (no existence oracle).
        assertEquals(preflightResponses.get("masked-cluster-pseudonym").body(),
                preflightResponses.get("raw-cluster-oracle-probe").body(),
                "preflight raw-cluster oracle probe must be indistinguishable from masked-cluster call");
        assertEquals(preflightResponses.get("masked-cluster-pseudonym").body(),
                preflightResponses.get("raw-hostname-oracle-probe").body(),
                "preflight raw-hostname oracle probe must be indistinguishable from masked-cluster call");
        assertEquals(schedulesResponses.get("masked-cluster-pseudonym").body(),
                schedulesResponses.get("raw-cluster-oracle-probe").body(),
                "schedules raw-cluster oracle probe must be indistinguishable from masked-cluster call");
        assertEquals(schedulesResponses.get("masked-cluster-pseudonym").body(),
                schedulesResponses.get("raw-hostname-oracle-probe").body(),
                "schedules raw-hostname oracle probe must be indistinguishable from masked-cluster call");

        report.forEach(System.out::println);
        System.out.println("AIVIEW_SWEEP_R11_DEFAULT_SUMMARY probes=" + probes.size()
                + " planted_values=" + estate.raw().size() + " surviving_raw_occurrences=" + totalLeaks);

        assertEquals(KNOWN_LEAKS, leaks,
                "planted raw values surviving in aiview refusal responses (route + value class); KNOWN_LEAKS must stay empty");
    }

    @Test
    void readonlyAdminFlagPermitsFourConfigReadsWithMaskedProjectionAndZeroRawLeakage() throws Exception {
        // Reuse the single cached aiview session cookie (persisted in PostgreSQL sessions table).
        String cookie = login();
        try (ConfigurableApplicationContext readonlyCtx = bootApplication(true)) {
            int readonlyPort = Integer.parseInt(readonlyCtx.getEnvironment().getRequiredProperty("local.server.port"));
            List<String> configRoutes = List.of(
                    "/api/v2/config/local-auth-mode",
                    "/api/v2/config/local-password-policy",
                    "/api/v2/config/snmp-polling",
                    "/api/v2/config/system-logs");

            Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
            List<String> report = new ArrayList<>();
            int totalLeaks = 0;

            for (String route : configRoutes) {
                Resp resp = get(readonlyPort, route, cookie);
                assertEquals(200, resp.status(), "GET " + route + " with UI2_AIVIEW_READONLY_ADMIN=true");
                JsonNode json = JSON.readTree(resp.body());
                assertFalse(json.path("can_write").asBoolean(true),
                        "GET " + route + " must set can_write=false for aiview");
                assertTrue(json.path("updated_by").asText("").startsWith("USR-"),
                        "GET " + route + " must mask updated_by principal to USR-*");

                Map<ValueClass, Integer> found = AiviewSweepEstate.count(resp.body(), estate.raw());
                if (!found.isEmpty()) {
                    leaks.put("GET " + route, found);
                    totalLeaks += found.values().stream().mapToInt(Integer::intValue).sum();
                }
                report.add(String.format(Locale.ROOT, "AIVIEW_SWEEP_R11_ROADMIN %3d %-44s can_write=%s raw=%s",
                        resp.status(), "GET " + route, json.path("can_write").asBoolean(),
                        found.isEmpty() ? "0" : found.toString()));
            }

            // Admin-only routes and backup download must remain refused even with UI2_AIVIEW_READONLY_ADMIN=true.
            for (String stillRefused : List.of(
                    "/credentials", "/local-identities", "/sessions", "/roles",
                    "/backups/" + encode(artefactId) + "/download?ticket=synthetic-invalid-ticket")) {
                Resp resp = get(readonlyPort, stillRefused, cookie);
                assertEquals(403, resp.status(), "GET " + stillRefused + " must stay refused 403 with readonly-admin flag");
                Map<ValueClass, Integer> found = AiviewSweepEstate.count(resp.body(), estate.raw());
                if (!found.isEmpty()) {
                    leaks.put("GET " + stillRefused + " [roadmin]", found);
                    totalLeaks += found.values().stream().mapToInt(Integer::intValue).sum();
                }
            }

            report.forEach(System.out::println);
            System.out.println("AIVIEW_SWEEP_R11_ROADMIN_SUMMARY routes=" + configRoutes.size()
                    + " planted_values=" + estate.raw().size() + " surviving_raw_occurrences=" + totalLeaks);

            assertEquals(KNOWN_LEAKS, leaks,
                    "planted raw values surviving in aiview readonly-admin config responses; KNOWN_LEAKS must stay empty");
        }
    }
}
