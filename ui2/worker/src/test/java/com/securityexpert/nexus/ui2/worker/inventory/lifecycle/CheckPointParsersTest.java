package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.byLabel;
import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeak;
import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeakPatches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;

/** Synthetic fixtures in the layouts measured on 2026-11-10 (docs/design/LIFECYCLE_READS_MEASUREMENTS_2026_11_10.md). */
class CheckPointParsersTest {

    private static final String[] NEVER_STORED = {"192.0.2.10", "CK-0123456789AB", "CK-AABBCCDDEEFF", "CK-1122334455EE", "77ab12cd",
            "Trial0001", "vs-example"};

    /** A VSX gateway: the context line first, license lines, then the Contract Coverage table with continuation lines. */
    static final String CPLIC_VSX = String.join("\n",
            "Context is set to Virtual Device vs-example.1 (ID 2).",
            "Host        Expiration  Features",
            "",
            "192.0.2.10  never       CPSG-C-4-NGTP CPSB-FW CPSB-IPS CK-0123456789AB",
            "192.0.2.10  30Dec2026   CPSB-AV CPSB-ABOT CK-AABBCCDDEEFF",
            "192.0.2.10  5Jan2027    CPSB-URLF CK-1122334455EE",
            "",
            "Contract Coverage:",
            "#   | ID        | Expiration   | SKU",
            "====+===========+==============+=====================",
            "1   | 77ab12cd  | 28Feb2027    | CPSB-NGTP-APPL-1Y",
            "----------------------------------------------",
            "|Covers: CPSB-FW CPSB-IPS CK-0123456789AB",
            "| CPSB-AV CPSB-ABOT CK-AABBCCDDEEFF",
            "----------------------------------------------",
            "2   | Trial0001 | 9Jun2027     | CPES-SS-PREMIUM-1Y",
            "3   | 12AB34CD  | 31Foo2027    | CPSB-SWB-1Y",
            "");

    @Test
    void cplicPrintReadsLicenseLinesAndContractRowsAndSkipsContextHostKeyAndContinuations() {
        LicenseParse parsed = CheckPointLicenseParser.parse("cp_cplic_print", CPLIC_VSX);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        List<LicenseItem> items = parsed.items();
        assertEquals(6, items.size(), items.toString());

        LicenseItem ngtp = byLabel(items, "CPSG-C-4-NGTP");
        assertEquals("LICENSE", ngtp.category());
        assertEquals("PERMANENT", ngtp.state());
        assertNull(ngtp.expiresOn());
        assertEquals("3 features", ngtp.detail());
        assertEquals("cp_cplic_print", ngtp.readKind());
        assertEquals("DIRECT_DEVICE", ngtp.evidenceGrade());
        assertNull(ngtp.memberRef());

        LicenseItem av = byLabel(items, "CPSB-AV");
        assertEquals("VALID", av.state());
        assertEquals(LocalDate.of(2026, 12, 30), av.expiresOn());
        assertEquals("2 features", av.detail());

        LicenseItem urlf = byLabel(items, "CPSB-URLF");
        assertEquals(LocalDate.of(2027, 1, 5), urlf.expiresOn());
        assertEquals("1 feature", urlf.detail());

        LicenseItem support = byLabel(items, "CPSB-NGTP-APPL-1Y");
        assertEquals("SUPPORT_CONTRACT", support.category());
        assertEquals("VALID", support.state());
        assertEquals(LocalDate.of(2027, 2, 28), support.expiresOn());
        assertNull(support.detail());
        assertEquals(LocalDate.of(2027, 6, 9), byLabel(items, "CPES-SS-PREMIUM-1Y").expiresOn());

        LicenseItem unreadableDate = byLabel(items, "CPSB-SWB-1Y");
        assertEquals("UNKNOWN", unreadableDate.state());
        assertNull(unreadableDate.expiresOn());

        noLeak(items, NEVER_STORED);
        // The continuation lines carry feature names, not contracts: no item is labelled from them.
        assertTrue(items.stream().noneMatch(i -> i.label().equals("CPSB-ABOT")));
    }

    @Test
    void sparkShowLicenseUsesTheSameParserUnderItsOwnReadKind() {
        String spark = String.join("\n",
                "Host        Expiration  Features",
                "192.0.2.10  never       CPSB-FW CPSB-IPS CK-0123456789AB",
                "Contract Coverage:",
                "#   | ID        | Expiration   | SKU",
                "====+===========+==============+=====================",
                "1   | 77ab12cd  | 28Feb2027    | CPSB-NGTP-APPL-1Y",
                "|Covers: CPSB-FW CK-0123456789AB");
        LicenseParse parsed = CheckPointLicenseParser.parse("cp_spark_show_license", spark);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(2, parsed.items().size());
        assertTrue(parsed.items().stream().allMatch(i -> i.readKind().equals("cp_spark_show_license")));
        noLeak(parsed.items(), NEVER_STORED);
    }

