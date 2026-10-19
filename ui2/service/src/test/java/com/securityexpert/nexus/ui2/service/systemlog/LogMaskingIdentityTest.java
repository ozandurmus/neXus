package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.SerialRule;

/**
 * The masked projection never shows a device serial or another known estate identity (2026-10-18 privacy fix: the
 * e2e canary found the 12-digit {@code serial} of every Palo Alto JSON Lines entry on the "config" source).
 *
 * <p>Synthetic values only, built at runtime: serial-shaped digit strings come from a seeded generator, addresses are
 * RFC 5737 documentation addresses, names are example names. Every leak check works the way the e2e canary does: the
 * SHA-256 of every visible token is compared with the digests of the identity values, and only a boolean is asserted,
 * so a failure never prints a value.</p>
 */
class LogMaskingIdentityTest {

    private static final byte[] KEY = ("0123456789abcdef".repeat(2) + "-test-" + "k" + "ey").getBytes(StandardCharsets.UTF_8);
    /** The e2e canary's tokenizer (ui2/frontend/e2e/safety.ts hasCanaryToken). */
    private static final Pattern CANARY_TOKEN = Pattern.compile("\\b[A-Za-z0-9][A-Za-z0-9._:-]*\\b");

    private final TopologyNamePseudonymizer pseudonyms = new TopologyNamePseudonymizer(KEY);

    /** A serial-shaped all-digit value of {@code length} digits, different per seed, never a literal in the source. */
    private static String digits(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append((char) ('0' + random.nextInt(10)));
        return sb.toString();
    }

    /** One {@code pan_config} entry exactly as {@code PanSystemLogCollectJobExecutor} stores it (JSON Lines, PanLogPage field order). */
    private static String panConfigLine(String serial, long seqno, String deviceName) {
        return "{\"@logid\":\"" + (7000 + seqno) + "\",\"domain\":\"1\",\"receive_time\":\"2026/10/17 10:00:0" + (seqno % 10)
                + "\",\"serial\":\"" + serial + "\",\"seqno\":\"" + (1000 + seqno) + "\",\"actionflags\":\"0x0\","
                + "\"is-logging-service\":\"no\",\"type\":\"CONFIG\",\"subtype\":\"0\",\"config_ver\":\"2561\","
                + "\"time_generated\":\"2026/10/17 10:00:00\",\"dg_hier_level_1\":\"0\",\"dg_hier_level_2\":\"0\","
                + "\"dg_hier_level_3\":\"0\",\"dg_hier_level_4\":\"0\",\"vsys_id\":\"0\",\"device_name\":\"" + deviceName
                + "\",\"tpl_id\":\"0\",\"dg_id\":\"0\",\"host\":\"192.0.2.10\",\"cmd\":\"edit\",\"admin\":\"admin\","
                + "\"client\":\"Web\",\"result\":\"Submitted\",\"path\":\" vsys  vsys1 address  a1\","
                + "\"high_res_timestamp\":\"2026-10-17T10:00:00.000+00:00\"}";
    }

    /** One {@code pan_system} entry as stored. */
    private static String panSystemLine(String serial, long seqno) {
        return "{\"@logid\":\"" + (9000 + seqno) + "\",\"domain\":\"1\",\"receive_time\":\"2026/10/17 10:00:00\",\"serial\":\""
                + serial + "\",\"seqno\":\"" + (2000 + seqno) + "\",\"actionflags\":\"0x0\",\"is-logging-service\":\"no\","
                + "\"type\":\"SYSTEM\",\"subtype\":\"general\",\"config_ver\":\"0\",\"time_generated\":\"2026/10/17 10:00:00\","
                + "\"dg_hier_level_1\":\"0\",\"vsys_id\":\"0\",\"eventid\":\"general\",\"fmt\":\"0\",\"id\":\"0\","
                + "\"module\":\"general\",\"object\":\"\",\"opaque\":\"Commit job succeeded\",\"severity\":\"informational\","
                + "\"device_name\":\"edge-fw-01\",\"vsys\":\"\",\"vsys_name\":\"\",\"tpl_id\":\"0\",\"dg_id\":\"0\","
                + "\"high_res_timestamp\":\"2026-10-17T10:00:00.000+00:00\"}";
    }

