package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed.Chunk;
import com.securityexpert.nexus.ui2.platform.SystemLogCensusFeed.Masked;
import com.securityexpert.nexus.ui2.worker.diagnostic.SystemLogTemplateCensus.Options;

/** The census over a fake feed of masked synthetic chunks (the EDAC/MCE shape of the C22 corpus case included). */
class SystemLogTemplateCensusTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A feed over in-memory chunks; the pseudonym map is the "aiview" side. */
    private static class FakeFeed implements SystemLogCensusFeed {
        record Row(Chunk meta, Masked chunk) {
        }

        final List<Row> rows = new ArrayList<>();
        final Map<String, String> pseudonyms;
        int reads;

        FakeFeed(Map<String, String> pseudonyms) {
            this.pseudonyms = pseudonyms;
        }

        void add(String device, String source, String day, List<String> lines) {
            add(device, source, day, lines, 0);
        }

        void add(String device, String source, String day, List<String> lines, long rejected) {
            rows.add(new Row(new Chunk("c" + rows.size(), device, source, day, "ref", new byte[] {1}, 100),
                    new Masked(lines, rejected, false)));
        }

        @Override
        public void chunks(LocalDate from, LocalDate to, List<String> sources, SystemLogCensusFeed.Sink sink) throws Exception {
            for (Row r : rows) {
                if (!sink.accept(r.meta())) return;
            }
        }

        @Override
        public Masked read(Chunk chunk, int maxBytes) {
            reads++;
            return rows.stream().filter(r -> r.meta() == chunk).findFirst().orElseThrow().chunk();
        }

        @Override
        public String devicePseudonym(String deviceId) {
            return pseudonyms.get(deviceId);
        }

        @Override
        public String maskRuleSet() {
            return "logmask-test";
        }

        @Override
        public void close() {
        }
    }

    private static String edac(String host, int n, int page) {
        return "Nov 24 13:31:44 2026 " + host + " kernel: EDAC MC0: " + n
                + " CE memory read error on CPU_SrcID#0_Ha#0_Chan#1_DIMM#0 (channel:1 slot:0 page:0x" + Integer.toHexString(page)
                + " offset:0x0 grain:32)";
    }

    private static String ssh(String host, int pid) {
        return "<6>Nov 24 10:00:" + String.format("%02d", pid % 60) + " 2026 " + host + " sshd[" + pid
                + "]: Accepted publickey for USR-6E19B2D0A4 from 198.51.100.9 port " + (40000 + pid);
    }

    private static Options options(long maxLines) {
        return new Options(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), List.of("cp_messages", "pan_system"),
                maxLines, 4, 0.5, 100, 1000, 1 << 20, null);
    }

    private static FakeFeed fleet() {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-SIERRA-06-M2", "dev-2", "FW-TANGO-04", "dev-3", "FW-JULIET-06",
                "dev-4", "FW-BRAVO-02", "dev-5", "FW-DELTA-12"));
        // one device has the DIMM shape on two days; five devices share the common ssh line
        feed.add("dev-1", "cp_messages", "2026-11-23", List.of(edac("FW-SIERRA-06-M2", 1, 0x4a1f2), edac("FW-SIERRA-06-M2", 1, 0x4a1f2),
                ssh("FW-SIERRA-06-M2", 100)));
        feed.add("dev-1", "cp_messages", "2026-11-24", List.of(edac("FW-SIERRA-06-M2", 3, 0x4a1f3), edac("FW-SIERRA-06-M2", 6, 0x4a1f2),
                edac("FW-SIERRA-06-M2", 9, 0x4a1f4),
                "Nov 24 13:31:44 2026 FW-SIERRA-06-M2 kernel: mce: [Hardware Error]: Machine check events logged",
                "  stack continuation without a header"));
        for (int d = 2; d <= 5; d++) {
            feed.add("dev-" + d, "cp_messages", "2026-11-24", List.of(ssh("FW-X-0" + d, 200 + d), ssh("FW-X-0" + d, 300 + d)));
        }
        feed.add("dev-1", "cp_messages", "2026-11-25", List.of(ssh("FW-SIERRA-06-M2", 101)));
        return feed;
    }

    private static JsonNode run(FakeFeed feed, Options o) throws Exception {
        return JSON.readTree(SystemLogTemplateCensus.run(feed, o, null));
    }

    private static JsonNode template(JsonNode family, String contains) {
        for (JsonNode t : family.get("templates")) {
            if (t.get("template").asText().contains(contains)) return t;
        }
        return null;
    }

    @Test
    void familyCountsAndTemplatePrevalence() throws Exception {
        JsonNode r = run(fleet(), options(1_000_000));
        JsonNode fam = r.get("families").get("cp_messages");
        assertEquals(5, fam.get("devices").asInt());
        assertEquals(1, fam.get("unparsed_lines").asInt());
        assertEquals(2 + 1 + 5 + 8 + 1, fam.get("lines").asInt());
        assertEquals(fam.get("templates").size(), fam.get("template_count").asInt());
        JsonNode sshT = template(fam, "sshd");
        assertEquals(5, sshT.get("device_count").asInt());
        assertEquals(1 + 8 + 1, sshT.get("count").asInt());
        assertEquals("info", sshT.get("severity").asText());
        assertEquals("2026-11-23", sshT.get("first_day").asText());
        assertEquals("2026-11-25", sshT.get("last_day").asText());
        assertEquals(2, sshT.get("max_device_day_count").asInt());
        assertEquals("logmask-test", r.get("mask_rule_set").asText());
    }

    @Test
    void theDimmShapeLandsInRareAndSevereWithPseudonymAndDays() throws Exception {
        JsonNode r = run(fleet(), options(1_000_000));
        JsonNode rare = r.get("rare_and_severe");
        assertTrue(rare.size() >= 2, rare.toString());
        JsonNode edac = null;
        JsonNode mce = null;
        for (JsonNode e : rare) {
            if ("edac_ce".equals(e.get("marker").asText())) edac = e;
            if ("mce_hardware_error".equals(e.get("marker").asText())) mce = e;
            assertTrue(e.get("device_count").asInt() <= 3);
        }
        assertEquals(5, edac.get("count").asInt());
        assertEquals(1, edac.get("device_count").asInt());
        assertEquals("FW-SIERRA-06-M2", edac.get("devices").get(0).get("device").asText());
        assertEquals(5, edac.get("devices").get(0).get("count").asInt());
        assertEquals(2, edac.get("devices").get(0).get("days").size());
        assertEquals("2026-11-23", edac.get("devices").get(0).get("days").get(0).get("day").asText());
        assertEquals(2, edac.get("devices").get(0).get("days").get(0).get("count").asInt());
        assertEquals(3, template(r.get("families").get("cp_messages"), "EDAC").get("max_device_day_count").asInt());
        assertTrue(mce != null);
        // the ssh line is common, not rare
        for (JsonNode e : rare) assertFalse(e.get("template").asText().contains("sshd"));
    }

    @Test
    void errorSeverityOnARareTemplateIsRareAndSevereWithoutAMarker() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-ALPHA-01"));
        feed.add("dev-1", "cp_messages", "2026-11-10", List.of("<3>Nov 10 10:00:00 2026 FW-ALPHA-01 vpnd[5]: tunnel negotiation failed"));
        JsonNode rare = run(feed, options(1000)).get("rare_and_severe");
        assertEquals(1, rare.size());
        assertEquals("error", rare.get(0).get("severity").asText());
        assertTrue(rare.get(0).get("marker").isNull());
    }

    @Test
    void panLinesAreMinedBySubtypeAndEvent() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-DELTA-12"));
        feed.add("dev-1", "pan_system", "2026-11-24", List.of(
                "receive_time=2026/11/24 03:22:10 severity=critical type=system subtype=hw eventid=fan-failed module=general object=fan-tray-2 opaque=\"Fan tray 2: fan 3 failed (0 RPM)\"",
                "receive_time=2026/11/24 03:30:10 severity=critical type=system subtype=hw eventid=fan-failed module=general object=fan-tray-1 opaque=\"Fan tray 1: fan 2 failed (0 RPM)\""));
        JsonNode r = run(feed, options(1000));
        JsonNode fam = r.get("families").get("pan_system");
        assertEquals(1, fam.get("template_count").asInt());
        assertEquals("critical", fam.get("templates").get(0).get("severity").asText());
        assertEquals("FW-DELTA-12", r.get("rare_and_severe").get(0).get("devices").get(0).get("device").asText());
    }

    @Test
    void panJsonLinesInTheCollectorShapeAreMined() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-DELTA-12"));
        feed.add("dev-1", "pan_system", "2026-11-24", List.of(
                "{\"receive_time\":\"2026/11/24 03:22:10\",\"seqno\":\"1\",\"type\":\"SYSTEM\",\"subtype\":\"hw\",\"eventid\":\"fan-failed\",\"severity\":\"critical\",\"opaque\":\"Fan tray 2: fan 3 failed (0 RPM)\"}",
                "{\"receive_time\":\"2026/11/24 03:30:10\",\"seqno\":\"2\",\"type\":\"SYSTEM\",\"subtype\":\"hw\",\"eventid\":\"fan-failed\",\"severity\":\"critical\",\"opaque\":\"Fan tray 1: fan 2 failed (0 RPM)\"}",
                "    continuation line"));
        JsonNode fam = run(feed, options(1000)).get("families").get("pan_system");
        assertEquals(1, fam.get("template_count").asInt());
        assertEquals(1, fam.get("unparsed_lines").asInt());
        assertEquals("critical", fam.get("templates").get(0).get("severity").asText());
    }

    @Test
    void guardRejectedLinesAreCountedNotMined() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-ALPHA-01"));
        feed.add("dev-1", "cp_messages", "2026-11-10", List.of(ssh("FW-ALPHA-01", 7)), 3);
        JsonNode fam = run(feed, options(1000)).get("families").get("cp_messages");
        assertEquals(3, fam.get("guard_rejected_lines").asInt());
        assertEquals(4, fam.get("lines").asInt());
        assertEquals(1, fam.get("template_count").asInt());
    }

    @Test
    void maxLinesStopsTheRun() throws Exception {
        FakeFeed feed = fleet();
        JsonNode r = run(feed, options(4));
        assertTrue(r.get("totals").get("stopped_by_max_lines").asBoolean());
        assertEquals(4, r.get("totals").get("lines").asInt());
        assertTrue(feed.reads < feed.rows.size());
    }

    @Test
    void aFailingChunkIsCountedByKindOnly() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-ALPHA-01")) {
            @Override
            public Masked read(Chunk chunk, int maxBytes) {
                throw new IllegalStateException("secret detail 192.0.2.5");
            }
        };
        feed.add("dev-1", "cp_messages", "2026-11-10", List.of("x"));
        String json = SystemLogTemplateCensus.run(feed, options(10), null);
        JsonNode r = JSON.readTree(json);
        assertEquals(1, r.get("totals").get("chunks_failed").asInt());
        assertEquals(1, r.get("totals").get("chunk_error_kinds").get("illegalstateexception").asInt());
        assertFalse(json.contains("192.0.2.5"));
    }

    @Test
    void theReportCarriesNoAddressNoHostnameAndNoDeviceId() throws Exception {
        FakeFeed feed = fleet();
        String json = SystemLogTemplateCensus.run(feed, options(1_000_000), null);
        assertTrue(CensusMaskGuard.findings(json).isEmpty());
        assertFalse(json.contains("198.51.100.9"));
        assertFalse(json.contains("dev-1"), "internal device ids never leave");
        assertFalse(json.contains("USR-6E19B2D0A4"));
    }

    @Test
    void aPseudonymThatLooksLikeANameIsReplacedByAnOpaqueOrdinal() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "db01.corp.example.com"));
        feed.add("dev-1", "cp_messages", "2026-11-10", List.of("<3>Nov 10 10:00:00 2026 H-1 vpnd[5]: tunnel negotiation failed"));
        String json = SystemLogTemplateCensus.run(feed, options(1000), null);
        assertFalse(json.contains("example.com"));
        assertEquals("DEVICE-1", JSON.readTree(json).get("rare_and_severe").get(0).get("devices").get(0).get("device").asText());
    }

    @Test
    void anAddressGluedToTextIsStillCollapsedInTheTemplate() throws Exception {
        FakeFeed feed = new FakeFeed(Map.of("dev-1", "FW-ALPHA-01"));
        feed.add("dev-1", "pan_system", "2026-11-10", List.of(
                "receive_time=2026/11/10 03:22:10 severity=high type=system subtype=x eventid=y module=general object= opaque=\"peer=192.0.2.5_ok\""));
        String json = SystemLogTemplateCensus.run(feed, options(100), null);
        assertTrue(CensusMaskGuard.findings(json).isEmpty());
        assertFalse(json.contains("192.0.2.5"));
    }

    @Test
    void optionsParseAndRefuseBadArguments() {
        Options o = Options.parse(new String[] {"--from", "2026-11-01", "--to", "2026-11-30", "--sources", "cp_messages,pan_system",
                "--max-lines", "500", "--out", "/app/tmp/census.json"});
        assertEquals(List.of("cp_messages", "pan_system"), o.sources());
        assertEquals(500, o.maxLines());
        assertEquals(4, o.depth());
        assertEquals("/app/tmp/census.json", o.out());
        assertNull(Options.parse(new String[] {"--from", "2026-11-01", "--to", "2026-11-02", "--sources", "cp_messages"}).out());
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--from", "2026-11-01", "--sources", "cp_messages"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--from", "2026-11-05", "--to", "2026-11-01", "--sources", "cp_messages"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--from", "2026-11-01", "--to", "2026-11-02", "--sources", "cp_messages; drop"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--bogus", "1"}));
    }

    @Test
    void templateIdIsStableAndTextDerived() {
        assertEquals(SystemLogTemplateCensus.templateId("a b <*>"), SystemLogTemplateCensus.templateId("a b <*>"));
        assertEquals(12, SystemLogTemplateCensus.templateId("a").length());
        assertFalse(SystemLogTemplateCensus.templateId("a").equals(SystemLogTemplateCensus.templateId("b")));
    }
}
