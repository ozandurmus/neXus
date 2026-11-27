package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.policy.PolicyHygiene;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Round 4 of the raw-versus-masked sweep of backlog {@code aiview_masking_leak_audit}: the planes that rounds 1-3
 * ({@code AiviewRawVersusMaskedSweepTest}) left empty. A synthetic estate with planted raw values is seeded into a real
 * PostgreSQL 16 database, the application is booted, the aiview persona ({@code role:replay_viewer}) logs in and GETs
 * every route the shared {@link AiviewReachableRoutes} calls aiview-reachable, and every planted raw value is counted in
 * every body. Output names route, status and value <em>class</em> with a count -- never a value.
 *
 * <p>Planes seeded: device lifecycle, licence facts and advisories (a member reference naming a host, a serial and an
 * address, a catalog row whose note and importer name a host and the administrator, an upload row, plus synthetic
 * advisories, product rules, known-exploited CVE, Check Point Jumbo statements/page load and feed runs across
 * Check Point, Palo Alto, Fortinet, Cisco ASA and CISA KEV), SNMP metric samples (raw,
 * hourly and poll state), configuration-override notifications (summary and paths in free text), backup size-anomaly
 * records (free-text summary), and a Palo Alto policy whose rules name raw zones with the stored virtual-system zone set
 * of its firewall (read both on the policy views and on {@code GET /api/v2/operations/policy-install/status}, fired
 * first on the cold process and keyed by {@code device_id}, {@code package_id} and the masked
 * {@code "row_id":"pan:<policyId>:"} prefix).
 * Notification settings and routes (syslog and mail hosts, recipients) and the audit rows (actor and
 * target text) are seeded too, but their only routes are {@code security_admin} reads aiview cannot reach: for those the
 * test asserts the refusal and that the refusal body carries no planted value.</p>
 *
 * <p>The class is self-contained on purpose (the other sweep is edited by other lanes): the boot, login, row-insert and
 * body-scan helpers are copies of that sweep's.</p>
 */
class AiviewUnseededPlanesSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a planted value is; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, ADDRESS, SERIAL, PRINCIPAL, RULE_NAME, OBJECT_NAME, POLICY_NAME, ZONE_NAME, VSYS_NAME, MAIL_ADDRESS
    }

    /** Surviving raw values left for a follow-up lane: {@code "GET <route> <CLASS>"}. Must equal the findings exactly. */
    static final Set<String> KNOWN_LEAKS = Set.of();

    /**
     * Routes of the seeded planes that only {@code security_admin} may read: aiview must be refused (401/403) and the
     * refusal must not carry a planted value.
     */
    static final Set<String> MUST_BE_REFUSED = Set.of("GET /audit-logs", "GET /api/v2/config/notifications");

    /** Views that must show seeded rows (by marker), so a zero count is a count over real data and not an empty page. */
    static final Map<String, Set<String>> COVERAGE = Map.ofEntries(
            Map.entry("GET /api/v2/lifecycle", Set.of("device", "license-item", "vulnerabilities-evaluated")),
            Map.entry("GET /devices/*/lifecycle", Set.of("device", "license-item", "vulnerabilities-evaluated")),
            Map.entry("GET /api/v2/lifecycle/catalog", Set.of("catalog-entry")),
            Map.entry("GET /api/v2/lifecycle/catalog-sources", Set.of("catalog-upload")),
            Map.entry("GET /api/v2/lifecycle/advisories", Set.of("advisory", "advisory-affected-count")),
            Map.entry("GET /api/v2/lifecycle/advisories/*", Set.of("advisory", "device", "advisory-affected-verdict")),
            Map.entry("GET /api/v2/lifecycle/advisory-sources", Set.of("advisory-feed-run", "advisory-source-fresh")),
            Map.entry("GET /notifications", Set.of("notification")),
            Map.entry("GET /api/v2/backups/deviations", Set.of("size-anomaly")),
            Map.entry("GET /api/v2/policy/policies/*", Set.of("pan-policy", "pan-rule")),
            Map.entry("GET /api/v2/policy/policies/*/rules", Set.of("pan-rule")),
            Map.entry("GET /api/v2/policy/policies/*/hygiene", Set.of("pan-zone-verdict")),
            Map.entry("GET /api/v2/policy/policies/*/hygiene.csv", Set.of("pan-rule")),
            Map.entry("GET /api/v2/policy/devices", Set.of("pan-policy")),
            Map.entry("GET /api/v2/operations/policy-install/status", Set.of("device", "pan-policy", "policy-install-row")),
            Map.entry("GET /api/v2/system-logs/health", Set.of("device", "snmp-metrics")));

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static Estate estate;
    private static String sessionCookie;
    private static final Map<String, String> ids = new TreeMap<>();

    // ------------------------------------------------------------------ boot (copied from AiviewRawVersusMaskedSweepTest)

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_unseeded");
        work = Files.createTempDirectory("ui2-aiview-unseeded-");
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

    /** The env-configured (here: absent) artefact store is replaced, and the fixed machine port is not opened. */
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
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
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

    // ------------------------------------------------------------------ the synthetic estate

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** A token with at least one letter, so it can never equal a digits-only pseudonym suffix. */
    private static String tag() {
        return "x" + hex(3);
    }

    record Device(String id, String vendor, String role, String hostname, String serial, String address, String cluster) {
    }

    /** Every planted raw value, generated per run. */
    record Estate(Device cpA, Device cpB, Device pan, Device panorama, Device asa, Device fgt, String principal,
            String fgtModifier, String objectName, String objectAddress, String policyName, String policyId,
            String domainId, String objectId, String ruleId, String ruleName, String shadowedRuleName, String zoneA,
            String zoneB, String vsys, String asaOutsideIf, String asaInsideIf, String asaIfAddress, String asaHostName,
            String asaHostAddress, String asaBoundAcl, String asaUnboundAcl, String fgtPortA, String fgtPortB,
            String fgtIfAddress, String fgtSrcObjName, String fgtSrcAddress, String fgtDstObjName, String fgtDstAddress,
            String fgtRuleName, String fgtShadowedRuleName, String catalogProduct, String syslogHost, String smtpHost,
            String smtpTo, String smtpFrom, String routeRecipients, String auditTargetHost, String auditTargetAddress,
            Map<String, ValueClass> raw) {

        static Estate generate() {
            String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            int base = 20 + RANDOM.nextInt(150);
            Device a = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway", "FW-TANGO-" + tag().toUpperCase(Locale.ROOT),
                    "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + base, cluster);
            Device b = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway", "ist" + tag() + "-fw-02",
                    "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + 1), cluster);
            Device pan = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway", "dc" + tag() + "-edge-gw01",
                    "0" + hex(6).replaceAll("[a-f]", "7"), "192.0.2." + (base + 2), null);
            Device panorama = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "management_server", "core" + tag() + "-pano-a",
                    "0" + hex(6).replaceAll("[a-f]", "3"), "192.0.2." + (base + 3), null);
            Device asa = new Device(Ui2Rows.opaqueId("dev"), "cisco_asa", "gateway", "asa" + tag() + "-edge01",
                    "JAD" + hex(4).toUpperCase(Locale.ROOT), "192.0.2." + (base + 4), null);
            Device fgt = new Device(Ui2Rows.opaqueId("dev"), "fortinet", "gateway", "fgt" + tag() + "-edge01",
                    "FGT" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + 5), null);
            String principal = "adm" + tag() + ".ops";
            String fgtModifier = "fgtadm" + tag() + ".sec";
            String t = tag();
            char at = '@';
            Estate e = new Estate(a, b, pan, panorama, asa, fgt, principal, fgtModifier,
                    "h-" + tag() + "-dbsrv", "198.51.100." + (base % 200),
                    "pkg-" + tag() + "-pan", "policy-" + UUID.randomUUID(), "domain-" + UUID.randomUUID(),
                    "obj-" + UUID.randomUUID(), "rule-" + UUID.randomUUID(), "allow-" + tag() + "-payroll",
                    "deny-" + tag() + "-legacy", "zn-" + tag() + "-cardholder", "zn-" + tag() + "-corebank",
                    "vsys-" + tag(),
                    "nif-" + tag() + "-out", "nif-" + tag() + "-in", "203.0.113." + (base % 200),
                    "asah-" + tag() + "-app01", "203.0.113." + (base % 200 + 1),
                    "ACL-" + tag().toUpperCase(Locale.ROOT) + "-OUT", "ACL-" + tag().toUpperCase(Locale.ROOT) + "-ORPHAN",
                    "if-" + tag() + "-wan", "if-" + tag() + "-lan", "203.0.113." + (base % 200 + 2),
                    "fgth-" + tag() + "-src", "203.0.113." + (base % 200 + 3),
                    "fgth-" + tag() + "-dst", "203.0.113." + (base % 200 + 4),
                    "fgtpol-" + tag() + "-allow", "fgtpol-" + tag() + "-shadow",
                    "Synthetic-" + tag() + " 6600", "syslog" + t + "-collector01", "smtp" + t + "-relay01",
                    principal + at + "mail" + t + ".example.test", "noreply" + at + "mail" + t + ".example.test",
                    "oncall" + t + at + "mail" + t + ".example.test", "jump" + tag() + "-bastion02",
                    "198.51.100." + (base % 200 + 1), null);
            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : e.devices()) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.address(), ValueClass.ADDRESS);
            }
            raw.put(cluster, ValueClass.HOSTNAME);
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(e.fgtModifier(), ValueClass.PRINCIPAL);
            raw.put(e.objectName(), ValueClass.OBJECT_NAME);
            raw.put(e.objectAddress(), ValueClass.ADDRESS);
            raw.put(e.policyName(), ValueClass.POLICY_NAME);
            raw.put(e.ruleName(), ValueClass.RULE_NAME);
            raw.put(e.shadowedRuleName(), ValueClass.RULE_NAME);
            raw.put(e.zoneA(), ValueClass.ZONE_NAME);
            raw.put(e.zoneB(), ValueClass.ZONE_NAME);
            raw.put(e.vsys(), ValueClass.VSYS_NAME);
            raw.put(e.asaOutsideIf(), ValueClass.ZONE_NAME);
            raw.put(e.asaInsideIf(), ValueClass.ZONE_NAME);
            raw.put(e.asaIfAddress(), ValueClass.ADDRESS);
            raw.put(e.asaHostName(), ValueClass.OBJECT_NAME);
            raw.put(e.asaHostAddress(), ValueClass.ADDRESS);
            raw.put(e.asaBoundAcl(), ValueClass.RULE_NAME);
            raw.put(e.asaUnboundAcl(), ValueClass.RULE_NAME);
            raw.put(e.fgtPortA(), ValueClass.ZONE_NAME);
            raw.put(e.fgtPortB(), ValueClass.ZONE_NAME);
            raw.put(e.fgtIfAddress(), ValueClass.ADDRESS);
            raw.put(e.fgtSrcObjName(), ValueClass.OBJECT_NAME);
            raw.put(e.fgtSrcAddress(), ValueClass.ADDRESS);
            raw.put(e.fgtDstObjName(), ValueClass.OBJECT_NAME);
            raw.put(e.fgtDstAddress(), ValueClass.ADDRESS);
            raw.put(e.fgtRuleName(), ValueClass.RULE_NAME);
            raw.put(e.fgtShadowedRuleName(), ValueClass.RULE_NAME);
            raw.put(e.syslogHost(), ValueClass.HOSTNAME);
            raw.put(e.smtpHost(), ValueClass.HOSTNAME);
            raw.put(e.smtpTo(), ValueClass.MAIL_ADDRESS);
            raw.put(e.smtpFrom(), ValueClass.MAIL_ADDRESS);
            raw.put(e.routeRecipients(), ValueClass.MAIL_ADDRESS);
            raw.put(e.auditTargetHost(), ValueClass.HOSTNAME);
            raw.put(e.auditTargetAddress(), ValueClass.ADDRESS);
            return new Estate(a, b, pan, panorama, asa, fgt, principal, e.fgtModifier(), e.objectName(), e.objectAddress(),
                    e.policyName(), e.policyId(), e.domainId(), e.objectId(), e.ruleId(), e.ruleName(), e.shadowedRuleName(),
                    e.zoneA(), e.zoneB(), e.vsys(), e.asaOutsideIf(), e.asaInsideIf(), e.asaIfAddress(), e.asaHostName(),
                    e.asaHostAddress(), e.asaBoundAcl(), e.asaUnboundAcl(), e.fgtPortA(), e.fgtPortB(), e.fgtIfAddress(),
                    e.fgtSrcObjName(), e.fgtSrcAddress(), e.fgtDstObjName(), e.fgtDstAddress(), e.fgtRuleName(),
                    e.fgtShadowedRuleName(), e.catalogProduct(), e.syslogHost(), e.smtpHost(), e.smtpTo(), e.smtpFrom(),
                    e.routeRecipients(), e.auditTargetHost(), e.auditTargetAddress(), Map.copyOf(raw));
        }

        List<Device> devices() {
            return List.of(cpA, cpB, pan, panorama, asa, fgt);
        }
    }

    // ------------------------------------------------------------------ seeding

    private static void seed(Estate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, Ui2Rows.ACTOR, () -> {
                for (Device d : e.devices()) {
                    String softwareVersion = switch (d.vendor()) {
                        case "check_point" -> "R81.20";
                        case "palo_alto" -> "11.1.4-h1";
                        case "cisco_asa" -> "9.18(4)22";
                        case "fortinet" -> "v7.4.8 build2795";
                        default -> "R81.20";
                    };
                    String hotfixLevel = "check_point".equals(d.vendor()) ? "R81.20 Jumbo Take 119" : null;
                    exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                            + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                            + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                            + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                            + "discovery_match_key) values (?, ?, 'manual', true, 'ENROLLED', false, ?, ?, 'NONE', ?, ?, "
                            + "'synthetic-model', ?, ?, ?, now(), ?, true, ?)",
                            d.id(), d.vendor(), credential, d.role(), d.cluster() == null ? "NONE" : "CORROBORATED",
                            d.hostname(), softwareVersion, d.cluster() == null ? null : "active", d.serial(), d.cluster(),
                            d.vendor() + "|" + d.serial());
                    exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                            Ui2Rows.opaqueId("ep"), d.id(), d.address());
                    exec(c, "insert into device_platform_facts(device_id, serial_number, hotfix_level, platform_family, source_read) "
                            + "values (?, ?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial(), hotfixLevel);
                }
            });
            seedLifecycle(c, e);
            seedAdvisories(c, e);
            seedSnmpMetrics(c, e);
            seedBackupDeviation(c, e);
            seedPanPolicy(c, e);
            seedAsaPolicy(c, e);
            seedFgtPolicy(c, e);
            seedNotifications(c, e);
            seedAuditAndSettings(c, e);
        }
    }

    /**
     * Lifecycle and licence facts (V200, V144, V219): licence items whose member reference is a raw host name, a raw
     * serial and a raw address, one successful read per device, a catalog row whose note and importer name raw values,
     * and an operator page upload by the administrator.
     */
    private static void seedLifecycle(Connection c, Estate e) throws Exception {
        audited(c, Ui2Rows.ACTOR, () -> {
            String[][] members = {{e.cpA().id(), e.cpA().hostname()}, {e.cpB().id(), e.cpB().serial()},
                    {e.pan().id(), e.pan().address()}};
            for (String[] m : members) {
                exec(c, "insert into device_lifecycle_read(device_id, read_kind, outcome, item_count) values (?, 'license', 'COLLECTED', 1)",
                        m[0]);
                exec(c, "insert into device_license_item(item_id, device_id, read_kind, category, label, state, expires_on, detail, "
                        + "member_ref, evidence_grade) values (?, ?, 'license', 'LICENSE', ?, 'VALID', current_date + 40, "
                        + "'synthetic detail', ?, 'DIRECT_DEVICE')",
                        Ui2Rows.opaqueId("lic"), m[0], "Synthetic Blade " + ids.computeIfAbsent("licenseTag", k -> tag()), m[1]);
            }
            exec(c, "insert into lifecycle_catalog(catalog_id, vendor, kind, product, end_of_sale, end_of_support, source, note, "
                    + "imported_by) values (?, 'CHECKPOINT', 'HARDWARE', ?, '2024-01-31', '2030-01-31', 'MANUAL', ?, ?)",
                    "cat-" + UUID.randomUUID(), e.catalogProduct(),
                    "checked by " + e.principal() + " against " + e.cpA().hostname() + " (" + e.cpA().address() + ")",
                    e.principal());
            String upload = "upl" + hex(6);
            exec(c, "insert into lifecycle_catalog_upload(upload_id, page_form, vendor, content_sha256, entries_parsed, "
                    + "entries_needing_review, rows_skipped, uploaded_by) values (?, 'checkpoint_lifecycle', 'CHECKPOINT', ?, 3, 1, 0, ?)",
                    upload, "a".repeat(64), e.principal());
            ids.put("uploadId", upload);
        });
        ids.put("licenseMarker", "Synthetic Blade " + ids.get("licenseTag"));
    }

    /**
     * Advisories and advisory sources (V205, V214): synthetic public advisories, product rules, known-exploited CVE,
     * Check Point Jumbo resolved-issues statement/page load, a Check Point Live Patch label row, and OK feed runs for
     * every source matching the seeded estate devices.
     */
    private static void seedAdvisories(Connection c, Estate e) throws Exception {
        String cpAdvId = "checkpoint:CVE-2026-99002";
        String panAdvId = "paloalto:CVE-2026-99001";
        String fgtAdvId = "fortinet:FG-IR-26-99003";
        String ciscoAdvId = "cisco:cisco-sa-asa-synth-99004";

        String cpRule = "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":null,"
                + "\"to\":\"R81.20 Jumbo Take 119\",\"toInclusive\":true,\"changes\":[]}]}";
        String panRule = "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":\"11.1.0\","
                + "\"to\":\"11.1.10\",\"toInclusive\":false,\"changes\":[]}]}";
        String fgtRule = "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":\"7.4.0\","
                + "\"to\":\"7.4.9\",\"toInclusive\":false,\"changes\":[]}]}";
        String ciscoRule = "{\"v\":1,\"rules\":[{\"k\":\"exact\",\"status\":\"AFFECTED\","
                + "\"versions\":[\"9.18.4.22\"]}]}";

        // Advisory tables carry no audit trigger (machine-ingested public data, V205 / V214).
        for (String src : List.of("checkpoint", "paloalto", "fortinet", "cisco", "cisa_kev")) {
            exec(c, "insert into advisory_feed_run(run_id, source, started_at, ended_at, outcome, "
                    + "documents_received, rejected, advisories_stored, advisories_needing_review, "
                    + "documents_fetched, documents_failed) values (?, ?, now() - interval '10 minutes', "
                    + "now() - interval '9 minutes', 'OK', 1, 0, 1, 0, 1, 0)",
                    "advrun-" + src + "-" + hex(4), src);
        }

        exec(c, "insert into checkpoint_jumbo_statement(train, take, cve_id, kind, product_text, sk_ref, evidence, "
                + "source_url, content_sha256) values ('R81.20', 120, 'CVE-2026-99002', 'FIX', 'Security Gateway', "
                + "'sk999999', 'Resolved CVE-2026-99002. Refer to sk999999.', "
                + "'https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html', ?)",
                "c".repeat(64));
        exec(c, "insert into checkpoint_jumbo_page_load(train, loaded_at, statements, source_url, content_sha256) "
                + "values ('R81.20', now(), 1, "
                + "'https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html', ?)",
                "c".repeat(64));

        exec(c, "insert into advisory(advisory_id, source, title, severity, cvss_score, cve_ids, published_on, "
                + "updated_on, source_url, parse_state, content_sha256) values "
                + "(?, 'checkpoint', 'Synthetic Check Point Gaia Gateway Advisory', 'HIGH', 8.6, "
                + "ARRAY['CVE-2026-99002'], current_date - 12, current_date - 6, "
                + "'https://support.checkpoint.com/results/sk/sk999999', 'PARSED', ?), "
                + "(?, 'paloalto', 'Synthetic PAN-OS Management Plane Advisory', 'CRITICAL', 9.8, "
                + "ARRAY['CVE-2026-99001'], current_date - 10, current_date - 5, "
                + "'https://security.paloaltonetworks.com/CVE-2026-99001', 'PARSED', ?), "
                + "(?, 'fortinet', 'Synthetic FortiOS Advisory', 'HIGH', 8.1, "
                + "ARRAY['CVE-2026-99003'], current_date - 9, current_date - 4, "
                + "'https://www.fortiguard.com/psirt/FG-IR-26-99003', 'PARSED', ?), "
                + "(?, 'cisco', 'Synthetic Cisco ASA Advisory', 'HIGH', 7.8, "
                + "ARRAY['CVE-2026-99004'], current_date - 8, current_date - 3, "
                + "'https://sec.cloudapps.cisco.com/security/center/content/CiscoSecurityAdvisory/cisco-sa-asa-synth-99004', "
                + "'PARSED', ?)",
                cpAdvId, "b".repeat(64),
                panAdvId, "a".repeat(64),
                fgtAdvId, "d".repeat(64),
                ciscoAdvId, "e".repeat(64));

        exec(c, "insert into advisory_product_rule(advisory_id, product_key, train, rule, fixed_text) values "
                + "(?, 'checkpoint:gaia-gateway', 'R81.20', ?, 'R81.20 Jumbo Take 120'), "
                + "(?, 'paloalto:pan-os', '11.1', ?, 'Upgrade to PAN-OS 11.1.10'), "
                + "(?, 'fortinet:fortios', '7.4', ?, 'Upgrade to FortiOS 7.4.9 or above'), "
                + "(?, 'cisco:asa', '9.18', ?, 'Upgrade to Cisco ASA 9.18.4.50')",
                cpAdvId, cpRule,
                panAdvId, panRule,
                fgtAdvId, fgtRule,
                ciscoAdvId, ciscoRule);

        exec(c, "insert into known_exploited_cve(cve_id, date_added, ransomware_use) values "
                + "('CVE-2026-99001', current_date - 7, true)");

        exec(c, "insert into device_live_patch_item(device_id, patch_id, status, mode, pids_running, pids_total, refs) "
                + "values (?, 'HF_SYNTH_01', 'armed', 'user', 2, 2, ARRAY['CVE-2026-99002'])", e.cpA().id());

        ids.put("advisoryId", cpAdvId);
        ids.put("cpAdvisoryId", cpAdvId);
        ids.put("panAdvisoryId", panAdvId);
        ids.put("fgtAdvisoryId", fgtAdvId);
        ids.put("ciscoAdvisoryId", ciscoAdvId);
    }

    /** SNMP metrics (V268): poll state, 36 raw samples over three days and the hourly roll-up of each gateway. */
    private static void seedSnmpMetrics(Connection c, Estate e) throws Exception {
        audited(c, Ui2Rows.ACTOR, () -> {
            for (Device d : List.of(e.cpA(), e.cpB(), e.pan())) {
                exec(c, "insert into snmp_poll_state(device_id, last_outcome, last_poll_at, last_answer_at, next_poll_at, "
                        + "last_sys_uptime_cs) values (?, 'OK', now(), now(), now() + interval '5 minutes', 123456789)", d.id());
                for (int i = 0; i < 12; i++) {
                    for (int day = 0; day < 3; day++) {
                        exec(c, "insert into device_metric_sample(device_id, sampled_at, source, outcome, sys_uptime_cs, rebooted, "
                                + "cpu_pct, cpu_dataplane_pct, mem_pct, sessions) values (?, now() - (? * interval '1 day') "
                                + "- (? * interval '7 minutes') - interval '20 minutes', 'SNMP', 'OK', 1000000, false, ?, ?, ?, ?)",
                                d.id(), day, i, 10 + RANDOM.nextInt(60), d.vendor().equals("palo_alto") ? 5 + RANDOM.nextInt(40) : null,
                                30 + RANDOM.nextInt(50), 1000 + RANDOM.nextInt(9000));
                    }
                }
                exec(c, "insert into device_metric_hourly(device_id, hour_start, samples, answered, cpu_p95, cpu_max, mem_p95, "
                        + "mem_max, sessions_p95, sessions_max, rebooted) values (?, date_trunc('hour', now() - interval '2 hours'), "
                        + "12, 12, 40, 55, 60, 70, 3000, 3500, false)", d.id());
            }
        });
    }

    /**
     * Backup deviation records (V30, V180): two backups of the first member and one open size anomaly whose free-text
     * summary names a host, an address, a serial and the administrator.
     */
    private static void seedBackupDeviation(Connection c, Estate e) throws Exception {
        String previous = UUID.randomUUID().toString();
        String latest = UUID.randomUUID().toString();
        audited(c, Ui2Rows.ACTOR, () -> {
            for (String artefact : List.of(previous, latest)) {
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path, deviation_state, backup_type) "
                        + "values (?, ?, 'backup', 'check_point', 'R81.20', ?, ?, ?, ?, 10, 'k1', ?, "
                        + "'{\"level_reached\":\"V2\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', ?, 'first', 'standard')",
                        artefact, e.cpA().id(), hex(16), "0".repeat(64), artefact.equals(latest) ? 9000L : 3000L, "1".repeat(64),
                        new byte[32], artefact);
            }
            String deviation = UUID.randomUUID().toString();
            exec(c, "insert into backup_deviation_record(deviation_id, artefact_id, previous_artefact_id, device_id, deviation_class, "
                    + "summary, details) values (?, ?, ?, ?, 'size_anomaly', ?, "
                    + "'{\"direction\":\"growth\",\"plaintext_bytes\":9000,\"median_bytes\":3000,\"ratio\":3.0}'::jsonb)",
                    deviation, latest, previous, e.cpA().id(),
                    "backup of " + e.cpA().hostname() + " (" + e.cpA().address() + ", serial " + e.cpA().serial()
                            + ") grew threefold after a change by " + e.principal());
            ids.put("deviationId", deviation);
        });
    }

    /**
     * Palo Alto policy and its stored virtual-system zone set (V229): rules whose from- and to-zones are raw zone names,
     * a later rule on any zone (the stored set makes the analysis decide it), the firewall target with a raw vsys name.
     */
    private static void seedPanPolicy(Connection c, Estate e) throws Exception {
        var object = new PolicySnapshot.PolicyObject(e.objectId(), e.objectName(), "host", List.of(), List.of(e.objectAddress()),
                "RESOLVED");
        Map<String, List<String>> named = Map.of("from", List.of(e.zoneA(), e.zoneB()), "to", List.of(e.zoneA(), e.zoneB()),
                "last-modifier", List.of(e.principal()));
        Map<String, List<String>> any = Map.of("from", List.of("any"), "to", List.of("any"), "last-modifier", List.of(e.principal()));
        var first = new PolicySnapshot.Rule(e.ruleId(), UUID.randomUUID().toString(), 1, e.ruleName(), true,
                new PolicySnapshot.Cell(List.of(e.objectId()), false), new PolicySnapshot.Cell(List.of(e.objectId()), false),
                new PolicySnapshot.Cell(List.of("any-obj"), false), new PolicySnapshot.Cell(List.of("any-obj"), false), "allow", "Log",
                "requested by " + e.principal() + " for " + e.pan().hostname(), named);
        var later = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2, e.shadowedRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.objectId()), false), new PolicySnapshot.Cell(List.of(e.objectId()), false),
                new PolicySnapshot.Cell(List.of("any-obj"), false), new PolicySnapshot.Cell(List.of("any-obj"), false), "allow", "Log",
                "opened by " + e.principal(), any);
        var metadata = new PolicySnapshot.Metadata(e.policyId(), e.panorama().id(), e.panorama().hostname(), "PAN", e.domainId(),
                "shared", e.policyName(), java.time.Instant.now().toString(), null,
                List.of(new PolicySnapshot.Target(e.pan().id(), e.pan().hostname(), e.vsys(), "IN_SYNC")));
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("section-1", "Pre rules", "Network", null, List.of(first, later))),
                Map.of(e.objectId(), object, "any-obj", new PolicySnapshot.PolicyObject("any-obj", "Any", "any", List.of(), List.of(), "RESOLVED")),
                List.of());
        audited(c, Ui2Rows.ACTOR, () -> {
            exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                    e.policyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
            exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, collected_at, "
                    + "changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now(), '2026-11-20T09:00:00Z', ?, ?::jsonb)",
                    e.policyId(), e.ruleId(), e.ruleId(), e.principal(),
                    JSON.writeValueAsString(List.of(Map.of("field", "from", "before", e.zoneA(), "after", e.zoneB()))));
            exec(c, "insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at) values (?, ?, ?, now())",
                    e.pan().id(), e.vsys(), c.createArrayOf("text", new String[] {e.zoneA(), e.zoneB()}));
        });
    }

    /**
     * Cisco ASA local-snapshot policy mapped via {@link AsaPolicyMapper}: carries {@code asa-interfaces}, a shadowed
     * inbound ACE with a remark naming raw actors/hosts, and an unbound ACL in section {@code "ASA not evaluated"}
     * carrying the raw ACL name in {@code Rule.name} and {@code asa-acl-use=["none"]} ({@link PolicyHygiene#ASA_ACL_NOT_REFERENCED}).
     */
    private static void seedAsaPolicy(Connection c, Estate e) throws Exception {
        String asaConfig = "ASA Version 9.18(4)22\n"
                + "hostname " + e.asa().hostname() + "\n"
                + "interface GigabitEthernet0/0\n"
                + " nameif " + e.asaOutsideIf() + "\n"
                + " security-level 0\n"
                + " ip address " + e.asaIfAddress() + " 255.255.255.0\n"
                + "interface GigabitEthernet0/1\n"
                + " nameif " + e.asaInsideIf() + "\n"
                + " security-level 100\n"
                + "object network " + e.asaHostName() + "\n"
                + " host " + e.asaHostAddress() + "\n"
                + "access-list " + e.asaBoundAcl() + " remark approved by " + e.principal() + " for " + e.asa().hostname() + "\n"
                + "access-list " + e.asaBoundAcl() + " extended permit tcp any object " + e.asaHostName() + " eq 443 log\n"
                + "access-list " + e.asaBoundAcl() + " extended permit tcp any object " + e.asaHostName() + " eq 443 log\n"
                + "access-list " + e.asaUnboundAcl() + " extended permit ip any any\n"
                + "access-group " + e.asaBoundAcl() + " in interface " + e.asaOutsideIf() + "\n";
        PolicySnapshot snapshot = AsaPolicyMapper.map(e.asa().id(), e.asa().hostname(), java.time.Instant.now().toString(),
                null, AsaConfigPolicyParser.parse(asaConfig));
        var boundSection = snapshot.sections().stream().filter(s -> "ASA access-group".equals(s.source())).findFirst().orElseThrow();
        var notEvaluated = snapshot.sections().stream().filter(s -> "ASA not evaluated".equals(s.name())).findFirst().orElseThrow();
        var unboundRule = notEvaluated.rules().stream().filter(r -> e.asaUnboundAcl().equals(r.name())).findFirst().orElseThrow();
        ids.put("asaPolicyId", snapshot.metadata().id());
        ids.put("asaRuleId", boundSection.rules().get(0).id());
        ids.put("asaUnboundRuleId", unboundRule.id());
        audited(c, Ui2Rows.ACTOR, () -> exec(c,
                "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                snapshot.metadata().id(), JSON.writeValueAsString(snapshot.metadata()), JSON.writeValueAsString(snapshot)));
    }

    /**
     * FortiGate local-snapshot policy mapped via {@link FortiGatePolicyMapper}: carries {@code fgt-interfaces},
     * {@code srcaddr}/{@code dstaddr} objects, and shadowed firewall policies with raw rule names, comments, and
     * {@code last-modifier}.
     */
    private static void seedFgtPolicy(Connection c, Estate e) throws Exception {
        String fgtConfig = "#config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin\n"
                + "config system global\n"
                + "    set hostname \"" + e.fgt().hostname() + "\"\n"
                + "end\n"
                + "config system interface\n"
                + "    edit \"" + e.fgtPortA() + "\"\n"
                + "        set vdom \"root\"\n"
                + "        set ip " + e.fgtIfAddress() + " 255.255.255.0\n"
                + "    next\n"
                + "    edit \"" + e.fgtPortB() + "\"\n"
                + "        set vdom \"root\"\n"
                + "    next\n"
                + "end\n"
                + "config firewall address\n"
                + "    edit \"" + e.fgtSrcObjName() + "\"\n"
                + "        set subnet " + e.fgtSrcAddress() + " 255.255.255.255\n"
                + "    next\n"
                + "    edit \"" + e.fgtDstObjName() + "\"\n"
                + "        set subnet " + e.fgtDstAddress() + " 255.255.255.255\n"
                + "    next\n"
                + "end\n"
                + "config firewall service custom\n"
                + "    edit \"HTTPS\"\n"
                + "        set tcp-portrange 443\n"
                + "    next\n"
                + "end\n"
                + "config firewall policy\n"
                + "    edit 1\n"
                + "        set name \"" + e.fgtRuleName() + "\"\n"
                + "        set srcintf \"" + e.fgtPortA() + "\"\n"
                + "        set dstintf \"" + e.fgtPortB() + "\"\n"
                + "        set srcaddr \"" + e.fgtSrcObjName() + "\"\n"
                + "        set dstaddr \"" + e.fgtDstObjName() + "\"\n"
                + "        set action accept\n"
                + "        set schedule \"always\"\n"
                + "        set service \"HTTPS\"\n"
                + "        set logtraffic all\n"
                + "        set comments \"approved by " + e.fgtModifier() + " on " + e.fgt().hostname() + "\"\n"
                + "    next\n"
                + "    edit 2\n"
                + "        set name \"" + e.fgtShadowedRuleName() + "\"\n"
                + "        set srcintf \"" + e.fgtPortA() + "\"\n"
                + "        set dstintf \"" + e.fgtPortB() + "\"\n"
                + "        set srcaddr \"" + e.fgtSrcObjName() + "\"\n"
                + "        set dstaddr \"" + e.fgtDstObjName() + "\"\n"
                + "        set action accept\n"
                + "        set schedule \"always\"\n"
                + "        set service \"HTTPS\"\n"
                + "        set logtraffic all\n"
                + "        set comments \"shadowed policy by " + e.fgtModifier() + "\"\n"
                + "    next\n"
                + "end\n";
        PolicySnapshot mapped = FortiGatePolicyMapper.map(e.fgt().id(), e.fgt().hostname(),
                java.time.Instant.now().toString(), null, FortiGateConfigPolicyParser.parse(fgtConfig)).get(0);
        List<PolicySnapshot.Section> sections = new ArrayList<>(mapped.sections().size());
        for (PolicySnapshot.Section s : mapped.sections()) {
            if ("FGT firewall policy".equals(s.source())) {
                List<PolicySnapshot.Rule> rules = new ArrayList<>(s.rules().size());
                for (PolicySnapshot.Rule r : s.rules()) {
                    Map<String, List<String>> extras = new LinkedHashMap<>(r.extras());
                    extras.put("last-modifier", List.of(e.fgtModifier()));
                    rules.add(new PolicySnapshot.Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(),
                            r.source(), r.destination(), r.service(), r.application(), r.action(), r.log(), r.comment(), extras));
                }
                sections.add(new PolicySnapshot.Section(s.id(), s.name(), s.source(), s.parentRuleId(), rules));
            } else {
                sections.add(s);
            }
        }
        PolicySnapshot snapshot = new PolicySnapshot(mapped.metadata(), sections, mapped.objects(), mapped.failures());
        var policySection = snapshot.sections().stream().filter(s -> "FGT firewall policy".equals(s.source())).findFirst().orElseThrow();
        ids.put("fgtPolicyId", snapshot.metadata().id());
        ids.put("fgtRuleId", policySection.rules().get(0).id());
        ids.put("fgtShadowRuleId", policySection.rules().get(1).id());
        audited(c, Ui2Rows.ACTOR, () -> exec(c,
                "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                snapshot.metadata().id(), JSON.writeValueAsString(snapshot.metadata()), JSON.writeValueAsString(snapshot)));
    }

    /** Configuration-override notifications (V16): the summary and the paths are free text naming raw values. */
    private static void seedNotifications(Connection c, Estate e) throws Exception {
        String job = Ui2Rows.opaqueId("job");
        String artefactRef = Ui2Rows.opaqueId("cfgart");
        String run = Ui2Rows.opaqueId("cfgrun");
        String notification = Ui2Rows.opaqueId("note");
        audited(c, Ui2Rows.ACTOR, () -> {
            exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, submitted_by_actor_fingerprint, "
                    + "state, action_class, finished_at) values (?, 'configuration_collect', 'device', ?, ?, 'COMPLETED', 'read', now())",
                    job, e.pan().id(), Ui2Rows.ACTOR);
            exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, plaintext_bytes, "
                    + "ciphertext_sha256, ciphertext_bytes, compression, key_id) values (?, ?, ?, 'palo_alto', ?, 10, ?, 10, 'none', 'k1')",
                    artefactRef, e.pan().id(), job, "0".repeat(64), "1".repeat(64));
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, is_primary, "
                    + "canonical_hash, raw_hash, raw_bytes, artefact_ref, change_state) values (?, ?, ?, now(), 'palo_alto', "
                    + "'effective_running', true, ?, ?, 10, ?, 'first_run')",
                    run, e.pan().id(), job, "2".repeat(64), "3".repeat(64), artefactRef);
            exec(c, "insert into configuration_notification(notification_id, device_id, run_id, kind, summary, override_paths) "
                    + "values (?, ?, ?, 'configuration_override_detected', ?, ?)",
                    notification, e.pan().id(), run,
                    "override on " + e.pan().hostname() + " (" + e.pan().address() + ", serial " + e.pan().serial()
                            + ") set by " + e.principal(),
                    "address/address/" + e.objectName() + "\nzone/zone/" + e.zoneA() + "\nzone/zone/" + e.zoneB());
        });
        ids.put("notificationId", notification);
    }

    /**
     * Notification settings and routes (V100 and later: syslog and mail hosts, sender, recipients) and audit rows whose
     * actor is a raw principal and whose target text is a raw host and a raw address. Their routes are
     * {@code security_admin} reads; the audit rows are written last so they stay inside the route's newest-100 window.
     */
    private static void seedAuditAndSettings(Connection c, Estate e) throws Exception {
        audited(c, e.principal(), () -> {
            exec(c, "update notification_settings set syslog_enabled = true, syslog_host = ?, smtp_enabled = true, smtp_host = ?, "
                    + "smtp_from = ?, smtp_to = ?, updated_at = now(), updated_by = ? where settings_id = 'default'",
                    e.syslogHost(), e.smtpHost(), e.smtpFrom(), e.smtpTo(), e.principal());
            exec(c, "update notification_routes set enabled = true, recipients = ?, updated_at = now(), updated_by = ? "
                    + "where type = 'admin_event'", e.routeRecipients(), e.principal());
            // an audited table whose primary key text is the raw host / address: the audit row's target is that key
            for (String key : List.of(e.auditTargetHost(), e.auditTargetAddress())) {
                exec(c, "insert into credential_references(credential_reference_id, purpose, backend_pointer) "
                        + "values (?, 'harness', 'vault://harness/opaque')", key);
            }
        });
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

    // ------------------------------------------------------------------ leak counting (copied from the other sweep)

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

    // ------------------------------------------------------------------ path variables

    private static final String UNKNOWN_ID = "00000000-0000-4000-8000-0000000000aa";

    static String target(String template) {
        Estate e = estate;
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("deviceId", e.cpA().id());
        byName.put("clusterMemberRef", e.cpA().cluster());
        byName.put("clusterRef", e.cpA().cluster());
        byName.put("clusterId", e.cpA().cluster());
        if (template.startsWith("/api/v2/lifecycle/advisories/")) byName.put("id", ids.get("advisoryId"));
        if (template.startsWith("/api/v2/policy/policies/")) byName.put("id", e.policyId());
        if (template.startsWith("/api/v2/policy/devices/")) byName.put("id", e.panorama().id());
        if (template.startsWith("/api/v2/policy/domains/")) byName.put("id", e.domainId());
        if (template.startsWith("/api/v2/policy/objects/")) byName.put("id", e.objectId());
        if (template.startsWith("/devices/{id}")) byName.put("id", e.cpA().id());
        Matcher m = Pattern.compile("\\{([^}/:]+)(?::[^}]*)?}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = byName.getOrDefault(m.group(1), UNKNOWN_ID);
            m.appendReplacement(out, Matcher.quoteReplacement(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(out);
        String query = QUERY.get(template);
        return query == null ? out.toString() : out + "?" + query.replace("{source}", e.panorama().id())
                .replace("{rule}", e.ruleId()).replace("{object}", e.objectId()).replace("{device}", e.cpA().id())
                .replace("{policy}", e.policyId())
                .replace("{cluster}", java.net.URLEncoder.encode(e.cpA().cluster(), StandardCharsets.UTF_8));
    }

    private static final Map<String, String> QUERY = Map.ofEntries(
            Map.entry("/api/v2/policy/policies/{id}/rules", "ruleId={rule}"),
            Map.entry("/api/v2/policy/policies/{id}/history", "rule={rule}"),
            Map.entry("/api/v2/policy/objects/{id}", "device={device}&policy={policy}"),
            Map.entry("/api/v2/policy/domains/{id}/objects", "source={source}"),
            Map.entry("/api/v2/policy/domains/{id}/object-usage", "source={source}&uid={object}"),
            Map.entry("/api/v2/policy/domains/{id}/objects/duplicates", "source={source}"),
            Map.entry("/api/v2/policy/domains/{id}/installation", "source={source}"),
            Map.entry("/api/v2/policy/domains/{id}/unused", "source={source}"),
            Map.entry("/api/v2/policy/domains/{id}/gateways", "source={source}"),
            Map.entry("/api/v2/policy/domains/{id}/hits", "source={source}"),
            Map.entry("/api/v2/system-logs/gaps", "deviceId={device}"),
            Map.entry("/api/v2/log-findings/coverage", "device_id={device}"),
            Map.entry("/api/v2/policy/changes/actor-candidates", "revision=1"),
            Map.entry("/api/v2/diagnostics/ports", "device_id={device}"),
            Map.entry("/api/v2/estate/as-of", "at=2026-11-23T00:00:00Z"),
            Map.entry("/api/v2/system-logs/crash-files", "deviceId={device}"));

    // ------------------------------------------------------------------ HTTP (copied from the other sweep)

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Per-request timeout: 30 s by default; the shared HOST-A runner sets UI2_TEST_HTTP_TIMEOUT_S (30..600). */
    private static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String body) {
    }

    private static Resp get(String target, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    private static synchronized String login() throws Exception {
        if (sessionCookie != null) return sessionCookie;
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
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

    // ------------------------------------------------------------------ the sweep

    @Test
    void noPlantedRawValueSurvivesInTheUnseededPlanes() throws Exception {
        String cookie = login();
        Estate e = estate;

        Map<String, String> templates = new TreeMap<>();
        Map<String, Boolean> reachable = new TreeMap<>();
        for (var ep : AiviewReachableRoutes.all()) {
            if (!ep.method().equals("GET")) continue;
            templates.putIfAbsent(ep.key(), ep.pathTemplate());
            boolean may = ep.actionId() != null && AiviewReachableRoutes.aiviewMayCall(ep.actionId());
            reachable.merge(ep.key(), may, (a, b) -> a || b);
        }
        for (String refused : MUST_BE_REFUSED) {
            assertTrue(templates.containsKey(refused), "the route exists: " + refused);
            assertTrue(!reachable.get(refused), "the static audit says aiview cannot reach " + refused);
        }

        Map<String, String> markers = new LinkedHashMap<>();
        for (Device d : e.devices()) markers.put(d.id(), "device");
        markers.put(ids.get("licenseMarker"), "license-item");
        markers.put(e.catalogProduct(), "catalog-entry");
        markers.put(ids.get("uploadId"), "catalog-upload");
        markers.put(ids.get("advisoryId"), "advisory");
        markers.put("\"affected\":2", "advisory-affected-count");
        markers.put("\"verdict\":\"AFFECTED\"", "advisory-affected-verdict");
        markers.put("\"outcome\":\"OK\"", "advisory-feed-run");
        markers.put("\"age_state\":\"OK\"", "advisory-source-fresh");
        markers.put("\"state\":\"EVALUATED\"", "vulnerabilities-evaluated");
        markers.put(ids.get("notificationId"), "notification");
        markers.put(ids.get("deviationId"), "size-anomaly");
        markers.put("\"source\":\"SNMP\"", "snmp-metrics");
        markers.put(e.policyId(), "pan-policy");
        markers.put(e.ruleId(), "pan-rule");
        markers.put("\"row_id\":\"pan:" + e.policyId() + ":", "policy-install-row");
        // the later any-zone rule is REDUNDANT only because the stored zone set of the firewall proves the earlier rule covers it
        markers.put("\"shadowStatus\":\"REDUNDANT\"", "pan-zone-verdict");

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, Integer> status = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int total = 0;
        // The planes' own routes go first: free-text masking learns names as responses mask them, so a plane read later
        // in a warm process proves less than the first read after the estate loaded (the cold case).
        List<Map.Entry<String, String>> ordered = new ArrayList<>();
        List<String> coldFirst = List.of(
                "GET /notifications",
                "GET /api/v2/operations/policy-install/status",
                "GET /api/v2/lifecycle/advisories",
                "GET /api/v2/lifecycle/advisories/*",
                "GET /api/v2/lifecycle/advisory-sources");
        for (String first : coldFirst) {
            ordered.add(Map.entry(first, templates.get(first)));
        }
        templates.forEach((key, template) -> { if (!coldFirst.contains(key)) ordered.add(Map.entry(key, template)); });
        for (var entry : ordered) {
            Resp resp = get(target(entry.getValue()), cookie);
            status.put(entry.getKey(), resp.status());
            Map<ValueClass, Integer> found = count(resp.body(), e.raw());
            if (!found.isEmpty()) leaks.put(entry.getKey(), found);
            Set<String> kinds = new TreeSet<>();
            markers.forEach((marker, what) -> { if (resp.body().contains(marker)) kinds.add(what); });
            seen.put(entry.getKey(), kinds);
            int sum = found.values().stream().mapToInt(Integer::intValue).sum();
            total += sum;
            report.add(String.format(Locale.ROOT, "AIVIEW_UNSEEDED %-10s %3d %-62s raw=%s seeded_rows_seen=%s",
                    reachable.get(entry.getKey()) ? "reachable" : "gated", resp.status(), entry.getKey(),
                    found.isEmpty() ? "0" : found.toString(), kinds));
        }
        report.forEach(System.out::println);
        System.out.println("AIVIEW_UNSEEDED_SUMMARY routes=" + templates.size() + " planted_values=" + e.raw().size()
                + " surviving_raw_occurrences=" + total);

        List<String> uncovered = new ArrayList<>();
        COVERAGE.forEach((route, expected) -> {
            if (!Integer.valueOf(200).equals(status.get(route))
                    || !seen.getOrDefault(route, Set.of()).containsAll(expected)) {
                uncovered.add(route + " expected 200+" + expected + " saw " + seen.get(route) + " status " + status.get(route));
            }
        });
        assertEquals(List.of(), uncovered, "seeded rows must reach these views, or a zero count proves nothing");
        for (String refused : MUST_BE_REFUSED) {
            int code = status.get(refused);
            assertTrue(code == 401 || code == 403, refused + " must refuse aiview, answered " + code);
        }
        Set<String> observed = new TreeSet<>();
        leaks.forEach((route, classes) -> classes.keySet().forEach(cls -> observed.add(route + " " + cls)));
        assertEquals(new TreeSet<>(KNOWN_LEAKS), observed,
                "planted raw values surviving in aiview GET bodies (route + value class); KNOWN_LEAKS must match exactly");
    }

    @Test
    void asaAndFortiGatePolicyViewsAndHygieneCsvLeakZeroRawValuesInAiview() throws Exception {
        String cookie = login();
        Estate e = estate;
        String asaPolicyId = ids.get("asaPolicyId");
        String asaRuleId = ids.get("asaRuleId");
        String asaUnboundRuleId = ids.get("asaUnboundRuleId");
        String fgtPolicyId = ids.get("fgtPolicyId");
        String fgtRuleId = ids.get("fgtRuleId");
        String fgtShadowRuleId = ids.get("fgtShadowRuleId");

        Resp asaPage = get("/api/v2/policy/policies/" + asaPolicyId, cookie);
        assertEquals(200, asaPage.status(), "ASA policy page");
        assertTrue(asaPage.body().contains(asaPolicyId) && asaPage.body().contains(asaRuleId)
                && asaPage.body().contains(asaUnboundRuleId) && asaPage.body().contains("\"ASA not evaluated\""),
                "ASA policy page must include seeded rules and not-evaluated section");
        assertTrue(count(asaPage.body(), e.raw()).isEmpty(),
                "ASA policy page leaked raw classes: " + count(asaPage.body(), e.raw()));

        Resp asaRule = get("/api/v2/policy/policies/" + asaPolicyId + "/rules?ruleId="
                + java.net.URLEncoder.encode(asaUnboundRuleId, StandardCharsets.UTF_8), cookie);
        assertEquals(200, asaRule.status(), "ASA unbound ACL rule detail");
        assertTrue(asaRule.body().contains(asaUnboundRuleId)
                && asaRule.body().contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED),
                "ASA unbound ACL rule detail must include ASA_ACL_NOT_REFERENCED");
        assertTrue(count(asaRule.body(), e.raw()).isEmpty(),
                "ASA rule detail leaked raw classes: " + count(asaRule.body(), e.raw()));

        Resp asaHygiene = get("/api/v2/policy/policies/" + asaPolicyId + "/hygiene", cookie);
        assertEquals(200, asaHygiene.status(), "ASA hygiene");
        assertTrue(asaHygiene.body().contains(asaUnboundRuleId)
                && asaHygiene.body().contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED)
                && asaHygiene.body().contains("\"shadowStatus\":\"REDUNDANT\"")
                && asaHygiene.body().contains(asaRuleId),
                "ASA hygiene must include ASA_ACL_NOT_REFERENCED and REDUNDANT verdict");
        assertTrue(count(asaHygiene.body(), e.raw()).isEmpty(),
                "ASA hygiene leaked raw classes: " + count(asaHygiene.body(), e.raw()));

        Resp asaCsv = get("/api/v2/policy/policies/" + asaPolicyId + "/hygiene.csv", cookie);
        assertEquals(200, asaCsv.status(), "ASA hygiene CSV");
        assertTrue(asaCsv.body().contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED) && asaCsv.body().contains(asaRuleId),
                "ASA hygiene CSV must include ASA_ACL_NOT_REFERENCED and covering rule ID");
        assertTrue(count(asaCsv.body(), e.raw()).isEmpty(),
                "ASA hygiene CSV leaked raw classes: " + count(asaCsv.body(), e.raw()));

        Resp fgtPage = get("/api/v2/policy/policies/" + fgtPolicyId, cookie);
        assertEquals(200, fgtPage.status(), "FortiGate policy page");
        assertTrue(fgtPage.body().contains(fgtPolicyId) && fgtPage.body().contains(fgtRuleId)
                && fgtPage.body().contains(fgtShadowRuleId) && fgtPage.body().contains("\"FGT implicit deny\""),
                "FortiGate policy page must include seeded rules and implicit deny section");
        assertTrue(count(fgtPage.body(), e.raw()).isEmpty(),
                "FortiGate policy page leaked raw classes: " + count(fgtPage.body(), e.raw()));

        Resp fgtRule = get("/api/v2/policy/policies/" + fgtPolicyId + "/rules?ruleId="
                + java.net.URLEncoder.encode(fgtShadowRuleId, StandardCharsets.UTF_8), cookie);
        assertEquals(200, fgtRule.status(), "FortiGate shadowed rule detail");
        assertTrue(fgtRule.body().contains(fgtShadowRuleId)
                && fgtRule.body().contains("\"shadowStatus\":\"REDUNDANT\"")
                && fgtRule.body().contains(fgtRuleId),
                "FortiGate shadowed rule detail must include REDUNDANT and covering rule ID");
        assertTrue(count(fgtRule.body(), e.raw()).isEmpty(),
                "FortiGate rule detail leaked raw classes: " + count(fgtRule.body(), e.raw()));

        Resp fgtHygiene = get("/api/v2/policy/policies/" + fgtPolicyId + "/hygiene", cookie);
        assertEquals(200, fgtHygiene.status(), "FortiGate hygiene");
        assertTrue(fgtHygiene.body().contains(fgtShadowRuleId)
                && fgtHygiene.body().contains("\"shadowStatus\":\"REDUNDANT\"")
                && fgtHygiene.body().contains(fgtRuleId),
                "FortiGate hygiene must include REDUNDANT and covering rule ID");
        assertTrue(count(fgtHygiene.body(), e.raw()).isEmpty(),
                "FortiGate hygiene leaked raw classes: " + count(fgtHygiene.body(), e.raw()));

        Resp fgtCsv = get("/api/v2/policy/policies/" + fgtPolicyId + "/hygiene.csv", cookie);
        assertEquals(200, fgtCsv.status(), "FortiGate hygiene CSV");
        assertTrue(fgtCsv.body().contains("\"shadowed\"")
                && fgtCsv.body().contains("Earlier enabled rule fully covers this match with the same action.")
                && fgtCsv.body().contains(fgtRuleId),
                "FortiGate hygiene CSV must include redundant shadow finding and covering rule ID");
        assertTrue(count(fgtCsv.body(), e.raw()).isEmpty(),
                "FortiGate hygiene CSV leaked raw classes: " + count(fgtCsv.body(), e.raw()));
    }

    @Test
    void allSeededVendorAdvisoriesAndLifecycleVulnerabilitiesLeakZeroRawValuesInAiview() throws Exception {
        String cookie = login();
        Estate e = estate;

        Resp list = get("/api/v2/lifecycle/advisories", cookie);
        assertEquals(200, list.status(), "advisories list");
        for (String key : List.of("cpAdvisoryId", "panAdvisoryId", "fgtAdvisoryId", "ciscoAdvisoryId")) {
            String advId = ids.get(key);
            assertTrue(list.body().contains(advId), "advisories list must include " + advId);
        }
        assertTrue(list.body().contains("\"exploited\":true"), "KEV-flagged advisory must report exploited=true");
        assertTrue(count(list.body(), e.raw()).isEmpty(),
                "advisories list leaked raw classes: " + count(list.body(), e.raw()));

        Map<String, String> expectedVersion = Map.of(
                "cpAdvisoryId", "R81.20 Jumbo Take 119",
                "panAdvisoryId", "11.1.4-h1",
                "fgtAdvisoryId", "v7.4.8 build2795",
                "ciscoAdvisoryId", "9.18(4)22");
        for (var entry : expectedVersion.entrySet()) {
            String advId = ids.get(entry.getKey());
            Resp detail = get("/api/v2/lifecycle/advisories/"
                    + java.net.URLEncoder.encode(advId, StandardCharsets.UTF_8), cookie);
            assertEquals(200, detail.status(), "advisory detail " + advId);
            var root = JSON.readTree(detail.body());
            assertEquals(advId, root.path("advisory").path("advisory_id").asText(), "advisory_id");
            assertTrue(root.path("advisory").path("source_url").asText("").startsWith("https://"),
                    "allowed https source_url preserved for " + advId);
            var devices = root.path("devices");
            assertTrue(devices.isArray() && !devices.isEmpty(), "affected devices present for " + advId);
            boolean sawAffected = false;
            for (var d : devices) {
                assertTrue(d.path("hostname").asText("").startsWith("FW-"),
                        "advisory detail device hostname pseudonymized for " + advId);
                assertEquals(entry.getValue(), d.path("version").asText(),
                        "product version preserved intact for " + advId);
                if ("AFFECTED".equals(d.path("verdict").asText())) sawAffected = true;
            }
            assertTrue(sawAffected, "at least one AFFECTED verdict for " + advId);
            if ("cpAdvisoryId".equals(entry.getKey())) {
                assertTrue(detail.body().contains("\"patch\":\"HF_SYNTH_01\""),
                        "Check Point advisory detail must include live_patch label");
            }
            assertTrue(count(detail.body(), e.raw()).isEmpty(),
                    "advisory detail " + advId + " leaked raw classes: " + count(detail.body(), e.raw()));
        }

        Resp sources = get("/api/v2/lifecycle/advisory-sources", cookie);
        assertEquals(200, sources.status(), "advisory sources");
        var srcArr = JSON.readTree(sources.body()).path("sources");
        assertEquals(5, srcArr.size(), "all five advisory sources returned");
        for (var s : srcArr) {
            assertEquals("OK", s.path("outcome").asText(), "source outcome OK for " + s.path("source").asText());
            assertEquals("OK", s.path("age_state").asText(), "source age_state OK for " + s.path("source").asText());
        }
        assertTrue(count(sources.body(), e.raw()).isEmpty(),
                "advisory sources leaked raw classes: " + count(sources.body(), e.raw()));

        Resp fleetLifecycle = get("/api/v2/lifecycle", cookie);
        assertEquals(200, fleetLifecycle.status(), "fleet lifecycle");
        assertTrue(fleetLifecycle.body().contains("\"vulnerabilities\"")
                && fleetLifecycle.body().contains("\"state\":\"EVALUATED\""),
                "fleet lifecycle must include evaluated vulnerabilities block");
        assertTrue(count(fleetLifecycle.body(), e.raw()).isEmpty(),
                "fleet lifecycle leaked raw classes: " + count(fleetLifecycle.body(), e.raw()));

        Resp devLifecycle = get("/devices/" + e.cpA().id() + "/lifecycle", cookie);
        assertEquals(200, devLifecycle.status(), "device lifecycle");
        var devVuln = JSON.readTree(devLifecycle.body()).path("vulnerabilities");
        assertEquals("EVALUATED", devVuln.path("state").asText(), "cpA vulnerabilities state EVALUATED");
        assertEquals(1, devVuln.path("affected").asInt(), "cpA affected advisory count");
        assertTrue(count(devLifecycle.body(), e.raw()).isEmpty(),
                "device lifecycle leaked raw classes: " + count(devLifecycle.body(), e.raw()));
    }

    /** The counter itself: an address is not matched as the prefix of a longer address, names match case-insensitively. */
    @Test
    void theCounterSeesSpellingsAndNoPrefixMatch() {
        Map<String, ValueClass> raw = Map.of("192.0.2.15", ValueClass.ADDRESS, "zn-x0a0b0c-core", ValueClass.ZONE_NAME);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ZONE_NAME, 1), count("zone ZN-X0A0B0C-CORE", raw));
        assertEquals(Map.of(), count("{\"zone\":\"zone-04\"}", raw));
    }
}
