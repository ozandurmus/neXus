package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.api.ScriptExecutionController;
import com.securityexpert.nexus.ui2.service.api.ZoneMatrixController;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.policy.PolicyHygiene;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Round 10 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp; post-parsing
 * masking law" and "Sensitive identity reporting law"): recent GET routes, 4-vendor {@code hygiene.csv} exports,
 * and raw-endpoint 403 refusals.
 *
 * <p>A synthetic estate with runtime-generated canary values and RFC 5737 addresses is seeded into PostgreSQL 16 and
 * a temporary encrypted {@link FileArtefactStore}. Under an authenticated {@code aiview} ({@code role:replay_viewer})
 * session reused across all test methods, this sweep verifies:</p>
 * <ol>
 *   <li>{@code GET /api/v2/policy/policies/{id}/hygiene.csv} across Check Point, Palo Alto, Cisco ASA, and FortiGate:
 *       HTTP 200, {@code text/csv}, {@code Cache-Control: no-store}, non-empty finding rows, and zero planted raw
 *       canaries in headers or CSV body.</li>
 *   <li>{@code GET /api/v2/config/log-detection/catalogs} and {@code /api/v2/config/log-detection/catalogs/{version}}:
 *       seeded V273 catalog version returns either masked non-leaking JSON or HTTP 403, with zero raw canaries.</li>
 *   <li>{@code GET /api/v2/config/snmp-polling}: HTTP 403 by default ({@code UI2_AIVIEW_READONLY_ADMIN} off) with
 *       zero credential reference, hostname, or IP leaks in headers or body.</li>
 *   <li>{@code GET /api/v2/zone-matrix/findings/{findingId}/witness}: HTTP 403 (both at the HTTP route gate and via
 *       the controller's {@code READ_ONLY_PERSONA} defence-in-depth check), zero {@code "cells"}, {@code "witness"},
 *       or planted raw CIDR/IP tokens, and zero new {@code WITNESS_VIEW} rows in {@code zone_matrix_access_log}.</li>
 *   <li>{@code GET /api/v2/scripts/runs/{runId}/output}: HTTP 403 (both at the HTTP route gate and via the
 *       controller's {@code FORBIDDEN_FOR_VIEWER} defence-in-depth check), with zero occurrences of the planted
 *       encrypted stdout canary.</li>
 * </ol>
 *
 * <p>Diagnostic output and assertion failures report route keys, HTTP status codes, and {@link ValueClass} counts
 * only — never raw planted values.</p>
 */
class AiviewRecentRoutesAndExportsSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Planted value category; the only thing about a value this test ever prints or asserts on. */
    enum ValueClass {
        HOSTNAME,
        CLUSTER_NAME,
        ADDRESS,
        SERIAL,
        PRINCIPAL,
        POLICY_NAME,
        RULE_NAME,
        OBJECT_NAME,
        ZONE_NAME,
        VSYS_NAME,
        CREDENTIAL_REF,
        WITNESS_RANGE,
        SCRIPT_NAME,
        SCRIPT_OUTPUT
    }

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static Estate estate;
    private static String sessionCookie;
    private static final Map<String, String> ids = new TreeMap<>();

    // ------------------------------------------------------------------ boot

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_recent_routes");
        work = Files.createTempDirectory("ui2-aiview-recent-routes-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
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

    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory, ArtefactStore testStore) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        registry.removeBeanDefinition("artefactStoreAccess");
        beanFactory.registerSingleton("artefactStoreAccess", new DeviceCompositionConfiguration.ArtefactStoreAccess(testStore));
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

    /** A token with at least one letter so it can never equal a digits-only pseudonym suffix. */
    private static String tag() {
        return "x" + hex(3);
    }

    record Device(String id, String vendor, String role, String hostname, String serial, String address, String cluster) {
    }

    record Estate(
            Device cpMgmt, Device cpGw, Device panorama, Device panFw, Device asa, Device fgt,
            String cpCluster, String principal, String fgtModifier,
            // CP policy
            String cpDomainId, String cpDomainName, String cpPolicyId, String cpPolicyName, String cpSectionName,
            String cpRuleId, String cpRuleName, String cpShadowRuleName, String cpDisabledRuleName,
            String cpObjId, String cpObjName, String cpObjAddress, String cpGroupId, String cpGroupName,
            // PAN policy
            String panDomainId, String panPolicyId, String panPolicyName,
            String panRuleId, String panRuleName, String panShadowRuleName,
            String panObjId, String panObjName, String panObjAddress,
            String panZoneA, String panZoneB, String panVsys,
            // ASA policy
            String asaOutsideIf, String asaInsideIf, String asaIfAddress,
            String asaHostName, String asaHostAddress, String asaBoundAcl, String asaUnboundAcl,
            // FGT policy
            String fgtPortA, String fgtPortB, String fgtIfAddress,
            String fgtSrcObjName, String fgtSrcAddress, String fgtDstObjName, String fgtDstAddress,
            String fgtRuleName, String fgtShadowRuleName,
            // SNMP polling
            String snmpCredentialRef,
            // Zone Matrix witness
            String zmZoneA, String zmZoneB, String zmRuleName,
            String zmWitnessSrcRange, String zmWitnessDstRange, String zmWitnessSrcIp, String zmWitnessDstIp,
            // Script execution
            String scriptName, String scriptEgressHost, String scriptCredentialRef, String scriptStdoutCanary,
            Map<String, ValueClass> raw) {

        static Estate generate() {
            String cpCluster = "CLX-R10-" + tag().toUpperCase(Locale.ROOT);
            int base = 20 + RANDOM.nextInt(120);
            Device cpMgmt = new Device(Ui2Rows.opaqueId("dev"), "check_point", "management_server",
                    "cpmgmt-" + tag() + "-01", "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + base, null);
            Device cpGw = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "cpgw-" + tag() + "-01", "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + 1), cpCluster);
            Device panorama = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "management_server",
                    "pano-" + tag() + "-01", "0" + hex(6).replaceAll("[a-f]", "3"), "192.0.2." + (base + 2), null);
            Device panFw = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway",
                    "panfw-" + tag() + "-01", "0" + hex(6).replaceAll("[a-f]", "7"), "192.0.2." + (base + 3), null);
            Device asa = new Device(Ui2Rows.opaqueId("dev"), "cisco_asa", "gateway",
                    "asafw-" + tag() + "-01", "JAD" + hex(4).toUpperCase(Locale.ROOT), "192.0.2." + (base + 4), null);
            Device fgt = new Device(Ui2Rows.opaqueId("dev"), "fortinet", "gateway",
                    "fgtfw-" + tag() + "-01", "FGT" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + 5), null);

            String principal = "adm" + tag() + ".ops";
            String fgtModifier = "fgtadm" + tag() + ".sec";

            // CP policy
            String cpDomainId = "dom-cp-" + UUID.randomUUID();
            String cpDomainName = "cma-" + tag() + "-core";
            String cpPolicyId = "pol-cp-" + UUID.randomUUID();
            String cpPolicyName = "pkg-" + tag() + "-cpcore";
            String cpSectionName = "sec-" + tag() + "-payments";
            String cpRuleId = "rule-cp-" + UUID.randomUUID();
            String cpRuleName = "accept-" + tag() + "-swift";
            String cpShadowRuleName = "accept-" + tag() + "-swift-dup";
            String cpDisabledRuleName = "drop-" + tag() + "-retired";
            String cpObjId = "obj-cp-" + UUID.randomUUID();
            String cpObjName = "cph-" + tag() + "-ledger01";
            String cpObjAddress = "198.51.100." + (10 + RANDOM.nextInt(40));
            String cpGroupId = "grp-cp-" + UUID.randomUUID();
            String cpGroupName = "cpgrp-" + tag() + "-ledgers";

            // PAN policy
            String panDomainId = "dom-pan-" + UUID.randomUUID();
            String panPolicyId = "pol-pan-" + UUID.randomUUID();
            String panPolicyName = "pkg-" + tag() + "-panedge";
            String panRuleId = "rule-pan-" + UUID.randomUUID();
            String panRuleName = "allow-" + tag() + "-payroll";
            String panShadowRuleName = "allow-" + tag() + "-payroll-dup";
            String panObjId = "obj-pan-" + UUID.randomUUID();
            String panObjName = "panh-" + tag() + "-dbsrv01";
            String panObjAddress = "198.51.100." + (60 + RANDOM.nextInt(40));
            String panZoneA = "zn-" + tag() + "-cardholder";
            String panZoneB = "zn-" + tag() + "-corebank";
            String panVsys = "vsys-" + tag() + "-prod";

            // ASA policy
            String asaOutsideIf = "nif-" + tag() + "-out";
            String asaInsideIf = "nif-" + tag() + "-in";
            String asaIfAddress = "203.0.113." + (10 + RANDOM.nextInt(30));
            String asaHostName = "asah-" + tag() + "-app01";
            String asaHostAddress = "203.0.113." + (45 + RANDOM.nextInt(30));
            String asaBoundAcl = "ACL-" + tag().toUpperCase(Locale.ROOT) + "-OUT";
            String asaUnboundAcl = "ACL-" + tag().toUpperCase(Locale.ROOT) + "-ORPHAN";

            // FGT policy
            String fgtPortA = "if-" + tag() + "-wan";
            String fgtPortB = "if-" + tag() + "-lan";
            String fgtIfAddress = "203.0.113." + (80 + RANDOM.nextInt(30));
            String fgtSrcObjName = "fgth-" + tag() + "-src01";
            String fgtSrcAddress = "203.0.113." + (115 + RANDOM.nextInt(30));
            String fgtDstObjName = "fgth-" + tag() + "-dst01";
            String fgtDstAddress = "203.0.113." + (150 + RANDOM.nextInt(30));
            String fgtRuleName = "fgtpol-" + tag() + "-allow";
            String fgtShadowRuleName = "fgtpol-" + tag() + "-shadow";

            // SNMP polling
            String snmpCredentialRef = "cred-snmp-" + tag() + "-v3";

            // Zone Matrix witness (distinct RFC 5737 /28 blocks)
            String zmZoneA = "zmz-" + tag() + "-dmz";
            String zmZoneB = "zmz-" + tag() + "-vault";
            String zmRuleName = "zmrule-" + tag() + "-breach";
            String zmWitnessSrcIp = "192.0.2.160";
            String zmWitnessDstIp = "198.51.100.160";
            String zmWitnessSrcRange = zmWitnessSrcIp + "/28";
            String zmWitnessDstRange = zmWitnessDstIp + "/28";

            // Script execution
            String scriptName = "scr-" + tag() + "-audit";
            String scriptEgressHost = "egress-" + tag() + ".internal.test";
            String scriptCredentialRef = "cred-scr-" + tag() + "-ssh";
            String scriptStdoutCanary = "stdout-canary-" + tag() + "-rawline";

            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(cpMgmt, cpGw, panorama, panFw, asa, fgt)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.address(), ValueClass.ADDRESS);
            }
            raw.put(cpCluster, ValueClass.CLUSTER_NAME);
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(fgtModifier, ValueClass.PRINCIPAL);

            raw.put(cpDomainName, ValueClass.POLICY_NAME);
            raw.put(cpPolicyName, ValueClass.POLICY_NAME);
            raw.put(cpSectionName, ValueClass.RULE_NAME);
            raw.put(cpRuleName, ValueClass.RULE_NAME);
            raw.put(cpShadowRuleName, ValueClass.RULE_NAME);
            raw.put(cpDisabledRuleName, ValueClass.RULE_NAME);
            raw.put(cpObjName, ValueClass.OBJECT_NAME);
            raw.put(cpObjAddress, ValueClass.ADDRESS);
            raw.put(cpGroupName, ValueClass.OBJECT_NAME);

            raw.put(panPolicyName, ValueClass.POLICY_NAME);
            raw.put(panRuleName, ValueClass.RULE_NAME);
            raw.put(panShadowRuleName, ValueClass.RULE_NAME);
            raw.put(panObjName, ValueClass.OBJECT_NAME);
            raw.put(panObjAddress, ValueClass.ADDRESS);
            raw.put(panZoneA, ValueClass.ZONE_NAME);
            raw.put(panZoneB, ValueClass.ZONE_NAME);
            raw.put(panVsys, ValueClass.VSYS_NAME);

            raw.put(asaOutsideIf, ValueClass.ZONE_NAME);
            raw.put(asaInsideIf, ValueClass.ZONE_NAME);
            raw.put(asaIfAddress, ValueClass.ADDRESS);
            raw.put(asaHostName, ValueClass.OBJECT_NAME);
            raw.put(asaHostAddress, ValueClass.ADDRESS);
            raw.put(asaBoundAcl, ValueClass.RULE_NAME);
            raw.put(asaUnboundAcl, ValueClass.RULE_NAME);

            raw.put(fgtPortA, ValueClass.ZONE_NAME);
            raw.put(fgtPortB, ValueClass.ZONE_NAME);
            raw.put(fgtIfAddress, ValueClass.ADDRESS);
            raw.put(fgtSrcObjName, ValueClass.OBJECT_NAME);
            raw.put(fgtSrcAddress, ValueClass.ADDRESS);
            raw.put(fgtDstObjName, ValueClass.OBJECT_NAME);
            raw.put(fgtDstAddress, ValueClass.ADDRESS);
            raw.put(fgtRuleName, ValueClass.RULE_NAME);
            raw.put(fgtShadowRuleName, ValueClass.RULE_NAME);

            raw.put(snmpCredentialRef, ValueClass.CREDENTIAL_REF);

            raw.put(zmZoneA, ValueClass.ZONE_NAME);
            raw.put(zmZoneB, ValueClass.ZONE_NAME);
            raw.put(zmRuleName, ValueClass.RULE_NAME);
            raw.put(zmWitnessSrcRange, ValueClass.WITNESS_RANGE);
            raw.put(zmWitnessDstRange, ValueClass.WITNESS_RANGE);
            raw.put(zmWitnessSrcIp, ValueClass.ADDRESS);
            raw.put(zmWitnessDstIp, ValueClass.ADDRESS);

            raw.put(scriptName, ValueClass.SCRIPT_NAME);
            raw.put(scriptEgressHost, ValueClass.HOSTNAME);
            raw.put(scriptCredentialRef, ValueClass.CREDENTIAL_REF);
            raw.put(scriptStdoutCanary, ValueClass.SCRIPT_OUTPUT);

            return new Estate(
                    cpMgmt, cpGw, panorama, panFw, asa, fgt,
                    cpCluster, principal, fgtModifier,
                    cpDomainId, cpDomainName, cpPolicyId, cpPolicyName, cpSectionName,
                    cpRuleId, cpRuleName, cpShadowRuleName, cpDisabledRuleName,
                    cpObjId, cpObjName, cpObjAddress, cpGroupId, cpGroupName,
                    panDomainId, panPolicyId, panPolicyName,
                    panRuleId, panRuleName, panShadowRuleName,
                    panObjId, panObjName, panObjAddress,
                    panZoneA, panZoneB, panVsys,
                    asaOutsideIf, asaInsideIf, asaIfAddress,
                    asaHostName, asaHostAddress, asaBoundAcl, asaUnboundAcl,
                    fgtPortA, fgtPortB, fgtIfAddress,
                    fgtSrcObjName, fgtSrcAddress, fgtDstObjName, fgtDstAddress,
                    fgtRuleName, fgtShadowRuleName,
                    snmpCredentialRef,
                    zmZoneA, zmZoneB, zmRuleName,
                    zmWitnessSrcRange, zmWitnessDstRange, zmWitnessSrcIp, zmWitnessDstIp,
                    scriptName, scriptEgressHost, scriptCredentialRef, scriptStdoutCanary,
                    Map.copyOf(raw));
        }

        List<Device> devices() {
            return List.of(cpMgmt, cpGw, panorama, panFw, asa, fgt);
        }
    }

    // ------------------------------------------------------------------ seeding

    private static void seed(Estate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, Ui2Rows.ACTOR, () -> {
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
                    exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                            Ui2Rows.opaqueId("ep"), d.id(), d.address());
                    exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                            + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                }
            });

            seedCpPolicy(c, e);
            seedPanPolicy(c, e);
            seedAsaPolicy(c, e);
            seedFgtPolicy(c, e);
            seedLogDetectionCatalog(c, e);
            seedSnmpPolling(c, e);
            seedZoneMatrixWitness(c, e);
            seedScriptRunWithEncryptedOutput(c, e);
        }
    }

    /** Check Point policy with a covering rule, a redundant shadowed rule, and a disabled rule. */
    private static void seedCpPolicy(Connection c, Estate e) throws Exception {
        var host = new PolicySnapshot.PolicyObject(e.cpObjId(), e.cpObjName(), "host",
                List.of(), List.of("ipv4-address: " + e.cpObjAddress()), "RESOLVED");
        var group = new PolicySnapshot.PolicyObject(e.cpGroupId(), e.cpGroupName(), "group",
                List.of(e.cpObjId()), List.of(), "RESOLVED");
        var anyObj = new PolicySnapshot.PolicyObject("cp-any", "Any", "any", List.of(), List.of(), "RESOLVED");

        Map<String, List<String>> extras = Map.of("last-modifier", List.of(e.principal()),
                "layer-name", List.of(e.cpSectionName()));
        var rule1 = new PolicySnapshot.Rule(e.cpRuleId(), UUID.randomUUID().toString(), 1, e.cpRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.cpGroupId()), false),
                new PolicySnapshot.Cell(List.of(e.cpObjId()), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                "Accept", "Log",
                "approved by " + e.principal() + " on " + e.cpGw().hostname(), extras);
        String cpShadowRuleId = "rule-cp-sh-" + UUID.randomUUID();
        var rule2 = new PolicySnapshot.Rule(cpShadowRuleId, UUID.randomUUID().toString(), 2, e.cpShadowRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.cpObjId()), false),
                new PolicySnapshot.Cell(List.of(e.cpObjId()), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                "Accept", "Log",
                "duplicate rule for " + e.cpObjName() + " (" + e.cpObjAddress() + ")", extras);
        String cpDisabledRuleId = "rule-cp-dis-" + UUID.randomUUID();
        var rule3 = new PolicySnapshot.Rule(cpDisabledRuleId, UUID.randomUUID().toString(), 3, e.cpDisabledRuleName(), false,
                new PolicySnapshot.Cell(List.of(e.cpObjId()), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                new PolicySnapshot.Cell(List.of("cp-any"), false),
                "Drop", "None",
                "disabled by " + e.principal(), extras);

        var metadata = new PolicySnapshot.Metadata(e.cpPolicyId(), e.cpMgmt().id(), e.cpMgmt().hostname(), "CP",
                e.cpDomainId(), e.cpDomainName(), e.cpPolicyName(), Instant.now().toString(), null,
                List.of(new PolicySnapshot.Target(e.cpGw().id(), e.cpGw().hostname(), "", "IN_SYNC")));
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("cp-sec-1", e.cpSectionName(), "CP access layer", null,
                        List.of(rule1, rule2, rule3))),
                Map.of(e.cpObjId(), host, e.cpGroupId(), group, "cp-any", anyObj),
                List.of());
        ids.put("cpPolicyId", e.cpPolicyId());
        ids.put("cpRuleId", e.cpRuleId());
        audited(c, Ui2Rows.ACTOR, () -> exec(c,
                "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                e.cpPolicyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot)));
    }

    /** Palo Alto policy with stored vsys zone set and a shadowed any-zone rule. */
    private static void seedPanPolicy(Connection c, Estate e) throws Exception {
        var object = new PolicySnapshot.PolicyObject(e.panObjId(), e.panObjName(), "host",
                List.of(), List.of(e.panObjAddress()), "RESOLVED");
        var anyObj = new PolicySnapshot.PolicyObject("pan-any", "Any", "any", List.of(), List.of(), "RESOLVED");
        Map<String, List<String>> named = Map.of("from", List.of(e.panZoneA(), e.panZoneB()),
                "to", List.of(e.panZoneA(), e.panZoneB()), "last-modifier", List.of(e.principal()));
        Map<String, List<String>> any = Map.of("from", List.of("any"), "to", List.of("any"),
                "last-modifier", List.of(e.principal()));
        var first = new PolicySnapshot.Rule(e.panRuleId(), UUID.randomUUID().toString(), 1, e.panRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.panObjId()), false),
                new PolicySnapshot.Cell(List.of(e.panObjId()), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                "allow", "Log", "requested by " + e.principal() + " for " + e.panFw().hostname(), named);
        var later = new PolicySnapshot.Rule("rule-pan-sh-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2,
                e.panShadowRuleName(), true,
                new PolicySnapshot.Cell(List.of(e.panObjId()), false),
                new PolicySnapshot.Cell(List.of(e.panObjId()), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                new PolicySnapshot.Cell(List.of("pan-any"), false),
                "allow", "Log", "opened by " + e.principal(), any);
        var metadata = new PolicySnapshot.Metadata(e.panPolicyId(), e.panorama().id(), e.panorama().hostname(), "PAN",
                e.panDomainId(), "shared", e.panPolicyName(), Instant.now().toString(), null,
                List.of(new PolicySnapshot.Target(e.panFw().id(), e.panFw().hostname(), e.panVsys(), "IN_SYNC")));
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("pan-sec-1", "Pre rules", "Network", null, List.of(first, later))),
                Map.of(e.panObjId(), object, "pan-any", anyObj),
                List.of());
        ids.put("panPolicyId", e.panPolicyId());
        ids.put("panRuleId", e.panRuleId());
        audited(c, Ui2Rows.ACTOR, () -> {
            exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                    e.panPolicyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
            exec(c, "insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at) values (?, ?, ?, now())",
                    e.panFw().id(), e.panVsys(), c.createArrayOf("text", new String[] {e.panZoneA(), e.panZoneB()}));
        });
    }

    /** Cisco ASA local-snapshot policy with a redundant shadowed ACE and an unbound ACL. */
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
        PolicySnapshot snapshot = AsaPolicyMapper.map(e.asa().id(), e.asa().hostname(), Instant.now().toString(),
                null, AsaConfigPolicyParser.parse(asaConfig));
        var boundSection = snapshot.sections().stream().filter(s -> "ASA access-group".equals(s.source())).findFirst().orElseThrow();
        ids.put("asaPolicyId", snapshot.metadata().id());
        ids.put("asaRuleId", boundSection.rules().get(0).id());
        audited(c, Ui2Rows.ACTOR, () -> exec(c,
                "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                snapshot.metadata().id(), JSON.writeValueAsString(snapshot.metadata()), JSON.writeValueAsString(snapshot)));
    }

    /** FortiGate local-snapshot policy with a redundant shadowed firewall policy. */
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
                + "        set name \"" + e.fgtShadowRuleName() + "\"\n"
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
                Instant.now().toString(), null, FortiGateConfigPolicyParser.parse(fgtConfig)).get(0);
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
        audited(c, Ui2Rows.ACTOR, () -> exec(c,
                "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                snapshot.metadata().id(), JSON.writeValueAsString(snapshot.metadata()), JSON.writeValueAsString(snapshot)));
    }

    /** V273 log-detection template catalog version with a frozen catalog row and one template. */
    private static void seedLogDetectionCatalog(Connection c, Estate e) throws Exception {
        String version = "cat-1";
        String digest = "a".repeat(64);
        String templateId = "t" + "0".repeat(31);
        ids.put("catalogVersion", version);
        audited(c, e.principal(), () -> {
            exec(c, "insert into log_template_catalog(catalog_version, state, previous_version, manifest, digest, "
                    + "template_count, diff, created_by, frozen_at, frozen_by) values (?, 'FROZEN', null, "
                    + "'{\"normaliser_version\":\"n1\",\"masking_rule_set\":\"m1\"}'::jsonb, ?, 1, "
                    + "'{\"added\":1,\"removed\":0}'::jsonb, ?, now(), ?)",
                    version, digest, e.principal(), e.principal());
            exec(c, "insert into log_template(catalog_version, template_id, source_key, template_text, match_form, "
                    + "token_count, severity_weight, template_class, provenance, line_count, prevalence_devices, cohort_devices) "
                    + "values (?, ?, 'cp_messages', 'kernel <W> link down', 'kernel k-0123456789ab link down', 4, 0.50, "
                    + "'UNCLASSED', 'MEASURED', 12, 1, 1)",
                    version, templateId);
        });
    }

    /** V268 SNMP polling settings and per-device override referencing a planted canary credential reference. */
    private static void seedSnmpPolling(Connection c, Estate e) throws Exception {
        audited(c, e.principal(), () -> {
            exec(c, "insert into credential_references(credential_reference_id, purpose, backend_pointer) "
                    + "values (?, 'snmp_v3', 'vault://harness/snmp-opaque')", e.snmpCredentialRef());
            exec(c, "update snmp_polling_settings set default_credential_reference_id = ?, updated_at = now(), updated_by = ? "
                    + "where settings_id = 'default'", e.snmpCredentialRef(), e.principal());
            exec(c, "insert into snmp_polling_override(override_key, scope_kind, device_id, in_scope, credential_reference_id, "
                    + "updated_by) values (?, 'DEVICE', ?, true, ?, ?)",
                    "device:" + e.cpGw().id(), e.cpGw().id(), e.snmpCredentialRef(), e.principal());
        });
    }

    /** Active Zone Matrix version, COMPLETE run, and finding with a stored witness tuple containing planted RFC 5737 ranges. */
    private static void seedZoneMatrixWitness(Connection c, Estate e) throws Exception {
        String versionId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        String contentSha = "b".repeat(64);
        String contentJson = JSON.writeValueAsString(Map.of(
                "zones", List.of(
                        Map.of("id", UUID.randomUUID().toString(), "name", e.zmZoneA(), "blocks", List.of(e.zmWitnessSrcRange())),
                        Map.of("id", UUID.randomUUID().toString(), "name", e.zmZoneB(), "blocks", List.of(e.zmWitnessDstRange())))));
        audited(c, e.principal(), () -> exec(c,
                "insert into zone_matrix_version(version_id, number, state, revision, content, content_sha256, "
                        + "created_by, updated_by, published_by, published_at) "
                        + "values (?, 1, 'ACTIVE', 1, ?, ?, ?, ?, ?, now())",
                versionId, contentJson, contentSha, e.principal(), e.principal(), e.principal()));

        ZoneMatrixRunRepository repo = new JooqZoneMatrixRunRepository(
                new JooqTransactionBoundary(DSL.using(c, SQLDialect.POSTGRES)));
        Instant started = Instant.now().minusSeconds(10);
        Instant finished = Instant.now().minusSeconds(5);
        repo.start(new ZoneMatrixRunRepository.NewRun(runId, versionId, 1, contentSha, e.panPolicyId(),
                "step3-v1", started, started.minusSeconds(60)));
        var witness = new ZoneMatrixRunRepository.Witness(e.zmWitnessSrcRange(), e.zmWitnessDstRange(), "tcp/443", 16L, 16L);
        var cell = new ZoneMatrixRunRepository.Cell(e.zmZoneA(), e.zmZoneB(), "DENY_ALL", "CRITICAL",
                List.of("tcp/443"), "original", witness);
        var finding = new ZoneMatrixRunRepository.Finding(
                e.panPolicyId(), "uid-zm-r10-" + UUID.randomUUID(), List.of(),
                List.of(e.zmZoneA() + "->" + e.zmZoneB()),
                e.panRuleId(), 1, e.zmRuleName(), "pan-sec-1", "Pre rules",
                List.of(e.panFw().id()), "VIOLATION", null, "CRITICAL",
                List.of(), null, List.of(cell), List.of(), "original",
                finished, finished, finished, null, null);
        boolean completed = repo.complete(runId, finished, 50L,
                new ZoneMatrixRunRepository.Counts(1, 0, 0, 0, 0, 1),
                new ZoneMatrixRunRepository.Coverage("COMPLETE", List.of(), 0, true, "PAN", started.minusSeconds(60), 2),
                List.of(finding));
        assertTrue(completed, "Zone Matrix run must complete cleanly");
        List<ZoneMatrixRunRepository.IdentifiedFinding> identified = repo.identifiedFindingsOf(runId);
        assertEquals(1, identified.size(), "one Zone Matrix finding must be stored");
        ids.put("zoneMatrixFindingId", String.valueOf(identified.get(0).findingId()));
    }

    /** V251/V258 script and completed run with encrypted stdout containing the planted stdout canary. */
    private static void seedScriptRunWithEncryptedOutput(Connection c, Estate e) throws Exception {
        String scriptId = Ui2Rows.opaqueId("scr");
        String jobId = Ui2Rows.opaqueId("job");
        String[] egress = {e.panFw().address() + ":22", e.scriptEgressHost() + ":443"};
        String[] targets = {e.panFw().id()};
        String[] refs = {e.scriptCredentialRef()};

        byte[] scriptBody = ("#!/bin/sh\necho " + e.panFw().hostname() + "\n").getBytes(StandardCharsets.UTF_8);
        var bodyHandle = store.open(scriptId, scriptId + "@v1", ArtefactClass.SCRIPT_BODY, false);
        bodyHandle.sink().write(scriptBody);
        var bodyMeta = bodyHandle.finish();

        String stdoutText = e.scriptStdoutCanary() + " host=" + e.panFw().hostname() + " ip=" + e.panFw().address() + "\n";
        byte[] stdoutBytes = stdoutText.getBytes(StandardCharsets.UTF_8);
        var outHandle = store.open(scriptId, jobId, ArtefactClass.SCRIPT_OUTPUT, false);
        outHandle.sink().write(stdoutBytes);
        var outMeta = outHandle.finish();

        audited(c, e.principal(), () -> {
            exec(c, "insert into credential_references(credential_reference_id, purpose, backend_pointer) "
                    + "values (?, 'harness', 'vault://harness/script-opaque')", e.scriptCredentialRef());
            exec(c, "insert into script(script_id, name, kind, current_version, enabled, timeout_s, egress, "
                    + "egress_device_targets, credential_refs, notify, run_as, created_by_actor_fingerprint) "
                    + "values (?, ?, 'sh', 1, false, 300, ?, ?, ?, 'none', 'role:operator', ?)",
                    scriptId, e.scriptName(), c.createArrayOf("text", egress), c.createArrayOf("text", targets),
                    c.createArrayOf("text", refs), e.principal());
            exec(c, "insert into script_version(script_id, version, sha256, size_bytes, artefact_ref, key_id, "
                    + "wrapped_data_key, credential_refs, egress_device_targets, created_by) values (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
                    scriptId, bodyMeta.plaintextSha256(), bodyMeta.plaintextBytes(), bodyMeta.ref().value(),
                    bodyMeta.keyId(), bodyMeta.wrappedDataKey(), c.createArrayOf("text", refs),
                    c.createArrayOf("text", targets), e.principal());
            exec(c, "insert into jobs(job_id, job_type, capability_id, target_kind, target_ref, "
                    + "submitted_by_actor_fingerprint, idempotency_key, action_class, state, finished_at) "
                    + "values (?, 'script_run', 'script_run', 'script', ?, ?, ?, 'read', 'COMPLETED', now())",
                    jobId, scriptId + "@1", Ui2Rows.ACTOR, "script_run:" + jobId);
            exec(c, "insert into script_run(job_id, script_id, version, state, outcome, trigger_kind, run_as, requested_by, "
                    + "started_at, finished_at, duration_ms, exit_code, stdout_bytes, output_artefact_ref, output_key_id, "
                    + "output_wrapped_data_key, output_sha256, output_size_bytes, output_tail, egress_declared, "
                    + "egress_device_targets, credential_refs_used) values (?, ?, 1, 'COMPLETED', 'SUCCESS', "
                    + "'MANUAL', 'role:operator', ?, now() - interval '1 minute', now(), 1200, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    jobId, scriptId, e.principal(), stdoutBytes.length, outMeta.ref().value(), outMeta.keyId(),
                    outMeta.wrappedDataKey(), outMeta.plaintextSha256(), outMeta.plaintextBytes(), stdoutText,
                    c.createArrayOf("text", egress), c.createArrayOf("text", targets), c.createArrayOf("text", refs));
        });
        ids.put("scriptRunId", jobId);
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

    private static long queryCount(String sql) throws SQLException {
        try (Connection c = fixture.appConnection();
             PreparedStatement st = c.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ canary scanning

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

    static Map<ValueClass, Integer> count(String text, Map<String, ValueClass> raw) {
        if (text == null || text.isEmpty()) return Map.of();
        Map<ValueClass, Integer> found = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            int n = 0;
            for (Pattern p : forms(entry.getKey(), entry.getValue())) {
                Matcher m = p.matcher(text);
                while (m.find()) n++;
            }
            if (n > 0) found.merge(entry.getValue(), n, Integer::sum);
        }
        return found;
    }

    private static String flattenHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder();
        headers.map().forEach((name, values) -> {
            for (String v : values) {
                sb.append(name).append(": ").append(v).append('\n');
            }
        });
        return sb.toString();
    }

    private static void assertZeroCanaries(String routeLabel, Resp resp) {
        Map<ValueClass, Integer> headerLeaks = count(flattenHeaders(resp.headers()), estate.raw());
        assertEquals(Map.of(), headerLeaks, routeLabel + " leaked raw canary classes in response headers: " + headerLeaks);
        Map<ValueClass, Integer> bodyLeaks = count(resp.body(), estate.raw());
        assertEquals(Map.of(), bodyLeaks, routeLabel + " leaked raw canary classes in response body: " + bodyLeaks);
    }

    // ------------------------------------------------------------------ HTTP client (reusing one aiview cookie)

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, HttpHeaders headers, String body) {
    }

    private static Resp get(String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/csv, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), response.headers(), new String(response.body(), StandardCharsets.UTF_8));
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
        throw new IllegalStateException("no session cookie on aiview login");
    }

    /** Synthetic request proxy for controller defence-in-depth checks under {@code IS_REPLAY_VIEWER_ATTRIBUTE=true}. */
    private static HttpServletRequest replayViewerRequest(String actorFingerprint) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, actorFingerprint);
        attrs.put(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attrs.get(args[0]);
                    case "getParameterMap" -> Map.of();
                    default -> null;
                });
    }

    // ------------------------------------------------------------------ 1. 4-vendor hygiene.csv

    @Test
    void hygieneCsvMasksAllFourVendorsWithNoStoreAndZeroCanaryLeaks() throws Exception {
        String cookie = login();
        Map<String, String> vendorPolicies = new LinkedHashMap<>();
        vendorPolicies.put("CP", ids.get("cpPolicyId"));
        vendorPolicies.put("PAN", ids.get("panPolicyId"));
        vendorPolicies.put("ASA", ids.get("asaPolicyId"));
        vendorPolicies.put("FGT", ids.get("fgtPolicyId"));

        for (var entry : vendorPolicies.entrySet()) {
            String vendor = entry.getKey();
            String policyId = entry.getValue();
            assertNotNull(policyId, vendor + " policyId must be seeded");

            Resp resp = get("/api/v2/policy/policies/" + policyId + "/hygiene.csv", cookie);
            assertEquals(200, resp.status(), vendor + " hygiene.csv HTTP status");

            String contentType = resp.headers().firstValue("content-type").orElse("");
            assertTrue(contentType.toLowerCase(Locale.ROOT).startsWith("text/csv"),
                    vendor + " hygiene.csv Content-Type must be text/csv, got: " + contentType);

            String cacheControl = resp.headers().firstValue("cache-control").orElse("");
            assertTrue(cacheControl.toLowerCase(Locale.ROOT).contains("no-store"),
                    vendor + " hygiene.csv Cache-Control must contain no-store, got: " + cacheControl);

            String[] lines = resp.body().split("\\r?\\n");
            assertTrue(lines.length >= 2, vendor + " hygiene.csv must contain header + at least one finding row");
            assertTrue(lines[0].startsWith("Rule,Number,Class,Severity,Evidence,"),
                    vendor + " hygiene.csv header must match schema");
            long nonBlankDataRows = java.util.Arrays.stream(lines).skip(1).filter(l -> !l.isBlank()).count();
            assertTrue(nonBlankDataRows >= 1, vendor + " hygiene.csv must have non-empty finding rows");

            if ("ASA".equals(vendor)) {
                assertTrue(resp.body().contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED),
                        "ASA hygiene.csv must include ASA_ACL_NOT_REFERENCED finding");
            }
            if ("CP".equals(vendor) || "PAN".equals(vendor) || "FGT".equals(vendor)) {
                assertTrue(resp.body().contains("\"shadowed\""),
                        vendor + " hygiene.csv must include a shadowed finding row");
            }

            assertZeroCanaries("GET /api/v2/policy/policies/*(" + vendor + ")/hygiene.csv", resp);
        }
    }

    // ------------------------------------------------------------------ 2. log-detection catalogs

    @Test
    void logDetectionCatalogRoutesRefuseOrMaskWithZeroCanaryLeaks() throws Exception {
        String cookie = login();
        String version = ids.get("catalogVersion");

        for (String path : List.of("/api/v2/config/log-detection/catalogs",
                "/api/v2/config/log-detection/catalogs/" + version)) {
            Resp resp = get(path, cookie);
            assertTrue(resp.status() == 403 || resp.status() == 200,
                    "GET " + path + " must return 403 or 200 for aiview, got " + resp.status());
            if (resp.status() == 200) {
                assertNotNull(JSON.readTree(resp.body()), "GET " + path + " 200 response must be valid JSON");
            }
            assertZeroCanaries("GET " + path, resp);
        }
    }

    // ------------------------------------------------------------------ 3. SNMP polling settings

    @Test
    void snmpPollingSettingsRefusesAiviewByDefaultWithZeroCanaryLeaks() throws Exception {
        String cookie = login();
        Resp resp = get("/api/v2/config/snmp-polling", cookie);
        assertEquals(403, resp.status(),
                "GET /api/v2/config/snmp-polling must refuse aiview with 403 when UI2_AIVIEW_READONLY_ADMIN is off");
        assertZeroCanaries("GET /api/v2/config/snmp-polling", resp);
    }

    // ------------------------------------------------------------------ 4. Zone Matrix finding witness

    @Test
    void zoneMatrixFindingWitnessRefusesAiviewWithZeroLeaksAndZeroAuditRows() throws Exception {
        String cookie = login();
        String findingId = ids.get("zoneMatrixFindingId");
        long witnessViewsBefore = queryCount("select count(*) from zone_matrix_access_log where kind = 'WITNESS_VIEW'");

        // End-to-end HTTP call as aiview: refused at the route gate with 403 before reaching the controller.
        Resp resp = get("/api/v2/zone-matrix/findings/" + findingId + "/witness", cookie);
        assertEquals(403, resp.status(), "GET /api/v2/zone-matrix/findings/{findingId}/witness must refuse aiview with 403");
        assertFalse(resp.body().contains("\"cells\""), "refusal must not contain cells key");
        assertFalse(resp.body().contains("\"witness\""), "refusal must not contain witness key");
        assertZeroCanaries("GET /api/v2/zone-matrix/findings/{findingId}/witness", resp);

        // Defence-in-depth controller check: even if a replay_viewer session reached the controller,
        // ZoneMatrixService#findingWitness refuses with 403 READ_ONLY_PERSONA and writes no WITNESS_VIEW row.
        ZoneMatrixController controller = context.getBean(ZoneMatrixController.class);
        ResponseEntity<?> direct = controller.findingWitness(findingId, replayViewerRequest(Ui2Rows.ACTOR));
        assertEquals(403, direct.getStatusCode().value(), "ZoneMatrixController#findingWitness must refuse replay_viewer with 403");
        String directJson = JSON.writeValueAsString(direct.getBody());
        assertEquals("READ_ONLY_PERSONA", JSON.readTree(directJson).path("error").asText(),
                "ZoneMatrixController#findingWitness must return READ_ONLY_PERSONA for replay_viewer");
        assertFalse(directJson.contains("\"cells\""), "controller refusal must not contain cells key");
        assertFalse(directJson.contains("\"witness\""), "controller refusal must not contain witness key");
        assertEquals(Map.of(), count(directJson, estate.raw()),
                "controller refusal leaked raw canary classes: " + count(directJson, estate.raw()));

        long witnessViewsAfter = queryCount("select count(*) from zone_matrix_access_log where kind = 'WITNESS_VIEW'");
        assertEquals(witnessViewsBefore, witnessViewsAfter,
                "refused aiview witness reads must write zero WITNESS_VIEW rows to zone_matrix_access_log");
    }

    // ------------------------------------------------------------------ 5. Script run raw output download

    @Test
    void scriptRunOutputRefusesAiviewWithZeroStdoutCanaryLeaks() throws Exception {
        String cookie = login();
        String runId = ids.get("scriptRunId");

        // End-to-end HTTP call as aiview: refused at the route gate with 403 before reaching the controller.
        Resp resp = get("/api/v2/scripts/runs/" + runId + "/output", cookie);
        assertEquals(403, resp.status(), "GET /api/v2/scripts/runs/{runId}/output must refuse aiview with 403");
        assertZeroCanaries("GET /api/v2/scripts/runs/{runId}/output", resp);

        // Defence-in-depth controller check: even if a replay_viewer session reached the controller,
        // ScriptExecutionController#downloadRunOutput refuses with 403 FORBIDDEN_FOR_VIEWER and zero canary leaks.
        ScriptExecutionController controller = context.getBean(ScriptExecutionController.class);
        ResponseEntity<?> direct = controller.downloadRunOutput(runId, replayViewerRequest(Ui2Rows.ACTOR));
        assertEquals(403, direct.getStatusCode().value(),
                "ScriptExecutionController#downloadRunOutput must refuse replay_viewer with 403");
        String directJson = JSON.writeValueAsString(direct.getBody());
        assertEquals("FORBIDDEN_FOR_VIEWER", JSON.readTree(directJson).path("error").asText(),
                "ScriptExecutionController#downloadRunOutput must return FORBIDDEN_FOR_VIEWER for replay_viewer");
        assertEquals(Map.of(), count(directJson, estate.raw()),
                "controller refusal leaked raw canary classes: " + count(directJson, estate.raw()));
    }
}
