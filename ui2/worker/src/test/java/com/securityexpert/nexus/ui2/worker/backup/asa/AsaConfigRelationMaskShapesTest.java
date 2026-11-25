package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.securityexpert.nexus.ui2.worker.backup.asa.AsaConfigRelation.Result;

/**
 * Pins the secret-bearing line shapes the "keys print as the five-character mask in the startup read" assumption of
 * {@link AsaConfigRelation} rests on. Automated validation only: the mask token itself is still unconfirmed on a real unit.
 * A shape marked {@code assumed} is one whose exact command form (or whose masked printing) the author could not confirm
 * from the repository's own fixtures or the command reference; it is kept so a later real-unit read can be compared with it.
 * Synthetic data only; every key is assembled at run time.
 */
class AsaConfigRelationMaskShapesTest {

    private static final String M = AsaConfigRelation.MASK;
    private static final String KEY = String.join("", "Qa", "7z", "Lp", "3x");
    private static final String KEY_WITH_SPACES = String.join(" ", "Qa7z", "Lp3x", "Rb8y");

    /** One secret-bearing line: an optional parent command, then the line with {@code {K}} where the secret stands. */
    record Shape(String name, boolean assumed, String parent, String line) {
        String label() {
            return name + (assumed ? " (assumed)" : "");
        }

        List<String> lines(String key) {
            List<String> out = new ArrayList<>();
            if (parent != null) {
                out.add(parent);
            }
            out.add(line.replace("{K}", key));
            out.add("interface GigabitEthernet0/0");
            return out;
        }
    }

    static final List<Shape> SHAPES = List.of(
            new Shape("enable password", true, null, "enable password {K}"),
            new Shape("username password privilege", true, null, "username FW-TANGO-04-admin password {K} privilege 15"),
            new Shape("snmp-server community", false, null, "snmp-server community {K}"),
            new Shape("snmp-server host community", false, null,
                    "snmp-server host inside 192.0.2.9 community {K} version 2c"),
            new Shape("ikev1 pre-shared-key", false, "tunnel-group 192.0.2.7 ipsec-attributes", " ikev1 pre-shared-key {K}"),
            new Shape("ikev2 remote-authentication pre-shared-key", false, "tunnel-group 192.0.2.7 ipsec-attributes",
                    " ikev2 remote-authentication pre-shared-key {K}"),
            new Shape("ikev2 local-authentication pre-shared-key", false, "tunnel-group 192.0.2.7 ipsec-attributes",
                    " ikev2 local-authentication pre-shared-key {K}"),
            new Shape("aaa-server host key", false, "aaa-server RAD-SYNTH (inside) host 198.51.100.50", " key {K}"),
            new Shape("aaa-server one-line host key", true, null,
                    "aaa-server RAD-SYNTH (inside) host 198.51.100.50 key {K} timeout 10"),
            new Shape("ldap-login-password", false, "aaa-server LDAP-SYNTH (inside) host 198.51.100.51",
                    " ldap-login-password {K}"),
            new Shape("failover key", false, null, "failover key {K}"),
            new Shape("ntp authentication-key md5", true, null, "ntp authentication-key 1 md5 {K}"),
            new Shape("crypto isakmp key address", true, null, "crypto isakmp key {K} address 192.0.2.7"),
            new Shape("passwd", true, null, "passwd {K}"),
            new Shape("radius-common-pw", false, "aaa-server RAD-SYNTH (inside) host 198.51.100.50",
                    " radius-common-pw {K}"),
            new Shape("ospf message-digest-key md5", false, "interface GigabitEthernet0/1",
                    " ospf message-digest-key 1 md5 {K}"),
            new Shape("ospf authentication-key", false, "interface GigabitEthernet0/1", " ospf authentication-key {K}"),
            new Shape("eigrp authentication key", true, "interface GigabitEthernet0/1",
                    " authentication key eigrp 100 {K} key-id 1"));

    static Stream<Shape> shapes() {
        return SHAPES.stream();
    }

    private static String running(List<String> lines) {
        return ": Saved\n: Hardware:   FPR4112\n:\nASA Version 9.22(1)\n!\nhostname FW-TANGO-04\n" + String.join("\n", lines)
                + "\nCryptochecksum:00000000111111112222222233333333\n: end\n";
    }

