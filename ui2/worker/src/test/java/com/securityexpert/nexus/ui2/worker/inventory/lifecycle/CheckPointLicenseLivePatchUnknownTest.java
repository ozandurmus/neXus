package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.byLabel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;

/**
 * Pins the UNKNOWN handling of the Check Point license and live-patch parsers: a missing or malformed expiry, a missing
 * contract field, and an empty or header-only answer never yield a present-and-valid state. Synthetic values only; the
 * secret-shaped key is built at runtime.
 */
class CheckPointLicenseLivePatchUnknownTest {

    private static final String[] READ_KINDS = {"cp_cplic_print", "cp_spark_show_license"};

    private static final List<String> STATUS_KNOWN = List.of("armed", "ready", "reverted", "jumbofix", "unsupp", "nopatch", "nolib");

    private static String key() {
        return "CK-" + "5566778899AA";
    }

    private static List<LicenseItem> license(String readKind, String text) {
        return CheckPointLicenseParser.parse(readKind, text).items();
    }

    @Test
    void aLicenseLineWithoutAnExpiryIsNotAnItem() {
        for (String readKind : READ_KINDS) {
            // No expiry column and no key: the second token is a feature, so the line is not a license line.
            LicenseParse noExpiry = CheckPointLicenseParser.parse(readKind,
                    "Host Expiration Features\n192.0.2.10 CPSB-FW CPSB-IPS CPSB-AV\n");
            assertTrue(noExpiry.items().isEmpty(), noExpiry.items().toString());
            assertEquals(ParseOutcome.UNPARSED, noExpiry.outcome());

            // A key-suffixed line with no readable expiry is UNKNOWN, never VALID or PERMANENT.
            LicenseParse keyed = CheckPointLicenseParser.parse(readKind,
                    "Host Expiration Features\n192.0.2.10 CPSB-FW CPSB-IPS " + key() + "\n");
            assertTrue(keyed.items().stream().allMatch(i -> i.state().equals("UNKNOWN") && i.expiresOn() == null),
                    keyed.items().toString());

            // The key is the last token and no feature remains once the label is taken: skipped and counted.
            LicenseParse keyOnly = CheckPointLicenseParser.parse(readKind, "Host Expiration Features\n192.0.2.10 30Dec2026 " + key() + "\n");
            assertTrue(keyOnly.items().isEmpty(), keyOnly.items().toString());
            assertEquals(1, keyOnly.skipped());
            assertEquals(ParseOutcome.UNPARSED, keyOnly.outcome());
        }
    }

    @Test
    void aMalformedOrImpossibleLicenseExpiryIsUnknownNotValid() {
        for (String readKind : READ_KINDS) {
            String text = String.join("\n",
                    "Host Expiration Features",
                    "192.0.2.10 31Foo2027 CPSB-FW CPSB-IPS " + key(),
                    "192.0.2.10 31Feb2027 CPSB-AV CPSB-IPS " + key(),
                    "192.0.2.10 BOGUS CPSB-URLF " + key(),
                    "");
            List<LicenseItem> items = license(readKind, text);
            assertEquals(3, items.size(), items.toString());
            for (LicenseItem item : items) {
                assertEquals("UNKNOWN", item.state(), item.toString());
                assertNull(item.expiresOn(), item.toString());
            }
        }
    }

    @Test
    void aMalformedOrMissingContractExpiryIsUnknownAndAContractWithoutALabelIsSkipped() {
        String text = String.join("\n",
                "Contract Coverage:",
                "#   | ID        | Expiration   | SKU",
                "====+===========+==============+=====================",
                "1   | 77ab12cd  |              | CPSB-EMPTY-EXPIRY-1Y",
                "2   | 77ab12cd  | 31Foo2027    | CPSB-BAD-MONTH-1Y",
                "3   | 77ab12cd  | 31Feb2027    | CPSB-IMPOSSIBLE-DAY-1Y",
                "4   | 77ab12cd  | 28Feb2027    |   ",
                "5   | 77ab12cd  | 2027-02-28   | CPSB-ISO-SHAPE-1Y",
                "");
        LicenseParse parsed = CheckPointLicenseParser.parse("cp_cplic_print", text);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(4, parsed.items().size(), parsed.items().toString());
        assertEquals(1, parsed.skipped(), "the row without a label is counted, not stored");
        for (LicenseItem item : parsed.items()) {
            assertEquals("SUPPORT_CONTRACT", item.category());
            assertEquals("UNKNOWN", item.state(), item.toString());
            assertNull(item.expiresOn(), item.toString());
        }
        assertEquals("UNKNOWN", byLabel(parsed.items(), "CPSB-ISO-SHAPE-1Y").state(),
                "a date outside the Check Point shape is not read as a valid expiry");
    }

    @Test
    void aContractRowWithoutTheExpiryColumnIsNotAnItem() {
        String text = "Contract Coverage:\n1 | 77ab12cd | CPSB-NO-EXPIRY-COLUMN\n";
        LicenseParse parsed = CheckPointLicenseParser.parse("cp_cplic_print", text);
        assertTrue(parsed.items().isEmpty(), parsed.items().toString());
        assertEquals(ParseOutcome.UNPARSED, parsed.outcome());
    }

