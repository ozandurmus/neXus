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
 * The heuristic free-text pass of the masked view (rule set {@code logmask-v3}, PO decision 2026-10-19): identity-shaped
 * tokens in free text that are neither a known estate identity nor behind a keyed field are masked; the short allowlist
 * of readable non-identity tokens stays.
 *
 * <p>Synthetic values only, built at runtime from seeded generators (no identity- or secret-shaped literal in the
 * source). Every leak check is a canary boolean: the SHA-256 of every visible word is compared with the digests of the
 * synthetic values, so a failure never prints a value.</p>
 */
class LogMaskingHeuristicTest {

    private static final byte[] PRIVACY = privacyBytes();

    private final TopologyNamePseudonymizer pseudonyms = new TopologyNamePseudonymizer(PRIVACY);

    private static byte[] privacyBytes() {
        byte[] bytes = new byte[48];
        new Random(20261019L).nextBytes(bytes);
        return bytes;
    }

    private static String letters(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append((char) ('a' + random.nextInt(26)));
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> digestsOf(String... values) {
        Set<String> out = new HashSet<>();
        for (String v : values) out.add(sha256(v));
        return out;
    }

    /** True when any visible word of {@code text} (split as the e2e canary does) is one of the canary values. */
    private static boolean showsCanary(String text, Set<String> digests) {
        for (String word : text.split("[^A-Za-z0-9._:-]+")) {
            String w = word.replaceAll("^[._:-]+|[._:-]+$", "");
            if (!w.isEmpty() && digests.contains(sha256(w))) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    private LogMasking.Session session() {
        return new LogMasking(PRIVACY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04"))).session();
    }

    // ------------------------------------------------------------------ FQDN- and hostname-shaped tokens

    @Test
    void anFqdnOutsideThePublicSuffixListIsMaskedLabelByLabel() {
        String host = letters(5, 1) + digits(2, 2);
        String site = letters(6, 3);
        String fqdn = host + "." + site + ".lan";
        LogMasking.Session s = session();
        String[] lines = {
            "{\"opaque\":\"Connection to " + fqdn + " failed\"}",
            "resolving " + host + "." + site + " ok",
            "cpd: peer " + fqdn + ": unreachable",
        };
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(s.maskLine(line)).append('\n');
        String shown = all.toString();
        assertFalse(showsCanary(shown, digestsOf(fqdn, host, site)), "an FQDN or one of its labels is visible (value withheld)");
        assertFalse(containsAny(shown, host, site), "a label occurs in the masked text (value withheld)");
        assertTrue(shown.contains("h-"), "label tokens take their place");
        assertTrue(shown.contains("Connection to ") && shown.contains(" failed"), "the message around it stays");
    }

    @Test
    void hostnameShapedTokensInFreeTextBecomeTheHostToken() {
        String hyphen = letters(4, 4) + "-" + letters(2, 5) + "-" + digits(2, 6);
        String member = letters(5, 7) + "-" + letters(3, 8) + "-aa";
        String underscore = letters(4, 9) + "_" + letters(3, 10) + "_0" + (1 + new Random(11).nextInt(9));
        LogMasking.Session s = session();
        String shown = s.maskLine("{\"opaque\":\"HA peer " + hyphen + " is down\"}") + "\n"
                + s.maskLine("sync with " + member + " lost") + "\n"
                + s.maskLine("clish[123]: object " + underscore + " saved") + "\n";
        assertFalse(showsCanary(shown, digestsOf(hyphen, member, underscore)), "a host name is visible (value withheld)");
        assertFalse(containsAny(shown, hyphen, member, underscore), "a host name occurs in the masked text (value withheld)");
        // The same token family as a host the text rules recognise in a syslog header.
        String header = s.maskLine("Oct 17 10:00:00 2026 " + hyphen + " sshd[12]: ok");
        String token = header.split(" ")[4];
        assertTrue(token.matches("h-[0-9a-f]{10}"), "the host label token");
        assertTrue(shown.contains("HA peer " + token + " is down"), "one host reads the same in free text and in a header");
    }

    @Test
    void theElgHeaderHostIsMasked() {
        String host = letters(6, 12);
        String shown = session().maskLine("[cpd 4321 8765]@" + host + "[17 Oct 10:15:01] SIC certificate fetched");
        assertFalse(showsCanary(shown, digestsOf(host)), "the elg header host is visible (value withheld)");
        assertTrue(shown.matches("\\[cpd 4321 8765]@h-[0-9a-f]{10}\\[17 Oct 10:15:01] SIC certificate fetched"), "only the host changes");
    }

    // ------------------------------------------------------------------ serial-like tokens

    @Test
    void serialLikeTokensInFreeTextBecomeTheInventorySerialToken() {
        String run = digits(12, 13);
        String mixed = "QX" + digits(9, 14);
        LogMasking.Session s = session();
        String a = s.maskLine("{\"opaque\":\"device " + run + " registered with Panorama\"}");
        String b = s.maskLine("license for unit " + mixed + " installed");
        assertFalse(showsCanary(a + "\n" + b, digestsOf(run, mixed)), "a serial-like value is visible (value withheld)");
        assertTrue(a.contains("device " + pseudonyms.maskSerial(run) + " registered"), "the inventory's SN- token");
        assertTrue(b.contains("unit " + pseudonyms.maskSerial(mixed) + " installed"));
        assertTrue(s.counts().get(FreeTextIdentityRules.SERIAL) >= 2);
    }

    @Test
    void timestampsAndSequenceNumbersAfterTheirKeysAreKept() {
        String epoch = "17" + digits(8, 15);
        String seq = digits(13, 16);
        LogMasking.Session s = session();
        for (String line : new String[] {"start time=" + epoch + " end", "{\"seqno\":\"" + seq + "\",\"type\":\"SYSTEM\"}",
                "seq " + seq + " ok", "sequence number: " + seq, "epoch: " + epoch}) {
            assertEquals(line, s.maskLine(line));
        }
    }

    // ------------------------------------------------------------------ principal mentions

    @Test
    void principalMentionsBecomeTheUserToken() {
        String user = letters(7, 17);
        String other = letters(5, 18) + "." + letters(4, 19);
        LogMasking.Session s = session();
        String[] lines = {
            "clish[2211]: User " + user + " logged in with ReadWrite permission",
            "{\"opaque\":\"User '" + other + "' logged in via Web from 192.0.2.10 using https\"}",
            "{\"opaque\":\"authenticated for user '" + user + "'. auth profile 'p1', vsys 'shared'\"}",
            "sshd[77]: Accepted publickey for " + user + " from 192.0.2.10 port 22 ssh2",
            "sshd[78]: Invalid user " + other + " from 192.0.2.11",
            "rule 5 changed by " + other,
            "policy installed by admin " + user,
            "login=" + user + " result=ok",
            "cpd: user: " + other + " session closed",
        };
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(s.maskLine(line)).append('\n');
        String shown = all.toString();
        assertFalse(showsCanary(shown, digestsOf(user, other)), "a principal is visible (value withheld)");
        assertFalse(containsAny(shown, user, other), "a principal occurs in the masked text (value withheld)");
        String token = pseudonyms.maskPayloadToken("USR", user);
        assertTrue(shown.contains("User " + token + " logged in"), "the same USR- token as the JSON admin field");
        assertTrue(shown.contains("installed by admin " + token), "the role word stays, the name is masked once");
        assertTrue(shown.contains("ReadWrite permission") && shown.contains("port 22 ssh2"), "the text around stays");
    }

    @Test
    void ordinaryWordsAfterAPrincipalTriggerAreNotNames() {
        LogMasking.Session s = session();
        for (String line : new String[] {"admin login ok", "user is locked out", "process restarted by cpwd",
                "packet dropped by rule 12", "{\"opaque\":\"user table rebuilt\"}"}) {
            assertEquals(line, s.maskLine(line));
        }
    }

    // ------------------------------------------------------------------ the allowlist

    @Test
    void versionsFileNamesProtocolAndInterfaceNamesStayReadable() {
        LogMasking.Session s = session();
        String uuid = java.util.UUID.nameUUIDFromBytes(letters(8, 29).getBytes(StandardCharsets.UTF_8)).toString();
        for (String line : new String[] {
            "Gaia R81.20 Take 99 installed",
            "PAN-OS 10.2.9-h1 loaded, content 8800-8700",
            "firmware v1.2.3.4 installed",
            "rotated fwd.elg cpd.elg cpwd.elg messages.1 spike_detective.csv mq_mng.elg",
            "IKE phase-1 negotiation is succeeded, AES-256-CBC sha2-256 group-14 UTF-8",
            "interface eth1-01 link down, bond0.100 up",
            "[17 Oct 10:15:01][fw4_0];[cpu_2];fwkern: ok",
            "report 17-Oct-2026 job " + uuid + " flags 0x80000000",
        }) {
            assertEquals(line, s.maskLine(line));
        }
    }

    @Test
    void panEnumFieldsStayAndTheirNeighboursAreStillMasked() {
        String serial = digits(12, 20);
        String host = letters(4, 21) + "-gw-" + digits(2, 22);
        String line = "{\"@logid\":\"7001\",\"receive_time\":\"2026/10/17 10:00:00\",\"serial\":\"" + serial
                + "\",\"seqno\":\"7340032000123\",\"type\":\"SYSTEM\",\"subtype\":\"vpn\",\"eventid\":\"ike-nego-p1-start\","
                + "\"opaque\":\"IKE phase-1 negotiation is started with " + host + "\",\"severity\":\"informational\","
                + "\"dg_hier_level_1\":\"0\",\"high_res_timestamp\":\"2026-10-17T10:00:00.000+00:00\"}";
        String shown = session().maskLine(line);
        assertFalse(showsCanary(shown, digestsOf(serial, host)), "an identity is visible (value withheld)");
        for (String kept : new String[] {"\"eventid\":\"ike-nego-p1-start\"", "\"seqno\":\"7340032000123\"",
                "\"subtype\":\"vpn\"", "\"dg_hier_level_1\":\"0\"", "\"high_res_timestamp\":\"2026-10-17T10:00:00.000+00:00\"",
                "\"receive_time\":\"2026/10/17 10:00:00\"", "IKE phase-1 negotiation is started with h-"}) {
            assertTrue(shown.contains(kept), "a readable vendor field changed: " + kept);
        }
    }

    @Test
    void theHeuristicPassIsIdempotentAndTheSessionNamesItsRuleSet() {
        LogMasking.Session s = session();
        String once = s.maskLine("peer " + letters(4, 23) + "-fw-" + digits(2, 24) + " by " + letters(6, 25) + " unit "
                + digits(11, 26));
        assertEquals(once, session().maskLine(once), "written tokens are never masked again");
        assertEquals("logmask-v3", s.ruleSet());
        assertEquals(LogMasking.RULE_SET, s.ruleSet());
    }

    @Test
    void fileNamesGetTheHeuristicPassToo() {
        String host = letters(5, 27) + "-fw-" + digits(2, 28);
        String name = session().maskFileName("hcp_report_" + host + "_17_10_26_03_00.tar.gz");
        assertFalse(name.contains(host), "the host in a file name (value withheld)");
        assertTrue(name.matches("hcp_report_h-[0-9a-f]{10}_17_10_26_03_00\\.tar\\.gz"), "only the host changes");
    }
}
