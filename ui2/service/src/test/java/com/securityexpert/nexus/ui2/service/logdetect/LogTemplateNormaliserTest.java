package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadFixture;

/**
 * Layer-B normalisation (design 4.1 steps 2 and 7): masking first, then typed placeholders, then the closed-vocabulary
 * forms. Synthetic lines; documentation addresses (192.0.2.x, 2001:db8::) and masked device names only.
 */
class LogTemplateNormaliserTest {
    private final LogTemplateNormaliser normaliser = new LogTemplateNormaliser();
    private final LogKeyToken keyed = new LogKeyToken(SystemLogReadFixture.PRIVACY_KEY);

    private static String masked(String rawLine) {
        LogMasking masking = new LogMasking(SystemLogReadFixture.PRIVACY_KEY, () -> KnownNames.of(Map.of("edge-fw-01", "FW-TANGO-04")));
        return masking.session().maskLine(rawLine);
    }

    @Test
    void theDesignExampleNormalisesToTypedPlaceholders() {
        List<String> tokens = normaliser.normalise("cp_messages", "Nov 24 02:01:07 2026 FW-TANGO-04 kernel: EDAC MC0: 1 CE memory read error "
                + "on CPU_SrcID#0_MC#0_Chan#1_DIMM#0 (channel:1 slot:0 page:0x4a1f2 offset:0x0 grain:32)");
        assertEquals("kernel: EDAC MC<NUM>: <NUM> CE memory read error on <DIMM_LABEL> (channel:<NUM> slot:<NUM> page:<HEX> "
                + "offset:<HEX> grain:<NUM>)", String.join(" ", tokens));
        assertEquals(String.join(" ", tokens), normaliser.displayForm(tokens), "every word of the example is in the vocabulary");
    }

    @Test
    void everyMaskingPseudonymClassBecomesAPlaceholderSoTemplatesDoNotSplitPerDevice() {
        String a = "Nov 24 02:01:07 2026 FW-TANGO-04 sshd[812]: Accepted publickey for USR-0A1B2C3D4E from 192.0.2.17 port 50122 "
                + "ssh2 on FW-TANGO-04 via h-0123456789 policy POL-ABCDEF0123 serial SN-0011223344";
        String b = "Nov 24 03:11:09 2026 CLS-ROMEO-01 sshd[9]: Accepted publickey for USR-FFFFFFFFFF from 192.0.2.200 port 1 "
                + "ssh2 on FW-JULIET-06 via h-abcdefabcd policy POL-0000000000 serial SN-FFEEDDCCBB";
        List<String> ta = normaliser.normalise("cp_messages", a);
        assertEquals(ta, normaliser.normalise("cp_messages", b));
        String text = String.join(" ", ta);
        for (String placeholder : List.of("<PID>", "<USR>", "<IP>", "<NUM>", "<DEV>", "<HOST>", "<EST>", "<SN>")) {
            assertTrue(text.contains(placeholder), placeholder + " in " + text);
        }
        assertFalse(text.contains("TANGO") || text.contains("JULIET") || text.contains("192.0.2"), text);
    }

    @Test
    void aPlantedIdentityOfEveryClassNeverReachesAStoredForm() {
        String host = "edge-fw-01";
        String raw = "Nov 24 02:01:07 2026 " + host + " sshd[812]: Failed password for alice.example from 192.0.2.17 port 50122 "
                + "ssh2 mac 00:1a:2b:3c:4d:5e mail alice@corp.example.com v6 2001:db8::5 path /home/alice/.ssh url "
                + "https://portal.corp.example.com/login <redacted> \"quoted secret words\"";
        String maskedLine = masked(raw);
        List<String> tokens = normaliser.normalise("cp_messages", maskedLine);
        String display = normaliser.displayForm(tokens);
        String match = normaliser.matchForm(tokens, keyed);
        for (String planted : List.of(host, "alice", "corp", "example", "192.0.2.17", "00:1a:2b", "2001:db8", "portal", "quoted",
                "secret", "FW-TANGO-04")) {
            assertFalse(display.contains(planted), planted + " in display form " + display);
            assertFalse(match.contains(planted), planted + " in match form " + match);
        }
        assertTrue(LogTemplateNormaliser.SAFE_CHARS.matcher(display).matches(), display);
        assertTrue(LogTemplateNormaliser.SAFE_CHARS.matcher(match).matches(), match);
        assertTrue(display.contains("<REDACTED>") && display.contains("<STR>"), display);
    }

    @Test
    void wordsOutsideTheVocabularyAreShownAsWAndMatchedAsKeyedTokens() {
        List<String> tokens = normaliser.normalise("cp_messages", "Nov 24 02:01:07 2026 FW-TANGO-04 zorblatt: frobnicated quux state up");
        assertEquals("<W> <W> <W> state up", normaliser.displayForm(tokens));
        String match = normaliser.matchForm(tokens, keyed);
        assertTrue(match.matches("k-[0-9a-f]{12} k-[0-9a-f]{12} k-[0-9a-f]{12} state up"), match);
        assertEquals(match, normaliser.matchForm(tokens, keyed), "the keyed token is stable: equality only");
    }

    @Test
    void panSystemKeepsTheVendorEnumerationAsTheFirstKey() {
        String line = "{\"receive_time\":\"2026/11/24 02:01:07\",\"severity\":\"high\",\"subtype\":\"general\",\"eventid\":\"general\","
                + "\"opaque\":\"Connection to 192.0.2.9 port 3978 lost on ethernet1/1\"}";
        List<String> tokens = normaliser.normalise("pan_system", line);
        assertEquals("general/general: Connection to <IP> port <NUM> lost on <IF>", String.join(" ", tokens));
        assertTrue(normaliser.normalise("pan_system", "not json").isEmpty(), "a line without the entry shape is unparsed");
        assertTrue(normaliser.normalise("cp_messages", "no syslog header here").isEmpty());
    }

    @Test
    void tokensAreBoundedAndTheVocabularyIsClosedLowerCaseWords() {
        StringBuilder sb = new StringBuilder("Nov 24 02:01:07 2026 FW-TANGO-04 kernel:");
        for (int i = 0; i < 200; i++) sb.append(" error");
        assertEquals(LogTemplateNormaliser.MAX_TOKENS, normaliser.normalise("cp_messages", sb.toString()).size());
        for (String w : LogTemplateNormaliser.loadVocabulary()) assertTrue(w.matches("[a-z]{1,32}"), w);
        assertFalse(normaliser.keeps("x".repeat(LogTemplateNormaliser.MAX_TOKEN_CHARS + 1)));
    }
}