    @Test
    void everyLicenseItemIsEitherUnknownPermanentOrValidWithAnExpiry() {
        String text = String.join("\n",
                "Host Expiration Features",
                "192.0.2.10 never CPSB-FW " + key(),
                "192.0.2.10 30Dec2026 CPSB-AV " + key(),
                "192.0.2.10 31Foo2027 CPSB-URLF " + key(),
                "192.0.2.10 31Feb2027 CPSB-IPS " + key(),
                "Contract Coverage:",
                "1 | 77ab12cd | 28Feb2027 | CPSB-NGTP-APPL-1Y",
                "2 | 77ab12cd |  | CPSB-BLANK-DATE-1Y",
                "3 | 77ab12cd | 9Xxx2027 | CPSB-UNKNOWN-MONTH-1Y",
                "");
        for (String readKind : READ_KINDS) {
            for (LicenseItem item : license(readKind, text)) {
                switch (item.state()) {
                    case "VALID" -> assertTrue(item.expiresOn() != null, "VALID without an expiry: " + item);
                    case "PERMANENT" -> assertNull(item.expiresOn(), item.toString());
                    case "UNKNOWN" -> assertNull(item.expiresOn(), item.toString());
                    default -> throw new AssertionError("unexpected state " + item);
                }
            }
        }
    }

    @Test
    void emptyAndHeaderOnlyLicenseAnswersYieldNoItemAndNoValidState() {
        for (String readKind : READ_KINDS) {
            assertEquals(ParseOutcome.UNPARSED, CheckPointLicenseParser.parse(readKind, "Host Expiration Features\n").outcome());
            LicenseParse contractHeaderOnly = CheckPointLicenseParser.parse(readKind,
                    "Contract Coverage:\n#   | ID        | Expiration   | SKU\n====+===+===+===\n");
            assertEquals(ParseOutcome.UNPARSED, contractHeaderOnly.outcome());
            assertTrue(contractHeaderOnly.items().isEmpty());
            assertEquals(ParseOutcome.EMPTY, CheckPointLicenseParser.parse(readKind, "").outcome());
            assertEquals(ParseOutcome.EMPTY, CheckPointLicenseParser.parse(readKind, " \n\t\n").outcome());
            assertEquals(ParseOutcome.EMPTY, CheckPointLicenseParser.parse(readKind, null).outcome());
        }
    }

    @Test
    void aLivePatchRowWithAnUnreadableInstalledDateKeepsTheStatusWithoutAnInstalledTime() {
        String text = String.join("\n",
                "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT",
                "sk190001:a armed automatic 1/1 2026-13-45 10:00:00 sk190001",
                "sk190002:a ready automatic 0/1 2026-02-30 10:00 sk190002",
                "sk190003:a armed automatic 1/1 2026-10-01 25:99:00 sk190003",
                "sk190004:a armed automatic 1/1 10:00:00 sk190004",
                "");
        LivePatchParse parsed = CheckPointLivePatchParser.parse(text);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(4, parsed.items().size(), parsed.items().toString());
        for (LivePatchItem item : parsed.items()) {
            assertNull(item.installedAt(), "an unreadable installed time is not guessed: " + item);
        }
        assertEquals("armed", parsed.items().get(0).status());
        assertEquals(List.of("sk190001"), parsed.items().get(0).refs());
    }

    @Test
    void aLivePatchRowWithoutAStatusColumnIsDroppedNotGivenAKnownStatus() {
        // Status missing: the PIDS token lands in the wrong column, so the row is not a row and no status is invented.
        String text = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n"
                + "sk190010:a automatic 1/1 2026-01-01 00:00:00 sk190010\n";
        LivePatchParse parsed = CheckPointLivePatchParser.parse(text);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertTrue(parsed.items().stream().noneMatch(i -> STATUS_KNOWN.contains(i.status())), parsed.items().toString());
    }

    @Test
    void aLivePatchRowWithoutAPidsColumnIsDroppedNotInvented() {
        String text = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n"
                + "sk190020:a armed automatic - 2026-01-01 00:00:00 sk190020\n";
        LivePatchParse parsed = CheckPointLivePatchParser.parse(text);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertTrue(parsed.items().isEmpty(), parsed.items().toString());
    }

    @Test
    void anUnknownStatusWordIsUnknownEvenWhenTheRowHasReferences() {
        String text = "ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n"
                + "sk190030:a bogus automatic 1/1 2026-01-01 00:00:00 CVE-2026-4321\n";
        LivePatchParse parsed = CheckPointLivePatchParser.parse(text);
        assertEquals(1, parsed.items().size());
        assertEquals("UNKNOWN", parsed.items().get(0).status());
        assertEquals(List.of("CVE-2026-4321"), parsed.items().get(0).refs());
    }

    @Test
    void emptyAndHeaderOnlyLivePatchAnswersAreNotRowsAndNotOk() {
        assertEquals(ParseOutcome.EMPTY, CheckPointLivePatchParser.parse("").outcome());
        assertEquals(ParseOutcome.EMPTY, CheckPointLivePatchParser.parse(" \n\t\n").outcome());
        assertEquals(ParseOutcome.EMPTY, CheckPointLivePatchParser.parse(null).outcome());
        // A recognised header with no rows: COLLECTED with no items, so no patch state is shown as OK.
        LivePatchParse headerOnly = CheckPointLivePatchParser.parse("ID(PATCH:PROC) STATUS MODE PIDS INSTALLED COMMENT\n");
        assertEquals(ParseOutcome.COLLECTED, headerOnly.outcome());
        assertTrue(headerOnly.items().isEmpty());
        assertEquals(ParseOutcome.UNPARSED, CheckPointLivePatchParser.parse("cplp: command not found\n").outcome());
        assertTrue(CheckPointLivePatchParser.parse("ID(PATCH:PROC) STATUS\n").items().isEmpty());
    }
}
