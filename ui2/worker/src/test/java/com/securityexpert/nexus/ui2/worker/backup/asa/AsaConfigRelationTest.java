package com.securityexpert.nexus.ui2.worker.backup.asa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.backup.asa.AsaConfigRelation.Result;

/** Synthetic configurations only; secret-shaped values are assembled at run time. */
class AsaConfigRelationTest {

    private static final String M = AsaConfigRelation.MASK;
    // Run-time assembled, so no secret-shaped literal sits in the source.
    private static final String CLEAR_KEY = String.join("", "Qa", "7z", "Lp", "3x");
    private static final String CLEAR_KEY_2 = String.join("", "Rb", "8y", "Mq", "4w");

    private static String running(String... lines) {
        return ": Saved\n: Hardware:   FPR4112\n:\nASA Version 9.22(1)\n!\nhostname FW-TANGO-04\n" + String.join("\n", lines)
                + "\nCryptochecksum:00000000111111112222222233333333\n: end\n";
    }

    private static String startup(String... lines) {
        return ": Saved\n: Written by enable_15 at 10:00:00.000 UTC Mon Nov 16 2026\nASA Version 9.22(1)\n!\n"
                + "hostname FW-TANGO-04\n" + String.join("\n", lines) + "\n\nCryptochecksum:99999999888888887777777766666666\n";
    }

    @Test
    void headerAndChecksumDifferencesAreIgnored() {
        AsaConfigRelation r = AsaConfigRelation.compare(running("interface Gi0/0", " nameif outside"),
                startup("interface Gi0/0", " nameif outside"));
        assertEquals(Result.MATCH, r.result());
        assertEquals(0, r.linesDiffering());
        assertEquals(0, r.linesEqualOnlyByMask());
        assertEquals(5, r.linesCompared());
    }

    @Test
    void trailingWhitespaceAndBlankLinesAreIgnored() {
        assertEquals(Result.MATCH, AsaConfigRelation.compare(running("same line   ", "", "other"),
                startup("same line", "other")).result());
    }

    @Test
    void maskedKeysAreEqualWhenTheFixedPiecesMatch() {
        AsaConfigRelation r = AsaConfigRelation.compare(
                running("tunnel-group 192.0.2.7 ipsec-attributes", " ikev1 pre-shared-key " + CLEAR_KEY,
                        "snmp-server host inside 192.0.2.9 community " + CLEAR_KEY_2 + " version 2c"),
                startup("tunnel-group 192.0.2.7 ipsec-attributes", " ikev1 pre-shared-key " + M,
                        "snmp-server host inside 192.0.2.9 community " + M + " version 2c"));
        assertEquals(Result.MATCH, r.result());
        assertEquals(2, r.linesEqualOnlyByMask());
        assertEquals(0, r.linesDiffering());
    }

    @Test
    void severalMasksInOneLineAreMatchedInOrder() {
        assertEquals(Result.MATCH, AsaConfigRelation.compare(
                running("x " + CLEAR_KEY + " mid " + CLEAR_KEY_2 + " end"), startup("x " + M + " mid " + M + " end")).result());
        assertEquals(Result.MISMATCH, AsaConfigRelation.compare(
                running("x " + CLEAR_KEY + " other " + CLEAR_KEY_2 + " end"), startup("x " + M + " mid " + M + " end")).result());
    }

    @Test
    void aMaskNeedsAValueAndTheSurroundingTextMustMatch() {
        assertEquals(Result.MISMATCH, AsaConfigRelation.compare(running("key "), startup("key " + M)).result());
        assertEquals(Result.MISMATCH, AsaConfigRelation.compare(running("key " + CLEAR_KEY + " extra"), startup("key " + M + " fixed")).result());
        assertEquals(Result.MISMATCH, AsaConfigRelation.compare(running("other " + CLEAR_KEY), startup("key " + M)).result());
    }

    @Test
    void aRealChangedLineIsAMismatch() {
        AsaConfigRelation r = AsaConfigRelation.compare(running("interface Gi0/0", " nameif inside"),
                startup("interface Gi0/0", " nameif outside"));
        assertEquals(Result.MISMATCH, r.result());
        assertEquals(2, r.linesDiffering(), "a changed line is one deleted plus one inserted line");
    }

