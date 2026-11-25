package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportRunStore;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore;
import com.securityexpert.nexus.ui2.persistence.report.ReportRunStore.RunRow;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.compliance.ComplianceService;
import com.securityexpert.nexus.ui2.service.compliance.JooqComplianceEvaluationStore;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;
import com.securityexpert.nexus.ui2.service.privacy.FreeTextEstate;
import com.securityexpert.nexus.ui2.service.privacy.MaskingDeviceDirectory;
import com.securityexpert.nexus.ui2.service.privacy.PseudonymRegistry;
import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.report.CsvReportRenderer;
import com.securityexpert.nexus.ui2.service.report.PdfReportRenderer;
import com.securityexpert.nexus.ui2.service.report.ProjectionMode;
import com.securityexpert.nexus.ui2.service.report.ReportArtefactWriter;
import com.securityexpert.nexus.ui2.service.report.ReportDocument;
import com.securityexpert.nexus.ui2.service.report.ReportDocumentMasker;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.CellKind;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Column;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Kpi;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.PlaneAsOf;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;
import com.securityexpert.nexus.ui2.service.report.ReportFormat;
import com.securityexpert.nexus.ui2.service.report.ReportRenderer;
import com.securityexpert.nexus.ui2.service.report.XlsxReportRenderer;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 5 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection & post-parsing
 * masking law", {@code docs/design/REPORTS_R1_CONTRACT.md} A1/A2): seeds real rows and artefacts backing six
 * identity-bearing GET planes that earlier synthetic rounds left empty or 404:
 * <ol>
 *   <li>Reports R1 ({@code /api/v2/reports/catalogue}, {@code /api/v2/reports/runs}, {@code /api/v2/reports/runs/{id}},
 *       {@code /api/v2/reports/runs/{id}/download} across CSV, XLSX and PDF, plus the {@code RAW} run boundary).</li>
 *   <li>Compliance ({@code /compliance}, {@code /compliance/overview}, {@code /compliance/controls},
 *       {@code /api/v2/compliance/controls/{id}}, {@code /devices/{id}/compliance}).</li>
 *   <li>HTTPS certificate &amp; SSH trust ({@code /devices/{id}/https-certificate}, {@code /api/v2/ssh-trust}).</li>
 *   <li>Software version history &amp; install events ({@code /devices/{id}/software-history},
 *       {@code /api/v2/software-history/changes}).</li>
 *   <li>Change feed ({@code /api/v2/changes/feed}).</li>
 *   <li>Global search ({@code /api/v2/search?q=...} for both raw substrings and masked pseudonyms).</li>
 * </ol>
 *
 * <p>Every synthetic raw identity value is generated at runtime across all identity classes; assertions and diagnostic
 * lines report route, status and value-class counts only -- never a raw value.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiviewReportsAndComplianceSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter PAN_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter CPWD_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Locale.ENGLISH);

    /** Identity class of a planted raw value; the only thing about a value this test ever reports. */
    enum ValueClass {
        HOSTNAME,
        CLUSTER_NAME,
        DOMAIN_OR_CONTEXT,
        ADDRESS,
        SERIAL,
        PRINCIPAL,
        CERT_SUBJECT_OR_SAN,
        SSH_KEY_COMMENT,
        INSTALL_LOG_FILENAME,
        POLICY_OR_OBJECT_NAME
    }

    /** Rules fingerprint matching the local compliance stub's /healthz response. */
    private static final String RULES_FINGERPRINT = "r5-1|3|{check_point=2, palo_alto=1}";

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static HttpServer complianceStub;
    private static int compliancePort;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static SeededEstate estate;
    private static final Map<String, String> ids = new TreeMap<>();
    private static final Map<ReportFormat, byte[]> rawReportBytes = new TreeMap<>();
    private static String maskedSearchUsername;
    private static char[] maskedSearchPassword;

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_r5_sweep");
        work = Files.createTempDirectory("ui2-aiview-r5-sweep-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));

        estate = SeededEstate.generate();
        complianceStub = startComplianceCatalogStub();
        compliancePort = complianceStub.getAddress().getPort();
        seedDatabase(estate);

        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(AiviewReportsAndComplianceSweepTest::customizeBeans))
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

        seedReportRuns(estate);
        seedMaskedSearchAccount();
    }

    private static void customizeBeans(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess", new DeviceCompositionConfiguration.ArtefactStoreAccess(store));
        registry.removeBeanDefinition("machineConnector");
        beanFactory.addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("complianceService".equals(beanName)) {
                    return new ComplianceService(
                            beanFactory.getBean(ConfigurationQueryService.class),
                            beanFactory.getBean(DeviceRepository.class),
                            "http://127.0.0.1:" + compliancePort,
                            new JooqComplianceEvaluationStore(beanFactory.getBean(TransactionBoundary.class)));
                }
                return bean;
            }
        });
    }

    @AfterAll
    static void shutDown() throws IOException {
        if (context != null) context.close();
        if (complianceStub != null) complianceStub.stop(0);
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

    // ------------------------------------------------------------------ local compliance catalog stub

    private static HttpServer startComplianceCatalogStub() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("catalog_version", "r5-1");
        health.put("controls_count", 3);
        health.put("controls_by_vendor", Map.of("check_point", 2, "palo_alto", 1));
        byte[] healthBytes = JSON.writeValueAsBytes(health);

        List<Map<String, Object>> controls = List.of(
                catalogControl("CP-SYN-01", "NTP servers match approved baseline", "CRITICAL", "check_point",
                        "gaia.ntp.server", Map.of("op", "equals", "targetString", "approved-ntp"),
                        List.of(Map.of("framework", "CIS", "clauseId", "1.1"),
                                Map.of("framework", "NIST_800_53", "clauseId", "AU-8"))),
                catalogControl("CP-SYN-02", "Primary DNS resolver is reachable", "HIGH", "check_point",
                        "gaia.dns.primary", Map.of("op", "present"),
                        List.of(Map.of("framework", "PCI_DSS", "clauseId", "2.2.4"),
                                Map.of("framework", "NIST_800_53", "clauseId", "SC-20"))),
                catalogControl("PAN-SYN-01", "Management plane SSH key comment policy", "CRITICAL", "palo_alto",
                        "panos.deviceconfig.system.ssh", Map.of("op", "present"),
                        List.of(Map.of("framework", "FINANCIAL_BASELINE", "clauseId", "FB-3.1"),
                                Map.of("framework", "CIS", "clauseId", "1.4"))));
        byte[] catalogBytes = JSON.writeValueAsBytes(Map.of("version", "r5-1", "count", controls.size(), "controls", controls));

        server.createContext("/healthz", ex -> respondJson(ex, 200, healthBytes));
        server.createContext("/api/v1/compliance/catalog", ex -> respondJson(ex, 200, catalogBytes));
        server.start();
        return server;
    }

    private static Map<String, Object> catalogControl(String id, String title, String severity, String vendor,
            String settingKey, Map<String, Object> assertion, List<Map<String, Object>> frameworks) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("vendor", vendor);
        binding.put("evidenceRequirement", Map.of("settingKey", settingKey));
        binding.put("assertion", assertion);
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("id", id);
        control.put("title", title);
        control.put("rationale", "Synthetic benchmark check for " + id);
        control.put("severity", severity);
        control.put("category", "System Hardening");
        control.put("frameworks", frameworks);
        control.put("bindings", List.of(binding));
        return control;
    }

    private static void respondJson(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ------------------------------------------------------------------ synthetic runtime-built estate

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String tag() {
        return "x" + hex(3);
    }

    private static String digits(int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(RANDOM.nextInt(10));
        return out.toString();
    }

    record Device(String id, String vendor, String role, String hostname, String serial, String managementAddress,
            String interfaceAddress, String cluster) {
    }

    record SeededEstate(Device cpMemberA, Device cpMemberB, Device panFirewall, Device management, String cluster,
            String domainOrContext, String secondaryContext, String ipv6Management, String ipv6Interface,
            String principal, String secondaryPrincipal, String certSubjectActive, String certIssuerActive,
            String certSubjectPending, String certIssuerPending, String sshKeyComment, String installLogFilename,
            String policyName, String ruleName, String objectName, String policyId, String domainId, String objectId,
            String ruleId, Map<String, ValueClass> raw) {

        static SeededEstate generate() {
            String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            int base = 60 + RANDOM.nextInt(90);
            Device a = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "ist" + tag() + "-fw-01", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + base, "198.51.100." + base, cluster);
            Device b = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "ist" + tag() + "-fw-02", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 1), "198.51.100." + (base + 1), cluster);
            Device pan = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway",
                    "ank" + tag() + "-edge-gw01", "0" + digits(11),
                    "192.0.2." + (base + 2), "198.51.100." + (base + 2), null);
            Device mgmt = new Device(Ui2Rows.opaqueId("dev"), "check_point", "management_server",
                    "core" + tag() + "-smc-01", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 3), null, null);

            String domainOrContext = "ctx-" + tag() + "-payments";
            String secondaryContext = "dom-" + tag() + "-treasury";
            String v6Mgmt = "2001:db8:" + Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff)) + "::"
                    + Integer.toHexString(0x100 + RANDOM.nextInt(0xefff));
            String v6Itf = "2001:db8:" + Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff)) + "::"
                    + Integer.toHexString(0x100 + RANDOM.nextInt(0xefff));
            String principal = "adm" + tag() + ".ops";
            String secondaryPrincipal = "sec" + tag() + ".audit";

            // Certificate subjects/SANs: one hostname-shaped CN (masked to FW-...) and one multi-SAN string with host + IP
            String certSubjectActive = "cn-" + tag() + "-" + a.hostname();
            String certIssuerActive = "ca-" + tag() + "-internal-root";
            String certSubjectPending = "CN=" + b.hostname() + ",DNS:" + b.hostname() + ",IP:" + a.managementAddress();
            String certIssuerPending = "CN=iss-" + tag() + "-corp-ca,DNS:" + mgmt.hostname();

            // SSH key comment in OpenSSH principal@hostname form and Check Point custom install-log filename
            String sshKeyComment = principal + "@" + b.hostname();
            String installLogFilename = "install_fw1_" + a.hostname() + "_R81.20_" + tag() + ".elg";

            String policyName = "pkg-" + tag() + "-core";
            String ruleName = "allow-" + tag() + "-settlement";
            String objectName = "obj-" + tag() + "-ledger";

            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(a, b, pan, mgmt)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.managementAddress(), ValueClass.ADDRESS);
                if (d.interfaceAddress() != null) raw.put(d.interfaceAddress(), ValueClass.ADDRESS);
            }
            raw.put(cluster, ValueClass.CLUSTER_NAME);
            raw.put(domainOrContext, ValueClass.DOMAIN_OR_CONTEXT);
            raw.put(secondaryContext, ValueClass.DOMAIN_OR_CONTEXT);
            raw.put(v6Mgmt, ValueClass.ADDRESS);
            raw.put(v6Itf, ValueClass.ADDRESS);
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(secondaryPrincipal, ValueClass.PRINCIPAL);
            raw.put(certSubjectActive, ValueClass.CERT_SUBJECT_OR_SAN);
            raw.put(certIssuerActive, ValueClass.CERT_SUBJECT_OR_SAN);
            raw.put(certSubjectPending, ValueClass.CERT_SUBJECT_OR_SAN);
            raw.put(certIssuerPending, ValueClass.CERT_SUBJECT_OR_SAN);
            raw.put(sshKeyComment, ValueClass.SSH_KEY_COMMENT);
            raw.put(installLogFilename, ValueClass.INSTALL_LOG_FILENAME);
            raw.put(policyName, ValueClass.POLICY_OR_OBJECT_NAME);
            raw.put(ruleName, ValueClass.POLICY_OR_OBJECT_NAME);
            raw.put(objectName, ValueClass.POLICY_OR_OBJECT_NAME);

            return new SeededEstate(a, b, pan, mgmt, cluster, domainOrContext, secondaryContext, v6Mgmt, v6Itf,
                    principal, secondaryPrincipal, certSubjectActive, certIssuerActive, certSubjectPending,
                    certIssuerPending, sshKeyComment, installLogFilename, policyName, ruleName, objectName,
                    "policy-" + UUID.randomUUID(), "domain-" + UUID.randomUUID(), "obj-" + UUID.randomUUID(),
                    "rule-" + UUID.randomUUID(), Map.copyOf(raw));
        }

        List<Device> devices() {
            return List.of(cpMemberA, cpMemberB, panFirewall, management);
        }
    }

    // ------------------------------------------------------------------ database & artefact seeding

    private static void seedDatabase(SeededEstate e) throws Exception {
        Instant now = Instant.now();
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, Ui2Rows.ACTOR, Ui2Rows.ACTION, () -> {
                for (Device d : e.devices()) {
                    exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                            + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                            + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                            + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                            + "discovery_match_key) values (?, ?, 'manual', true, 'ENROLLED', false, ?, ?, 'NONE', ?, ?, "
                            + "'synthetic-model', 'R81.20', ?, ?, now(), ?, true, ?)",
                            d.id(), d.vendor(), credential, d.role(), d.cluster() == null ? "NONE" : "CORROBORATED",
                            d.hostname(), d.cluster() == null ? null : "active", d.serial(), d.cluster(),
                            d.vendor() + "|" + d.serial());
                    String epId = Ui2Rows.opaqueId("ep");
                    exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                            epId, d.id(), d.managementAddress());
                    ids.put("ep:" + d.id(), epId);
                    exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                            + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                }

                // HTTPS endpoint on cpMemberA so HttpsCertificateTrustRepository.deviceEndpoint resolves (port 443).
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                        + "values (?, ?, 'https', ?, now() + interval '1 second')",
                        Ui2Rows.opaqueId("ep"), e.cpMemberA().id(), e.cpMemberA().managementAddress());
                // IPv6 endpoint on management server.
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                        + "values (?, ?, 'https', ?, now() + interval '2 seconds')",
                        Ui2Rows.opaqueId("ep"), e.management().id(), e.ipv6Management());

                // Domain placement row so JooqEstateNameRepository registers secondaryContext (DOMAIN) in the estate dictionary.
                exec(c, "insert into cp_domain_server_placement(source_id, domain_uid, domain_name, server_name, "
                        + "multi_domain_server, active, observed_at) values (?, ?, ?, ?, ?, true, now())",
                        e.management().id(), e.domainId(), e.secondaryContext(), e.management().hostname(),
                        e.management().hostname());

                // Discovery run + candidates (links ssh_host_key_observation to MANAGEMENT_DISCOVERY too).
                String discoveryJob = job(c, null, "discovery", "COMPLETED", null, "discovery_run", "run-pending");
                String discRunId = Ui2Rows.opaqueId("disc");
                exec(c, "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                        + "requested_by_actor_fingerprint, state, job_id) values (?, 'check_point', ?, ?, ?, 'FINISHED', ?)",
                        discRunId, e.management().managementAddress(), credential, Ui2Rows.ACTOR, discoveryJob);
                exec(c, "update jobs set target_ref = ? where job_id = ?", discRunId, discoveryJob);
                ids.put("discoveryRunId", discRunId);
                for (Device d : List.of(e.cpMemberA(), e.cpMemberB())) {
                    exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                            + "own_address, management_address, cluster_reference, model, software_version, importable, import_outcome) "
                            + "values (?, ?, 'check_point', ?, 'gateway', ?, ?, ?, ?, 'synthetic-model', 'R81.20', true, 'already_imported')",
                            Ui2Rows.opaqueId("cand"), discRunId, d.serial(), d.hostname(), d.managementAddress(),
                            e.management().managementAddress(), e.cluster());
                }

                // Inventory runs with planted domainOrContext, IPv4 and IPv6 addresses (for IpInventorySearch & estate dictionary).
                for (Device d : List.of(e.cpMemberA(), e.cpMemberB(), e.panFirewall())) {
                    String invJob = job(c, d.id(), "inventory_collect", "COMPLETED", null, "device", null);
                    String invRun = Ui2Rows.opaqueId("inv");
                    exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) "
                            + "values (?, ?, ?, now(), 1)", invRun, d.id(), invJob);
                    String itf = Ui2Rows.opaqueId("itf");
                    exec(c, "insert into device_interface(interface_id, run_id, context, routing_context, name, kind, state, zone) "
                            + "values (?, ?, 'physical', ?, 'eth1', 'physical', 'up', 'internal')",
                            itf, invRun, e.domainOrContext());
                    exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                            + "values (?, ?, ?, 'ipv4', 'member')", Ui2Rows.opaqueId("addr"), itf, d.interfaceAddress() + "/24");
                    if (d == e.cpMemberA()) {
                        exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                                + "values (?, ?, ?, 'ipv6', 'member')", Ui2Rows.opaqueId("addr"), itf, e.ipv6Interface() + "/64");
                    }
                }

                // Plane 2 + Plane 6 backing: primary configuration runs with sanitized_text and compliance_evaluation rows.
                seedConfigurationAndCompliance(c, e, now);

                // Plane 3 (part C): ssh_host_key_observation rows (V147).
                String sshObsA = UUID.randomUUID().toString();
                String sshObsB = UUID.randomUUID().toString();
                String sshJob = job(c, e.cpMemberA().id(), "inventory_collect", "COMPLETED", null, "device", null);
                exec(c, "insert into ssh_host_key_observation(observation_id, device_id, endpoint_id, management_address, "
                        + "management_port, key_algorithm, presented_fingerprint, expected_fingerprint, status, strict_refusal, "
                        + "last_job_id, connection_allowed) values (?, ?, ?, ?, 22, 'ssh-ed25519', ?, ?, 'OPEN', false, ?, true)",
                        sshObsA, e.cpMemberA().id(), ids.get("ep:" + e.cpMemberA().id()), e.cpMemberA().managementAddress(),
                        hex(32), hex(32), sshJob);
                exec(c, "insert into ssh_host_key_observation(observation_id, device_id, endpoint_id, management_address, "
                        + "management_port, key_algorithm, presented_fingerprint, expected_fingerprint, status, strict_refusal, "
                        + "last_job_id, connection_allowed) values (?, ?, ?, ?, 22, 'ecdsa-sha2-nistp256', ?, ?, 'OPEN', true, ?, false)",
                        sshObsB, e.panFirewall().id(), ids.get("ep:" + e.panFirewall().id()), e.panFirewall().managementAddress(),
                        hex(32), hex(32), sshJob);
                ids.put("sshObsA", sshObsA);
                ids.put("sshObsB", sshObsB);

                // Plane 4: device_software_version_history (V243) and device_install_event (V246).
                exec(c, "insert into device_software_version_history(device_id, vendor, software_version, hotfix_level, "
                        + "first_seen_at, last_seen_at, previous_software_version, previous_hotfix_level, previous_last_seen_at, source) "
                        + "values (?, 'check_point', 'R81.10', 'Take 95', now() - interval '30 days', now() - interval '5 days', "
                        + "null, null, null, 'discovery_backfill')", e.cpMemberA().id());
                exec(c, "insert into device_software_version_history(device_id, vendor, software_version, hotfix_level, "
                        + "first_seen_at, last_seen_at, previous_software_version, previous_hotfix_level, previous_last_seen_at, source) "
                        + "values (?, 'check_point', 'R81.20', 'Take 119', now() - interval '2 days', now() - interval '1 hour', "
                        + "'R81.10', 'Take 95', now() - interval '5 days', 'inventory')", e.cpMemberA().id());
                LocalDateTime installTimeA = LocalDateTime.now(ZoneOffset.UTC).minusDays(4).withNano(0);
                LocalDateTime installTimeB = LocalDateTime.now(ZoneOffset.UTC).minusDays(2).withNano(0);
                exec(c, "insert into device_install_event(device_id, kind, product_version, jumbo_take, installed_at_local, file_name) "
                        + "values (?, 'BASE_INSTALL', 'R81.20', null, ?, 'install_fw1_R81.20.elg')",
                        e.cpMemberA().id(), Timestamp.valueOf(installTimeA));
                exec(c, "insert into device_install_event(device_id, kind, product_version, jumbo_take, installed_at_local, file_name) "
                        + "values (?, 'JUMBO_INSTALL', 'R81.20', 119, ?, ?)",
                        e.cpMemberA().id(), Timestamp.valueOf(installTimeB), e.installLogFilename());

                // Plane 5: Change feed sources (all timestamps within last 2 hours for default 24h window).
                seedChangeFeedSources(c, e, now);
            });

            // Plane 3 (part A): https_endpoint_cert_trust trigger requires actor='system:worker', action='https_certificate_observe'.
            String certActiveId = UUID.randomUUID().toString();
            String certPendingId = UUID.randomUUID().toString();
            audited(c, "system:worker", "https_certificate_observe", () -> {
                exec(c, "insert into https_endpoint_cert_trust(trust_entry_id, management_address, management_port, "
                        + "fingerprint_sha256, subject_cn, issuer_cn, not_after, status, authorized_by, authorized_at) "
                        + "values (?, ?, 443, ?, ?, ?, now() + interval '180 days', 'ACTIVE', 'system:worker', now())",
                        certActiveId, e.cpMemberA().managementAddress(), hex(32), e.certSubjectActive(), e.certIssuerActive());
                exec(c, "insert into https_endpoint_cert_trust(trust_entry_id, management_address, management_port, "
                        + "fingerprint_sha256, subject_cn, issuer_cn, not_after, status) "
                        + "values (?, ?, 443, ?, ?, ?, now() + interval '365 days', 'PENDING')",
                        certPendingId, e.cpMemberA().managementAddress(), hex(32), e.certSubjectPending(), e.certIssuerPending());
            });
            ids.put("certActiveId", certActiveId);
            ids.put("certPendingId", certPendingId);

            // Plane 3 (part B): management_endpoint_ssh_trust trigger requires authorized_by=actor and action='discovery_ssh_trust_enroll'.
            String sshTrustId = UUID.randomUUID().toString();
            audited(c, e.principal(), "discovery_ssh_trust_enroll", () -> {
                exec(c, "insert into management_endpoint_ssh_trust(trust_entry_id, vendor, management_address, management_port, "
                        + "key_algorithm, fingerprint_sha256, status, authorized_by, authorized_at, observed_at) "
                        + "values (?, 'CHECK_POINT', ?, 22, 'ssh-ed25519', ?, 'ACTIVE', ?, now(), now() - interval '1 minute')",
                        sshTrustId, e.management().managementAddress(), hex(32), e.principal());
            });
            ids.put("sshTrustId", sshTrustId);
        }
    }

    private static void seedConfigurationAndCompliance(Connection c, SeededEstate e, Instant now) throws Exception {
        // Check Point gateway configuration run
        String cpConfig = "set hostname " + e.cpMemberA().hostname() + "\n"
                + "set ntp server primary " + e.cpMemberA().interfaceAddress() + " version 4\n"
                + "set ntp server secondary " + e.ipv6Interface() + " version 4\n"
                + "set ssh server comment " + e.sshKeyComment() + "\n"
                + "set message banner on line \"Managed by " + e.principal() + " on " + e.cpMemberA().hostname()
                + " serial " + e.cpMemberA().serial() + "\"\n";
        String cpCanon = sha256(cpConfig);
        insertConfigRun(c, e.cpMemberA(), "show_configuration", cpConfig, cpCanon);

        // Palo Alto firewall configuration run (XML parsed by ConfigurationProjection.paloAlto)
        String panConfig = "<response status=\"success\"><result><config><devices><entry name=\"localhost.localdomain\">"
                + "<deviceconfig><system><hostname>" + e.panFirewall().hostname() + "</hostname>"
                + "<ip-address>" + e.panFirewall().managementAddress() + "</ip-address>"
                + "<ntp-servers><primary-ntp-server><ntp-server-address>" + e.panFirewall().interfaceAddress()
                + "</ntp-server-address></primary-ntp-server></ntp-servers>"
                + "<login-banner>Maintained by " + e.secondaryPrincipal() + " key " + e.sshKeyComment()
                + "</login-banner></system></deviceconfig></entry></devices></config></result></response>";
        String panCanon = sha256(panConfig);
        insertConfigRun(c, e.panFirewall(), "active", panConfig, panCanon);

        // Compliance evaluation for cpMemberA: one CRITICAL FAIL + one HIGH DATA_UNAVAILABLE carrying raw identities in observedValue/message
        List<Map<String, Object>> cpItems = List.of(
                evalItem("CP-SYN-01", "NTP servers match approved baseline", "CRITICAL", "FAIL",
                        "ntp " + e.cpMemberA().interfaceAddress() + " and " + e.ipv6Interface() + " on "
                                + e.cpMemberA().hostname() + " serial " + e.cpMemberA().serial(),
                        "Mismatch on " + e.cpMemberA().hostname() + " (" + e.cpMemberA().managementAddress()
                                + " / " + e.ipv6Management() + ") reviewed by " + e.principal() + " key " + e.sshKeyComment(),
                        List.of(Map.of("framework", "CIS", "clauseId", "1.1"),
                                Map.of("framework", "NIST_800_53", "clauseId", "AU-8"))),
                evalItem("CP-SYN-02", "Primary DNS resolver is reachable", "HIGH", "DATA_UNAVAILABLE",
                        null,
                        "Resolver missing on " + e.cpMemberB().hostname()
                                + " (" + e.cpMemberB().managementAddress() + ") serial " + e.cpMemberB().serial()
                                + " actor " + e.secondaryPrincipal(),
                        List.of(Map.of("framework", "PCI_DSS", "clauseId", "2.2.4"),
                                Map.of("framework", "NIST_800_53", "clauseId", "SC-20"))),
                evalItem("PAN-SYN-01", "Management plane SSH key comment policy", "CRITICAL", "NOT_APPLICABLE",
                        null, "Not applicable", List.of()));
        Map<String, Object> cpEval = evaluationResult(e.cpMemberA().id(), "check_point", 2, 0, 1, 1, cpItems);
        exec(c, "insert into compliance_evaluation(device_id, canonical_hash, config_hash, rules_fingerprint, evaluated_at, result) "
                + "values (?, ?, ?, ?, ?, ?::jsonb)",
                e.cpMemberA().id(), cpCanon, String.valueOf(cpConfig.hashCode()), RULES_FINGERPRINT,
                Timestamp.from(now.minusSeconds(120)), JSON.writeValueAsString(cpEval));

        // Compliance evaluation for panFirewall: CRITICAL FAIL carrying raw PAN host, IP, serial, principal, SSH key comment
        List<Map<String, Object>> panItems = List.of(
                evalItem("PAN-SYN-01", "Management plane SSH key comment policy", "CRITICAL", "FAIL",
                        "ssh-ed25519 " + e.sshKeyComment() + " host " + e.panFirewall().hostname() + " serial "
                                + e.panFirewall().serial(),
                        "Key comment " + e.sshKeyComment() + " on " + e.panFirewall().managementAddress()
                                + " / " + e.ipv6Management() + " set by " + e.secondaryPrincipal(),
                        List.of(Map.of("framework", "FINANCIAL_BASELINE", "clauseId", "FB-3.1"),
                                Map.of("framework", "CIS", "clauseId", "1.4"))),
                evalItem("CP-SYN-01", "NTP servers match approved baseline", "CRITICAL", "NOT_APPLICABLE",
                        null, "Not applicable", List.of()),
                evalItem("CP-SYN-02", "Primary DNS resolver is reachable", "HIGH", "NOT_APPLICABLE",
                        null, "Not applicable", List.of()));
        Map<String, Object> panEval = evaluationResult(e.panFirewall().id(), "palo_alto", 1, 0, 1, 0, panItems);
        exec(c, "insert into compliance_evaluation(device_id, canonical_hash, config_hash, rules_fingerprint, evaluated_at, result) "
                + "values (?, ?, ?, ?, ?, ?::jsonb)",
                e.panFirewall().id(), panCanon, String.valueOf(panConfig.hashCode()), RULES_FINGERPRINT,
                Timestamp.from(now.minusSeconds(90)), JSON.writeValueAsString(panEval));
    }

    private static void insertConfigRun(Connection c, Device d, String readKind, String text, String canonicalHash)
            throws Exception {
        String cfgJob = job(c, d.id(), "configuration_collect", "COMPLETED", null, "device", null);
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        var handle = store.open(d.id(), cfgJob, d.vendor(), false);
        handle.sink().write(plain);
        var meta = handle.finish();
        String ref = meta.ref().value();
        exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, "
                + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, 'none', ?)",
                ref, d.id(), cfgJob, d.vendor(), meta.plaintextSha256(), meta.plaintextBytes(),
                meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId());
        String runId = Ui2Rows.opaqueId("cfg");
        exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, sanitized_text, change_state) "
                + "values (?, ?, ?, now() - interval '10 minutes', ?, ?, true, ?, ?, ?, ?, 0, ?, 'first_run')",
                runId, d.id(), cfgJob, d.vendor(), readKind, canonicalHash, meta.plaintextSha256(),
                meta.plaintextBytes(), ref, text);
        exec(c, "insert into device_configuration_index(index_id, run_id, context, section, source, entry_count) "
                + "values (?, ?, 'default', 'System', 'local', 4)", UUID.randomUUID().toString(), runId);
    }

    private static Map<String, Object> evalItem(String controlId, String title, String severity, String status,
            String observedValue, String message, List<Map<String, Object>> frameworks) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("controlId", controlId);
        item.put("title", title);
        item.put("severity", severity);
        item.put("displayStatus", status);
        item.put("observedValue", observedValue);
        item.put("guidance", "Configure approved baseline setting");
        item.put("message", message);
        item.put("frameworks", frameworks);
        return item;
    }

    private static Map<String, Object> evaluationResult(String deviceId, String vendor, int totalAssigned, int pass,
            int fail, int unavailable, List<Map<String, Object>> items) {
        Map<String, Object> eval = new LinkedHashMap<>();
        eval.put("deviceId", deviceId);
        eval.put("vendor", vendor);
        eval.put("totalAssigned", totalAssigned);
        eval.put("passCount", pass);
        eval.put("failCount", fail);
        eval.put("dataUnavailableCount", unavailable);
        eval.put("observedCompliance", 0.0);
        eval.put("evidenceCoverage", 100.0);
        eval.put("assuredCompliance", 0.0);
        eval.put("items", items);
        return eval;
    }

    private static void seedChangeFeedSources(Connection c, SeededEstate e, Instant now) throws Exception {
        // 1. Policy snapshot + recent rule history (populates both ChangeFeed POLICY_RULE_MODIFIED and EstateDictionary USER/OBJECT/RULE).
        var object = new PolicySnapshot.PolicyObject(e.objectId(), e.objectName(), "host", List.of(),
                List.of(e.cpMemberA().interfaceAddress()), "RESOLVED");
        var rule = new PolicySnapshot.Rule(e.ruleId(), UUID.randomUUID().toString(), 1, e.ruleName(), true,
                new PolicySnapshot.Cell(List.of(e.objectId()), false),
                new PolicySnapshot.Cell(List.of(e.objectId()), false),
                new PolicySnapshot.Cell(List.of(), false), new PolicySnapshot.Cell(List.of(), false),
                "Accept", "Log", "approved by " + e.principal() + " for " + e.panFirewall().hostname(),
                Map.of("last-modifier", List.of(e.principal(), e.secondaryPrincipal())));
        var metadata = new PolicySnapshot.Metadata(e.policyId(), e.management().id(), e.management().hostname(), "CP",
                e.domainId(), e.secondaryContext(), e.policyName(), now.minusSeconds(3600).toString(), null,
                List.of(new PolicySnapshot.Target(e.cpMemberA().id(), e.cpMemberA().hostname(), "", "IN_SYNC"),
                        new PolicySnapshot.Target(e.cpMemberB().id(), e.cpMemberB().hostname(), "", "IN_SYNC")));
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("sec-1", "Core Rules", "Network", null, List.of(rule))),
                Map.of(e.objectId(), object), List.of());
        exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now() - interval '1 hour', ?::jsonb, ?::jsonb)",
                e.policyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
        exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now() - interval '50 minutes', ?, ?, ?::jsonb)",
                e.policyId(), e.ruleId(), e.ruleId(), now.minusSeconds(3200).toString(), e.principal(),
                JSON.writeValueAsString(List.of(Map.of("field", "name", "before", "old-rule", "after", e.ruleName()))));
        exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'added', now() - interval '45 minutes', ?, ?, '[]'::jsonb)",
                e.policyId(), "rk-2", "rule-2-" + UUID.randomUUID(), now.minusSeconds(2900).toString(), e.secondaryPrincipal());

        // 2. Policy install (POLICY_INSTALLED in ChangeFeed).
        exec(c, "insert into device_policy_install(device_id, policy_name, installed_at, installed_at_text, source_read) "
                + "values (?, ?, now() - interval '40 minutes', 'synthetic', 'synthetic_seed')",
                e.cpMemberA().id(), e.policyName());

        // 3. Policy change evidence (POLICY_CHANGE_RECORDED in ChangeFeed, V163/V208/V213).
        String polJob = job(c, e.panFirewall().id(), "policy_collect", "COMPLETED", null, "device", null);
        LocalDateTime changeTimeUtc = LocalDateTime.ofInstant(now.minusSeconds(2400), ZoneOffset.UTC).withNano(0);
        exec(c, "insert into policy_change_evidence(evidence_id, source_id, vendor, evidence_kind, container_ref, job_id, "
                + "observed_at, entry_ref, change_time, time_basis, admin_ref, admin_name, rule_ref_hmac, command_class, "
                + "result_class, path_hmac, added_count, modified_count, deleted_count) "
                + "values (?, ?, 'palo_alto', 'PAN_CONFIG_LOG', 'dg-shared', ?, now() - interval '35 minutes', '70041', ?, "
                + "'UTC', ?, ?, ?, 'edit', 'Succeeded', ?, 0, 1, 0)",
                UUID.randomUUID().toString(), e.panFirewall().id(), polJob, Timestamp.valueOf(changeTimeUtc),
                sha256(e.principal()), e.principal(), sha256(e.ruleName()), sha256("xpath"));

        // 4. Failed job (JOB_FAILED in ChangeFeed + GlobalSearch evidence).
        String failedJob = job(c, e.panFirewall().id(), "inventory_collect", "FAILED",
                "ssh to " + e.panFirewall().hostname() + " (" + e.panFirewall().managementAddress() + ") serial "
                        + e.panFirewall().serial() + " rejected " + e.principal() + " key " + e.sshKeyComment(),
                "device", null);
        ids.put("failedJobId", failedJob);

        // 5. Device crash alert (CRASH_FILE_NEW in ChangeFeed).
        String crashJob = job(c, e.cpMemberA().id(), "cp_system_log_collect", "COMPLETED", null, "device", null);
        String crashAlertId = UUID.randomUUID().toString();
        exec(c, "insert into device_crash_alert(alert_id, device_id, process, file_count, newest_mtime, job_id, created_at) "
                + "values (?, ?, 'fwd', 2, ?, ?, now() - interval '25 minutes')",
                crashAlertId, e.cpMemberA().id(), now.minusSeconds(1500).getEpochSecond(), crashJob);
        ids.put("crashAlertId", crashAlertId);

        // 6. Stored PAN config log chunk (CONFIG_CHANGED in ChangeFeed).
        ZonedDateTime istNow = now.minusSeconds(1800).atZone(ISTANBUL);
        LocalDate logDay = istNow.toLocalDate();
        String panLine = panConfigJsonLine(PAN_TIME.format(istNow), e.panFirewall().serial(), e.panFirewall().hostname(),
                e.panFirewall().managementAddress(), e.principal(),
                "/config/devices/entry/vsys/entry[@name='" + e.domainOrContext() + "']/address/entry[@name='" + e.objectName() + "']",
                "ip-netmask " + e.cpMemberB().interfaceAddress() + " host " + e.cpMemberB().hostname(),
                "ip-netmask " + e.panFirewall().interfaceAddress() + " key " + e.sshKeyComment()) + "\n";
        insertSystemLogChunk(c, e.panFirewall(), "pan_config", logDay, null, panLine);

        // 7. Stored Check Point cpwd.elg log chunk (PROCESS_RESTART in ChangeFeed).
        String cpwdLine = "[fwd 4242 1]@" + e.cpMemberA().hostname() + "[" + CPWD_TIME.format(istNow)
                + "] Process fwd restarted after peer " + e.cpMemberB().hostname() + " ("
                + e.cpMemberB().managementAddress() + ") serial " + e.cpMemberB().serial() + " actor "
                + e.principal() + "\n";
        insertSystemLogChunk(c, e.cpMemberA(), "cp_cpwd_elg", logDay, 9001L, cpwdLine);
    }

    private static String panConfigJsonLine(String receiveTime, String serial, String deviceName, String hostIp,
            String admin, String path, String before, String after) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@logid", "7001");
        m.put("domain", "1");
        m.put("receive_time", receiveTime);
        m.put("serial", serial);
        m.put("seqno", "841");
        m.put("type", "CONFIG");
        m.put("subtype", "0");
        m.put("time_generated", receiveTime);
        m.put("device_name", deviceName);
        m.put("host", hostIp);
        m.put("cmd", "edit");
        m.put("admin", admin);
        m.put("client", "Web");
        m.put("result", "Succeeded");
        m.put("path", path);
        m.put("before-change-preview", before);
        m.put("after-change-preview", after);
        return JSON.writeValueAsString(m);
    }

    private static void insertSystemLogChunk(Connection c, Device d, String sourceKey, LocalDate logDay, Long inode,
            String content) throws Exception {
        String jobType = "palo_alto".equals(d.vendor()) ? "pan_system_log_collect" : "cp_system_log_collect";
        String logJob = job(c, d.id(), jobType, "COMPLETED", null, "device", null);
        byte[] plain = content.getBytes(StandardCharsets.UTF_8);
        var handle = store.open(d.id(), logJob, d.vendor(), true);
        handle.sink().write(plain);
        var meta = handle.finish();
        exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, hostname_fingerprint, "
                + "plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, "
                + "validation, retention_tier, expires_at, recovery_volume_path) values (?, ?, 'system_log', ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                + "'standard', now() + interval '30 days', ?)",
                meta.ref().value(), d.id(), d.vendor(), sha256(d.hostname()), meta.plaintextSha256(),
                meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                meta.wrappedDataKey(), meta.ref().value());
        exec(c, "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                + "reads_issued, duration_ms, detail) values (?, ?, ?, ?, 'COLLECTED', ?, ?, 1, 5, '{}'::jsonb)",
                UUID.randomUUID().toString(), d.id(), logJob, sourceKey, (long) plain.length, (long) plain.length);
        String chunkId = UUID.randomUUID().toString();
        exec(c, "insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, inode, "
                + "start_offset, end_offset, bytes, lines, redaction_count, flags, artefact_ref, wrapped_data_key, "
                + "plaintext_hmac, ciphertext_bytes) values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 1, 0, '{}'::text[], ?, ?, ?, ?)",
                chunkId, d.id(), sourceKey, logJob, java.sql.Date.valueOf(logDay), sha256(sourceKey), inode,
                (long) plain.length, (long) plain.length, meta.ref().value(), meta.wrappedDataKey(),
                sha256(content), meta.ciphertextBytes());
        ids.put("chunk:" + sourceKey, chunkId);
    }

    /**
     * Seeds MASKED CSV, XLSX and PDF report runs plus one RAW CSV report run through the real
     * {@link ReportDocumentMasker}, {@link CsvReportRenderer}, {@link XlsxReportRenderer}, {@link PdfReportRenderer},
     * {@link ReportArtefactWriter} and {@link JooqReportRunStore}. Uses its own pseudonymizer instance so the live
     * HTTP bean's {@code rawNameToPseudonym} map stays cold until the first HTTP request.
     */
    private static void seedReportRuns(SeededEstate e) throws Exception {
        byte[] seedKey = new byte[32];
        RANDOM.nextBytes(seedKey);
        TopologyNamePseudonymizer reportNames = new TopologyNamePseudonymizer(seedKey);
        reportNames.setRegistry(context.getBean(PseudonymRegistry.class));
        reportNames.setDeviceDirectory(context.getBean(MaskingDeviceDirectory.class));
        reportNames.setFreeTextEstate(context.getBeanProvider(FreeTextEstate.class));
        SubnetPreservingIpMasker reportIps = new SubnetPreservingIpMasker(seedKey);
        ReportDocumentMasker masker = new ReportDocumentMasker(new ReportProjectionMasker(reportNames, reportIps));

        ReportDocument unprojected = buildPlantedReportDocument(e);
        ReportDocument maskedDoc = masker.project(unprojected, ProjectionMode.MASKED);
        ReportDocument rawDoc = masker.project(unprojected, ProjectionMode.RAW);

        ReportArtefactWriter writer = new ReportArtefactWriter(store);
        ReportRunStore runStore = new JooqReportRunStore(context.getBean(TransactionBoundary.class));
        List<ReportRenderer> renderers = List.of(new CsvReportRenderer(), new XlsxReportRenderer(), new PdfReportRenderer());
        Instant baseTime = Instant.now().minusSeconds(600);
        int seq = 0;
        for (ReportRenderer renderer : renderers) {
            byte[] maskedBytes = renderer.render(maskedDoc);
            rawReportBytes.put(renderer.format(), renderer.render(rawDoc));
            UUID runId = UUID.randomUUID();
            Instant reqAt = baseTime.plusSeconds(seq * 10L);
            Instant expAt = reqAt.plus(Duration.ofDays(30));
            runStore.insertQueued(new RunRow(runId, "RPT-02", "QUEUED", "MASKED", renderer.format().name(),
                    "{\"framework\":\"ALL\"}", "{}", null, null, null, "actor:r5-seed", reqAt, null, expAt));
            runStore.markRunning(runId);
            BackupArtefactManifestRecord manifest = writer.write(runId, maskedBytes, expAt);
            runStore.markSucceeded(runId, manifest, "{\"compliance\":{\"covered\":2,\"total\":2}}", "actor:r5-seed");
            ids.put("reportMasked:" + renderer.format().name(), runId.toString());
            seq++;
        }

        // Seed one SUCCEEDED RAW report run whose stored artefact carries raw identities (REPORTS_R1_CONTRACT.md A2).
        UUID rawRunId = UUID.randomUUID();
        Instant rawReqAt = baseTime.plusSeconds(60);
        Instant rawExpAt = rawReqAt.plus(Duration.ofDays(30));
        runStore.insertQueued(new RunRow(rawRunId, "RPT-02", "QUEUED", "RAW", "CSV",
                "{\"framework\":\"ALL\"}", "{}", null, null, null, "actor:sec-admin", rawReqAt, null, rawExpAt));
        runStore.markRunning(rawRunId);
        BackupArtefactManifestRecord rawManifest = writer.write(rawRunId, rawReportBytes.get(ReportFormat.CSV), rawExpAt);
        runStore.markSucceeded(rawRunId, rawManifest, "{\"compliance\":{\"covered\":2,\"total\":2}}", "actor:sec-admin");
        ids.put("reportRaw", rawRunId.toString());
    }

    private static ReportDocument buildPlantedReportDocument(SeededEstate e) {
        Instant gen = Instant.parse("2026-11-25T12:00:00Z");
        List<Column> cols = List.of(
                new Column("device", "Firewall"),
                new Column("cluster", "Cluster"),
                new Column("address", "Management IP"),
                new Column("serial", "Serial"),
                new Column("detail", "Finding Detail"));
        List<List<Cell>> rows = List.of(
                List.of(
                        Cell.device(e.cpMemberA().hostname(), e.cpMemberA().id(), e.cluster()),
                        Cell.cluster(e.cluster()),
                        new Cell(e.cpMemberA().managementAddress(), CellKind.ADDRESS, null),
                        new Cell(e.cpMemberA().serial(), CellKind.SERIAL, null),
                        Cell.text("NTP drift on " + e.cpMemberA().hostname()
                                + " (" + e.cpMemberA().interfaceAddress() + " / " + e.ipv6Management() + ") serial "
                                + e.cpMemberA().serial() + " reviewed by " + e.principal() + " key " + e.sshKeyComment())),
                List.of(
                        Cell.device(e.panFirewall().hostname(), e.panFirewall().id(), null),
                        Cell.text("Standalone"),
                        new Cell(e.ipv6Interface(), CellKind.ADDRESS, null),
                        new Cell(e.panFirewall().serial(), CellKind.SERIAL, null),
                        Cell.text("SSH policy on " + e.panFirewall().hostname()
                                + " (" + e.panFirewall().interfaceAddress() + ") actor " + e.secondaryPrincipal()
                                + " peer " + e.cpMemberB().hostname())));
        Table table = new Table("compliance-devices", "Evaluated Firewalls (" + e.cpMemberA().hostname() + ")", cols, rows, "None");
        List<Kpi> kpis = List.of(
                new Kpi("Critical on " + e.cpMemberA().hostname(), "2 (" + e.principal() + ")", "BAD"),
                new Kpi("Scope", e.cluster() + " / " + e.cpMemberA().managementAddress(), "INFO"));
        List<PlaneAsOf> asOf = List.of(new PlaneAsOf("compliance", gen, gen.minusSeconds(300), 2, 2, 0, "firewalls"));
        Section section = new Section("sec-compliance", "Compliance Findings for " + e.panFirewall().hostname(),
                asOf, kpis, List.of(table),
                List.of("Signed off by " + e.principal() + " and " + e.secondaryPrincipal() + " on "
                        + e.management().hostname() + " serial " + e.management().serial() + " key " + e.sshKeyComment()));
        return new ReportDocument("RPT-02", "Compliance & Hardening Coverage", "Audit / Risk",
                "Which controls fail across " + e.cpMemberA().hostname() + " and " + e.panFirewall().hostname() + "?",
                gen, ReportDocument.UNPROJECTED,
                Map.of("framework", "ALL", "scope_note", e.cpMemberA().hostname() + " " + e.cpMemberA().managementAddress()),
                asOf,
                List.of("Evidence includes " + e.cpMemberB().hostname() + " (" + e.cpMemberB().managementAddress()
                        + " / " + e.ipv6Interface() + ") serial " + e.cpMemberB().serial()),
                List.of(section));
    }

    /**
     * Global search requires {@code role:onboarding_admin} ({@code ActionRegistry.GLOBAL_SEARCH_READ}), so plain
     * {@code aiview} is refused with 403. We also seed a dedicated replay-viewer identity holding
     * {@code role:replay_viewer + role:onboarding_admin} so the HTTP search handler itself is exercised under the
     * masked {@code isReplayViewer == true} projection.
     */
    private static void seedMaskedSearchAccount() {
        maskedSearchUsername = "aiview-search-" + tag();
        maskedSearchPassword = ("pw-" + hex(12) + "-Aa1!").toCharArray();
        FirstBootIdentityRoleBindingSeeder seeder = context.getBean(FirstBootIdentityRoleBindingSeeder.class);
        seeder.seed(List.of(new FirstBootIdentityRoleBindingSeeder.IdentitySpec(
                maskedSearchUsername,
                maskedSearchPassword.clone(),
                List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER, RoleToken.ONBOARDING_ADMIN))));
    }

    // ------------------------------------------------------------------ SQL helpers

    private static String job(Connection c, String deviceId, String type, String state, String reason, String targetKind,
            String targetRef) throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, target_ref, submitted_by_actor_fingerprint, "
                + "state, action_class, terminal_reason, submitted_at, finished_at) values (?, ?, ?, ?, ?, ?, ?, 'read', ?, "
                + "now() - interval '20 minutes', " + (state.equals("REQUESTED") ? "null" : "now() - interval '19 minutes'") + ")",
                id, type, targetKind, deviceId, targetRef, Ui2Rows.ACTOR, state, reason);
        return id;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    private static void audited(Connection c, String actor, String action, SqlWork work) throws Exception {
        c.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(c, actor, action);
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

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ leak counting (counts only, never values)

    static List<Pattern> forms(String value, ValueClass cls) {
        List<Pattern> out = new ArrayList<>();
        if (cls == ValueClass.ADDRESS && value.contains(":")) {
            Set<String> spellings = new TreeSet<>();
            spellings.add(value);
            try {
                byte[] bytes = java.net.InetAddress.getByName(value).getAddress();
                StringBuilder full = new StringBuilder();
                StringBuilder unpadded = new StringBuilder();
                for (int i = 0; i < 16; i += 2) {
                    int group = ((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff);
                    if (i > 0) {
                        full.append(':');
                        unpadded.append(':');
                    }
                    full.append(String.format(Locale.ROOT, "%04x", group));
                    unpadded.append(Integer.toHexString(group));
                }
                spellings.add(full.toString());
                spellings.add(unpadded.toString());
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("seeded IPv6 literal did not parse", e);
            }
            for (String s : spellings) {
                out.add(Pattern.compile("(?<![0-9a-f:])" + Pattern.quote(s) + "(?![0-9a-f:])", Pattern.CASE_INSENSITIVE));
            }
        } else if (cls == ValueClass.ADDRESS) {
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

    // ------------------------------------------------------------------ HTTP helpers

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    private static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String contentType, String maskedHeader, byte[] bytes, String body) {
    }

    private static Resp get(String target, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(
                response.statusCode(),
                response.headers().firstValue("content-type").orElse(""),
                response.headers().firstValue("x-nexus-masked").orElse(""),
                response.body(),
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String aiviewSession;
    private static String maskedSearchSession;

    private static synchronized String loginAsAiview() throws Exception {
        if (aiviewSession == null) aiviewSession = login("aiview", "aiview");
        return aiviewSession;
    }

    private static synchronized String loginAsMaskedSearchViewer() throws Exception {
        if (maskedSearchSession == null) maskedSearchSession = login(maskedSearchUsername, new String(maskedSearchPassword));
        return maskedSearchSession;
    }

    private static String login(String username, String password) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("username", username, "password", password))))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "login HTTP status");
        for (String cookie : response.headers().allValues("set-cookie")) {
            if (cookie.startsWith("ui2_session=")) {
                String value = cookie.substring("ui2_session=".length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("no ui2_session cookie returned on login");
    }

    // ------------------------------------------------------------------ report byte & archive scanners

    record ScanResult(String text, Set<String> zipEntries) {
    }

    static ScanResult scanReportBytes(ReportFormat format, byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder(new String(bytes, StandardCharsets.ISO_8859_1));
        Set<String> entries = new TreeSet<>();
        switch (format) {
            case CSV -> out.append('\n').append(new String(bytes, StandardCharsets.UTF_8));
            case XLSX -> {
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        entries.add(entry.getName());
                        out.append('\n').append(entry.getName()).append('\n')
                                .append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            case PDF -> {
                for (byte[] stream : inflatePdfStreams(bytes)) {
                    String s = new String(stream, StandardCharsets.ISO_8859_1);
                    out.append('\n').append(s).append('\n').append(extractPdfLiterals(s));
                }
                out.append('\n').append(extractPdfLiterals(new String(bytes, StandardCharsets.ISO_8859_1)));
            }
        }
        return new ScanResult(out.toString(), entries);
    }

    private static List<byte[]> inflatePdfStreams(byte[] pdf) {
        List<byte[]> out = new ArrayList<>();
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        int from = 0;
        while ((from = text.indexOf("stream", from)) >= 0) {
            int start = from + "stream".length();
            if (start < text.length() && text.charAt(start) == '\r') start++;
            if (start < text.length() && text.charAt(start) == '\n') start++;
            int end = text.indexOf("endstream", start);
            if (end < 0) break;
            try {
                Inflater inflater = new Inflater();
                inflater.setInput(pdf, start, end - start);
                ByteArrayOutputStream inflated = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (!inflater.finished() && !inflater.needsInput()) {
                    int n = inflater.inflate(buf);
                    if (n == 0 && inflater.needsDictionary()) break;
                    inflated.write(buf, 0, n);
                }
                inflater.end();
                if (inflated.size() > 0) out.add(inflated.toByteArray());
            } catch (java.util.zip.DataFormatException ignored) {
                // PdfReportRenderer sets NO_COMPRESSION, so streams are already uncompressed ASCII/Latin-1.
            }
            from = end + "endstream".length();
        }
        return out;
    }

    /** Extracts concatenated text from PDF parenthesized string literals {@code (...)} in content streams. */
    private static String extractPdfLiterals(String pdfStream) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean escaped = false;
        for (int i = 0; i < pdfStream.length(); i++) {
            char ch = pdfStream.charAt(i);
            if (depth == 0) {
                if (ch == '(') depth = 1;
            } else if (escaped) {
                out.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '(') {
                depth++;
                out.append(ch);
            } else if (ch == ')') {
                depth--;
                if (depth == 0) out.append(' ');
                else out.append(ch);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ Test 1: cold-process sweep of seeded GET planes

    @Test
    @Order(1)
    void seededPlanesReturnNonEmptyMaskedPayloadsWithZeroRawSurvivors() throws Exception {
        String cookie = loginAsAiview();
        assertNotNull(cookie);

        // Hit the newly seeded planes FIRST on a cold process (before /devices) so any missing cold-dictionary load
        // surfaces immediately rather than being hidden by an earlier device-list call.
        Map<String, String> seededTargets = new LinkedHashMap<>();
        seededTargets.put("GET /compliance", "/compliance");
        seededTargets.put("GET /compliance/overview", "/compliance/overview");
        seededTargets.put("GET /compliance/controls", "/compliance/controls");
        seededTargets.put("GET /api/v2/compliance/controls/{controlId}#CP-SYN-01", "/api/v2/compliance/controls/CP-SYN-01");
        seededTargets.put("GET /api/v2/compliance/controls/{controlId}#CP-SYN-02", "/api/v2/compliance/controls/CP-SYN-02");
        seededTargets.put("GET /api/v2/compliance/controls/{controlId}#PAN-SYN-01", "/api/v2/compliance/controls/PAN-SYN-01");
        seededTargets.put("GET /devices/{deviceId}/compliance#cp", "/devices/" + estate.cpMemberA().id() + "/compliance");
        seededTargets.put("GET /devices/{deviceId}/compliance#pan", "/devices/" + estate.panFirewall().id() + "/compliance");
        seededTargets.put("GET /devices/{deviceId}/https-certificate", "/devices/" + estate.cpMemberA().id() + "/https-certificate");
        seededTargets.put("GET /api/v2/ssh-trust", "/api/v2/ssh-trust");
        seededTargets.put("GET /devices/{deviceId}/software-history", "/devices/" + estate.cpMemberA().id() + "/software-history");
        seededTargets.put("GET /api/v2/software-history/changes", "/api/v2/software-history/changes");
        seededTargets.put("GET /api/v2/changes/feed", "/api/v2/changes/feed");
        seededTargets.put("GET /api/v2/reports/catalogue", "/api/v2/reports/catalogue");
        seededTargets.put("GET /api/v2/reports/runs", "/api/v2/reports/runs");
        seededTargets.put("GET /api/v2/reports/runs/{runId}#CSV", "/api/v2/reports/runs/" + ids.get("reportMasked:CSV"));
        seededTargets.put("GET /api/v2/reports/runs/{runId}#XLSX", "/api/v2/reports/runs/" + ids.get("reportMasked:XLSX"));
        seededTargets.put("GET /api/v2/reports/runs/{runId}#PDF", "/api/v2/reports/runs/" + ids.get("reportMasked:PDF"));

        Map<String, Resp> responses = new LinkedHashMap<>();
        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        for (var entry : seededTargets.entrySet()) {
            Resp resp = get(entry.getValue(), cookie);
            responses.put(entry.getKey(), resp);
            Map<ValueClass, Integer> found = count(resp.body(), estate.raw());
            if (!found.isEmpty()) leaks.put(entry.getKey(), found);
        }

        // Also sweep all remaining AiviewReachableRoutes GET endpoints to ensure zero cross-route fallout.
        int reachableCount = 0;
        for (AiviewReachableRoutes.Endpoint ep : AiviewReachableRoutes.reachable()) {
            if (!"GET".equals(ep.method())) continue;
            reachableCount++;
            String routeKey = ep.key();
            if (seededTargets.containsKey(routeKey)) continue;
            Resp resp = get(resolveTemplate(ep.pathTemplate()), cookie);
            Map<ValueClass, Integer> found = count(resp.body(), estate.raw());
            if (!found.isEmpty()) leaks.put(routeKey, found);
        }

        responses.forEach((route, resp) -> System.out.printf(Locale.ROOT,
                "AIVIEW_R5_SWEEP %3d %-56s masked=%-5s raw=%s%n",
                resp.status(), route, resp.maskedHeader(), leaks.getOrDefault(route, Map.of())));
        leaks.forEach((route, found) -> {
            if (!responses.containsKey(route)) {
                System.out.printf(Locale.ROOT, "AIVIEW_R5_LEAK  %-60s raw=%s%n", route, found);
            }
        });
        System.out.println("AIVIEW_R5_SUMMARY seeded_routes=" + seededTargets.size()
                + " reachable_get=" + reachableCount + " planted_values=" + estate.raw().size()
                + " leak_routes=" + leaks.size());

        // 1. Assert HTTP 200 and non-empty seeded markers on every plane before checking leak counts.
        for (var entry : responses.entrySet()) {
            assertEquals(200, entry.getValue().status(), entry.getKey() + " HTTP status");
            assertEquals("true", entry.getValue().maskedHeader(), entry.getKey() + " X-Nexus-Masked header");
        }

        // Compliance assertions
        for (String key : List.of("GET /compliance", "GET /compliance/overview")) {
            JsonNode root = JSON.readTree(responses.get(key).body());
            assertEquals(2, root.path("evaluated_firewalls").asInt(), key + " evaluated_firewalls");
            assertEquals(3, root.path("control_checks").asInt(), key + " control_checks");
            assertEquals(2, root.path("critical_deficiencies").asInt(), key + " critical_deficiencies");
            assertEquals(1, root.path("data_gaps").asInt(), key + " data_gaps");
            assertTrue(root.path("frameworks").size() >= 4, key + " framework cards");
        }
        JsonNode controlsRoot = JSON.readTree(responses.get("GET /compliance/controls").body());
        assertEquals(3, controlsRoot.path("total_controls").asInt(), "seeded catalog controls");
        assertTrue(responses.get("GET /compliance/controls").body().contains("FW-"), "affected_devices masked as FW-");
        assertFalse(responses.get("GET /compliance/controls").body().contains(FreeTextEstate.UNAVAILABLE),
                "cold-process free-text dictionary loaded on /compliance/controls");

        for (String key : List.of("GET /api/v2/compliance/controls/{controlId}#CP-SYN-01",
                "GET /api/v2/compliance/controls/{controlId}#CP-SYN-02",
                "GET /api/v2/compliance/controls/{controlId}#PAN-SYN-01")) {
            JsonNode detail = JSON.readTree(responses.get(key).body());
            assertTrue(detail.path("devices").size() >= 1, key + " has device rows");
            assertTrue(responses.get(key).body().contains("FW-"), key + " masks device hostnames as FW-");
            assertFalse(responses.get(key).body().contains("Evidence withheld"), key + " served non-withheld evidence");
        }

        for (String key : List.of("GET /devices/{deviceId}/compliance#cp", "GET /devices/{deviceId}/compliance#pan")) {
            JsonNode devComp = JSON.readTree(responses.get(key).body());
            assertTrue(devComp.path("items").size() >= 1, key + " has evaluation items");
            assertTrue(devComp.path("hostname").asText().startsWith("FW-"), key + " masked hostname");
        }

        // HTTPS certificate & SSH trust assertions
        JsonNode httpsCert = JSON.readTree(responses.get("GET /devices/{deviceId}/https-certificate").body());
        assertTrue(httpsCert.path("available").asBoolean(), "https-certificate available");
        assertTrue(httpsCert.path("certificate_changed").asBoolean(), "https-certificate pending change flagged");
        assertEquals(2, httpsCert.path("certificates").size(), "https-certificate ACTIVE + PENDING entries");
        assertTrue(responses.get("GET /devices/{deviceId}/https-certificate").body().contains("FW-"),
                "https-certificate subject/issuer masked");

        JsonNode sshTrust = JSON.readTree(responses.get("GET /api/v2/ssh-trust").body());
        assertEquals(2, sshTrust.path("observations").size(), "ssh-trust observations count");
        assertTrue(responses.get("GET /api/v2/ssh-trust").body().contains("FW-"), "ssh-trust device hostname masked");

        // Software history & install events assertions
        JsonNode devSw = JSON.readTree(responses.get("GET /devices/{deviceId}/software-history").body());
        assertTrue(devSw.path("known").asBoolean(), "device software-history known");
        assertTrue(devSw.path("stretches").size() >= 2, "device software-history stretches");
        assertEquals(2, devSw.path("install_events").size(), "device software-history install_events");

        JsonNode fleetSw = JSON.readTree(responses.get("GET /api/v2/software-history/changes").body());
        assertTrue(fleetSw.path("changes").size() >= 1, "fleet software-history changes");
        assertEquals(2, fleetSw.path("install_events").size(), "fleet software-history install_events");
        assertTrue(responses.get("GET /api/v2/software-history/changes").body().contains("FW-"),
                "fleet software-history masks hostnames");

        // Change feed assertions
        String feedBody = responses.get("GET /api/v2/changes/feed").body();
        JsonNode feed = JSON.readTree(feedBody);
        assertTrue(feed.path("events").size() >= 6, "change feed events count: " + feed.path("events").size());
        for (String expectedSource : List.of("policy_rule_history", "device_policy_install", "policy_change_evidence",
                "pan_config", "cp_cpwd_elg", "device_crash_alert", "jobs")) {
            assertTrue(feedBody.contains(expectedSource), "change feed contains " + expectedSource);
        }
        assertTrue(feedBody.contains("FW-"), "change feed masks device pseudonyms");
        assertTrue(feedBody.contains("USR-"), "change feed masks administrator principals");

        // Reports R1 JSON endpoints assertions
        JsonNode catalogue = JSON.readTree(responses.get("GET /api/v2/reports/catalogue").body());
        assertTrue(catalogue.path("templates").size() >= 5, "reports catalogue templates");
        assertTrue(catalogue.path("permissions").path("masked").asBoolean(), "reports catalogue permissions.masked");
        assertFalse(catalogue.path("permissions").path("raw_view").asBoolean(true), "reports catalogue permissions.raw_view");

        String runsBody = responses.get("GET /api/v2/reports/runs").body();
        JsonNode runs = JSON.readTree(runsBody);
        assertEquals(3, runs.path("runs").size(), "reports history returns the 3 MASKED runs only");
        for (ReportFormat fmt : List.of(ReportFormat.CSV, ReportFormat.XLSX, ReportFormat.PDF)) {
            assertTrue(runsBody.contains(ids.get("reportMasked:" + fmt.name())), "runs includes MASKED " + fmt);
            JsonNode detail = JSON.readTree(responses.get("GET /api/v2/reports/runs/{runId}#" + fmt.name()).body());
            assertEquals("SUCCEEDED", detail.path("state").asText());
            assertEquals("MASKED", detail.path("projection").asText());
            assertTrue(detail.path("downloadable").asBoolean(), fmt + " run downloadable");
        }
        assertFalse(runsBody.contains(ids.get("reportRaw")), "aiview /api/v2/reports/runs excludes RAW runs");

        // 2. Assert 0 planted raw values survived on any route.
        assertEquals(Map.of(), leaks, "surviving raw value counts per (route, ValueClass)");
    }

    // ------------------------------------------------------------------ Test 2: Reports R1 CSV/XLSX/PDF downloads + RAW refusal

    @Test
    @Order(2)
    void reportsR1DownloadsCsvXlsxPdfMaskedAndRefusesRawRun() throws Exception {
        assertFalse(AiviewReachableRoutes.aiviewMayCall(ActionRegistry.REPORT_RAW_VIEW),
                "aiview must never hold " + ActionRegistry.REPORT_RAW_VIEW);
        String cookie = loginAsAiview();

        for (ReportFormat format : List.of(ReportFormat.CSV, ReportFormat.XLSX, ReportFormat.PDF)) {
            // Non-vacuity: the scanner must detect planted raw values in the RAW rendering of the same document.
            ScanResult rawScan = scanReportBytes(format, rawReportBytes.get(format));
            Map<ValueClass, Integer> rawHits = count(rawScan.text(), estate.raw());
            assertTrue(rawHits.containsKey(ValueClass.HOSTNAME) && rawHits.containsKey(ValueClass.ADDRESS)
                    && rawHits.containsKey(ValueClass.SERIAL) && rawHits.containsKey(ValueClass.PRINCIPAL),
                    format + " scanner non-vacuity over RAW bytes: " + rawHits.keySet());

            String runId = ids.get("reportMasked:" + format.name());
            Resp dl = get("/api/v2/reports/runs/" + runId + "/download", cookie);
            assertEquals(200, dl.status(), format + " download HTTP status");
            assertTrue(dl.bytes().length > 100, format + " download non-empty bytes");

            ScanResult maskedScan = scanReportBytes(format, dl.bytes());
            if (format == ReportFormat.XLSX) {
                assertTrue(maskedScan.zipEntries().stream().anyMatch(n -> n.startsWith("xl/worksheets/")),
                        "XLSX worksheets unpacked");
                assertTrue(maskedScan.zipEntries().stream().anyMatch(n -> n.startsWith("docProps/")),
                        "XLSX docProps unpacked");
                assertTrue(maskedScan.zipEntries().contains("xl/sharedStrings.xml")
                        || maskedScan.zipEntries().stream().anyMatch(n -> n.endsWith(".xml")),
                        "XLSX string/sheet XML unpacked");
            }
            if (format == ReportFormat.PDF) {
                assertTrue(new String(dl.bytes(), 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"), "PDF header");
            }

            assertTrue(maskedScan.text().contains("MASKED"), format + " stamped MASKED");
            assertTrue(maskedScan.text().contains("FW-"), format + " contains FW- device pseudonyms");
            assertTrue(maskedScan.text().contains("CLS-"), format + " contains CLS- cluster pseudonym");
            assertTrue(maskedScan.text().contains("SN-"), format + " contains SN- serial token");
            assertTrue(maskedScan.text().contains("USR-"), format + " contains USR- actor token");

            Map<ValueClass, Integer> survivors = count(maskedScan.text(), estate.raw());
            assertEquals(Map.of(), survivors, "GET /api/v2/reports/runs/{id}/download (" + format + ") raw survivors");
        }

        // RAW boundary (REPORTS_R1_CONTRACT.md A2): hidden from history, detail and download refused, 0 raw values in error body.
        String rawRunId = ids.get("reportRaw");
        Resp rawDetail = get("/api/v2/reports/runs/" + rawRunId, cookie);
        assertTrue(rawDetail.status() == 403 || rawDetail.status() == 404,
                "RAW run detail refused for aiview, got " + rawDetail.status());
        assertEquals(Map.of(), count(rawDetail.body(), estate.raw()), "RAW run detail error body leaks");

        Resp rawDownload = get("/api/v2/reports/runs/" + rawRunId + "/download", cookie);
        assertTrue(rawDownload.status() == 403 || rawDownload.status() == 404,
                "RAW run download refused for aiview, got " + rawDownload.status());
        assertEquals(Map.of(), count(rawDownload.body(), estate.raw()), "RAW run download error body leaks");
    }

    // ------------------------------------------------------------------ Test 3: Global search RBAC + masked no-oracle search

    @Test
    @Order(3)
    void globalSearchRefusesPlainAiviewAndMasksWithoutOracleMatchWhenPermitted() throws Exception {
        String rawHostPrefix = estate.cpMemberA().hostname().substring(0, 8);
        String rawPanPrefix = estate.panFirewall().hostname().substring(0, 8);
        String rawSerialPrefix = estate.cpMemberA().serial().substring(0, 7);
        String rawPrincipalPrefix = estate.principal().substring(0, 7);

        // 1. Plain aiview (role:viewer + role:replay_viewer) is refused by GateChain on GLOBAL_SEARCH_READ (403, 0 raw values).
        String aiviewCookie = loginAsAiview();
        for (String q : List.of(rawHostPrefix, "FW-")) {
            Resp refused = get("/api/v2/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), aiviewCookie);
            assertEquals(403, refused.status(), "plain aiview refused on /api/v2/search");
            assertEquals(Map.of(), count(refused.body(), estate.raw()), "plain aiview /api/v2/search refusal body leaks");
        }

        // 2. Masked replay-viewer holding GLOBAL_SEARCH_READ exercises the live search handler under masked=true.
        String searchCookie = loginAsMaskedSearchViewer();

        // Searching by masked pseudonym prefix "FW-" returns HTTP 200 with masked devices and evidence, 0 raw values.
        Resp maskedQuery = get("/api/v2/search?q=FW-", searchCookie);
        assertEquals(200, maskedQuery.status(), "masked viewer /api/v2/search?q=FW- status");
        assertEquals("true", maskedQuery.maskedHeader(), "masked viewer X-Nexus-Masked header");
        JsonNode fwJson = JSON.readTree(maskedQuery.body());
        assertTrue(fwJson.path("devices").size() >= 3, "FW- query returns masked devices");
        assertTrue(fwJson.path("evidence").size() >= 1, "FW- query returns masked failed-job evidence");
        assertTrue(maskedQuery.body().contains(estate.cpMemberA().id()), "seeded device id in FW- results");
        assertEquals(Map.of(), count(maskedQuery.body(), estate.raw()), "GET /api/v2/search?q=FW- raw survivors");

        // Searching a configuration setting label returns masked setting excerpts and FW- sample hostnames, 0 raw values.
        Resp ntpQuery = get("/api/v2/search?q=" + URLEncoder.encode("Primary NTP", StandardCharsets.UTF_8), searchCookie);
        assertEquals(200, ntpQuery.status(), "masked viewer setting search status");
        assertTrue(JSON.readTree(ntpQuery.body()).path("settings").size() >= 1, "Primary NTP setting hit");
        assertEquals(Map.of(), count(ntpQuery.body(), estate.raw()), "GET /api/v2/search?q=Primary+NTP raw survivors");

        // Searching by planted raw hostname, serial or principal substrings must NOT oracle-match devices/settings/evidence.
        for (String rawProbe : List.of(rawHostPrefix, rawPanPrefix, rawSerialPrefix, rawPrincipalPrefix,
                estate.domainOrContext())) {
            Resp probe = get("/api/v2/search?q=" + URLEncoder.encode(rawProbe, StandardCharsets.UTF_8), searchCookie);
            assertEquals(200, probe.status(), "masked viewer raw-substring probe status");
            assertEquals("true", probe.maskedHeader(), "masked viewer probe X-Nexus-Masked header");
            JsonNode root = JSON.readTree(probe.body());
            assertEquals(0, root.path("devices").size(), "raw substring must not oracle-match devices under replay_viewer");
            assertEquals(0, root.path("settings").size(), "raw substring must not oracle-match settings under replay_viewer");
            assertEquals(0, root.path("evidence").size(), "raw substring must not oracle-match evidence under replay_viewer");
            assertEquals(Map.of(), count(probe.body(), estate.raw()), "GET /api/v2/search raw-probe survivors");
        }

        // IP search projects every matched interface/object row through visibleIdentity and leaks 0 raw values.
        Resp ipQuery = get("/api/v2/search?q=" + URLEncoder.encode(estate.cpMemberA().interfaceAddress(), StandardCharsets.UTF_8),
                searchCookie);
        assertEquals(200, ipQuery.status(), "masked viewer IP search status");
        assertEquals("true", ipQuery.maskedHeader(), "masked viewer IP search X-Nexus-Masked header");
        assertTrue(JSON.readTree(ipQuery.body()).path("interfaces").size() >= 1, "IP search returns masked interface row");
        assertTrue(ipQuery.body().contains("FW-"), "IP search masks device hostname");
        assertEquals(Map.of(), count(ipQuery.body(), estate.raw()), "GET /api/v2/search?q=<ip> raw survivors");
    }

    // ------------------------------------------------------------------ Test 4: leak counter self-check

    @Test
    @Order(4)
    void counterCatchesEverySpellingWithoutPrefixFalsePositives() {
        String sampleHost = "ist" + tag() + "-fw-09";
        String sampleComment = "adm" + tag() + ".ops@" + sampleHost;
        Map<String, ValueClass> raw = Map.of(
                "192.0.2.15", ValueClass.ADDRESS,
                "2001:db8:a1::7", ValueClass.ADDRESS,
                sampleHost, ValueClass.HOSTNAME,
                sampleComment, ValueClass.SSH_KEY_COMMENT);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ADDRESS, 3),
                count("2001:DB8:A1::7 2001:0db8:00a1:0000:0000:0000:0000:0007 2001:db8:a1:0:0:0:0:7 2001:db8:a1::70", raw));
        assertEquals(Map.of(ValueClass.HOSTNAME, 1, ValueClass.SSH_KEY_COMMENT, 1),
                count("key " + sampleComment.toUpperCase(Locale.ROOT), raw));
        assertEquals(Map.of(), count("{\"hostname\":\"FW-TANGO-04\",\"user\":\"USR-9a1b2c3d\"}", raw));
    }

    // ------------------------------------------------------------------ generic template resolver for full sweep

    private static final String UNKNOWN_ID = "00000000-0000-4000-8000-0000000000aa";

    private static String resolveTemplate(String template) {
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("deviceId", estate.cpMemberA().id());
        byName.put("clusterMemberRef", estate.cluster());
        byName.put("clusterRef", estate.cluster());
        byName.put("clusterId", estate.cluster());
        byName.put("controlId", "CP-SYN-01");
        if (template.startsWith("/devices/{id}")) byName.put("id", estate.cpMemberA().id());
        if (template.startsWith("/discovery/runs/")) byName.put("runId", ids.get("discoveryRunId"));
        if (template.startsWith("/api/v2/reports/runs/")) byName.put("runId", ids.get("reportMasked:CSV"));
        if (template.startsWith("/api/v2/policy/policies/")) byName.put("id", estate.policyId());
        if (template.startsWith("/api/v2/policy/devices/")) byName.put("id", estate.management().id());
        if (template.startsWith("/api/v2/policy/domains/")) byName.put("id", estate.domainId());
        if (template.startsWith("/api/v2/policy/objects/")) byName.put("id", estate.objectId());
        byName.put("chunkId", ids.get("chunk:cp_cpwd_elg"));
        byName.put("jobId", ids.get("failedJobId"));

        Matcher m = Pattern.compile("\\{([^}/:]+)(?::[^}]*)?}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = byName.getOrDefault(m.group(1), UNKNOWN_ID);
            m.appendReplacement(out, Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
