package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.byLabel;
import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

class FortiGateParsersTest {

    private static final String[] NEVER_STORED = {"192.0.2.7", "ACCT-SYNTH-0001", "ExampleCo", "424242", "FGT-SYNTH-ID", "FAKE",
            "Contract=", "industry"};

    private static final String CONTRACT = String.join("\n",
            "Tue Nov  9 10:11:12 2026 diag: update server 192.0.2.7:443 reachable",
            "Tue Nov  9 10:11:12 2026 diag: serial FGT-SYNTH-ID registered",
            "FAKE,Mon May 11 2027",
            "System contracts:",
            "HDWR,Mon May 11 2027",
            "ENHN,Mon  May 11 2027",
            "FMWR,Tue Jan  5 2027",
            "SPRT,Wed Dec 1 2027",
            "AVDB,Fri Mar 12 2027",
            "FRVS,Sat Feb 29 2028",
            "ZHVO,Mon Feb 30 2027",
            "BROK,garbage text",
            "Account contracts:",
            "Account ID: ACCT-SYNTH-0001",
            "Contract=1-2-3:4:5|AccountID=ACCT-SYNTH-0001|Industry=Example|Company=ExampleCo|UserID=424242",
            "Support contract: pending_registration=0 got_contract_info=1",
            "company=[ExampleCo] industry=[Example]",
            "User ID: 424242",
            "");

    @Test
    void contractBlockBecomesSupportContractsAndSubscriptionsByCode() {
        LicenseParse parsed = FortiGateContractParser.parse(CONTRACT);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(8, parsed.items().size(), parsed.items().toString());
        for (String code : new String[] {"HDWR", "ENHN", "SPRT", "FMWR"}) {
            assertEquals("SUPPORT_CONTRACT", byLabel(parsed.items(), code).category(), code);
        }
        assertEquals("SUBSCRIPTION", byLabel(parsed.items(), "AVDB").category());
        assertEquals("SUBSCRIPTION", byLabel(parsed.items(), "FRVS").category());
        assertEquals(LocalDate.of(2027, 5, 11), byLabel(parsed.items(), "HDWR").expiresOn());
        assertEquals(LocalDate.of(2027, 5, 11), byLabel(parsed.items(), "ENHN").expiresOn(), "double spaces are tolerated");
        assertEquals(LocalDate.of(2027, 1, 5), byLabel(parsed.items(), "FMWR").expiresOn());
        assertEquals("VALID", byLabel(parsed.items(), "SPRT").state());
        assertEquals(LocalDate.of(2028, 2, 29), byLabel(parsed.items(), "FRVS").expiresOn());
        assertEquals("UNKNOWN", byLabel(parsed.items(), "ZHVO").state(), "an impossible day is not guessed");
    }

    @Test
    void contractBlockDoesNotReadDiagnosticLinesOrTheAccountBlock() {
        LicenseParse parsed = FortiGateContractParser.parse(CONTRACT);
        noLeak(parsed.items(), NEVER_STORED);
        assertTrue(parsed.items().stream().noneMatch(i -> i.label().equals("FAKE")));
        LicenseItem unreadable = byLabel(parsed.items(), "BROK");
        assertEquals("UNKNOWN", unreadable.state());
        assertNull(unreadable.expiresOn());
        assertEquals("fgt_update_info_contract", unreadable.readKind());
    }

    @Test
    void contractWithoutTheBlockIsUnparsedAndAnEmptyBlockIsEmpty() {
        assertEquals(ParseOutcome.UNPARSED, FortiGateContractParser.parse("Unknown action 0\n").outcome());
        assertEquals(ParseOutcome.EMPTY, FortiGateContractParser.parse("System contracts:\nAccount contracts:\nUser ID: 1\n").outcome());
        assertEquals(ParseOutcome.EMPTY, FortiGateContractParser.parse("   ").outcome());
    }

    private static final String AUTOUPDATE = String.join("\n",
            "AV Engine",
            "---------",
            "Version: 7.00018 ",
            "Contract Expiry Date: Mon May 11 2027",
            "Last Updated using manual update on Wed Aug 27 11:22:33 2025",
            "Last Update Attempt: Tue Nov  9 10:11:12 2026",
            "Result: No Updates",
            "",
            "Virus Definitions",
            "---------",
            "Version: 1.00000",
            "Contract Expiry Date: Mon May 11 2027",
            "Last Updated using scheduled update on Tue Nov  9 03:00:00 2026",
            "Last Update Attempt: Tue Nov  9 10:11:12 2026",
            "Result: Updates Installed",
            "Extended set",
            "Version: 1.1",
            "Contract Expiry Date: Mon Jun 1 2027",
            "Result: Connectivity failure",
            "",
            "IPS Attack Engine",
            "---------",
            "Version: 7.1",
            "Contract Expiry Date: n/a",
            "Last Update Attempt: n/a",
            "Result: Unauthorized",
            "",
            "Botnet Domain Database",
            "---------",
            "Version: 3.1",
            "Last Updated using manual update on Mon Jan  4 03:00:00 2027",
            "",
            "FDS Address",
            "-----------",
            "192.0.2.7:443",
            "");

    @Test
    void autoupdateBlocksBecomeSubscriptionsWithTheResultWords() {
        LicenseParse parsed = FortiGateAutoupdateParser.parse(AUTOUPDATE);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(3, parsed.items().size(), parsed.items().toString());
        LicenseItem engine = byLabel(parsed.items(), "AV Engine");
        assertEquals("SUBSCRIPTION", engine.category());
        assertEquals("VALID", engine.state());
        assertEquals(LocalDate.of(2027, 5, 11), engine.expiresOn());
        assertEquals("No Updates", engine.detail());
        LicenseItem virus = byLabel(parsed.items(), "Virus Definitions");
        assertEquals(LocalDate.of(2027, 5, 11), virus.expiresOn(), "the first contract line of a block is the block's");
        assertEquals("Updates Installed", virus.detail());
        LicenseItem ips = byLabel(parsed.items(), "IPS Attack Engine");
        assertEquals("UNKNOWN", ips.state());
        assertNull(ips.expiresOn());
        assertEquals("Unauthorized", ips.detail());
        noLeak(parsed.items(), "192.0.2.7", "Botnet");
    }

    @Test
    void autoupdateWithoutBlocksIsUnparsed() {
        assertEquals(ParseOutcome.UNPARSED, FortiGateAutoupdateParser.parse("Unknown action 0\n").outcome());
        assertEquals(ParseOutcome.EMPTY, FortiGateAutoupdateParser.parse("").outcome());
    }
}
