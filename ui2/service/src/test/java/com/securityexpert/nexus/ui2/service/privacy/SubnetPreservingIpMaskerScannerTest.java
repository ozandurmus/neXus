package com.securityexpert.nexus.ui2.service.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Differential proof that the hand-written IPv4 scanners in {@link SubnetPreservingIpMasker} (CodeQL
 * java/polynomial-redos, alert 46) match exactly what the former regular expressions matched. The old patterns live
 * only here, as the oracle.
 */
class SubnetPreservingIpMaskerScannerTest {

    private static final Pattern OLD_IPV4_PATTERN =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?$");
    private static final Pattern OLD_EMBEDDED_IPV4_PATTERN =
            Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?:/\\d{1,2})?)\\b");

    private static final byte[] KEY = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);

    private static final String NSM = "́";                 // combining acute accent (BMP non-spacing mark)
    private static final String SUPPLEMENTARY_NSM = "𝅧"; // U+1D167, a non-spacing mark outside the BMP

    private static List<String> trickyInputs() {
        List<String> inputs = new ArrayList<>(List.of(
                "", " ", ".", "/", "1", "1.2.3", "1.2.3.4", "1.2.3.4/", "1.2.3.4/2", "1.2.3.4/24", "1.2.3.4/33",
                "1.2.3.4/333", "1.2.3.4/24a", "1.2.3.4/2a", "1.2.3.4/a", "1.2.3.4//24", "1.2.3.4/24/8",
                "1.2.3.4.5", "1.2.3.4.5.6.7.8", "1.2.3.4.", ".1.2.3.4", "..1.2.3.4..", "1..2.3.4", "1.2.3..4",
                "999.1.1.1", "1.1.1.999", "256.0.0.1", "0.0.0.0/0", "000.000.000.000/00", "01.02.03.04/08",
                "1234.1.1.1", "1.1.1.1234", "1.1234.1.1", "11.22.33.44 55.66.77.88/16",
                "a1.2.3.4", "1.2.3.4a", "_1.2.3.4", "1.2.3.4_", "a 1.2.3.4 b", "x1.2.3.4y 5.6.7.8",
                "ip=192.0.2.1,gw=192.0.2.254;mask=255.255.255.0", "(198.51.100.7/24)", "[203.0.113.9]:443",
                "host-192-0-2-1", "192.0.2.1-192.0.2.9", "192.0.2.1:8080", "v1.2.3.4", "version 1.2.3.4.5",
                "é1.2.3.4", "1.2.3.4é", "ß 1.2.3.4 ß", "日本1.2.3.4語", "١٢.1.2.3",
                "1.2.3.٤", "١1.2.3.4", "1.2.3.4١", "1.2.3.4" + NSM, NSM + "1.2.3.4",
                "a" + NSM + "1.2.3.4", " " + NSM + "1.2.3.4", "x." + NSM + "1.2.3.4", "1" + NSM + NSM + "1.2.3.4",
                "1.2.3.4/24" + NSM, "1.2.3.4/" + NSM + "24", SUPPLEMENTARY_NSM + "1.2.3.4",
                "a" + SUPPLEMENTARY_NSM + "1.2.3.4", "1.2.3.4" + SUPPLEMENTARY_NSM, "1.2.3.4/2" + SUPPLEMENTARY_NSM,
                "\uD8001.2.3.4", "1.2.3.4\uDC00", "fe80::1", "::ffff:192.0.2.1", "2001:db8::192.0.2.33",
                "::ffff:1.2.3.4/96", "1.2.3.4\n", "\t1.2.3.4\t", "1.2.3.4\u0085", "1.2.3.4 ",
                "1.2.3.4 \n 5.6.7.8/30"));
        inputs.add(".".repeat(10_000));
        inputs.add("1".repeat(10_000));
        inputs.add("1.".repeat(5_000));
        inputs.add("12.".repeat(3_000) + "4");
        inputs.add("1.2.3.".repeat(2_000));
        inputs.add("123".repeat(3_000) + ".1.1.1");
        inputs.add("1.1.1.1/" + "9".repeat(5_000));
        inputs.add("a".repeat(5_000) + "1.2.3.4" + "b".repeat(5_000));
        return inputs;
    }

    @Test
    void fullStringParseMatchesTheFormerPatternOnTrickyInputs() {
        for (String input : trickyInputs()) {
            assertSameParse(input);
        }
    }

    @Test
    void embeddedScanMatchesTheFormerPatternOnTrickyInputs() {
        for (String input : trickyInputs()) {
            assertSameEmbeddedTokens(input);
            assertSameMaskText(input);
        }
    }

    @Test
    void bothScannersMatchTheFormerPatternsOnRandomStrings() {
        String[] alphabet = {"0", "1", "2", "5", "9", ".", ".", ".", "/", "a", "Z", "_", " ", "-", ":", ",", "é",
                "١", NSM, SUPPLEMENTARY_NSM, "\n", "日"};
        String[] ipish = {"0", "1", "2", "3", "5", "9", ".", "/"};
        Random random = new Random(20261125L);
        for (int n = 0; n < 10_000; n++) {
            String text = randomString(random, alphabet, 60);
            assertSameEmbeddedTokens(text);
            assertSameParse(text);
            String dense = randomString(random, ipish, 24);
            assertSameEmbeddedTokens(dense);
            assertSameParse(dense);
            String shaped = randomAddressShape(random);
            assertSameParse(shaped);
            assertSameEmbeddedTokens(shaped);
        }
        SubnetPreservingIpMasker scanner = new SubnetPreservingIpMasker(KEY);
        SubnetPreservingIpMasker oracle = new SubnetPreservingIpMasker(KEY);
        for (int n = 0; n < 2_000; n++) {
            String text = randomString(random, alphabet, 80) + " " + randomAddressShape(random) + " "
                    + randomString(random, alphabet, 20);
            assertThat(scanner.maskText(text)).as("maskText(%s)", text).isEqualTo(oldMaskText(oracle, text));
            String shaped = randomAddressShape(random);
            assertThat(scanner.mask(shaped)).as("mask(%s)", shaped).isEqualTo(oracle.mask(shaped));
        }
    }

    @Test
    void longRunsOfDotsAndDigitsAreProcessedInLinearTime() {
        Random random = new Random(7L);
        StringBuilder mixed = new StringBuilder(200_000);
        while (mixed.length() < 200_000) {
            mixed.append(random.nextInt(3) == 0 ? '.' : (char) ('0' + random.nextInt(10)));
        }
        String[] inputs = {".".repeat(200_000), "1.".repeat(100_000), "1".repeat(200_000), "1.1.1".repeat(40_000),
                "11.".repeat(66_667), mixed.toString()};
        SubnetPreservingIpMasker masker = new SubnetPreservingIpMasker(KEY);
        for (String input : inputs) {
            long started = System.nanoTime();
            masker.maskText(input);
            masker.mask(input);
            masker.peek(input);
            masker.registerSubnet(input);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(elapsedMillis).as("%d chars starting %s", input.length(), input.substring(0, 12))
                    .isLessThan(1_000L);
        }
    }

    private static void assertSameParse(String input) {
        Matcher matcher = OLD_IPV4_PATTERN.matcher(input);
        SubnetPreservingIpMasker.ParsedIpv4 parsed = SubnetPreservingIpMasker.parseIpv4(input);
        if (!matcher.matches()) {
            assertThat(parsed).as("parse(%s)", input).isNull();
            return;
        }
        assertThat(parsed).as("parse(%s)", input).isEqualTo(new SubnetPreservingIpMasker.ParsedIpv4(
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), matcher.group(5)));
    }

    private static void assertSameEmbeddedTokens(String text) {
        List<String> expected = new ArrayList<>();
        Matcher matcher = OLD_EMBEDDED_IPV4_PATTERN.matcher(text);
        while (matcher.find()) {
            expected.add(matcher.start(1) + ":" + matcher.end(1) + ":" + matcher.group(1));
        }
        List<String> actual = new ArrayList<>();
        int from = 0;
        int[] span;
        while ((span = SubnetPreservingIpMasker.findEmbeddedIpv4(text, from)) != null) {
            actual.add(span[0] + ":" + span[1] + ":" + text.substring(span[0], span[1]));
            from = span[1];
        }
        assertThat(actual).as("tokens of %s", abbreviate(text)).isEqualTo(expected);
    }

    private static void assertSameMaskText(String text) {
        SubnetPreservingIpMasker scanner = new SubnetPreservingIpMasker(KEY);
        SubnetPreservingIpMasker oracle = new SubnetPreservingIpMasker(KEY);
        assertThat(scanner.maskText(text)).as("maskText(%s)", abbreviate(text)).isEqualTo(oldMaskText(oracle, text));
    }

    /** The former maskText body, driven by the former pattern, on a masker with the same key. */
    private static String oldMaskText(SubnetPreservingIpMasker masker, String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = OLD_EMBEDDED_IPV4_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masker.mask(matcher.group(1))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String randomString(Random random, String[] alphabet, int maxLength) {
        int length = random.nextInt(maxLength + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    /** Near-addresses: 3-5 groups of 0-4 digits, sometimes a /length of 0-3 digits, sometimes a stray suffix. */
    private static String randomAddressShape(Random random) {
        StringBuilder sb = new StringBuilder();
        int groups = 3 + random.nextInt(3);
        for (int g = 0; g < groups; g++) {
            if (g > 0) {
                sb.append('.');
            }
            sb.append(digits(random, random.nextInt(10) == 0 ? random.nextInt(5) : 1 + random.nextInt(3)));
        }
        if (random.nextBoolean()) {
            sb.append('/').append(digits(random, random.nextInt(4)));
        }
        if (random.nextInt(4) == 0) {
            sb.append("a_. /é".charAt(random.nextInt(6)));
        }
        return sb.toString();
    }

    private static String digits(Random random, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append((char) ('0' + random.nextInt(random.nextBoolean() ? 3 : 10)));
        }
        return sb.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...(" + text.length() + " chars)";
    }
}
