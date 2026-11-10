package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

class AsaSmartLicensingParserTest {

    private static final String[] NEVER_STORED = {"ExampleCorp", "LabAccount", "SYN0000A1", "ASA-SYNTH", "fw-synth-01", "FJZ0000SYNTH"};

    /** The measured active appliance: Smart Agent layout, unregistered, evaluation period over. */
    private static final String EVAL_EXPIRED = String.join("\n",
            "Smart Licensing Status",
            "======================",
            "",
            "Smart Licensing is ENABLED",
            "",
            "Registration:",
            "  Status: UNREGISTERED",
            "  Smart Account: ExampleCorp",
            "  Virtual Account: LabAccount",
            "  Export-Controlled Functionality: ALLOWED",
            "",
            "License Authorization:",
            "  Status: EVAL EXPIRED on Dec 14 2025 10:11:12 UTC+0",
            "",
            "Overall Authorization Status:",
            "Features Authorized:",
            "  <none>",
            "",
            "Utility:",
            "  Status: DISABLED",
            "",
            "Data Privacy:",
            "  Sending Hostname: yes",
            "    Callhome hostname privacy: DISABLED",
            "",
            "Transport:",
            "  Type: Callhome",
            "",
            "License Usage",
            "==============",
            "",
            "(ASA_SMART_LIC):",
            "  Description:",
            "  Count: 1",
            "  Version: 1.0",
            "  Status: EVAL EXPIRED",
            "  Export status: NOT RESTRICTED",
            "",
            "Product Information",
            "===================",
            "UDI: PID:ASA-SYNTH,SN:SYN0000A1",
            "Hostname: fw-synth-01",
            "",
            "Agent Version",
            "=============",
            "Smart Agent for Licensing: 5.4.5_rel/16",
            "");

    @Test
    void smartAgentLayoutUnregisteredAndEvalExpiredReadsTheDateAfterOn() {
        LicenseParse parsed = AsaSmartLicensingParser.parse(EVAL_EXPIRED);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(1, parsed.items().size());
        LicenseItem item = parsed.items().get(0);
        assertEquals("SMART_LICENSING", item.category());
        assertEquals("Smart Licensing", item.label());
        assertEquals("EVAL_EXPIRED", item.state());
        assertEquals(LocalDate.of(2025, 12, 14), item.expiresOn());
        assertEquals("Registration=UNREGISTERED, Authorization=EVAL EXPIRED", item.detail());
        assertEquals("asa_show_license_all", item.readKind());
        assertEquals("DIRECT_DEVICE", item.evidenceGrade());
        noLeak(parsed.items(), NEVER_STORED);
    }

    @Test
    void smartAgentRegisteredStates() {
        assertEquals("VALID", state("Registration:\n  Status: REGISTERED (Mon Jan 05 2026)\nLicense Authorization:\n  Status: AUTHORIZED\n"));
        assertEquals("OUT_OF_COMPLIANCE", state("Registration:\n  Status: REGISTERED\nLicense Authorization:\n  Status: OUT OF COMPLIANCE\n"));
        assertEquals("EXPIRED", state("Registration:\n  Status: REGISTERED\nLicense Authorization:\n  Status: AUTH EXPIRED\n"));
        assertEquals("UNREGISTERED", state("Registration:\n  Status: UNREGISTERED\nLicense Authorization:\n  Status: EVAL MODE\n"));
        assertEquals("UNKNOWN", state("Registration:\n  Status: REGISTERED\nLicense Authorization:\n  Status: SOMETHING NEW\n"));
    }

    @Test
    void evalExpiredWithoutAReadableDateKeepsTheStateAndNoDate() {
        LicenseItem item = AsaSmartLicensingParser.parse("Registration:\n Status: UNREGISTERED\nLicense Authorization:\n"
                + " Status: EVAL EXPIRED on Foo 99 2025\n").items().get(0);
        assertEquals("EVAL_EXPIRED", item.state());
        assertNull(item.expiresOn());
    }

    /** The measured 4100-series module on the standby unit. */
    private static final String STANDBY = String.join("\n",
            "Smart licensing enabled: Yes",
            "Compliance status: In compliance",
            "Overall licensed status: Not applicable in standby state",
            "No entitlements in use",
            "Serial Number: FJZ0000SYNTH",
            "License mode: Smart Licensing",
            "",
            "Licensed features for this platform:",
            "Maximum Physical Interfaces       : Unlimited      perpetual",
            "Maximum VLANs                     : 1024           perpetual",
            "Encryption-3DES-AES               : Enabled        perpetual",
            "Security Contexts                 : 10             perpetual",
            "",
            "This platform has a Standard tier license.",
            "");

    @Test
    void moduleLayoutStandbyIsNotApplicable() {
        LicenseParse parsed = AsaSmartLicensingParser.parse(STANDBY);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        LicenseItem item = parsed.items().get(0);
        assertEquals("NOT_APPLICABLE", item.state());
        assertEquals("Compliance=In compliance, Overall=Not applicable in standby state", item.detail());
        noLeak(parsed.items(), NEVER_STORED);
    }

    @Test
    void moduleLayoutActiveStates() {
        assertEquals("VALID", state("Smart licensing enabled: Yes\nCompliance status: In compliance\nOverall licensed status: Authorized\n"));
        assertEquals("OUT_OF_COMPLIANCE", state("Compliance status: Out of compliance\nOverall licensed status: Not authorized\n"));
        assertEquals("UNKNOWN", state("Compliance status: Waiting\nOverall licensed status: Pending\n"));
    }

    @Test
    void noRecognisableLineIsUnparsedAndBlankIsEmpty() {
        assertEquals(ParseOutcome.UNPARSED, AsaSmartLicensingParser.parse("ERROR: % Invalid input detected at '^' marker.\n").outcome());
        assertEquals(ParseOutcome.EMPTY, AsaSmartLicensingParser.parse("\n \n").outcome());
        assertEquals(ParseOutcome.EMPTY, AsaSmartLicensingParser.parse(null).outcome());
    }

    private static String state(String text) {
        return AsaSmartLicensingParser.parse(text).items().get(0).state();
    }
}
