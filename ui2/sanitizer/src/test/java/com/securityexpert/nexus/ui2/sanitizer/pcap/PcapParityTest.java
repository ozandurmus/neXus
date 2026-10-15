package com.securityexpert.nexus.ui2.sanitizer.pcap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Parity with the frozen PacketBreaker anonymizer (5b608e6, DATA_SANITIZER_CONTRACT.md §4 "byte-identical output on
 * its own test fixtures for its existing modes"). The goldens were produced by the reference itself
 * ({@code tools/sanitizer/pcap_parity_reference.py}): for every synthetic input and each of zero/headers-only with
 * and without IP mapping, the SHA-256 of the reference output, or "error" when the reference refuses the capture.
 * The larger PacketBreaker scenario captures stay outside Git; set {@code PCAP_PARITY_SCENARIO_DIR} to the
 * directory the script's {@code --scenario-dir} wrote to run them too.
 */
class PcapParityTest {

    private record Case(String mode, boolean mapIps, String expect) {
    }

    @Test
    void everyCommittedFixtureMatchesTheReferenceByteForByte() throws Exception {
        String manifest;
        try (InputStream in = getClass().getResourceAsStream("/pcap-parity/manifest.json")) {
            manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        byte[] key = HexFormat.of().parseHex(field(manifest, "key_hex"));
        Matcher fixture = Pattern.compile("\"name\": \"([^\"]+)\",\\s*\"input_base64\": \"([^\"]*)\",\\s*\"cases\": \\[(.*?)\\]",
                Pattern.DOTALL).matcher(manifest);
        int fixtures = 0;
        int cases = 0;
        List<String> failures = new ArrayList<>();
        while (fixture.find()) {
            fixtures++;
            byte[] input = Base64.getDecoder().decode(fixture.group(2));
            for (Case c : cases(fixture.group(3))) {
                cases++;
                String actual = run(input, c, key);
                if (!actual.equals(c.expect())) failures.add(fixture.group(1) + " " + c.mode() + " map_ips=" + c.mapIps());
            }
        }
        assertEquals(20, fixtures, "every committed fixture was read");
        assertEquals(80, cases);
        assertEquals(List.of(), failures, "outputs differing from the reference");
    }

    @Test
    void scenarioCapturesMatchTheReferenceWhenGenerated() throws Exception {
        String dir = System.getenv("PCAP_PARITY_SCENARIO_DIR");
        Assumptions.assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "PCAP_PARITY_SCENARIO_DIR not set");
        String manifest = Files.readString(Path.of(dir, "manifest.json"));
        byte[] key = HexFormat.of().parseHex(field(manifest, "key_hex"));
        Matcher file = Pattern.compile("\"file\": \"([^\"]+)\",\\s*\"cases\": \\[(.*?)\\]", Pattern.DOTALL).matcher(manifest);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        while (file.find()) {
            byte[] input = Files.readAllBytes(Path.of(dir, file.group(1)));
            for (Case c : cases(file.group(2))) {
                checked++;
                if (!run(input, c, key).equals(c.expect())) failures.add(file.group(1) + " " + c.mode() + " " + c.mapIps());
            }
        }
        assertTrue(checked > 0);
        assertEquals(List.of(), failures);
    }

    @Test
    void ipMappingWithoutAKeyIsRefusedAsTheReferenceDoes() {
        assertThrows(PcapException.class, () -> PcapSanitizer.reference(new ByteArrayInputStream(new byte[24]),
                new ByteArrayOutputStream(), PcapSanitizer.ReferenceMode.ZERO, null, true));
    }

    @Test
    void theReferenceIpMapperIsPrefixPreservingAndKeyed() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        var mapper = com.securityexpert.nexus.ui2.sanitizer.core.IpPermutation.reference(key);
        byte[] reversed = key.clone();
        for (int i = 0; i < 16; i++) {
            byte t = reversed[i];
            reversed[i] = reversed[31 - i];
            reversed[31 - i] = t;
        }
        var other = com.securityexpert.nexus.ui2.sanitizer.core.IpPermutation.reference(reversed);
        for (int family : new int[] {4, 16}) {
            for (int i = 0; i < 15; i++) {
                byte[] a = new byte[family];
                byte[] b = new byte[family];
                a[0] = b[0] = 10;
                a[family - 1] = (byte) i;
                b[family - 1] = (byte) (i + 1);
                assertEquals(xorBits(a, b), xorBits(mapper.map(a), mapper.map(b)));
                assertFalse(java.util.Arrays.equals(mapper.map(a), other.map(a)));
            }
        }
    }

    private static int xorBits(byte[] a, byte[] b) {
        return new java.math.BigInteger(1, a).xor(new java.math.BigInteger(1, b)).bitLength();
    }

    private static String run(byte[] input, Case c, byte[] key) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PcapSanitizer.reference(new ByteArrayInputStream(input), out,
                    c.mode().equals("zero") ? PcapSanitizer.ReferenceMode.ZERO : PcapSanitizer.ReferenceMode.HEADERS_ONLY,
                    c.mapIps() ? key : null, c.mapIps());
        } catch (PcapException refused) {
            return "error";
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
    }

    private static List<Case> cases(String json) {
        List<Case> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"mode\": \"([^\"]+)\",\\s*\"map_ips\": (true|false),\\s*\"expect\": \"([^\"]+)\"").matcher(json);
        while (m.find()) out.add(new Case(m.group(1), Boolean.parseBoolean(m.group(2)), m.group(3)));
        return out;
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\": \"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("manifest field missing");
        return m.group(1);
    }

}
