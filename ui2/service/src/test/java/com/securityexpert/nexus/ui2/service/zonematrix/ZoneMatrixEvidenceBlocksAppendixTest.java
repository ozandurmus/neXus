package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.service.privacy.ReportProjectionMasker;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.report.CsvReportRenderer;
import com.securityexpert.nexus.ui2.service.report.ProjectionMode;
import com.securityexpert.nexus.ui2.service.report.ReportDocument;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.CellKind;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;
import com.securityexpert.nexus.ui2.service.report.ReportDocumentMasker;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent.Zone;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceBlocksAppendix.Input;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceBlocksAppendix.Result;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceBlocksAppendix.Rights;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvidenceBlocksAppendix.Status;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ZoneMatrixEvidenceBlocksAppendix} (docs/design/ZONE_MATRIX_CONTRACT.md 3.4, 7.3, 8.2;
 * {@code ZM-38}, {@code ZM-33}). Synthetic names and documentation ranges only ({@code ZONE-1}..,
 * {@code 192.0.2.0/24}, {@code 198.51.100.0/24}, {@code 2001:db8::/32}).
 */
class ZoneMatrixEvidenceBlocksAppendixTest {
    private static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");

    private static Zone zone(String name, String role, List<ZoneBlock> blocks) {
        return new Zone(UUID.randomUUID().toString(), name, "synthetic description fw-tango-04", role, blocks);
    }

    private static List<Zone> sampleZones() {
        return List.of(
                zone("ZONE-1", "USER", List.of(
                        ZoneBlock.parse("192.0.2.0/24"),
                        ZoneBlock.parse("2001:db8::/32"))),
                zone("ZONE-2", "DATABASE", List.of(
                        ZoneBlock.parse("198.51.100.0/24"))));
    }

    // ---- not requested (fail-closed: no appendix, no mention of blocks anywhere)

    @Test
    void zm38_notRequestedProducesNoAppendixAndNoMentionOfBlocksAnywhere() {
        List<Rights> unrequestedCases = List.of(
                new Rights(false, false, false),
                new Rights(false, true, false),
                new Rights(false, false, true),
                new Rights(false, true, true),
                new Rights(false, false, false, true),
                new Rights(false, true, true, true));
        for (Rights rights : unrequestedCases) {
            Result result = ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), rights);
            assertEquals(Status.NOT_REQUESTED, result.status());
            assertFalse(result.isIncluded());
            assertFalse(result.isRefused());
            assertTrue(result.section().isEmpty());
            assertEquals(List.of(), result.sections());
            assertNull(result.refusalCode());

