package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.byLabel;
import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

class ManagerAndGridParsersTest {

    private static final String[] NEVER_STORED = {"SN-SYNTH-0001", "SERIAL-SYNTH-001", "SERIAL-SYNTH-002", "PN-SYNTH-001", "PN-SYNTH-002",
            "11111111-2222-3333-4444-555555555555", "Synthetic Proxy Appliance"};

    private static final String MC_LICENSE = """
            {"name":"edge-proxy-01","uuid":"11111111-2222-3333-4444-555555555555","deviceType":"sgos6x","manufacturer":"Example",
             "serialNumber":"SN-SYNTH-0001","creationDate":"2025-01-01","lastModifiedDate":null,"licenseStatus":null,
             "trialPeriodExpirationDate":null,
             "components":[
              {"componentName":"SGOS 6 Proxy Edition","serialNumber":"SERIAL-SYNTH-001","productDescription":"Synthetic Proxy Appliance",
               "partNumber":"PN-SYNTH-001","activationDate":"2025-01-01","expirationDate":"2027-03-01T00:00:00.000+0000",
               "validity":"Valid","attributes":[]},
              {"componentName":"Web Filter (BCWF)","serialNumber":"SERIAL-SYNTH-002","productDescription":"Synthetic Proxy Appliance",
               "partNumber":"PN-SYNTH-002","activationDate":"2024-01-01","expirationDate":"2025-12-31","validity":"Expired","attributes":[]},
              {"componentName":"Subscription Without Date","serialNumber":"SERIAL-SYNTH-002","partNumber":"PN-SYNTH-002",
               "expirationDate":null,"validity":"Valid","attributes":[]},
              {"componentName":"Perpetual Base","expirationDate":"Never","validity":"Valid"},
              {"componentName":"Bad<Label>","expirationDate":"2027-01-01","validity":"Valid"}
             ],"attributes":[{"name":"a","value":"b"}]}
            """;

    @Test
    void managementCenterComponentsBecomeManagerReportedLicensesWithTheManagedDeviceAsMember() {
        LicenseParse parsed = ManagementCenterLicenseParser.parse("edge-proxy-01", MC_LICENSE);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(4, parsed.items().size());
        assertEquals(1, parsed.skipped());
        for (LicenseItem item : parsed.items()) {
            assertEquals("LICENSE", item.category());
            assertEquals("MANAGER_REPORTED", item.evidenceGrade());
            assertEquals("edge-proxy-01", item.memberRef());
            assertEquals("mc_device_license", item.readKind());
        }
        LicenseItem valid = byLabel(parsed.items(), "SGOS 6 Proxy Edition");
        assertEquals("VALID", valid.state());
        assertEquals(LocalDate.of(2027, 3, 1), valid.expiresOn(), "a longer timestamp is read by its first ten characters");
        assertEquals("validity=Valid", valid.detail());
        LicenseItem expired = byLabel(parsed.items(), "Web Filter (BCWF)");
        assertEquals("EXPIRED", expired.state());
        assertEquals(LocalDate.of(2025, 12, 31), expired.expiresOn());
        LicenseItem undated = byLabel(parsed.items(), "Subscription Without Date");
        assertEquals("UNKNOWN", undated.state());
        assertNull(undated.expiresOn());
        assertEquals("PERMANENT", byLabel(parsed.items(), "Perpetual Base").state());
        noLeak(parsed.items(), NEVER_STORED);
    }

    @Test
    void managementCenterWithoutAMemberOrComponents() {
        assertNull(ManagementCenterLicenseParser.parse(null, MC_LICENSE).items().get(0).memberRef());
        assertEquals(ParseOutcome.EMPTY, ManagementCenterLicenseParser.parse("x", "{\"components\":[]}").outcome());
        assertEquals(ParseOutcome.UNPARSED, ManagementCenterLicenseParser.parse("x", "{\"name\":\"a\"}").outcome());
        assertEquals(ParseOutcome.UNPARSED, ManagementCenterLicenseParser.parse("x", "<html>502</html>").outcome());
        assertEquals(ParseOutcome.EMPTY, ManagementCenterLicenseParser.parse("x", " ").outcome());
    }

    private static long epoch(LocalDate date) {
        return date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    @Test
    void infobloxGridwideEntriesBecomeLicensesOfTheGrid() {
        long future = epoch(LocalDate.of(2027, 1, 15));
        long past = epoch(LocalDate.of(2025, 6, 1));
        String json = "[{\"_ref\":\"license:gridwide/ZG5zLmxpY2Vuc2U:DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":" + future
                + ",\"limit\":\"1000\",\"type\":\"DNS\"},"
                + "{\"_ref\":\"license:gridwide/ZG5zLmxpY2Vuc2U:Grid\",\"expiration_status\":\"PERMANENT\",\"expiry_date\":0,\"limit\":\"\",\"type\":\"Grid\"},"
                + "{\"_ref\":\"license:gridwide/ZG5zLmxpY2Vuc2U:Rep\",\"expiration_status\":\"EXPIRED\",\"expiry_date\":" + past
                + ",\"limit\":\"Unlimited\",\"type\":\"Reporting\"},"
                + "{\"expiration_status\":\"NOT_EXPIRED\",\"type\":\"NIOS (IB-1415)\"}]";
        LicenseParse parsed = InfobloxGridwideLicenseParser.parse(json);
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome());
        assertEquals(4, parsed.items().size());
        for (LicenseItem item : parsed.items()) {
            assertEquals("grid", item.memberRef());
            assertEquals("LICENSE", item.category());
            assertEquals("DIRECT_DEVICE", item.evidenceGrade());
        }
        LicenseItem dns = byLabel(parsed.items(), "DNS");
        assertEquals("VALID", dns.state());
        assertEquals(LocalDate.of(2027, 1, 15), dns.expiresOn());
        assertEquals("limit=1000", dns.detail());
        assertEquals("PERMANENT", byLabel(parsed.items(), "Grid").state());
        assertNull(byLabel(parsed.items(), "Grid").detail());
        LicenseItem reporting = byLabel(parsed.items(), "Reporting");
        assertEquals("EXPIRED", reporting.state());
        assertEquals(LocalDate.of(2025, 6, 1), reporting.expiresOn());
        assertEquals("UNKNOWN", byLabel(parsed.items(), "NIOS (IB-1415)").state());
        noLeak(parsed.items(), "ZG5zLmxpY2Vuc2U", "license:gridwide");
        assertEquals(Instant.ofEpochSecond(future).atOffset(ZoneOffset.UTC).toLocalDate(), dns.expiresOn());
    }

    @Test
    void infobloxBlankEmptyAndForeignAnswers() {
        assertEquals(ParseOutcome.EMPTY, InfobloxGridwideLicenseParser.parse("[]").outcome());
        assertEquals(ParseOutcome.EMPTY, InfobloxGridwideLicenseParser.parse("").outcome());
        assertEquals(ParseOutcome.UNPARSED, InfobloxGridwideLicenseParser.parse("{\"Error\":\"AdmConProtoError\"}").outcome());
        assertEquals(ParseOutcome.UNPARSED, InfobloxGridwideLicenseParser.parse("not json").outcome());
    }
}
