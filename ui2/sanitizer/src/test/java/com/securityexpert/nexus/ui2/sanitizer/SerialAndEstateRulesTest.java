package com.securityexpert.nexus.ui2.sanitizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.sanitizer.core.EstateDictionary;
import com.securityexpert.nexus.ui2.sanitizer.core.EstateIdentity;
import com.securityexpert.nexus.ui2.sanitizer.core.IdentityTokens;
import com.securityexpert.nexus.ui2.sanitizer.core.KnownNames;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.SanitizerKey;
import com.securityexpert.nexus.ui2.sanitizer.core.SerialRule;

/**
 * The Data Sanitizer's serial rule and known-identity pass (PO decision 2026-10-19): serial keys and pairs become the
 * inventory's one-way {@code SN-} token in text, JSON and CSV; identity values the service knows are replaced by
 * their masked form through the same {@link EstateDictionary} as the masked system-log view.
 *
 * <p>Synthetic values only, built at runtime from seeded generators; the key is {@link TestVault#key()}. Leak checks
 * assert booleans, so a failure never prints a value.</p>
 */
class SerialAndEstateRulesTest {

    private final Mappers mappers = new Mappers(SanitizerKey.of(TestVault.key()), KnownNames.NONE);
    private final IdentityTokens tokens = new IdentityTokens(TestVault.key());

    @TempDir
    Path dir;

    private static String digits(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append((char) ('0' + random.nextInt(10)));
        return sb.toString();
    }

    private static String letters(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append((char) ('a' + random.nextInt(26)));
        return sb.toString();
    }

    private static boolean containsAny(String text, String... values) {
        for (String v : values) if (text.contains(v)) return true;
        return false;
    }