    private static String startup(List<String> lines) {
        return ": Saved\n: Written by enable_15 at 10:00:00.000 UTC Mon Nov 16 2026\nASA Version 9.22(1)\n!\n"
                + "hostname FW-TANGO-04\n" + String.join("\n", lines) + "\n\nCryptochecksum:99999999888888887777777766666666\n";
    }

    private static AsaConfigRelation compare(List<String> runningLines, List<String> startupLines) {
        return AsaConfigRelation.compare(running(runningLines), startup(startupLines));
    }

    @Test
    void theCatalogueSizeIsPinned() {
        assertEquals(18, SHAPES.size());
        assertEquals(7, SHAPES.stream().filter(Shape::assumed).count());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void aMaskedStartupLineMatchesItsClearRunningLine(Shape shape) {
        AsaConfigRelation r = compare(shape.lines(KEY), shape.lines(M));
        assertEquals(Result.MATCH, r.result(), shape.label());
        assertEquals(1, r.linesEqualOnlyByMask(), shape.label());
        assertEquals(0, r.linesDiffering(), shape.label());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void aDifferentFixedPieceIsAMismatch(Shape shape) {
        List<String> changed = new ArrayList<>(shape.lines(M));
        int at = changed.indexOf(shape.line().replace("{K}", M));
        // the first lower-case word of the secret line gets a suffix: the fixed text differs, the mask is intact
        changed.set(at, changed.get(at).replaceFirst("([a-z]+)", "$1x"));
        AsaConfigRelation r = compare(shape.lines(KEY), changed);
        assertEquals(Result.MISMATCH, r.result(), shape.label());
        assertEquals(2, r.linesDiffering(), shape.label());
        assertEquals(0, r.linesEqualOnlyByMask(), shape.label());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void aMaskAgainstAnEmptyRunningValueIsAMismatch(Shape shape) {
        AsaConfigRelation r = compare(shape.lines(""), shape.lines(M));
        assertEquals(Result.MISMATCH, r.result(), shape.label());
        assertEquals(0, r.linesEqualOnlyByMask(), shape.label());
        assertTrue(r.linesDiffering() >= 1, shape.label());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void aClearKeyWithSpacesIsStillOneValue(Shape shape) {
        // pinned as observed: the mask stands for any non-empty text, spaces included
        AsaConfigRelation r = compare(shape.lines(KEY_WITH_SPACES), shape.lines(M));
        assertEquals(Result.MATCH, r.result(), shape.label());
        assertEquals(1, r.linesEqualOnlyByMask(), shape.label());
    }

    @Test
    void everyShapeInOneConfigurationCountsOnceEach() {
        List<String> clear = new ArrayList<>();
        List<String> masked = new ArrayList<>();
        for (Shape s : SHAPES) {
            clear.addAll(s.lines(KEY));
            masked.addAll(s.lines(M));
        }
        AsaConfigRelation r = compare(clear, masked);
        assertEquals(Result.MATCH, r.result());
        assertEquals(SHAPES.size(), r.linesEqualOnlyByMask());
        assertEquals(0, r.linesDiffering());
    }

    @Test
    void anOtherwiseIdenticalConfigurationWithOneRealChangeStaysOneDifference() {
        List<String> clear = new ArrayList<>();
        List<String> masked = new ArrayList<>();
        for (Shape s : SHAPES) {
            clear.addAll(s.lines(KEY));
            masked.addAll(s.lines(M));
        }
        clear.add("object network host-1");
        AsaConfigRelation r = compare(clear, masked);
        assertEquals(Result.MISMATCH, r.result());
        assertEquals(1, r.linesDiffering());
        assertEquals(SHAPES.size(), r.linesEqualOnlyByMask());
    }

    @Test
    void severalAdjacentLinesOfTheSameShapeEachPairOnce() {
        List<String> clear = List.of("snmp-server community " + KEY, "snmp-server community " + KEY_WITH_SPACES);
        List<String> masked = List.of("snmp-server community " + M, "snmp-server community " + M);
        AsaConfigRelation r = compare(clear, masked);
        assertEquals(Result.MATCH, r.result());
        assertEquals(2, r.linesEqualOnlyByMask());
    }

    // ---- shapes the mask rule cannot decide: pinned as observed, not "fixed" ----

    @Test
    void aSecretThatContainsTheFixedPieceNextToTheMaskIsStillAMatch() {
        // The mask absorbs the whole run between the fixed pieces, so a secret that repeats the trailing fixed text
        // cannot make the line differ: observed MATCH. It also means a real difference hidden inside the secret is
        // invisible by design: the relation never sees the key.
        String line = "snmp-server host inside 192.0.2.9 community {K} version 2c";
        AsaConfigRelation r = compare(List.of(line.replace("{K}", KEY + " version 2c")), List.of(line.replace("{K}", M)));
        assertEquals(Result.MATCH, r.result());
        assertEquals(1, r.linesEqualOnlyByMask());
        // two masks in one line, the first secret holds the text between them
        AsaConfigRelation two = compare(List.of("x " + KEY + " mid " + KEY + " mid " + KEY + " end"), List.of("x " + M + " mid " + M + " end"));
        assertEquals(Result.MATCH, two.result());
    }

    @Test
    void aSecretMadeOnlyOfTheFixedPieceAfterTheMaskIsAMismatch() {
        // "key <mask> extra" against "key extra": nothing is left for the mask, observed MISMATCH
        AsaConfigRelation r = compare(List.of("key extra"), List.of("key " + M + " extra"));
        assertEquals(Result.MISMATCH, r.result());
    }

    @Test
    void aHashThatPrintsIdenticallyInBothReadsNeedsNoMask() {
        String hash = String.join("", "ab", "Cd", "12", "Ef", "34");
        String line = "username FW-TANGO-04-admin password " + hash + " pbkdf2 privilege 15";
        AsaConfigRelation same = compare(List.of(line), List.of(line));
        assertEquals(Result.MATCH, same.result());
        assertEquals(0, same.linesEqualOnlyByMask(), "equal text is not counted as equal-by-mask");
        String other = line.replace(hash, hash + "9");
        AsaConfigRelation differ = compare(List.of(line), List.of(other));
        assertEquals(Result.MISMATCH, differ.result());
        assertEquals(2, differ.linesDiffering());
    }

    @Test
    void aMaskInTheStartupReadWhereRunningHoldsAHashIsAMatchByMaskAndCannotBeChecked() {
        // Per the class Javadoc a hash prints identically in both reads, so a mask next to a running-side hash is not
        // expected. If it ever happens the rule cannot tell the two values apart: observed MATCH, counted as equal-by-mask.
        // The count (linesEqualOnlyByMask) is how a real-unit read would reveal the case.
        String hash = String.join("", "ab", "Cd", "12", "Ef", "34");
        for (String marker : List.of("pbkdf2", "encrypted")) {
            AsaConfigRelation r = compare(List.of("username FW-TANGO-04-admin password " + hash + " " + marker + " privilege 15"),
                    List.of("username FW-TANGO-04-admin password " + M + " " + marker + " privilege 15"));
            assertEquals(Result.MATCH, r.result(), marker);
            assertEquals(1, r.linesEqualOnlyByMask(), marker);
        }
        // a mask at the end of the line also absorbs the running line's trailing marker: observed MATCH, undecidable
        AsaConfigRelation r = compare(List.of("enable password " + hash + " encrypted"), List.of("enable password " + M));
        assertEquals(Result.MATCH, r.result());
        assertEquals(1, r.linesEqualOnlyByMask());
    }

    @Test
    void aKeyThatIsOnlySpacesIsEmpty() {
        AsaConfigRelation r = compare(List.of("snmp-server community     "), List.of("snmp-server community " + M));
        assertEquals(Result.MISMATCH, r.result());
    }

    @Test
    void aKeyThatContainsTheMaskTokenIsStillOneValue() {
        AsaConfigRelation r = compare(List.of("failover key a" + M + "b"), List.of("failover key " + M));
        assertEquals(Result.MATCH, r.result());
        assertEquals(1, r.linesEqualOnlyByMask());
    }

    // ---- volume: the mask pairing must not give up on a long block of secret-bearing lines ----

    @Test
    void aLongBlockOfSecretLinesPairsEveryLine() {
        for (int n : new int[] {500, 1500, 3000, 6000}) {
            List<String> clear = new ArrayList<>();
            List<String> masked = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                clear.add("username FW-TANGO-04-user" + i + " password " + KEY + i + " privilege 3");
                masked.add("username FW-TANGO-04-user" + i + " password " + M + " privilege 3");
            }
            AsaConfigRelation r = compare(clear, masked);
            assertEquals(Result.MATCH, r.result(), "n=" + n);
            assertEquals(n, r.linesEqualOnlyByMask(), "n=" + n);
            assertEquals(0, r.linesDiffering(), "n=" + n);
        }
    }
}
