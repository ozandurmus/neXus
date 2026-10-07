package com.securityexpert.nexus.ui2.worker.diagnostic;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import com.securityexpert.nexus.ui2.platform.CpviewProjection;

class CpviewProjectionTest {
    @Test void projectsOnlyAllowlistedCountersAndGenericNames() throws Exception {
        String raw = new String(getClass().getResourceAsStream("/diagnostic/cpview-synthetic.txt").readAllBytes(), StandardCharsets.UTF_8);
        String safe = CpviewProjection.project(raw);
        assertTrue(safe.contains("section: traffic"));
        assertTrue(safe.contains("field: throughput in; type: NUMBER; unit: Mbps; value: 120"));
        assertTrue(safe.contains("scope_marker_present: true"));
        assertTrue(safe.contains("MASKED_SECTION_1"));
        assertTrue(safe.contains("MASKED_FIELD_5; type: NUMBER; unit: Mbps\n"));
        for (String forbidden : new String[]{"invented", "192.0.2.20", "999", "Context", "Unknown counter"})
            assertFalse(safe.contains(forbidden));
        assertFalse(CpviewProjection.project("CPU %: 4 %").contains("scope_marker_present: true"));
    }
    @Test void unknownUnitsAndNonNumericValuesNeverSurvive() {
        String safe = CpviewProjection.project("CPU %: invented-person\nDrops: 5 invented-unit\nFlag: true");
        assertFalse(safe.contains("invented"));
        assertFalse(safe.contains("value:"));
        assertTrue(safe.contains("type: BOOLEAN"));
    }
    @Test void enforcesUtf8ByteLimit() {
        assertDoesNotThrow(() -> CpviewProjection.project("x".repeat(CpviewProjection.MAX_BYTES)));
        assertThrows(IllegalArgumentException.class, () -> CpviewProjection.project("x".repeat(CpviewProjection.MAX_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> CpviewProjection.project("é".repeat(CpviewProjection.MAX_BYTES / 2 + 1)));
    }
}