    private String text(Sanitizer engine, String input, TestVault vault) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        engine.sanitizeText(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out, vault);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String file(Sanitizer engine, String name, String input, TestVault vault) throws Exception {
        Path in = dir.resolve(name);
        Path out = dir.resolve(name + ".out");
        Files.writeString(in, input, StandardCharsets.UTF_8);
        engine.sanitize(in, out, vault);
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    @Test
    void serialPairsInTextBecomeTheInventorySerialToken() throws Exception {
        String serial = digits(12, 1);
        String alnum = "QZ" + digits(8, 2) + "X";
        String input = "{\"serial\":\"" + serial + "\",\"type\":\"SYSTEM\"}\n"
                + "cpd: device serial=" + serial + " registered\n"
                + "Serial Number: " + alnum + " (chassis)\n"
                + "{\"sn\":" + serial + "}\n";
        String shown = text(new Sanitizer(mappers), input, new TestVault());
        assertFalse(containsAny(shown, serial, alnum), "a serial survived (value withheld)");
        assertTrue(shown.contains("{\"serial\":\"" + tokens.serial(serial) + "\",\"type\":\"SYSTEM\"}"), "the SN- token, the line still JSON");
        assertTrue(shown.contains("device serial=" + tokens.serial(serial) + " registered"));
        assertTrue(shown.contains("Serial Number: " + tokens.serial(alnum) + " (chassis)"));
        assertTrue(shown.contains("{\"sn\":\"" + tokens.serial(serial) + "\"}"), "a bare number becomes a quoted token");
    }

    @Test
    void jsonValuesUnderASerialKeyAreSerialsWhateverTheirForm() throws Exception {
        String serial = digits(12, 3);
        String other = letters(3, 4).toUpperCase() + digits(9, 5);
        String input = "{\"serial\": \"" + serial + "\", \"sn\": " + digits(13, 6) + ", \"device\": {\"Serial_Number\": \""
                + other + "\", \"model\": \"PA-3220\"}, \"note\": \"serial console enabled\", \"count\": 12}";
        String shown = file(new Sanitizer(mappers), "doc.json", input, new TestVault());
        assertFalse(containsAny(shown, serial, other, digits(13, 6)), "a serial survived (value withheld)");
        assertTrue(shown.contains("\"serial\": \"" + tokens.serial(serial) + "\""));
        assertTrue(shown.contains("\"sn\": \"" + tokens.serial(digits(13, 6)) + "\""), "a bare number under a serial key becomes a quoted token");
        assertTrue(shown.contains("\"model\": \"PA-3220\"") && shown.contains("\"note\": \"serial console enabled\"")
                && shown.contains("\"count\": 12"), "other keys and numbers are kept");
    }

    @Test
    void csvColumnsUnderASerialHeaderAreSerials() throws Exception {
        String a = digits(12, 7);
        String b = "FX" + digits(10, 8);
        String input = "name,serial,S/N,model\r\nunit-a," + a + "," + b + ",PA-3220\r\nunit-b,,,PA-850\r\n";
        String shown = file(new Sanitizer(mappers), "inv.csv", input, new TestVault());
        assertFalse(containsAny(shown, a, b), "a serial survived (value withheld)");
        assertTrue(shown.startsWith("name,serial,S/N,model\r\n"), "the header is kept");
        assertTrue(shown.contains("," + tokens.serial(a) + "," + tokens.serial(b) + ",PA-3220\r\n"));
        assertTrue(shown.contains(",,,PA-850\r\n"), "an empty serial cell stays empty");
    }

    @Test
    void theSerialKeyRecognisesItsSpellingsOnly() {
        for (String key : new String[] {"serial", "Serial Number", "serial_number", "serialNo", "SN", "S/N", "@serial", "seri no"}) {
            assertTrue(SerialRule.isSerialKey(key), "a serial key");
        }
        for (String key : new String[] {"serials_seen", "snmp", "sensor", "description", "", "name"}) {
            assertFalse(SerialRule.isSerialKey(key), "not a serial key");
        }
    }

    @Test
    void textWithoutASerialIsUntouchedAndSerialTokensStayOneWayOnRevert() throws Exception {
        Sanitizer engine = new Sanitizer(mappers);
        String plain = "serial console enabled\nsnmpd: started\n{\"serial\":\"\"}\nserial=<redacted>\n";
        assertEquals(plain, text(engine, plain, new TestVault()));

        String serial = digits(12, 9);
        TestVault vault = new TestVault();
        String sanitized = text(engine, "unit serial=" + serial + " src 192.0.2.17\n", vault);
        Path in = dir.resolve("back.txt");
        Path out = dir.resolve("back.out");
        Files.writeString(in, sanitized, StandardCharsets.UTF_8);
        engine.revert(in, out, vault);
        String reverted = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(reverted.contains("src 192.0.2.17"), "the address token reverts through the vault");
        assertTrue(reverted.contains("serial=" + tokens.serial(serial)), "a serial token is one way, like the inventory's");
    }

    @Test
    void theKnownIdentityPassReplacesEstateValuesInAnyFormat() throws Exception {
        String serial = digits(12, 10);
        String name = letters(6, 11) + "-gw-" + digits(2, 12);
        EstateDictionary estate = EstateDictionary.build(List.of(EstateIdentity.serial(serial),
                EstateIdentity.name(name, "FW-BRAVO-02"), EstateIdentity.address("198.51.100.23")), mappers);
        Sanitizer engine = new Sanitizer(mappers, estate);
        String input = "unit " + serial + " joined\nid#" + serial + "#ok\n[" + name + "] sync ok\nmgmt=198.51.100.23\n";
        String shown = text(engine, input, new TestVault());
        assertFalse(containsAny(shown, serial, name, "198.51.100.23"), "an estate identity survived (value withheld)");
        assertTrue(shown.contains("unit " + tokens.serial(serial) + " joined"));
        assertTrue(shown.contains("[FW-BRAVO-02] sync ok"), "a known name gets its UI pseudonym");
        assertFalse(estate.toString().contains(serial), "the dictionary never prints a value");

        String csv = file(engine, "notes.csv", "note\r\nmoved " + name + " to rack 2\r\n", new TestVault());
        assertTrue(csv.contains("moved FW-BRAVO-02 to rack 2"), "the pass runs on structured values too");
        assertEquals("plain line\n", text(new Sanitizer(mappers, EstateDictionary.EMPTY), "plain line\n", new TestVault()));
    }
}