    @Test
    void headerWithoutAnyLineAnAnswerWithoutHeaderAndBlankAnswersAreClassified() {
        assertEquals(ParseOutcome.UNPARSED, CheckPointLicenseParser.parse("cp_cplic_print", "Host Expiration Features\n").outcome());
        assertEquals(ParseOutcome.UNPARSED, CheckPointLicenseParser.parse("cp_cplic_print", "cplic: command not found\n").outcome());
        assertEquals(ParseOutcome.EMPTY, CheckPointLicenseParser.parse("cp_cplic_print", "  \n \n").outcome());
        assertEquals(ParseOutcome.EMPTY, CheckPointLicenseParser.parse("cp_cplic_print", null).outcome());
    }

    @Test
    void aLabelThatCannotBeCleanIsSkippedAndCounted() {
        String text = String.join("\n",
                "Host Expiration Features",
                "192.0.2.10  never  BAD!TOKEN CK-0123456789AB",
                "192.0.2.10  never  CPSB-FW CK-0123456789AB",
                "Contract Coverage:",
                "1 | 77ab12cd | 28Feb2027 | SKU#BAD");
        LicenseParse parsed = CheckPointLicenseParser.parse("cp_cplic_print", text);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(1, parsed.items().size());
        assertEquals(2, parsed.skipped());

        String onlyBad = "Host Expiration Features\n192.0.2.10 never BAD!TOKEN CK-0123456789AB\n";
        LicenseParse none = CheckPointLicenseParser.parse("cp_cplic_print", onlyBad);
        assertEquals(ParseOutcome.UNPARSED, none.outcome());
        assertEquals(1, none.skipped());
    }

    static final String CPLP = String.join("\n",
            "ID(PATCH:PROC)                  STATUS     MODE       PIDS   INSTALLED            COMMENT",
            "-------------------------------------------------------------------------------------------",
            "sk181311+v1:fwd_proc  jumbofix   automatic  2/2    2026-10-01 08:15:33  sk181311",
            "sk182222:cpd          armed      automatic  1/1    2026-09-12 10:00:01  CVE-2026-1234 cve-2026-1234 sk182222 CVE-2025-12345",
            "sk183333:dlpd         ready      automatic  0/2    2026-09-30 22:01:02  sk183333,",
            "sk184444:mpd          frozen     manual     1/1    2026-09-30 22:01:02  see CVE-2026-9999.",
            "sk185555:xyz          nopatch    automatic  1/1    30/09/2026 22:01     CVE-2026-1",
            "* Patches in status 'ready' are installed and wait for their process (see 'cplp list --help').",
            "");

    @Test
    void cplpListReadsRowsAndReferencesAndIgnoresRuleLineAndClosingNote() {
        LivePatchParse parsed = CheckPointLivePatchParser.parse(CPLP);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        List<LivePatchItem> items = parsed.items();
        assertEquals(5, items.size(), items.toString());

        LivePatchItem first = items.get(0);
        assertEquals("sk181311_v1:fwd_proc", first.patchId());
        assertEquals("jumbofix", first.status());
        assertEquals("automatic", first.mode());
        assertEquals(2, first.pidsRunning());
        assertEquals(2, first.pidsTotal());
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 15, 33), first.installedAt());
        assertEquals(List.of("sk181311"), first.refs());

        LivePatchItem second = items.get(1);
        assertEquals("armed", second.status());
        assertEquals(List.of("CVE-2026-1234", "sk182222", "CVE-2025-12345"), second.refs(), "upper-cased, in order, no duplicates");

        LivePatchItem ready = items.get(2);
        assertEquals("ready", ready.status());
        assertEquals(0, ready.pidsRunning());
        assertEquals(2, ready.pidsTotal());
        assertEquals(List.of("sk183333"), ready.refs());

        LivePatchItem unknown = items.get(3);
        assertEquals("UNKNOWN", unknown.status(), "a status outside the closed set");
        assertEquals("manual", unknown.mode());
        assertEquals(List.of("CVE-2026-9999"), unknown.refs());

        LivePatchItem odd = items.get(4);
        assertEquals("nopatch", odd.status());
        assertNull(odd.installedAt(), "an installed time in another layout is not guessed");
        assertEquals(List.of(), odd.refs(), "a CVE with fewer than four digits is not a reference");
        noLeakPatches(items, "192.0.2");
    }

    @Test
    void cplpListWithHeaderAndNoRowsIsCollectedEmptyAndWithoutHeaderIsUnparsed() {
        String headerOnly = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n------------\n"
                + "* Patches in status 'ready' are installed and wait for their process.\n";
        LivePatchParse none = CheckPointLivePatchParser.parse(headerOnly);
        assertEquals(ParseOutcome.COLLECTED, none.outcome());
        assertEquals(List.of(), none.items());
        assertEquals(ParseOutcome.UNPARSED, CheckPointLivePatchParser.parse("cplp: command not found\n").outcome());
        assertEquals(ParseOutcome.EMPTY, CheckPointLivePatchParser.parse("").outcome());
        assertEquals(ParseOutcome.EMPTY, CheckPointLivePatchParser.parse(null).outcome());
    }

    @Test
    void cplpListKeepsOneRowPerPatchId() {
        String text = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n"
                + "sk1:a armed automatic 1/1 2026-01-01 00:00:00 sk1\n"
                + "sk1:a ready automatic 0/1 2026-01-02 00:00:00 sk1\n";
        assertEquals(1, CheckPointLivePatchParser.parse(text).items().size());
    }
}
