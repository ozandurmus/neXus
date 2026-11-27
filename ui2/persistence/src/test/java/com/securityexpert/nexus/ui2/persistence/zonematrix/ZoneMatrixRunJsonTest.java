package com.securityexpert.nexus.ui2.persistence.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Cell;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.Witness;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.ZoneDefinitionGap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The JSONB codec of the V267 zone-matrix run tables (ZONE_MATRIX_CONTRACT.md 5.9 and 8.1): identity text round-trips
 * byte-exact (identity law: leading zeroes, case, quotes, backslashes and non-ASCII kept), and PostgreSQL's own JSONB
 * text form (spaces after separators, reordered keys) reads back the same. Synthetic values only.
 */
class ZoneMatrixRunJsonTest {

    @Test void identityStringsRoundTripByteExact() {
        List<String> values = List.of("0001", "0001aBc", "ZONE-A->zone-a", "quote\"back\\slash", "tab\tnew\nline",
                "\u0001ctl", "çğı-ü", "", "x/y");
        String json = ZoneMatrixRunJson.write(values);
        assertEquals(values, ZoneMatrixRunJson.strings(json));
        assertFalse(json.contains("\u0001"), "control characters are escaped");
    }

    @Test void readsPostgresJsonbTextForm() {
        Object parsed = ZoneMatrixRunJson.parse("[{\"witness\": null, \"source_zone\": \"0a\", \"not_allowed_services\": "
                + "[\"tcp/22\", \"udp/53\"]}, {\"witness\": {\"sample_source_ip\": 3221225488, \"service\": \"tcp/22\"}}]");
        List<?> list = (List<?>) parsed;
        assertEquals("0a", ((Map<?, ?>) list.get(0)).get("source_zone"));
        assertNull(((Map<?, ?>) list.get(0)).get("witness"));
        assertEquals(3221225488L, ((Map<?, ?>) ((Map<?, ?>) list.get(1)).get("witness")).get("sample_source_ip"));
        assertEquals(List.of(), ZoneMatrixRunJson.strings(null));
        assertEquals(List.of(), ZoneMatrixRunJson.strings("[]"));
    }

    @Test void violatedCellsWithWitnessRoundTrip() {
        List<Cell> cells = Arrays.asList(
                new Cell("zone-a", "zone-b", "ALLOW_LISTED", "HIGH", List.of("tcp/22"), "original",
                        new Witness("192.0.2.16/28", "198.51.100.77/32", "tcp/22", 3221225488L, 3325256781L)),
                new Cell("UNASSIGNED", "zone-b", "DENY_ALL", "CRITICAL", List.of(), null, null));
        String json = ZoneMatrixRunJson.write(JooqZoneMatrixRunRepository.cellsJson(cells));
        assertEquals(cells, JooqZoneMatrixRunRepository.cells(json));
    }

    @Test void zoneDefinitionGapsRoundTripAndNullStaysUnknown() {
        List<ZoneDefinitionGap> gaps = List.of(
                new ZoneDefinitionGap("0007", 7, "Rule \"gap\"\\1", "Custom section", List.of("192.0.2.0/24", "2001:db8::/32")),
                new ZoneDefinitionGap("r-null", null, null, null, List.of()));
        String json = ZoneMatrixRunJson.writeZoneDefinitionGaps(gaps);
        assertEquals(gaps, ZoneMatrixRunJson.zoneDefinitionGaps(json));
        assertNull(ZoneMatrixRunJson.zoneDefinitionGaps(null));
        assertEquals(List.of(), ZoneMatrixRunJson.zoneDefinitionGaps("[]"));
        assertNull(ZoneMatrixRunJson.writeZoneDefinitionGaps(null));
        assertEquals("[]", ZoneMatrixRunJson.writeZoneDefinitionGaps(List.of()));
    }

    @Test void malformedJsonIsRefusedNotGuessed() {
        assertThrows(RuntimeException.class, () -> ZoneMatrixRunJson.parse("[\"open"));
        assertThrows(RuntimeException.class, () -> ZoneMatrixRunJson.parse("[1] trailing"));
        assertThrows(IllegalArgumentException.class, () -> ZoneMatrixRunJson.write(List.of(new Object())));
    }
}
