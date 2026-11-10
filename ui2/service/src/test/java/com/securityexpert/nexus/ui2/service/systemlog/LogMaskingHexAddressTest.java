package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;

/**
 * Hex-encoded addresses in the masked view (rule set {@code logmask-v6}, 2026-11-11; planted-identity test E5, finding
 * 6): {@code 0x} plus exactly eight hex digits is the hex form of an IPv4 address, so it is masked right after an
 * address keyword; every other hex literal (kernel masks, pointers, short error codes) stays readable. Values are
 * built at runtime from a seeded generator; a failure never prints one.
 */
class LogMaskingHexAddressTest {

    private static final byte[] PRIVACY = privacyBytes();

    private static byte[] privacyBytes() {
        byte[] bytes = new byte[48];
        new Random(20261111L).nextBytes(bytes);
        return bytes;
    }

    /** {@code 0x} plus eight hex digits whose value is an RFC 5737 documentation address (192.0.2.x). */
    private static String hexAddress(int host, boolean upper) {
        String digits = String.format("c00002%02x", host);
        return "0x" + (upper ? digits.toUpperCase(Locale.ROOT) : digits);
    }

    private LogMasking.Session session() {
        return new LogMasking(PRIVACY, () -> KnownNames.NONE).session();
    }

    @Test
    void aHexAddressAfterAnAddressKeywordIsMasked() {
        String[] forms = {"addr=%s", "src_ip: %s", "dst %s", "peer=%s down", "gateway %s unreachable", "remote_addr=\"%s\"",
            "IP %s rejected", "source-address: %s", "client=%s"};
        int host = 10;
        for (String form : forms) {
            String hex = hexAddress(host++, false);
            String line = form.replace("%s", hex);
            String shown = session().maskLine(line);
            assertFalse(shown.toLowerCase(Locale.ROOT).contains(hex.substring(2)), "a hex address stays in one keyword form (value withheld)");
            assertTrue(shown.contains("ID-"), "the identity token takes its place");
        }
    }

    @Test
    void theSameAddressInEitherLetterCaseIsOneToken() {
        LogMasking.Session s = session();
        String lower = s.maskLine("peer=" + hexAddress(77, false));
        String upper = s.maskLine("peer=" + hexAddress(77, true));
        String other = s.maskLine("peer=" + hexAddress(78, false));
        assertEquals(lower, upper);
        assertNotEquals(lower, other);
    }

    @Test
    void shorterOrLongerHexLiteralsAndLiteralsWithoutAKeywordStayReadable() {
        LogMasking.Session s = session();
        for (String line : new String[] {
            "fw_full invoked oom-killer: gfp_mask=0x6200ca, order=0",
            "EDAC MC0: page:0x4a1f2 offset:0x0 grain:32",
            "segfault at 0x0 ip 0x7f12ab sp 0x7ffc12 error 4",
            "cpd_main+0x1f2 sic_dispatch+0x88",
            "addr=0x1f2a",
            "peer=0x" + "c0000211" + "ab",
            "error code " + hexAddress(5, false) + " from the driver",
            "mask " + hexAddress(6, true) + " applied",
        }) {
            assertEquals(line, s.maskLine(line), "an ordinary hex literal changed");
        }
    }

    @Test
    void theRuleCountsAndMaskedLinesAreNeverMaskedAgain() {
        LogMasking.Session s = session();
        String once = s.maskLine("addr=" + hexAddress(9, false));
        assertEquals(once, s.maskLine(once));
        assertTrue(s.counts().get(FreeTextIdentityRules.HEX_ADDRESS_COUNT) >= 1);
    }
}
