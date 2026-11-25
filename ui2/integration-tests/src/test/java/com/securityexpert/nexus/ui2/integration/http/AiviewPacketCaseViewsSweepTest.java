package com.securityexpert.nexus.ui2.integration.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Topology;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.AiviewReachableRoutes;

/**
 * Sweep round 4 of backlog {@code aiview_masking_leak_audit} (AGENTS.md "Mandatory AIView inspection &amp; post-parsing
 * masking law"; PO decision 2026-10-18 "aiview sees the masked Packet Analysis view only"): the case-detail routes of
 * Packet Analysis read a per-case DuckDB, so rounds 1-3 ({@code AiviewRawVersusMaskedSweepTest}) saw them answer 409
 * and counted nothing. This class gives one case a real {@code project.duckdb} -- built by the real engine
 * ({@code CaseRunner.runEngineOnTopology}) from synthetic packet rows, as {@code PacketAnalysisPayloadMaskingApiTest}
 * does in the service module, without a tshark installation -- boots the application on a real PostgreSQL database,
 * logs in as aiview ({@code role:replay_viewer}) and reads every case-detail GET route the controllers map.
 *
 * <p>Planted values are synthetic and built at runtime: documentation addresses (192.0.2.0/24, 198.51.100.0/24,
 * 2001:db8::/32), a DNS question name, estate host names and serials (seeded as devices, so the estate dictionary
 * knows them), an administrator principal, a capture file name and a case title. Output names route, HTTP status and
 * value <em>class</em> with a count, never a value. A route must answer 200 with seeded data (a 409 or an empty list is
 * a seeding failure) or be a reviewed 403; any surviving planted value fails the test.</p>
 */
class AiviewPacketCaseViewsSweepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE = "/api/v2/packet-analysis";
    private static final String CASE_PREFIX = BASE + "/cases/{caseId}/";

    /** What a planted value is; the only thing about a value this test ever prints. */
    enum ValueClass {
        HOSTNAME, ADDRESS, SERIAL, PRINCIPAL, FILE_NAME, CASE_TITLE
    }

    /**
     * Every GET route under {@code /cases/{caseId}/} that the controllers map (the shared enumeration, not a hand list
     * of what to sweep). A new route appears in the enumeration and fails {@link #theSweptRoutesAreTheMappedRoutes()}
     * until it is added here with a request and a coverage check.
     */
    static final Set<String> CASE_DETAIL_ROUTES = Set.of(
            "state", "flows", "frames", "diagnosis", "charts", "ladder", "waterfall", "timeseries", "topology/overlay",
            "topology/inspect", "findings", "events", "dns", "field-diff", "export/findings", "export/download",
            "export/expert-report", "sessions/evidence", "reference/template", "correlation", "device-events");

    /** Routes the gate refuses aiview by design: they need {@code packet_analysis_raw_view}, which is security_admin only. */
    static final Map<String, String> REFUSED_BY_DESIGN = Map.of(
            "field-diff", "raw packet field differences need packet_analysis_raw_view (security_admin only)",
            "export/download", "HTML/JSON/CSV/XLSX engine exports carry raw values and need packet_analysis_raw_view");

    private static Ui2PostgresFixture fixture;
    private static ConfigurableApplicationContext context;
    private static int port;
    private static Path work;
    private static Planted planted;
    private static String caseId;
    private static String flowTuple;

    // ------------------------------------------------------------------ the planted values

    record Device(String id, String hostname, String serial, String managementAddress) {
    }

    /** Every planted raw value, by class. */
    record Planted(Device deviceA, Device deviceB, String principal, String dnsName, String clientV4, String serverV4,
            String resolverV4, String clientV6, String serverV6, String fileName, String caseTitle,
            Map<String, ValueClass> raw) {

        static Planted generate() {
            int base = 20 + RANDOM.nextInt(150);
            Device a = new Device(Ui2Rows.opaqueId("dev"), "ist" + tag() + "-fw-01", "CP" + hex(5).toUpperCase(Locale.ROOT),
                    "192.0.2." + (base + 40));
            Device b = new Device(Ui2Rows.opaqueId("dev"), "dc" + tag() + "-edge-gw02", "0" + digits(11),
                    "198.51.100." + (base + 40));
            String principal = "adm" + tag() + ".ops";
            String dnsName = "srv" + tag() + ".dmz" + tag() + ".corp-" + tag() + ".example.test";
            String clientV4 = "192.0.2." + base;
            String serverV4 = "192.0.2." + (base + 1);
            String resolverV4 = "198.51.100." + base;
            String v6 = "2001:db8:" + Integer.toHexString(0x1000 + RANDOM.nextInt(0xefff)) + "::";
            String clientV6 = v6 + Integer.toHexString(0x100 + RANDOM.nextInt(0xefff));
            String serverV6 = v6 + Integer.toHexString(0x100 + RANDOM.nextInt(0xefff));
            String fileName = "cust-" + tag() + "-core-fw.pcap";
            String caseTitle = "Payroll " + tag() + " slow login";
            Map<String, ValueClass> raw = new LinkedHashMap<>();
            for (Device d : List.of(a, b)) {
                raw.put(d.hostname(), ValueClass.HOSTNAME);
                raw.put(d.serial(), ValueClass.SERIAL);
                raw.put(d.managementAddress(), ValueClass.ADDRESS);
            }
            raw.put(principal, ValueClass.PRINCIPAL);
            raw.put(dnsName, ValueClass.HOSTNAME);
            for (String address : List.of(clientV4, serverV4, resolverV4, clientV6, serverV6)) raw.put(address, ValueClass.ADDRESS);
            raw.put(fileName, ValueClass.FILE_NAME);
            raw.put(caseTitle, ValueClass.CASE_TITLE);
            return new Planted(a, b, principal, dnsName, clientV4, serverV4, resolverV4, clientV6, serverV6, fileName,
                    caseTitle, Map.copyOf(raw));
        }

        /** Engine-style text that embeds estate values, as engine reasons and vendor trailer text can. */
        String embeddingText() {
            return "reset toward " + deviceA.hostname() + " (serial " + deviceA.serial() + ") at " + deviceA.managementAddress()
                    + " approved by " + principal + " for " + dnsName + " from " + clientV4;
        }
    }

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

    // ------------------------------------------------------------------ boot

    @BeforeAll
    static void bootAndSeed() throws Exception {
        fixture = Ui2PostgresFixture.createAndMigrate("aiview_pa_cases");
        work = Files.createTempDirectory("ui2-aiview-pa-cases-");
        Path paRoot = Files.createDirectory(work.resolve("pa"));
        planted = Planted.generate();
        seedDatabase(planted);
        seedCaseDuckDb(paRoot, planted);
        context = new SpringApplicationBuilder(Ui2Application.class)
                .initializers(ctx -> ctx.addBeanFactoryPostProcessor(beanFactory -> {
                    // the fixed machine port is not opened: another fork may own it
                    ((org.springframework.beans.factory.support.BeanDefinitionRegistry) beanFactory)
                            .removeBeanDefinition("machineConnector");
                }))
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
                        "--ui2.packet-analysis.root=" + paRoot,
                        "--ui2.sanitizer.work-dir=" + Files.createDirectory(work.resolve("sanitizer")),
                        "--ui2.scheduling.enabled=false");
        port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
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

    // ------------------------------------------------------------------ PostgreSQL seed

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

    /**
     * Two gateways (host name, serial, management address -- the estate dictionary the free-text masker reads) and one
     * ANALYZED Packet Analysis case created by the planted administrator, with its capture and a completed run.
     */
    private static void seedDatabase(Planted p) throws Exception {
        caseId = UUID.randomUUID().toString();
        String captureId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", "Inconclusive");
        summary.put("reason", p.embeddingText());
        summary.put("capture_count", 2);
        summary.put("total_bytes", 4096);
        summary.put("finding_count", 1);
        summary.put("flow_count", 3);
        summary.put("schema_version", 11);
        try (Connection c = fixture.migrateConnection()) {
            String credential = Ui2Rows.insertCredentialReference(c);
            audited(c, () -> {
                for (Device d : List.of(p.deviceA(), p.deviceB())) {
                    exec(c, "insert into devices(device_id, vendor_hint, registration_source, is_test_target, enrollment_state, "
                            + "disabled, credential_reference_id, role, identity_mismatch_state, peer_follow_outcome, "
                            + "observed_hostname, observed_model, observed_software_version, observed_ha_role, "
                            + "recorded_identity_primary, recorded_identity_recorded_at, cluster_member_ref, backup_target, "
                            + "discovery_match_key) values (?, 'check_point', 'manual', true, 'ENROLLED', false, ?, 'gateway', "
                            + "'NONE', 'NONE', ?, 'synthetic-model', 'R81.20', null, ?, now(), null, true, ?)",
                            d.id(), credential, d.hostname(), d.serial(), "check_point|" + d.serial());
                    exec(c, "insert into endpoints(endpoint_id, device_id, transport_kind, address_ref) values (?, ?, 'ssh_exec', ?)",
                            Ui2Rows.opaqueId("ep"), d.id(), d.managementAddress());
                    exec(c, "insert into device_platform_facts(device_id, serial_number, platform_family, source_read) "
                            + "values (?, ?, 'synthetic', 'synthetic_seed')", d.id(), d.serial());
                }
                exec(c, "insert into packet_analysis_case(case_id, title, created_by, created_at, expires_at, state, total_bytes) "
                        + "values (?, ?, ?, now(), now() + interval '7 days', 'ANALYZED', 4096)",
                        caseId, p.caseTitle(), p.principal());
                exec(c, "insert into packet_analysis_capture(capture_id, case_id, display_name, capture_format, size_bytes, "
                        + "sha256, storage_key, uploaded_by) values (?, ?, ?, 'pcapng', 2048, ?, ?, ?)",
                        captureId, caseId, p.fileName(), "5".repeat(64), UUID.randomUUID() + ".pcapng", p.principal());
                exec(c, "insert into jobs(job_id, job_type, capability_id, target_ref, target_kind, "
                        + "submitted_by_actor_fingerprint, idempotency_key, action_class, state, finished_at) "
                        + "values (?, 'packet_analysis_run', 'packet_analysis_run', ?, 'packet_analysis_case', ?, ?, 'read', "
                        + "'COMPLETED', now())", jobId, caseId, Ui2Rows.ACTOR, "packet-analysis:" + jobId);
                exec(c, "insert into packet_analysis_run(job_id, case_id, state, requested_by, started_at, finished_at, "
                        + "engine_version, masked_summary) values (?, ?, 'COMPLETED', ?, now() - interval '1 minute', now(), "
                        + "'synthetic-1', ?::jsonb)", jobId, caseId, p.principal(), JSON.writeValueAsString(summary));
            });
        }
    }

    // ------------------------------------------------------------------ the case DuckDB (real engine)

    private static final String INSERT_PACKET = "INSERT INTO packets (capture_id, frame, ts, iface, src, dst, sport, dport, "
            + "proto, ipid, seq, ack, flags, length, wirelen, caplen, prefix, payload_hash, signature, tuple_key, "
            + "reverse_tuple, stream, ttl, dscp, mss, \"window\", retrans, fast_retrans, spurious, out_of_order, acked_unseen, "
            + "zero_window, rtt, unsupported, frame_hash, icmp_id, icmp_seq, icmp_type, dns_id, dns_response, vendor, "
            + "path_fields) VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 1460, 65535, "
            + "false, false, false, false, false, false, NULL, '', ?, NULL, NULL, NULL, ?, ?, ?, ?)";

    private static String tuple(String proto, String a, int pa, String b, int pb) throws Exception {
        return JSON.writeValueAsString(List.of(proto, a, pa, b, pb));
    }

    /**
     * Two capture points seeing the same traffic: an IPv4 TCP flow (handshake, a request, a response), an IPv6 TCP flow
     * and a DNS exchange whose question name is planted. The vendor trailer of the request frames carries text that embeds
     * the estate values; the stored report gets a finding and an F5-style reset in the engine's own shapes (the
     * application sections need tshark, which this build does not require).
     */
    private static void seedCaseDuckDb(Path paRoot, Planted p) throws Exception {
        var projectStore = ProjectStore.open(PacketAnalysisPaths.caseDir(paRoot, caseId), ProjectStore.Settings.defaults(null));
        String invJson = "{\"packet_count\":12,\"records_read\":12,\"prefilter_excluded\":0,\"prefilter_skipped\":false}";
        String vendor = JSON.writeValueAsString(Map.of("adapter", "f5", "f5ethtrailer.vip", "/Common/vip-" + tag(),
                "f5ethtrailer.rstcausetxt", p.embeddingText(), "f5ethtrailer.peeraddr", p.serverV4(), "f5ethtrailer.flowid",
                "0x1a", "http.request.method", "GET", "http.request.uri", "/pay/" + tag(), "http.request.version", "HTTP/1.1"));
        String fwd4 = tuple("TCP", p.clientV4(), 40000, p.serverV4(), 80);
        String rev4 = tuple("TCP", p.serverV4(), 80, p.clientV4(), 40000);
        String fwd6 = tuple("TCP", p.clientV6(), 41000, p.serverV6(), 80);
        String rev6 = tuple("TCP", p.serverV6(), 80, p.clientV6(), 41000);
        String dnsFwd = tuple("UDP", p.clientV4(), 53000, p.resolverV4(), 53);
        String dnsRev = tuple("UDP", p.resolverV4(), 53, p.clientV4(), 53000);
        flowTuple = fwd4;
        String plain = "{\"transport_index\":1,\"dns_index\":1}";
        String dnsQuery = "{\"transport_index\":1,\"dns_index\":1,\"dns_name\":\"" + p.dnsName()
                + "\",\"dns_type\":1,\"dns_class\":1,\"dns_questions\":1,\"dns_rcode\":0}";
        // frame, ts at point 1, ts at point 2, src, dst, sport, dport, proto, ipid, seq, ack, flags, length, prefix,
        // payload hash, signature, tuple, reverse tuple, dns id, dns response, vendor, path fields
        Object[][] rows = {
            {1L, 1700000000.100, 1700000000.105, p.clientV4(), p.serverV4(), 40000L, 80L, "TCP", 101L, 1000L, 0L, 2L, 0L, "", "", "sig1", fwd4, rev4, null, false, null, plain},
            {2L, 1700000000.120, 1700000000.115, p.serverV4(), p.clientV4(), 80L, 40000L, "TCP", 201L, 2000L, 1001L, 18L, 0L, "", "", "sig2", rev4, fwd4, null, false, null, plain},
            {3L, 1700000000.130, 1700000000.135, p.clientV4(), p.serverV4(), 40000L, 80L, "TCP", 102L, 1001L, 2001L, 16L, 0L, "", "", "sig3", fwd4, rev4, null, false, null, plain},
            {4L, 1700000001.100, 1700000001.105, p.clientV4(), p.serverV4(), 40000L, 80L, "TCP", 103L, 1001L, 2001L, 24L, 18L, "474554202f", "p1", "sig4", fwd4, rev4, null, false, vendor, plain},
            {5L, 1700000001.150, 1700000001.145, p.serverV4(), p.clientV4(), 80L, 40000L, "TCP", 202L, 2001L, 1019L, 16L, 0L, "", "", "sig5", rev4, fwd4, null, false, null, plain},
            {6L, 1700000001.200, 1700000001.195, p.serverV4(), p.clientV4(), 80L, 40000L, "TCP", 203L, 2001L, 1019L, 24L, 15L, "485454502f", "p2", "sig6", rev4, fwd4, null, false, vendor, plain},
            {7L, 1700000001.300, 1700000001.305, p.clientV4(), p.resolverV4(), 53000L, 53L, "UDP", 104L, 0L, 0L, 0L, 30L, "", "d1", "sig7", dnsFwd, dnsRev, 4660L, false, null, dnsQuery},
            {8L, 1700000001.320, 1700000001.315, p.resolverV4(), p.clientV4(), 53L, 53000L, "UDP", 204L, 0L, 0L, 0L, 46L, "", "d2", "sig8", dnsRev, dnsFwd, 4660L, true, null, dnsQuery},
            {9L, 1700000001.400, 1700000001.405, p.clientV4(), p.serverV4(), 40000L, 80L, "TCP", 105L, 1019L, 2016L, 17L, 0L, "", "", "sig9", fwd4, rev4, null, false, null, plain},
            {10L, 1700000002.100, 1700000002.105, p.clientV6(), p.serverV6(), 41000L, 80L, "TCP", 0L, 5000L, 0L, 2L, 0L, "", "", "sig10", fwd6, rev6, null, false, null, plain},
            {11L, 1700000002.120, 1700000002.115, p.serverV6(), p.clientV6(), 80L, 41000L, "TCP", 0L, 6000L, 5001L, 18L, 0L, "", "", "sig11", rev6, fwd6, null, false, null, plain},
            {12L, 1700000002.130, 1700000002.135, p.clientV6(), p.serverV6(), 41000L, 80L, "TCP", 0L, 5001L, 6001L, 16L, 0L, "", "", "sig12", fwd6, rev6, null, false, null, plain}
        };
        try (var db = projectStore.connect()) {
            for (String cap : List.of("cap1", "cap2")) {
                db.execute("INSERT INTO captures (id, path, name, state, checkpoint, inventory, error) VALUES (?, ?, ?, 'ready', 12, ?, NULL)",
                        cap, "/synthetic/" + cap + ".pcap", cap.equals("cap1") ? p.fileName() : "synthetic-server-edge.pcap", invJson);
            }
            for (Object[] r : rows) {
                long len = (Long) r[12];
                for (String cap : List.of("cap1", "cap2")) {
                    boolean first = cap.equals("cap1");
                    if (!first && ((Long) r[0]) == 4L) continue; // the request frame never reaches the second point: a loss event
                    db.execute(INSERT_PACKET, cap, r[0], first ? r[1] : r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10],
                            r[11], len, 54L + len, 54L + len, r[13], r[14], r[15], r[16], r[17], first ? 64L : 63L, "h" + r[0],
                            r[18], r[19], r[20], r[21]);
                }
            }
        }
        var top = new Topology();
        top.points.add(Point.of("p1", p.fileName(), p.fileName(), "cap1", Py.i(0), Py.i(0)));
        top.points.add(Point.of("p2", "synthetic-server-edge.pcap", "synthetic-server-edge.pcap", "cap2", Py.i(240), Py.i(0)));
        top.forward = List.of("p1", "p2");
        top.reverse = List.of("p2", "p1");
        top.clientCidrs = List.of(p.clientV4() + "/32", p.clientV6() + "/128");
        CaseRunner.runEngineOnTopology(projectStore.path(), top.dump());

        Path dbPath = PacketAnalysisPaths.projectDuckDb(paRoot, caseId);
        try (var conn = Engine.open(dbPath); var db = new Db(conn)) {
            Object[] stored = db.one("SELECT value FROM settings WHERE key='report'");
            Map<String, Object> report = JSON.readValue((String) stored[0], new TypeReference<LinkedHashMap<String, Object>>() { });
            injectApplicationSections(report, p);
            db.exec("INSERT OR REPLACE INTO settings VALUES ('report', ?)", JSON.writeValueAsString(report));
        }
        assertTrue(Files.isRegularFile(dbPath), "the engine wrote the case DuckDB");
    }

    @SuppressWarnings("unchecked")
    private static void injectApplicationSections(Map<String, Object> report, Planted p) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("point", "p1");
        ref.put("file", p.fileName());
        ref.put("capture_id", "cap1");
        ref.put("frame", 4);
        ref.put("display_filter", "frame.number == 4");
        ref.put("observed_time", 1700000001.1);
        ref.put("corrected_time", 1700000001.1);
        ref.put("content_filter", "(ip.src == " + p.clientV4() + " && ip.dst == " + p.serverV4() + " && tcp) && tcp.len == 18");
        ref.put("flow_filter", "((ip.src == " + p.clientV4() + " && ip.dst == " + p.serverV4() + "))");
        ref.put("filter_note", "Content filters survive frame renumbering.");
        ref.put("tunnel", null);
        ref.put("fragment", null);
        Map<String, Object> vendorRef = new LinkedHashMap<>(ref);
        vendorRef.put("vendor", Map.of("adapter", "f5", "f5ethtrailer.rstcausetxt", p.embeddingText(),
                "f5ethtrailer.peeraddr", p.serverV4()));
        Map<String, Object> reset = new LinkedHashMap<>();
        reset.put("device", "synthetic-proxy");
        reset.put("flow", flowTuple);
        reset.put("time", 1700000001.2);
        reset.put("reason", p.embeddingText());
        reset.put("evidence", List.of(vendorRef));
        report.put("f5", new LinkedHashMap<>(Map.of("pairs", List.of(), "requests", List.of(), "resets", List.of(reset),
                "connection_count", 1, "note", "Overview shows up to 100 connection pairs")));
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", "forward:p1:p2:reset:" + tag());
        finding.put("type", "tcp_reset");
        finding.put("hop", "forward:p1:p2");
        finding.put("device", "synthetic-proxy");
        finding.put("direction", "forward");
        finding.put("severity", "high");
        finding.put("summary", "Reset between p1 and p2: " + p.embeddingText());
        finding.put("evidence", List.of(vendorRef));
        List<Object> findings = new ArrayList<>((List<Object>) report.getOrDefault("findings", List.of()));
        findings.add(finding);
        report.put("findings", findings);
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
                throw new IllegalStateException("planted IPv6 literal did not parse", e);
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
            for (Pattern pattern : forms(entry.getKey(), entry.getValue())) {
                Matcher m = pattern.matcher(body);
                while (m.find()) n++;
            }
            if (n > 0) found.merge(entry.getValue(), n, Integer::sum);
        }
        return found;
    }

    /**
     * The text of a response body: the bytes as UTF-8 and, when the body is a zip container (an XLSX workbook, a CSV
     * archive), the decoded text of every entry, nested containers included.
     */
    static String textOf(byte[] body) throws IOException {
        StringBuilder out = new StringBuilder(new String(body, StandardCharsets.UTF_8));
        if (body.length > 4 && body[0] == 'P' && body[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    out.append('\n').append(entry.getName()).append('\n').append(textOf(zip.readAllBytes()));
                }
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** 30 s on a workstation; a shared runner sets UI2_TEST_HTTP_TIMEOUT_S (30..600), as the other sweep does. */
    static final Duration REQUEST_TIMEOUT = requestTimeout(System.getenv("UI2_TEST_HTTP_TIMEOUT_S"));

    static Duration requestTimeout(String seconds) {
        if (seconds == null || seconds.isBlank()) return Duration.ofSeconds(30);
        int value = Integer.parseInt(seconds.strip());
        if (value < 30 || value > 600) throw new IllegalArgumentException("UI2_TEST_HTTP_TIMEOUT_S outside 30..600");
        return Duration.ofSeconds(value);
    }

    record Resp(int status, String contentType, String maskedHeader, byte[] bytes) {
        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Resp get(String target, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json, text/plain, */*");
        if (cookie != null) request.header("Cookie", "ui2_session=" + cookie);
        HttpResponse<byte[]> response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Resp(response.statusCode(), response.headers().firstValue("content-type").orElse(""),
                response.headers().firstValue("x-nexus-masked").orElse(""), response.body());
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

    // ------------------------------------------------------------------ requests

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** The concrete request (path and query) of each swept route, built so the handler reaches the seeded rows. */
    private static Map<String, String> requests(String firstFindingId, String flowId) {
        String c = BASE + "/cases/" + caseId + "/";
        Map<String, String> out = new LinkedHashMap<>();
        out.put("state", c + "state");
        out.put("flows", c + "flows");
        out.put("frames", c + "frames?point=p1&flow=" + enc(flowId) + "&frames=1&frames=4&frames=7");
        out.put("diagnosis", c + "diagnosis?flow=" + enc(flowId));
        out.put("charts", c + "charts?flow=" + enc(flowId));
        out.put("ladder", c + "ladder?flow=" + enc(flowId));
        out.put("waterfall", c + "waterfall?flow=" + enc(flowId));
        out.put("timeseries", c + "timeseries");
        out.put("topology/overlay", c + "topology/overlay");
        out.put("topology/inspect", c + "topology/inspect?point=p1");
        out.put("findings", c + "findings");
        out.put("events", c + "events");
        out.put("dns", c + "dns");
        out.put("field-diff", c + "field-diff");
        out.put("export/findings", c + "export/findings");
        out.put("export/download", c + "export/download?format=csv");
        out.put("export/expert-report", c + "export/expert-report");
        out.put("sessions/evidence", c + "sessions/evidence?point=p1&start=1700000000&end=1700000002");
        out.put("reference/template", c + "reference/template");
        out.put("correlation", c + "correlation");
        out.put("device-events", c + "device-events" + (firstFindingId == null ? "" : "?finding=" + enc(firstFindingId)));
        return out;
    }

    private static List<AiviewReachableRoutes.Endpoint> mappedCaseDetailGets() throws Exception {
        List<AiviewReachableRoutes.Endpoint> out = new ArrayList<>();
        for (var e : AiviewReachableRoutes.all()) {
            if (e.method().equals("GET") && e.pathTemplate().startsWith(CASE_PREFIX)) out.add(e);
        }
        return out;
    }

    /** The routes the sweep claims to cover are exactly the routes the controllers map under /cases/{caseId}/. */
    @Test
    void theSweptRoutesAreTheMappedRoutes() throws Exception {
        Set<String> mapped = new TreeSet<>();
        for (var e : mappedCaseDetailGets()) mapped.add(e.pathTemplate().substring(CASE_PREFIX.length()));
        assertEquals(new TreeSet<>(CASE_DETAIL_ROUTES), mapped,
                "GET routes under /cases/{caseId}/ mapped by the controllers versus the routes this sweep reads");
        assertEquals(CASE_DETAIL_ROUTES, requests(null, "flow").keySet(), "every swept route has a request");
        // the refused routes are exactly the ones the static gate withholds from aiview
        Set<String> notReachable = new TreeSet<>();
        for (var e : mappedCaseDetailGets()) {
            if (e.actionId() == null || !AiviewReachableRoutes.aiviewMayCall(e.actionId())) {
                notReachable.add(e.pathTemplate().substring(CASE_PREFIX.length()));
            }
        }
        assertEquals(new TreeSet<>(REFUSED_BY_DESIGN.keySet()), notReachable, "routes aiview may not call, by the gate");
        assertFalse(AiviewReachableRoutes.aiviewMayCall(ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
                "aiview may never hold " + ActionRegistry.PACKET_ANALYSIS_RAW_VIEW);
    }

    // ------------------------------------------------------------------ the sweep

    /** At least one non-empty array or object member under the named top-level keys: the route answered with seeded rows. */
    private static boolean hasRows(JsonNode body, String... keys) {
        for (String key : keys) {
            JsonNode node = body.path(key);
            if (node.isArray() && node.size() > 0) return true;
            if (node.isObject() && node.size() > 0) return true;
            if (node.isNumber() && node.asLong() > 0) return true;
        }
        return false;
    }

    /** What each JSON route must show for the answer to count as covered (seeded rows, not an empty page). */
    private static final Map<String, String[]> COVERAGE_KEYS = Map.ofEntries(
            Map.entry("state", new String[] {"report", "captures"}),
            Map.entry("flows", new String[] {"items"}),
            Map.entry("frames", new String[] {"items"}),
            Map.entry("diagnosis", new String[] {"items", "point_metrics"}),
            Map.entry("charts", new String[] {"packets", "buckets"}),
            Map.entry("ladder", new String[] {"items"}),
            Map.entry("waterfall", new String[] {"items"}),
            Map.entry("timeseries", new String[] {"items", "metrics"}),
            Map.entry("topology/overlay", new String[] {"segments", "nodes"}),
            Map.entry("topology/inspect", new String[] {"findings", "flows"}),
            Map.entry("findings", new String[] {"items"}),
            Map.entry("events", new String[] {"items"}),
            Map.entry("dns", new String[] {"items"}),
            Map.entry("export/findings", new String[] {"findings", "report"}),
            Map.entry("sessions/evidence", new String[] {"evidence"}),
            Map.entry("reference/template", new String[] {"items"}),
            Map.entry("correlation", new String[] {"hops", "flows"}),
            Map.entry("device-events", new String[] {"findings", "devices"}));

    @Test
    void noPlantedRawValueSurvivesInAnyAiviewCaseDetailRoute() throws Exception {
        String cookie = loginAsAiview();
        // The case itself answers for aiview (the case DuckDB views below are its detail).
        Resp head = get(BASE + "/cases/" + caseId, cookie);
        assertEquals(200, head.status(), "aiview reads the seeded case");
        assertTrue(JSON.readTree(head.bytes()).path("permissions").path("masked").asBoolean(false),
                "case permissions say the view is masked");

        assertPlantedValuesAreInTheCaseSource();

        String findingId = null;
        Resp findings = get(requests(null, null).get("findings"), cookie);
        if (findings.status() == 200) {
            JsonNode items = JSON.readTree(findings.bytes()).path("items");
            if (items.isArray() && items.size() > 0) findingId = items.get(0).path("id").asText(null);
        }

        String flowId = null;
        Resp flows = get(requests(null, null).get("flows"), cookie);
        if (flows.status() == 200) {
            for (JsonNode item : JSON.readTree(flows.bytes()).path("items")) {
                if (flowId == null && item.path("tuple").asText().startsWith("[\"TCP\"") && !item.path("tuple").asText().contains("REDACTED")
                        && item.path("flow").isTextual()) {
                    flowId = item.path("flow").asText();
                }
            }
        }
        Map<String, String> requests = requests(findingId, flowId == null ? "unresolved" : flowId);
        List<String> problems = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Map<String, Map<ValueClass, Integer>> survivors = new TreeMap<>();
        for (var entry : requests.entrySet()) {
            String route = entry.getKey();
            Resp response = get(entry.getValue(), cookie);
            String text = textOf(response.bytes());
            Map<ValueClass, Integer> found = count(text, planted.raw());
            String line = "GET /cases/{caseId}/" + route + " -> " + response.status() + " bytes=" + response.bytes().length
                    + " survivors=" + found;
            lines.add(line);
            if (!found.isEmpty()) {
                survivors.put(route, found);
                problems.add(route + ": planted raw values survive in the aiview body " + found);
            }
            if (REFUSED_BY_DESIGN.containsKey(route)) {
                if (response.status() != 403) problems.add(route + ": expected 403 (" + REFUSED_BY_DESIGN.get(route) + ") but got " + response.status());
                continue;
            }
            if (response.status() != 200) {
                problems.add(route + ": answered " + response.status() + " instead of 200 (seeding gap): " + abbreviate(response.text()));
                continue;
            }
            if (response.bytes().length == 0) {
                problems.add(route + ": empty body");
                continue;
            }
            if (route.equals("dns") && !Pattern.compile("HOST-[0-9A-F]{10}").matcher(text).find()) {
                problems.add(route + ": the DNS question carries no HOST- token (the masked projection did not run on it)");
            }
            if (route.equals("export/expert-report")) {
                if (!"true".equals(response.maskedHeader())) problems.add(route + ": the masked header is not set for aiview");
                if (!text.contains("xl/")) problems.add(route + ": the workbook has no sheet entries to scan");
                continue;
            }
            JsonNode json = JSON.readTree(response.bytes());
            String[] keys = COVERAGE_KEYS.get(route);
            if (keys == null) {
                problems.add(route + ": no coverage rule");
            } else if (!hasRows(json, keys)) {
                problems.add(route + ": answered 200 without seeded rows (looked at " + String.join("/", keys) + "): "
                        + abbreviate(response.text()));
            }
        }
        lines.forEach(System.out::println);
        System.out.println("aiview PA case-detail sweep: " + requests.size() + " routes, survivors by route " + survivors);
        assertEquals(List.of(), problems, "aiview case-detail routes: coverage and surviving planted raw values");
    }

    /**
     * The control for the zeros: every planted value class really sits in the case DuckDB the routes read, so "no
     * survivors" is a count over rows that carried the values (the case title lives in PostgreSQL, the file name in the
     * case DuckDB's capture inventory).
     */
    private static void assertPlantedValuesAreInTheCaseSource() throws Exception {
        Path dbPath = PacketAnalysisPaths.projectDuckDb(Path.of(context.getEnvironment().getRequiredProperty("ui2.packet-analysis.root")), caseId);
        StringBuilder source = new StringBuilder();
        try (Connection conn = Engine.openReadOnly(dbPath); var st = conn.createStatement()) {
            for (String sql : List.of("SELECT coalesce(string_agg(value, ' '), '') FROM settings",
                    "SELECT coalesce(string_agg(coalesce(vendor, '') || ' ' || coalesce(path_fields, '') || ' ' || tuple_key, ' '), '') FROM packets",
                    "SELECT coalesce(string_agg(name, ' '), '') FROM captures")) {
                try (var rs = st.executeQuery(sql)) {
                    while (rs.next()) source.append(rs.getString(1)).append('\n');
                }
            }
        }
        Map<ValueClass, Integer> present = count(source.toString(), planted.raw());
        for (ValueClass cls : List.of(ValueClass.HOSTNAME, ValueClass.ADDRESS, ValueClass.SERIAL, ValueClass.PRINCIPAL, ValueClass.FILE_NAME)) {
            assertTrue(present.getOrDefault(cls, 0) > 0, "the case DuckDB holds no planted " + cls + " value: " + present);
        }
    }

    /** The first 160 characters of an answer that failed a coverage check, to see why (error codes carry no planted value). */
    private static String abbreviate(String body) {
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }

    /** The counter itself: every spelling it claims to catch is caught, and a longer address is not a match. */
    @Test
    void theCounterSeesEverySpellingAndNoPrefixMatch() throws Exception {
        Map<String, ValueClass> raw = Map.of("192.0.2.15", ValueClass.ADDRESS, "2001:db8:a1::7", ValueClass.ADDRESS,
                "ist" + "x0a0b0c-fw-02", ValueClass.HOSTNAME);
        assertEquals(Map.of(ValueClass.ADDRESS, 2), count("[\"192.0.2.15\",\"192-0-2-15\",\"192.0.2.150\"]", raw));
        assertEquals(Map.of(ValueClass.ADDRESS, 3),
                count("2001:DB8:A1::7 2001:0db8:00a1:0000:0000:0000:0000:0007 2001:db8:a1:0:0:0:0:7 2001:db8:a1::70", raw));
        assertEquals(Map.of(ValueClass.HOSTNAME, 1), count("seen on IST" + "X0A0B0C-FW-02 today", raw));
        assertEquals(Map.of(), count("{\"hostname\":\"FW-TANGO-04\"}", raw));
        // a zip container is read entry by entry
        var buffer = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zip.write("<si><t>192.0.2.15</t></si>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertEquals(Map.of(ValueClass.ADDRESS, 1), count(textOf(buffer.toByteArray()), raw));
    }
}
