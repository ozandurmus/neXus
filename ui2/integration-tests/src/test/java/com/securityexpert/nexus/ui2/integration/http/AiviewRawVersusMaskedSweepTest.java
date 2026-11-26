package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import com.securityexpert.nexus.ui2.jobs.failover.AsaFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.jobs.failover.FgtFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneBlock;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent;

/**
 * The raw-versus-masked sweep of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &
 * post-parsing masking law"): a small synthetic estate is seeded into a real PostgreSQL 16 database, the application is
 * booted on it, the aiview persona ({@code role:replay_viewer}) logs in and GETs every route the static audit
 * ({@code AiviewMaskingLeakAuditTest}) calls aiview-reachable -- both enumerate through the one shared
 * {@link AiviewReachableRoutes} -- plus every ungated GET, and every seeded raw value is counted in every response body.
 *
 * <p>Seeded values are synthetic and built at runtime: hostnames in the FW-TANGO style plus look-alike real-shaped
 * names, addresses in 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 and 2001:db8::/32, random serials, an administrator principal,
 * rule, object and policy names. The P1 planes are seeded too: a Zone Matrix ACTIVE and DRAFT version (raw zone names,
 * IPv4 and IPv6 blocks, cell rationale and regulation reference, the administrator as author and publisher), an ACI
 * cutover segment with one assessment and its flow rows (raw segment name, blocks, flow addresses, the seeded rule), and a
 * Cisco ASA failover pair (raw cluster reference, unit serials, stored running configurations in the test store); the
 * seeded static-route preview uses a UUID {@code preview_id} (matching {@code StaticRoutePreviewService#submitPreview})
 * so both {@code GET /api/v2/operations/static-route/preview} and {@code GET /api/v2/operations/static-route/preview/*}
 * answer 200 with the seeded preview row.
 * Output names the route, the HTTP status and the value <em>class</em> with a count --
 * never a value. A surviving raw value fails the test unless the route and class are on {@link #KNOWN_LEAKS}, which
 * must match the findings exactly (an entry that stops leaking fails too, so the list only shrinks on purpose).</p>
 *
 * <p>Round 3 (2026-11-25): Zone Matrix evaluation history -- two COMPLETE runs of the seeded policy (the latest on the
 * ACTIVE version with a VIOLATION, an UNKNOWN and a NOT_EVALUABLE finding carrying raw rule and section names, an
 * inline parent path, two violated cells with witness ranges and a NAT view; the earlier with one finding resolved by a
 * version change) -- and failover run history: one finished run per vendor family (Check Point and Palo Alto: a
 * failover run with its approval; Cisco ASA and FortiGate: a readiness run, their readiness switches switched on in this
 * test's application only, whereas {@code AiviewFailoverIncidentsSweepTest} leaves both switches at their default OFF)
 * on pairs that carry raw cluster references, hostnames, serials, addresses and the
 * administrator. Not covered: failover approvals and runs of VSX virtual systems (units need real VSX inventory
 * contexts), the in-flight run states, dispatch intents and check results.</p>
 *
 * <p>The application's artefact store is configured from environment variables only; this test replaces the
 * {@code artefactStoreAccess} bean with a store on a temporary directory so the masked system-log text route reads a
 * real encrypted line (the logmask leak of 2026-10-18 was in exactly that route).</p>
 */
class AiviewRawVersusMaskedSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a seeded value is; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, CLUSTER_NAME, ADDRESS, SERIAL, PRINCIPAL, RULE_NAME, OBJECT_NAME, POLICY_NAME, ZONE_OR_SEGMENT_NAME,
        /** Round 2 (2026-11-25): a whole script name, credential reference, script run output line, case title, file name. */
        SCRIPT_NAME, CREDENTIAL_ALIAS, SCRIPT_OUTPUT, CASE_TITLE, FILE_NAME
    }

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
            Map.entry("GET /api/v2/policy/devices", Set.of("policy", "asa-unit")),
            Map.entry("GET /api/v2/system-logs/*/chunks", Set.of("log-chunk")),
            Map.entry("GET /api/v2/system-logs/chunks/*/text", Set.of("log-line")),
            Map.entry("GET /api/v2/operations/static-route/preview", Set.of("device", "static-route-preview")),
            Map.entry("GET /api/v2/operations/static-route/preview/*", Set.of("device", "static-route-preview")),
            Map.entry("GET /api/v2/zone-matrix/versions", Set.of("zone-matrix-version")),
            Map.entry("GET /api/v2/zone-matrix/versions/*", Set.of("zone-matrix-version", "zone")),
            Map.entry("GET /api/v2/zone-matrix/draft", Set.of("zone-matrix-version")),
            Map.entry("GET /api/v2/zone-matrix/policies", Set.of("policy")),
            Map.entry("GET /api/v2/cutover/segments", Set.of("cutover-segment")),
            Map.entry("GET /api/v2/cutover/segments/*", Set.of("cutover-segment", "cutover-assessment")),
            Map.entry("GET /api/v2/cutover/assessments/*", Set.of("cutover-segment", "cutover-assessment")),
            Map.entry("GET /api/v2/cutover/assessments/*/flows", Set.of("cutover-assessment", "cutover-flow")),
            Map.entry("GET /api/v2/policy/ha-pairs", Set.of("asa-unit")),
            Map.entry("GET /api/v2/jobs/export.csv", Set.of("failed-job")),
            Map.entry("GET /api/v2/policy/changes.csv", Set.of("device")),
            Map.entry("GET /api/v2/policy/policies/*/hygiene", Set.of("hygiene-finding")),
            Map.entry("GET /api/v2/policy/policies/*/hygiene.csv", Set.of("hygiene-finding")),
            // round 2 (2026-11-25)
            Map.entry("GET /api/v2/scripts", Set.of("script")),
            Map.entry("GET /api/v2/scripts/*", Set.of("script")),
            Map.entry("GET /api/v2/scripts/*/runs", Set.of("script", "script-run")),
            Map.entry("GET /api/v2/scripts/runs/*", Set.of("script", "script-run")),
            Map.entry("GET /api/v2/log-findings", Set.of("log-finding", "device", "log-chunk")),
            Map.entry("GET /api/v2/log-findings/*", Set.of("log-finding", "device", "log-chunk")),
            Map.entry("GET /api/v2/log-findings/chips", Set.of("device")),
            Map.entry("GET /api/v2/log-findings/coverage", Set.of("device", "log-window")),
            Map.entry("GET /api/v2/packet-analysis/cases", Set.of("pa-case", "pa-run")),
            Map.entry("GET /api/v2/packet-analysis/cases/*", Set.of("pa-case", "pa-capture", "pa-run")),
            Map.entry("GET /api/v2/packet-analysis/runs/*", Set.of("pa-case", "pa-run")),
            Map.entry("GET /api/v2/packet-analysis/runs/*/report", Set.of("pa-case", "pa-run")),
            // round 3 (2026-11-25)
            Map.entry("GET /api/v2/zone-matrix/summary", Set.of("zone-matrix-version", "policy", "zone-matrix-run",
                    "zone-matrix-finding", "zone-matrix-resolved")),
            Map.entry("GET /api/v2/cp-failover/runs", Set.of("failover-run-check_point")),
            Map.entry("GET /api/v2/cp-failover/runs/*", Set.of("failover-run-check_point")),
            Map.entry("GET /api/v2/cp-failover/approvals", Set.of("failover-approval-check_point")),
            Map.entry("GET /api/v2/pan-failover/runs", Set.of("failover-run-palo_alto")),
            Map.entry("GET /api/v2/pan-failover/runs/*", Set.of("failover-run-palo_alto")),
            Map.entry("GET /api/v2/pan-failover/approvals", Set.of("failover-approval-palo_alto")),
            Map.entry("GET /api/v2/asa-failover/runs", Set.of("failover-run-cisco_asa")),
            Map.entry("GET /api/v2/asa-failover/runs/*", Set.of("failover-run-cisco_asa")),
            Map.entry("GET /api/v2/pan-failover/units/*", Set.of("ha-unit")),
            Map.entry("GET /api/v2/fgt-failover/units/*", Set.of("ha-unit")),
            Map.entry("GET /api/v2/fgt-failover/runs", Set.of("failover-run-fortinet")),
            Map.entry("GET /api/v2/fgt-failover/runs/*", Set.of("failover-run-fortinet")));

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
        // Round 3: Cisco ASA and FortiGate failover readiness are off unless an environment variable says on; the runs routes
        // of those two vendors answer an empty list while off, so this test's application runs with both on (readiness only:
        // no execution path depends on either switch).
        registry.removeBeanDefinition("asaFailoverReadinessSwitch");
        beanFactory.registerSingleton("asaFailoverReadinessSwitch", new AsaFailoverReadinessSwitch(true));
        registry.removeBeanDefinition("fgtFailoverReadinessSwitch");
        beanFactory.registerSingleton("fgtFailoverReadinessSwitch", new FgtFailoverReadinessSwitch(true));
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

    /** One unit of the Cisco ASA failover pair: an enrolled gateway with its own management address and serial. */
    record AsaUnit(String id, String hostname, String serial, String managementAddress, String haRole) {
    }

    /**
     * The P1 planes of the estate: a zone model (Zone Matrix), an ACI cutover segment with one assessment, and a Cisco
     * ASA failover pair with stored running configurations. Blocks are written as CIDR text; the network address of
     * each block is what the sweep counts (it occurs in every spelling of the block).
     */
    record P1(String zoneNameA, String zoneNameB, String zoneDescription, String zoneV4A, String zoneV4B, String zoneV6A,
            String zoneV6B, String cellRationale, String regulationReference, String segmentName, String segmentV4,
            String segmentV6, String peerV4, String flowSource, String flowDestination, String missingRuleSource,
            String flowFirstSeen, String asaCluster, AsaUnit asaA, AsaUnit asaB, String asaNameif, String asaInterfaceAddress,
            String asaObjectName, String asaObjectAddress, String asaAclName, String disabledRuleName) {

        static P1 generate(String principal, String hostname, int base, Map<String, ValueClass> raw) {
            int zoneA = 16 + 4 * RANDOM.nextInt(8);    // 203.0.113.16 .. .44, /30
            int zoneB = 64 + 4 * RANDOM.nextInt(8);    // 203.0.113.64 .. .92, /30
            int segment = 128 + 8 * RANDOM.nextInt(7); // 203.0.113.128 .. .176, /29
            int peer = 192 + 8 * RANDOM.nextInt(7);    // 203.0.113.192 .. .240, /29
            String v6a = "2001:db8:" + hex16() + ":" + hex16() + "::";
            String v6b = "2001:db8:" + hex16() + ":" + hex16() + "::";
            String v6segment = "2001:db8:" + hex16() + "::";
            AsaUnit a = new AsaUnit(Ui2Rows.opaqueId("dev"), "asa" + tag() + "-edge-a", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 4), "active");
            AsaUnit b = new AsaUnit(Ui2Rows.opaqueId("dev"), "asa" + tag() + "-edge-b", "JAD" + hex(4).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 5), "standby");
            P1 p = new P1("zn-" + tag() + "-cardholder", "zn-" + tag() + "-corebank",
                    "kept by " + principal + " on " + hostname,
                    "203.0.113." + zoneA, "203.0.113." + zoneB, v6a, v6b,
                    "requested by " + principal + " for " + hostname, "REG-" + tag() + " signed by " + principal,
                    "seg-" + tag() + "-swift", "203.0.113." + segment, v6segment, "203.0.113." + peer,
                    "203.0.113." + (segment + 2), "203.0.113." + (peer + 3), "203.0.113." + (segment + 5),
                    String.format(Locale.ROOT, "2026-11-19T%02d:%02d:%02dZ", RANDOM.nextInt(24), RANDOM.nextInt(60),
                            1 + RANDOM.nextInt(59)),
                    "asa-ha-" + tag() + "-dc", a, b, "nif" + tag() + "-outside", "198.51.100." + (base + 7),
                    "obj-" + tag() + "-paysrv", "198.51.100." + (base + 8), "ACL-" + tag() + "-EXT",
                    "deny-" + tag() + "-legacy");
            raw.put(p.zoneNameA(), ValueClass.ZONE_OR_SEGMENT_NAME);
            raw.put(p.zoneNameB(), ValueClass.ZONE_OR_SEGMENT_NAME);
            raw.put(p.segmentName(), ValueClass.ZONE_OR_SEGMENT_NAME);
            for (String address : List.of(p.zoneV4A(), p.zoneV4B(), p.zoneV6A(), p.zoneV6B(), p.segmentV4(), p.segmentV6(),
                    p.peerV4(), p.flowSource(), p.flowDestination(), p.missingRuleSource(), a.managementAddress(),
                    b.managementAddress(), p.asaInterfaceAddress(), p.asaObjectAddress())) {
                raw.put(address, ValueClass.ADDRESS);
            }
            raw.put(p.asaCluster(), ValueClass.CLUSTER_NAME);
            for (AsaUnit u : List.of(a, b)) {
                raw.put(u.hostname(), ValueClass.HOSTNAME);
                raw.put(u.serial(), ValueClass.SERIAL);
            }
            raw.put(p.asaNameif(), ValueClass.OBJECT_NAME);
            raw.put(p.asaObjectName(), ValueClass.OBJECT_NAME);
            raw.put(p.asaAclName(), ValueClass.RULE_NAME);
            raw.put(p.disabledRuleName(), ValueClass.RULE_NAME);
            return p;
        }

        List<AsaUnit> asaUnits() {
            return List.of(asaA, asaB);
        }
    }

    /**
     * Round 2 of the sweep (2026-11-25): the planes the first round read empty. A Script Execution script (raw name, egress
     * host, credential reference, the administrator as author) with one finished run (raw output tail and failure
     * reason, encrypted output in the test store); log-detection findings whose key token is a raw serial or address and
     * whose pointer is the seeded log line; a Packet Analysis case (raw title), capture (raw file name) and a completed
     * run whose stored summary reasons name raw addresses, hostnames, a serial and the administrator.
     */
    record R2(String scriptName, String egressHost, String credentialRef, String runOutput, String runFailure,
            String caseTitle, String captureName) {

        static R2 generate(String principal, Device pan, Device peer, Map<String, ValueClass> raw) {
            R2 r = new R2("chk-" + tag() + "-" + pan.hostname(), "relay" + tag() + "-syslog01", "cred-" + tag() + "-" + principal,
                    "connected to " + peer.hostname() + " (" + peer.managementAddress() + ") serial " + peer.serial()
                            + " as " + principal,
                    "exit 2: " + pan.hostname() + " at " + pan.managementAddress() + " refused " + principal,
                    "slow login " + pan.hostname() + " for " + principal,
                    "cap-" + peer.managementAddress() + "-" + peer.hostname() + ".pcapng");
            raw.put(r.scriptName(), ValueClass.SCRIPT_NAME);
            raw.put(r.egressHost(), ValueClass.HOSTNAME);
            raw.put(r.credentialRef(), ValueClass.CREDENTIAL_ALIAS);
            raw.put(r.runOutput(), ValueClass.SCRIPT_OUTPUT);
            raw.put(r.runFailure(), ValueClass.SCRIPT_OUTPUT);
            raw.put(r.caseTitle(), ValueClass.CASE_TITLE);
            raw.put(r.captureName(), ValueClass.FILE_NAME);
            return r;
        }
    }

    /**
     * Round 3 of the sweep (2026-11-25): the Zone Matrix finding rows (rule and section names beyond the seeded policy's own)
     * and two more failover pairs, a Palo Alto active/passive pair and a FortiGate pair, so every failover vendor family
     * has an enrolled pair of its own (Check Point and Cisco ASA pairs are seeded above).
     */
    record R3(String zmNotEvaluableRule, String zmResolvedRule, String zmNotEvaluableSection, String panCluster,
            AsaUnit panA, AsaUnit panB, String fgtCluster, AsaUnit fgtA, AsaUnit fgtB) {

        static R3 generate(int base, Map<String, ValueClass> raw) {
            R3 r = new R3("zmr-" + tag() + "-legacy", "zmr-" + tag() + "-retired", "zms-" + tag() + "-batch",
                    "pan-ha-" + tag() + "-dc",
                    new AsaUnit(Ui2Rows.opaqueId("dev"), "pan" + tag() + "-edge-a", "0" + digits(11), "192.0.2." + (base + 10), "active"),
                    new AsaUnit(Ui2Rows.opaqueId("dev"), "pan" + tag() + "-edge-b", "0" + digits(11), "192.0.2." + (base + 11), "passive"),
                    "fgt-ha-" + tag() + "-dc",
                    new AsaUnit(Ui2Rows.opaqueId("dev"), "fgt" + tag() + "-edge-a", "FGT" + hex(4).toUpperCase(Locale.ROOT), "192.0.2." + (base + 12), "primary"),
                    new AsaUnit(Ui2Rows.opaqueId("dev"), "fgt" + tag() + "-edge-b", "FGT" + hex(4).toUpperCase(Locale.ROOT), "192.0.2." + (base + 13), "secondary"));
            raw.put(r.zmNotEvaluableRule(), ValueClass.RULE_NAME);
            raw.put(r.zmResolvedRule(), ValueClass.RULE_NAME);
            raw.put(r.zmNotEvaluableSection(), ValueClass.RULE_NAME);
            raw.put(r.panCluster(), ValueClass.CLUSTER_NAME);
            raw.put(r.fgtCluster(), ValueClass.CLUSTER_NAME);
            for (AsaUnit u : r.units()) {
                raw.put(u.hostname(), ValueClass.HOSTNAME);
                raw.put(u.serial(), ValueClass.SERIAL);
                raw.put(u.managementAddress(), ValueClass.ADDRESS);
            }
            return r;
        }

        List<AsaUnit> units() {
            return List.of(panA, panB, fgtA, fgtB);
        }
    }

    private static String hex16() {
        return Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff));
    }

    /** Every raw value the sweep looks for, generated per run. */
    record Estate(Device cpMemberA, Device cpMemberB, Device panFirewall, Device management, String cluster,
            String ipv6Management, String principal, String ruleName, String sectionName, String objectName,
            String objectAddress, String policyName, String policyId, String domainId, String objectId, String ruleId,
            P1 p1, R2 r2, R3 r3, Map<String, ValueClass> raw) {

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
            P1 p1 = P1.generate(principal, pan.hostname(), base, raw);
            R2 r2 = R2.generate(principal, pan, b, raw);
            R3 r3 = R3.generate(base, raw);
            return new Estate(a, b, pan, mgmt, cluster, v6, principal, ruleName, sectionName, objectName, objectAddress,
                    policyName, "policy-" + UUID.randomUUID(), "domain-" + UUID.randomUUID(),
                    "obj-" + UUID.randomUUID(), "rule-" + UUID.randomUUID(), p1, r2, r3, Map.copyOf(raw));
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
                ids.put("logJob", logJob);
                ids.put("logLineBytes", Long.toString(plain.length));

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
                // A disabled second rule: a hygiene finding ("disabled") for the hygiene views and their CSV export.
                var disabled = new PolicySnapshot.Rule("rule-" + UUID.randomUUID(), UUID.randomUUID().toString(), 2,
                        e.p1().disabledRuleName(), false, new PolicySnapshot.Cell(List.of(e.objectId()), false),
                        new PolicySnapshot.Cell(List.of(), false), new PolicySnapshot.Cell(List.of(), false),
                        new PolicySnapshot.Cell(List.of(), false), "Drop", "None", "disabled by " + e.principal(),
                        Map.of("last-modifier", List.of(e.principal())));
                var snapshot = new PolicySnapshot(metadata,
                        List.of(new PolicySnapshot.Section("section-1", e.sectionName(), "Network", null, List.of(rule, disabled))),
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
                String preview = UUID.randomUUID().toString();
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
            seedZoneMatrix(c, e);
            seedCutover(c, e);
            seedAsaPair(c, e, credential);
            seedScripts(c, e);
            seedLogFindings(c, e);
            seedPacketAnalysis(c, e);
            seedZoneMatrixRuns(c, e);
            seedFailoverHistory(c, e, credential);
        }
    }

    /**
     * Script Execution (V251/V258/V266): one script whose name, egress host and credential reference carry raw values,
     * its version 1 (body encrypted in the test store, the administrator as author), and one finished, failed manual run
     * with an encrypted output artefact, a raw inline output tail and a raw failure reason.
     */
    private static void seedScripts(Connection c, Estate e) throws Exception {
        R2 r = e.r2();
        String scriptId = UUID.randomUUID().toString();
        String jobId = Ui2Rows.opaqueId("job");
        String[] egress = {e.panFirewall().managementAddress() + ":22", r.egressHost() + ":514"};
        String[] targets = {e.panFirewall().id()};
        String[] refs = {r.credentialRef()};
        byte[] body = ("#!/bin/sh\nssh " + e.principal() + "@" + e.panFirewall().managementAddress() + " # "
                + e.panFirewall().hostname() + "\n").getBytes(StandardCharsets.UTF_8);
        var bodyHandle = store.open(scriptId, scriptId + "@v1", ArtefactClass.SCRIPT_BODY, false);
        bodyHandle.sink().write(body);
        var bodyMeta = bodyHandle.finish();
        byte[] output = (r.runOutput() + "\n").getBytes(StandardCharsets.UTF_8);
        var outHandle = store.open(scriptId, jobId, ArtefactClass.SCRIPT_OUTPUT, false);
        outHandle.sink().write(output);
        var outMeta = outHandle.finish();
        audited(c, () -> {
            exec(c, "insert into credential_references(credential_reference_id, purpose, backend_pointer) "
                    + "values (?, 'harness', 'vault://harness/opaque')", r.credentialRef());
            exec(c, "insert into script(script_id, name, kind, current_version, enabled, timeout_s, egress, "
                    + "egress_device_targets, credential_refs, notify, run_as, created_by_actor_fingerprint) "
                    + "values (?, ?, 'sh', 1, false, 300, ?, ?, ?, 'none', 'role:operator', ?)",
                    scriptId, r.scriptName(), c.createArrayOf("text", egress), c.createArrayOf("text", targets),
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
                    + "egress_device_targets, credential_refs_used, failure_reason) values (?, ?, 1, 'COMPLETED', 'FAILURE', "
                    + "'MANUAL', 'role:operator', ?, now() - interval '1 minute', now(), 60000, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    jobId, scriptId, e.principal(), output.length, outMeta.ref().value(), outMeta.keyId(),
                    outMeta.wrappedDataKey(), outMeta.plaintextSha256(), outMeta.plaintextBytes(), r.runOutput(),
                    c.createArrayOf("text", egress), c.createArrayOf("text", targets), c.createArrayOf("text", refs),
                    r.runFailure());
        });
        ids.put("scriptId", scriptId);
        ids.put("scriptRun", jobId);
    }

    /**
     * Log detection (V272): two open ATTENTION findings on the first Check Point member, one whose key token is the
     * peer's raw serial and one whose key token is the peer's raw management address, both pointing at the seeded log
     * line; the member's coverage window; and an acknowledgement-style action whose note names the administrator.
     */
    private static void seedLogFindings(Connection c, Estate e) throws Exception {
        String pointers = JSON.writeValueAsString(List.of(Map.of("chunk_id", ids.get("chunkId"), "offset", 0,
                "length", Long.parseLong(ids.get("logLineBytes")))));
        List<String> findings = new ArrayList<>();
        audited(c, () -> {
            String[][] rows = {{"cp.sync_peer_lost", e.cpMemberB().serial()},
                    {"cp.sync_peer_addr", e.cpMemberB().managementAddress()}};
            for (String[] row : rows) {
                String finding = hex(16);
                findings.add(finding);
                exec(c, "insert into log_finding(finding_id, device_id, source_key, layer, rule_id, rule_version, kind, "
                        + "marker_id, key_token, window_day, window_start, window_end, severity, severity_rule, "
                        + "severity_weight, provenance, event_count, peak_bucket_count, incident_group, coverage_state, "
                        + "pointers, masking_rule_set, marker_list_version, config_version, review_by) values (?, ?, "
                        + "'cp_messages', 'A', 'cp.sync.peer', 'v1', 'MARKER', ?, ?, current_date, "
                        + "current_date + time '10:00', current_date + time '10:10', 'ATTENTION', 'MARKER_ATTENTION', 0.5, "
                        + "'PROVISIONAL', 3, 2, ?, 'EVALUATED', ?::jsonb, 'r1', 'm1', 'c1', ?)",
                        finding, e.cpMemberA().id(), row[0], row[1], hex(8), pointers, e.principal());
            }
            exec(c, "insert into log_coverage(device_id, source_key, job_id, window_day, state, reason, history_days) "
                    + "values (?, 'cp_messages', ?, current_date, 'EVALUATED', 'ALL_EVALUATED', 7)",
                    e.cpMemberA().id(), ids.get("logJob"));
            exec(c, "insert into log_finding_action(action_id, finding_id, action, actor, note) values (?, ?, 'REOPEN', ?, ?)",
                    hex(16), findings.get(0), e.principal(), "seen on " + e.cpMemberB().hostname() + " by " + e.principal());
        });
        ids.put("logFinding", findings.get(0));
    }

    /**
     * Packet Analysis (V154/V158): one ANALYZED case created by the administrator (raw title), one capture (raw uploaded
     * file name), and one completed run whose stored summary (the engine's shape) names raw addresses, hostnames, a
     * serial and the administrator in its reason texts. The case DuckDB is not created: the detail views that read it
     * (state, flows, charts, ladder ...) need a real engine run and answer 409 here.
     */
    private static void seedPacketAnalysis(Connection c, Estate e) throws Exception {
        R2 r = e.r2();
        String caseId = UUID.randomUUID().toString();
        String captureId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        // The engine's own summary shape (RealPacketAnalysisEngine): a constant verdict, counts, and engine reason text
        // per stage; the raw values sit in the free-text reasons, the only place a run summary can carry text.
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", "loss");
        stage.put("verdict", "degraded");
        stage.put("reason", "retransmissions towards " + e.cpMemberB().interfaceAddress() + " on "
                + e.cpMemberB().hostname() + " serial " + e.cpMemberB().serial());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", "Inconclusive");
        summary.put("reason", "no answer from " + e.panFirewall().hostname() + " at " + e.panFirewall().managementAddress()
                + " for " + e.principal());
        summary.put("stages", List.of(stage));
        summary.put("capture_count", 1);
        summary.put("total_bytes", 2048);
        summary.put("finding_count", 0);
        summary.put("flow_count", 3);
        summary.put("schema_version", 11);
        audited(c, () -> {
            exec(c, "insert into packet_analysis_case(case_id, title, created_by, created_at, expires_at, state, total_bytes) "
                    + "values (?, ?, ?, now(), now() + interval '7 days', 'ANALYZED', 2048)",
                    caseId, r.caseTitle(), e.principal());
            exec(c, "insert into packet_analysis_capture(capture_id, case_id, display_name, capture_format, size_bytes, "
                    + "sha256, storage_key, uploaded_by) values (?, ?, ?, 'pcapng', 2048, ?, ?, ?)",
                    captureId, caseId, r.captureName(), "5".repeat(64), UUID.randomUUID() + ".pcapng", e.principal());
            exec(c, "insert into jobs(job_id, job_type, capability_id, target_ref, target_kind, "
                    + "submitted_by_actor_fingerprint, idempotency_key, action_class, state, finished_at) "
                    + "values (?, 'packet_analysis_run', 'packet_analysis_run', ?, 'packet_analysis_case', ?, ?, 'read', "
                    + "'COMPLETED', now())",
                    jobId, caseId, Ui2Rows.ACTOR, "packet-analysis:" + jobId);
            exec(c, "insert into packet_analysis_run(job_id, case_id, state, requested_by, started_at, finished_at, "
                    + "engine_version, masked_summary) values (?, ?, 'COMPLETED', ?, now() - interval '1 minute', now(), "
                    + "'synthetic-1', ?::jsonb)",
                    jobId, caseId, e.principal(), JSON.writeValueAsString(summary));
            exec(c, "insert into packet_analysis_ledger(ledger_id, case_id, capture_id, event, actor_fingerprint, bytes) "
                    + "values (?, ?, ?, 'CAPTURE_UPLOADED', ?, 2048)", UUID.randomUUID().toString(), caseId, captureId,
                    e.principal());
        });
        ids.put("paCase", caseId);
        ids.put("paCapture", captureId);
        ids.put("paRun", jobId);
    }

    /**
     * Zone Matrix evaluation history (V267, round 3): two COMPLETE runs of the seeded policy on the ACTIVE version. The
     * earlier run (two days ago) holds one VIOLATION finding that the latest run resolved with
     * {@code MATRIX_VERSION_CHANGED}; the latest run holds a VIOLATION (the seeded rule and section, an inline parent path
     * of two rule ids, two violated cells whose zone ids are the seeded zones, witness ranges, a NAT view and the two
     * Check Point members as targets), an UNKNOWN (the seeded disabled rule) and a NOT_EVALUABLE finding (a rule and
     * section of their own). Findings are inserted while their run is RUNNING and the run is then completed, as the
     * engine does (V267 guard triggers).
     */
    private static void seedZoneMatrixRuns(Connection c, Estate e) throws Exception {
        P1 p = e.p1();
        R3 r = e.r3();
        String versionId = ids.get("zoneMatrixActive");
        String sha = ids.get("zoneMatrixActiveSha");
        String zoneA = ids.get("zoneA");
        String zoneB = ids.get("zoneB");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant earlierStart = now.minus(Duration.ofDays(2));
        Instant earlierEnd = earlierStart.plusSeconds(30);
        Instant latestStart = now.minusSeconds(120);
        Instant latestEnd = now.minusSeconds(90);
        String earlierRun = UUID.randomUUID().toString();
        String latestRun = UUID.randomUUID().toString();
        String violationUid = "uid-" + UUID.randomUUID();
        String resolvedUid = "uid-" + UUID.randomUUID();
        List<String> parentPath = List.of("rule-" + UUID.randomUUID(), "rule-" + UUID.randomUUID());
        List<String> targets = List.of(e.cpMemberA().id(), e.cpMemberB().id());
        List<Map<String, Object>> cells = List.of(
                zoneCell(zoneB, zoneA, "HIGH", p.zoneV4B() + "/30", p.zoneV4A() + "/30", p.zoneV4A()),
                zoneCell(zoneA, zoneB, "CRITICAL", p.zoneV4A() + "/30", p.zoneV4B() + "/30", p.zoneV4B()));
        List<String> pairs = List.of(zoneB + "->" + zoneA, zoneA + "->" + zoneB);
        audited(c, () -> {
            zoneRunStart(c, earlierRun, versionId, sha, e.policyId(), earlierStart);
            zoneFinding(c, earlierRun, e.policyId(), resolvedUid, List.of(), List.of(zoneB + "->" + zoneA), e.ruleId(), 4,
                    r.zmResolvedRule(), "section-1", e.sectionName(), targets, "VIOLATION", null, "HIGH", List.of(),
                    List.of(cells.get(0)), List.of(), earlierStart, latestEnd, "MATRIX_VERSION_CHANGED");
            zoneRunComplete(c, earlierRun, earlierEnd, 1, 0, 0, 0, 1);
            zoneRunStart(c, latestRun, versionId, sha, e.policyId(), latestStart);
            zoneFinding(c, latestRun, e.policyId(), violationUid, parentPath, pairs, e.ruleId(), 1, e.ruleName(),
                    "section-1", e.sectionName(), targets, "VIOLATION", null, "CRITICAL", List.of("INLINE_LAYER_COVER"),
                    cells, List.of(), latestStart, null, null);
            zoneFinding(c, latestRun, e.policyId(), "uid-" + UUID.randomUUID(), List.of(), List.of(), "rule-" + UUID.randomUUID(), 2,
                    p.disabledRuleName(), "section-1", e.sectionName(), targets, "UNKNOWN", "action semantics not modelled",
                    null, List.of(), List.of(), List.of("service"), latestStart, null, null);
            zoneFinding(c, latestRun, e.policyId(), "uid-" + UUID.randomUUID(), List.of(), List.of(), "rule-" + UUID.randomUUID(), 3,
                    r.zmNotEvaluableRule(), "section-2", r.zmNotEvaluableSection(), targets, "NOT_EVALUABLE",
                    "inline layer cleanup not collected", null, List.of(), List.of(), List.of(), latestStart, null, null);
            zoneRunComplete(c, latestRun, latestEnd, 1, 1, 1, 1, 3);
        });
        ids.put("zmRun", latestRun);
        ids.put("zmFindingUid", violationUid);
        ids.put("zmResolvedUid", resolvedUid);
    }

    /** One violated cell as the engine stores it, with its witness (ranges, a service, sample addresses) and a NAT view. */
    private static Map<String, Object> zoneCell(String source, String destination, String severity, String sourceRange,
            String destinationRange, String natAddress) {
        Map<String, Object> witness = new LinkedHashMap<>();
        witness.put("source_range", sourceRange);
        witness.put("destination_range", destinationRange);
        witness.put("service", "tcp/443");
        witness.put("sample_source_ip", 3405803776L + 17);
        witness.put("sample_destination_ip", 3405803776L + 65);
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("source_zone", source);
        cell.put("destination_zone", destination);
        cell.put("statement", "DENY_ALL");
        cell.put("severity", severity);
        cell.put("not_allowed_services", List.of("tcp/443"));
        cell.put("nat_view", "translated to " + natAddress);
        cell.put("witness", witness);
        return cell;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void zoneRunStart(Connection c, String runId, String versionId, String sha, String policyId,
            Instant started) throws SQLException {
        exec(c, "insert into zone_matrix_run(run_id, version_id, version_number, content_sha256, policy_source_id, "
                + "engine_version, state, started_at, snapshot_collected_at) values (?, ?, 1, ?, ?, 'synthetic-engine', "
                + "'RUNNING', ?, '2026-11-20T10:00:00Z'::timestamptz)",
                runId, versionId, sha, policyId, at(started));
    }

    private static void zoneRunComplete(Connection c, String runId, Instant finished, int violation, int compliant,
            int notEvaluable, int unknown, int findings) throws SQLException {
        exec(c, "update zone_matrix_run set state = 'COMPLETE', finished_at = ?, snapshot_rule_count = 3, vendor = 'CP', "
                + "evaluated = true, coverage = 'COMPLETE', coverage_items = '[]'::jsonb, collection_failures = 0, "
                + "count_violation = ?, count_compliant = ?, count_not_evaluable = ?, count_unknown = ?, "
                + "count_not_applicable = 0, count_findings = ?, duration_ms = 30000 where run_id = ?",
                at(finished), violation, compliant, notEvaluable, unknown, findings, runId);
    }

    private static void zoneFinding(Connection c, String runId, String policyId, String ruleUid, List<String> parentPath,
            List<String> pairs, String ruleId, int ruleNumber, String ruleName, String sectionId, String sectionName,
            List<String> targets, String outcome, String reason, String severity, List<String> flags,
            List<Map<String, Object>> cells, List<String> undecided, Instant observed, Instant resolvedAt,
            String resolution) throws Exception {
        exec(c, "insert into zone_matrix_finding(run_id, policy_source_id, rule_uid, inline_parent_path, violated_pairs, "
                + "rule_id, rule_number, rule_name, section_id, section_name, target_device_ids, outcome, reason, "
                + "highest_severity, flags, covering_rule_id, violated_cells, undecided_parts, nat_view, first_observed_at, "
                + "first_observed_any_version, last_observed_at, resolved_at, resolution_reason) values (?, ?, ?, ?::jsonb, "
                + "?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)",
                runId, policyId, ruleUid, JSON.writeValueAsString(parentPath), JSON.writeValueAsString(pairs), ruleId,
                ruleNumber, ruleName, sectionId, sectionName, JSON.writeValueAsString(targets), outcome, reason, severity,
                JSON.writeValueAsString(flags), flags.isEmpty() ? null : "rule-" + UUID.randomUUID(),
                JSON.writeValueAsString(cells), JSON.writeValueAsString(undecided),
                cells.isEmpty() ? null : "translated by the gateway", at(observed), at(observed), at(observed),
                resolvedAt == null ? null : at(resolvedAt), resolution);
    }

    /**
     * Failover run history (V101/V109/V137, round 3): one finished run per vendor family on an enrolled pair that carries
     * a raw cluster reference, raw hostnames, serials and addresses. Check Point and Palo Alto: a finished failover run
     * with its approval (raw reason and the administrator as approver; a legacy window-only approval, the shape a plain
     * insert can fill -- a request-bound approval needs a nonce and member bindings of the signed request flow). Cisco ASA
     * and FortiGate: a finished readiness run (readiness is all those vendors have; the test application switches their
     * readiness on). Every run's stored message names the raw cluster reference, a member's hostname and serial, an
     * address and the administrator -- the only free-text column the runs views project. Palo Alto and FortiGate pairs
     * are enrolled here; every member of the four pairs gets an inventory run (a unit is not derived without one) and the
     * Palo Alto members the active-passive HA fact their unit derivation requires.
     */
    private static void seedFailoverHistory(Connection c, Estate e, String credential) throws Exception {
        P1 p = e.p1();
        R3 r = e.r3();
        for (AsaUnit u : List.of(r.panA(), r.panB())) {
            seedHaMember(c, credential, "palo_alto", "pan_xml_api", u, r.panCluster(), "11.1.2");
        }
        for (AsaUnit u : List.of(r.fgtA(), r.fgtB())) {
            seedHaMember(c, credential, "fortinet", "ssh_exec", u, r.fgtCluster(), "7.4.5");
        }
        List<AsaUnit> allUnits = new ArrayList<>(p.asaUnits());
        allUnits.addAll(r.units());
        audited(c, () -> {
            for (AsaUnit u : allUnits) {
                String job = job(c, u.id(), "inventory_collect", "COMPLETED", null, "device", null);
                String run = Ui2Rows.opaqueId("inv");
                exec(c, "insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) "
                        + "values (?, ?, ?, now(), 1)", run, u.id(), job);
                if (r.panA().equals(u) || r.panB().equals(u)) {
                    exec(c, "insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                            + "values (?, ?, 'physical', ?, 'active-passive', 'pan_high_availability_state')",
                            Ui2Rows.opaqueId("ha"), run, u.haRole());
                }
            }
            failoverRun(c, e, "check_point", e.cluster(), e.cpMemberA(), "FAILOVER", true);
            failoverRun(c, e, "palo_alto", r.panCluster(), r.panA(), "FAILOVER", true);
            failoverRun(c, e, "cisco_asa", p.asaCluster(), p.asaA(), "READINESS", false);
            failoverRun(c, e, "fortinet", r.fgtCluster(), r.fgtA(), "READINESS", false);
        });
    }

    private static void failoverRun(Connection c, Estate e, String vendor, String cluster, Object member, String kind,
            boolean withApproval) throws SQLException {
        String hostname = member instanceof Device d ? d.hostname() : ((AsaUnit) member).hostname();
        String serial = member instanceof Device d ? d.serial() : ((AsaUnit) member).serial();
        String address = member instanceof Device d ? d.managementAddress() : ((AsaUnit) member).managementAddress();
        String approval = null;
        if (withApproval) {
            approval = "apr-" + UUID.randomUUID();
            exec(c, "insert into failover_approval(approval_id, cluster_ref, vs_id, window_from, window_until, reason, "
                    + "approved_by, vendor) values (?, ?, null, now() - interval '1 hour', now() + interval '1 hour', ?, ?, ?)",
                    approval, cluster, "planned maintenance of " + hostname + " at " + address + ", requested by " + e.principal(),
                    e.principal(), vendor);
            ids.put("failoverApproval:" + vendor, approval);
        }
        String run = "run-" + UUID.randomUUID();
        exec(c, "insert into failover_run(run_id, cluster_ref, vs_id, approval_id, requested_by, scheduled_for, job_id, state, "
                + "step, started_at, finished_at, outcome, failed_check, message, vendor, run_kind) values (?, ?, null, ?, ?, "
                + "now() - interval '30 minutes', null, 'DONE', 'DONE', now() - interval '29 minutes', now() - interval '20 minutes', "
                + "'SUCCEEDED', null, ?, ?, ?)",
                run, cluster, approval, e.principal(),
                "finished on " + cluster + ": " + hostname + " serial " + serial + " at " + address + " for " + e.principal(),
                vendor, kind);
        ids.put("failoverRun:" + vendor, run);
    }

    /** One enrolled member of a failover pair: the device row, its endpoint and platform serial (as the ASA seed above). */
    private static void seedHaMember(Connection c, String credential, String vendor, String transport, AsaUnit u,
            String cluster, String version) throws Exception {
        audited(c, () -> {
            exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                    + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                    + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                    + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                    + "discovery_match_key) values (?, ?, 'manual', true, 'ENROLLED', false, ?, 'gateway', 'NONE', "
                    + "'CORROBORATED', ?, 'synthetic-model', ?, ?, ?, now(), ?, true, ?)",
                    u.id(), vendor, credential, u.hostname(), version, u.haRole(), u.serial(), cluster, vendor + "|" + u.serial());
            exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, ?, ?)",
                    Ui2Rows.opaqueId("ep"), u.id(), transport, u.managementAddress());
            exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                    + "values (?, ?, 'synthetic', 'synthetic_seed')", u.id(), u.serial());
        });
    }

    /**
     * Zone Matrix (V216/V220/V267): one ACTIVE version and one DRAFT based on it, with their revision rows. The canonical
     * content holds two zones with raw names, IPv4 and IPv6 blocks and a description, and one cell whose rationale,
     * regulation reference and confirmation name the administrator and a hostname. The administrator created,
     * published and edited both versions.
     */
    private static void seedZoneMatrix(Connection c, Estate e) throws Exception {
        P1 p = e.p1();
        String zoneA = UUID.randomUUID().toString();
        String zoneB = UUID.randomUUID().toString();
        var zones = List.of(
                new ZoneMatrixContent.Zone(zoneA, p.zoneNameA(), p.zoneDescription(), "DATABASE",
                        List.of(ZoneBlock.parse(p.zoneV4A() + "/30"), ZoneBlock.parse(p.zoneV6A() + "/64"))),
                new ZoneMatrixContent.Zone(zoneB, p.zoneNameB(), p.zoneDescription(), "APPLICATION",
                        List.of(ZoneBlock.parse(p.zoneV4B() + "/30"), ZoneBlock.parse(p.zoneV6B() + "/64"))));
        var cell = new ZoneMatrixContent.Cell(zoneB, zoneA, "DENY_ALL", List.of(), "HIGH", p.cellRationale(),
                p.regulationReference(), "CONFIRMED", e.principal(), "2026-11-20T10:00:00Z");
        var active = new ZoneMatrixContent(zones, ZoneMatrixContent.Matrix.initial().withCells(List.of(cell)));
        active.validate();
        var draft = new ZoneMatrixContent(List.of(zones.get(0), new ZoneMatrixContent.Zone(zoneB, p.zoneNameB(),
                "moved by " + e.principal(), "APPLICATION", List.of(ZoneBlock.parse(p.zoneV4B() + "/30")))),
                active.matrix());
        draft.validate();
        String activeId = UUID.randomUUID().toString();
        String draftId = UUID.randomUUID().toString();
        audited(c, () -> {
            exec(c, "insert into zone_matrix_version(version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) values (?, 1, 'ACTIVE', 2, ?, ?, ?, ?, ?, now())",
                    activeId, active.canonicalJson(), active.sha256(), e.principal(), e.principal(), e.principal());
            exec(c, "insert into zone_matrix_revision(version_id, revision, actor, change_kind, zone_id, content_sha256, "
                    + "content, rationale) values (?, 1, ?, 'ZONE_CREATE', ?, ?, ?, ?)",
                    activeId, e.principal(), zoneA, active.sha256(), active.canonicalJson(), p.cellRationale());
            exec(c, "insert into zone_matrix_revision(version_id, revision, actor, change_kind, content_sha256, content, "
                    + "rationale) values (?, 2, ?, 'PUBLISH', ?, ?, ?)",
                    activeId, e.principal(), active.sha256(), active.canonicalJson(), p.regulationReference());
            exec(c, "insert into zone_matrix_version(version_id, state, revision, content, content_sha256, "
                    + "based_on_version_id, created_by, updated_by) values (?, 'DRAFT', 1, ?, ?, ?, ?, ?)",
                    draftId, draft.canonicalJson(), draft.sha256(), activeId, e.principal(), e.principal());
            exec(c, "insert into zone_matrix_revision(version_id, revision, actor, change_kind, zone_id, content_sha256, "
                    + "content, rationale) values (?, 1, ?, 'ZONE_UPDATE', ?, ?, ?, ?)",
                    draftId, e.principal(), zoneB, draft.sha256(), draft.canonicalJson(), p.zoneDescription());
        });
        ids.put("zoneMatrixActive", activeId);
        ids.put("zoneMatrixDraft", draftId);
        ids.put("zoneMatrixActiveSha", active.sha256());
        ids.put("zoneA", zoneA);
        ids.put("zoneB", zoneB);
    }

    /**
     * ACI cutover (V217): one segment (raw name, IPv4 and IPv6 blocks, a peer block, written by the administrator), one
     * operator-override assessment on the first Check Point member citing the seeded policy, and its flow rows: a
     * dropped flow between raw addresses decided by the seeded rule, and a missing-rule group with its raw sources.
     */
    private static void seedCutover(Connection c, Estate e) throws Exception {
        P1 p = e.p1();
        String segment = UUID.randomUUID().toString();
        String assessment = UUID.randomUUID().toString();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", e.ruleId());
        rule.put("number", 1);
        rule.put("name", e.ruleName());
        rule.put("sectionId", "section-1");
        rule.put("sectionName", e.sectionName());
        rule.put("action", "Drop");
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("direction", "SEGMENT_TO_PEER");
        flow.put("flowSource", p.flowSource());
        flow.put("flowDestination", p.flowDestination());
        flow.put("protocol", "tcp");
        flow.put("port", 443);
        flow.put("flowCount", 7);
        flow.put("firstSeen", p.flowFirstSeen());
        flow.put("lastSeen", "2026-11-20T08:00:00Z");
        flow.put("decision", "WOULD_DROP");
        flow.put("decisionReason", "rule match");
        flow.put("decidingRule", rule);
        flow.put("targetSource", "OPERATOR_OVERRIDE");
        flow.put("flags", List.of());
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("direction", "SEGMENT_TO_PEER");
        missing.put("flowDestination", p.flowDestination());
        missing.put("protocol", "tcp");
        missing.put("port", 1433);
        missing.put("sourceCount", 2);
        missing.put("flowSources", List.of(p.flowSource(), p.missingRuleSource()));
        missing.put("flowCount", 3);
        missing.put("firstSeen", p.flowFirstSeen());
        missing.put("lastSeen", "2026-11-20T08:00:00Z");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("engineVersion", "synthetic-engine");
        summary.put("status", "COMPLETE");
        summary.put("readiness", "BLOCKERS_FOUND");
        summary.put("headline", "1 of 1 evaluated flows would be dropped by the target policy.");
        summary.put("targetSource", "OPERATOR_OVERRIDE");
        summary.put("counts", Map.of("tuples", 1, "flows", 7, "blockingTuples", 1, "missingRuleGroups", 1));
        summary.put("coverage", List.of(Map.of("code", "LOG_PERIOD")));
        summary.put("uploads", List.of(Map.of("index", 0, "rowCount", 10, "invalidRows", 0, "inScopeFlows", 10,
                "periodStart", p.flowFirstSeen(), "periodEnd", "2026-11-20T08:00:00Z")));
        try {
            c.setAutoCommit(false);
            Ui2Rows.setAuditContext(c, e.principal(), "cutover_assessment_write");
            exec(c, "insert into cutover_segment(segment_id, name, revision, blocks, peer_blocks, created_by, updated_by) "
                    + "values (?, ?, 1, ?::jsonb, ?::jsonb, ?, ?)",
                    segment, p.segmentName(), JSON.writeValueAsString(List.of(p.segmentV4() + "/29", p.segmentV6() + "/48")),
                    JSON.writeValueAsString(List.of(p.peerV4() + "/29")), e.principal(), e.principal());
            exec(c, "insert into cutover_assessment(assessment_id, segment_id, segment_revision, actor, target_source, "
                    + "target_device_id, target_policy_id, target_collected_at, engine_version, status, readiness, summary) "
                    + "values (?, ?, 1, ?, 'OPERATOR_OVERRIDE', ?, ?, '2026-11-20T10:00:00Z', 'synthetic-engine', 'COMPLETE', "
                    + "'BLOCKERS_FOUND', ?::jsonb)",
                    assessment, segment, e.principal(), e.cpMemberA().id(), e.policyId(), JSON.writeValueAsString(summary));
            exec(c, "insert into cutover_assessment_flow(assessment_id, kind, ordinal, decision, flow) "
                    + "values (?, 'FLOW', 0, 'WOULD_DROP', ?::jsonb), (?, 'MISSING_RULE', 0, 'MISSING_RULE', ?::jsonb)",
                    assessment, JSON.writeValueAsString(flow), assessment, JSON.writeValueAsString(missing));
            c.commit();
        } catch (Exception failure) {
            c.rollback();
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
        ids.put("segmentId", segment);
        ids.put("assessmentId", assessment);
    }

    /**
     * A Cisco ASA failover pair: two enrolled gateways sharing a raw cluster reference, the pair's recorded unit serials
     * (MATCH), and one stored running configuration per unit (encrypted in the test store) with a raw interface name
     * and address, a network object and an access list.
     */
    private static void seedAsaPair(Connection c, Estate e, String credential) throws Exception {
        P1 p = e.p1();
        List<String> serials = new ArrayList<>(List.of(p.asaA().serial(), p.asaB().serial()));
        java.util.Collections.sort(serials);
        audited(c, () -> {
            for (AsaUnit u : p.asaUnits()) {
                exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                        + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                        + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                        + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                        + "discovery_match_key) values (?, 'cisco_asa', 'manual', true, 'ENROLLED', false, ?, 'gateway', 'NONE', "
                        + "'CORROBORATED', ?, 'synthetic-model', '9.18(4)22', ?, ?, now(), ?, true, ?)",
                        u.id(), credential, u.hostname(), u.haRole(), u.serial(), p.asaCluster(), "cisco_asa|" + u.serial());
                exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                        Ui2Rows.opaqueId("ep"), u.id(), u.managementAddress());
                exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                        + "values (?, ?, 'synthetic', 'synthetic_seed')", u.id(), u.serial());
                String config = "ASA Version 9.18(4)22\n"
                        + "hostname " + u.hostname() + "\n"
                        + "interface GigabitEthernet0/0\n nameif " + p.asaNameif() + "\n security-level 0\n"
                        + " ip address " + p.asaInterfaceAddress() + " 255.255.255.0\n"
                        + "interface GigabitEthernet0/1\n nameif inside\n security-level 100\n"
                        + "object network " + p.asaObjectName() + "\n host " + p.asaObjectAddress() + "\n"
                        + "access-list " + p.asaAclName() + " extended permit tcp any object " + p.asaObjectName() + " eq 443\n"
                        + "access-list " + p.asaAclName() + " extended deny ip host " + p.flowSource() + " any\n"
                        + "access-group " + p.asaAclName() + " in interface " + p.asaNameif() + "\n";
                String job = job(c, u.id(), "configuration_collect", "COMPLETED", null, "device", null);
                var handle = store.open(u.id(), job, "cisco_asa", false);
                byte[] plain = config.getBytes(StandardCharsets.UTF_8);
                handle.sink().write(plain);
                var meta = handle.finish();
                String ref = meta.ref().value();
                exec(c, "insert into backup_artefact(artefact_id, device_id, artefact_class, vendor, software_version, "
                        + "hostname_fingerprint, plaintext_sha256, plaintext_bytes, ciphertext_sha256, ciphertext_bytes, key_id, "
                        + "wrapped_data_key, validation, retention_tier, recovery_volume_path) values (?, ?, 'configuration', "
                        + "'cisco_asa', '9.18(4)22', ?, ?, ?, ?, ?, ?, ?, "
                        + "'{\"level_reached\":\"V1\",\"v3_status\":\"NOT_APPLICABLE\",\"restore_proven\":false}'::jsonb, "
                        + "'standard', ?)",
                        ref, u.id(), sha256(u.hostname()), meta.plaintextSha256(), meta.plaintextBytes(),
                        meta.ciphertextSha256(), meta.ciphertextBytes(), meta.keyId(), meta.wrappedDataKey(), ref);
                exec(c, "insert into configuration_artefact(artefact_ref, device_id, job_id, vendor, plaintext_sha256, "
                        + "plaintext_bytes, ciphertext_sha256, ciphertext_bytes, compression, key_id) "
                        + "values (?, ?, ?, 'cisco_asa', ?, ?, ?, ?, 'none', ?)",
                        ref, u.id(), job, meta.plaintextSha256(), meta.plaintextBytes(), meta.ciphertextSha256(),
                        meta.ciphertextBytes(), meta.keyId());
                exec(c, "insert into device_configuration_run(run_id, device_id, job_id, collected_at, vendor, read_kind, "
                        + "is_primary, canonical_hash, raw_hash, raw_bytes, artefact_ref, change_state) "
                        + "values (?, ?, ?, '2026-11-20T09:30:00Z'::timestamptz, 'cisco_asa', 'show_configuration', true, ?, ?, ?, ?, "
                        + "'first_run')",
                        Ui2Rows.opaqueId("cfg"), u.id(), job, meta.plaintextSha256(), meta.plaintextSha256(),
                        meta.plaintextBytes(), ref);
            }
            exec(c, "insert into ha_pair_identity(cluster_member_ref, vendor, unit_serial_low, unit_serial_high, recorded_by, "
                    + "last_state) values (?, 'cisco_asa', ?, ?, 'synthetic_seed', 'MATCH')",
                    p.asaCluster(), serials.get(0), serials.get(1));
        });
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
        if (template.startsWith("/api/v2/zone-matrix/versions/")) byName.put("id", ids.get("zoneMatrixActive"));
        if (template.startsWith("/api/v2/cutover/segments/")) byName.put("id", ids.get("segmentId"));
        if (template.startsWith("/api/v2/cutover/assessments/")) byName.put("id", ids.get("assessmentId"));
        if (template.startsWith("/api/v2/scripts/")) byName.put("id", ids.get("scriptId"));
        if (template.startsWith("/api/v2/scripts/runs/")) byName.put("runId", ids.get("scriptRun"));
        if (template.startsWith("/api/v2/log-findings/")) byName.put("findingId", ids.get("logFinding"));
        if (template.startsWith("/api/v2/packet-analysis/")) {
            byName.put("caseId", ids.get("paCase"));
            byName.put("captureId", ids.get("paCapture"));
            byName.put("jobId", ids.get("paRun"));
        }
        String family = failoverFamily(template);
        if (family != null) {
            String opaque = opaque(failoverCluster(e, family));
            byName.put("clusterId", opaque);
            byName.put("unitId", opaque);
            byName.put("runId", ids.get("failoverRun:" + family));
        }
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
                .replace("{fcluster}", family == null ? "" : opaque(failoverCluster(e, family)))
                .replace("{artefact}", ids.get("artefactId"));
    }

    /**
     * The vendor family ("check_point", "palo_alto", "cisco_asa", "fortinet") of a failover route template, or null. One
     * controller serves the four prefixes; each reads the pair of its own vendor.
     */
    private static String failoverFamily(String template) {
        if (template.startsWith("/api/v2/cp-failover/")) return "check_point";
        if (template.startsWith("/api/v2/pan-failover/")) return "palo_alto";
        if (template.startsWith("/api/v2/asa-failover/")) return "cisco_asa";
        if (template.startsWith("/api/v2/fgt-failover/")) return "fortinet";
        return null;
    }

    private static String failoverCluster(Estate e, String family) {
        return switch (family) {
            case "check_point" -> e.cluster();
            case "palo_alto" -> e.r3().panCluster();
            case "cisco_asa" -> e.p1().asaCluster();
            default -> e.r3().fgtCluster();
        };
    }

    /** The failover service's opaque cluster and unit id: a name-based UUID of the cluster reference. */
    private static String opaque(String clusterReference) {
        return UUID.nameUUIDFromBytes(clusterReference.getBytes(StandardCharsets.UTF_8)).toString();
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
            Map.entry("/api/v2/log-findings/coverage", "device_id={device}"),
            Map.entry("/api/v2/cp-failover/runs", "clusterId={fcluster}&unitId={fcluster}"),
            Map.entry("/api/v2/cp-failover/approvals", "clusterId={fcluster}&unitId={fcluster}"),
            Map.entry("/api/v2/pan-failover/runs", "clusterId={fcluster}&unitId={fcluster}"),
            Map.entry("/api/v2/pan-failover/approvals", "clusterId={fcluster}&unitId={fcluster}"),
            Map.entry("/api/v2/asa-failover/runs", "clusterId={fcluster}&unitId={fcluster}"),
            Map.entry("/api/v2/fgt-failover/runs", "clusterId={fcluster}&unitId={fcluster}"),
            Map.entry("/api/v2/diagnostics/ports", "device_id={device}"),
            Map.entry("/api/v2/estate/as-of", "at=2026-11-23T00:00:00Z"),
            Map.entry("/api/v2/policy/changes/actor-candidates", "revision=1"),
            Map.entry("/backups/{leftId}/compare", "with={artefact}"),
            Map.entry("/api/v2/system-logs/crash-files", "deviceId={device}"));

    // ------------------------------------------------------------------ HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * Per-request timeout: 30 s on a workstation and in CI. A shared, CPU-throttled runner (the HOST-A ui2-test pod) sets
     * {@code UI2_TEST_HTTP_TIMEOUT_S} (30..600) to give a slow answer time; the sweep checks the same routes and values.
     */
    static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String contentType, String body) {
    }

    private static Resp get(String target, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String session;

    /** One aiview session for the whole class: a second concurrent login of the same user is refused (409). */
    private static synchronized String loginAsAiview() throws Exception {
        if (session == null) session = login();
        return session;
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
        markers.put(ids.get("zoneMatrixActive"), "zone-matrix-version");
        markers.put(ids.get("zoneA"), "zone");
        markers.put(ids.get("segmentId"), "cutover-segment");
        markers.put(ids.get("assessmentId"), "cutover-assessment");
        markers.put(estate.p1().flowFirstSeen(), "cutover-flow");
        markers.put("Rule is disabled.", "hygiene-finding");
        for (AsaUnit u : estate.p1().asaUnits()) markers.put(u.id(), "asa-unit");
        markers.put(ids.get("scriptId"), "script");
        markers.put(ids.get("scriptRun"), "script-run");
        markers.put(ids.get("logFinding"), "log-finding");
        markers.put(ids.get("logJob"), "log-window");
        markers.put(ids.get("paCase"), "pa-case");
        markers.put(ids.get("paCapture"), "pa-capture");
        markers.put(ids.get("paRun"), "pa-run");
        markers.put(ids.get("zmRun"), "zone-matrix-run");
        markers.put(ids.get("zmFindingUid"), "zone-matrix-finding");
        markers.put(ids.get("zmResolvedUid"), "zone-matrix-resolved");
        for (String family : List.of("check_point", "palo_alto", "cisco_asa", "fortinet")) {
            markers.put(ids.get("failoverRun:" + family), "failover-run-" + family);
        }
        for (AsaUnit u : estate.r3().units()) markers.put(u.id(), "ha-unit");
        markers.put(ids.get("failoverApproval:check_point"), "failover-approval-check_point");
        markers.put(ids.get("failoverApproval:palo_alto"), "failover-approval-palo_alto");
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
            if (!Integer.valueOf(200).equals(statusByRoute.get(route))
                    || !seen.getOrDefault(route, Set.of()).containsAll(expected)) {
                uncovered.add(route + " expected 200+" + expected + " saw " + seen.get(route)
                        + " status " + statusByRoute.get(route));
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

    /**
     * PO decision 2026-10-18: the masked Packet Analysis view is the only view aiview gets. The raw-view action is not
     * aiview's, the seeded case says so in its permissions, the run report is the masked projection and the capture
     * download is refused.
     */
    @Test
    void packetAnalysisRawViewStaysOutOfAiviewReach() throws Exception {
        assertFalse(AiviewReachableRoutes.aiviewMayCall(ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
                "aiview may never hold " + ActionRegistry.PACKET_ANALYSIS_RAW_VIEW);
        String cookie = loginAsAiview();
        Resp read = get("/api/v2/packet-analysis/cases/" + ids.get("paCase"), cookie);
        assertEquals(200, read.status(), "aiview reads the seeded case");
        var permissions = JSON.readTree(read.body()).path("permissions");
        assertFalse(permissions.path("raw_view").asBoolean(true), "case permissions offer aiview no raw view");
        assertTrue(permissions.path("masked").asBoolean(false), "case permissions say the view is masked");
        Resp report = get("/api/v2/packet-analysis/runs/" + ids.get("paRun") + "/report", cookie);
        assertEquals(200, report.status(), "aiview reads the seeded run report");
        assertEquals("masked", JSON.readTree(report.body()).path("projection").asText(), "aiview's report projection");
        Resp download = get("/api/v2/packet-analysis/captures/" + ids.get("paCapture") + "/download", cookie);
        assertEquals(403, download.status(), "aiview downloads no raw capture");
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
