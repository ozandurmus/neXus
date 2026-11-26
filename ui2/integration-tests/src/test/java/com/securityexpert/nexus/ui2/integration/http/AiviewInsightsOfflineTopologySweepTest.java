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
import java.time.Instant;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 8 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp;
 * post-parsing masking law"): six planes that rounds 1-7 never seeded. A synthetic estate with planted raw values is
 * written into a real PostgreSQL 16 database, the application is booted, the {@code aiview} persona
 * ({@code role:replay_viewer}) logs in, GETs each route below, and every planted raw value is counted in every body.
 * Output names route, status and value <em>class</em> with a count, never a value.
 *
 * <table>
 *   <caption>Route coverage</caption>
 *   <tr><th>Route</th><th>Plane</th><th>Status</th></tr>
 *   <tr><td>GET /api/v2/policy/policies/{id}/insights</td><td>Policy Insights overview and list</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/policy/policies/{id}/insight?insightId=</td><td>Policy Insights detail</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/policy/policies/{id}/offline-analyses</td><td>Offline analyses of a policy</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/policy/offline-analyses/{id}</td><td>One offline analysis (rule and object usage)</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/path-analysis/validate/{validationId}</td><td>Path validation run and hops</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/topology/overrides</td><td>Topology manual overrides</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/system-logs/{deviceId}/sources</td><td>System-log source ledger</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/system-logs/{deviceId}/hcp</td><td>Check Point health-check report files</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/system-logs/{deviceId}/spike-data</td><td>Spike data directories</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/sanitize/jobs</td><td>Data Sanitizer capabilities (every permission false)</td><td>COVERED</td></tr>
 *   <tr><td>GET /api/v2/sanitize/jobs/{jobId}</td><td>Sanitizer job</td><td>NOT COVERED (refused by design)</td></tr>
 *   <tr><td>GET /api/v2/sanitize/jobs/{jobId}/download</td><td>Sanitizer output</td><td>NOT COVERED (refused by design)</td></tr>
 *   <tr><td>GET /api/v2/sanitize/reverts/{revertId}</td><td>Sanitizer revert job</td><td>NOT COVERED (refused by design)</td></tr>
 * </table>
 *
 * <p>The three refused sanitizer routes are asserted as 403 whose body carries no planted value; they are never listed
 * as covered, because aiview sees no payload there.</p>
 *
 * <p>Seeded planes: a Check Point package (shadowed, conflicting and unused rules, duplicate address objects, a raw
 * layer, section, package and domain name, the administrator as last modifier); one stored offline analysis in the
 * aggregate shape the service reads (rule and object names, observed addresses, uploader, a file name that names a
 * host); one path validation with MATCH, MISMATCH, NOT_EVALUABLE and a still-pending hop whose stored plan names zones,
 * a virtual router, a rule and addresses (never returned); two topology overrides (one revoked) with raw contexts,
 * interface names, a subnet, a note and an author; a source cursor and runs, an HCP file and Spike directories (name
 * classes and member classes deliberately carry a host name the schema would accept); a sanitizer job and revert job
 * created by the administrator.</p>
 *
 * <p>The class is self-contained on purpose (other sweeps are edited by other lanes): the boot, login, row-insert and
 * body-scan helpers are copies of the neighbouring sweeps'.</p>
 */
class AiviewInsightsOfflineTopologySweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a planted value is; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, ADDRESS, SERIAL, PRINCIPAL, RULE_NAME, OBJECT_NAME, POLICY_NAME, LAYER_OR_DOMAIN_NAME, CONTEXT_OR_INTERFACE,
        FILE_OR_DIRECTORY_NAME
    }

    private static final String INSIGHTS = "GET /api/v2/policy/policies/{id}/insights";
    private static final String INSIGHT = "GET /api/v2/policy/policies/{id}/insight";
    private static final String OFFLINE_LIST = "GET /api/v2/policy/policies/{id}/offline-analyses";
    private static final String OFFLINE_ONE = "GET /api/v2/policy/offline-analyses/{id}";
    private static final String VALIDATION = "GET /api/v2/path-analysis/validate/{validationId}";
    private static final String OVERRIDES = "GET /api/v2/topology/overrides";
    private static final String SOURCES = "GET /api/v2/system-logs/{deviceId}/sources";
    private static final String HCP = "GET /api/v2/system-logs/{deviceId}/hcp";
    private static final String SPIKE = "GET /api/v2/system-logs/{deviceId}/spike-data";
    private static final String SANITIZE_JOBS = "GET /api/v2/sanitize/jobs";
    private static final String SANITIZE_JOB = "GET /api/v2/sanitize/jobs/{jobId}";
    private static final String SANITIZE_DOWNLOAD = "GET /api/v2/sanitize/jobs/{jobId}/download";
    private static final String SANITIZE_REVERT = "GET /api/v2/sanitize/reverts/{revertId}";

    private static final List<String> COVERED = List.of(INSIGHTS, INSIGHT, OFFLINE_LIST, OFFLINE_ONE, VALIDATION, OVERRIDES,
            SOURCES, HCP, SPIKE, SANITIZE_JOBS);
    private static final List<String> REFUSED = List.of(SANITIZE_JOB, SANITIZE_DOWNLOAD, SANITIZE_REVERT);

    /**
     * Surviving raw values per route and value class, found by this sweep and left for a masking fix lane (no production
     * class is edited here). A new leak, a larger count, or a fix that shrinks a count without editing this map fails
     * the test.
     * <ol>
     *   <li>{@code GET /api/v2/topology/overrides}: {@code overrides[].a.context} and {@code overrides[].b.context} (the
     *       virtual-system or virtual-context name of each end) are returned raw: 2 overrides x 2 ends = 4. The key-based
     *       switch of {@code PrivacyMaskingResponseBodyAdvice} masks {@code interface_name} but has no case for a bare
     *       {@code context} key ({@code maskContext} is reached only for composite element strings), so the nested
     *       {@code a}/{@code b} endpoint objects pass the context through. A real exposure: a context name is a raw
     *       estate identity.</li>
     *   <li>{@code GET /api/v2/system-logs/{deviceId}/spike-data}: {@code directories[].name_class} and
     *       {@code directories[].member_classes[].class} are returned as stored. Defence-in-depth gap, not an observed
     *       production leak: the collector writes only the closed name-class vocabulary with keyed tokens for unknown
     *       words, so a host name never reaches these columns from the real pipeline; this sweep plants one because the
     *       column check ({@code [A-Za-z0-9._:+?-]}) would accept it, and the route has no value masking of its own.
     *       Each of the 2 fields carries the planted directory name (class FILE_OR_DIRECTORY_NAME, 2) which embeds a
     *       member host name (class HOSTNAME, 2).</li>
     * </ol>
     */
    static final Map<String, Map<ValueClass, Integer>> KNOWN_LEAKS = Map.of(
            OVERRIDES, Map.of(ValueClass.CONTEXT_OR_INTERFACE, 4),
            SPIKE, Map.of(ValueClass.HOSTNAME, 2, ValueClass.FILE_OR_DIRECTORY_NAME, 2));

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static Estate estate;

    // ------------------------------------------------------------------ boot (copied from the neighbouring sweeps)

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_insights_offline");
        work = Files.createTempDirectory("ui2-aiview-insights-offline-");
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
    record Estate(Device cpA, Device cpB, Device pan, Device mgmt, String principal, String v6, String transit,
            String policyName, String policyId, String domainId, String domainName, String packageLayer, String sectionName,
            String objectAName, String objectBName, String objectNetName, String objectAddress, String netAddress,
            String ruleOne, String ruleTwo, String ruleThree, String ruleFour, String zoneA, String zoneB,
            String virtualRouter, String ifaceA, String ifaceB, String contextA, String contextB, String subnet,
            String uploadName, String spikeDirectory, String note, Map<String, ValueClass> raw) {

        static Estate generate() {
            String cluster = "CLX-ROMEO-" + tag().toUpperCase(Locale.ROOT);
            int base = 20 + RANDOM.nextInt(150);
            Device a = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway",
                    "FW-TANGO-" + tag().toUpperCase(Locale.ROOT), "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + base, cluster);
            Device b = new Device(Ui2Rows.opaqueId("dev"), "check_point", "gateway", "ist" + tag() + "-fw-02",
                    "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + 1), cluster);
            Device pan = new Device(Ui2Rows.opaqueId("dev"), "palo_alto", "gateway", "dc" + tag() + "-edge-gw01",
                    "0" + hex(6).replaceAll("[a-f]", "7"), "192.0.2." + (base + 2), null);
            Device mgmt = new Device(Ui2Rows.opaqueId("dev"), "check_point", "management_server", "core" + tag() + "-smc-a",
                    "CP" + hex(5).toUpperCase(Locale.ROOT), "192.0.2." + (base + 3), null);
            String principal = "adm" + tag() + ".ops";
            int net = base % 150 + 1;
            String v6 = "2001:db8:" + hex(2) + "::" + (1 + RANDOM.nextInt(200));
            String transit = "198.51.100." + (net + 2);
            String objectAddress = "198.51.100." + net;
            String netAddress = "203.0.113." + (net + 3);
            String policyName = "pkg-" + tag() + "-branches";
            String domainName = "dom-" + tag() + "-retail";
            String layer = "lyr-" + tag() + "-access";
            String section = "sec-" + tag() + "-inbound";
            String objectA = "h-" + tag() + "-dbsrv";
            String objectB = "h-" + tag() + "-dbsrv-copy";
            String objectNet = "n-" + tag() + "-cardnet";
            String r1 = "allow-" + tag() + "-payroll";
            String r2 = "again-" + tag() + "-payroll";
            String r3 = "deny-" + tag() + "-legacy";
            String r4 = "idle-" + tag() + "-vendor";
            String zoneA = "zn-" + tag() + "-cardholder";
            String zoneB = "zn-" + tag() + "-corebank";
            String vr = "vr-" + tag() + "-wan";
            String ifaceA = "bond" + tag() + ".410";
            String ifaceB = "ethernet1/" + tag();
            String contextA = "vs-" + tag() + "-dmz";
            String contextB = "vsys-" + tag();
            String subnet = "198.51.100." + (net + 40) + "/29";
            String upload = "traffic-" + a.hostname() + "-export.csv";
            String spike = "spike-" + b.hostname();
            String note = "cabling checked by " + principal + " between " + a.hostname() + " and " + pan.hostname() + " ("
                    + transit + ", " + a.serial() + ")";
            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(a, b, pan, mgmt)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.address(), ValueClass.ADDRESS);
            }
            raw.put(cluster, ValueClass.HOSTNAME);
            raw.put(v6, ValueClass.ADDRESS);
            raw.put(transit, ValueClass.ADDRESS);
            raw.put(objectAddress, ValueClass.ADDRESS);
            raw.put(netAddress, ValueClass.ADDRESS);
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(policyName, ValueClass.POLICY_NAME);
            raw.put(domainName, ValueClass.LAYER_OR_DOMAIN_NAME);
            raw.put(layer, ValueClass.LAYER_OR_DOMAIN_NAME);
            raw.put(section, ValueClass.LAYER_OR_DOMAIN_NAME);
            raw.put(objectA, ValueClass.OBJECT_NAME);
            raw.put(objectB, ValueClass.OBJECT_NAME);
            raw.put(objectNet, ValueClass.OBJECT_NAME);
            for (String rule : List.of(r1, r2, r3, r4)) raw.put(rule, ValueClass.RULE_NAME);
            for (String name : List.of(zoneA, zoneB, vr, ifaceA, ifaceB, contextA, contextB)) {
                raw.put(name, ValueClass.CONTEXT_OR_INTERFACE);
            }
            raw.put(upload, ValueClass.FILE_OR_DIRECTORY_NAME);
            raw.put(spike, ValueClass.FILE_OR_DIRECTORY_NAME);
            return new Estate(a, b, pan, mgmt, principal, v6, transit, policyName, "policy-" + UUID.randomUUID(),
                    "domain-" + UUID.randomUUID(), domainName, layer, section, objectA, objectB, objectNet, objectAddress,
                    netAddress, r1, r2, r3, r4, zoneA, zoneB, vr, ifaceA, ifaceB, contextA, contextB, subnet, upload, spike,
                    note, Map.copyOf(raw));
        }

        List<Device> devices() {
            return List.of(cpA, cpB, pan, mgmt);
        }
    }

    // ------------------------------------------------------------------ seeding

    /** Opaque identifiers the routes are addressed by. */
    private static final Map<String, String> ids = new TreeMap<>();

    private static void seed(Estate e) throws Exception {
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, e.principal(), Ui2Rows.ACTION, () -> {
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
                // The management server's second endpoint is IPv6.
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                        + "values (?, ?, 'https', ?, now() + interval '1 second')", Ui2Rows.opaqueId("ep"), e.mgmt().id(), e.v6());
            });
            seedPolicy(c, e);
            seedOfflineAnalysis(c, e);
            seedPathValidation(c, e);
            seedTopologyOverrides(c, e);
            seedSystemLogs(c, e);
            seedSanitizer(c, e);
        }
    }

    private static PolicySnapshot.PolicyObject address(String id, String name, String value) {
        return new PolicySnapshot.PolicyObject(id, name, "address", List.of(), List.of("ipv4-address: " + value), "RESOLVED");
    }

    private static PolicySnapshot.Cell cell(String id) {
        return new PolicySnapshot.Cell(List.of(id), false);
    }

    /**
     * A Check Point package of the management server: r1 and r2 are the same match (r2 is fully shadowed), r3 matches the
     * same values on a differently named object with the opposite action, r4 has a stored zero hit count. The layer,
     * section, package and domain names, the object names and the administrator as last modifier are raw.
     */
    private static void seedPolicy(Connection c, Estate e) throws Exception {
        String objectA = "obj-" + UUID.randomUUID();
        String objectB = "obj-" + UUID.randomUUID();
        String objectNet = "obj-" + UUID.randomUUID();
        Map<String, PolicySnapshot.PolicyObject> objects = new LinkedHashMap<>();
        objects.put("any", new PolicySnapshot.PolicyObject("any", "Any", "any", List.of(), List.of(), "RESOLVED"));
        objects.put(objectA, address(objectA, e.objectAName(), e.objectAddress()));
        objects.put(objectB, address(objectB, e.objectBName(), e.objectAddress()));
        objects.put(objectNet, new PolicySnapshot.PolicyObject(objectNet, e.objectNetName(), "address", List.of(),
                List.of("ip-netmask: " + e.netAddress() + "/32"), "RESOLVED"));
        Map<String, List<String>> extras = Map.of("time", List.of("any"), "install-on", List.of("any"),
                "layer-name", List.of(e.packageLayer()), "last-modifier", List.of(e.principal()));
        String comment = "requested by " + e.principal() + " for " + e.cpA().hostname() + " (" + e.v6() + ")";
        var r1 = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 1, e.ruleOne(), true,
                cell(objectA), cell("any"), cell("any"), cell("any"), "Accept", "Log", comment, extras);
        var r2 = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2, e.ruleTwo(), true,
                cell(objectA), cell("any"), cell("any"), cell("any"), "Accept", "Log", "duplicate of " + e.ruleOne(), extras);
        var r3 = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 3, e.ruleThree(), true,
                cell(objectB), cell("any"), cell("any"), cell("any"), "Drop", "Log", "", extras);
        var r4 = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 4, e.ruleFour(), true,
                cell(objectNet), cell("any"), cell("any"), cell("any"), "Accept", "Log", "vendor access from " + e.transit(), extras)
                .withHitCounts(new PolicySnapshot.HitCounts(0L, null, null, "mds", Instant.now().toString(), "zero", List.of()));
        var metadata = new PolicySnapshot.Metadata(e.policyId(), e.mgmt().id(), e.mgmt().hostname(), "CP", e.domainId(),
                e.domainName(), e.policyName(), Instant.now().toString(), null,
                List.of(new PolicySnapshot.Target(e.cpA().id(), e.cpA().hostname(), "", "IN_SYNC"),
                        new PolicySnapshot.Target(e.cpB().id(), e.cpB().hostname(), "", "IN_SYNC")));
        var snapshot = new PolicySnapshot(metadata,
                List.of(new PolicySnapshot.Section("section-1", e.sectionName(), "CP access layer", null, List.of(r1, r2, r3, r4))),
                objects);
        audited(c, e.principal(), Ui2Rows.ACTION, () -> exec(c,
                "insert into policy_snapshot(policy_id, collected_at, metadata, snapshot) values (?, now(), ?::jsonb, ?::jsonb)",
                e.policyId(), JSON.writeValueAsString(metadata), JSON.writeValueAsString(snapshot)));
        ids.put("ruleOneId", r1.id());
        ids.put("objectAId", objectA);
    }

    /**
     * One stored offline analysis (V146): the aggregate shape {@code OfflineAnalysis} writes, with the uploader, a file
     * name that names a host, and a rule result carrying rule, section and object names and observed addresses.
     */
    private static void seedOfflineAnalysis(Connection c, Estate e) throws Exception {
        String analysis = "oa-" + UUID.randomUUID();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("dimension", "source");
        usage.put("objectId", ids.get("objectAId"));
        usage.put("name", e.objectAName());
        usage.put("parentId", null);
        usage.put("depth", 0);
        usage.put("state", "USED");
        usage.put("flowCount", 40L);
        usage.put("reason", "");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", ids.get("ruleOneId"));
        rule.put("name", e.ruleOne());
        rule.put("number", 1);
        rule.put("action", "Accept");
        rule.put("sectionId", "section-1");
        rule.put("sectionName", e.sectionName());
        rule.put("flowCount", 40L);
        rule.put("firstTime", "2026-11-01T00:00:00Z");
        rule.put("lastTime", "2026-11-20T00:00:00Z");
        rule.put("distinctSources", 1);
        rule.put("distinctDestinations", 1);
        rule.put("distinctServices", 1);
        rule.put("topObserved", List.of(Map.of("observedSource", e.objectAddress(), "count", 25L),
                Map.of("observedDestination", e.transit(), "count", 30L), Map.of("observedService", "tcp/443", "count", 40L)));
        rule.put("zeroTraffic", "");
        rule.put("severity", "LOW");
        rule.put("unseenMembers", List.of(Map.of("memberId", ids.get("objectAId"), "name", e.objectBName(), "dimension", "source")));
        rule.put("narrowing", Map.of("memberStatus", "RESOLVED", "observedSource", List.of(e.objectAddress() + "/32"),
                "observedDestination", List.of(e.transit() + "/32"), "observedService", List.of("tcp/443"),
                "coveragePercent", 100, "coveredFlows", 40L));
        rule.put("objectUsage", List.of(usage));
        Map<String, Object> aggregates = new LinkedHashMap<>();
        aggregates.put("mode", "ONE_RULE");
        aggregates.put("ruleId", ids.get("ruleOneId"));
        aggregates.put("otherRuleRows", 3L);
        aggregates.put("policyId", e.policyId());
        aggregates.put("collectedAt", Instant.now().toString());
        aggregates.put("rowCount", 120L);
        aggregates.put("invalidRows", 2L);
        aggregates.put("flowCount", 118L);
        aggregates.put("attributedFlows", 40L);
        aggregates.put("periodStart", "2026-11-01T00:00:00Z");
        aggregates.put("periodEnd", "2026-11-20T00:00:00Z");
        aggregates.put("parseStats", List.of(Map.of("statReason", "ok", "count", 118L)));
        aggregates.put("unknownStats", List.of(Map.of("statReason", "no deciding rule", "count", 3L)));
        aggregates.put("verdictStats", List.of(Map.of("statReason", "ALLOWED", "count", 40L)));
        aggregates.put("agreementCount", 0L);
        aggregates.put("disagreementCount", 0L);
        aggregates.put("comparisonUnknownCount", 0L);
        aggregates.put("comparisonEnabled", false);
        aggregates.put("rules", List.of(rule));
        audited(c, e.principal(), "policy_offline_write", () -> exec(c,
                "insert into policy_offline_analysis(analysis_id, policy_id, uploader, file_name, aggregates) "
                        + "values (?, ?, ?, ?, ?::jsonb)",
                analysis, e.policyId(), e.principal(), e.uploadName(), JSON.writeValueAsString(aggregates)));
        ids.put("analysisId", analysis);
    }

    /**
     * One path validation (V188): MATCH, MISMATCH and NOT_EVALUABLE hops with enum checks, and a pending hop whose
     * transient plan names zones, a virtual router, a rule and addresses (the read never returns a plan).
     */
    private static void seedPathValidation(Connection c, Estate e) throws Exception {
        String pending = Ui2Rows.insertJob(c, e.pan().id());
        String validation = "pv-" + UUID.randomUUID();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("destination", e.transit());
        plan.put("source", e.objectAddress());
        plan.put("virtualRouter", e.virtualRouter());
        plan.put("fromZone", e.zoneA());
        plan.put("toZone", e.zoneB());
        plan.put("rule", e.ruleOne());
        plan.put("serial", e.pan().serial());
        plan.put("hostname", e.pan().hostname());
        audited(c, e.principal(), Ui2Rows.ACTION, () -> {
            exec(c, "insert into path_validation(validation_id, submitted_by, topology_snapshot_id, path_verdict, hop_count, "
                    + "hops_not_validated) values (?, ?, ?, 'ALLOWED', 4, 1)", validation, e.principal(), "tps-" + UUID.randomUUID());
            hop(c, validation, 1, "check_point", e.cpA().id(), "MATCH",
                    "[{\"check\":\"CP_ROUTE\",\"result\":\"MATCH\",\"reason\":\"ROUTE_AGREES\"}]", new String[0]);
            hop(c, validation, 2, "palo_alto", e.pan().id(), "MISMATCH",
                    "[{\"check\":\"PAN_FIB\",\"result\":\"MISMATCH\",\"reason\":\"EGRESS_DIFFERS\"},"
                            + "{\"check\":\"PAN_SECURITY\",\"result\":\"MATCH\",\"reason\":\"RULE_AGREES\"}]",
                    new String[] {"EGRESS_DIFFERS"});
            hop(c, validation, 3, "other", null, "NOT_EVALUABLE", "[]", new String[] {"NO_MANAGED_DEVICE"});
            exec(c, "insert into path_validation_hop(validation_id, ordinal, branch_id, hop_index, node_id, vendor, device_id, "
                    + "job_id, plan, result, checks, reasons) values (?, 4, 'b1', 4, ?, 'palo_alto', ?, ?, ?::jsonb, 'PENDING', "
                    + "'[]'::jsonb, '{}'::text[])", validation, "tn-" + hex(10), e.pan().id(), pending, JSON.writeValueAsString(plan));
        });
        ids.put("validationId", validation);
    }

    private static void hop(Connection c, String validation, int ordinal, String vendor, String deviceId, String result,
            String checks, String[] reasons) throws SQLException {
        exec(c, "insert into path_validation_hop(validation_id, ordinal, branch_id, hop_index, node_id, vendor, device_id, result, "
                + "checks, reasons, evaluated_at) values (?, ?, 'b1', ?, ?, ?, ?, ?, ?::jsonb, ?, now())",
                validation, ordinal, ordinal, "tn-" + hex(10), vendor, deviceId, result, checks,
                c.createArrayOf("text", reasons));
    }

    /**
     * Two topology link overrides (V186): an active one whose two ends name raw contexts and interfaces on a Check Point
     * member and a Palo Alto firewall with an assumed subnet and a note naming hosts, and a revoked one.
     */
    private static void seedTopologyOverrides(Connection c, Estate e) throws Exception {
        audited(c, e.principal(), Ui2Rows.ACTION, () -> {
            exec(c, "insert into topology_link_override(override_id, action, link_kind, a_device_id, a_context, a_interface_name, "
                    + "b_device_id, b_context, b_interface_name, assumed_subnet, reason_code, note, created_by) "
                    + "values (?, 'FORCE_LINK', 'STATIC_L3', ?, ?, ?, ?, ?, ?, ?, 'UNMANAGED_INTERMEDIATE_DEVICE', ?, ?)",
                    "tlo-" + UUID.randomUUID(), e.cpA().id(), e.contextA(), e.ifaceA(), e.pan().id(), e.contextB(), e.ifaceB(),
                    e.subnet(), e.note(), e.principal());
            exec(c, "insert into topology_link_override(override_id, action, link_kind, a_device_id, a_context, a_interface_name, "
                    + "b_device_id, b_context, b_interface_name, reason_code, note, created_by, revoked_at, revoked_by) "
                    + "values (?, 'SUPPRESS_LINK', 'IPSEC_TUNNEL', ?, ?, ?, ?, ?, ?, 'TUNNEL', ?, ?, now(), ?)",
                    "tlo-" + UUID.randomUUID(), e.cpB().id(), e.contextA(), e.ifaceA(), e.pan().id(), e.contextB(), e.ifaceB(),
                    "removed by " + e.principal() + " for " + e.cpB().hostname(), e.principal(), e.principal());
        });
    }

    /**
     * System-log ledger rows of the first Check Point member (V160, V166, V167): a cursor whose stored file family names a
     * path with the host name, two runs, an HCP report file, and two Spike directories (a collected one with its archive
     * file and a skipped one) whose name class and member class carry a host name.
     */
    private static void seedSystemLogs(Connection c, Estate e) throws Exception {
        String job = Ui2Rows.opaqueId("job");
        String hcpFile = "slf-" + UUID.randomUUID();
        String spikeFile = "slf-" + UUID.randomUUID();
        String family = JSON.writeValueAsString(List.of(Map.of("name", "/var/log/messages-" + e.cpA().hostname(), "inode", "42",
                "size", 4096, "mtime", 1_700_000_000)));
        String members = JSON.writeValueAsString(Map.of("file:" + e.spikeDirectory(), Map.of("count", 3, "bytes", 900, "fetched", 3),
                "dir:logs", Map.of("count", 1, "bytes", 0, "fetched", 0)));
        audited(c, e.principal(), Ui2Rows.ACTION, () -> {
            for (String source : List.of("cp_messages", "cp_fwd_elg")) {
                exec(c, "insert into system_log_cursor(device_id, source_key, inode, offset_bytes, anchor_hmac, family_snapshot, "
                        + "device_epoch_at_read, fast_rotating, retention_horizon_seconds, updated_job_id) "
                        + "values (?, ?, '42', 4096, ?, ?::jsonb, 1700000000, false, 86400, ?)",
                        e.cpA().id(), source, "a".repeat(64), family, job);
                exec(c, "insert into system_log_run(run_id, device_id, job_id, source_key, outcome, bytes_read, bytes_stored, "
                        + "reads_issued, duration_ms, detail) values (?, ?, ?, ?, 'COLLECTED', 4096, 4096, 1, 5, ?::jsonb)",
                        UUID.randomUUID().toString(), e.cpA().id(), job, source,
                        JSON.writeValueAsString(Map.of("note", "read from " + e.cpA().hostname())));
            }
            for (String[] file : new String[][] {{hcpFile, "cp_hcp_report"}, {spikeFile, "cp_spike_data"}}) {
                exec(c, "insert into system_log_file(file_id, device_id, kind, job_id, remote_name_hmac, name_time, size, mtime, "
                        + "status, flags, artefact_ref, wrapped_data_key, content_hmac, ciphertext_bytes, expires_at) "
                        + "values (?, ?, ?, ?, ?, now() - interval '1 day', 2048, 1700000000, 'STORED', '{}'::text[], ?, ?, ?, "
                        + "2100, now() + interval '30 days')",
                        file[0], e.cpA().id(), file[1], job, hex(32), "art-" + e.cpA().hostname(), new byte[60], hex(32));
            }
            exec(c, "insert into system_log_spike_directory(directory_id, device_id, name_hmac, name_class, mtime, state, "
                    + "member_count, fetched_files, member_bytes, fetched_bytes, members, file_id, job_id) "
                    + "values (?, ?, ?, ?, 1700000000, 'COLLECTED', 4, 3, 900, 900, ?::jsonb, ?, ?)",
                    "spd-" + UUID.randomUUID(), e.cpA().id(), hex(32), e.spikeDirectory(), members, spikeFile, job);
            exec(c, "insert into system_log_spike_directory(directory_id, device_id, name_hmac, name_class, mtime, state, "
                    + "member_count, fetched_files, member_bytes, fetched_bytes, members, job_id) "
                    + "values (?, ?, ?, 'spike_YYYYMMDD', 1700000100, 'TOO_LARGE', 9, 0, 999999, 0, '{}'::jsonb, ?)",
                    "spd-" + UUID.randomUUID(), e.cpA().id(), hex(32), job);
        });
    }

    /** One sanitizer job and one revert job created by the administrator (V156): state and counts only, no file name. */
    private static void seedSanitizer(Connection c, Estate e) throws Exception {
        String job = UUID.randomUUID().toString();
        String revert = UUID.randomUUID().toString();
        audited(c, e.principal(), Ui2Rows.ACTION, () -> {
            exec(c, "insert into sanitize_job(job_id, created_by, state, file_type, input_bytes, output_bytes, counts, "
                    + "output_state, vault_entries, vault_expires_at, wrapped_data_key, key_id) values (?, ?, 'SUCCEEDED', 'csv', "
                    + "2048, 2048, '{\"address\":3}'::jsonb, 'READY', 3, now() + interval '30 days', ?, 'k1')",
                    job, e.principal(), new byte[60]);
            exec(c, "insert into sanitize_revert_job(revert_id, created_by, scope, source_job_id, state, file_type, input_bytes, "
                    + "output_bytes, counts, output_state) values (?, ?, 'ONE_JOB', ?, 'SUCCEEDED', 'csv', 2048, 2048, "
                    + "'{\"address\":3}'::jsonb, 'READY')", revert, e.principal(), job);
        });
        ids.put("sanitizeJobId", job);
        ids.put("sanitizeRevertId", revert);
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void audited(Connection c, String actor, String action, Work work) throws Exception {
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

    // ------------------------------------------------------------------ leak counting (copied from the neighbouring sweeps)

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

    /** Where a planted value survives: JSON field paths (array indexes dropped) with the value class, never the value. */
    static Set<String> locate(JsonNode node, String path, Map<String, ValueClass> raw) {
        Set<String> out = new TreeSet<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(f -> out.addAll(locate(f.getValue(), path + "." + f.getKey(), raw)));
        } else if (node.isArray()) {
            node.forEach(item -> out.addAll(locate(item, path + "[]", raw)));
        } else if (node.isValueNode()) {
            count(node.asText(), raw).forEach((cls, n) -> out.add(path + " " + cls));
        }
        return out;
    }

    // ------------------------------------------------------------------ HTTP (copied from the neighbouring sweeps)

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Per-request timeout: 30 s by default; the shared runner sets UI2_TEST_HTTP_TIMEOUT_S (30..600). */
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

    private static String login() throws Exception {
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
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("no session cookie on the aiview login");
    }

    // ------------------------------------------------------------------ the sweep

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode tree(Resp response) throws Exception {
        return JSON.readTree(response.body());
    }

    @Test
    void noPlantedRawValueSurvivesInInsightsOfflineTopologyAndSystemLogPlanes() throws Exception {
        String cookie = login();
        Estate e = estate;

        // The routes exist in the shared aiview enumeration under these templates (guards drift of the table above).
        Set<String> enumerated = new TreeSet<>();
        for (var ep : AiviewReachableRoutes.all()) {
            if (ep.method().equals("GET")) enumerated.add("GET " + ep.pathTemplate());
        }
        for (String route : COVERED) assertTrue(enumerated.contains(route), "route is enumerated: " + route);
        for (String route : REFUSED) assertTrue(enumerated.contains(route), "route is enumerated: " + route);

        String policy = "/api/v2/policy/policies/" + url(e.policyId());
        String device = "/api/v2/system-logs/" + url(e.cpA().id());
        Map<String, String> targets = new LinkedHashMap<>();
        Map<String, Resp> responses = new LinkedHashMap<>();

        targets.put(INSIGHTS, policy + "/insights");
        responses.put(INSIGHTS, get(targets.get(INSIGHTS), cookie));
        assertEquals(200, responses.get(INSIGHTS).status(), INSIGHTS);
        JsonNode insights = tree(responses.get(INSIGHTS));
        assertTrue(insights.path("supported").asBoolean(), "the package is a supported Check Point package");
        assertTrue(insights.path("total").asInt() > 0 && insights.path("insights").size() > 0, "insight list is non-empty");
        String insightId = insights.path("insights").get(0).path("insightId").asText();
        assertTrue(insightId.matches("[0-9a-f-]{36}"), "an insight id the detail route accepts");

        targets.put(INSIGHT, policy + "/insight?insightId=" + url(insightId));
        responses.put(INSIGHT, get(targets.get(INSIGHT), cookie));
        assertEquals(200, responses.get(INSIGHT).status(), INSIGHT);
        JsonNode detail = tree(responses.get(INSIGHT));
        assertFalse(detail.path("insight").isMissingNode() || detail.path("insight").isEmpty(), "insight detail is non-empty");
        assertTrue(detail.path("rules").size() > 0 && detail.path("ruleObjects").size() > 0, "insight detail names rules and objects");

        targets.put(OFFLINE_LIST, policy + "/offline-analyses");
        responses.put(OFFLINE_LIST, get(targets.get(OFFLINE_LIST), cookie));
        assertEquals(200, responses.get(OFFLINE_LIST).status(), OFFLINE_LIST);
        assertTrue(tree(responses.get(OFFLINE_LIST)).path("analyses").size() > 0, "offline analysis list is non-empty");

        targets.put(OFFLINE_ONE, "/api/v2/policy/offline-analyses/" + url(ids.get("analysisId")));
        responses.put(OFFLINE_ONE, get(targets.get(OFFLINE_ONE), cookie));
        assertEquals(200, responses.get(OFFLINE_ONE).status(), OFFLINE_ONE);
        JsonNode analysis = tree(responses.get(OFFLINE_ONE));
        assertTrue(analysis.path("rowCount").asLong() > 0 && analysis.path("rules").size() > 0, "offline analysis carries rule usage");

        targets.put(VALIDATION, "/api/v2/path-analysis/validate/" + url(ids.get("validationId")));
        responses.put(VALIDATION, get(targets.get(VALIDATION), cookie));
        assertEquals(200, responses.get(VALIDATION).status(), VALIDATION);
        assertEquals(4, tree(responses.get(VALIDATION)).path("hops").size(), "path validation hops are present");

        targets.put(OVERRIDES, "/api/v2/topology/overrides");
        responses.put(OVERRIDES, get(targets.get(OVERRIDES), cookie));
        assertEquals(200, responses.get(OVERRIDES).status(), OVERRIDES);
        assertEquals(2, tree(responses.get(OVERRIDES)).path("overrides").size(), "topology overrides are present");

        targets.put(SOURCES, device + "/sources");
        responses.put(SOURCES, get(targets.get(SOURCES), cookie));
        assertEquals(200, responses.get(SOURCES).status(), SOURCES);
        assertEquals(2, tree(responses.get(SOURCES)).path("sources").size(), "system-log sources are present");

        targets.put(HCP, device + "/hcp");
        responses.put(HCP, get(targets.get(HCP), cookie));
        assertEquals(200, responses.get(HCP).status(), HCP);
        assertEquals(1, tree(responses.get(HCP)).path("files").size(), "HCP report file is present");

        targets.put(SPIKE, device + "/spike-data");
        responses.put(SPIKE, get(targets.get(SPIKE), cookie));
        assertEquals(200, responses.get(SPIKE).status(), SPIKE);
        JsonNode spike = tree(responses.get(SPIKE));
        assertEquals(2, spike.path("directories").size(), "spike directories are present");
        assertTrue(spike.path("counts").size() > 0, "spike directory counts are present");

        targets.put(SANITIZE_JOBS, "/api/v2/sanitize/jobs");
        responses.put(SANITIZE_JOBS, get(targets.get(SANITIZE_JOBS), cookie));
        assertEquals(200, responses.get(SANITIZE_JOBS).status(), SANITIZE_JOBS);
        JsonNode permissions = tree(responses.get(SANITIZE_JOBS)).path("permissions");
        assertEquals(3, permissions.size(), "sanitizer permissions are listed");
        permissions.fields().forEachRemaining(p -> assertFalse(p.getValue().asBoolean(), "aiview holds no sanitizer permission: " + p.getKey()));

        targets.put(SANITIZE_JOB, "/api/v2/sanitize/jobs/" + url(ids.get("sanitizeJobId")));
        targets.put(SANITIZE_DOWNLOAD, "/api/v2/sanitize/jobs/" + url(ids.get("sanitizeJobId")) + "/download");
        targets.put(SANITIZE_REVERT, "/api/v2/sanitize/reverts/" + url(ids.get("sanitizeRevertId")));
        for (String refused : REFUSED) {
            responses.put(refused, get(targets.get(refused), cookie));
            assertEquals(403, responses.get(refused).status(), refused + " must refuse aiview");
        }

        Map<String, Map<ValueClass, Integer>> leaks = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int total = 0;
        for (var entry : responses.entrySet()) {
            Map<ValueClass, Integer> found = count(entry.getValue().body(), e.raw());
            if (!found.isEmpty()) leaks.put(entry.getKey(), found);
            total += found.values().stream().mapToInt(Integer::intValue).sum();
            report.add(String.format(Locale.ROOT, "AIVIEW_INSIGHTS_OFFLINE %3d %-62s raw=%s", entry.getValue().status(),
                    entry.getKey(), found.isEmpty() ? "0" : found.toString()));
            if (!found.isEmpty()) {
                locate(tree(entry.getValue()), "$", e.raw()).forEach(where ->
                        report.add("AIVIEW_INSIGHTS_OFFLINE_FIELD " + entry.getKey() + " " + where));
            }
        }
        report.forEach(System.out::println);
        System.out.println("AIVIEW_INSIGHTS_OFFLINE_SUMMARY routes=" + responses.size() + " planted_values=" + e.raw().size()
                + " surviving_raw_occurrences=" + total);

        assertEquals(KNOWN_LEAKS, leaks,
                "planted raw value counts surviving in aiview GET bodies per (route, value class); KNOWN_LEAKS must match exactly");
    }

    /** The counter itself: an address is not matched as the prefix of a longer address, names match case-insensitively. */
    @Test
    void theCounterSeesSpellingsAndNoPrefixMatch() {
        Map<String, ValueClass> raw = Map.of("192.0.2.15", ValueClass.ADDRESS, "2001:db8:ab12::5", ValueClass.ADDRESS,
                "zn-x0a0b0c-core", ValueClass.CONTEXT_OR_INTERFACE);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("2001:db8:ab12:0:0:0:0:5 and 2001:DB8:AB12::5 but not 2001:db8:ab12::55", raw));
        assertEquals(Map.of(ValueClass.CONTEXT_OR_INTERFACE, 1), count("zone ZN-X0A0B0C-CORE", raw));
        assertEquals(Map.of(), count("{\"zone\":\"zone-04\"}", raw));
    }
}
