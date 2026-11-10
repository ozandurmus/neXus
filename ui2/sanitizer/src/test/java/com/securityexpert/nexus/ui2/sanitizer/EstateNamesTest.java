package com.securityexpert.nexus.ui2.sanitizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.IdentityTokens;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.TextSanitizer;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;

/**
 * Estate names in the shared dictionary ({@code logmask-v5}, 2026-11-10): administrator, policy, rule, object, zone,
 * virtual-system and domain names are masked anywhere in a line, case-insensitively and as phrases, as typed one-way
 * tokens; device identities keep their exact match; masked forms are never rewritten; the IPv4 rule treats an
 * underscore as a delimiter. Invented words and RFC 5737 addresses only.
 */
class EstateNamesTest {

    private final Mappers mappers = new Mappers(SanitizerKey.of(TestVault.key()), KnownNames.NONE);
    private final IdentityTokens tokens = new IdentityTokens(TestVault.key());

    private static final String ADMIN = "zorvanek";
    private static final String POLICY = "Quellmar Branch Standard";
    private static final String OBJECT = "Brindlewick_Net";
    private static final String VSYS = "vsTarnholm";
    private static final String DOMAIN = "Ostervale";

    private EstateDictionary names() {
        return EstateDictionary.build(List.of(
                EstateIdentity.estateName(EstateIdentity.Kind.USER, ADMIN),
                EstateIdentity.estateName(EstateIdentity.Kind.POLICY, POLICY),
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, OBJECT),
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, "tango"),
                EstateIdentity.estateName(EstateIdentity.Kind.VSYS, VSYS),
                EstateIdentity.estateName(EstateIdentity.Kind.DOMAIN, DOMAIN),
                EstateIdentity.estateName(EstateIdentity.Kind.ZONE, "  Glimmer   Zone  ")), mappers);
    }

    private static String replace(EstateDictionary d, String line) {
        return d.replacer().replace(line, new Counts());
    }

    /** Turkish letters written as escapes: dotted capital I (U+0130) and dotless small i (U+0131). */
    private static final String DOTTED_CAPITAL = "\u0130";
    private static final String DOTLESS = "\u0131";

    @Test
    void turkishCaseVariantsOfAnEstateNameAreOneName() {
        // logmask-v6 (planted-identity test E5): the stored name and the text fold I, i, dotted capital and dotless to one letter.
        String dotless = "Tarnh" + DOTLESS + "ll";
        String plain = "Quellmir";
        EstateDictionary d = EstateDictionary.build(List.of(
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, dotless),
                EstateIdentity.estateName(EstateIdentity.Kind.POLICY, plain)), mappers);
        String objectToken = tokens.payload(IdentityTokens.OBJECT, dotless);
        String policyToken = tokens.payload(IdentityTokens.POLICY, plain);
        for (String text : new String[] {dotless, "Tarnhill", "TARNHILL", "tarnhill", "TARNH" + DOTLESS + "LL", "tarnh" + DOTLESS + "ll"}) {
            assertEquals("obj " + objectToken + " up", replace(d, "obj " + text + " up"), "a case variant escaped (value withheld)");
        }
        for (String text : new String[] {plain, "QUELLMIR", "quellmir", "QUELLM" + DOTTED_CAPITAL + "R", "quellm" + DOTLESS + "r",
            "Quellm" + DOTTED_CAPITAL + "r"}) {
            assertEquals("pol " + policyToken + " up", replace(d, "pol " + text + " up"), "a case variant escaped (value withheld)");
        }
        // A name stored with the dotted capital is matched by the plain forms too, as part of a phrase.
        String stored = "QUELLM" + DOTTED_CAPITAL + "R Branch";
        EstateDictionary phrase = EstateDictionary.build(List.of(EstateIdentity.estateName(EstateIdentity.Kind.POLICY, stored)), mappers);
        assertEquals("p " + tokens.payload(IdentityTokens.POLICY, stored) + " ok", replace(phrase, "p quellmir BRANCH ok"));
    }

    @Test
    void theTurkishFoldIsOneToOneAndDoesNotMergeDifferentWords() {
        assertEquals("iiii", EstateDictionary.fold("I" + DOTTED_CAPITAL + DOTLESS + "i"));
        assertEquals("quellmir".length(), EstateDictionary.fold("QUELLM" + DOTTED_CAPITAL + "R").length());
        EstateDictionary d = EstateDictionary.build(List.of(EstateIdentity.estateName(EstateIdentity.Kind.POLICY, "Quellmir")), mappers);
        assertEquals("pol Quellmar up", replace(d, "pol Quellmar up"));
        assertEquals("pol Quellmirs up", replace(d, "pol Quellmirs up"));
    }

    @Test
    void anAdministratorNameWithoutATriggerWordIsTheUserToken() {
        String out = replace(names(), "Nov 10 10:00:00 2026 " + ADMIN + " sshd[812]: session opened");
        assertFalse(out.contains(ADMIN));
        assertTrue(out.contains(tokens.user(ADMIN)), "the same USR- token the principal rule writes");
    }

    @Test
    void namesAreMatchedCaseInsensitivelyAndKeepOneToken() {
        EstateDictionary d = names();
        String lower = replace(d, "domain " + DOMAIN.toLowerCase(Locale.ROOT) + " synced");
        String upper = replace(d, "domain " + DOMAIN.toUpperCase(Locale.ROOT) + " synced");
        String token = tokens.payload(IdentityTokens.DOMAIN, DOMAIN);
        assertEquals("domain " + token + " synced", lower);
        assertEquals("domain " + token + " synced", upper);
    }

    @Test
    void aMultiWordNameIsMatchedAsAPhraseInFreeTextQuotesAndParentheses() {
        EstateDictionary d = names();
        String token = tokens.payload(IdentityTokens.POLICY, POLICY);
        assertEquals("installed " + token + " on gw", replace(d, "installed " + POLICY + " on gw"));
        assertEquals("{\"policy\":\"" + token + "\"}", replace(d, "{\"policy\":\"" + POLICY + "\"}"));
        assertEquals("name (" + token + ")", replace(d, "name (" + POLICY.toLowerCase(Locale.ROOT) + ")"));
        // Whitespace inside a stored name is collapsed to one space; the text must carry single spaces.
        assertEquals("zone " + tokens.payload(IdentityTokens.ZONE, "Glimmer Zone") + ".", replace(d, "zone Glimmer Zone."));
        // Only the first word of a phrase is no match.
        assertEquals("installed Quellmar only", replace(d, "installed Quellmar only"));
    }

    @Test
    void anObjectNameInsideAPathOrADelimitedTokenIsMaskedButALongerWordIsNot() {
        EstateDictionary d = names();
        String token = tokens.payload(IdentityTokens.OBJECT, OBJECT);
        assertEquals("/opt/" + token + "/state", replace(d, "/opt/" + OBJECT + "/state"));
        assertEquals("grp." + token + ":1", replace(d, "grp." + OBJECT + ":1"));
        assertEquals("x" + OBJECT + "y", replace(d, "x" + OBJECT + "y"));
        assertEquals("{\"vsys\":\"" + tokens.payload(IdentityTokens.VSYS, VSYS) + "\"}", replace(d, "{\"vsys\":\"" + VSYS + "\"}"));
    }

    @Test
    void maskedFormsAreNeverRewritten() {
        EstateDictionary d = names();
        assertEquals("peer FW-TANGO-04 up", replace(d, "peer FW-TANGO-04 up"), "a pseudonym word that is also an object name");
        String user = tokens.user(ADMIN);
        assertEquals(user, replace(d, user));
        assertEquals("obj " + tokens.payload(IdentityTokens.OBJECT, "tango"), replace(d, "obj Tango"));
    }

    @Test
    void aDeviceIdentityKeepsItsExactMatchAndWinsOverAnEstateName() {
        EstateDictionary devices = EstateDictionary.build(List.of(EstateIdentity.name("edge-fw-01", "FW-TANGO-04")), mappers);
        EstateDictionary sameName = EstateDictionary.build(List.of(
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, "edge-fw-01")), mappers);
        EstateDictionary both = devices.with(sameName);
        assertEquals("from FW-TANGO-04 ok", replace(both, "from edge-fw-01 ok"));
        assertEquals("from " + tokens.payload(IdentityTokens.OBJECT, "edge-fw-01") + " ok", replace(both, "from EDGE-FW-01 ok"),
                "the device rule is exact; the folded estate name still masks another spelling");
        assertEquals("from EDGE-FW-01 ok", replace(devices, "from EDGE-FW-01 ok"), "device identities stay case-sensitive");
        assertSame(devices, devices.with(EstateDictionary.EMPTY));
        assertEquals(2, both.size());
        assertEquals(1, both.nameCount());
    }

    @Test
    void withoutEstateNamesASpaceStillEndsACandidate() {
        EstateDictionary devices = EstateDictionary.build(List.of(EstateIdentity.name("edge", "FW-TANGO-04"),
                EstateIdentity.name("edge fw", "FW-BRAVO-02")), mappers);
        assertEquals("FW-TANGO-04 fw", replace(devices, "edge fw"), "a device name with a space is no device identity");
    }

    @Test
    void namesThatAreAddressesNumbersOrTooShortAreNotEstateNames() {
        EstateDictionary d = EstateDictionary.build(List.of(
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, "192.0.2.44"),
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, "123456"),
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, "ab"),
                EstateIdentity.estateName(EstateIdentity.Kind.OBJECT, "a/b/c")), mappers);
        assertEquals(0, d.size());
        assertThrows(IllegalArgumentException.class, () -> EstateIdentity.estateName(EstateIdentity.Kind.NAME, "x"));
    }

    @Test
    void estateNameTokensAreRecognisedAsTokens() {
        for (String kind : List.of(IdentityTokens.POLICY, IdentityTokens.RULE, IdentityTokens.OBJECT, IdentityTokens.ZONE,
                IdentityTokens.VSYS, IdentityTokens.DOMAIN, IdentityTokens.USER)) {
            assertTrue(IdentityTokens.isToken(tokens.payload(kind, "Quillon")), kind);
        }
        assertFalse(IdentityTokens.isToken("ABC-0123456789"));
    }

    @Test
    void anAddressGluedToAnUnderscoreIsMasked() {
        TextSanitizer text = new TextSanitizer(mappers, TokenSink.DISCARD, new Counts());
        String out = text.sanitize("lookup (net_192.0.2.7) and host_198.51.100.9_32 done", false);
        assertFalse(out.contains("192.0.2.7"));
        assertFalse(out.contains("198.51.100.9"));
        assertTrue(out.startsWith("lookup (net_") && out.contains(") and host_") && out.endsWith("_32 done"), "the delimiters stay");
        // Still not inside a longer dotted number or a word.
        assertEquals("oid 1.3.192.0.2.7.1 and v2x192.0.2.7", text.sanitize("oid 1.3.192.0.2.7.1 and v2x192.0.2.7", false));
    }
}