    @Test
    void anExtraRunningLineIsAMismatch() {
        AsaConfigRelation r = AsaConfigRelation.compare(running("a", "b"), startup("a"));
        assertEquals(Result.MISMATCH, r.result());
        assertEquals(1, r.linesDiffering());
    }

    @Test
    void missingOrForeignTextIsNotEvaluable() {
        assertEquals(Result.NOT_EVALUABLE, AsaConfigRelation.compare(running("a"), null).result());
        assertEquals(Result.NOT_EVALUABLE, AsaConfigRelation.compare(null, startup("a")).result());
        assertEquals(Result.NOT_EVALUABLE, AsaConfigRelation.compare(running("a"), "").result());
        assertEquals(Result.NOT_EVALUABLE, AsaConfigRelation.compare(running("a"), "ERROR: no configuration\n").result());
        assertEquals(Result.NOT_EVALUABLE, AsaConfigRelation.compare("hostname FW-TANGO-04\n", startup("a")).result());
    }

    @Test
    void oneInsertedLineIsOneDifferenceNotACascade() {
        String[] base = new String[400];
        for (int i = 0; i < base.length; i++) {
            base[i] = "object network host-" + i;
        }
        String[] shifted = new String[base.length + 1];
        System.arraycopy(base, 0, shifted, 0, 10);
        shifted[10] = "snmp-server enable";
        System.arraycopy(base, 10, shifted, 11, base.length - 10);
        AsaConfigRelation r = AsaConfigRelation.compare(running(shifted), startup(base));
        assertEquals(Result.MISMATCH, r.result());
        assertEquals(1, r.linesDiffering());
        assertEquals(java.util.Map.of("snmp-server", 1), r.differingKeywords());
    }

    @Test
    void oneRemovedLineAndMaskedKeysTogether() {
        AsaConfigRelation r = AsaConfigRelation.compare(
                running("a", "b", "c", "username admin password " + CLEAR_KEY + " privilege 15", "d"),
                startup("a", "c", "username admin password " + M + " privilege 15", "d"));
        assertEquals(1, r.linesDiffering());
        assertEquals(1, r.linesEqualOnlyByMask());
    }

    @Test
    void certificateBlockDifferencesCountUnderTheirCommandNeverByValue() {
        String hex1 = "  3082" + CLEAR_KEY + "aa";
        String hex2 = "  3082" + CLEAR_KEY_2 + "bb";
        AsaConfigRelation r = AsaConfigRelation.compare(
                running("crypto ca certificate chain TP-1", " certificate ca 0a1b", hex1, hex2, "  quit", "object network o1"),
                startup("crypto ca certificate chain TP-1", " certificate ca 0a1b", "  quit", "object network o1"));
        assertEquals(Result.MISMATCH, r.result());
        assertEquals(2, r.linesDiffering());
        assertEquals(java.util.Map.of("crypto", 2), r.differingKeywords());
        assertEquals(false, r.differingKeywords().toString().contains(CLEAR_KEY));
    }

    @Test
    void aValueIsNeverAKeywordAndNoSkipsToTheCommand() {
        AsaConfigRelation r = AsaConfigRelation.compare(
                running("no access-list x", "Zq9-Key" + CLEAR_KEY, "object network a"),
                startup("object network a"));
        assertEquals(java.util.Map.of("access-list", 1, "other", 1), r.differingKeywords());
    }

    @Test
    void aMovedBlockIsBoundedAndNotACascade() {
        java.util.List<String> a = new java.util.ArrayList<>();
        java.util.List<String> b = new java.util.ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            a.add("access-list acl-" + i + " permit ip any any");
            b.add("access-list acl-" + (5999 - i) + " permit ip any any");
        }
        AsaConfigRelation r = AsaConfigRelation.compare(running(a.toArray(String[]::new)), startup(b.toArray(String[]::new)));
        // beyond the exact bound: the order-free fallback; the same lines are present, so nothing differs
        assertEquals(Result.MATCH, r.result());
    }

    @Test
    void keywordsAreTheTopTenOnly() {
        String[] extra = new String[15];
        for (int i = 0; i < extra.length; i++) {
            extra[i] = "cmd" + (char) ('a' + i) + " x";
        }
        AsaConfigRelation r = AsaConfigRelation.compare(running(extra), startup("same"));
        assertEquals(10, r.differingKeywords().size());
    }
}
