package com.securityexpert.nexus.ui2.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;

class RadwareDiscoveryCandidateMapperTest {

    /** Built from the field names measured on Cyber Controller 10.13 (2026-09-24); values synthetic. */
    private static final String LIST = """
            {"children": [
              {"name": "SITE-A", "ormId": "s1", "treeType": "Site", "children": [
                {"name": "DP-ALPHA-01", "ormId": "d1", "parentOrmId": "s1", "managementIp": "192.0.2.41", "type": "DefensePro",
                 "deviceVersion": "8.32.1", "formFactor": "x4420", "status": "UP", "highAvailabilityPriorityEnum": "PRIMARY", "deleted": false},
                {"name": "DP-OLD", "ormId": "d2", "managementIp": "192.0.2.42", "type": "DefensePro", "deleted": true},
                {"name": "ALTEON-1", "ormId": "a1", "managementIp": "192.0.2.43", "type": "Alteon"}
              ]}
            ]}
            """;

    @Test
    void devicesUnderASiteBecomeCandidatesAndOnlyDefenseProIsImportable() throws Exception {
        List<DiscoveryCandidateRecord> records = RadwareDiscoveryCandidateMapper.map("run-1", new ObjectMapper().readTree(LIST));
        assertEquals(2, records.size(), "the deleted device is left out");
        DiscoveryCandidateRecord dp = records.stream().filter(r -> r.stableIdentifier().equals("d1")).findFirst().orElseThrow();
        assertEquals("RADWARE_DEFENSEPRO", dp.kind());
        assertEquals(Optional.of("SITE-A"), dp.owningDomain());
        assertEquals(Optional.of("192.0.2.41"), dp.ownAddress());
        assertEquals(Optional.of("8.32.1"), dp.softwareVersion());
        assertTrue(dp.importable());
        DiscoveryCandidateRecord alteon = records.stream().filter(r -> r.stableIdentifier().equals("a1")).findFirst().orElseThrow();
        assertEquals("RADWARE_ALTEON", alteon.kind());
        assertFalse(alteon.importable(), "neXus imports DefensePro only; the rest is shown");
    }

    @Test
    void theMeasurementRecordCarriesCategoriesNeverNamesOrAddresses() throws Exception {
        var values = RadwareDiscoveryCandidateMapper.categoricalValues(new ObjectMapper().readTree(LIST));
        assertTrue(values.get("type").contains("DefensePro"));
        assertFalse(values.toString().contains("192.0.2"));
        assertFalse(values.toString().contains("DP-ALPHA"));
    }
}
