package com.securityexpert.nexus.ui2.service.device.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.AsaConfigRelationReader.Latest;

/** The saved-config-differs read: counts and times only, MISMATCH first, no configuration text. */
class AsaConfigRelationViewServiceTest {

    @Test
    void mismatchRowsComeFirstWithKeywordCountsAndBothTimes() {
        var match = new Latest("dev-00000001", "MATCH", 0, Map.of(), Instant.parse("2026-11-19T02:00:00Z"),
                Instant.parse("2026-11-19T02:01:00Z"));
        var mismatch = new Latest("dev-00000002", "MISMATCH", 4, Map.of("access-list", 3, "object-group", 1),
                Instant.parse("2026-11-19T03:00:00Z"), Instant.parse("2026-11-19T03:01:00Z"));
        var service = new AsaConfigRelationViewService(() -> List.of(match, mismatch), List::of);

        List<Map<String, Object>> rows = service.rows();

        assertEquals(2, rows.size());
        assertEquals("dev-00000002", rows.get(0).get("device_id"));
        assertEquals("MISMATCH", rows.get(0).get("relation"));
        assertEquals(Map.of("access-list", 3, "object-group", 1), rows.get(0).get("differing_keywords"));
        assertEquals("2026-11-19T03:00:00Z", rows.get(0).get("backup_created_at"));
        assertEquals("2026-11-19T03:01:00Z", rows.get(0).get("derived_at"));
        assertNull(rows.get(0).get("hostname"), "no inventory row, no name");
        assertEquals("MATCH", rows.get(1).get("relation"));
    }

    @Test
    void noRelationRowsGiveAnEmptyList() {
        assertEquals(List.of(), new AsaConfigRelationViewService(List::of, List::of).rows());
    }
}