    private static Set<String> digestsOf(String... values) {
        Set<String> out = new HashSet<>();
        for (String v : values) out.add(sha256(v));
        return out;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when any visible token of {@code text} is one of the canary values (the e2e check, value withheld). */
    private static boolean hasCanaryToken(String text, Set<String> digests) {
        Matcher m = CANARY_TOKEN.matcher(text);
        while (m.find()) if (digests.contains(sha256(m.group()))) return true;
        return false;
    }

    /** True when a canary value occurs anywhere in {@code text}, even inside a longer token. */
    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    private LogMasking masking() {
        return new LogMasking(KEY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")));
    }

    // ------------------------------------------------------------------ the serial field rule

    @Test
    void aPanConfigChunkInTheStoredShapeNeverShowsItsTwelveDigitSerial() {
        String serial = digits(12, 1);
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < 100; i++) chunk.append(panConfigLine(serial, i, "edge-fw-01")).append('\n');

        String shown = new String(masking().session().maskChunk(chunk.toString().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertFalse(hasCanaryToken(shown, digestsOf(serial)), "a visible token is the serial (value withheld)");
        assertFalse(containsAny(shown, serial), "the serial occurs in the masked text (value withheld)");
        String token = pseudonyms.maskSerial(serial);
        assertEquals(100, shown.split("\"serial\":\"" + Pattern.quote(token) + "\"", -1).length - 1,
                "every line carries the inventory's SN- token for the serial");
        assertEquals(100, shown.split("\n", -1).length - 1, "no line lost or merged");
        assertTrue(shown.contains("\"device_name\":\"FW-TANGO-04\""), "the inventory name is masked as before");
        assertTrue(shown.contains("\"seqno\":\"1000\""), "non-identity numbers are kept");
    }

    @Test
    void aPanSystemEntryAndFifteenDigitVirtualSerialsAreMaskedToo() {
        String hardware = digits(12, 2);
        String virtual = digits(15, 3);
        LogMasking.Session s = masking().session();
        String a = s.maskLine(panSystemLine(hardware, 1));
        String b = s.maskLine(panSystemLine(virtual, 2));
        assertFalse(containsAny(a + b, hardware, virtual), "a serial occurs in the masked text (value withheld)");
        assertTrue(a.contains(pseudonyms.maskSerial(hardware)));
        assertTrue(b.contains(pseudonyms.maskSerial(virtual)));
        assertEquals(2L, s.counts().get(SerialRule.SERIAL));
    }

    @Test
    void serialKeysAreFoundInAnySpellingQuotedOrBareAndAnyValueFormIsMaskedFailClosed() {
        String serial = digits(12, 4);
        String alnum = "QZ" + digits(8, 5) + "X";
        LogMasking.Session s = masking().session();
        String[] lines = {
            "{\"Serial\": \"" + serial + "\"}",
            "{\"serial_number\":\"" + alnum + "\"}",
            "{\"SerialNo\" : \"" + serial + "\"}",
            "{\"@serial\":\"" + serial + "\"}",
            "{\"sn\":\"" + alnum + "\"}",
            "{\"serial\":" + serial + "}",
            "cpd: device serial=" + serial + " registered",
            "Serial Number: " + alnum + " (chassis)",
            "unit serial: \"" + serial + "\".",
        };
        for (String line : lines) {
            String shown = s.maskLine(line);
            assertFalse(containsAny(shown, serial, alnum), "a serial survived in one line shape (value withheld)");
            assertTrue(shown.contains("SN-"), "the SN- token replaces it");
        }
        assertEquals("{\"serial\":\"" + pseudonyms.maskSerial(serial) + "\"}", s.maskLine("{\"serial\":" + serial + "}"),
                "a bare JSON number becomes a quoted token, so the line stays valid JSON");
    }

    @Test
    void theSerialTokenIsTheInventoryScreensTokenAndMaskingIsIdempotent() {
        String serial = digits(12, 6);
        String once = masking().session().maskLine(panConfigLine(serial, 3, "edge-fw-01"));
        assertTrue(once.contains("\"serial\":\"" + pseudonyms.maskSerial(serial) + "\""));
        // The address rule is a keyed permutation and is not idempotent by design; the serial rule is.
        String twice = masking().session().maskLine(once);
        assertTrue(twice.contains("\"serial\":\"" + pseudonyms.maskSerial(serial) + "\""), "an SN- token is never re-masked");
        String token = "{\"serial\":\"" + pseudonyms.maskSerial(serial) + "\"}";
        assertEquals(token, masking().session().maskLine(token));
    }

    @Test
    void textThatOnlyMentionsSerialsOrCarriesNoIdentityIsUntouched() {
        LogMasking.Session s = masking().session();
        for (String line : new String[] {"serial console enabled", "{\"serial\":\"\"}", "serial=<redacted>",
                "snmpd: started", "at 12:30:45 rc=0", "{\"seqno\":\"123456789012\",\"type\":\"SYSTEM\"}"}) {
            assertEquals(line, s.maskLine(line));
        }
    }

    // ------------------------------------------------------------------ JSON host and principal fields

    /** A lower-case synthetic word of {@code length} letters, different per seed, never a literal in the source. */
    private static String letters(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append((char) ('a' + random.nextInt(26)));
        return sb.toString();
    }

    @Test
    void anUnknownDeviceNameInAPanEntryBecomesTheGenericHostToken() {
        String unknown = letters(5, 20) + "-fw-" + digits(2, 21);
        String shown = masking().session().maskLine(panConfigLine(digits(12, 22), 4, unknown));
        assertFalse(hasCanaryToken(shown, digestsOf(unknown)), "a visible token is the device name (value withheld)");
        assertFalse(shown.contains(unknown), "the device name occurs in the masked text (value withheld)");
        assertTrue(shown.matches(".*\"device_name\":\"h-[0-9a-f]{10}\".*"), "the generic host label token");
    }

    @Test
    void hostKeysAreMaskedWhateverTheValue() {
        String label = letters(6, 23);
        String spaced = "Site " + letters(4, 24) + " FW";
        String numeric = digits(8, 25);
        String fqdn = letters(4, 26) + ".corp.example.com";
        LogMasking.Session s = masking().session();
        String[] lines = {
            "{\"devicename\":\"" + label + "\"}",
            "{\"Device-Name\":\"" + spaced + "\"}",
            "{\"hostname\":\"" + numeric + "\"}",
            "{\"@host\":\"" + fqdn + "\"}",
            "{\"host_name\":\"" + label + "\",\"cmd\":\"edit\"}",
        };
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(s.maskLine(line)).append('\n');
        String shown = all.toString();
        assertFalse(hasCanaryToken(shown, digestsOf(label, numeric, fqdn)), "a host value is visible (value withheld)");
        assertFalse(containsAny(shown, label, spaced, numeric, fqdn), "a host value occurs in the masked text (value withheld)");
        assertTrue(shown.matches("(?s).*\\{\"@host\":\"h-[0-9a-f]{10}(\\.[A-Za-z0-9-]+)*\\.com\"\\}.*"),
                "an FQDN becomes label tokens and keeps its public suffix, as the text rules do");
        assertTrue(shown.contains("\"cmd\":\"edit\""), "other keys are untouched");
        assertEquals("{\"device_name\":\"FW-TANGO-04\"}", s.maskLine("{\"device_name\":\"edge-fw-01\"}"), "a known name gets its pseudonym");
    }

    @Test
    void aHostAddressGetsTheAddressRuleAndAPassThroughAddressStays() {
        String viaRules = masking().session().maskLine("src 192.0.2.10");
        String masked = masking().session().maskLine("{\"host\":\"192.0.2.10\"}");
        assertFalse(masked.contains("192.0.2.10"));
        assertEquals("{\"host\":\"" + viaRules.substring(4) + "\"}", masked, "the same masked address as in free text");
        assertEquals("{\"host\":\"127.0.0.1\"}", masking().session().maskLine("{\"host\":\"127.0.0.1\"}"));
    }

    @Test
    void principalKeysBecomeAStableKeyedUserToken() {
        String user = letters(7, 27);
        String other = letters(7, 28) + "." + letters(3, 29);
        LogMasking.Session s = masking().session();
        String entry = s.maskLine(panConfigLine(digits(12, 30), 5, "edge-fw-01").replace("\"admin\":\"admin\"", "\"admin\":\"" + user + "\""));
        String[] lines = {
            "{\"user\":\"" + user + "\"}",
            "{\"Username\":\"" + other + "\"}",
            "{\"admin_name\":\"" + other + "\"}",
        };
        StringBuilder all = new StringBuilder(entry).append('\n');
        for (String line : lines) all.append(s.maskLine(line)).append('\n');
        String shown = all.toString();
        assertFalse(hasCanaryToken(shown, digestsOf(user, other)), "a principal is visible (value withheld)");
        assertFalse(containsAny(shown, user, other), "a principal occurs in the masked text (value withheld)");
        String token = pseudonyms.maskPayloadToken("USR", user);
        assertTrue(token.matches("USR-[0-9A-F]{10}"));
        assertTrue(entry.contains("\"admin\":\"" + token + "\""), "the pan_config admin gets the user token");
        assertEquals("{\"user\":\"" + token + "\"}", masking().session().maskLine("{\"user\":\"" + user + "\"}"),
                "the same user reads the same in every session and under every principal key");
        assertFalse(token.equals(pseudonyms.maskPayloadToken("USR", other)), "different users stay distinct");
        assertEquals("{\"admin\":\"" + token + "\"}", s.maskLine("{\"admin\":\"" + token + "\"}"), "a user token is not re-masked");
        assertTrue(entry.contains("\"client\":\"Web\""), "the client kind is no principal");
        assertEquals(4L, s.counts().get(LogMasking.IdentityFields.PRINCIPAL));
    }

    @Test
    void freeTextMentionsOfPrincipalAndHostWordsKeepTheTextRules() {
        LogMasking.Session s = masking().session();
        for (String line : new String[] {"admin login ok", "{\"opaque\":\"host table rebuilt\"}", "{\"admin\":\"\"}",
                "{\"user\":\"<redacted>\"}", "{\"hostile\":\"value\"}"}) {
            assertEquals(line, s.maskLine(line));
        }
    }

    // ------------------------------------------------------------------ the estate dictionary

    private static final String MGMT = "198.51.100.23";
    private static final String HA = "203.0.113.9";

    private LogMasking withEstate(String serial, String recorded) {
        List<EstateIdentity> estate = List.of(
                EstateIdentity.serial(serial),
                EstateIdentity.serial(recorded),
                EstateIdentity.address(MGMT),
                EstateIdentity.address(HA + "/30"),
                EstateIdentity.address("127.0.0.1"),
                EstateIdentity.name("edge-fw-01", "FW-TANGO-04"),
                EstateIdentity.name("branch-gw-77", "FW-BRAVO-02"),
                EstateIdentity.name("core-cluster-9", "CLS-ROMEO-01"));
        return new LogMasking(KEY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")), () -> estate, Duration.ofMinutes(1));
    }

    @Test
    void aKnownIdentityInAnUnknownFormatIsReplacedByItsMaskedForm() {
        String serial = digits(12, 7);
        String recorded = "CP" + digits(10, 8);
        LogMasking.Session s = withEstate(serial, recorded).session();
        String[] lines = {
            "unit " + serial + " joined",
            "id#" + serial + "#ok",
            "hcp-" + serial + ".tgz",
            serial + "_backup done",
            "obj:" + recorded + ":state=up",
            "[branch-gw-77] sync ok",
            "member core-cluster-9 standby",
            "peer at " + HA + ":8116",
            "mgmt=" + MGMT,
            "{\"opaque\":\"device " + serial + " changed by admin\"}",
        };
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(s.maskLine(line)).append('\n');
        String shown = all.toString();
        Set<String> canary = digestsOf(serial, recorded, MGMT, HA, "branch-gw-77", "core-cluster-9", "edge-fw-01");
        assertFalse(hasCanaryToken(shown, canary), "a visible token is an estate identity (value withheld)");
        assertFalse(containsAny(shown, serial, recorded, MGMT, HA, "branch-gw-77", "core-cluster-9"),
                "an estate identity occurs in the masked text (value withheld)");
        assertTrue(shown.contains("unit " + pseudonyms.maskSerial(serial) + " joined"), "a serial gets its SN- token");
        assertTrue(shown.contains("[FW-BRAVO-02] sync ok"), "a name gets its UI pseudonym");
        assertTrue(shown.contains("member CLS-ROMEO-01 standby"));
        assertTrue(s.counts().get(EstateDictionary.ESTATE_IDENTITY) > 0);
    }

    @Test
    void anAddressGetsTheSameReplacementAsTheTextRulesAndAPassThroughAddressIsNoIdentity() {
        LogMasking estate = withEstate(digits(12, 9), "CP" + digits(10, 10));
        String viaRules = masking().session().maskLine("src " + MGMT);
        assertEquals(viaRules, estate.session().maskLine("src " + MGMT), "one masked form per address");
        assertEquals("bind 127.0.0.1 ok", estate.session().maskLine("bind 127.0.0.1 ok"));
    }

    @Test
    void lookalikesAndPartsOfLongerTokensAreNotReplaced() {
        String serial = digits(12, 11);
        LogMasking.Session s = withEstate(serial, "CP" + digits(10, 12)).session();
        String longer = serial + "7";
        assertEquals("seq " + longer + " ok", s.maskLine("seq " + longer + " ok"), "a longer number is not the serial");
        // A longer name is not the name: the dictionary does not give it FW-TANGO-04. Since logmask-v3 the heuristic
        // pass masks it as a hostname-shaped token instead (letters, digits and hyphens).
        String longerName = s.maskLine("edge-fw-010 up");
        assertFalse(longerName.contains("FW-TANGO-04"), "a longer name is not the name");
        assertTrue(longerName.matches("h-[0-9a-f]{10} up"), "the heuristic host token");
        assertEquals("firmware v1.2.3.4 installed", s.maskLine("firmware v1.2.3.4 installed"));
    }

    @Test
    void theDictionaryKeepsNoRawValueAndIsReadOncePerTtl() {
        String serial = digits(12, 13);
        AtomicInteger reads = new AtomicInteger();
        LogMasking m = new LogMasking(KEY, () -> KnownNames.NONE, () -> {
            reads.incrementAndGet();
            return List.of(EstateIdentity.serial(serial));
        }, Duration.ofMinutes(5));
        m.session().maskLine("x " + serial);
        m.session().maskLine("y " + serial);
        assertEquals(1, reads.get(), "the estate is read once per TTL, not per page");
        assertFalse(EstateIdentity.serial(serial).toString().contains(serial), "an identity never prints its value");
    }

    @Test
    void withoutADictionaryNoMaskedSessionIsGivenAndAStaleOneIsKeptOnARefreshFailure() {
        LogMasking broken = new LogMasking(KEY, () -> KnownNames.NONE, () -> {
            throw new IllegalStateException("database down");
        }, Duration.ofMinutes(1));
        assertThrows(LogMasking.MaskingUnavailableException.class, broken::session);

        String serial = digits(12, 14);
        AtomicBoolean fail = new AtomicBoolean(false);
        LogMasking flaky = new LogMasking(KEY, () -> KnownNames.NONE, () -> {
            if (fail.get()) throw new IllegalStateException("database down");
            return List.of(EstateIdentity.serial(serial));
        }, Duration.ZERO);
        flaky.session();
        fail.set(true);
        String shown = flaky.session().maskLine("unit " + serial);
        assertFalse(shown.contains(serial), "the previous dictionary still masks (value withheld)");
    }
}
