package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * The host-context rules of the heuristic pass (rule set {@code logmask-v4}, 2026-10-21): a plain single-word host
 * name with no digit, hyphen or dot is masked when it follows a host keyword ({@code host}, {@code device},
 * {@code gateway}, {@code peer}, {@code node}, {@code member} ...) or {@code from}/{@code to}, in the sar header and
 * in an HCP report file name; ordinary words after those keywords stay readable.
 *
 * <p>Synthetic values only, built at runtime from seeded generators (no identity- or secret-shaped literal in the
 * source). Every leak check is a canary boolean: the SHA-256 of every visible word is compared with the digests of the
 * synthetic values, so a failure never prints a value.</p>
 */
class LogMaskingHostContextTest {

    private static final byte[] PRIVACY = privacyBytes();

    private final TopologyNamePseudonymizer pseudonyms = new TopologyNamePseudonymizer(PRIVACY);

    private static byte[] privacyBytes() {
        byte[] bytes = new byte[48];
        new Random(20261021L).nextBytes(bytes);
        return bytes;
    }

    /** A plain lower-case word: no digit, no hyphen, no dot (the shape logmask-v3 could not catch). */
    private static String word(long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 9; i++) sb.append((char) ('a' + random.nextInt(26)));
        return sb.toString();
    }

    private static String digits(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append((char) ('0' + random.nextInt(10)));
        return sb.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> digestsOf(String... values) {
        Set<String> out = new HashSet<>();
        for (String v : values) out.add(sha256(v));
        return out;
    }

    /** True when any visible word of {@code text} (split as the e2e canary does, any case) is one of the canaries. */
    private static boolean showsCanary(String text, Set<String> digests) {
        for (String w : text.split("[^A-Za-z0-9]+")) {
            if (!w.isEmpty() && digests.contains(sha256(w))) return true;
        }
        return false;
    }

    private LogMasking.Session session() {
        return new LogMasking(PRIVACY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04"))).session();
    }

    /** The host label token the text rules give {@code host} in a syslog header: one host reads the same everywhere. */
    private String headerToken(String host) {
        return session().maskLine("Oct 17 10:00:00 2026 " + host + " sshd[12]: ok").split(" ")[4];
    }

    @Test
    void aPlainWordAfterAHostKeywordBecomesTheHostToken() {
        String[] forms = {"host %s unreachable", "hostname %s", "Host name: %s", "device %s registered",
            "gateway: %s", "HA peer %s is down", "peer='%s' state changed", "node %s joined", "member %s left the cluster",
            "cluster member %s is active", "{\"opaque\":\"Peer device %s not responding\"}", "device_name=%s",
            "connection from %s refused", "sync to %s failed", "Connected to \"%s\""};
        long seed = 100;
        for (String form : forms) {
            String host = word(seed++);
            String line = form.replace("%s", host);
            String shown = session().maskLine(line);
            assertFalse(showsCanary(shown, digestsOf(host)), "a plain host word is visible in one keyword form (value withheld)");
            assertTrue(shown.contains(headerToken(host)), "the same h- token as a syslog header host");
            assertEquals(line.length() - host.length() + 12, shown.length(), "only the host changes");
        }
    }

    @Test
    void aHostNamedAfterAKeywordIsMaskedInLaterLinesOfTheSession() {
        String host = word(200);
        LogMasking.Session s = session();
        String all = s.maskLine("HA peer " + host + " is down") + "\n"
                + s.maskLine("sync lost with " + host + "; retrying") + "\n"
                + s.maskLine("{\"opaque\":\"" + host.toUpperCase(java.util.Locale.ROOT) + " state Active\"}") + "\n";
        assertFalse(showsCanary(all, digestsOf(host)), "a learned host is visible later in the session (value withheld)");
        assertTrue(all.contains("sync lost with " + headerToken(host) + "; retrying"));
        assertTrue(s.counts().get(FreeTextIdentityRules.HOST_CONTEXT) >= 3);
    }

    @Test
    void theWordsThatFollowTheseKeywordsInOrdinaryLogTextStayReadable() {
        LogMasking.Session s = session();
        for (String line : new String[] {
            "host key verification failed",
            "HA peer is down",
            "failed to connect to the server",
            "state changed from active to standby",
            "member 2 changed state from Down to Active",
            "process restarted by cpwd, sync from cpwd",
            "Connection to Update Server completed successfully",
            "connection to Panorama established",
            "device eth1 link down",
            "upgrade to R81.20 done",
            "peer 2 unreachable, node 1 up",
            "logged in from Web using https",
            "since Oct 17 to Nov 2",
            "cpd: device serial=" + pseudonyms.maskSerial(digits(12, 1)) + " registered",
            "peer FW-TANGO-04 is active",
            "point-to-point link to-do",
        }) {
            assertEquals(line, s.maskLine(line), "an ordinary line changed");
        }
    }

    @Test
    void dottedAndSerialShapedWordsAfterAKeywordKeepTheirOwnRules() {
        String host = word(300);
        String site = word(301);
        String mixed = "QX" + digits(9, 302);
        LogMasking.Session s = session();
        String fqdn = s.maskLine("peer " + host + "." + site + ".lan unreachable");
        String serial = s.maskLine("device " + mixed + " registered");
        assertFalse(showsCanary(fqdn + "\n" + serial, digestsOf(host, site, mixed)), "an identity is visible (value withheld)");
        assertTrue(fqdn.matches("peer h-[0-9a-f]{10}\\.h-[0-9a-f]{10}\\.\\S+ unreachable"), "label by label, as an FQDN");
        assertEquals("device " + pseudonyms.maskSerial(mixed) + " registered", serial, "the inventory's SN- token");
    }

    @Test
    void theSarHeaderHostAndTheHcpReportHostAreMasked() {
        String host = word(400);
        String other = word(401);
        LogMasking.Session s = session();
        String sar = s.maskLine("Linux 3.10.0-957.21.3cpx86_64 (" + host + ")\t10/17/2026\t_x86_64_\t(8 CPU)");
        String file = s.maskFileName("hcp_report_" + other + "_17_10_26_03_00.tar.gz");
        assertFalse(showsCanary(sar + "\n" + file, digestsOf(host, other)), "a host is visible (value withheld)");
        assertTrue(sar.startsWith("Linux 3.10.0-957.21.3cpx86_64 (" + headerToken(host) + ")\t10/17/2026"), "only the host changes");
        assertEquals("hcp_report_" + headerToken(other) + "_17_10_26_03_00.tar.gz", file, "only the host changes");
        assertEquals("hcp_report_FW-TANGO-04_17_10_26_03_00.tar.gz", s.maskFileName("hcp_report_edge-fw-01_17_10_26_03_00.tar.gz"),
                "a known name keeps its pseudonym");
    }

    @Test
    void aJsonLineStaysValidAndItsKeysAreNeverMaskedAsLearnedNames() {
        String host = word(500);
        LogMasking.Session s = session();
        s.maskLine("peer " + host + " is down");
        String json = "{\"" + host + "\":\"1\",\"opaque\":\"node " + host + " left\"}";
        String shown = s.maskLine(json);
        assertTrue(shown.startsWith("{\"" + host + "\":\"1\""), "a JSON key is structure, not text");
        assertTrue(shown.endsWith("\"opaque\":\"node " + headerToken(host) + " left\"}"), "the value is masked");
    }

    @Test
    void theRuleSetIsLogmaskV4AndThePassIsIdempotent() {
        LogMasking.Session s = session();
        String once = s.maskLine("sync from " + word(600) + " to " + word(601) + " via gateway " + word(602));
        assertEquals(once, session().maskLine(once), "written tokens are never masked again");
        assertEquals("logmask-v4", s.ruleSet());
        assertEquals(LogMasking.RULE_SET, s.ruleSet());
    }
}
