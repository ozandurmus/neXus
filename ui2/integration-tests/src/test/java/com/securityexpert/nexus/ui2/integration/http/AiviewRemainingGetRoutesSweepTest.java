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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialView;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 12 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp;
 * post-parsing masking law", {@code docs/design/ZONE_MATRIX_CONTRACT.md} §3.4 &amp; §8.2 / {@code ZM-10}, and
 * {@code docs/design/PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS.md} §3(b)): exercises the 15 remaining GET
 * routes not covered by rounds 1–11 against a live Spring Boot + PostgreSQL 16 instance.
 *
 * <p>Every route is called with both an authorized administrator session ({@code nexusadmin}) and the {@code aiview}
 * ({@code role:replay_viewer}) session:
 * <ul>
 *   <li>For the 13 mapped administration and witness routes, the administrator session must receive {@code 200 OK}
 *       containing the planted synthetic data (proving the sweep is non-vacuous), and {@code aiview} must receive
 *       {@code 403} with zero planted raw values in the response body.</li>
 *   <li>For the 2 unmapped generic-failover routes ({@code GET /api/v2/failover/checks} and
 *       {@code GET /api/v2/failover/schedules/{scheduleId}}), both administrator and {@code aiview} must receive
 *       {@code 403 ACTION_MAPPING_REQUIRED} with zero planted raw values in the response body.</li>
 * </ul>
 *
 * <p>Assertion and diagnostic output report only route, HTTP status, value <em>class</em> and count — never a raw
 * planted value.</p>
 */
class AiviewRemainingGetRoutesSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Classification of planted synthetic raw values; assertion messages report only route + class + count. */
    enum ValueClass {
        HOSTNAME,
        ADDRESS,
        SERIAL,
        ZONE_NAME,
        CREDENTIAL_LABEL,
        USERNAME,
        DIRECTORY_OR_ROLE_NAME,
        ACTOR_OR_SCHEDULE_TOKEN
    }

    /** Routes intentionally unmapped in {@code SecurityWebMvcConfig}: refused with 403 ACTION_MAPPING_REQUIRED for all callers. */
    static final Set<String> UNMAPPED_ROUTES = Set.of(
            "GET /api/v2/failover/checks",
            "GET /api/v2/failover/schedules/*");

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static SeededEstate estate;

    private static long findingId;
    private static String catalogVersion = "cat-1";
    private static String snmpCredRefId;

    // ------------------------------------------------------------------ boot & seed

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_sweep_r12");
        work = Files.createTempDirectory("ui2-aiview-sweep-r12-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));

        estate = SeededEstate.generate();
        seedDatabaseBeforeBoot(estate);

        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(
                        AiviewRemainingGetRoutesSweepTest::useTestArtefactStore))
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

        seedViaApplicationBeans(estate);
    }

    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess",
                new DeviceCompositionConfiguration.ArtefactStoreAccess(store));
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

    private static String tag() {
        return "r" + hex(3);
    }

    private static String sha256Hex(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    record SeededEstate(
            String deviceId,
            String deviceHostname,
            String deviceSerial,
            String deviceIp,
            String zoneSourceName,
            String zoneDestName,
            String witnessSourceIp,
            String witnessDestIp,
            String credLabelSsh,
            String credLabelSnmp,
            String credUsername,
            String snmpUsername,
            String localIdentityName,
            String localIdentityPassword,
            String customRoleName,
            String customRoleToken,
            String ldapHost,
            String ldapIp,
            String ldapBindUser,
            String ldapAdminGroup,
            String ldapUsersGroup,
            String samlSpHost,
            String samlIdpHost,
            String samlAdminGroup,
            String samlUsersGroup,
            String settingsActor,
            String scheduleToken,
            Map<String, ValueClass> raw) {

        static SeededEstate generate() {
            int base = 20 + RANDOM.nextInt(150);
            String deviceId = Ui2Rows.opaqueId("dev");
            String deviceHostname = "gw-" + tag() + "-edge-01";
            String deviceSerial = "CP" + hex(5).toUpperCase(Locale.ROOT);
            String deviceIp = "192.0.2." + base;

            String zoneSourceName = "zn-" + tag() + "-cardholder";
            String zoneDestName = "zn-" + tag() + "-dmz";
            String witnessSourceIp = "192.0.2." + (base + 10);
            String witnessDestIp = "198.51.100." + (base + 20);

            String credLabelSsh = "cred-" + tag() + "-ssh-core";
            String credLabelSnmp = "cred-" + tag() + "-snmp-prod";
            String credUsername = "svc-" + tag() + "-fwops";
            String snmpUsername = "snmp-" + tag() + "-poll";

            String localIdentityName = "adm-" + tag() + "-local";
            String localIdentityPassword = "Synthetic#Pass-" + tag() + "99";

            String customRoleName = "Role-" + tag() + "-NetOps";
            String customRoleToken = "role:custom_" + tag();

            String ldapHost = "dc-" + tag() + "-01.corp.invalid";
            String ldapIp = "192.0.2." + (base + 30);
            String ldapBindUser = "bind-" + tag() + "-svc";
            String ldapAdminGroup = "CN=grp-" + tag() + "-admins,OU=Groups,DC=corp,DC=invalid";
            String ldapUsersGroup = "CN=grp-" + tag() + "-users,OU=Groups,DC=corp,DC=invalid";

            String samlSpHost = "overwatch-" + tag() + ".corp.invalid";
            String samlIdpHost = "adfs-" + tag() + ".corp.invalid";
            String samlAdminGroup = "saml-grp-" + tag() + "-adm";
            String samlUsersGroup = "saml-grp-" + tag() + "-usr";

            String settingsActor = "adm-" + tag() + "-cfg";
            String scheduleToken = "sched-" + tag() + "-mw";

            Map<String, ValueClass> raw = new LinkedHashMap<>();
            raw.put(deviceHostname, ValueClass.HOSTNAME);
            raw.put(ldapHost, ValueClass.HOSTNAME);
            raw.put(samlSpHost, ValueClass.HOSTNAME);
            raw.put(samlIdpHost, ValueClass.HOSTNAME);

            raw.put(deviceIp, ValueClass.ADDRESS);
            raw.put(witnessSourceIp, ValueClass.ADDRESS);
            raw.put(witnessDestIp, ValueClass.ADDRESS);
            raw.put(ldapIp, ValueClass.ADDRESS);

            raw.put(deviceSerial, ValueClass.SERIAL);

            raw.put(zoneSourceName, ValueClass.ZONE_NAME);
            raw.put(zoneDestName, ValueClass.ZONE_NAME);

            raw.put(credLabelSsh, ValueClass.CREDENTIAL_LABEL);
            raw.put(credLabelSnmp, ValueClass.CREDENTIAL_LABEL);

            raw.put(credUsername, ValueClass.USERNAME);
            raw.put(snmpUsername, ValueClass.USERNAME);
            raw.put(localIdentityName, ValueClass.USERNAME);
            raw.put(ldapBindUser, ValueClass.USERNAME);

            raw.put(customRoleName, ValueClass.DIRECTORY_OR_ROLE_NAME);
            raw.put(ldapAdminGroup, ValueClass.DIRECTORY_OR_ROLE_NAME);
            raw.put(ldapUsersGroup, ValueClass.DIRECTORY_OR_ROLE_NAME);
            raw.put(samlAdminGroup, ValueClass.DIRECTORY_OR_ROLE_NAME);
            raw.put(samlUsersGroup, ValueClass.DIRECTORY_OR_ROLE_NAME);

            raw.put(settingsActor, ValueClass.ACTOR_OR_SCHEDULE_TOKEN);
            raw.put(scheduleToken, ValueClass.ACTOR_OR_SCHEDULE_TOKEN);

            return new SeededEstate(
                    deviceId, deviceHostname, deviceSerial, deviceIp,
                    zoneSourceName, zoneDestName, witnessSourceIp, witnessDestIp,
                    credLabelSsh, credLabelSnmp, credUsername, snmpUsername,
                    localIdentityName, localIdentityPassword,
                    customRoleName, customRoleToken,
                    ldapHost, ldapIp, ldapBindUser, ldapAdminGroup, ldapUsersGroup,
                    samlSpHost, samlIdpHost, samlAdminGroup, samlUsersGroup,
                    settingsActor, scheduleToken, Map.copyOf(raw));
        }
    }

    // ------------------------------------------------------------------ database seeding

    private static void seedDatabaseBeforeBoot(SeededEstate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String baseCredRef = Ui2Rows.insertCredentialReference(c);
            audited(c, () -> {
                // 1. Enrolled device (eligible for SNMP polling and general estate)
                exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, "
                        + "enrollment_state, disabled, credential_reference_id, role, identity_mismatch_state, "
                        + "peer_follow_outcome, observed_hostname, observed_model, observed_software_version, "
                        + "recorded_identity_primary, recorded_identity_recorded_at, backup_target, discovery_match_key) "
                        + "values (?, 'check_point', 'manual', true, 'ENROLLED', false, ?, 'gateway', 'NONE', "
                        + "'NONE', ?, 'synthetic-model', 'R81.20', ?, now(), true, ?)",
                        e.deviceId(), baseCredRef, e.deviceHostname(), e.deviceSerial(),
                        "check_point|" + e.deviceSerial());
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                        Ui2Rows.opaqueId("ep"), e.deviceId(), e.deviceIp());

                // 2. Zone matrix: ACTIVE version + COMPLETE run + finding with witness addresses
                String versionId = UUID.randomUUID().toString();
                String srcZoneId = UUID.randomUUID().toString();
                String dstZoneId = UUID.randomUUID().toString();
                String versionContent = JSON.writeValueAsString(Map.of(
                        "zones", List.of(
                                Map.of("id", srcZoneId, "name", e.zoneSourceName(), "blocks", List.of(e.witnessSourceIp() + "/32")),
                                Map.of("id", dstZoneId, "name", e.zoneDestName(), "blocks", List.of(e.witnessDestIp() + "/32")))));
                String versionSha = sha256Hex(versionContent);
                exec(c, "insert into zone_matrix_version(version_id, number, state, revision, content, content_sha256, "
                        + "created_by, updated_by, published_by, published_at) "
                        + "values (?, 1, 'ACTIVE', 1, ?, ?, ?, ?, ?, now())",
                        versionId, versionContent, versionSha, Ui2Rows.ACTOR, Ui2Rows.ACTOR, Ui2Rows.ACTOR);

                String runId = UUID.randomUUID().toString();
                String policySourceId = "policy-" + UUID.randomUUID();
                exec(c, "insert into zone_matrix_run(run_id, version_id, version_number, content_sha256, policy_source_id, "
                        + "engine_version, state, started_at) values (?, ?, 1, ?, ?, 'zm-1', 'RUNNING', now())",
                        runId, versionId, versionSha, policySourceId);

                String violatedCellsJson = JSON.writeValueAsString(List.of(Map.of(
                        "source_zone", srcZoneId,
                        "destination_zone", dstZoneId,
                        "statement", "DENY",
                        "severity", "HIGH",
                        "not_allowed_services", List.of("tcp/443"),
                        "nat_view", "PRE_NAT",
                        "witness", Map.of(
                                "source_range", e.witnessSourceIp() + "/32",
                                "destination_range", e.witnessDestIp() + "/32",
                                "service", "tcp/443",
                                "sample_source_ip", 3221225984L,
                                "sample_destination_ip", 3325256704L))));
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into zone_matrix_finding(run_id, policy_source_id, rule_uid, inline_parent_path, "
                                + "violated_pairs, rule_id, rule_number, rule_name, section_id, section_name, "
                                + "target_device_ids, outcome, reason, highest_severity, flags, violated_cells, "
                                + "undecided_parts, nat_view, first_observed_at, last_observed_at) "
                                + "values (?, ?, ?, '[]'::jsonb, ?::jsonb, ?, 1, ?, 'sec-1', 'Access', ?::jsonb, "
                                + "'VIOLATION', 'DENY_MATCH', 'HIGH', '[]'::jsonb, ?::jsonb, '[]'::jsonb, 'PRE_NAT', now(), now()) "
                                + "returning finding_id")) {
                    ps.setString(1, runId);
                    ps.setString(2, policySourceId);
                    ps.setString(3, "rule-uid-" + tag());
                    ps.setString(4, JSON.writeValueAsString(List.of(srcZoneId + "->" + dstZoneId)));
                    ps.setString(5, "rule-" + tag());
                    ps.setString(6, "allow-" + e.zoneSourceName() + "-to-" + e.zoneDestName());
                    ps.setString(7, JSON.writeValueAsString(List.of(e.deviceId())));
                    ps.setString(8, violatedCellsJson);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "zone_matrix_finding insert returned finding_id");
                        findingId = rs.getLong(1);
                    }
                }

                exec(c, "update zone_matrix_run set state = 'COMPLETE', finished_at = now(), snapshot_collected_at = now(), "
                        + "snapshot_rule_count = 1, vendor = 'CP', evaluated = true, coverage = 'COMPLETE', "
                        + "coverage_items = '[]'::jsonb, collection_failures = 0, count_violation = 1, count_compliant = 0, "
                        + "count_not_evaluable = 0, count_unknown = 0, count_not_applicable = 0, count_findings = 1, "
                        + "duration_ms = 12 where run_id = ?", runId);

                // 3. Log-detection P2 template catalog (cat-1, FROZEN) + one template
                String catManifest = JSON.writeValueAsString(Map.of(
                        "normaliser", "v1",
                        "window_from", "2026-11-01",
                        "window_to", "2026-11-10",
                        "sources", List.of("cp_messages")));
                String catDigest = sha256Hex("cat-1:" + e.settingsActor());
                String catDiff = JSON.writeValueAsString(Map.of("added", 1, "removed", 0));
                exec(c, "insert into log_template_catalog(catalog_version, state, previous_version, manifest, digest, "
                        + "template_count, diff, created_by, frozen_at, frozen_by) "
                        + "values (?, 'FROZEN', null, ?::jsonb, ?, 1, ?::jsonb, ?, now(), ?)",
                        catalogVersion, catManifest, catDigest, catDiff, e.settingsActor(), e.settingsActor());
                exec(c, "insert into log_template(catalog_version, template_id, source_key, template_text, match_form, "
                        + "token_count, severity_weight, template_class, provenance, line_count, prevalence_devices, cohort_devices) "
                        + "values (?, ?, 'cp_messages', 'interface link up <*>', 'interface link up <*>', 4, 0.50, "
                        + "'MARKER', 'MEASURED', 12, 1, 1)",
                        catalogVersion, "t" + hex(15) + "0");

                // 4. Custom RBAC role with planted name, hostname and IP in description
                exec(c, "insert into rbac_roles(id, name, token_string, description, is_system) values (?, ?, ?, ?, false)",
                        UUID.randomUUID(), e.customRoleName(), e.customRoleToken(),
                        "Custom ops role for " + e.deviceHostname() + " (" + e.deviceIp() + ")");

                // 5. LDAP settings (row id = 1)
                exec(c, "insert into ldap_settings(id, enabled, server_type, servers, port, domain_dn, admin_group_dn, "
                        + "users_group_dn, bind_dn, bind_password_encrypted, bind_password_key_id, connect_timeout_seconds, "
                        + "ldaps, trust_mode, trust_pem, updated_by) "
                        + "values (1, false, 'ACTIVE_DIRECTORY', ARRAY[?, ?], 389, 'DC=corp,DC=invalid', ?, ?, ?, "
                        + "decode('01020304', 'hex'), 'synthetic-key', 10, false, 'TRUST_ANY', null, ?)",
                        e.ldapHost(), e.ldapIp(), e.ldapAdminGroup(), e.ldapUsersGroup(),
                        "CN=" + e.ldapBindUser() + ",OU=ServiceAccounts,DC=corp,DC=invalid", e.settingsActor());

                // 6. SAML settings (row id = 1)
                exec(c, "insert into saml_settings(id, enabled, sp_base_url, sp_entity_id, idp_entity_id, idp_sso_url, "
                        + "idp_sso_binding, idp_signing_cert_pem, group_claim_attribute, admin_group_value, users_group_value, "
                        + "clock_skew_seconds, sign_authn_requests, updated_by) "
                        + "values (1, false, ?, ?, ?, ?, 'REDIRECT', "
                        + "'-----BEGIN CERTIFICATE-----\nSYNTHETIC\n-----END CERTIFICATE-----', "
                        + "'http://schemas.xmlsoap.org/claims/Group', ?, ?, 60, false, ?)",
                        "https://" + e.samlSpHost(),
                        "https://" + e.samlSpHost() + "/sp",
                        "https://" + e.samlIdpHost() + "/adfs/services/trust",
                        "https://" + e.samlIdpHost() + "/adfs/ls/",
                        e.samlAdminGroup(), e.samlUsersGroup(), e.settingsActor());

                // 7. System-log settings, local-auth-mode, local-password-policy with planted updated_by actor
                exec(c, "insert into system_log_settings(settings_id, updated_by) values ('default', ?) "
                        + "on conflict (settings_id) do update set updated_by = excluded.updated_by, updated_at = now()",
                        e.settingsActor());
                exec(c, "insert into local_auth_mode_settings(settings_id, mode, updated_by) values ('default', 'ALL_LOCAL', ?) "
                        + "on conflict (settings_id) do update set updated_by = excluded.updated_by, updated_at = now()",
                        e.settingsActor());
                exec(c, "insert into local_password_policy_settings(settings_id, enabled, updated_by) values ('default', false, ?) "
                        + "on conflict (settings_id) do update set updated_by = excluded.updated_by, updated_at = now()",
                        e.settingsActor());
            });
        }
    }

    private static void seedViaApplicationBeans(SeededEstate e) throws Exception {
        // Seed credentials via the real CredentialStorePort so encryption and credential_references are authentic.
        CredentialStorePort creds = context.getBean(CredentialStorePort.class);
        creds.create(Ui2Rows.ACTOR, e.credLabelSsh(), CredentialKind.SSH_PASSWORD, e.credUsername(),
                true, false, "SyntheticSshSecret!".toCharArray(), Optional.empty(), null);
        CredentialView snmpCred = creds.create(Ui2Rows.ACTOR, e.credLabelSnmp(), CredentialKind.SNMP_V3,
                e.snmpUsername(), true, true, "SyntheticAuthPass1!".toCharArray(),
                Optional.of("SyntheticPrivPass1!".toCharArray()),
                new SnmpSettings("authPriv", "SHA-256", "AES-128"));
        snmpCredRefId = snmpCred.credentialReferenceId();

        // Point snmp_polling_settings at the seeded SNMPv3 credential and planted actor.
        try (Connection c = fixture.migrateConnection()) {
            audited(c, () -> exec(c,
                    "insert into snmp_polling_settings(settings_id, enabled, interval_s, default_credential_reference_id, "
                            + "scope_mode, updated_by) values ('default', false, 300, ?, 'VENDORS', ?) "
                            + "on conflict (settings_id) do update set default_credential_reference_id = excluded.default_credential_reference_id, "
                            + "updated_by = excluded.updated_by, updated_at = now()",
                    snmpCredRefId, e.settingsActor()));
        }

        // Seed a local identity via LocalIdentityAdministrationPort so GET /local-identities and GET /sessions carry it.
        LocalIdentityAdministrationPort localIds = context.getBean(LocalIdentityAdministrationPort.class);
        localIds.create(Ui2Rows.ACTOR, e.localIdentityName(), e.localIdentityPassword().toCharArray());
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void audited(Connection c, Work work) throws Exception {
        c.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(c, Ui2Rows.ACTOR, Ui2Rows.ACTION);
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
        if (body == null || body.isEmpty()) return found;
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

    // ------------------------------------------------------------------ HTTP helpers

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    record Resp(int status, String body) {
    }

    private static Resp get(String path, String cookie) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Cookie", "ui2_session=" + cookie)
                .GET()
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8));
    }

    private static String login(String username, String password) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "login failed for persona");
        for (String cookie : resp.headers().allValues("set-cookie")) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("no session cookie on login");
    }

    // ------------------------------------------------------------------ route table

    /**
     * One of the 15 uncovered GET routes: {@code routeKey} matches {@link AiviewReachableRoutes}, {@code path} is the
     * concrete HTTP request path, and {@code adminExpectedTokens} are planted strings or seeded markers that MUST
     * appear in the administrator {@code 200 OK} body to prove the fixture is non-vacuous.
     */
    record RouteSpec(String routeKey, String path, List<String> adminExpectedTokens) {
    }

    static List<RouteSpec> allFifteenRoutes(SeededEstate e) {
        return List.of(
                new RouteSpec(
                        "GET /api/v2/zone-matrix/findings/*/witness",
                        "/api/v2/zone-matrix/findings/" + findingId + "/witness",
                        List.of("\"witnesses_included\":true", e.witnessSourceIp(), e.witnessDestIp())),
                new RouteSpec(
                        "GET /api/v2/config/log-detection/catalogs",
                        "/api/v2/config/log-detection/catalogs",
                        List.of("\"" + catalogVersion + "\"", "\"FROZEN\"")),
                new RouteSpec(
                        "GET /api/v2/config/log-detection/catalogs/*",
                        "/api/v2/config/log-detection/catalogs/" + catalogVersion,
                        List.of("\"" + catalogVersion + "\"", "\"FROZEN\"")),
                new RouteSpec(
                        "GET /api/v2/failover/checks",
                        "/api/v2/failover/checks",
                        List.of()),
                new RouteSpec(
                        "GET /api/v2/failover/schedules/*",
                        "/api/v2/failover/schedules/" + e.scheduleToken(),
                        List.of()),
                new RouteSpec(
                        "GET /credentials",
                        "/credentials",
                        List.of(e.credLabelSsh(), e.credLabelSnmp(), e.credUsername(), e.snmpUsername())),
                new RouteSpec(
                        "GET /local-identities",
                        "/local-identities",
                        List.of(e.localIdentityName())),
                new RouteSpec(
                        "GET /roles",
                        "/roles",
                        List.of(e.customRoleName(), e.deviceHostname(), e.deviceIp())),
                new RouteSpec(
                        "GET /sessions",
                        "/sessions",
                        List.of("\"sessions\"", e.localIdentityName())),
                new RouteSpec(
                        "GET /api/v2/config/ldap",
                        "/api/v2/config/ldap",
                        List.of(e.ldapHost(), e.ldapIp(), e.ldapBindUser(), e.ldapAdminGroup(), e.ldapUsersGroup())),
                new RouteSpec(
                        "GET /api/v2/config/saml",
                        "/api/v2/config/saml",
                        List.of(e.samlSpHost(), e.samlIdpHost(), e.samlAdminGroup(), e.samlUsersGroup())),
                new RouteSpec(
                        "GET /api/v2/config/snmp-polling",
                        "/api/v2/config/snmp-polling",
                        List.of(e.credLabelSnmp(), snmpCredRefId, e.settingsActor())),
                new RouteSpec(
                        "GET /api/v2/config/system-logs",
                        "/api/v2/config/system-logs",
                        List.of(e.settingsActor())),
                new RouteSpec(
                        "GET /api/v2/config/local-auth-mode",
                        "/api/v2/config/local-auth-mode",
                        List.of(e.settingsActor(), "ALL_LOCAL")),
                new RouteSpec(
                        "GET /api/v2/config/local-password-policy",
                        "/api/v2/config/local-password-policy",
                        List.of(e.settingsActor())));
    }

    // ------------------------------------------------------------------ the sweep

    @Test
    void allFifteenRemainingGetRoutesLeakZeroRawValuesToAiview() throws Exception {
        SeededEstate e = estate;
        List<RouteSpec> routes = allFifteenRoutes(e);
        assertEquals(15, routes.size(), "must cover all 15 remaining GET routes");

        // Static route table check: every one of the 15 routes exists and is NOT reachable by aiview in default mode.
        Map<String, Boolean> staticReachable = new TreeMap<>();
        for (var ep : AiviewReachableRoutes.all()) {
            if (!"GET".equals(ep.method())) continue;
            boolean may = ep.actionId() != null && AiviewReachableRoutes.aiviewMayCall(ep.actionId());
            staticReachable.merge(ep.key(), may, (a, b) -> a || b);
        }
        for (RouteSpec spec : routes) {
            assertTrue(staticReachable.containsKey(spec.routeKey()),
                    "route must exist in Spring MVC mappings: " + spec.routeKey());
            assertFalse(staticReachable.get(spec.routeKey()),
                    "route must not be aiview-reachable in default config: " + spec.routeKey());
        }

        // Open sessions: admin (nexusadmin), aiview (replay_viewer), and the seeded local identity (so /sessions sees it).
        String adminCookie = login("nexusadmin", "nexusadmin");
        String aiviewCookie = login("aiview", "aiview");
        login(e.localIdentityName(), e.localIdentityPassword());

        Map<String, Map<ValueClass, Integer>> aiviewLeaks = new TreeMap<>();
        int totalAiviewLeaks = 0;

        for (RouteSpec spec : routes) {
            Resp adminResp = get(spec.path(), adminCookie);
            Resp aiviewResp = get(spec.path(), aiviewCookie);

            Map<ValueClass, Integer> adminRaw = count(adminResp.body(), e.raw());
            Map<ValueClass, Integer> aiviewRaw = count(aiviewResp.body(), e.raw());
            if (!aiviewRaw.isEmpty()) {
                aiviewLeaks.put(spec.routeKey(), aiviewRaw);
                totalAiviewLeaks += aiviewRaw.values().stream().mapToInt(Integer::intValue).sum();
            }

            System.out.printf(Locale.ROOT,
                    "AIVIEW_REMAINING_GET %-48s admin=%3d (raw=%s) aiview=%3d (raw=%s)%n",
                    spec.routeKey(), adminResp.status(), adminRaw.isEmpty() ? "0" : adminRaw,
                    aiviewResp.status(), aiviewRaw.isEmpty() ? "0" : aiviewRaw);

            if (UNMAPPED_ROUTES.contains(spec.routeKey())) {
                assertEquals(403, adminResp.status(),
                        spec.routeKey() + " is unmapped and must refuse admin with 403 ACTION_MAPPING_REQUIRED");
                assertTrue(adminResp.body().contains("ACTION_MAPPING_REQUIRED"),
                        spec.routeKey() + " admin refusal must cite ACTION_MAPPING_REQUIRED");
                assertEquals(403, aiviewResp.status(),
                        spec.routeKey() + " is unmapped and must refuse aiview with 403 ACTION_MAPPING_REQUIRED");
                assertTrue(aiviewResp.body().contains("ACTION_MAPPING_REQUIRED"),
                        spec.routeKey() + " aiview refusal must cite ACTION_MAPPING_REQUIRED");
                assertEquals(Map.of(), adminRaw,
                        spec.routeKey() + " unmapped refusal must echo zero planted raw values");
            } else {
                assertEquals(200, adminResp.status(),
                        spec.routeKey() + " must return 200 OK for admin (seeded, non-vacuous)");
                assertFalse(adminResp.body().isBlank(),
                        spec.routeKey() + " admin response body must not be empty");
                for (int i = 0; i < spec.adminExpectedTokens().size(); i++) {
                    String expected = spec.adminExpectedTokens().get(i);
                    assertTrue(adminResp.body().contains(expected),
                            spec.routeKey() + " admin response must contain seeded marker index " + i
                                    + " (non-vacuous verification)");
                }
                assertEquals(403, aiviewResp.status(),
                        spec.routeKey() + " must refuse aiview with HTTP 403");
            }

            assertEquals(Map.of(), aiviewRaw,
                    spec.routeKey() + " must leak zero planted raw values to aiview");
        }

        System.out.println("AIVIEW_REMAINING_GET_SUMMARY routes=" + routes.size()
                + " planted_values=" + e.raw().size()
                + " aiview_surviving_raw_occurrences=" + totalAiviewLeaks);
        assertEquals(Map.of(), aiviewLeaks, "zero planted raw values may survive in any aiview response");
    }

    /** The counter itself: addresses never match as a prefix of a longer address; names match case-insensitively. */
    @Test
    void theCounterSeesSpellingsAndNoPrefixMatch() {
        Map<String, ValueClass> raw = Map.of(
                "192.0.2.15", ValueClass.ADDRESS,
                "zn-r0a0b0c-cardholder", ValueClass.ZONE_NAME);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ZONE_NAME, 1), count("zone ZN-R0A0B0C-CARDHOLDER", raw));
        assertEquals(Map.of(), count("{\"zone\":\"ZONE-01\"}", raw));
    }
}
