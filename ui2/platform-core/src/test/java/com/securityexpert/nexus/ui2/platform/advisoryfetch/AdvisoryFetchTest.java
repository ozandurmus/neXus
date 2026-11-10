package com.securityexpert.nexus.ui2.platform.advisoryfetch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The outbound advisory fetch workload (docs/design/ADVISORY_MATCHING_CONTRACT.md section 3): only the listed public
 * addresses are ever requested, follow-up addresses are taken only from the allow-list, a certificate failure stops the
 * source, and the package can reach nothing but the JDK.
 */
class AdvisoryFetchTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    // ------------------------------------------------------------------------------------------------ allow-list

    @Test
    void onlyTheListedHostsAndPathPrefixesAreAllowed() {
        for (AdvisorySources.Source source : AdvisorySources.ALL) {
            assertTrue(AdvisorySources.allowed(source, source.startUri()), source.key());
        }
        assertTrue(AdvisorySources.allowed(AdvisorySources.FORTINET, URI.create("https://filestore.fortinet.com/fortiguard/psirt/csaf_x_fg-ir-26-150.json")));
        assertTrue(AdvisorySources.allowed(AdvisorySources.CISCO, URI.create("https://www.cisco.com/.well-known/csaf/2026/cisco-sa-asaftd-x.json")));
        for (String refused : List.of(
                "http://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories",
                "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/11.1/advisories",
                "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories?version=11.1",
                "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories/extra",
                "https://security.paloaltonetworks.com:8443/api/v1/products/PAN-OS/advisories",
                "https://user@security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories",
                "https://security.paloaltonetworks.com.example.org/api/v1/products/PAN-OS/advisories",
                "https://example.org/api/v1/products/PAN-OS/advisories",
                "https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories#x")) {
            assertFalse(AdvisorySources.allowed(AdvisorySources.PALOALTO, URI.create(refused)), refused);
        }
        for (String refused : List.of(
                "https://filestore.fortinet.com/fortiguard/psirt/",
                "https://filestore.fortinet.com/fortiguard/psirt/../secret.json",
                "https://filestore.fortinet.com/fortiguard/psirt//x.json",
                "https://filestore.fortinet.com/fortiguard/psirt/%2e%2e/x.json",
                "https://filestore.fortinet.com/other/x.json",
                "https://filestore.fortinet.com/fortiguard/psirt/x.json?device=1",
                "https://www.cisco.com/fortiguard/psirt/x.json")) {
            assertFalse(AdvisorySources.allowed(AdvisorySources.FORTINET, URI.create(refused)), refused);
        }
        assertFalse(AdvisorySources.allowed(AdvisorySources.CISCO, URI.create("https://www.cisco.com/.well-known/csaf/" + "a".repeat(300) + ".json")));
        assertFalse(AdvisorySources.allowed(null, AdvisorySources.CISCO.startUri()));
        assertFalse(AdvisorySources.allowed(AdvisorySources.CISCO, null));
        assertTrue(AdvisorySources.byKey("cisa_kev").isPresent());
        assertTrue(AdvisorySources.byKey("checkpoint").isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ follow-ups

    @Test
    void theFortinetFeedYieldsOnlyItsOwnCsafAddresses() {
        String feed = "<rss><channel>"
                + "<item><csaf:link>https://filestore.fortinet.com/fortiguard/psirt/csaf_a_fg-ir-26-150.json</csaf:link></item>"
                + "<item><csaf:link> https://filestore.fortinet.com/fortiguard/psirt/csaf_b_fg-ir-26-151.json </csaf:link></item>"
                + "<item><csaf:link>https://filestore.fortinet.com/fortiguard/psirt/csaf_a_fg-ir-26-150.json</csaf:link></item>"
                + "<item><csaf:link>https://example.org/fortiguard/psirt/csaf_c.json</csaf:link></item>"
                + "<item><csaf:link>https://filestore.fortinet.com/fortiguard/psirt/csaf_d.json?x=1</csaf:link></item>"
                + "<item><csaf:link>https://filestore.fortinet.com/fortiguard/psirt/page.html</csaf:link></item>"
                + "<item><cvrf:link>https://fortiguard.fortinet.com/psirt/cvrf/FG-IR-26-150</cvrf:link></item>"
                + "</channel></rss>";
        assertEquals(List.of(URI.create("https://filestore.fortinet.com/fortiguard/psirt/csaf_a_fg-ir-26-150.json"),
                URI.create("https://filestore.fortinet.com/fortiguard/psirt/csaf_b_fg-ir-26-151.json")),
                FollowUps.fortinet(feed.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(), FollowUps.fortinet(null));
        assertEquals(List.of(), FollowUps.fortinet("not a feed".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theCiscoChangeListYieldsTheFilesChangedInsideTheWindow() {
        String changes = "2026/cisco-sa-new-one.json,2026-11-09T10:00:00Z\n"
                + "\"2026/cisco-sa-quoted.json\",\"2026-11-08T10:00:00Z\"\r\n"
                + "2026/cisco-sa-new-one.json,2026-11-07T10:00:00Z\n"
                + "2026/cisco-sa-old.json,2026-10-01T10:00:00Z\n"
                + "../../etc/passwd,2026-11-09T10:00:00Z\n"
                + "2026/other-file.json,2026-11-09T10:00:00Z\n"
                + "2026/cisco-sa-bad-date.json,yesterday\n"
                + "https://example.org/2026/cisco-sa-x.json,2026-11-09T10:00:00Z\n";
        assertEquals(List.of(URI.create("https://www.cisco.com/.well-known/csaf/2026/cisco-sa-new-one.json"),
                URI.create("https://www.cisco.com/.well-known/csaf/2026/cisco-sa-quoted.json")),
                FollowUps.cisco(changes.getBytes(StandardCharsets.UTF_8), NOW.minusSeconds(7 * 86400)));
        assertEquals(3, FollowUps.cisco(changes.getBytes(StandardCharsets.UTF_8), NOW.minusSeconds(100 * 86400L)).size());
        assertEquals(List.of(), FollowUps.cisco(null, NOW));
        assertTrue(FollowUps.namesCiscoAsa("{\"name\":\"Cisco Secure Firewall Adaptive Security Appliance (ASA) Software\"}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FollowUps.namesCiscoAsa("{\"name\":\"Cisco IOS XE Software\"}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FollowUps.namesCiscoAsa(null));
    }

    // ------------------------------------------------------------------------------------------------ the run

    /** Scripted ports: bodies by address, failures by address, every call recorded. */
    private static final class Ports implements AdvisoryFetchRun.Outbound, AdvisoryFetchRun.Ingest {
        final Map<String, byte[]> bodies = new HashMap<>();
        final Map<String, String> failures = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<String> handedOver = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        boolean acceptDocuments = true;

        @Override
        public AdvisoryFetchRun.Fetched get(AdvisorySources.Source source, URI uri) {
            requested.add(uri.toString());
            assertTrue(AdvisorySources.allowed(source, uri), "requested an address outside the list: " + uri);
            String failure = failures.get(uri.toString());
            if (failure != null) {
                return AdvisoryFetchRun.Fetched.failed(failure);
            }
            byte[] body = bodies.get(uri.toString());
            return body == null ? AdvisoryFetchRun.Fetched.failed("HTTP_404") : AdvisoryFetchRun.Fetched.ok(body);
        }

        @Override
        public boolean document(String source, String runId, URI readFrom, byte[] body) {
            handedOver.add(source + " " + readFrom);
            return acceptDocuments;
        }

        @Override
        public boolean complete(String source, String runId, int fetched, int failed, String reason) {
            completed.add(source + " fetched=" + fetched + " failed=" + failed + " reason=" + reason);
            return true;
        }
    }

    private static AdvisoryFetchRun run(Ports ports, List<String> log) {
        return new AdvisoryFetchRun(ports, ports, log::add, NOW, 7);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aSingleDocumentSourceIsFetchedOnceAndHandedOver() {
        Ports ports = new Ports();
        ports.bodies.put(AdvisorySources.PALOALTO.startUri().toString(), bytes("{\"success\":true,\"data\":[]}"));
        List<String> log = new ArrayList<>();
        AdvisoryFetchRun.SourceResult result = run(ports, log).run(AdvisorySources.PALOALTO, "run-00000001");
        assertTrue(result.ok());
        assertEquals(List.of(AdvisorySources.PALOALTO.startUri().toString()), ports.requested);
        assertEquals(List.of("paloalto " + AdvisorySources.PALOALTO.startUri()), ports.handedOver);
        assertEquals(List.of("paloalto fetched=1 failed=0 reason=null"), ports.completed);
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("[ADVISORY_FETCH] source=paloalto fetched=1 failed=0 handed_over=1 dropped=0 reason=none"), log.get(0));
    }

    @Test
    void theFortinetFeedIsFollowedAndOnlyItsCsafFilesAreHandedOver() {
        Ports ports = new Ports();
        String a = "https://filestore.fortinet.com/fortiguard/psirt/csaf_a.json";
        String b = "https://filestore.fortinet.com/fortiguard/psirt/csaf_b.json";
        ports.bodies.put(AdvisorySources.FORTINET.startUri().toString(), bytes("<rss><csaf:link>" + a + "</csaf:link><csaf:link>" + b
                + "</csaf:link><csaf:link>https://example.org/x.json</csaf:link></rss>"));
        ports.bodies.put(a, bytes("{}"));
        AdvisoryFetchRun.SourceResult result = run(ports, new ArrayList<>()).run(AdvisorySources.FORTINET, "run-00000001");
        assertEquals(List.of(AdvisorySources.FORTINET.startUri().toString(), a, b), ports.requested);
        assertEquals(List.of("fortinet " + a), ports.handedOver, "the feed itself is not handed over; the missing file is counted");
        assertEquals(new AdvisoryFetchRun.SourceResult("fortinet", 2, 1, 1, 0, null), result);
        assertFalse(result.ok());
        assertEquals(List.of("fortinet fetched=2 failed=1 reason=null"), ports.completed);
    }

    @Test
    void ciscoFilesThatDoNotNameTheAsaFamilyAreDroppedUnread() {
        Ports ports = new Ports();
        String asa = "https://www.cisco.com/.well-known/csaf/2026/cisco-sa-asa-x.json";
        String ios = "https://www.cisco.com/.well-known/csaf/2026/cisco-sa-ios-y.json";
        ports.bodies.put(AdvisorySources.CISCO.startUri().toString(), bytes("2026/cisco-sa-asa-x.json,2026-11-09T10:00:00Z\n"
                + "2026/cisco-sa-ios-y.json,2026-11-09T09:00:00Z\n2025/cisco-sa-old-z.json,2025-01-01T00:00:00Z\n"));
        ports.bodies.put(asa, bytes("{\"n\":\"Cisco Secure Firewall " + FollowUps.CISCO_ASA_FAMILY + "\"}"));
        ports.bodies.put(ios, bytes("{\"n\":\"Cisco IOS XE Software\"}"));
        AdvisoryFetchRun.SourceResult result = run(ports, new ArrayList<>()).run(AdvisorySources.CISCO, "run-00000001");
        assertEquals(List.of(AdvisorySources.CISCO.startUri().toString(), asa, ios), ports.requested, "the old file is outside the window");
        assertEquals(List.of("cisco " + asa), ports.handedOver);
        assertEquals(new AdvisoryFetchRun.SourceResult("cisco", 3, 0, 1, 1, null), result);
        assertTrue(result.ok());
    }

    @Test
    void aCertificateFailureStopsTheSourceAtOnce() {
        Ports ports = new Ports();
        String a = "https://filestore.fortinet.com/fortiguard/psirt/csaf_a.json";
        String b = "https://filestore.fortinet.com/fortiguard/psirt/csaf_b.json";
        ports.bodies.put(AdvisorySources.FORTINET.startUri().toString(), bytes("<csaf:link>" + a + "</csaf:link><csaf:link>" + b + "</csaf:link>"));
        ports.failures.put(a, AdvisoryFetchRun.TLS_VALIDATION_FAILED);
        ports.bodies.put(b, bytes("{}"));
        AdvisoryFetchRun.SourceResult result = run(ports, new ArrayList<>()).run(AdvisorySources.FORTINET, "run-00000001");
        assertEquals(List.of(AdvisorySources.FORTINET.startUri().toString(), a), ports.requested, "nothing more is requested after the failure");
        assertEquals(List.of(), ports.handedOver);
        assertEquals("TLS_VALIDATION_FAILED", result.reason());
        assertEquals(List.of("fortinet fetched=1 failed=1 reason=TLS_VALIDATION_FAILED"), ports.completed);

        Ports start = new Ports();
        start.failures.put(AdvisorySources.CISA_KEV.startUri().toString(), AdvisoryFetchRun.TLS_VALIDATION_FAILED);
        AdvisoryFetchRun.SourceResult failedStart = run(start, new ArrayList<>()).run(AdvisorySources.CISA_KEV, "run-00000001");
        assertEquals(new AdvisoryFetchRun.SourceResult("cisa_kev", 0, 1, 0, 0, "TLS_VALIDATION_FAILED"), failedStart);
        assertEquals(List.of(), start.handedOver);
    }

    @Test
    void aRefusedHandOverStopsTheSourceAndIsReported() {
        Ports ports = new Ports();
        ports.acceptDocuments = false;
        ports.bodies.put(AdvisorySources.CISA_KEV.startUri().toString(), bytes("{}"));
        AdvisoryFetchRun.SourceResult result = run(ports, new ArrayList<>()).run(AdvisorySources.CISA_KEV, "run-00000001");
        assertEquals("INGEST_REFUSED", result.reason());
        assertFalse(result.ok());
        assertEquals(List.of("cisa_kev fetched=1 failed=0 reason=INGEST_REFUSED"), ports.completed);
    }

    // ------------------------------------------------------------------------------------------------ settings

    private static Map<String, String> settings() {
        Map<String, String> env = new HashMap<>();
        env.put("UI2_ADVISORY_PROXY", "proxy.example.test:3128");
        env.put("UI2_ADVISORY_FETCH_TOKEN", "synthetic-" + "ingest-" + "value-" + "0".repeat(12));
        return env;
    }

    @Test
    void withoutAProxyOrATokenNothingStarts() {
        List<String> log = new ArrayList<>();
        assertEquals(2, AdvisoryFetchMain.run(key -> null, log::add));
        assertEquals(List.of("[ADVISORY_FETCH] not started: UI2_ADVISORY_PROXY is not set"), log);
        Map<String, String> noToken = settings();
        noToken.remove("UI2_ADVISORY_FETCH_TOKEN");
        assertNotNull(AdvisoryFetchMain.Settings.from(noToken::get).problem());
        Map<String, String> shortToken = settings();
        shortToken.put("UI2_ADVISORY_FETCH_TOKEN", "short");
        assertNotNull(AdvisoryFetchMain.Settings.from(shortToken::get).problem());
        for (String badProxy : List.of("proxy.example.test", "proxy.example.test:0", "proxy.example.test:abc", "bad host:3128", ":3128")) {
            Map<String, String> env = settings();
            env.put("UI2_ADVISORY_PROXY", badProxy);
            assertNotNull(AdvisoryFetchMain.Settings.from(env::get).problem(), badProxy);
        }
    }

    @Test
    void settingsHaveSafeDefaultsAndRefuseAnythingElse() {
        AdvisoryFetchMain.Settings defaults = AdvisoryFetchMain.Settings.from(settings()::get);
        assertNull(defaults.problem());
        assertEquals("proxy.example.test", defaults.proxyHost());
        assertEquals(3128, defaults.proxyPort());
        assertEquals(AdvisorySources.ALL, defaults.sources());
        assertEquals(7, defaults.ciscoDays());
        assertEquals("ui2-service-internal.ui2.svc.cluster.local", defaults.ingest().getHost());

        Map<String, String> env = settings();
        env.put("UI2_ADVISORY_PROXY", "http://proxy.example.test:3128/");
        env.put("UI2_ADVISORY_SOURCES", "cisco, paloalto,cisco");
        env.put("UI2_ADVISORY_CISCO_DAYS", "1100");
        AdvisoryFetchMain.Settings chosen = AdvisoryFetchMain.Settings.from(env::get);
        assertNull(chosen.problem());
        assertEquals(List.of(AdvisorySources.CISCO, AdvisorySources.PALOALTO), chosen.sources());
        assertEquals(1100, chosen.ciscoDays());
        assertEquals("proxy.example.test", chosen.proxyHost());

        for (Map.Entry<String, String> bad : Map.of("UI2_ADVISORY_SOURCES", "paloalto,checkpoint", "UI2_ADVISORY_CISCO_DAYS", "0",
                "UI2_ADVISORY_INGEST_URL", "https://example.org").entrySet()) {
            Map<String, String> broken = settings();
            broken.put(bad.getKey(), bad.getValue());
            assertNotNull(AdvisoryFetchMain.Settings.from(broken::get).problem(), bad.getKey());
        }
        assertTrue(AdvisoryFetchMain.runId().matches("[A-Za-z0-9_-]{8,64}"));
    }

    // ------------------------------------------------------------------------------------------------ transport

    @Test
    void theTransportRefusesAnAddressOutsideTheListBeforeAnyConnection() {
        // The proxy address is unusable on purpose: a refused address must return before a connection is tried.
        HttpTransport transport = new HttpTransport("localhost", 1, URI.create("http://localhost:1"), "t".repeat(24));
        AdvisoryFetchRun.Fetched refused = transport.get(AdvisorySources.PALOALTO, URI.create("https://example.org/api/v1/products/PAN-OS/advisories"));
        assertEquals("ADDRESS_NOT_ALLOWED", refused.failure());
        assertEquals("ADDRESS_NOT_ALLOWED", transport.get(AdvisorySources.CISCO, AdvisorySources.PALOALTO.startUri()).failure());
    }

    @Test
    void failureWordsAreCodeWordsTheRunRecordAccepts() {
        assertEquals("IO_HTTPCONNECTTIMEOUTEXCEPTION", HttpTransport.codeWord("IO_", new java.net.http.HttpConnectTimeoutException("x")));
        assertEquals("ERROR_ILLEGALSTATEEXCEPTION", HttpTransport.codeWord("ERROR_", new IllegalStateException("never the message")));
        assertTrue(HttpTransport.codeWord("IO_", new java.nio.channels.UnresolvedAddressException()).matches("[A-Z0-9_]{1,40}"));
        for (String word : List.of(AdvisoryFetchRun.TLS_VALIDATION_FAILED, "ADDRESS_NOT_ALLOWED", "DOCUMENT_TOO_LARGE", "HTTP_403",
                "INGEST_REFUSED", "NO_DOCUMENTS_NAMED", "INTERRUPTED")) {
            assertTrue(word.matches("[A-Z0-9_]{1,40}"), word);
        }
    }

    @Test
    void theSizeCapStopsAnOversizedDocument() throws IOException {
        assertArrayEquals(new byte[] {1, 2, 3}, HttpTransport.readCapped(new ByteArrayInputStream(new byte[] {1, 2, 3})));
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return length;
            }
        };
        assertNull(HttpTransport.readCapped(endless));
    }

    @Test
    void handOverGoesDirectlyToTheIngestPortWithTheTokenTheAddressAndTheRun() throws IOException {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " token=" + exchange.getRequestHeaders().getFirst(HttpTransport.TOKEN_HEADER)
                    + " url=" + exchange.getRequestHeaders().getFirst(HttpTransport.URL_HEADER)
                    + " run=" + exchange.getRequestHeaders().getFirst(HttpTransport.RUN_HEADER)
                    + " body=" + new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().contains("fortinet") ? 403 : 204, -1);
            exchange.close();
        });
        server.start();
        try {
            String token = "synthetic-" + "ingest-" + "value-" + "1".repeat(12);
            // The proxy is unusable on purpose: ingest must not go through it.
            HttpTransport transport = new HttpTransport("localhost", 1, URI.create("http://localhost:" + server.getAddress().getPort()), token);
            assertTrue(transport.document("paloalto", "run-00000001", AdvisorySources.PALOALTO.startUri(), "{}".getBytes(StandardCharsets.UTF_8)));
            assertTrue(transport.complete("paloalto", "run-00000001", 1, 0, null));
            assertTrue(transport.complete("paloalto", "run-00000001", 1, 1, "bad reason \" with quote"));
            assertTrue(transport.complete("paloalto", "run-00000001", 1, 1, "lower_case_word"));
            assertFalse(transport.document("fortinet", "run-00000001", AdvisorySources.FORTINET.startUri(), "{}".getBytes(StandardCharsets.UTF_8)));
            assertEquals("POST /internal/advisories/paloalto token=" + token + " url=" + AdvisorySources.PALOALTO.startUri()
                    + " run=run-00000001 body={}", seen.get(0));
            assertEquals("POST /internal/advisories/paloalto/complete token=" + token
                    + " url=null run=run-00000001 body={\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}", seen.get(1));
            assertTrue(seen.get(2).endsWith("\"reason\":null}"), "a reason that is not a code word is not sent: " + seen.get(2));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------------------------------------ the fence

    @Test
    void thePackageImportsOnlyTheJdk() throws IOException {
        Path sources = Path.of("src/main/java/com/securityexpert/nexus/ui2/platform/advisoryfetch");
        try (Stream<Path> files = Files.list(sources)) {
            List<Path> java = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertTrue(java.size() >= 5);
            for (Path file : java) {
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("import ")) {
                        assertTrue(line.startsWith("import java.") || line.startsWith("import javax.net.ssl."),
                                file.getFileName() + " imports outside the JDK: " + line);
                    }
                    assertFalse(line.contains("TrustManager") || line.contains("HostnameVerifier") || line.contains("sslContext("),
                            file.getFileName() + " must not customise certificate validation: " + line);
                }
            }
        }
    }
}
