package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.zip.GZIPOutputStream;

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
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * The raw-versus-masked sweep of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &
 * post-parsing masking law"): a small synthetic estate is seeded into a real PostgreSQL 16 database, the application is
 * booted on it, the aiview persona ({@code role:replay_viewer}) logs in and GETs every route the static audit
 * ({@code AiviewMaskingLeakAuditTest}) calls aiview-reachable -- both enumerate through the one shared
 * {@link AiviewReachableRoutes} -- plus every ungated GET, and every seeded raw value is counted in every response body.
 *
 * <p>Seeded values are synthetic and built at runtime: hostnames in the FW-TANGO style plus look-alike real-shaped
 * names, addresses in 192.0.2.0/24, 198.51.100.0/24 and 2001:db8::/32, random serials, an administrator principal,
 * rule, object and policy names. Output names the route, the HTTP status and the value <em>class</em> with a count --
 * never a value. A surviving raw value fails the test unless the route and class are on {@link #KNOWN_LEAKS}, which
 * must match the findings exactly (an entry that stops leaking fails too, so the list only shrinks on purpose).</p>
 *
 * <p>The application's artefact store is configured from environment variables only; this test replaces the
 * {@code artefactStoreAccess} bean with a store on a temporary directory so the masked system-log text route reads a
 * real encrypted line (the logmask leak of 2026-10-18 was in exactly that route).</p>
 */
class AiviewRawVersusMaskedSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a seeded value is; the only thing about a value this test ever prints. */
    enum ValueClass { HOSTNAME, CLUSTER_NAME, ADDRESS, SERIAL, PRINCIPAL, RULE_NAME, OBJECT_NAME, POLICY_NAME }

    /**
     * Surviving raw values found by this sweep and left for a follow-up lane: {@code "GET <route> <CLASS>"}. Must equal
     * the findings exactly. Empty means no seeded raw value survives in any aiview GET body.
     */
    static final Set<String> KNOWN_LEAKS = Set.of();

    /**
     * aiview-reachable GET routes (by the static audit) that the running application nevertheless refuses aiview
     * (401/403), each reviewed. Must equal the observation exactly.
     */
    static final Map<String, String> REFUSED_ALTHOUGH_STATICALLY_REACHABLE = Map.of(
            "GET /api/v2/policy/changes/actor-candidates",
            "PolicyChangesController withholds administrator candidate names from the replay viewer (403 WITHHELD_IN_AIVIEW)");

    /**
     * Views that must show seeded rows (by opaque id, or the masked log line's process tag), so the zero counts above are
     * counts over real data and not over empty pages.
     */
    static final Map<String, Set<String>> COVERAGE = Map.ofEntries(
            Map.entry("GET /devices", Set.of("device")),
            Map.entry("GET /devices/*", Set.of("device")),
            Map.entry("GET /devices/*/inventory", Set.of("device")),
            Map.entry("GET /devices/*/backups", Set.of("backup")),
            Map.entry("GET /backups", Set.of("backup")),
            Map.entry("GET /backups/*/entries", Set.of("backup")),
            Map.entry("GET /clusters/*/inventory", Set.of("device")),
            Map.entry("GET /discovery/runs/*", Set.of("discovery-run")),
            Map.entry("GET /api/v2/jobs", Set.of("failed-job")),
            Map.entry("GET /api/v2/overview", Set.of("device", "failed-job")),
            Map.entry("GET /api/v2/topology", Set.of("device")),
            Map.entry("GET /api/v2/policy/policies/*", Set.of("policy", "rule")),
            Map.entry("GET /api/v2/policy/policies/*/rules", Set.of("rule")),
            Map.entry("GET /api/v2/policy/policies/*/history", Set.of("rule")),
            Map.entry("GET /api/v2/policy/changes", Set.of("rule")),
            Map.entry("GET /api/v2/policy/devices", Set.of("policy")),
            Map.entry("GET /api/v2/system-logs/*/chunks", Set.of("log-chunk")),
            Map.entry("GET /api/v2/system-logs/chunks/*/text", Set.of("log-line")),
            Map.entry("GET /api/v2/operations/static-route/preview", Set.of("device", "static-route-preview")));

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static ArtefactStore store;
    private static Estate estate;

    // ------------------------------------------------------------------ boot

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_sweep");
        work = Files.createTempDirectory("ui2-aiview-sweep-");
        byte[] storeKey = new byte[32];
        RANDOM.nextBytes(storeKey);
        Path storeRoot = Files.createDirectory(work.resolve("artefacts"));
        store = new FileArtefactStore(storeRoot,
                ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(storeKey)));
        estate = Estate.generate();
        seed(estate);
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(AiviewRawVersusMaskedSweepTest::useTestArtefactStore))
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

    /**
     * Runs after every bean definition is registered. The env-configured (here: absent) artefact store is replaced by
     * the test store, and the fixed machine port 8086 is not opened: this sweep reads only the main port, and another
     * fork booting the application at the same time (HttpSurfaceCharacterizationTest) owns 8086.
     */
    private static void useTestArtefactStore(ConfigurableListableBeanFactory beanFactory) {
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

    private static String digits(int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(RANDOM.nextInt(10));
        return out.toString();
    }

    record Device(String id, String vendor, String role, String hostname, String serial, String managementAddress,
            String interfaceAddress, String cluster) {
    }

    /** Every raw value the sweep looks for, generated per run. */
    record Estate(Device cpMemberA, Device cpMemberB, Device panFirewall, Device management, String cluster,
            String ipv6Management, String principal, String ruleName, String sectionName, String objectName,
            String objectAddress, String policyName, String policyId, String domainId, String objectId, String ruleId,
            Map<String, ValueClass> raw) {

        static Estate generate() {
            String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            int base = 100 + RANDOM.nextInt(90);
            Device a = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "FW-TANGO-" + tag().toUpperCase(Locale.ROOT), "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + base, "198.51.100." + base, cluster);
            Device b = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "ist" + tag() + "-fw-02", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 1), "198.51.100." + (base + 1), cluster);
            Device pan = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway",
                    "dc" + tag() + "-edge-gw01", "0" + digits(11),
                    "192.0.2." + (base + 2), "198.51.100." + (base + 2), null);
            Device mgmt = new Device(Ui2Rows.opaqueId("dev"), "check_point", "management_server",
                    "core" + tag() + "-smc-a", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 3), null, null);
            String v6 = "2001:db8:" + Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff)) + "::"
                    + Integer.toHexString(0x100 + RANDOM.nextInt(0xefff));
            String principal = "adm" + tag() + ".ops";
            String ruleName = "allow-" + tag() + "-payroll";
            String sectionName = "sec-" + tag() + "-dmz";
            String objectName = "h-" + tag() + "-dbsrv";
            String objectAddress = "198.51.100." + (base + 5);
            String policyName = "pkg-" + tag() + "-branch";
            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(a, b, pan, mgmt)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.managementAddress(), ValueClass.ADDRESS);
                if (d.interfaceAddress() != null) raw.put(d.interfaceAddress(), ValueClass.ADDRESS);
            }
            raw.put(cluster, ValueClass.CLUSTER_NAME);
            raw.put(v6, ValueClass.ADDRESS);
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(ruleName, ValueClass.RULE_NAME);
            raw.put(sectionName, ValueClass.RULE_NAME);
            raw.put(objectName, ValueClass.OBJECT_NAME);
            raw.put(objectAddress, ValueClass.ADDRESS);
            raw.put(policyName, ValueClass.POLICY_NAME);
            // the static-route preview's typed destination and its representative host (documentation range)
            raw.put("203.0.113.0", ValueClass.ADDRESS);
            raw.put("203.0.113.10", ValueClass.ADDRESS);
            return new Estate(a, b, pan, mgmt, cluster, v6, principal, ruleName, sectionName, objectName, objectAddress,
                    policyName, "policy-" + UUID.randomUUID(), "domain-" + UUID.randomUUID(),
                    "obj-" + UUID.randomUUID(), "rule-" + UUID.randomUUID(), Map.copyOf(raw));
        }

        List<Device> devices() {
            return List.of(cpMemberA, cpMemberB, panFirewall, management);
        }
    }

    /** Seeding ids the sweep substitutes for path variables. */
    private static final Map<String, String> ids = new TreeMap<>();

    private static void seed(Estate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, () -> {
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
                            Ui2Rows.opaqueId("ep"), d.id(), d.managementAddress());
                    exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                            + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                }
                // The management server's second endpoint is IPv6.
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                        + "values (?, ?, 'https', ?, now() + interval '1 second')",
                        Ui2Rows.opaqueId("ep"), e.management().id(), e.ipv6Management());
                exec(c, "insert into device_policy_install(device_id, policy_name, installed_at_text, source_read) "
                        + "values (?, ?, 'synthetic', 'synthetic_seed')", e.cpMemberA().id(), e.policyName());

                // A discovery run on the management server: the candidates carry hostnames, serials and addresses.
                String discoveryJob = job(c, null, "discovery", "COMPLETED", null, "discovery_run", "run-pending");
                String runId = Ui2Rows.opaqueId("disc");
                exec(c, "insert into discovery_run(run_id, vendor, management_address, credential_reference_id, "
                        + "requested_by_actor_fingerprint, state, job_id) values (?, 'check_point', ?, ?, ?, 'FINISHED', ?)",
                        runId, e.management().managementAddress(), credential, Ui2Rows.ACTOR, discoveryJob);
                exec(c, "update jobs set target_ref = ? where job_id = ?", runId, discoveryJob);
                ids.put("runId:discovery", runId);
                String clusterCandidate = Ui2Rows.opaqueId("cand");
                exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                        + "management_address, importable) values (?, ?, 'check_point', ?, 'cluster', ?, ?, false)",
                        clusterCandidate, runId, e.cluster(), e.cluster(), e.cpMemberA().managementAddress());
                for (Device d : e.devices()) {
                    exec(c, "insert into discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, display_name, "
                            + "own_address, management_address, cluster_reference, parent_candidate_id, model, software_version, "
                            + "importable, import_outcome) values (?, ?, ?, ?, 'gateway', ?, ?, ?, ?, ?, 'synthetic-model', "
                            + "'R81.20', true, 'already_imported')",
                            Ui2Rows.opaqueId("cand"), runId, d.vendor(), d.serial(), d.hostname(), d.managementAddress(),
                            e.management().managementAddress(), d.cluster(), d.cluster() == null ? null : clusterCandidate);
                }

                // One inventory run per gateway: interface addresses, zone names.
                for (Device d : List.of(e.cpMemberA(), e.cpMemberB(), e.panFirewall())) {
                    String job = job(c, d.id(), "inventory_collect", "COMPLETED", null, "device", null);
                    String run = Ui2Rows.opaqueId("inv");
                    exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) "
                            + "values (?, ?, ?, now(), 1)", run, d.id(), job);
                    String itf = Ui2Rows.opaqueId("itf");
                    exec(c, "insert into device_interface(interface_id, run_id, context, name, kind, state, zone) "
                            + "values (?, ?, 'physical', 'eth1', 'physical', 'up', 'internal')", itf, run);
                    exec(c, "insert into device_interface_address(address_id, interface_id, address, family, role) "
                            + "values (?, ?, ?, 'ipv4', 'member')", Ui2Rows.opaqueId("addr"), itf, d.interfaceAddress());
                }

                // A failed job whose free-text reason names a device, its address and the administrator.
                String failed = job(c, e.panFirewall().id(), "inventory_collect", "FAILED",
                        "ssh to " + e.panFirewall().hostname() + " at " + e.panFirewall().managementAddress()
                                + " refused for " + e.principal() + " (serial " + e.panFirewall().serial() + ")",
                        "device", null);
                ids.put("jobId", failed);

                // A configuration backup row of the first member.
                String backup = UUID.randomUUID().toString();
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path, deviation_state, backup_type) "
                        + "values (?, ?, 'backup', 'check_point', 'R81.20', ?, ?, 10, ?, 10, 'k1', ?, "
                        + "'{\"level_reached\":\"V2\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', ?, 'first', 'standard')",
                        backup, e.cpMemberA().id(), sha256(e.cpMemberA().hostname()), "0".repeat(64), "1".repeat(64),
                        new byte[32], backup);
                ids.put("artefactId", backup);

                // A stored system-log chunk whose only line names a hostname, a serial, an address and the administrator.
                String line = "Nov 23 10:00:01 " + e.cpMemberA().hostname() + " fwd[4242]: sync with peer "
                        + e.cpMemberB().hostname() + " serial " + e.cpMemberB().serial() + " from "
                        + e.cpMemberB().managementAddress() + " by " + e.principal() + "\n";
                String logJob = job(c, e.cpMemberA().id(), "system_log_collect", "COMPLETED", null, "device", null);
                var handle = store.open(e.cpMemberA().id(), logJob, "check_point", true);
                byte[] plain = line.getBytes(StandardCharsets.UTF_8);
                handle.sink().write(plain);
                var meta = handle.finish();
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, hostname_fingerprint, "
                        + "plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, wrapped_data_key, "
                        + "validation, retention_tier, expires_at, recovery_volume_path) values (?, ?, 'system_log', "
                        + "'check_point', ?, ?, ?, ?, ?, ?, ?, "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', now() + interval '30 days', ?)",
                        meta.ref().value(), e.cpMemberA().id(), sha256(e.cpMemberA().hostname()), meta.plaintextSha256(),
                        meta.plaintextBytes(), meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(),
                        meta.wrappedDataKey(), meta.ref().value());
                exec(c, "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                        + "reads_issued, duration_ms, detail) values (?, ?, ?, 'cp_messages', 'COLLECTED', ?, ?, 1, 5, '{}'::jsonb)",
                        UUID.randomUUID().toString(), e.cpMemberA().id(), logJob, (long) plain.length, (long) plain.length);
                String chunk = UUID.randomUUID().toString();
                exec(c, "insert into system_log_chunk(chunk_id, device_id, source_key, job_id, log_day, file_name_hmac, "
                        + "start_offset, end_offset, bytes, lines, redaction_count, flags, artefact_ref, wrapped_data_key, "
                        + "plaintext_hmac, ciphertext_bytes) values (?, ?, 'cp_messages', ?, current_date, ?, 0, ?, ?, 1, 0, "
                        + "'{}'::text[], ?, ?, ?, ?)",
                        chunk, e.cpMemberA().id(), logJob, "2".repeat(64), (long) plain.length, (long) plain.length,
                        meta.ref().value(), meta.wrappedDataKey(), "3".repeat(64), meta.ciphertextBytes());
                ids.put("chunkId", chunk);

                // A Check Point policy of the management server: rule, section, object and package names, the
                // administrator as last modifier, the gateways as install targets.
                var object = new PolicySnapshot.PolicyObject(e.objectId(), e.objectName(), "host", List.of(),
                        List.of(e.objectAddress()), "RESOLVED");
                var rule = new PolicySnapshot.Rule(e.ruleId(), UUID.randomUUID().toString(), 1, e.ruleName(), true,
                        new PolicySnapshot.Cell(List.of(e.objectId()), false), new PolicySnapshot.Cell(List.of(e.objectId()), false),
                        new PolicySnapshot.Cell(List.of(), false), new PolicySnapshot.Cell(List.of(), false), "Accept", "Log",
                        "requested by " + e.principal() + " for " + e.panFirewall().hostname(),
                        Map.of("last-modifier", List.of(e.principal())));
                var metadata = new PolicySnapshot.Metadata(e.policyId(), e.management().id(), e.management().hostname(), "CP",
                        e.domainId(), "SMC User", e.policyName(), "2026-11-20T10:00:00Z", null,
                        List.of(new PolicySnapshot.Target(e.cpMemberA().id(), e.cpMemberA().hostname(), "", "IN_SYNC"),
                                new PolicySnapshot.Target(e.cpMemberB().id(), e.cpMemberB().hostname(), "", "IN_SYNC")));
                var snapshot = new PolicySnapshot(metadata,
                        List.of(new PolicySnapshot.Section("section-1", e.sectionName(), "Network", null, List.of(rule))),
                        Map.of(e.objectId(), object), List.of());
                exec(c, "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) "
                        + "values (?, '2026-11-20T10:00:00Z'::timestamptz, ?::jsonb, ?::jsonb)",
                        e.policyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot));
                exec(c, "insert into policy_rule_history(policy_id, rule_key, rule_id, identity_fallback, change_type, "
                        + "collected_at, changed_on, changed_by, changes) values (?, ?, ?, false, 'modified', now(), "
                        + "'2026-11-20T09:00:00Z', ?, ?::jsonb)",
                        e.policyId(), e.ruleId(), e.ruleId(), e.principal(),
                        JSON.writeValueAsString(List.of(Map.of("field", "name", "before", "old-" + tag(), "after", e.ruleName()))));

                // A static-route preview on the cluster (the unit is the cluster), requested by the administrator.
                String preview = "srp-" + UUID.randomUUID();
                exec(c, "insert into static_route_preview(preview_id, idempotency_key, request_payload_sha256, requested_by, "
                        + "unit_id, target_device_id, vendor, routing_context, operation, destination_cidr, nexthop_ip, "
                        + "interface_name, route_name, device_comment, representative_host, state, verdict, pre_checks_json, "
                        + "command_plan_json, inverse_plan_json, completed_at) values (?, ?, ?, ?, ?, ?, 'check_point', "
                        + "'default', 'ADD', '203.0.113.0/24', ?, 'eth1', ?, ?, '203.0.113.10', 'DONE', 'PASS', '[]'::jsonb, "
                        + "'{}'::jsonb, '{}'::jsonb, now())",
                        preview, UUID.randomUUID().toString(), "4".repeat(64), e.principal(), e.cluster(), e.cpMemberA().id(),
                        e.cpMemberB().interfaceAddress(), "to-" + e.panFirewall().hostname(),
                        "for " + e.principal());
                ids.put("previewId", preview);
            });
        }
    }

    private static String job(Connection c, String deviceId, String type, String state, String reason, String targetKind,
            String targetRef) throws SQLException {
        String id = Ui2Rows.opaqueId("job");
        exec(c, "insert into jobs(job_id, job_type, target_kind, target_device_id, target_ref, submitted_by_actor_fingerprint, "
                + "state, action_class, terminal_reason, finished_at) values (?, ?, ?, ?, ?, ?, ?, 'read', ?, "
                + (state.equals("REQUESTED") ? "null" : "now()") + ")",
                id, type, targetKind, deviceId, targetRef, Ui2Rows.ACTOR, state, reason);
        return id;
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

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ leak counting

    /** The search forms of one raw value: exact text, case-insensitively, and the other spellings of an address. */
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
                    if (i > 0) { full.append(':'); unpadded.append(':'); }
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
            // dotted form, not as the prefix of a longer address (192.0.2.1 inside 192.0.2.10)
            out.add(Pattern.compile("(?<![0-9.])" + Pattern.quote(value) + "(?![0-9])"));
            // URL- or JSON-escaped dots do not occur in JSON bodies; the hyphenated form some name builders use does
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

    /** A concrete request target for a route template; seeded ids where the estate has one, an unknown id otherwise. */
    static String target(String template) {
        Estate e = estate;
        String path = template;
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("deviceId", e.cpMemberA().id());
        byName.put("clusterMemberRef", e.cluster());
        byName.put("clusterRef", e.cluster());
        byName.put("clusterId", e.cluster());
        byName.put("artefactId", ids.get("artefactId"));
        byName.put("leftId", ids.get("artefactId"));
        byName.put("chunkId", ids.get("chunkId"));
        byName.put("jobId", ids.get("jobId"));
        byName.put("previewId", ids.get("previewId"));
        if (template.startsWith("/discovery/runs/")) byName.put("runId", ids.get("runId:discovery"));
        if (template.startsWith("/api/v2/policy/policies/")) byName.put("id", e.policyId());
        if (template.startsWith("/api/v2/policy/devices/")) byName.put("id", e.management().id());
        if (template.startsWith("/api/v2/policy/domains/")) byName.put("id", e.domainId());
        if (template.startsWith("/api/v2/policy/objects/")) byName.put("id", e.objectId());
        if (template.startsWith("/devices/{id}")) byName.put("id", e.cpMemberA().id());
        Matcher m = Pattern.compile("\\{([^}/:]+)(?::[^}]*)?}").matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = byName.getOrDefault(m.group(1), UNKNOWN_ID);
            m.appendReplacement(out, Matcher.quoteReplacement(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(out);
        String query = QUERY.get(template);
        return query == null ? out.toString() : out + "?" + query.replace("{source}", e.management().id())
                .replace("{rule}", e.ruleId()).replace("{object}", e.objectId()).replace("{device}", e.cpMemberA().id())
                .replace("{policy}", e.policyId())
                .replace("{cluster}", java.net.URLEncoder.encode(e.cluster(), StandardCharsets.UTF_8))
                .replace("{artefact}", ids.get("artefactId"));
    }

    /** Required (or identity-bearing) query parameters of some routes, so the handler reaches its data. */
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
            Map.entry("/api/v2/cp-failover/runs", "clusterId={cluster}"),
            Map.entry("/api/v2/cp-failover/approvals", "clusterId={cluster}"),
            Map.entry("/api/v2/pan-failover/runs", "clusterId={cluster}"),
            Map.entry("/api/v2/pan-failover/approvals", "clusterId={cluster}"),
            Map.entry("/api/v2/asa-failover/runs", "clusterId={cluster}"),
            Map.entry("/api/v2/fgt-failover/runs", "clusterId={cluster}"),
            Map.entry("/api/v2/diagnostics/ports", "device_id={device}"),
            Map.entry("/api/v2/estate/as-of", "at=2026-11-23T00:00:00Z"),
            Map.entry("/api/v2/policy/changes/actor-candidates", "revision=1"),
            Map.entry("/backups/{leftId}/compare", "with={artefact}"),
            Map.entry("/api/v2/system-logs/crash-files", "deviceId={device}"));

    // ------------------------------------------------------------------ HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Resp(int status, String contentType, String body) {
    }

    private static Resp get(String target, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String loginAsAiview() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("username", "aiview", "password", "aiview"))))
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

    // ------------------------------------------------------------------ the sweep

    @Test
    void noSeededRawValueSurvivesInAnyAiviewGetBody() throws Exception {
        String cookie = loginAsAiview();
        assertNotNull(cookie);

        List<AiviewReachableRoutes.Endpoint> all = AiviewReachableRoutes.all();
        List<AiviewReachableRoutes.Endpoint> reachable = AiviewReachableRoutes.reachable();
        assertTrue(reachable.size() > 100, "aiview-reachable routes: " + reachable.size());
        // the static audit's set (same helper, same production inputs), restricted to GET
        Set<String> staticReachableGets = new TreeSet<>();
        for (var r : reachable) if (r.method().equals("GET")) staticReachableGets.add(r.key());

        Map<String, String> templates = new TreeMap<>(); // key -> one path template
        Map<String, String> kind = new TreeMap<>();      // key -> REACHABLE / UNGATED / GATED_OTHER
        for (var e : all) {
            if (!e.method().equals("GET")) continue;
            templates.putIfAbsent(e.key(), e.pathTemplate());
            String k = e.actionId() == null ? "UNGATED"
                    : AiviewReachableRoutes.aiviewMayCall(e.actionId()) ? "REACHABLE" : "GATED_OTHER";
            kind.merge(e.key(), k, (a, b) -> a.equals("REACHABLE") || b.equals("REACHABLE") ? "REACHABLE" : a);
        }
        assertEquals(staticReachableGets, new TreeSet<>(kind.entrySet().stream()
                .filter(x -> x.getValue().equals("REACHABLE")).map(Map.Entry::getKey).toList()),
                "the sweep's GET set is the static audit's reachable GET set");

        Map<String, String> markers = new LinkedHashMap<>(); // opaque seeded id -> what it shows
        for (Device d : estate.devices()) markers.put(d.id(), "device");
        markers.put(estate.policyId(), "policy");
        markers.put(estate.ruleId(), "rule");
        markers.put(ids.get("chunkId"), "log-chunk");
        markers.put("fwd[4242]", "log-line");
        markers.put(ids.get("artefactId"), "backup");
        markers.put(ids.get("runId:discovery"), "discovery-run");
        markers.put(ids.get("jobId"), "failed-job");
        markers.put(ids.get("previewId"), "static-route-preview");
        Map<String, Set<String>> seen = new TreeMap<>();

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        Map<String, Integer> statusByRoute = new TreeMap<>();
        Set<String> refusedReachable = new TreeSet<>();
        Set<String> servedGatedOther = new TreeSet<>();
        Map<Integer, Integer> statusHistogram = new TreeMap<>();
        for (var entry : templates.entrySet()) {
            String key = entry.getKey();
            Resp resp = get(target(entry.getValue()), cookie);
            statusByRoute.put(key, resp.status());
            statusHistogram.merge(resp.status(), 1, Integer::sum);
            String k = kind.get(key);
            if (k.equals("REACHABLE") && (resp.status() == 401 || resp.status() == 403)) refusedReachable.add(key);
            if (k.equals("GATED_OTHER") && resp.status() != 401 && resp.status() != 403) servedGatedOther.add(key);
            Map<ValueClass, Integer> found = count(resp.body(), estate.raw());
            if (!found.isEmpty()) leaks.put(key, found);
            Set<String> kinds = new TreeSet<>();
            markers.forEach((marker, what) -> { if (resp.body().contains(marker)) kinds.add(what); });
            seen.put(key, kinds);
        }

        // ---- report: route, status, value class and count; never a value
        List<String> report = new ArrayList<>();
        int total = 0;
        Set<String> observedLeaks = new TreeSet<>();
        for (var entry : templates.keySet()) {
            Map<ValueClass, Integer> found = leaks.getOrDefault(entry, Map.of());
            int sum = found.values().stream().mapToInt(Integer::intValue).sum();
            total += sum;
            for (var cls : found.keySet()) observedLeaks.add(entry + " " + cls);
            String reach = switch (kind.get(entry)) {
                case "REACHABLE" -> refusedReachable.contains(entry) ? "not-reachable" : "reachable";
                case "UNGATED" -> "ungated";
                default -> "gated-other";
            };
            report.add(String.format(Locale.ROOT, "AIVIEW_SWEEP %-13s %3d %-62s raw=%s seeded_rows_seen=%s", reach,
                    statusByRoute.get(entry), entry, found.isEmpty() ? "0" : found.toString(), seen.get(entry)));
        }
        report.forEach(System.out::println);
        System.out.println("AIVIEW_SWEEP_SUMMARY routes=" + templates.size() + " reachable_get=" + staticReachableGets.size()
                + " seeded_values=" + estate.raw().size() + " surviving_raw_occurrences=" + total
                + " status=" + statusHistogram);

        List<String> uncovered = new ArrayList<>();
        COVERAGE.forEach((route, expected) -> {
            if (!seen.getOrDefault(route, Set.of()).containsAll(expected)) {
                uncovered.add(route + " expected " + expected + " saw " + seen.get(route));
            }
        });
        assertEquals(List.of(), uncovered, "seeded rows must reach these views, or a zero count proves nothing");
        assertEquals(new TreeSet<>(REFUSED_ALTHOUGH_STATICALLY_REACHABLE.keySet()), refusedReachable,
                "statically aiview-reachable GET routes the application refuses aiview (401/403)");
        assertEquals(Set.of(), servedGatedOther,
                "GET routes the static audit says aiview may not call, answered with something other than 401/403");
        assertEquals(new TreeSet<>(KNOWN_LEAKS), observedLeaks,
                "seeded raw values surviving in aiview GET bodies (route + value class); KNOWN_LEAKS must match exactly");
    }

    /** The counter itself: every spelling it claims to catch is caught, and a longer address is not a match. */
    @Test
    void theCounterSeesEverySpellingAndNoPrefixMatch() {
        Map<String, ValueClass> raw = Map.of("192.0.2.15", ValueClass.ADDRESS, "2001:db8:a1::7", ValueClass.ADDRESS,
                "ist" + "x0a0b0c-fw-02", ValueClass.HOSTNAME);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ADDRESS, 3),
                count("2001:DB8:A1::7 2001:0db8:00a1:0000:0000:0000:0000:0007 2001:db8:a1:0:0:0:0:7 2001:db8:a1::70", raw));
        assertEquals(Map.of(ValueClass.HOSTNAME, 1), count("seen on IST" + "X0A0B0C-FW-02 today", raw));
        assertEquals(Map.of(), count("{\"hostname\":\"FW-TANGO-04\"}", raw));
    }
}