            for (String s : strings(result)) {
                String lower = s.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("block"), "unrequested result mentions blocks: " + s);
                assertFalse(s.contains("192.0.2") || s.contains("198.51.100") || lower.contains("2001:db8"));
                assertFalse(s.contains("ZONE-1") || s.contains("ZONE-2"));
            }

            String renderedCsv = renderCsvWithAppendix(result.sections(), ProjectionMode.MASKED);
            assertFalse(renderedCsv.toLowerCase(Locale.ROOT).contains("block"), renderedCsv);
            assertFalse(renderedCsv.contains("192.0.2") || renderedCsv.contains("198.51.100")
                    || renderedCsv.toLowerCase(Locale.ROOT).contains("2001:db8"), renderedCsv);
        }
    }

    // ---- requested + both rights -> included

    @Test
    void zm38_requestedWithBothRightsIncludesAppendixWithTitleRowsAndFixedSentence() {
        List<Zone> zones = sampleZones();
        Result result = ZoneMatrixEvidenceBlocksAppendix.build(zones, new Rights(true, true, true));
        assertEquals(Status.INCLUDED, result.status());
        assertTrue(result.isIncluded());
        assertFalse(result.isRefused());
        assertNull(result.refusalCode());
        assertEquals(1, result.sections().size());

        Section section = result.section().orElseThrow();
        assertEquals("Appendix: zone blocks", section.title());
        assertEquals(List.of(ZoneMatrixEvidenceBlocksAppendix.FIXED_SENTENCE), section.notes());
        assertEquals(
                "Blocks describe the bank's network and are listed because the requester asked for them and holds the raw-view right.",
                ZoneMatrixEvidenceBlocksAppendix.FIXED_SENTENCE);

        assertEquals(1, section.tables().size());
        Table table = section.tables().get(0);
        assertEquals(List.of("zone", "family", "block", "addressCount"),
                table.columns().stream().map(ReportDocument.Column::key).toList());
        assertEquals(List.of(
                List.of("ZONE-1", "IPV4", "192.0.2.0/24", "256"),
                List.of("ZONE-1", "IPV6", "2001:db8::/32", "79228162514264337593543950336"),
                List.of("ZONE-2", "IPV4", "198.51.100.0/24", "256")),
                rowsText(table));

        // Never uses identity cell kinds or leaks zone descriptions / UUIDs / hostnames.
        for (List<Cell> row : table.rows()) {
            for (Cell cell : row) {
                assertNotEquals(CellKind.DEVICE, cell.kind());
                assertNotEquals(CellKind.CLUSTER, cell.kind());
                assertNotEquals(CellKind.ADDRESS, cell.kind());
                assertNotEquals(CellKind.SERIAL, cell.kind());
                assertNull(cell.ref());
            }
            assertEquals(CellKind.NUMBER, row.get(3).kind());
        }
        for (String s : strings(result)) {
            assertFalse(s.contains("fw-tango-04"), "zone description or hostname must not appear: " + s);
            for (Zone z : zones) {
                assertFalse(s.contains(z.id()), "zone UUID must not appear: " + s);
            }
        }

        // Bundled Input overload and determinism.
        assertEquals(result, ZoneMatrixEvidenceBlocksAppendix.build(new Input(zones, new Rights(true, true, true))));
    }

    // ---- refusals (missing maySeeBlocks, missing rawReportRight, replay viewer with both true)

    @Test
    void zm38_requestedWithoutBlocksRightIsRefused() {
        Result refused = ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, false, true));
        assertRefused(refused);
    }

    @Test
    void zm38_requestedWithoutRawReportRightIsRefused() {
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, true, false)));
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, false, false)));
    }

    @Test
    void zm33_zm38_replayViewerWithBothRightsTrueIsAlwaysRefused() {
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, true, true, true)));
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), Rights.forReplayViewer(true, true, true)));
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, false, true, true)));
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, true, false, true)));
        assertRefused(ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), new Rights(true, false, false, true)));
    }

    @Test
    void zm38_noRefusalPathEverReturnsABlockStringAcrossResultAndWholeRenderedReport() {
        List<Rights> refusalPaths = List.of(
                new Rights(true, false, true),
                new Rights(true, true, false),
                new Rights(true, false, false),
                new Rights(true, true, true, true),
                new Rights(true, false, true, true),
                new Rights(true, true, false, true),
                new Rights(true, false, false, true));
        List<String> forbiddenBlockStrings = List.of(
                "192.0.2", "198.51.100", "2001:db8", "/24", "/32", "79228162514264337593543950336",
                "Appendix: zone blocks", ZoneMatrixEvidenceBlocksAppendix.FIXED_SENTENCE);

        for (Rights rights : refusalPaths) {
            Result refused = ZoneMatrixEvidenceBlocksAppendix.build(sampleZones(), rights);
            assertRefused(refused);

            for (String s : strings(refused)) {
                for (String marker : forbiddenBlockStrings) {
                    assertFalse(s.contains(marker), "refusal result leaked '" + marker + "' in: " + s);
                }
            }

            // Whole rendered report text (both RAW and MASKED projections) when composed with ZoneMatrixEvidenceReport.
            ZoneMatrixEvidenceReport.Input reportIn = new ZoneMatrixEvidenceReport.Input(
                    "1.0.0-test", "user-1", "MASKED", null, null, null, 0, 0, null, null, List.of(),
                    ZoneMatrixStaleness.INTERIM_PO_2026_11_23, List.of(), List.of(),
                    new ZoneMatrixEvidenceReport.Summary("0 rules violate the zone matrix (v1)", null), List.of());
            ZoneMatrixEvidenceReport.Built built = ZoneMatrixEvidenceReport.build(reportIn, NOW);
            List<Section> sections = new ArrayList<>(built.sections());
            sections.addAll(refused.sections());

            for (ProjectionMode mode : List.of(ProjectionMode.MASKED, ProjectionMode.RAW)) {
                ReportDocument projected = mask(new ReportDocument(
                        "ZM-EVIDENCE-TEST", built.title(), "Compliance", "Segmentation evidence", NOW,
                        ReportDocument.UNPROJECTED, Map.of(), List.of(), built.notices(), sections), mode);
                String wholeRenderedCsv = new String(new CsvReportRenderer().render(projected), StandardCharsets.UTF_8);
                for (String marker : forbiddenBlockStrings) {
                    assertFalse(wholeRenderedCsv.contains(marker),
                            "rendered CSV on refusal path (" + rights + ", " + mode + ") leaked '" + marker + "'");
                }
            }
        }
    }

    private static void assertRefused(Result result) {
        assertEquals(Status.REFUSED, result.status());
        assertTrue(result.isRefused());
        assertFalse(result.isIncluded());
        assertEquals(ZoneMatrixEvidenceBlocksAppendix.BLOCKS_NOT_PERMITTED, result.refusalCode());
        assertEquals("BLOCKS_NOT_PERMITTED", result.refusalCode());
        assertTrue(result.section().isEmpty());
        assertEquals(List.of(), result.sections());
    }

    // ---- IPv6 canonical RFC 5952 text

    @Test
    void ipv6BlocksPrintInCanonicalRfc5952Text() {
        Zone ipv6Zone = zone("ZONE-1", "APPLICATION", List.of(
                ZoneBlock.parse("2001:0DB8:0000:0000:0000:0000:0000:0000/32"),
                ZoneBlock.parse("2001:0db8:0:0:1:0:0:1/128"),
                ZoneBlock.parse("2001:0db8:0000:0001:0001:0001:0001:0001/128"),
                ZoneBlock.parse("2001:0DB8:000A:0000:0000:0000:00B0:0000/112")));
        Section section = ZoneMatrixEvidenceBlocksAppendix.build(
                List.of(ipv6Zone), new Rights(true, true, true)).section().orElseThrow();
        List<String> blocks = column(section.tables().get(0), "block");
        assertEquals(List.of(
                "2001:db8::/32",
                "2001:db8::1:0:0:1/128",
                "2001:db8:0:1:1:1:1:1/128",
                "2001:db8:a::b0:0/112"),
                blocks);
    }

    // ---- exact BigInteger counts for /0../128 edge prefixes

    @Test
    void exactDecimalAddressCountsHoldAcrossEveryEdgePrefixFromZeroToOneTwentyEight() {
        List<ZoneBlock> blocks = new ArrayList<>();
        for (int p = 0; p <= 32; p++) {
            blocks.add(ZoneBlock.parse("0.0.0.0/" + p));
        }
        blocks.add(ZoneBlock.parse("192.0.2.1/32"));
        for (int p = 0; p <= 128; p++) {
            blocks.add(ZoneBlock.parse("::/" + p));
        }
        blocks.add(ZoneBlock.parse("2001:db8::1/128"));

        Section section = ZoneMatrixEvidenceBlocksAppendix.build(
                List.of(zone("ZONE-1", "DMZ", blocks)), new Rights(true, true, true)).section().orElseThrow();
        Table table = section.tables().get(0);
        assertEquals(33 + 1 + 129 + 1, table.rows().size());

        Map<String, String> countByBlock = new java.util.LinkedHashMap<>();
        for (List<String> r : rowsText(table)) {
            assertTrue(r.get(3).matches("0|[1-9][0-9]*"), "decimal digits only, no scientific notation: " + r.get(3));
            countByBlock.put(r.get(2), r.get(3));
        }

        // Named edge cases (/0, /24, /31, /32, IPv6 /0, /32, /64 > Long.MAX_VALUE, /127, /128).
        assertEquals("4294967296", countByBlock.get("0.0.0.0/0"));
        assertEquals("256", countByBlock.get("0.0.0.0/24"));
        assertEquals("2", countByBlock.get("0.0.0.0/31"));
        assertEquals("1", countByBlock.get("0.0.0.0/32"));
        assertEquals("1", countByBlock.get("192.0.2.1/32"));
        assertEquals("340282366920938463463374607431768211456", countByBlock.get("::/0"));
        assertEquals("79228162514264337593543950336", countByBlock.get("::/32"));
        assertEquals("18446744073709551616", countByBlock.get("::/64"));
        assertEquals("2", countByBlock.get("::/127"));
        assertEquals("1", countByBlock.get("::/128"));
        assertEquals("1", countByBlock.get("2001:db8::1/128"));

        // Exhaustive check across /0../32 and /0../128.
        for (int p = 0; p <= 32; p++) {
            assertEquals(BigInteger.ONE.shiftLeft(32 - p).toString(), countByBlock.get("0.0.0.0/" + p));
        }
        for (int p = 0; p <= 128; p++) {
            assertEquals(BigInteger.ONE.shiftLeft(128 - p).toString(), countByBlock.get("::/" + p));
        }
    }

    // ---- empty zone ("no blocks") and uncapped large zone

    @Test
    void zoneWithNoBlockPrintsOneNoBlocksRowAndLargeZonePrintsEveryRowUncapped() {
        List<ZoneBlock> manyBlocks = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            manyBlocks.add(ZoneBlock.parse("2001:db8:" + Integer.toHexString(i) + "::/48"));
        }
        List<Zone> zones = List.of(
                zone("ZONE-1", "USER", List.of()),
                zone("ZONE-2", "APPLICATION", manyBlocks),
                zone(" ", null, List.of(ZoneBlock.parse("192.0.2.0/24"))));

        Section section = ZoneMatrixEvidenceBlocksAppendix.build(zones, new Rights(true, true, true))
                .section().orElseThrow();
        Table table = section.tables().get(0);
        assertEquals(1 + 1_500 + 1, table.rows().size());
        assertEquals(List.of("ZONE-1", "", "no blocks", "0"), rowsText(table).get(0));
        assertEquals(List.of("UNKNOWN", "IPV4", "192.0.2.0/24", "256"), rowsText(table).get(1_501));
        for (String s : strings(section)) {
            assertFalse(s.contains("more"), "nothing is capped: " + s);
        }
    }

    // ---- stable ordering (version zone order, then ZoneBlock.ORDER)

    @Test
    void orderingPreservesVersionZoneOrderThenZoneBlockOrder() {
        List<Zone> zones = List.of(
                zone("ZONE-3", "DMZ", List.of(
                        ZoneBlock.parse("2001:db8::/32"),
                        ZoneBlock.parse("198.51.100.0/24"),
                        ZoneBlock.parse("192.0.2.0/25"),
                        ZoneBlock.parse("192.0.2.0/24"))),
                zone("ZONE-1", "USER", List.of()),
                zone("ZONE-2", "DATABASE", List.of(
                        ZoneBlock.parse("198.51.100.128/25"),
                        ZoneBlock.parse("198.51.100.0/25"))));

        Section section = ZoneMatrixEvidenceBlocksAppendix.build(zones, new Rights(true, true, true))
                .section().orElseThrow();
        assertEquals(List.of(
                List.of("ZONE-3", "IPV4", "192.0.2.0/24", "256"),
                List.of("ZONE-3", "IPV4", "192.0.2.0/25", "128"),
                List.of("ZONE-3", "IPV4", "198.51.100.0/24", "256"),
                List.of("ZONE-3", "IPV6", "2001:db8::/32", "79228162514264337593543950336"),
                List.of("ZONE-1", "", "no blocks", "0"),
                List.of("ZONE-2", "IPV4", "198.51.100.0/25", "128"),
                List.of("ZONE-2", "IPV4", "198.51.100.128/25", "128")),
                rowsText(section.tables().get(0)));
    }

    // ---- CSV division of duty: formula-leading zone name unchanged here, escaped by R1 CSV guard

    @Test
    void zoneNameStartingWithEqualsIsReturnedUnchangedAndEscapedByTheR1CsvGuard() {
        List<Zone> zones = List.of(zone("=ZONE-1", "USER", List.of(ZoneBlock.parse("192.0.2.0/24"))));
        Result result = ZoneMatrixEvidenceBlocksAppendix.build(zones, new Rights(true, true, true));
        Section section = result.section().orElseThrow();
        assertEquals("=ZONE-1", column(section.tables().get(0), "zone").get(0),
                "this class passes projected zone names through unchanged");

        String csv = renderCsvWithAppendix(result.sections(), ProjectionMode.RAW);
        assertTrue(csv.contains("\"'=ZONE-1\""), "R1 CSV formula guard prefixes leading '=' with a single quote");
        assertFalse(csv.contains("\"=ZONE-1\""), "raw unescaped '=ZONE-1' must not appear as a CSV field");
    }

    // ---- helpers

    private static String renderCsvWithAppendix(List<Section> appendixSections, ProjectionMode mode) {
        Section base = new Section("summary", "Summary", List.of(), List.of(), List.of(),
                List.of("Synthetic report summary."));
        List<Section> all = new ArrayList<>();
        all.add(base);
        all.addAll(appendixSections);
        ReportDocument unprojected = new ReportDocument(
                "ZM-EVIDENCE-TEST",
                ZoneMatrixEvidenceReport.GENERIC_TITLE,
                "Compliance",
                "Segmentation evidence",
                NOW,
                ReportDocument.UNPROJECTED,
                Map.of(),
                List.of(),
                List.of(),
                all);
        return new String(new CsvReportRenderer().render(mask(unprojected, mode)), StandardCharsets.UTF_8);
    }

    private static ReportDocument mask(ReportDocument doc, ProjectionMode mode) {
        ReportProjectionMasker projectionMasker = new ReportProjectionMasker(
                new TopologyNamePseudonymizer(new byte[32]),
                new SubnetPreservingIpMasker(new byte[32]));
        return new ReportDocumentMasker(projectionMasker).project(doc, mode);
    }

    private static List<List<String>> rowsText(Table table) {
        return table.rows().stream().map(r -> r.stream().map(Cell::text).toList()).toList();
    }

    private static List<String> column(Table table, String key) {
        int idx = -1;
        for (int i = 0; i < table.columns().size(); i++) {
            if (table.columns().get(i).key().equals(key)) {
                idx = i;
                break;
            }
        }
        assertTrue(idx >= 0, "missing column " + key);
        int col = idx;
        return table.rows().stream().map(r -> r.get(col).text()).toList();
    }

    private static List<String> strings(Object root) {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Object v, List<String> out) {
        if (v == null) return;
        if (v instanceof String s) { out.add(s); return; }
        if (v instanceof Enum<?> e) { out.add(e.name()); return; }
        if (v instanceof Number || v instanceof Boolean || v instanceof Instant) {
            out.add(String.valueOf(v));
            return;
        }
        if (v instanceof Optional<?> opt) { opt.ifPresent(o -> walk(o, out)); return; }
        if (v instanceof Iterable<?> it) { for (Object o : it) walk(o, out); return; }
        if (v instanceof Map<?, ?> m) { m.forEach((k, x) -> { walk(k, out); walk(x, out); }); return; }
        if (v.getClass().isRecord()) {
            for (RecordComponent c : v.getClass().getRecordComponents()) {
                try {
                    var accessor = c.getAccessor();
                    accessor.setAccessible(true);
                    walk(accessor.invoke(v), out);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
            return;
        }
        throw new AssertionError("unexpected type in walk: " + v.getClass());
    }
}
