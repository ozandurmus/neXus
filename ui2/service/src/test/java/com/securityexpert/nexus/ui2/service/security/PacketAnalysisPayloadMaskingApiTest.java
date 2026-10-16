package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseRunner;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.packetanalysis.privacy.PayloadFields;
import com.securityexpert.nexus.ui2.packetanalysis.privacy.PayloadFields.Field;
import com.securityexpert.nexus.ui2.packetanalysis.privacy.PayloadMasker;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Py;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Topology;
import com.securityexpert.nexus.ui2.persistence.identity.*;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaptureRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.CaseRow;
import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository.RunRow;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.service.api.PacketAnalysisController;
import com.securityexpert.nexus.ui2.service.packetanalysis.PacketCaptureStore;
import com.securityexpert.nexus.ui2.service.privacy.*;

/**
 * Payload-derived text in the masked projection, end to end through the real gate chain, on a synthetic case: the
 * engine runs on seeded packets (a TCP flow, a DNS exchange, F5 trailer metadata with an HTTP request) and the stored
 * report then carries synthetic proxy, TLS, SIP and F5 sections in the engine's own shapes. Every value derived from
 * payload is a distinctive {@code sentinel} string; none of them may reach the replay viewer (aiview) or a run role
 * without the raw-view action, while the raw-view role (security_admin) still reads them.
 *
 * <p>Fail closed: every string field of every raw response must have a classification in {@link PayloadFields}, and
 * every string the replay viewer receives is either a classified safe field or a masked value.</p>
 */
class PacketAnalysisPayloadMaskingApiTest {

    private static final String CASE = "21111111-2222-4333-8444-555555555555";
    private static final String JOB = "cbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
    private static final String BASE = "/api/v2/packet-analysis";
    private static final String PRIVATE_FILE = "synthetic-customer-core-fw.pcap";
    private static final ObjectMapper JSON = new ObjectMapper();

    // Payload-derived sentinels (all synthetic; documentation names and addresses only).
    private static final String HTTP_LINE = "GET /sentinel-path/sentinel-user-4711?sentinel-query=1 HTTP/1.1";
    private static final String HTTP_ABSOLUTE = "POST http://shared.example.test:8080/sentinel-login/form HTTP/1.1";
    private static final String SNI = "sentinel-sni.example.test";
    private static final String SHARED_HOST = "shared.example.test";
    private static final String CALL_ID = "sentinel-call-id-77@example.test";
    private static final String CONTACT = "<sip:sentinel-user@example.test>;expires=60";
    private static final String VIA = "SIP/2.0/UDP sentinel-via.example.test:5060;branch=z9hG4bKsentinel";
    private static final String CERT_SUBJECT = "2.5.4.3=sentinel-cert.example.test";
    private static final String CERT_ISSUER = "2.5.4.3=sentinel-ca.example.test";
    private static final String VIP = "/Common/sentinel-vip";
    private static final String RST_TEXT = "[0x1a2b] sentinel reset cause text";
    private static final String VENDOR_URI = "/sentinel-vendor-uri/item";
    private static final String DNS_QUESTION = "sentinel-dns.example.test";
    // Capture-derived names (masked for the replay viewer only, like device names).
    private static final String FORTI_SOURCE = "forti-name-source.txt";
    private static final String FORTI_IFACE = "forti-name-port7";
    private static final List<String> RAW_IPS = List.of("192.0.2.10", "192.0.2.20", "192.0.2.53");

    @TempDir Path root;
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final PacketAnalysisRepository repository = mock(PacketAnalysisRepository.class);
    private final Map<String, Set<String>> roles = Map.of(
            "synthetic-operator", Set.of(RoleToken.OPERATOR),
            "synthetic-secadmin", Set.of(RoleToken.SECURITY_ADMIN),
            "synthetic-aiview", Set.of(RoleToken.REPLAY_VIEWER));
    private PacketCaptureStore store;
    private MockMvc mvc;
    private String flowTuple;

