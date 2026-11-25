package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.zip.GZIPOutputStream;

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
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.TarStream;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;

/**
 * Sweep round 6 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection & post-parsing
 * masking law"): seeds non-empty synthetic data across four identity-bearing planes and verifies that {@code aiview}
 * ({@code role:replay_viewer}) receives non-empty masked payloads, HTTP 403 refusals on raw configuration text and
 * support-bundle downloads, HTTP 404 without oracle leakage on raw-hostname cluster member-diff probes, and zero
 * unpinned raw value survivors (with open multi-projection leaks pinned by exact route, value class, and count in
 * {@link #KNOWN_LEAKS}).
 *
 * <ol>
 *   <li>Configuration, Cluster member-diff, Backup compare &amp; Management tree</li>
 *   <li>Support bundles &amp; uploads (V230, V240)</li>
 *   <li>Diagnostics &amp; system-log measurements (V91, V181-V183)</li>
 *   <li>Lifecycle advisories (V205, V214)</li>
 * </ol>
 */
class AiviewConfigBundlesDiagnosticsSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Classification of planted synthetic raw values; assertion messages report only route + class + count. */
    enum ValueClass {
        HOSTNAME,
        CLUSTER_NAME,
        DOMAIN_OR_VS_NAME,
        PANORAMA_NAME,
        INTERFACE_OR_OBJECT_LABEL,
        ADDRESS,
        SERIAL,
        PRINCIPAL,
        SFTP_OR_CASE_REF,
        CONFIG_OR_DIAG_SNIPPET
    }

    /**
     * Expected surviving raw value counts by {@code route -> (value_class -> count)}. Empty now that all four
     * multi-projection leaks identified in sweep round 6 ({@code ConfigurationController} index/override contexts,
     * element paths and Panorama sources; {@code TopologyNamePseudonymizer.knownNamesPattern()} underscore-delimited
     * archive entry hostnames; {@code PrivacyMaskingResponseBodyAdvice} Jackson {@code JsonNode} recursion on
     * support-bundle {@code prechecks}; {@code SupportBundleUploadController} {@code error_detail} and
     * {@code remote_status} withholding/masking) are closed. Kept as an exact-match assertion so any future leak fails
     * the sweep immediately.
     */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.of();

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static SeededEstate estate;
    private static final Map<String, String> ids = new TreeMap<>();

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_sweep_r6");
        work = Files.createTempDirectory("ui2-aiview-sweep-r6-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));
        estate = SeededEstate.generate();
        seedAll(estate);
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(
                        AiviewConfigBundlesDiagnosticsSweepTest::useTestArtefactStore))
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

    // ------------------------------------------------------------------ synthetic raw generators

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String tag() {
        return "r" + hex(3);
    }

    private static String digits(int n) {
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) out.append(RANDOM.nextInt(10));
        return out.toString();
    }

    private static String hex16() {
        return Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff));
    }

    record SeedDevice(String id, String vendor, String role, String hostname, String serial, String mgmtIp,
            String ifaceIp, String softwareVersion, String hotfixLevel, String clusterRef, String haRole) {
    }

    record SeededEstate(
            SeedDevice cpMemberA,
            SeedDevice cpMemberB,
            SeedDevice cpManagement,
            SeedDevice panFirewall,
            SeedDevice asaUnitA,
            SeedDevice asaUnitB,
            SeedDevice fortiManager,
            String cpCluster,
            String asaCluster,
            String cpDomain,
            String vsNameA,
            String vsNameB,
            String mdsStandbyHost,
            String unimportedGwHost,
            String unimportedGwSerial,
            String panTemplateStack,
            String panTemplate,
            String panDeviceGroup,
            String vlanIfaceA,
            String panOverrideObj,
            String policyPkgA,
            String policyPkgB,
            String mgmtIpv6,
            String routeDestIpv4,
            String routeNextHopIpv4,
            String routeDestIpv6,
            String backupEntryIpv4,
            String diagIpv4,
            String diagIpv6,
            String syslogMeasIpv4,
            String adminPrincipal,
            String bundleActor,
            String sftpHost,
            String sftpUser,
            String cxdUser,
            String bundleCaseRef,
            String uploadCaseRef,
            String bundleReasonToken,
            String uploadReasonToken,
            String cpConfigSnippet,
            String asaConfigSnippet,
            String diagSnippet,
            Map<String, ValueClass> raw) {

        static SeededEstate generate() {
            int base = 20 + RANDOM.nextInt(150);
            String cpCluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            String asaCluster = "ASA-HA-" + tag().toUpperCase(Locale.ROOT);

            SeedDevice cpA = new SeedDevice(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "FW-TANGO-" + tag().toUpperCase(Locale.ROOT), "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + base, "198.51.100." + base, "R81.20", "R81.20 Jumbo Take 110", cpCluster, "active");
            SeedDevice cpB = new SeedDevice(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "ist" + tag() + "-fw-02", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 1), "198.51.100." + (base + 1), "R81.20", "R81.20 Jumbo Take 110", cpCluster, "standby");
            SeedDevice cpMgmt = new SeedDevice(Ui2Rows.opaqueId("dev"), "check_point", "management_server",
                    "mds" + tag() + "-smc-01", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 2), null, "R81.20", "R81.20 Jumbo Take 120", null, null);
            SeedDevice pan = new SeedDevice(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway",
                    "pa" + tag() + "-edge-01", "0" + digits(11),
                    "192.0.2." + (base + 3), "198.51.100." + (base + 3), "11.1.4-h1", null, null, null);
            SeedDevice asaA = new SeedDevice(Ui2Rows.opaqueId("dev"), "cisco_asa", "gateway",
                    "asa" + tag() + "-dc-a", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 4), "198.51.100." + (base + 4), "9.18(4)22", null, asaCluster, "active");
            SeedDevice asaB = new SeedDevice(Ui2Rows.opaqueId("dev"), "cisco_asa", "gateway",
                    "asa" + tag() + "-dc-b", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 5), "198.51.100." + (base + 5), "9.18(4)22", null, asaCluster, "standby");
            SeedDevice fmg = new SeedDevice(Ui2Rows.opaqueId("dev"), "fortinet", "management_server",
                    "fmg" + tag() + "-mgr-01", "FMG" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 6), "198.51.100." + (base + 6), "v7.4.3 build2573", null, null, null);

            String cpDomain = "cma-" + tag() + "-retail";
            String vsNameA = "vs-" + tag() + "-payments";
            String vsNameB = "vs-" + tag() + "-treasury";
            String mdsStandbyHost = "mds" + tag() + "-smc-02";
            String unimportedGwHost = "br" + tag() + "-gw-09";
            String unimportedGwSerial = "CP" + hex(5).toUpperCase(Locale.ROOT);

            String panTemplateStack = "stk-" + tag() + "-branch";
            String panTemplate = "tpl-" + tag() + "-edge";
            String panDeviceGroup = "dg-" + tag() + "-cardholder";

            String vlanIfaceA = "vl-" + tag() + "-card";
            String panOverrideObj = "obj-" + tag() + "-paydb";
            String policyPkgA = "pkg-" + tag() + "-core";
            String policyPkgB = "pkg-" + tag() + "-dmz";

            String mgmtIpv6 = "2001:db8:" + hex16() + "::" + hex16();
            String routeDestIpv4 = "198.51.100." + (base + 20);
            String routeNextHopIpv4 = "192.0.2." + (base + 21);
            String routeDestIpv6 = "2001:db8:" + hex16() + ":" + hex16() + "::";
            String backupEntryIpv4 = "198.51.100." + (base + 22);
            String diagIpv4 = "192.0.2." + (base + 23);
            String diagIpv6 = "2001:db8:" + hex16() + "::" + hex16();
            String syslogMeasIpv4 = "192.0.2." + (base + 24);

            String adminPrincipal = "adm" + tag() + ".sec";
            String bundleActor = "tac" + tag() + ".ops";

            String sftpHost = "sftp-" + tag() + ".drop.invalid";
            String sftpUser = "sftpusr-" + tag();
            String cxdUser = "cxd-" + tag() + "-acct";
            String bundleCaseRef = "SR-69" + digits(7);
            String uploadCaseRef = "TAC-" + tag().toUpperCase(Locale.ROOT);
            String bundleReasonToken = "bref-" + tag() + "-justif";
            String uploadReasonToken = "uref-" + tag() + "-justif";

            String cpConfigSnippet = "snmp-contact-" + tag() + "-noc";
            String asaConfigSnippet = "asa-banner-" + tag() + "-soc";
            String diagSnippet = "diag-blade-" + tag() + "-tok";

            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (SeedDevice d : List.of(cpA, cpB, cpMgmt, pan, asaA, asaB, fmg)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.mgmtIp(), ValueClass.ADDRESS);
                if (d.ifaceIp() != null) raw.put(d.ifaceIp(), ValueClass.ADDRESS);
            }
            raw.put(mdsStandbyHost, ValueClass.HOSTNAME);
            raw.put(unimportedGwHost, ValueClass.HOSTNAME);
            raw.put(unimportedGwSerial, ValueClass.SERIAL);

            raw.put(cpCluster, ValueClass.CLUSTER_NAME);
            raw.put(asaCluster, ValueClass.CLUSTER_NAME);

            raw.put(cpDomain, ValueClass.DOMAIN_OR_VS_NAME);
            raw.put(vsNameA, ValueClass.DOMAIN_OR_VS_NAME);
            raw.put(vsNameB, ValueClass.DOMAIN_OR_VS_NAME);

            raw.put(panTemplateStack, ValueClass.PANORAMA_NAME);
            raw.put(panTemplate, ValueClass.PANORAMA_NAME);
            raw.put(panDeviceGroup, ValueClass.PANORAMA_NAME);

            raw.put(vlanIfaceA, ValueClass.INTERFACE_OR_OBJECT_LABEL);
            raw.put(panOverrideObj, ValueClass.INTERFACE_OR_OBJECT_LABEL);
            raw.put(policyPkgA, ValueClass.INTERFACE_OR_OBJECT_LABEL);
            raw.put(policyPkgB, ValueClass.INTERFACE_OR_OBJECT_LABEL);

            for (String addr : List.of(mgmtIpv6, routeDestIpv4, routeNextHopIpv4, routeDestIpv6,
                    backupEntryIpv4, diagIpv4, diagIpv6, syslogMeasIpv4)) {
                raw.put(addr, ValueClass.ADDRESS);
            }

            raw.put(adminPrincipal, ValueClass.PRINCIPAL);
            raw.put(bundleActor, ValueClass.PRINCIPAL);

            raw.put(sftpHost, ValueClass.SFTP_OR_CASE_REF);
            raw.put(sftpUser, ValueClass.SFTP_OR_CASE_REF);
            raw.put(cxdUser, ValueClass.SFTP_OR_CASE_REF);
            raw.put(bundleCaseRef, ValueClass.SFTP_OR_CASE_REF);
            raw.put(uploadCaseRef, ValueClass.SFTP_OR_CASE_REF);
            raw.put(bundleReasonToken, ValueClass.SFTP_OR_CASE_REF);
            raw.put(uploadReasonToken, ValueClass.SFTP_OR_CASE_REF);

            raw.put(cpConfigSnippet, ValueClass.CONFIG_OR_DIAG_SNIPPET);
            raw.put(asaConfigSnippet, ValueClass.CONFIG_OR_DIAG_SNIPPET);
            raw.put(diagSnippet, ValueClass.CONFIG_OR_DIAG_SNIPPET);

            return new SeededEstate(cpA, cpB, cpMgmt, pan, asaA, asaB, fmg,
                    cpCluster, asaCluster, cpDomain, vsNameA, vsNameB, mdsStandbyHost,
                    unimportedGwHost, unimportedGwSerial,
                    panTemplateStack, panTemplate, panDeviceGroup,
                    vlanIfaceA, panOverrideObj, policyPkgA, policyPkgB,
                    mgmtIpv6, routeDestIpv4, routeNextHopIpv4, routeDestIpv6,
                    backupEntryIpv4, diagIpv4, diagIpv6, syslogMeasIpv4,
                    adminPrincipal, bundleActor, sftpHost, sftpUser, cxdUser,
                    bundleCaseRef, uploadCaseRef, bundleReasonToken, uploadReasonToken,
                    cpConfigSnippet, asaConfigSnippet, diagSnippet,
                    Map.copyOf(raw));
        }

        List<SeedDevice> allDevices() {
            return List.of(cpMemberA, cpMemberB, cpManagement, panFirewall, asaUnitA, asaUnitB, fortiManager);
        }
    }

    // ------------------------------------------------------------------ seeding

    private static void seedAll(SeededEstate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            seedDevicesInventoryManagementAndPolicy(c, e, credential);
            seedConfigurationsAndBackups(c, e);
            seedSupportBundlesAndUploads(c, e);
            seedDiagnosticsAndSyslogMeasurements(c, e);
            seedAdvisories(c, e);
        }
    }

    private static void seedDevicesInventoryManagementAndPolicy(Connection c, SeededEstate e, String credential)
            throws Exception {
        audited(c, () -> {
            for (SeedDevice d : e.allDevices()) {
                String matchKey = "check_point".equals(d.vendor())
                        ? "check_point|" + e.cpDomain() + "|" + d.serial()
                        : d.vendor() + "|" + d.serial();
                exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, "
                        + "enrollment_state, disabled, credential_reference_id, role, identity_mismatch_state, "
                        + "peer_follow_outcome, observed_hostname, observed_model, observed_software_version, "
                        + "observed_ha_role, recorded_identity_primary, recorded_identity_recorded_at, "
                        + "cluster_member_ref, backup_target, discovery_match_key) values "
                        + "(?, ?, 'manual', true, 'ENROLLED', false, ?, ?, 'NONE', ?, ?, 'synthetic-appliance', ?, ?, ?, "
                        + "now(), ?, true, ?)",
                        d.id(), d.vendor(), credential, d.role(),
                        d.clusterRef() == null ? "NONE" : "CORROBORATED",
                        d.hostname(), d.softwareVersion(), d.haRole(), d.serial(), d.clusterRef(), matchKey);
                String transport = "palo_alto".equals(d.vendor()) ? "pan_xml_api" : "ssh_exec";
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, ?, ?)",
                        Ui2Rows.opaqueId("ep"), d.id(), transport, d.mgmtIp());
                exec(c, "insert into device_platform_facts(device_id, serial_number, hotfix_level, platform_family, "
                        + "source_read) values (?, ?, ?, 'synthetic', 'synthetic_seed')",
                        d.id(), d.serial(), d.hotfixLevel());
            }
            // Management server IPv6 endpoint
            exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                    + "values (?, ?, 'https', ?, now() + interval '1 second')",
                    Ui2Rows.opaqueId("ep"), e.cpManagement().id(), e.mgmtIpv6());

            // Installed policies on the 2-member cluster (differing names exercise member-diff policy_installed)
            exec(c, "insert into device_policy_install(device_id, policy_name, installed_at_text, source_read) "
                    + "values (?, ?, '2026-11-24T10:00:00Z', 'synthetic_seed')",
                    e.cpMemberA().id(), e.policyPkgA());
            exec(c, "insert into device_policy_install(device_id, policy_name, installed_at_text, source_read) "
                    + "values (?, ?, '2026-11-24T10:05:00Z', 'synthetic_seed')",
                    e.cpMemberB().id(), e.policyPkgB());

            // Seed policy_snapshot + policy_rule_history so adminPrincipal is in FreeTextEstate's user dictionary
            String policyId = "policy-" + UUID.randomUUID();
            String ruleId = "rule-" + UUID.randomUUID();
            var metadata = new PolicySnapshot.Metadata(policyId, e.cpManagement().id(), e.cpManagement().hostname(),
                    "CP", "dom-" + UUID.randomUUID(), e.cpDomain(), e.policyPkgA(), "2026-11-24T10:00:00Z", null,
                    List.of(new PolicySnapshot.Target(e.cpMemberA().id(), e.cpMemberA().hostname(), "", "IN_SYNC")));
            var snapshot = new PolicySnapshot(metadata, List.of(), Map.of(), List.of());
            exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) "
                    + "values (?, '2026-11-24T10:00:00Z'::timestamptz, ?::jsonb, ?::jsonb)",
                    policyId, JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
            exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                    + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now(), "
                    + "'2026-11-24T09:00:00Z', ?, '[]'::jsonb)",
                    policyId, ruleId, ruleId, e.adminPrincipal());

            // MDS domain HA placement for ManagementTreeService.domainHa
            exec(c, "insert into cp_domain_server_placement(source_id, domain_uid, domain_name, server_name, "
                    + "multi_domain_server, active, observed_at) values "
                    + "(?, 'dom-uid-1', ?, 'cma-srv-1', ?, true, now()), "
                    + "(?, 'dom-uid-1', ?, 'cma-srv-2', ?, false, now())",
                    e.cpManagement().id(), e.cpDomain(), e.cpManagement().hostname(),
                    e.cpManagement().id(), e.cpDomain(), e.mdsStandbyHost());

            // Finished discovery run on cpManagement managing the 2-member cluster, a VS, and an unimported gateway
            String discJob = insertJob(c, null, "discovery", "cp_discovery_enumerate", "COMPLETED", null,
                    "discovery_run", "run-pending");
            String discRun = Ui2Rows.opaqueId("disc");
            exec(c, "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                    + "requested_by_actor_fingerprint, state, finished_at, job_id) "
                    + "values (?, 'check_point', ?, ?, ?, 'FINISHED', now(), ?)",
                    discRun, e.cpManagement().mgmtIp(), credential, e.adminPrincipal(), discJob);
            exec(c, "update jobs set target_ref = ? where job_id = ?", discRun, discJob);
            ids.put("discRunId", discRun);

            String clusterCand = Ui2Rows.opaqueId("cand");
            exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, owning_domain, stable_identifier, "
                    + "kind, display_name, management_address, importable) "
                    + "values (?, ?, 'check_point', ?, ?, 'CHECK_POINT_CLUSTER', ?, ?, false)",
                    clusterCand, discRun, e.cpDomain(), e.cpCluster(), e.cpCluster(), e.cpMemberA().mgmtIp());
            for (SeedDevice m : List.of(e.cpMemberA(), e.cpMemberB())) {
                exec(c, "insert into discovery_candidate(candidate_id, run_id, parent_candidate_id, vendor, "
                        + "owning_domain, stable_identifier, kind, display_name, own_address, management_address, "
                        + "cluster_reference, model, software_version, importable, import_outcome) "
                        + "values (?, ?, ?, 'check_point', ?, ?, 'CHECK_POINT_GATEWAY', ?, ?, ?, ?, "
                        + "'synthetic-appliance', 'R81.20', true, 'already_imported')",
                        Ui2Rows.opaqueId("cand"), discRun, clusterCand, e.cpDomain(), m.serial(), m.hostname(),
                        m.mgmtIp(), e.cpManagement().mgmtIp(), e.cpCluster());
            }
            exec(c, "insert into discovery_candidate(candidate_id, run_id, parent_candidate_id, vendor, owning_domain, "
                    + "stable_identifier, kind, display_name, management_address, importable) "
                    + "values (?, ?, ?, 'check_point', ?, 'vs-uid-1', 'CHECK_POINT_VIRTUAL_SYSTEM', ?, ?, false)",
                    Ui2Rows.opaqueId("cand"), discRun, clusterCand, e.cpDomain(), e.vsNameA(), e.cpMemberA().mgmtIp());
            exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, owning_domain, stable_identifier, "
                    + "kind, display_name, own_address, management_address, model, software_version, importable) "
                    + "values (?, ?, 'check_point', ?, ?, 'CHECK_POINT_GATEWAY', ?, ?, ?, 'synthetic-appliance', "
                    + "'R81.20', true)",
                    Ui2Rows.opaqueId("cand"), discRun, e.cpDomain(), e.unimportedGwSerial(), e.unimportedGwHost(),
                    e.routeNextHopIpv4(), e.cpManagement().mgmtIp());
            String unimportedMatchKey = "check_point|" + e.cpDomain() + "|" + e.unimportedGwSerial();
            exec(c, "insert into discovery_acknowledgement(discovery_match_key, acknowledged_by_actor_fingerprint, reason) "
                    + "values (?, ?, ?)",
                    unimportedMatchKey, e.adminPrincipal(),
                    "lab gateway " + e.unimportedGwHost() + " at " + e.routeNextHopIpv4() + " noted by " + e.adminPrincipal());

            // Inventory runs for cpMemberA and cpMemberB with differing VS, interfaces, addresses, and routes
            String invJobA = insertJob(c, e.cpMemberA().id(), "inventory_collect", "cp_inventory_collect",
                    "COMPLETED", null, "device", null);
            String invRunA = Ui2Rows.opaqueId("inv");
            exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count, virtual_systems) "
                    + "values (?, ?, ?, now(), 2, ?)",
                    invRunA, e.cpMemberA().id(), invJobA, e.vsNameA() + ", " + e.vsNameB());
            String itfA1 = Ui2Rows.opaqueId("itf");
            exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state, zone) "
                    + "values (?, ?, 'physical', 'eth1', 'physical', 'up', 'internal')", itfA1, invRunA);
            exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                    + "values (?, ?, ?, 'ipv4', 'member')",
                    Ui2Rows.opaqueId("addr"), itfA1, e.cpMemberA().ifaceIp() + "/24");
            String itfA2 = Ui2Rows.opaqueId("itf");
            exec(c, "insert into device_interface(interface_id, run_id, context, name, parent, kind, state, vlan_id, zone) "
                    + "values (?, ?, 'physical', ?, 'eth1', 'vlan', 'up', 210, 'dmz')",
                    itfA2, invRunA, e.vlanIfaceA());
            String itfVs = Ui2Rows.opaqueId("itf");
            exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state) "
                    + "values (?, ?, '1', 'wrp64', 'other', 'up')", itfVs, invRunA);
            exec(c, "insert into device_route(route_id, run_id, context, destination, next_hop, interface, protocol) "
                    + "values (?, ?, 'physical', ?, ?, ?, 'static'), (?, ?, 'physical', ?, null, 'eth1', 'connected')",
                    Ui2Rows.opaqueId("rt"), invRunA, e.routeDestIpv4() + "/24", e.routeNextHopIpv4(), e.vlanIfaceA(),
                    Ui2Rows.opaqueId("rt"), invRunA, e.routeDestIpv6() + "/64");
            exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                    + "values (?, ?, 'physical', 'active', 'High Availability', 'cp_cphaprob_stat')",
                    Ui2Rows.opaqueId("ha"), invRunA);

            String invJobB = insertJob(c, e.cpMemberB().id(), "inventory_collect", "cp_inventory_collect",
                    "COMPLETED", null, "device", null);
            String invRunB = Ui2Rows.opaqueId("inv");
            exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count, virtual_systems) "
                    + "values (?, ?, ?, now(), 1, ?)",
                    invRunB, e.cpMemberB().id(), invJobB, e.vsNameA());
            String itfB1 = Ui2Rows.opaqueId("itf");
            exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state, zone) "
                    + "values (?, ?, 'physical', 'eth1', 'physical', 'up', 'internal')", itfB1, invRunB);
            exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                    + "values (?, ?, ?, 'ipv4', 'member')",
                    Ui2Rows.opaqueId("addr"), itfB1, e.cpMemberB().ifaceIp() + "/24");
            exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                    + "values (?, ?, 'physical', 'standby', 'High Availability', 'cp_cphaprob_stat')",
                    Ui2Rows.opaqueId("ha"), invRunB);

            // FortiManager inventory run with physical ports for GET /api/v2/diagnostics/ports?device_id={fmgId}
            String invJobFmg = insertJob(c, e.fortiManager().id(), "inventory_collect", "fmg_discovery_enumerate",
                    "COMPLETED", null, "device", null);
            String invRunFmg = Ui2Rows.opaqueId("inv");
            exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) "
                    + "values (?, ?, ?, now(), 1)", invRunFmg, e.fortiManager().id(), invJobFmg);
            exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state) "
                    + "values (?, ?, 'physical', 'port1', 'physical', 'up'), (?, ?, 'physical', 'port2', 'physical', 'up')",
                    Ui2Rows.opaqueId("itf"), invRunFmg, Ui2Rows.opaqueId("itf"), invRunFmg);
        });
    }

    private static void seedConfigurationsAndBackups(Connection c, SeededEstate e) throws Exception {
        // Encrypted Check Point configs for cpMemberA and cpMemberB
        String cpTextA = "set hostname " + e.cpMemberA().hostname() + "\n"
                + "set interface eth1 ipv4-address " + e.cpMemberA().ifaceIp() + " mask-length 24\n"
                + "set snmp contact " + e.cpConfigSnippet() + "\n";
        var cpMetaA = storeEncrypted(e.cpMemberA().id(), "cfg-cp-a", "check_point", false,
                cpTextA.getBytes(StandardCharsets.UTF_8));

        String cpTextB = "set hostname " + e.cpMemberB().hostname() + "\n"
                + "set interface eth1 ipv4-address " + e.cpMemberB().ifaceIp() + " mask-length 24\n";
        var cpMetaB = storeEncrypted(e.cpMemberB().id(), "cfg-cp-b", "check_point", false,
                cpTextB.getBytes(StandardCharsets.UTF_8));

        // Encrypted Palo Alto effective_running + supplementary active config
        String panXml = "<config><devices><entry><vsys><entry name=\"" + e.vsNameA() + "\"><address><entry name=\""
                + e.panOverrideObj() + "\" src=\"local\"><ip-netmask>" + e.panFirewall().ifaceIp()
                + "/32</ip-netmask></entry></address></entry></vsys></entry></devices></config>";
        var panMetaPrimary = storeEncrypted(e.panFirewall().id(), "cfg-pan-eff", "palo_alto", true,
                gzip(panXml.getBytes(StandardCharsets.UTF_8)));
        var panMetaActive = storeEncrypted(e.panFirewall().id(), "cfg-pan-act", "palo_alto", false,
                panXml.getBytes(StandardCharsets.UTF_8));

        // Encrypted Cisco ASA configs + newest backup + asa_config_relation
        String asaText = "ASA Version 9.18(4)22\nhostname " + e.asaUnitA().hostname() + "\nbanner motd "
                + e.asaConfigSnippet() + "\nip address " + e.asaUnitA().ifaceIp() + " 255.255.255.0\n";
        var asaCfgMeta = storeEncrypted(e.asaUnitA().id(), "cfg-asa-a", "cisco_asa", false,
                asaText.getBytes(StandardCharsets.UTF_8));
        var asaBkMeta = storeEncrypted(e.asaUnitA().id(), "bk-asa-a", "cisco_asa", true,
                gzip(asaText.getBytes(StandardCharsets.UTF_8)));

        // Two Check Point backup artefacts on cpMemberA for GET /backups/{leftId}/compare?with={rightId}
        var bkLeftMeta = storeEncrypted(e.cpMemberA().id(), "bk-cp-left", "check_point", true,
                gzip(("left " + e.cpMemberA().hostname()).getBytes(StandardCharsets.UTF_8)));
        var bkRightMeta = storeEncrypted(e.cpMemberA().id(), "bk-cp-right", "check_point", true,
                gzip(("right " + e.cpMemberA().hostname()).getBytes(StandardCharsets.UTF_8)));

        String backupLeftId = UUID.randomUUID().toString();
        String backupRightId = UUID.randomUUID().toString();
        String asaBackupId = UUID.randomUUID().toString();

        audited(c, () -> {
            // 1. cpMemberA configuration (changed run + index + deviation summary)
            String cfgJobCpA = insertJob(c, e.cpMemberA().id(), "configuration_collect", "cp_configuration_collect",
                    "COMPLETED", null, "device", null);
            insertConfigArtefactAndBackupRow(c, cpMetaA, e.cpMemberA(), cfgJobCpA, "none");
            String runCpA = Ui2Rows.opaqueId("cfgrun");
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                    + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, "
                    + "sanitized_text, change_state) values (?, ?, ?, now(), 'check_point', 'show_configuration', "
                    + "true, ?, ?, ?, ?, 2, ?, 'changed')",
                    runCpA, e.cpMemberA().id(), cfgJobCpA, cpMetaA.plaintextSha256(), cpMetaA.plaintextSha256(),
                    cpMetaA.plaintextBytes(), cpMetaA.ref().value(), cpTextA);
            exec(c, "insert into device_configuration_index(index_id, run_id, context, section, source, entry_count) "
                    + "values (?, ?, 'physical', 'interface', null, 4), (?, ?, 'physical', 'static-route', null, 6)",
                    UUID.randomUUID().toString(), runCpA, UUID.randomUUID().toString(), runCpA);
            String devSumId = UUID.randomUUID().toString();
            exec(c, "insert into configuration_run_deviation_summary(summary_id, run_id, status) "
                    + "values (?, ?, 'COMPUTED')", devSumId, runCpA);
            exec(c, "insert into configuration_run_deviation_entry(entry_id, summary_id, context, section, source, "
                    + "kind, old_count, new_count) values (?, ?, 'physical', 'static-route', null, 'RECOUNTED', 4, 6)",
                    UUID.randomUUID().toString(), devSumId);

            // 2. cpMemberB configuration (fewer index entries -> member-diff configuration_sections & hash differ)
            String cfgJobCpB = insertJob(c, e.cpMemberB().id(), "configuration_collect", "cp_configuration_collect",
                    "COMPLETED", null, "device", null);
            insertConfigArtefactAndBackupRow(c, cpMetaB, e.cpMemberB(), cfgJobCpB, "none");
            String runCpB = Ui2Rows.opaqueId("cfgrun");
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                    + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, "
                    + "sanitized_text, change_state) values (?, ?, ?, now(), 'check_point', 'show_configuration', "
                    + "true, ?, ?, ?, ?, 1, ?, 'first_run')",
                    runCpB, e.cpMemberB().id(), cfgJobCpB, cpMetaB.plaintextSha256(), cpMetaB.plaintextSha256(),
                    cpMetaB.plaintextBytes(), cpMetaB.ref().value(), cpTextB);
            exec(c, "insert into device_configuration_index(index_id, run_id, context, section, source, entry_count) "
                    + "values (?, ?, 'physical', 'interface', null, 4)",
                    UUID.randomUUID().toString(), runCpB);

            // 3. panFirewall configuration: primary effective_running + supplementary active + index + overrides + panorama_assignment
            String cfgJobPan = insertJob(c, e.panFirewall().id(), "configuration_collect", "pan_configuration_collect",
                    "COMPLETED", null, "device", null);
            insertConfigArtefactAndBackupRow(c, panMetaPrimary, e.panFirewall(), cfgJobPan, "gzip");
            insertConfigArtefactAndBackupRow(c, panMetaActive, e.panFirewall(), cfgJobPan, "none");
            String runPanPrimary = Ui2Rows.opaqueId("cfgrun");
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                    + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, "
                    + "sanitized_text, change_state) values (?, ?, ?, now(), 'palo_alto', 'effective_running', "
                    + "true, ?, ?, ?, ?, 0, null, 'changed')",
                    runPanPrimary, e.panFirewall().id(), cfgJobPan, panMetaPrimary.plaintextSha256(),
                    panMetaPrimary.plaintextSha256(), panMetaPrimary.plaintextBytes(), panMetaPrimary.ref().value());
            String runPanActive = Ui2Rows.opaqueId("cfgrun");
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                    + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, "
                    + "sanitized_text, change_state) values (?, ?, ?, now(), 'palo_alto', 'active', "
                    + "false, ?, ?, ?, ?, 3, ?, 'first_run')",
                    runPanActive, e.panFirewall().id(), cfgJobPan, panMetaActive.plaintextSha256(),
                    panMetaActive.plaintextSha256(), panMetaActive.plaintextBytes(), panMetaActive.ref().value(), panXml);
            exec(c, "insert into device_configuration_index(index_id, run_id, context, section, source, entry_count) "
                    + "values (?, ?, ?, 'address', 'local', 3), (?, ?, 'network', 'interface', 'tpl', 2)",
                    UUID.randomUUID().toString(), runPanPrimary, e.vsNameA(),
                    UUID.randomUUID().toString(), runPanPrimary);
            exec(c, "insert into device_configuration_override(override_id, run_id, context, category, element_path, "
                    + "panorama_source) values (?, ?, ?, 'address', ?, ?), (?, ?, 'network', 'interface', ?, ?)",
                    UUID.randomUUID().toString(), runPanPrimary, e.vsNameA(),
                    "address/" + e.panOverrideObj(), "template:" + e.panTemplate(),
                    UUID.randomUUID().toString(), runPanPrimary,
                    "interface/" + e.vlanIfaceA(), "device-group:" + e.panDeviceGroup());
            exec(c, "insert into panorama_assignment(assignment_id, device_serial, template_stack, templates, device_groups) "
                    + "values (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), e.panFirewall().serial(), e.panTemplateStack(),
                    e.panTemplate(), e.panDeviceGroup());

            // 4. asaUnitA configuration + backup + asa_config_relation
            String cfgJobAsa = insertJob(c, e.asaUnitA().id(), "configuration_collect", "asa_configuration_collect",
                    "COMPLETED", null, "device", null);
            insertConfigArtefactAndBackupRow(c, asaCfgMeta, e.asaUnitA(), cfgJobAsa, "none");
            String runAsa = Ui2Rows.opaqueId("cfgrun");
            exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                    + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, withheld_line_count, "
                    + "sanitized_text, change_state) values (?, ?, ?, now(), 'cisco_asa', 'show_configuration', "
                    + "true, ?, ?, ?, ?, 1, ?, 'first_run')",
                    runAsa, e.asaUnitA().id(), cfgJobAsa, asaCfgMeta.plaintextSha256(), asaCfgMeta.plaintextSha256(),
                    asaCfgMeta.plaintextBytes(), asaCfgMeta.ref().value(), asaText);
            exec(c, "insert into device_configuration_index(index_id, run_id, context, section, source, entry_count) "
                    + "values (?, ?, 'system', 'interface', null, 2)",
                    UUID.randomUUID().toString(), runAsa);
            List<String> asaSerials = new ArrayList<>(List.of(e.asaUnitA().serial(), e.asaUnitB().serial()));
            Collections.sort(asaSerials);
            exec(c, "insert into ha_pair_identity(cluster_member_ref, vendor, unit_serial_low, unit_serial_high, "
                    + "recorded_by, last_state) values (?, 'cisco_asa', ?, ?, 'synthetic_seed', 'MATCH')",
                    e.asaCluster(), asaSerials.get(0), asaSerials.get(1));
            insertBackupRow(c, asaBackupId, e.asaUnitA(), "backup", asaBkMeta, null);
            exec(c, "insert into asa_config_relation(artefact_id, device_id, relation, lines_compared, lines_differing, "
                    + "lines_equal_by_mask, differing_keywords) values (?, ?, 'MISMATCH', 120, 3, 2, "
                    + "'{\"access-list\":2,\"object\":1}'::jsonb)",
                    asaBackupId, e.asaUnitA().id());

            // 5. Two backup artefacts + entry listings on cpMemberA for GET /backups/{leftId}/compare?with={rightId}
            insertBackupRow(c, backupLeftId, e.cpMemberA(), "backup", bkLeftMeta, "now() - interval '2 hours'");
            insertBackupRow(c, backupRightId, e.cpMemberA(), "backup", bkRightMeta, "now() - interval '1 hour'");
            exec(c, "insert into backup_artefact_content_listing(artefact_id, state, entry_count, listed_at) "
                    + "values (?, 'LISTED', 3, now()), (?, 'LISTED', 3, now())",
                    backupLeftId, backupRightId);
            String sharedPath = "etc/routed.conf";
            String changedPath = "var/backups/" + e.cpMemberA().hostname() + "/" + e.backupEntryIpv4() + "/db.tgz";
            String removedPath = "var/log/" + e.cpMemberB().hostname() + "/messages.0";
            String addedPath = "var/log/hcp_report_" + e.cpMemberA().hostname() + "_25_11_26_03_00.tar.gz";
            exec(c, "insert into backup_artefact_entry(artefact_id, entry_path, entry_type, entry_bytes, entry_sha256) values "
                    + "(?, ?, 'file', 128, ?), (?, ?, 'file', 1024, ?), (?, ?, 'file', 512, ?), "
                    + "(?, ?, 'file', 128, ?), (?, ?, 'file', 2048, ?), (?, ?, 'file', 768, ?)",
                    backupLeftId, sharedPath, "1".repeat(64),
                    backupLeftId, changedPath, "2".repeat(64),
                    backupLeftId, removedPath, "3".repeat(64),
                    backupRightId, sharedPath, "1".repeat(64),
                    backupRightId, changedPath, "4".repeat(64),
                    backupRightId, addedPath, "5".repeat(64));
        });
        ids.put("backupLeftId", backupLeftId);
        ids.put("backupRightId", backupRightId);
    }

    private static void seedSupportBundlesAndUploads(Connection c, SeededEstate e) throws Exception {
        UUID cpBundleId = UUID.randomUUID();
        UUID cpItemExtrasId = UUID.randomUUID();
        UUID cpItemCpinfoId = UUID.randomUUID();
        UUID asaBundleId = UUID.randomUUID();
        UUID asaItemCrashId = UUID.randomUUID();
        UUID uploadCxdId = UUID.randomUUID();
        UUID uploadSftpId = UUID.randomUUID();

        // Build a valid tar.gz inner archive for cp_gaia_extras and a text.gz for asa_crashinfo
        ByteArrayOutputStream tarGz = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(tarGz)) {
            TarStream.Writer tar = new TarStream.Writer(gz);
            tar.entry("items/cp_gaia_extras/show-version-all.txt",
                    ("Product version Check Point Gaia R81.20\n").getBytes(StandardCharsets.UTF_8), 1_700_000_000L);
            tar.finish();
        }
        var cpSbMeta = storeEncrypted(e.cpMemberA().id(), "sb-cp", "check_point", false, tarGz.toByteArray());
        var asaSbMeta = storeEncrypted(e.asaUnitA().id(), "sb-asa", "cisco_asa", false,
                ("Saved_Crash " + e.asaUnitA().hostname() + "\n").getBytes(StandardCharsets.UTF_8));

        String cpSbArtId = "sbart-" + UUID.randomUUID();
        String asaSbArtId = "sbart-" + UUID.randomUUID();

        String cpPrecheckJson = JSON.writeValueAsString(Map.of(
                "cpu_max_core_pct", 28,
                "disk_free_bytes", 15_032_385_536L,
                "cpdata_collector_present", false,
                "host_note", "checked " + e.cpMemberA().hostname() + " at " + e.cpMemberA().mgmtIp()
                        + " serial " + e.cpMemberA().serial()));

        audited(c, () -> {
            String sbJobCp = insertJob(c, e.cpMemberA().id(), "support_bundle_collect", "cp_support_bundle",
                    "COMPLETED", null, "device", null);
            insertBackupRow(c, cpSbArtId, e.cpMemberA(), "support_bundle", cpSbMeta, "now() + interval '14 days'");
            exec(c, "insert into support_bundle_request(id, device_id, vendor, platform_role, run_mode, run_at, "
                    + "case_ref, reason, requested_by, requested_at, started_at, finished_at, expires_at, job_id, "
                    + "precheck_json, state, outcome) values "
                    + "(?::uuid, ?, 'check_point', 'cp_gaia_gateway', 'NOW', now() - interval '5 minutes', "
                    + "?, ?, ?, now() - interval '5 minutes', now() - interval '4 minutes', now() - interval '2 minutes', "
                    + "now() + interval '14 days', ?, ?::jsonb, 'PARTIAL', 'PARTIAL')",
                    cpBundleId.toString(), e.cpMemberA().id(), e.bundleCaseRef(),
                    "TAC collection " + e.bundleReasonToken() + " for " + e.cpMemberA().hostname(),
                    e.bundleActor(), sbJobCp, cpPrecheckJson);
            exec(c, "insert into support_bundle_item(id, request_id, ordinal, kind, state, outcome_code, "
                    + "outcome_detail, archive_entry_name, artefact_id, size_bytes, sha256, redaction_ruleset, "
                    + "gate_ids_used, started_at, finished_at) values "
                    + "(?::uuid, ?::uuid, 1, 'cp_gaia_extras', 'COMPLETED', 'COMPLETED', ?, "
                    + "'items/cp_gaia_extras/', ?, ?, ?, 'cp-text-v1', ARRAY['cp_sb_show_version_all'], "
                    + "now() - interval '4 minutes', now() - interval '3 minutes'), "
                    + "(?::uuid, ?::uuid, 2, 'cp_cpinfo', 'REFUSED_PRECHECK', 'CPU_HIGH', ?, "
                    + "null, null, null, null, 'none', ARRAY['cp_sb_cpu_multi'], "
                    + "now() - interval '3 minutes', now() - interval '2 minutes')",
                    cpItemExtrasId.toString(), cpBundleId.toString(),
                    "collected on " + e.cpMemberA().hostname() + " (" + e.cpMemberA().mgmtIp() + ") serial "
                            + e.cpMemberA().serial(),
                    cpSbArtId, cpSbMeta.plaintextBytes(), cpSbMeta.plaintextSha256(),
                    cpItemCpinfoId.toString(), cpBundleId.toString(),
                    "CPU core 1 at 78% on " + e.cpMemberA().hostname() + " requested by " + e.adminPrincipal());

            String sbJobAsa = insertJob(c, e.asaUnitA().id(), "support_bundle_collect", "asa_support_bundle",
                    "COMPLETED", null, "device", null);
            insertBackupRow(c, asaSbArtId, e.asaUnitA(), "support_bundle", asaSbMeta, "now() + interval '14 days'");
            exec(c, "insert into support_bundle_request(id, device_id, vendor, platform_role, run_mode, run_at, "
                    + "case_ref, reason, requested_by, requested_at, started_at, finished_at, expires_at, job_id, "
                    + "precheck_json, state, outcome) values "
                    + "(?::uuid, ?, 'cisco_asa', 'cisco_asa', 'NOW', now() - interval '10 minutes', "
                    + "?, ?, ?, now() - interval '10 minutes', now() - interval '9 minutes', now() - interval '8 minutes', "
                    + "now() + interval '14 days', ?, '{\"asa_cpu_pct\":14}'::jsonb, 'COMPLETED', 'COMPLETED')",
                    asaBundleId.toString(), e.asaUnitA().id(), e.bundleCaseRef(),
                    "ASA bundle " + e.bundleReasonToken(), e.bundleActor(), sbJobAsa);
            exec(c, "insert into support_bundle_item(id, request_id, ordinal, kind, state, outcome_code, "
                    + "outcome_detail, archive_entry_name, artefact_id, size_bytes, sha256, redaction_ruleset, "
                    + "gate_ids_used, started_at, finished_at) values "
                    + "(?::uuid, ?::uuid, 1, 'asa_crashinfo', 'COMPLETED', 'COMPLETED', ?, "
                    + "'items/asa_crashinfo/show-crashinfo.txt', ?, ?, ?, 'cp-text-v1', ARRAY['asa_show_version'], "
                    + "now() - interval '9 minutes', now() - interval '8 minutes')",
                    asaItemCrashId.toString(), asaBundleId.toString(),
                    "captured crashinfo on " + e.asaUnitA().hostname() + " at " + e.asaUnitA().mgmtIp(),
                    asaSbArtId, asaSbMeta.plaintextBytes(), asaSbMeta.plaintextSha256());

            // Upload 1: vendor HTTPS destination (cisco_cxd_https)
            exec(c, "insert into support_bundle_upload(id, request_id, item_ids, destination_kind, destination_host, "
                    + "destination_port, remote_user, remote_name, case_ref, reason, requested_by, requested_at, "
                    + "started_at, finished_at, heartbeat_at, state, attempts, bytes_total, bytes_sent, sha256, "
                    + "remote_status, error_code, error_detail, host_key_fingerprint, host_key_changed) values "
                    + "(?::uuid, ?::uuid, ARRAY[?::uuid], 'cisco_cxd_https', 'cxd.cisco.com', 443, ?, "
                    + "'nexus-support-bundle-cisco_asa.tgz', ?, ?, ?, now() - interval '7 minutes', "
                    + "now() - interval '7 minutes', now() - interval '6 minutes', now() - interval '6 minutes', "
                    + "'COMPLETED', 1, 512, 512, ?, ?, null, null, null, false)",
                    uploadCxdId.toString(), asaBundleId.toString(), asaItemCrashId.toString(),
                    e.cxdUser(), e.bundleCaseRef(),
                    "Vendor CXD upload " + e.uploadReasonToken(), e.bundleActor(),
                    "a".repeat(64), "HTTP 201 Created for case " + e.bundleCaseRef() + " user " + e.cxdUser());

            // Upload 2: generic_sftp with planted raw destination_host, remote_user, case_ref, reason, error_detail, remote_status
            exec(c, "insert into support_bundle_upload(id, request_id, item_ids, destination_kind, destination_host, "
                    + "destination_port, remote_user, remote_name, case_ref, reason, requested_by, requested_at, "
                    + "started_at, finished_at, heartbeat_at, state, attempts, bytes_total, bytes_sent, sha256, "
                    + "remote_status, error_code, error_detail, host_key_fingerprint, host_key_changed) values "
                    + "(?::uuid, ?::uuid, ARRAY[?::uuid], 'generic_sftp', ?, 22, ?, "
                    + "'nexus-support-bundle-cisco_asa.tgz', ?, ?, ?, now() - interval '5 minutes', "
                    + "now() - interval '5 minutes', now() - interval '4 minutes', now() - interval '4 minutes', "
                    + "'FAILED', 2, 512, 0, ?, ?, 'CONNECTION_FAILED', ?, 'SHA256:synthetic-fp', true)",
                    uploadSftpId.toString(), asaBundleId.toString(), asaItemCrashId.toString(),
                    e.sftpHost(), e.sftpUser(), e.uploadCaseRef(),
                    "SFTP drop upload " + e.uploadReasonToken(), e.bundleActor(),
                    "b".repeat(64),
                    "SFTP refused user " + e.sftpUser() + " at " + e.sftpHost(),
                    "Could not reach " + e.sftpHost() + ":22 (ConnectException). The bank firewall may need an opening "
                            + "for this destination from the Overwatch host.");
        });
        ids.put("cpBundleId", cpBundleId.toString());
        ids.put("asaBundleId", asaBundleId.toString());
        ids.put("uploadCxdId", uploadCxdId.toString());
        ids.put("uploadSftpId", uploadSftpId.toString());
    }

    private static void seedDiagnosticsAndSyslogMeasurements(Connection c, SeededEstate e) throws Exception {
        // Diagnostic job id MUST be a valid UUID (DiagnosticService.result parses UUID.fromString)
        String diagJobId = UUID.randomUUID().toString();
        String diagPlain = "Product version Check Point Gaia R81.20\n"
                + "Hostname: " + e.cpMemberA().hostname() + "\n"
                + "Address: " + e.diagIpv4() + "\n"
                + "IPv6: " + e.diagIpv6() + "\n"
                + "Serial-Number: " + e.cpMemberA().serial() + "\n"
                + "Blade: " + e.diagSnippet() + "\n";
        var diagMeta = storeEncrypted(e.cpMemberA().id(), diagJobId, "check_point", false,
                diagPlain.getBytes(StandardCharsets.UTF_8));

        String measCpId = UUID.randomUUID().toString();
        String measPanId = UUID.randomUUID().toString();
        String measCpSummary = JSON.writeValueAsString(Map.of(
                "status", "MEASURED",
                "schema", "cp_syslog_numeric_v1",
                "kind", "stat",
                "files", List.of(Map.of("class", "messages", "present", true, "sizeBytes", 4_821_012,
                        "mtimeEpoch", 1_760_699_400))));
        String measPanSummary = JSON.writeValueAsString(Map.of(
                "status", "UNKNOWN",
                "reason", "LOG_JOB_NOT_FINISHED",
                "detail", "poll timeout on " + e.panFirewall().hostname() + " at " + e.syslogMeasIpv4()
                        + " serial " + e.panFirewall().serial() + " for " + e.adminPrincipal()));

        audited(c, () -> {
            exec(c, "insert into jobs(job_id, job_type, capability_id, target_kind, target_device_id, "
                    + "submitted_by_actor_fingerprint, submitted_at, finished_at, idempotency_key, action_class, "
                    + "state, diagnostic_gate_id, diagnostic_command, diagnostic_output_ref, diagnostic_output_key, "
                    + "diagnostic_exit_status, diagnostic_line_count) values "
                    + "(?, 'diagnostic_read', 'diagnostic_read', 'device', ?, ?, now() - interval '3 minutes', "
                    + "now() - interval '2 minutes', ?, 'read', 'COMPLETED', 'cp_configuration_show_version_all', "
                    + "'clish -c ''show version all''', ?, ?, 0, 6)",
                    diagJobId, e.cpMemberA().id(), e.adminPrincipal(), "diagnostic:" + diagJobId,
                    diagMeta.ref().value(), diagMeta.wrappedDataKey());

            String panMeasJobId = insertJob(c, e.panFirewall().id(), "pan_syslog_measure", "pan_syslog_measure",
                    "COMPLETED", null, "device", null);
            exec(c, "insert into system_log_measurement(measurement_id, device_id, vendor, source, measured_at, "
                    + "summary, job_id) values (?, ?, 'check_point', 'cp_syslog_measure_stat', now(), ?::jsonb, ?), "
                    + "(?, ?, 'palo_alto', 'pan_system', now(), ?::jsonb, ?)",
                    measCpId, e.cpMemberA().id(), measCpSummary, diagJobId,
                    measPanId, e.panFirewall().id(), measPanSummary, panMeasJobId);
        });
        ids.put("diagJobId", diagJobId);
        ids.put("measCpId", measCpId);
        ids.put("measPanId", measPanId);
    }

    private static void seedAdvisories(Connection c, SeededEstate e) throws Exception {
        String panAdvId = "paloalto:CVE-2024-3400";
        String cpAdvId = "checkpoint:CVE-2024-24919";
        String ciscoAdvId = "cisco:cisco-sa-asa-synthetic";

        String panRule = "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":\"11.1.0\","
                + "\"to\":\"11.1.10\",\"toInclusive\":false,\"changes\":[]}]}";
        String cpRule = "{\"v\":1,\"rules\":[{\"k\":\"range\",\"status\":\"AFFECTED\",\"from\":null,"
                + "\"to\":\"R81.20 Jumbo Take 119\",\"toInclusive\":true,\"changes\":[]}]}";
        String ciscoRule = "{\"v\":1,\"rules\":[{\"k\":\"exact\",\"status\":\"AFFECTED\","
                + "\"versions\":[\"9.18.4.22\"]}]}";

        // Advisory tables carry no audit trigger (machine-ingested public data, V205 / V214)
        for (String src : List.of("paloalto", "checkpoint", "cisco", "cisa_kev")) {
            exec(c, "insert into advisory_feed_run(run_id, source, started_at, ended_at, outcome, "
                    + "documents_received, rejected, advisories_stored, advisories_needing_review, "
                    + "documents_fetched, documents_failed) values (?, ?, now() - interval '10 minutes', "
                    + "now() - interval '9 minutes', 'OK', 1, 0, 1, 0, 1, 0)",
                    "advrun-" + src + "-" + hex(4), src);
        }
        exec(c, "insert into checkpoint_jumbo_statement(train, take, cve_id, kind, product_text, sk_ref, evidence, "
                + "source_url, content_sha256) values ('R81.20', 120, 'CVE-2024-24919', 'FIX', 'Security Gateway', "
                + "'sk182243', 'Resolved CVE-2024-24919. Refer to sk182243.', "
                + "'https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html', ?)",
                "c".repeat(64));
        exec(c, "insert into checkpoint_jumbo_page_load(train, loaded_at, statements, source_url, content_sha256) "
                + "values ('R81.20', now(), 1, "
                + "'https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html', ?)",
                "c".repeat(64));

        exec(c, "insert into advisory(advisory_id, source, title, severity, cvss_score, cve_ids, published_on, "
                + "updated_on, source_url, parse_state, content_sha256) values "
                + "(?, 'paloalto', 'PAN-OS GlobalProtect synthetic advisory', 'CRITICAL', 10.0, "
                + "ARRAY['CVE-2024-3400'], current_date - 10, current_date - 5, "
                + "'https://security.paloaltonetworks.com/CVE-2024-3400', 'PARSED', ?), "
                + "(?, 'checkpoint', 'Check Point Security Gateway information disclosure', 'CRITICAL', 8.6, "
                + "ARRAY['CVE-2024-24919'], current_date - 12, current_date - 6, "
                + "'https://support.checkpoint.com/results/sk/sk182243', 'PARSED', ?), "
                + "(?, 'cisco', 'Cisco ASA Software synthetic vulnerability', 'HIGH', 7.8, "
                + "ARRAY['CVE-2024-20353'], current_date - 8, current_date - 4, "
                + "'https://sec.cloudapps.cisco.com/security/center/content/CiscoSecurityAdvisory/cisco-sa-asa-synthetic', "
                + "'PARSED', ?)",
                panAdvId, "d".repeat(64),
                cpAdvId, "e".repeat(64),
                ciscoAdvId, "f".repeat(64));

        exec(c, "insert into advisory_product_rule(advisory_id, product_key, train, rule, fixed_text) values "
                + "(?, 'paloalto:pan-os', '11.1', ?, '11.1.10'), "
                + "(?, 'checkpoint:gaia-gateway', 'R81.20', ?, 'R81.20 Jumbo Take 120'), "
                + "(?, 'cisco:asa', '9.18', ?, 'Upgrade per Cisco advisory')",
                panAdvId, panRule, cpAdvId, cpRule, ciscoAdvId, ciscoRule);

        exec(c, "insert into known_exploited_cve(cve_id, date_added, ransomware_use) values "
                + "('CVE-2024-3400', current_date - 9, true), ('CVE-2024-24919', current_date - 11, false)");

        ids.put("panAdvId", panAdvId);
        ids.put("cpAdvId", cpAdvId);
        ids.put("ciscoAdvId", ciscoAdvId);
    }

    private static ArtefactStore.ArtefactMetadata storeEncrypted(String deviceId, String jobKey, String vendor,
            boolean gzip, byte[] bytes) throws IOException {
        var handle = store.open(deviceId, jobKey + "-" + UUID.randomUUID(), vendor, gzip);
        handle.sink().write(bytes);
        return handle.finish();
    }

    private static void insertConfigArtefactAndBackupRow(Connection c, ArtefactStore.ArtefactMetadata meta,
            SeedDevice device, String jobId, String compression) throws Exception {
        String ref = meta.ref().value();
        exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, "
                + "key_id, wrapped_data_key, validation, retention_tier, recovery_volume_path) values "
                + "(?, ?, 'configuration', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                + "'standard', ?)",
                ref, device.id(), device.vendor(), device.softwareVersion(), sha256(device.hostname()),
                meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(),
                meta.keyId(), meta.wrappedDataKey(), ref);
        exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, "
                + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref, device.id(), jobId, device.vendor(), meta.plaintextSha256(), meta.plaintextBytes(),
                meta.ciphertextSha256(), meta.ciphertextBytes(), compression, meta.keyId());
    }

    private static void insertBackupRow(Connection c, String artefactId, SeedDevice device, String artefactClass,
            ArtefactStore.ArtefactMetadata meta, String timeExpr) throws Exception {
        boolean supportBundle = "support_bundle".equals(artefactClass);
        String createdCol = !supportBundle && timeExpr != null ? ", created_at" : "";
        String createdVal = !supportBundle && timeExpr != null ? ", " + timeExpr : "";
        String expiresCol = supportBundle ? ", expires_at" : "";
        String expiresVal = supportBundle ? ", " + (timeExpr == null ? "now() + interval '14 days'" : timeExpr) : "";
        String devCols = "backup".equals(artefactClass) ? ", deviation_state, backup_type" : "";
        String devVals = "backup".equals(artefactClass) ? ", 'first', 'standard'" : "";
        exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, "
                + "key_id, wrapped_data_key, validation, retention_tier, recovery_volume_path"
                + createdCol + expiresCol + devCols + ") values "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'{\"level_reached\":\"V2\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                + "'standard', ?" + createdVal + expiresVal + devVals + ")",
                artefactId, device.id(), artefactClass, device.vendor(), device.softwareVersion(),
                sha256(device.hostname()), meta.plaintextSha256(), meta.plaintextBytes(),
                meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(), meta.wrappedDataKey(),
                meta.ref().value());
    }

    private static String insertJob(Connection c, String deviceId, String jobType, String capabilityId, String state,
            String reason, String targetKind, String targetRef) throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        exec(c, "insert into jobs(job_id, job_type, capability_id, target_kind, target_device_id, target_ref, "
                + "submitted_by_actor_fingerprint, state, action_class, terminal_reason, finished_at) values "
                + "(?, ?, ?, ?, ?, ?, ?, ?, 'read', ?, now())",
                id, jobType, capabilityId, targetKind, deviceId, targetRef, Ui2Rows.ACTOR, state, reason);
        return id;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    private static void audited(Connection c, SqlWork work) throws Exception {
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

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(plain);
        }
        return out.toByteArray();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ body-scan & HTTP helpers

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
                out.add(Pattern.compile("(?<![0-9a-f:])" + Pattern.quote(s) + "(?![0-9a-f:])",
                        Pattern.CASE_INSENSITIVE));
            }
        } else if (cls == ValueClass.ADDRESS) {
            out.add(Pattern.compile("(?<![0-9.])" + Pattern.quote(value) + "(?![0-9])"));
            out.add(Pattern.compile("(?<![0-9-])" + Pattern.quote(value.replace('.', '-')) + "(?![0-9])"));
        } else {
            out.add(Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    static Map<ValueClass, Integer> countSurvivors(String body, Map<String, ValueClass> raw) {
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

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Per-request timeout from {@code UI2_TEST_HTTP_TIMEOUT_S} (default 15s, up to 600s on shared runners). */
    static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(15);
        int value = Integer.parseInt(seconds.strip());
        if (value < 1 || value > 600) {
            throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 1..600");
        }
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String contentType, String body) {
    }

    private static Resp get(String pathAndQuery, String cookie) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*");
        if (cookie != null) req.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> resp = HTTP.send(req.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(resp.statusCode(), resp.headers().firstValue("content-type").orElse(""),
                new String(resp.body(), StandardCharsets.UTF_8));
    }

    private static Resp postJson(String pathAndQuery, String cookie, String csrf, Object payload) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json")
                .header("Origin", "http://127.0.0.1:" + port);
        if (cookie != null) req.header("Cookie", "ui2_session=" + cookie);
        if (csrf != null) req.header("X-CSRF-Token", csrf);
        HttpResponse<byte[]> resp = HTTP.send(
                req.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(resp.statusCode(), resp.headers().firstValue("content-type").orElse(""),
                new String(resp.body(), StandardCharsets.UTF_8));
    }

    record AiviewSession(String cookie, String csrfToken) {
    }

    private static AiviewSession cachedSession;

    private static synchronized AiviewSession loginAsAiview() throws Exception {
        if (cachedSession != null) return cachedSession;
        var loginReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
                .build();
        HttpResponse<String> loginResp = HTTP.send(loginReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResp.statusCode(), "aiview login");
        String cookie = null;
        for (String header : loginResp.headers().allValues("set-cookie")) {
            if (header.startsWith("ui2_session=")) {
                String v = header.substring("ui2_session=".length());
                int semi = v.indexOf(';');
                cookie = semi < 0 ? v : v.substring(0, semi);
                break;
            }
        }
        assertNotNull(cookie, "aiview session cookie");
        Resp status = get("/session/status", cookie);
        assertEquals(200, status.status(), "aiview session status");
        String csrf = JSON.readTree(status.body()).path("csrf_token").asText("");
        assertFalse(csrf.isBlank(), "aiview csrf_token");
        cachedSession = new AiviewSession(cookie, csrf);
        return cachedSession;
    }

    private static String urlPart(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void allFourSeededPlanesAnswerNonEmptyMaskedPayloadsAndRefuseRawDownloads() throws Exception {
        AiviewSession session = loginAsAiview();
        String cookie = session.cookie();
        String csrf = session.csrfToken();

        Map<String, Map<ValueClass, Integer>> leaksByRoute = new LinkedHashMap<>();
        List<String> summaryLines = new ArrayList<>();

        // ---- Plane 1: Configuration, Cluster member-diff, Backup compare & Management tree

        Resp cfgList = checkGet("GET /configuration", "/configuration", cookie, leaksByRoute, summaryLines);
        JsonNode cfgDevices = JSON.readTree(cfgList.body()).path("devices");
        assertTrue(cfgDevices.isArray() && cfgDevices.size() >= 7, "non-empty /configuration devices list");
        String maskedClusterRef = null;
        for (JsonNode d : cfgDevices) {
            if (estate.cpMemberA().id().equals(d.path("device_id").asText())) {
                assertTrue(d.path("hostname").asText().startsWith("FW-"), "masked hostname on /configuration");
                maskedClusterRef = d.path("cluster_member_ref").asText();
                assertTrue(maskedClusterRef.startsWith("CLS-"), "masked cluster ref on /configuration");
                assertEquals(10, d.path("projected_settings").asInt(), "projected settings count");
            }
        }
        assertNotNull(maskedClusterRef, "resolved masked cluster pseudonym from /configuration");

        // Check Point device configuration + raw text refusal
        Resp cfgCp = checkGet("GET /devices/{cpDeviceId}/configuration",
                "/devices/" + urlPart(estate.cpMemberA().id()) + "/configuration", cookie, leaksByRoute, summaryLines);
        JsonNode cfgCpJson = JSON.readTree(cfgCp.body());
        assertFalse(cfgCpJson.path("text_readable").asBoolean(true), "text_readable must be false for aiview");
        assertTrue(cfgCpJson.path("index").size() >= 2, "non-empty CP configuration index");
        assertEquals("computed", cfgCpJson.path("change_deviation_summary").path("status").asText());

        // Palo Alto device configuration (overrides + Panorama provenance + supplementary run) + raw text refusal
        Resp cfgPan = checkGet("GET /devices/{panDeviceId}/configuration",
                "/devices/" + urlPart(estate.panFirewall().id()) + "/configuration", cookie, leaksByRoute, summaryLines);
        JsonNode cfgPanJson = JSON.readTree(cfgPan.body());
        assertFalse(cfgPanJson.path("text_readable").asBoolean(true), "PAN text_readable must be false for aiview");
        assertTrue(cfgPanJson.path("index").size() >= 2, "non-empty PAN configuration index");
        assertTrue(cfgPanJson.path("overrides").size() >= 2, "non-empty PAN configuration overrides");
        assertTrue(cfgPanJson.path("supplementary_runs").size() >= 1, "non-empty PAN supplementary runs");

        // Cisco ASA device configuration
        Resp cfgAsa = checkGet("GET /devices/{asaDeviceId}/configuration",
                "/devices/" + urlPart(estate.asaUnitA().id()) + "/configuration", cookie, leaksByRoute, summaryLines);
        JsonNode cfgAsaJson = JSON.readTree(cfgAsa.body());
        assertFalse(cfgAsaJson.path("text_readable").asBoolean(true), "ASA text_readable must be false for aiview");
        assertTrue(cfgAsaJson.path("index").size() >= 1, "non-empty ASA configuration index");

        for (SeedDevice dev : List.of(estate.cpMemberA(), estate.panFirewall(), estate.asaUnitA())) {
            Resp textRefusal = get("/devices/" + urlPart(dev.id()) + "/configuration/text", cookie);
            assertEquals(403, textRefusal.status(), "GET /devices/{id}/configuration/text must refuse aiview with 403");
            recordLeaks("GET /devices/{deviceId}/configuration/text (403)", textRefusal, leaksByRoute, summaryLines);
        }

        // Also check BackupDeviations where asa_config_relation is projected
        Resp deviations = checkGet("GET /api/v2/backups/deviations", "/api/v2/backups/deviations",
                cookie, leaksByRoute, summaryLines);
        JsonNode asaRels = JSON.readTree(deviations.body()).path("asa_config_relations");
        assertTrue(asaRels.isArray() && asaRels.size() >= 1, "asa_config_relation row present in deviations");
        assertEquals("MISMATCH", asaRels.get(0).path("relation").asText());

        // Cluster member-diff via masked cluster pseudonym + raw hostname / UUID oracle probes
        Resp diffMasked = checkGet("GET /clusters/{maskedClusterRef}/member-diff",
                "/clusters/" + urlPart(maskedClusterRef) + "/member-diff", cookie, leaksByRoute, summaryLines);
        JsonNode diffJson = JSON.readTree(diffMasked.body());
        assertTrue(diffJson.path("comparable").asBoolean(), "cluster member-diff comparable");
        assertEquals(2, diffJson.path("members").size(), "cluster member-diff has 2 members");
        assertTrue(diffJson.path("summary").path("differs").asInt() >= 4, "member-diff reports differing fields");

        // Probe raw device hostnames and device UUID on member-diff: must 404 with 0 raw values (no hostname oracle)
        for (String probe : List.of(estate.cpMemberA().hostname(), estate.cpMemberB().hostname(),
                estate.cpMemberA().id())) {
            Resp probeResp = get("/clusters/" + urlPart(probe) + "/member-diff", cookie);
            assertEquals(404, probeResp.status(), "member-diff raw-hostname/uuid probe must 404");
            recordLeaks("GET /clusters/{probe}/member-diff (404)", probeResp, leaksByRoute, summaryLines);
        }

        // Backup compare + entries
        String compareTarget = "/backups/" + urlPart(ids.get("backupLeftId"))
                + "/compare?with=" + urlPart(ids.get("backupRightId"));
        Resp compareResp = checkGet("GET /backups/{leftId}/compare?with={rightId}", compareTarget,
                cookie, leaksByRoute, summaryLines);
        JsonNode compareJson = JSON.readTree(compareResp.body());
        assertFalse(compareJson.path("identical").asBoolean(true), "backup compare must show differences");
        assertEquals(1, compareJson.path("unchanged").asInt());
        assertEquals(1, compareJson.path("added").size());
        assertEquals(1, compareJson.path("removed").size());
        assertEquals(1, compareJson.path("changed").size());

        // Management tree
        Resp mgmtTree = checkGet("GET /devices/{mgmtDeviceId}/management-tree",
                "/devices/" + urlPart(estate.cpManagement().id()) + "/management-tree",
                cookie, leaksByRoute, summaryLines);
        JsonNode treeJson = JSON.readTree(mgmtTree.body());
        assertEquals(ids.get("discRunId"), treeJson.path("run_id").asText());
        assertTrue(treeJson.path("domains").size() >= 1, "management tree has seeded domain");
        assertTrue(treeJson.path("domain_ha").path("domains").size() >= 1, "management tree has domain_ha");
        assertTrue(treeJson.path("counts").path("imported").asInt() >= 2, "management tree links 2 cluster members");

        // ---- Plane 2: Support bundles & uploads (V230, V240)

        Resp sbCat = checkGet("GET /api/v2/support-bundles/catalogue", "/api/v2/support-bundles/catalogue",
                cookie, leaksByRoute, summaryLines);
        assertTrue(JSON.readTree(sbCat.body()).path("kinds").size() >= 5, "support-bundle catalogue non-empty");

        Resp sbDest = checkGet("GET /api/v2/support-bundles/upload-destinations",
                "/api/v2/support-bundles/upload-destinations", cookie, leaksByRoute, summaryLines);
        assertTrue(JSON.readTree(sbDest.body()).path("destinations").size() >= 5, "upload destinations non-empty");

        Resp sbAll = checkGet("GET /api/v2/support-bundles", "/api/v2/support-bundles",
                cookie, leaksByRoute, summaryLines);
        JsonNode sbList = JSON.readTree(sbAll.body()).path("support_bundles");
        assertEquals(2, sbList.size(), "both seeded support-bundle requests returned");
        for (JsonNode sb : sbList) {
            assertWithheldFieldsAbsent(sb, List.of("requested_by", "reason", "case_ref", "deleted_by"),
                    "GET /api/v2/support-bundles");
            assertTrue(sb.path("device_pseudonym").asText().startsWith("FW-"), "support-bundle device_pseudonym");
        }

        Resp sbByDev = checkGet("GET /api/v2/devices/{deviceId}/support-bundles",
                "/api/v2/devices/" + urlPart(estate.cpMemberA().id()) + "/support-bundles",
                cookie, leaksByRoute, summaryLines);
        assertEquals(1, JSON.readTree(sbByDev.body()).path("support_bundles").size());

        Resp sbOne = checkGet("GET /api/v2/support-bundles/{id}",
                "/api/v2/support-bundles/" + urlPart(ids.get("cpBundleId")), cookie, leaksByRoute, summaryLines);
        JsonNode sbOneJson = JSON.readTree(sbOne.body());
        assertEquals(ids.get("cpBundleId"), sbOneJson.path("id").asText());
        assertEquals(2, sbOneJson.path("items").size());
        assertWithheldFieldsAbsent(sbOneJson, List.of("requested_by", "reason", "case_ref", "deleted_by"),
                "GET /api/v2/support-bundles/{id}");

        Resp sbUploads = checkGet("GET /api/v2/support-bundles/{id}/uploads",
                "/api/v2/support-bundles/" + urlPart(ids.get("asaBundleId")) + "/uploads",
                cookie, leaksByRoute, summaryLines);
        JsonNode uploadsArr = JSON.readTree(sbUploads.body()).path("uploads");
        assertEquals(2, uploadsArr.size(), "both cisco_cxd_https and generic_sftp uploads returned");
        for (JsonNode up : uploadsArr) {
            assertWithheldFieldsAbsent(up,
                    List.of("requested_by", "remote_user", "case_ref", "reason", "host_key_fingerprint"),
                    "GET /api/v2/support-bundles/{id}/uploads");
            if ("generic_sftp".equals(up.path("destination").asText())) {
                assertTrue(up.path("destination_host").isNull(),
                        "generic_sftp destination_host must be null for aiview");
            } else if ("cisco_cxd_https".equals(up.path("destination").asText())) {
                assertEquals("cxd.cisco.com", up.path("destination_host").asText(),
                        "fixed vendor host constant stays visible");
            }
        }

        // Download & download-ticket refusal checks (with valid CSRF token)
        for (String bundleId : List.of(ids.get("cpBundleId"), ids.get("asaBundleId"))) {
            Resp dlTicket = postJson("/api/v2/support-bundles/" + urlPart(bundleId) + "/download-ticket",
                    cookie, csrf, Map.of("reason", "Attempted download by replay viewer"));
            assertEquals(403, dlTicket.status(), "POST download-ticket must refuse aiview with 403");
            recordLeaks("POST /api/v2/support-bundles/{id}/download-ticket (403)", dlTicket,
                    leaksByRoute, summaryLines);

            Resp dlDirect = postJson("/api/v2/support-bundles/" + urlPart(bundleId) + "/download",
                    cookie, csrf, Map.of("reason", "Attempted download by replay viewer"));
            assertEquals(403, dlDirect.status(), "POST download must refuse aiview with 403");
            recordLeaks("POST /api/v2/support-bundles/{id}/download (403)", dlDirect,
                    leaksByRoute, summaryLines);
        }

        // ---- Plane 3: Diagnostics & system-log measurements (V91, V181-V183)

        Resp diagTargets = checkGet("GET /api/v2/diagnostics/targets", "/api/v2/diagnostics/targets",
                cookie, leaksByRoute, summaryLines);
        JsonNode targetsArr = JSON.readTree(diagTargets.body()).path("targets");
        assertTrue(targetsArr.isArray() && targetsArr.size() >= 6, "diagnostic targets non-empty");
        assertTrue(targetsArr.get(0).path("target").asText().startsWith("FW-"), "diagnostic target masked to FW-");

        Resp diagPorts = checkGet("GET /api/v2/diagnostics/ports?device_id={fmgDeviceId}",
                "/api/v2/diagnostics/ports?device_id=" + urlPart(estate.fortiManager().id()),
                cookie, leaksByRoute, summaryLines);
        JsonNode portsArr = JSON.readTree(diagPorts.body()).path("ports");
        assertEquals(2, portsArr.size(), "FortiManager physical ports returned");

        Resp diagHistory = checkGet("GET /api/v2/diagnostics/history", "/api/v2/diagnostics/history",
                cookie, leaksByRoute, summaryLines);
        JsonNode runsArr = JSON.readTree(diagHistory.body()).path("runs");
        assertTrue(runsArr.isArray() && runsArr.size() >= 1, "diagnostic history non-empty");
        assertEquals(ids.get("diagJobId"), runsArr.get(0).path("jobId").asText());
        assertTrue(runsArr.get(0).path("target").asText().startsWith("FW-"), "history target masked to FW-");

        Resp diagOutput = checkGet("GET /api/v2/diagnostics/{jobId}",
                "/api/v2/diagnostics/" + urlPart(ids.get("diagJobId")), cookie, leaksByRoute, summaryLines);
        JsonNode diagJson = JSON.readTree(diagOutput.body());
        assertTrue(diagJson.path("masked").asBoolean(), "diagnostic output marked masked");
        String maskedText = diagJson.path("output").asText("");
        assertTrue(maskedText.contains("FW-"), "diagnostic output contains masked FW- pseudonym");
        assertTrue(maskedText.contains("SN-"), "diagnostic output contains masked SN- token");

        Resp syslogMeas = checkGet("GET /api/v2/diagnostics/system-log-measurements",
                "/api/v2/diagnostics/system-log-measurements", cookie, leaksByRoute, summaryLines);
        JsonNode measArr = JSON.readTree(syslogMeas.body()).path("measurements");
        assertEquals(2, measArr.size(), "both seeded system_log_measurement rows returned");

        // ---- Plane 4: Lifecycle advisories (V205, V214)

        Resp advSources = checkGet("GET /api/v2/lifecycle/advisory-sources",
                "/api/v2/lifecycle/advisory-sources", cookie, leaksByRoute, summaryLines);
        JsonNode srcArr = JSON.readTree(advSources.body()).path("sources");
        assertTrue(srcArr.isArray() && srcArr.size() >= 4, "advisory sources non-empty");

        Resp advList = checkGet("GET /api/v2/lifecycle/advisories",
                "/api/v2/lifecycle/advisories", cookie, leaksByRoute, summaryLines);
        JsonNode advArr = JSON.readTree(advList.body()).path("advisories");
        assertEquals(3, advArr.size(), "all 3 seeded advisories listed with verdicts");

        for (String advIdKey : List.of("panAdvId", "cpAdvId", "ciscoAdvId")) {
            String advId = ids.get(advIdKey);
            Resp advDetail = checkGet("GET /api/v2/lifecycle/advisories/{" + advIdKey + "}",
                    "/api/v2/lifecycle/advisories/" + urlPart(advId), cookie, leaksByRoute, summaryLines);
            JsonNode detailDevs = JSON.readTree(advDetail.body()).path("devices");
            assertTrue(detailDevs.isArray() && detailDevs.size() >= 1, "advisory detail has affected devices");
            boolean anyAffected = false;
            for (JsonNode d : detailDevs) {
                assertTrue(d.path("hostname").asText().startsWith("FW-"),
                        "advisory detail device hostname masked to FW-");
                if ("AFFECTED".equals(d.path("verdict").asText())) anyAffected = true;
            }
            assertTrue(anyAffected, "at least one AFFECTED device on " + advIdKey);
        }

        summaryLines.forEach(System.out::println);
        int totalLeaks = leaksByRoute.values().stream()
                .flatMapToInt(m -> m.values().stream().mapToInt(Integer::intValue))
                .sum();
        System.out.println("AIVIEW_SWEEP_R6_SUMMARY checked_calls=" + summaryLines.size()
                + " planted_raw_values=" + estate.raw().size()
                + " surviving_raw_occurrences=" + totalLeaks);

        assertEquals(KNOWN_LEAKS, leaksByRoute,
                "surviving planted raw value counts per (route, value_class); KNOWN_LEAKS must match exactly");
    }

    @Test
    void counterAndTimeoutHelpersCoverAddressSpellingsAndBounds() {
        Map<String, ValueClass> raw = Map.of(
                "192.0.2.44", ValueClass.ADDRESS,
                "2001:db8:cafe::9", ValueClass.ADDRESS,
                "pa" + "r010203-edge-01", ValueClass.HOSTNAME);
        assertEquals(Map.of(ValueClass.ADDRESS, 2),
                countSurvivors("[\"192.0.2.44\",\"192-0-2-44\",\"192.0.2.440\"]", raw));
        assertEquals(Map.of(ValueClass.ADDRESS, 3),
                countSurvivors("2001:DB8:CAFE::9 2001:0db8:cafe:0000:0000:0000:0000:0009 2001:db8:cafe:0:0:0:0:9", raw));
        assertEquals(Map.of(ValueClass.HOSTNAME, 1),
                countSurvivors("host PA" + "R010203-EDGE-01 active", raw));
        assertEquals(Duration.ofSeconds(15), requestTimeout(null));
        assertEquals(Duration.ofSeconds(180), requestTimeout("180"));
    }

    private static void assertWithheldFieldsAbsent(JsonNode node, List<String> fields, String route) {
        for (String f : fields) {
            assertFalse(node.hasNonNull(f), route + " must withhold " + f + " from aiview");
        }
    }

    private static Resp checkGet(String label, String pathAndQuery, String cookie,
            Map<String, Map<ValueClass, Integer>> leaksByRoute, List<String> summaryLines) throws Exception {
        Resp resp = get(pathAndQuery, cookie);
        assertEquals(200, resp.status(), label + " expected HTTP 200");
        assertFalse(resp.body().isBlank(), label + " expected non-empty body");
        recordLeaks(label, resp, leaksByRoute, summaryLines);
        return resp;
    }

    private static void recordLeaks(String label, Resp resp,
            Map<String, Map<ValueClass, Integer>> leaksByRoute, List<String> summaryLines) {
        Map<ValueClass, Integer> found = countSurvivors(resp.body(), estate.raw());
        if (!found.isEmpty()) {
            leaksByRoute.put(label, found);
        }
        summaryLines.add(String.format(Locale.ROOT, "AIVIEW_SWEEP_R6 %3d %-56s raw=%s",
                resp.status(), label, found.isEmpty() ? "0" : found.toString()));
    }
}
