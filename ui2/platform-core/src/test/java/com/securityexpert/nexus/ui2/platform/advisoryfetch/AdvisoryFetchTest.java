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
import java.time.Duration;
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
        assertTrue(AdvisorySources.byKey("checkpoint").isPresent(), "amendment A-2026-11-13");
        assertTrue(AdvisorySources.byKey("infoblox").isEmpty());
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
    void theCiscoLifecycleSourceReadsBothListingsThenTheEnglishBulletinsAndHandsThemToTheLifecycleEndpoint() {
        String asa = "https://www.cisco.com" + AdvisorySources.CISCO_EOL_ASA_LISTING;
        String firepower = "https://www.cisco.com" + AdvisorySources.CISCO_EOL_FIREPOWER_LISTING;
        String b1 = "https://www.cisco.com/c/en/us/products/collateral/security/asa-firepower-services/asa-9-18-eol.html";
        String b2 = "https://www.cisco.com/c/en/us/products/collateral/security/firepower-ngfw/firepower-4100-series-eol.html";
        Ports ports = new Ports();
        ports.bodies.put(asa, bytes("<a href=\"/c/en/us/products/collateral/security/asa-firepower-services/asa-9-18-eol.html\">9.18</a>"
                + "<a href=\"/c/en/us/products/collateral/security/asa-firepower-services/asa-9-18-eol-fr.html\">fr</a>"
                + "<a href=\"/c/en/us/products/collateral/routers/800-series-routers/eol_C51-730374.html\">old</a>"
                + "<a href=\"https://www.example.org/c/en/us/products/collateral/security/x/y.html\">elsewhere</a>"));
        ports.bodies.put(firepower, bytes("<a href=\"https://www.cisco.com/c/en/us/products/collateral/security/firepower-ngfw/firepower-4100-series-eol.html\">"
                + "<a href=\"/c/en/us/products/collateral/security/asa-firepower-services/asa-9-18-eol.html\">again</a>"));
        ports.bodies.put(b1, bytes("<html>bulletin one</html>"));
        ports.bodies.put(b2, bytes("<html>bulletin two</html>"));
        var result = run(ports, new ArrayList<>()).run(AdvisorySources.CISCO_EOL, "run-00000001");
        assertTrue(result.ok());
        assertEquals(List.of(asa, firepower, b1, b2), ports.requested);
        assertEquals(List.of("cisco_eol " + b1, "cisco_eol " + b2), ports.handedOver, "listings are not handed over, bulletins are");
        assertEquals(List.of("cisco_eol fetched=4 failed=0 reason=null"), ports.completed);
        assertEquals("/internal/lifecycle-sources/cisco_eol", AdvisorySources.ingestPath("cisco_eol"));
        assertEquals("/internal/advisories/cisco", AdvisorySources.ingestPath("cisco"));
    }

    @Test
    void theCheckPointAndPaloAltoLifecyclePagesAreExactAddressesHandedToTheLifecycleEndpoint() {
        String cp = "https://www.checkpoint.com" + AdvisorySources.CHECKPOINT_LIFECYCLE_PAGE;
        String paSoftware = "https://www.paloaltonetworks.com" + AdvisorySources.PALOALTO_EOL_SOFTWARE;
        String paHardware = "https://www.paloaltonetworks.com" + AdvisorySources.PALOALTO_EOL_HARDWARE;
        Ports ports = new Ports();
        ports.bodies.put(cp, bytes("<html>cp</html>"));
        ports.bodies.put(paSoftware, bytes("<html>sw</html>"));
        ports.bodies.put(paHardware, bytes("<html>hw</html>"));
        assertTrue(run(ports, new ArrayList<>()).run(AdvisorySources.CHECKPOINT_LIFECYCLE, "run-00000001").ok());
        assertTrue(run(ports, new ArrayList<>()).run(AdvisorySources.PALOALTO_EOL, "run-00000001").ok());
        assertEquals(List.of(cp, paSoftware, paHardware), ports.requested);
        assertEquals(List.of("checkpoint_lifecycle " + cp, "paloalto_eol " + paSoftware, "paloalto_eol " + paHardware), ports.handedOver);
        assertEquals("/internal/lifecycle-sources/checkpoint_lifecycle", AdvisorySources.ingestPath("checkpoint_lifecycle"));
        assertEquals("/internal/lifecycle-sources/paloalto_eol", AdvisorySources.ingestPath("paloalto_eol"));
        for (String refused : List.of("https://www.checkpoint.com/support-services/", "https://www.checkpoint.com/support-services/support-life-cycle-policy",
                "https://www.checkpoint.com/support-services/support-life-cycle-policy/x", "https://www.checkpoint.com/")) {
            assertFalse(AdvisorySources.allowed(AdvisorySources.CHECKPOINT_LIFECYCLE, URI.create(refused)), refused);
        }
        for (String refused : List.of("https://www.paloaltonetworks.com/services/support/end-of-life-announcements/end-of-life-policy",
                "https://www.paloaltonetworks.com/services/support/end-of-life-announcements/", "https://www.paloaltonetworks.com" + AdvisorySources.PALOALTO_EOL_SOFTWARE + "?x=1",
                "https://security.paloaltonetworks.com" + AdvisorySources.PALOALTO_EOL_SOFTWARE)) {
            assertFalse(AdvisorySources.allowed(AdvisorySources.PALOALTO_EOL, URI.create(refused)), refused);
        }
        assertFalse(AdvisorySources.allowed(AdvisorySources.PALOALTO, URI.create(paSoftware)), "the advisory source keeps its own host only");
    }

    @Test
    void ciscoLifecycleListingsThatNameNoBulletinAreAReasonNotAFreshSource() {
        Ports ports = new Ports();
        ports.bodies.put("https://www.cisco.com" + AdvisorySources.CISCO_EOL_ASA_LISTING, bytes("<html>moved</html>"));
        var result = run(ports, new ArrayList<>()).run(AdvisorySources.CISCO_EOL, "run-00000001");
        assertFalse(result.ok());
        assertEquals("NO_DOCUMENTS_NAMED", result.reason());
        assertFalse(AdvisorySources.allowed(AdvisorySources.CISCO_EOL, URI.create("https://www.cisco.com/c/en/us/products/security/other-listing.html")));
        assertFalse(AdvisorySources.allowed(AdvisorySources.CISCO_EOL, URI.create("https://www.cisco.com/c/en/us/products/collateral/security/")));
        assertTrue(AdvisorySources.allowed(AdvisorySources.CISCO_EOL,
                URI.create("https://www.cisco.com/c/en/us/products/collateral/security/firepower-ngfw/x.html")));
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

    // ------------------------------------------------------------------------------------------------ Check Point

    private static final String SC1 = "https://sc1.checkpoint.com";
    private static final String CVELIST = "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/";

    @Test
    void checkPointRequestsOnlyItsFourPagesAndTheCveRecordsOfThePublicList() {
        for (String path : AdvisorySources.CHECKPOINT_JUMBO_PAGES) {
            assertTrue(AdvisorySources.allowed(AdvisorySources.CHECKPOINT, URI.create(SC1 + path)), path);
        }
        assertTrue(AdvisorySources.allowed(AdvisorySources.CHECKPOINT, URI.create(CVELIST + "2026/85xxx/CVE-2026-85103.json")));
        for (String refused : List.of(
                SC1 + "/documents/Jumbo_HFA/R81.10/All_Resolved_Issues.html",
                SC1 + "/documents/Jumbo_HFA/R82/All_Resolved_Issues.html?take=1",
                SC1 + "/documents/",
                "https://support.checkpoint.com/results/sk/sk185114",
                "https://iapi-services-ucs.checkpoint.com/public/api/support-center-mms/api/securityAdvisories/getAllActive",
                "https://raw.githubusercontent.com/someone/else/main/cves/2026/85xxx/CVE-2026-85103.json",
                "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/",
                "https://cveawg.mitre.org/api/cve/CVE-2026-85103",
                "http://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/85xxx/CVE-2026-85103.json")) {
            assertFalse(AdvisorySources.allowed(AdvisorySources.CHECKPOINT, URI.create(refused)), refused);
        }
        // Another source's hosts never reach the Check Point list, and its hosts never reach another source.
        assertFalse(AdvisorySources.allowed(AdvisorySources.PALOALTO, URI.create(CVELIST + "2026/85xxx/CVE-2026-85103.json")));
    }

    @Test
    void theCveRecordAddressIsBuiltFromTheIdAloneInTheListsLayout() {
        List<URI> records = FollowUps.checkpointRecords(List.of(
                bytes("<td>UPDATE: Resolved CVE-2026-85103 and CVE-2025-0060. Refer to sk1000001.</td>"),
                bytes("<a href=\"https://www.cve.org/CVERecord?id=CVE-2026-120490\">CVE-2026-120490</a> CVE-2026-85103 XCVE-2026-1111")));
        assertEquals(List.of(URI.create(CVELIST + "2026/85xxx/CVE-2026-85103.json"), URI.create(CVELIST + "2025/0xxx/CVE-2025-0060.json"),
                URI.create(CVELIST + "2026/120xxx/CVE-2026-120490.json")), records);
        assertEquals(List.of(), FollowUps.checkpointRecords(List.of(bytes("no ids here"))));
    }

    @Test
    void checkPointHandsOverEveryPageBeforeTheRecordsTheyName() {
        Ports ports = new Ports();
        List<String> pages = AdvisorySources.CHECKPOINT_JUMBO_PAGES.stream().map(p -> SC1 + p).toList();
        ports.bodies.put(pages.get(0), bytes("<td>UPDATE: Resolved CVE-2026-85103.</td>"));
        ports.bodies.put(pages.get(1), bytes("<td>UPDATE: Resolved CVE-2026-85103 and CVE-2026-1234.</td>"));
        ports.failures.put(pages.get(2), "HTTP_404");
        ports.bodies.put(pages.get(3), bytes("<td>nothing</td>"));
        String first = CVELIST + "2026/85xxx/CVE-2026-85103.json";
        String second = CVELIST + "2026/1xxx/CVE-2026-1234.json";
        ports.bodies.put(first, bytes("{}"));
        ports.bodies.put(second, bytes("{}"));
        AdvisoryFetchRun.SourceResult result = run(ports, new ArrayList<>()).run(AdvisorySources.CHECKPOINT, "run-00000001");
        assertEquals(List.of(pages.get(0), pages.get(1), pages.get(2), pages.get(3), first, second), ports.requested);
        assertEquals(List.of("checkpoint " + pages.get(0), "checkpoint " + pages.get(1), "checkpoint " + pages.get(3),
                "checkpoint " + first, "checkpoint " + second), ports.handedOver, "pages first, the missing page counted");
        assertEquals(new AdvisoryFetchRun.SourceResult("checkpoint", 5, 1, 5, 0, null), result);
        assertEquals(List.of("checkpoint fetched=5 failed=1 reason=null"), ports.completed);
    }

    @Test
    void aCertificateFailureStopsCheckPointAtOnce() {
        Ports ports = new Ports();
        List<String> pages = AdvisorySources.CHECKPOINT_JUMBO_PAGES.stream().map(p -> SC1 + p).toList();
        ports.bodies.put(pages.get(0), bytes("<td>UPDATE: Resolved CVE-2026-85103.</td>"));
        ports.failures.put(pages.get(1), AdvisoryFetchRun.TLS_VALIDATION_FAILED);
        AdvisoryFetchRun.SourceResult result = run(ports, new ArrayList<>()).run(AdvisorySources.CHECKPOINT, "run-00000001");
        assertEquals(List.of(pages.get(0), pages.get(1)), ports.requested, "no record is requested after the failure");
        assertEquals("TLS_VALIDATION_FAILED", result.reason());

        Ports none = new Ports();
        AdvisoryFetchRun.SourceResult nothing = run(none, new ArrayList<>()).run(AdvisorySources.CHECKPOINT, "run-00000001");
        assertEquals("NO_PAGE_READ", nothing.reason());
        assertEquals(List.of(), none.handedOver);
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

        for (Map.Entry<String, String> bad : Map.of("UI2_ADVISORY_SOURCES", "paloalto,infoblox", "UI2_ADVISORY_CISCO_DAYS", "0",
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

    @Test
    void transientIngestRefusalDuringDeployOverlapRetriesWithBoundedBackoffAndSucceeds() throws IOException {
        assertEquals(5, AdvisoryFetchRun.DEFAULT_INGEST_MAX_ATTEMPTS);
        assertEquals(Duration.ofMinutes(5),
                AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF.stream().reduce(Duration.ZERO, Duration::plus),
                "5 tries over 5 minutes total backoff");

        List<String> seen = new ArrayList<>();
        List<Integer> ingestStatuses = new ArrayList<>(List.of(503, 429, 204));
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            seen.add(exchange.getRequestMethod() + " " + path + " body=" + new String(body, StandardCharsets.UTF_8));
            int status = path.endsWith("/complete") ? 204 : ingestStatuses.remove(0);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            String token = "synthetic-" + "ingest-" + "value-" + "2".repeat(12);
            List<Duration> waits = new ArrayList<>();
            HttpTransport transport = new HttpTransport("localhost", 1,
                    URI.create("http://localhost:" + server.getAddress().getPort()), token,
                    AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits::add);
            Ports outbound = new Ports();
            outbound.bodies.put(AdvisorySources.CISA_KEV.startUri().toString(), bytes("{\"vulnerabilities\":[]}"));
            List<String> log = new ArrayList<>();
            AdvisoryFetchRun fetchRun = new AdvisoryFetchRun(outbound, transport, log::add, NOW, 7,
                    AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits::add);

            AdvisoryFetchRun.SourceResult result = fetchRun.run(AdvisorySources.CISA_KEV, "run-00000002");

            assertTrue(result.ok());
            assertNull(result.reason());
            assertEquals(1, result.fetched());
            assertEquals(0, result.failed());
            assertEquals(1, result.handedOver());
            assertEquals(3, result.attempts());
            assertEquals(3, result.ingestAttempts());
            assertEquals(List.of(Duration.ofSeconds(20), Duration.ofSeconds(40)), waits);
            assertEquals(4, seen.size(), "3 document attempts + 1 complete call");
            assertEquals("POST /internal/advisories/cisa_kev/complete body={\"documents_fetched\":1,\"documents_failed\":0,\"reason\":null}",
                    seen.get(3));
            assertEquals(List.of("[ADVISORY_FETCH] source=cisa_kev fetched=1 failed=0 handed_over=1 dropped=0 reason=none attempts=3"), log);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transientIngestRefusalExhaustsBoundedRetriesAndRecordsAttemptsAndReason() throws IOException {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            seen.add(exchange.getRequestMethod() + " " + path + " body=" + new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(path.endsWith("/complete") ? 204 : 503, -1);
            exchange.close();
        });
        server.start();
        int closedPort;
        try (java.net.ServerSocket ephemeral = new java.net.ServerSocket(0)) {
            closedPort = ephemeral.getLocalPort();
        }
        try {
            String token = "synthetic-" + "ingest-" + "value-" + "3".repeat(12);
            List<Duration> waits = new ArrayList<>();
            HttpTransport transport = new HttpTransport("localhost", 1,
                    URI.create("http://localhost:" + server.getAddress().getPort()), token,
                    AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits::add);
            Ports outbound = new Ports();
            outbound.bodies.put(AdvisorySources.CISA_KEV.startUri().toString(), bytes("{\"vulnerabilities\":[]}"));
            List<String> log = new ArrayList<>();
            AdvisoryFetchRun fetchRun = new AdvisoryFetchRun(outbound, transport, log::add, NOW, 7,
                    AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits::add);

            AdvisoryFetchRun.SourceResult result = fetchRun.run(AdvisorySources.CISA_KEV, "run-00000003");

            assertFalse(result.ok());
            assertEquals("INGEST_REFUSED", result.reason());
            assertEquals(1, result.fetched());
            assertEquals(0, result.handedOver());
            assertEquals(5, result.attempts());
            assertEquals(AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits);
            assertEquals(6, seen.size(), "5 document attempts + 1 best-effort complete call");
            assertEquals("POST /internal/advisories/cisa_kev/complete body={\"documents_fetched\":1,\"documents_failed\":0,\"reason\":\"INGEST_REFUSED\"}",
                    seen.get(5));
            assertEquals(List.of("[ADVISORY_FETCH] source=cisa_kev fetched=1 failed=0 handed_over=0 dropped=0 reason=INGEST_REFUSED attempts=5"), log);

            // Connection refused (service down/restarting on closed port) also retries all 5 attempts with bounded backoff.
            List<Duration> refusedWaits = new ArrayList<>();
            HttpTransport refusedTransport = new HttpTransport("localhost", 1,
                    URI.create("http://localhost:" + closedPort), token,
                    AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, refusedWaits::add);
            List<String> refusedLog = new ArrayList<>();
            AdvisoryFetchRun.SourceResult refusedResult = new AdvisoryFetchRun(outbound, refusedTransport, refusedLog::add, NOW, 7,
                    AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, refusedWaits::add)
                    .run(AdvisorySources.CISA_KEV, "run-00000004");
            assertFalse(refusedResult.ok());
            assertEquals("INGEST_REFUSED", refusedResult.reason());
            assertEquals(5, refusedResult.attempts());
            assertEquals(AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, refusedWaits);
            assertEquals(List.of("[ADVISORY_FETCH] source=cisa_kev fetched=1 failed=0 handed_over=0 dropped=0 reason=INGEST_REFUSED attempts=5"),
                    refusedLog);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void permanentIngestRefusalFailsAtOnceWithoutRetrying() throws IOException {
        for (int permanentStatus : List.of(400, 401, 403, 404, 409, 413, 422)) {
            List<String> seen = new ArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                seen.add(exchange.getRequestMethod() + " " + path + " body=" + new String(body, StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(path.endsWith("/complete") ? 204 : permanentStatus, -1);
                exchange.close();
            });
            server.start();
            try {
                String token = "synthetic-" + "ingest-" + "value-" + "4".repeat(12);
                List<Duration> waits = new ArrayList<>();
                HttpTransport transport = new HttpTransport("localhost", 1,
                        URI.create("http://localhost:" + server.getAddress().getPort()), token,
                        AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits::add);
                Ports outbound = new Ports();
                outbound.bodies.put(AdvisorySources.CISA_KEV.startUri().toString(), bytes("{\"vulnerabilities\":[]}"));
                List<String> log = new ArrayList<>();
                AdvisoryFetchRun fetchRun = new AdvisoryFetchRun(outbound, transport, log::add, NOW, 7,
                        AdvisoryFetchRun.DEFAULT_INGEST_BACKOFF, waits::add);

                AdvisoryFetchRun.SourceResult result = fetchRun.run(AdvisorySources.CISA_KEV, "run-00000005");

                assertFalse(result.ok(), "status=" + permanentStatus);
                assertEquals("INGEST_REFUSED", result.reason(), "status=" + permanentStatus);
                assertEquals(0, result.handedOver(), "status=" + permanentStatus);
                assertEquals(1, result.attempts(), "status=" + permanentStatus);
                assertEquals(List.of(), waits, "permanent refusal must not sleep or retry: status=" + permanentStatus);
                assertEquals(2, seen.size(), "1 document attempt + 1 complete call for status=" + permanentStatus);
                assertEquals("POST /internal/advisories/cisa_kev/complete body={\"documents_fetched\":1,\"documents_failed\":0,\"reason\":\"INGEST_REFUSED\"}",
                        seen.get(1));
                assertEquals(List.of("[ADVISORY_FETCH] source=cisa_kev fetched=1 failed=0 handed_over=0 dropped=0 reason=INGEST_REFUSED attempts=1"),
                        log);
            } finally {
                server.stop(0);
            }
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