    @BeforeEach
    void setUp() throws Exception {
        store = new PacketCaptureStore(root, 4096, 64 * 1024 * 1024);
        Instant now = Instant.now();
        for (String actor : roles.keySet()) {
            var session = new SessionRecord(SessionHasher.hash("cookie-" + actor), actor, "csrf-" + actor, SessionState.ACTIVE,
                    now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty(), false);
            when(sessions.findBySessionId(eq(session.sessionId()), any())).thenReturn(Optional.of(session));
        }
        var permitted = new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty());
        var denied = new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty());
        when(rbac.evaluate(anyString(), any(), any())).thenAnswer(call -> {
            Optional<?> token = call.getArgument(1);
            return token.isPresent() && roles.getOrDefault(call.getArgument(0), Set.of()).contains(token.get()) ? permitted : denied;
        });
        when(rbac.evaluateAny(anyString(), anySet(), any())).thenAnswer(call -> {
            Set<?> wanted = call.getArgument(1);
            return roles.getOrDefault(call.getArgument(0), Set.of()).stream().anyMatch(wanted::contains) ? permitted : denied;
        });
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var names = new TopologyNamePseudonymizer(new byte[32]);
        mvc = MockMvcBuilders.standaloneSetup(new PacketAnalysisController(repository, store, rbac, names))
                .setControllerAdvice(new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), names))
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();

        CaseRow row = new CaseRow(CASE, "synthetic-payload-case", "synthetic-operator", now, now.plusSeconds(7 * 86400),
                "OPEN", 3048, false, 1, JOB);
        when(repository.findCase(CASE)).thenReturn(Optional.of(row));
        when(repository.captures(CASE)).thenReturn(List.of(new CaptureRow("66666666-7777-4888-9999-bbbbbbbbbbbb", CASE,
                PRIVATE_FILE, "pcap", 3048, "a".repeat(64), "x.pcap", "synthetic-operator", now)));
        RunRow run = new RunRow(JOB, CASE, "COMPLETED", "synthetic-operator", now, now, now, "engine-1",
                "{\"verdict\":\"unknown\",\"capture_count\":2}", null);
        when(repository.runs(CASE)).thenReturn(List.of(run));
        when(repository.findRun(JOB)).thenReturn(Optional.of(run));
        seedCase();
    }

    // ---- every endpoint, three projections -----------------------------------------------------------------

    @Test void noPayloadSentinelReachesAiviewOrRunRolesWhileRawViewKeepsThem() throws Exception {
        for (String endpoint : endpoints()) {
            String raw = body(endpoint, "synthetic-secadmin");
            String operator = body(endpoint, "synthetic-operator");
            String aiview = body(endpoint, "synthetic-aiview");
            assertNoSentinel(operator, "operator " + endpoint);
            assertNoSentinel(aiview, "aiview " + endpoint);
            for (String ip : RAW_IPS) assertFalse(aiview.contains(ip), "aiview address " + ip + " on " + endpoint);
            assertFalse(aiview.contains(FORTI_SOURCE) || aiview.contains(FORTI_IFACE), "aiview Fortinet names on " + endpoint);
            assertFalse(aiview.contains(PRIVATE_FILE), "aiview capture file name on " + endpoint);
            if (raw.contains("sentinel")) {
                assertTrue(operator.matches("(?s).*(HOST|PATH|CALL|SIP|CERT|VTXT|VNAME)-[0-9A-F]{10}.*"),
                        "operator sees tokens where the raw view has payload on " + endpoint);
            }
        }
        // The raw-view role reads the raw values (state carries every injected section).
        String rawState = body(BASE + "/cases/" + CASE + "/state", "synthetic-secadmin");
        for (String sentinel : List.of(HTTP_LINE, SNI, CALL_ID, CERT_SUBJECT, VIP, RST_TEXT, FORTI_SOURCE)) {
            assertTrue(rawState.contains(sentinel), "raw view keeps " + sentinel);
        }
    }

    @Test void maskedValuesKeepStructureAndRelationships() throws Exception {
        String operatorState = body(BASE + "/cases/" + CASE + "/state", "synthetic-operator");
        Map<String, Object> state = JSON.readValue(operatorState, new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) state.get("report");
        @SuppressWarnings("unchecked")
        var txns = (List<Map<String, Object>>) ((Map<String, Object>) report.get("proxies")).get("transactions");
        String line = (String) txns.get(0).get("request");
        assertTrue(line.matches("GET /PATH-[0-9A-F]{10}/PATH-[0-9A-F]{10}\\?QUERY-[0-9A-F]{10} HTTP/1\\.1"), line);
        assertTrue(PayloadMasker.isMasked(line), line);
        String sniToken = (String) txns.get(1).get("request");
        assertTrue(sniToken.matches("HOST-[0-9A-F]{10}"), sniToken);
        @SuppressWarnings("unchecked")
        var f5Requests = (List<Map<String, Object>>) ((Map<String, Object>) report.get("f5")).get("requests");
        String absolute = (String) f5Requests.get(0).get("request");
        assertTrue(absolute.matches("POST http://HOST-[0-9A-F]{10}:8080/PATH-[0-9A-F]{10}/PATH-[0-9A-F]{10} HTTP/1\\.1"), absolute);
        @SuppressWarnings("unchecked")
        var sip = (Map<String, Object>) report.get("sip");
        @SuppressWarnings("unchecked")
        var comparison = ((List<Map<String, Object>>) sip.get("comparisons")).get(0);
        String callToken = (String) comparison.get("call_id");
        assertTrue(callToken.matches("CALL-[0-9A-F]{10}"), callToken);
        @SuppressWarnings("unchecked")
        var changes = (Map<String, Map<String, Object>>) comparison.get("changes");
        assertTrue(((String) changes.get("sip.Contact").get("before")).startsWith("SIP-"));
        assertEquals("49170", changes.get("sdp.media.port").get("before"), "numbers stay");
        assertEquals("changed", comparison.get("status"), "enum codes stay");

        // Same input, same token: the finding id, the evidence protocol identity and the Wireshark filter name the
        // Call-ID through the same token, and the shared host is one token in the TLS section, the F5 request and DNS.
        @SuppressWarnings("unchecked")
        var findings = (List<Map<String, Object>>) report.get("findings");
        Map<String, Object> media = findings.stream().filter(f -> "one_way_audio".equals(f.get("type"))).findFirst().orElseThrow();
        assertTrue(((String) media.get("id")).contains(callToken), String.valueOf(media.get("id")));
        String evidence = JSON.writeValueAsString(media.get("evidence"));
        assertTrue(evidence.contains("sip.Call-ID == \\\"" + callToken + "\\\""), evidence);
        String hostToken = absolute.substring(absolute.indexOf("HOST-"), absolute.indexOf("HOST-") + 15);
        @SuppressWarnings("unchecked")
        var tls = (List<Map<String, Object>>) report.get("tls");
        assertEquals(hostToken, tls.get(1).get("sni"), "case-insensitive host: same token across HTTP and TLS");
        assertEquals(hostToken, JSON.readTree(body(BASE + "/cases/" + CASE + "/state", "synthetic-aiview"))
                .at("/report/tls/1/sni").asText(), "aiview keeps the same host token (no second masking)");
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(operatorState).get("report");
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(
                body(BASE + "/cases/" + CASE + "/state", "synthetic-operator")).get("report");
        first.remove("nexus_correlation"); // carries its own correlation time
        second.remove("nexus_correlation");
        assertEquals(first, second, "deterministic");
    }

    @Test void dnsQuestionsAreTokenizedOnTheDnsTab() throws Exception {
        String dns = BASE + "/cases/" + CASE + "/dns";
        String raw = body(dns, "synthetic-secadmin");
        String operator = body(dns, "synthetic-operator");
        String aiview = body(dns, "synthetic-aiview");
        assertTrue(raw.contains(DNS_QUESTION), "the engine produced the synthetic DNS transaction");
        String token = new TopologyNamePseudonymizer(new byte[32]).maskPayloadToken("HOST", DNS_QUESTION);
        assertTrue(operator.contains(token) && aiview.contains(token), "DNS question token on the DNS tab");
        assertFalse(operator.contains(DNS_QUESTION));
        assertFalse(aiview.contains(DNS_QUESTION));
    }

    @Test void fortinetImportNamesAreMaskedForTheReplayViewerOnly() throws Exception {
        String state = BASE + "/cases/" + CASE + "/state";
        String operator = body(state, "synthetic-operator");
        assertTrue(operator.contains(FORTI_SOURCE) && operator.contains(FORTI_IFACE), "names stay for run roles");
        var aiview = JSON.readTree(body(state, "synthetic-aiview"));
        assertEquals(PayloadMasker.REDACTED_PATH, aiview.at("/fortinet_conversion/files/0/path").asText());
        assertTrue(aiview.at("/fortinet_conversion/files/0/interface").asText().matches("OBJ-[A-Z]+-\\d+"),
                aiview.at("/fortinet_conversion/files/0/interface").asText());
    }

    @Test void runRolesReadTheFullReportPayloadMaskedAndTheViewIsStillLedgered() throws Exception {
        String path = BASE + "/runs/" + JOB + "/report";
        String operator = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projection").value("full"))
                .andExpect(jsonPath("$.payload_masked").value(true))
                .andReturn().getResponse().getContentAsString();
        assertNoSentinel(operator, "operator report");
        String raw = mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-secadmin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payload_masked").value(false))
                .andReturn().getResponse().getContentAsString();
        assertTrue(raw.contains(CALL_ID));
        verify(repository).recordAccess(eq(CASE), isNull(), eq(JOB), eq("REPORT_VIEWED"), eq("synthetic-operator"), anyLong());
        verify(repository).recordAccess(eq(CASE), isNull(), eq(JOB), eq("REPORT_VIEWED"), eq("synthetic-secadmin"), anyLong());
        // aiview still gets only the stored masked summary.
        mvc.perform(get(path).servletPath(path).cookie(cookie("synthetic-aiview")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projection").value("masked"))
                .andExpect(jsonPath("$.report").doesNotExist());
    }

    @Test void rawViewKeepsTheRawViewedLedgerOnFieldDiff() throws Exception {
        String diff = BASE + "/cases/" + CASE + "/field-diff";
        mvc.perform(get(diff).servletPath(diff).cookie(cookie("synthetic-secadmin"))).andExpect(status().isOk());
        verify(repository).recordAccess(eq(CASE), isNull(), eq(JOB), eq("RAW_VIEWED"), eq("synthetic-secadmin"), eq(0L));
        mvc.perform(get(diff).servletPath(diff).cookie(cookie("synthetic-operator"))).andExpect(status().isForbidden());
    }

    /**
     * The fail-closed gate. (1) Every string in every raw response sits under a classified key: a new engine field
     * fails here until it is added to {@link PayloadFields} (and the README inventory). (2) Every string the replay
     * viewer receives is a classified safe field (engine vocabulary, prose, filters, addresses after the IP masker)
     * or a masked value; a payload-class field never carries clear text.
     */
    @Test void everyStringIsClassifiedAndTheReplayViewerReceivesOnlySafeFieldsOrMaskedValues() throws Exception {
        Set<String> problems = new TreeSet<>();
        for (String endpoint : endpoints()) {
            walk(JSON.readValue(body(endpoint, "synthetic-secadmin"), Object.class), null, null, null, "raw " + endpoint,
                    (where, key, field, value) -> {
                        if (field == Field.UNCLASSIFIED) problems.add(where + ": unclassified key '" + key + "'");
                    });
            walk(JSON.readValue(body(endpoint, "synthetic-aiview"), Object.class), null, null, null, "aiview " + endpoint,
                    (where, key, field, value) -> {
                        boolean safe = field == Field.KEEP || field == Field.TEXT || field == Field.ADDRESS;
                        if (!safe && !PayloadMasker.isMasked(value) && !PSEUDONYM.matcher(value).matches())
                            problems.add(where + ": key '" + key + "' (" + field + ") is not masked");
                        if (value.contains("sentinel")) problems.add(where + ": key '" + key + "' carries payload text");
                    });
        }
        assertEquals(Set.of(), problems);
    }

    // ---- fixture -------------------------------------------------------------------------------------------

    private List<String> endpoints() {
        String c = BASE + "/cases/" + CASE;
        String flow = URLEncoder.encode(flowTuple, StandardCharsets.UTF_8);
        return List.of(c + "/state", c + "/flows", c + "/frames?point=p1&flow=" + flow + "&frames=1&frames=4&frames=7",
                c + "/diagnosis?flow=" + flow, c + "/charts?flow=" + flow, c + "/ladder?flow=" + flow,
                c + "/waterfall?flow=" + flow, c + "/timeseries", c + "/topology/overlay", c + "/topology/inspect?point=p1",
                c + "/findings", c + "/events", c + "/dns", c + "/export/findings",
                c + "/sessions/evidence?point=p1&start=1700000000&end=1700000002", c + "/reference/template",
                c + "/correlation");
    }

    private String body(String endpoint, String actor) throws Exception {
        String servletPath = endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf('?')) : endpoint;
        String body = mvc.perform(get(endpoint).servletPath(servletPath).cookie(cookie(actor)))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("CASE_DB_READ_FAILED") || body.contains("QUERY_INVALID"), endpoint + ": " + body);
        return body;
    }

    private static void assertNoSentinel(String body, String where) {
        assertFalse(body.contains("sentinel"), where + " carries payload-derived text: " + excerpt(body, "sentinel"));
        assertFalse(body.contains(SHARED_HOST), where + " carries a payload host");
    }

    private static String excerpt(String body, String needle) {
        int at = body.indexOf(needle);
        return at < 0 ? "" : body.substring(Math.max(0, at - 120), Math.min(body.length(), at + 80));
    }

    /** Pseudonyms of the name and IP maskers (device, interface, domain, redactions). */
    private static final Pattern PSEUDONYM = Pattern.compile(
            "(?:FW|CLS|DOM|VS|OBJ|ZONE|RULE)-[A-Z]+-[0-9]+|\\[REDACTED_[A-Z]+\\]|Unknown");

    private interface Visitor {
        void string(String where, String key, Field field, String value);
    }

    private static void walk(Object node, String key, String context, Map<?, ?> owner, String where, Visitor visitor) {
        if (node instanceof Map<?, ?> map) {
            String inner = PayloadFields.context(key, context);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                walk(e.getValue(), String.valueOf(e.getKey()), inner, map, where, visitor);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) walk(item, key, context, owner, where, visitor);
        } else if (node instanceof String s && !s.isEmpty()) {
            visitor.string(where, key, PayloadFields.field(key, context, owner), s);
        }
    }

    private void seedCase() throws Exception {
        Path caseDir = PacketAnalysisPaths.caseDir(root, CASE);
        var projectStore = ProjectStore.open(caseDir, ProjectStore.Settings.defaults(null));
        String invJson = "{\"packet_count\":9,\"records_read\":9,\"prefilter_excluded\":0,\"prefilter_skipped\":false}";
        String f5 = JSON.writeValueAsString(Map.of("adapter", "f5", "f5ethtrailer.vip", VIP, "f5ethtrailer.rstcausetxt",
                RST_TEXT, "f5ethtrailer.peeraddr", "192.0.2.20", "f5ethtrailer.flowid", "0x1a", "http.request.method",
                "GET", "http.request.uri", VENDOR_URI, "http.request.version", "HTTP/1.1"));
        try (var db = projectStore.connect()) {
            for (String cap : List.of("cap1", "cap2")) {
                db.execute("INSERT INTO captures (id, path, name, state, checkpoint, inventory, error) VALUES (?, ?, ?, 'ready', 9, ?, NULL)",
                        cap, "/synthetic/" + cap + ".pcap", cap.equals("cap1") ? PRIVATE_FILE : "synthetic-server-edge.pcap", invJson);
            }
            String insert = "INSERT INTO packets (capture_id, frame, ts, iface, src, dst, sport, dport, proto, ipid, "
                    + "seq, ack, flags, length, wirelen, caplen, prefix, payload_hash, signature, tuple_key, reverse_tuple, "
                    + "stream, ttl, dscp, mss, \"window\", retrans, fast_retrans, spurious, out_of_order, acked_unseen, "
                    + "zero_window, rtt, unsupported, frame_hash, icmp_id, icmp_seq, icmp_type, dns_id, dns_response, vendor, path_fields) "
                    + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, 1460, 65535, "
                    + "false, false, false, false, false, false, NULL, '', ?, NULL, NULL, NULL, ?, ?, ?, ?)";
            String fwd = "[\"TCP\",\"192.0.2.10\",40000,\"192.0.2.20\",80]";
            String rev = "[\"TCP\",\"192.0.2.20\",80,\"192.0.2.10\",40000]";
            String dnsFwd = "[\"UDP\",\"192.0.2.10\",53000,\"192.0.2.53\",53]";
            String dnsRev = "[\"UDP\",\"192.0.2.53\",53,\"192.0.2.10\",53000]";
            String plain = "{\"transport_index\":1,\"dns_index\":1}";
            String dnsQuery = "{\"transport_index\":1,\"dns_index\":1,\"dns_name\":\"" + DNS_QUESTION
                    + "\",\"dns_type\":1,\"dns_class\":1,\"dns_questions\":1,\"dns_rcode\":0}";
            // frame, ts1, ts2, src, dst, sport, dport, proto, ipid, seq, ack, flags, len, prefix, key, sig, tuple, rev, dnsId, dnsResp, vendor, path
            Object[][] rows = {
                {1L, 1700000000.100, 1700000000.105, "192.0.2.10", "192.0.2.20", 40000L, 80L, "TCP", 101L, 1000L, 0L, 2L, 0L, "", "", "sig1", fwd, rev, null, false, null, plain},
                {2L, 1700000000.120, 1700000000.115, "192.0.2.20", "192.0.2.10", 80L, 40000L, "TCP", 201L, 2000L, 1001L, 18L, 0L, "", "", "sig2", rev, fwd, null, false, null, plain},
                {3L, 1700000000.130, 1700000000.135, "192.0.2.10", "192.0.2.20", 40000L, 80L, "TCP", 102L, 1001L, 2001L, 16L, 0L, "", "", "sig3", fwd, rev, null, false, null, plain},
                {4L, 1700000001.100, 1700000001.105, "192.0.2.10", "192.0.2.20", 40000L, 80L, "TCP", 103L, 1001L, 2001L, 24L, 18L, "474554202f", "p1", "sig4", fwd, rev, null, false, f5, plain},
                {5L, 1700000001.150, 1700000001.145, "192.0.2.20", "192.0.2.10", 80L, 40000L, "TCP", 202L, 2001L, 1019L, 16L, 0L, "", "", "sig5", rev, fwd, null, false, null, plain},
                {6L, 1700000001.200, 1700000001.195, "192.0.2.20", "192.0.2.10", 80L, 40000L, "TCP", 203L, 2001L, 1019L, 24L, 15L, "485454502f", "p2", "sig6", rev, fwd, null, false, f5, plain},
                {7L, 1700000001.300, 1700000001.305, "192.0.2.10", "192.0.2.53", 53000L, 53L, "UDP", 104L, 0L, 0L, 0L, 30L, "", "d1", "sig7", dnsFwd, dnsRev, 4660L, false, null, dnsQuery},
                {8L, 1700000001.320, 1700000001.315, "192.0.2.53", "192.0.2.10", 53L, 53000L, "UDP", 204L, 0L, 0L, 0L, 46L, "", "d2", "sig8", dnsRev, dnsFwd, 4660L, true, null, dnsQuery},
                {9L, 1700000001.400, 1700000001.405, "192.0.2.10", "192.0.2.20", 40000L, 80L, "TCP", 105L, 1019L, 2016L, 17L, 0L, "", "", "sig9", fwd, rev, null, false, null, plain}
            };
            for (Object[] p : rows) {
                long len = (Long) p[12];
                for (String cap : List.of("cap1", "cap2")) {
                    boolean first = cap.equals("cap1");
                    db.execute(insert, cap, p[0], first ? p[1] : p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11],
                            len, 54L + len, 54L + len, p[13], p[14], p[15], p[16], p[17], first ? 64L : 63L, "h" + p[0],
                            p[18], p[19], p[20], p[21]);
                }
            }
        }
        var top = new Topology();
        top.points.add(Point.of("p1", PRIVATE_FILE, PRIVATE_FILE, "cap1", Py.i(0), Py.i(0)));
        top.points.add(Point.of("p2", "synthetic-server-edge.pcap", "synthetic-server-edge.pcap", "cap2", Py.i(240), Py.i(0)));
        top.forward = List.of("p1", "p2");
        top.reverse = List.of("p2", "p1");
        top.clientCidrs = List.of("192.0.2.10/32");
        CaseRunner.runEngineOnTopology(projectStore.path(), top.dump());
        flowTuple = "[\"TCP\",\"192.0.2.10\",40000,\"192.0.2.20\",80]";

        // The engine's application sections need tshark; add them in the engine's own shapes.
        Path dbPath = PacketAnalysisPaths.projectDuckDb(root, CASE);
        Map<String, Object> report;
        try (var conn = Engine.open(dbPath); var db = new Db(conn)) {
            Object[] stored = db.one("SELECT value FROM settings WHERE key='report'");
            report = JSON.readValue((String) stored[0], new TypeReference<LinkedHashMap<String, Object>>() { });
            injectApplicationSections(report);
            db.exec("INSERT OR REPLACE INTO settings VALUES ('report', ?)", JSON.writeValueAsString(report));
            db.exec("INSERT OR REPLACE INTO settings VALUES ('fortinet_conversion', ?)", JSON.writeValueAsString(Map.of(
                    "source", FORTI_SOURCE, "packets", 3, "skipped_packets", 0, "skipped_lines", 0,
                    "skipped_reasons", Map.of(), "lines", 12,
                    "files", List.of(Map.of("path", "/analysis/cases/" + CASE + "/fortinet/" + FORTI_IFACE + ".pcap",
                            "interface", FORTI_IFACE, "packets", 3, "adapter", "fortinet", "link_type", 1,
                            "cooked_metadata", false, "clock_group", "fortinet", "clock_confidence", "source",
                            "relative_timestamps", false)))));
        }
        Path reportFile = store.reportFile(CASE, JOB);
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, JSON.writeValueAsString(report));
    }

    @SuppressWarnings("unchecked")
    private void injectApplicationSections(Map<String, Object> report) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("point", "p1");
        ref.put("file", PRIVATE_FILE);
        ref.put("capture_id", "cap1");
        ref.put("frame", 4);
        ref.put("display_filter", "frame.number == 4");
        ref.put("observed_time", 1700000001.1);
        ref.put("corrected_time", 1700000001.1);
        ref.put("content_filter", "(ip.src == 192.0.2.10 && ip.dst == 192.0.2.20 && tcp) && tcp.len == 18");
        ref.put("flow_filter", "((ip.src == 192.0.2.10 && ip.dst == 192.0.2.20))");
        ref.put("filter_note", "Content filters survive frame renumbering.");
        ref.put("tunnel", null);
        ref.put("fragment", null);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("status", "matched");
        http.put("client_id", "c-1");
        http.put("server_id", "s-1");
        http.put("reason", null);
        http.put("evidence_rank", "HTTP request line + Host");
        http.put("xff_confirmed", true);
        http.put("device", "synthetic-proxy");
        http.put("point_a", "p1");
        http.put("point_b", "p2");
        http.put("kind", "http");
        http.put("request", HTTP_LINE);
        http.put("client_flow", flowTuple);
        http.put("server_flow", flowTuple);
        http.put("time", 1700000001.1);
        http.put("evidence", List.of(ref));
        Map<String, Object> tlsPair = new LinkedHashMap<>(http);
        tlsPair.put("kind", "tls");
        tlsPair.put("evidence_rank", "TLS SNI");
        tlsPair.put("request", SNI);
        report.put("proxies", new LinkedHashMap<>(Map.of("transactions", List.of(http, tlsPair),
                "note", "Full proxies link requests, never TCP packets.")));

        Map<String, Object> tlsNote = new LinkedHashMap<>();
        tlsNote.put("status", "unknown");
        tlsNote.put("point", "p1");
        tlsNote.put("sni", SNI);
        tlsNote.put("reason", "TLS 1.3 certificates encrypted; interception unknown");
        tlsNote.put("evidence", List.of(ref));
        Map<String, Object> tlsShared = new LinkedHashMap<>(tlsNote);
        tlsShared.put("sni", "Shared.Example.Test");
        tlsShared.put("status", "consistent");
        report.put("tls", List.of(tlsNote, tlsShared));

        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("fingerprint", "ab".repeat(32));
        cert.put("subject", CERT_SUBJECT);
        cert.put("issuer", CERT_ISSUER);
        Map<String, Object> tlsFinding = finding("forward:p1:p2:tls_interception", "tls_interception",
                "TLS intercepted by synthetic-proxy (not declared)");
        tlsFinding.put("metrics", new LinkedHashMap<>(Map.of("sni", SNI, "before_chain", List.of(cert),
                "after_chain", List.of(cert), "declared", false)));
        tlsFinding.put("evidence", List.of(ref));

        Map<String, Object> sipRef = new LinkedHashMap<>(ref);
        sipRef.put("content_filter", "udp && sip.Call-ID == \"" + CALL_ID + "\"");
        sipRef.put("protocol_identity", Map.of("sip.CSeq.seq", 1, "sip.Call-ID", CALL_ID));
        Map<String, Object> media = finding("media:one_way_audio:forward:p1:p2:" + flowTuple + ":" + CALL_ID + ":1700000001.0",
                "one_way_audio", "One-way audio: media stops between p1 and p2");
        media.put("metrics", new LinkedHashMap<>(Map.of("call_id", CALL_ID, "flow", flowTuple, "reason", "RTP stops")));
        media.put("evidence", List.of(sipRef));
        List<Object> findings = new ArrayList<>((List<Object>) report.getOrDefault("findings", List.of()));
        findings.add(tlsFinding);
        findings.add(media);
        report.put("findings", findings);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sip.Contact", Map.of("before", CONTACT, "after", "<sip:sentinel-user@192.0.2.20>"));
        changes.put("sip.Via", Map.of("before", VIA, "after", "SIP/2.0/UDP 192.0.2.20:5060;branch=z9hG4bKsentinel2"));
        changes.put("sdp.connection_info.address", Map.of("before", "192.0.2.10", "after", "192.0.2.20"));
        changes.put("sdp.media.port", Map.of("before", "49170", "after", "49172"));
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("call_id", CALL_ID);
        comparison.put("flow", flowTuple);
        comparison.put("hop", "forward:p1:p2");
        comparison.put("device", "synthetic-proxy");
        comparison.put("direction", "forward");
        comparison.put("changes", changes);
        comparison.put("status", "changed");
        comparison.put("reason", "Unique message and reliable segment");
        comparison.put("evidence", List.of(sipRef));
        comparison.put("tooltip", "SIP message identity includes canonical signaling flow, Call-ID, CSeq.");
        comparison.put("media_status", "unknown");
        comparison.put("media_reason", "No unique actual media source at receiving endpoint");
        Map<String, Object> oneWay = new LinkedHashMap<>();
        oneWay.put("call_id", CALL_ID);
        oneWay.put("hop", "forward:p1:p2");
        oneWay.put("reason", "RTP stops");
        oneWay.put("evidence", List.of(sipRef));
        report.put("sip", new LinkedHashMap<>(Map.of("comparisons", List.of(comparison), "total", 1,
                "one_way", List.of(oneWay), "note", "SIP note", "scope", "Selected-window comparisons")));

        Map<String, Object> vendorRef = new LinkedHashMap<>(ref);
        vendorRef.put("vendor", Map.of("adapter", "f5", "f5ethtrailer.vip", VIP, "f5ethtrailer.rstcausetxt", RST_TEXT,
                "f5ethtrailer.peeraddr", "192.0.2.20", "http.request.uri", VENDOR_URI, "fw1.interface", "sentinel-eth1"));
        Map<String, Object> f5Request = new LinkedHashMap<>();
        f5Request.put("device", "synthetic-proxy");
        f5Request.put("request", HTTP_ABSOLUTE);
        f5Request.put("client_flow", flowTuple);
        f5Request.put("server_flow", flowTuple);
        f5Request.put("flowid", "0x1a");
        f5Request.put("peerid", "0x2b");
        f5Request.put("request_dwell_ms", 1.5);
        f5Request.put("clock_uncertainty_ms", 0);
        f5Request.put("reason", null);
        f5Request.put("evidence", List.of(vendorRef));
        f5Request.put("note", "Same-capture interval between tshark-decoded request-bearing frames");
        Map<String, Object> reset = new LinkedHashMap<>();
        reset.put("device", "synthetic-proxy");
        reset.put("flow", flowTuple);
        reset.put("time", 1700000001.2);
        reset.put("reason", RST_TEXT);
        reset.put("evidence", List.of(vendorRef));
        report.put("f5", new LinkedHashMap<>(Map.of("pairs", List.of(), "requests", List.of(f5Request),
                "resets", List.of(reset), "connection_count", 1, "note", "Overview shows up to 100 connection pairs")));
        report.put("application_status", List.of(Map.of("point", "p1", "reason", "tshark unavailable", "cached", false)));
    }

    private static Map<String, Object> finding(String id, String type, String summary) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("type", type);
        f.put("hop", "forward:p1:p2");
        f.put("device", "synthetic-proxy");
        f.put("direction", "forward");
        f.put("severity", "high");
        f.put("summary", summary);
        return f;
    }

    private static Cookie cookie(String actor) {
        return new Cookie("ui2_session", "cookie-" + actor);
    }

    @SuppressWarnings("unused")
    private static MockHttpServletRequestBuilder write(String method, String path, String actor) {
        return request(org.springframework.http.HttpMethod.valueOf(method), path).servletPath(path).cookie(cookie(actor))
                .header("X-CSRF-Token", "csrf-" + actor).header("Origin", "https://example.invalid");
    }
}
