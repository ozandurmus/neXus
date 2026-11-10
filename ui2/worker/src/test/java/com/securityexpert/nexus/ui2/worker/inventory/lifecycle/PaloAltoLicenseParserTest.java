package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.byLabel;
import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PaloAltoLicenseParserTest {

    private static final String[] NEVER_STORED = {"007000000123", "I1234567", "AUTHCODE-SYNTH-2", "Synthetic description text",
            "ops@example.invalid", "support.example.invalid", "Extended coverage synthetic"};

    private static final String LICENSES = """
            <response status="success"><result><licenses>
              <entry><feature>Threat Prevention</feature><description>Synthetic description text</description>
                <serial>007000000123</serial><issued>July 06, 2025</issued><expires>July 06, 2027</expires><expired>no</expired>
                <authcode>I1234567</authcode></entry>
              <entry><feature>PA-VM</feature><description>Synthetic description text</description><serial>007000000123</serial>
                <issued>January 01, 2025</issued><expires>Never</expires><expired>no</expired></entry>
              <entry><feature>PAN-DB URL Filtering</feature><serial>007000000123</serial><expires>2026/01/05</expires><expired>yes</expired>
                <authcode>AUTHCODE-SYNTH-2</authcode></entry>
              <entry><feature>GlobalProtect Gateway</feature><expires>Sep 9, 2027</expires><expired>no</expired></entry>
              <entry><feature>DNS Security</feature><expires>2027-02-30</expires><expired>no</expired></entry>
              <entry><feature>Bad@Feature</feature><expires>2027-02-01</expires><expired>no</expired></entry>
            </licenses></result></response>
            """;

    @Test
    void licenseEntriesBecomeSubscriptionsWithoutSerialAuthcodeOrDescription() {
        LicenseParse parsed = PaloAltoLicenseParser.parseLicenseInfo(LICENSES);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(5, parsed.items().size());
        assertEquals(1, parsed.skipped());
        assertEquals("SUBSCRIPTION", byLabel(parsed.items(), "PA-VM").category());
        assertEquals("pan_license_info", byLabel(parsed.items(), "PA-VM").readKind());
        assertEquals(LocalDate.of(2027, 7, 6), byLabel(parsed.items(), "Threat Prevention").expiresOn());
        assertEquals("VALID", byLabel(parsed.items(), "Threat Prevention").state());
        assertEquals("PERMANENT", byLabel(parsed.items(), "PA-VM").state());
        assertNull(byLabel(parsed.items(), "PA-VM").expiresOn());
        assertEquals("EXPIRED", byLabel(parsed.items(), "PAN-DB URL Filtering").state());
        assertEquals(LocalDate.of(2026, 1, 5), byLabel(parsed.items(), "PAN-DB URL Filtering").expiresOn());
        assertEquals(LocalDate.of(2027, 9, 9), byLabel(parsed.items(), "GlobalProtect Gateway").expiresOn());
        assertEquals("UNKNOWN", byLabel(parsed.items(), "DNS Security").state(), "an impossible date is not guessed");
        noLeak(parsed.items(), NEVER_STORED);
    }

    private static final String SUPPORT = """
            <response status="success"><result><SupportInfoResponse>
              <Links><Link><Title>Support Home</Title><Url>https://support.example.invalid/</Url></Link></Links>
              <Support><Contact><Contact>ops@example.invalid</Contact></Contact><ExpiryDate>March 03, 2027</ExpiryDate>
                <SupportLevel>Premium</SupportLevel><SupportDescription>Extended coverage synthetic</SupportDescription></Support>
            </SupportInfoResponse></result></response>
            """;

    @Test
    void supportInfoBecomesOneSupportContract() {
        LicenseParse parsed = PaloAltoLicenseParser.parseSupportInfo(SUPPORT);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(1, parsed.items().size());
        var item = parsed.items().get(0);
        assertEquals("SUPPORT_CONTRACT", item.category());
        assertEquals("Premium", item.label());
        assertEquals("VALID", item.state());
        assertEquals(LocalDate.of(2027, 3, 3), item.expiresOn());
        assertEquals("pan_support_info", item.readKind());
        noLeak(parsed.items(), NEVER_STORED);
    }

    @Test
    void errorsEmptyAndForeignXml() {
        assertEquals(ParseOutcome.UNPARSED, PaloAltoLicenseParser.parseLicenseInfo("<response status=\"error\"><msg>no</msg></response>").outcome());
        assertEquals(ParseOutcome.EMPTY, PaloAltoLicenseParser.parseLicenseInfo("<response status=\"success\"><result><licenses/></result></response>").outcome());
        assertEquals(ParseOutcome.UNPARSED, PaloAltoLicenseParser.parseLicenseInfo("<html>").outcome());
        assertEquals(ParseOutcome.UNPARSED, PaloAltoLicenseParser.parseSupportInfo("<response status=\"success\"><result/></response>").outcome());
        assertEquals(ParseOutcome.EMPTY, PaloAltoLicenseParser.parseSupportInfo("").outcome());
        assertEquals("PERMANENT", PaloAltoLicenseParser.parseSupportInfo(
                "<response status=\"success\"><result><SupportInfoResponse><Support><ExpiryDate>never</ExpiryDate>"
                        + "<SupportLevel>Standard</SupportLevel></Support></SupportInfoResponse></result></response>").items().get(0).state());
    }

    @Test
    void aDoctypeIsRefusedWithoutResolvingAnything() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><response status=\"success\">"
                + "<result><licenses><entry><feature>&x;</feature></entry></licenses></result></response>";
        assertEquals(ParseOutcome.UNPARSED, PaloAltoLicenseParser.parseLicenseInfo(xxe).outcome());
    }

    @Test
    void everyAcceptedDateFormat() {
        assertEquals(LocalDate.of(2026, 9, 30), LifecycleText.panDate("September 30, 2026"));
        assertEquals(LocalDate.of(2026, 9, 30), LifecycleText.panDate("Sep 30, 2026"));
        assertEquals(LocalDate.of(2026, 9, 5), LifecycleText.panDate("2026/09/05"));
        assertEquals(LocalDate.of(2026, 9, 5), LifecycleText.panDate("2026-09-05"));
        assertNull(LifecycleText.panDate("30.09.2026"));
        assertNull(LifecycleText.panDate("Smarch 30, 2026"));
        assertNull(LifecycleText.panDate(""));
    }
}
