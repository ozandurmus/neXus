package com.securityexpert.nexus.ui2.service.advisorystore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryParser;
import com.securityexpert.nexus.ui2.service.advisory.DefaultAdvisoryCore;

/**
 * Check Point from the fetched documents to the Lifecycle verdicts (ADVISORY_MATCHING_CONTRACT.md amendment A-2026-11-13),
 * with the real core and parser, the ingest service, an in-memory store and the read service. Every document, device, take
 * and number is synthetic.
 */
class CheckPointAdvisoryFlowTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final String RUN = "run-checkpoint-0001";
    private static final String PAGE_R8120 = "https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.20/All_Resolved_Issues.html";
    private static final String PAGE_R82 = "https://sc1.checkpoint.com/documents/Jumbo_HFA/R82/All_Resolved_Issues.html";
    private static final String RECORDS = "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/90xxx/";

    private final AdvisoryCore core = new DefaultAdvisoryCore(List.of(new CheckPointAdvisoryParser()));
    private final AdvisoryTestSupport.MemoryRepository repo = new AdvisoryTestSupport.MemoryRepository();
    private final AdvisoryIngestService ingest = new AdvisoryIngestService(repo, () -> Optional.of(core));
    private final DeviceRepository devices = mock(DeviceRepository.class);

    private static String row(String product, String description) {
        return "<tr class=\"- topic/row\"><td><p>PRJ-1</p></td><td><p>" + product + "</p></td><td><p>" + description + "</p></td></tr>";
    }

    private static byte[] page(int take, String... rows) {
        return ("<section class=\"- topic/section section\" id=\"All_Resolved_Issues__Take_" + take + "\"><h2>Take " + take
                + "</h2><table><tbody>" + String.join("", rows) + "</tbody></table></section>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] record(String cve, String assigner, String affected) {
        return ("{\"cveMetadata\":{\"cveId\":\"" + cve + "\",\"assignerShortName\":\"" + assigner + "\",\"state\":\"PUBLISHED\","
                + "\"datePublished\":\"2026-10-01T00:00:00Z\"},\"containers\":{\"cna\":{\"title\":\"Synthetic " + cve + "\",\"affected\":"
                + affected + ",\"metrics\":[{\"cvssV3_1\":{\"baseScore\":8.1,\"baseSeverity\":\"HIGH\"}}],\"references\":[{\"url\":"
                + "\"https://support.checkpoint.com/results/sk/sk9100001\"}]}}}").getBytes(StandardCharsets.UTF_8);
    }

    private static DeviceSummaryRecord device(String id, String role, String name, String version) {
        return new DeviceSummaryRecord(id, role, "check_point", DeviceEnrollmentState.ENROLLED, Optional.of(name),
                Optional.of("Example Appliance"), Optional.ofNullable(version), Optional.empty(), Optional.empty());
    }

    /** Pages first, then records, as the fetcher sends them; then the run is closed. */
    private void load() {
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.ingest("checkpoint", RUN, PAGE_R8120, page(166,
                row("VPN", "UPDATE: Resolved CVE-2026-90001 - synthetic overflow. Refer to sk9100001."),
                row("Gaia OS", "UPDATE: A component was updated to fix CVE-2026-90002."))));
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.ingest("checkpoint", RUN, PAGE_R82, page(126,
                row("VPN", "UPDATE: Resolved CVE-2026-90001 - synthetic overflow. Refer to sk9100001."))));
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.ingest("checkpoint", RUN, RECORDS + "CVE-2026-90001.json",
                record("CVE-2026-90001", "checkpoint", "[{\"vendor\":\"checkpoint\",\"product\":\"Quantum Security Gateway\",\"versions\":["
                        + "{\"status\":\"affected\",\"version\":\"R82 with Jumbo Hotfix Take 125 or below\"},"
                        + "{\"status\":\"affected\",\"version\":\"R81.20 with Jumbo Hotfix Take 165 or below\"}]}]")));
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.ingest("checkpoint", RUN, RECORDS + "CVE-2026-90002.json",
                record("CVE-2026-90002", "someoneelse", "[]")));
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.complete("checkpoint", RUN,
                "{\"documents_fetched\":4,\"documents_failed\":0}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theStatementsAreStoredPerTrainAndTheRecordsJoinThem() {
        load();
        assertEquals(3, repo.jumbo.size());
        assertEquals(2, repo.advisories.size());
        var run = repo.run(RUN, "checkpoint").orElseThrow();
        assertEquals("OK", run.outcome());
        assertEquals(4, run.documentsReceived());
        assertEquals(2, run.advisoriesStored());
        // A page read again replaces its own train only; one that is not this page form changes nothing.
        ingest.ingest("checkpoint", "run-checkpoint-0002", PAGE_R8120, page(170, row("VPN", "UPDATE: Resolved CVE-2026-90009.")));
        assertEquals(List.of("R82", "R81.20"), repo.jumbo.stream().map(j -> j.train()).distinct().toList());
        assertEquals(2, repo.jumbo.size());
        ingest.ingest("checkpoint", "run-checkpoint-0002", PAGE_R8120, "<html>changed layout</html>".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, repo.jumbo.size());
    }

    @Test
    void checkPointDevicesAreJudgedByTrainAndJumboTakeAndPendingStaysOutOfTheCounts() {
        load();
        when(devices.listAll()).thenReturn(List.of(
                device("gw-old", "gateway", "FW-TANGO-01", "R81.20"),
                device("gw-new", "gateway", "FW-TANGO-02", "R81.20"),
                device("gw-nojumbo", "gateway", "FW-TANGO-03", "R81.20"),
                device("spark", "gateway", "FW-TANGO-04", "R81.10.15"),
                device("gw-r8110", "gateway", "FW-TANGO-05", "R81.10"),
                device("mds", "management_server", "FW-TANGO-06", "R82")));
        Map<String, String> hotfix = Map.of("gw-old", "R81.20 Jumbo Take 119", "gw-new", "R81.20 Jumbo Take 170",
                "spark", "R81.20 Jumbo Take 1", "mds", "R82 Jumbo Take 120");
        Map<String, List<LivePatchItem>> patches = Map.of("gw-old", List.of(new LivePatchItem("PATCH:fwd", "armed", "auto", 1, 1, null,
                List.of("sk9100001", "CVE-2026-90001"), NOW)));
        var read = new AdvisoryReadService(repo, devices, () -> Optional.of(core), () -> hotfix, () -> patches);
        var facts = devices.listAll().stream().map(d -> AdvisoryReadService.facts(d, hotfix.get(d.deviceId()))).toList();
        Map<String, Map<String, Object>> v = read.vulnerabilities(facts, NOW);

        assertEquals("EVALUATED", v.get("gw-old").get("state"));
        assertEquals(1, v.get("gw-old").get("affected"), "take 119 is at or below the last affected take 165");
        assertEquals(1, v.get("gw-old").get("pending"), "the third-party CVE resolved in take 166 waits for confirmation");
        assertEquals(0, v.get("gw-old").get("not_evaluable"), "pending is not counted as not evaluable");
        assertEquals("HIGH", v.get("gw-old").get("worst_severity"));
        assertEquals(2, v.get("gw-new").get("fixed"));
        assertEquals(0, v.get("gw-new").get("affected"));
        assertEquals(0, v.get("gw-new").get("pending"));
        assertEquals("VERSION_UNKNOWN", v.get("gw-nojumbo").get("state"));
        assertEquals("JUMBO_TAKE_MISSING", v.get("gw-nojumbo").get("reason"));
        assertEquals("NOT_COVERED", v.get("spark").get("state"));
        assertEquals("SPARK_NOT_COVERED", v.get("spark").get("reason"));
        assertEquals("TRAIN_NOT_COVERED", v.get("gw-r8110").get("reason"));
        assertEquals("EVALUATED", v.get("mds").get("state"));
        assertEquals(0, v.get("mds").get("affected"), "the record names gateways only; management is not listed");
        assertEquals(0, v.get("mds").get("pending"), "the R82 page does not name the third-party CVE");

        @SuppressWarnings("unchecked")
        var detail = (List<Map<String, Object>>) read.detail("checkpoint:CVE-2026-90001", NOW).orElseThrow().get("devices");
        Map<String, Object> old = detail.stream().filter(d -> d.get("device_id").equals("gw-old")).findFirst().orElseThrow();
        assertEquals("AFFECTED", old.get("verdict"), "an armed Live Patch never changes the verdict");
        assertEquals("R81.20 Jumbo Take 119", old.get("version"));
        assertEquals(Map.of("patch", "PATCH:fwd", "status", "armed", "used_in_verdict", false), old.get("live_patch"));
        Map<String, Object> fresh = detail.stream().filter(d -> d.get("device_id").equals("gw-new")).findFirst().orElseThrow();
        assertEquals("FIXED", fresh.get("verdict"));
        assertNull(fresh.get("live_patch"));
        assertTrue(fresh.containsKey("live_patch"));

        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) read.list(NOW).get("advisories");
        var third = list.stream().filter(a -> a.get("advisory_id").equals("checkpoint:CVE-2026-90002")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var counts = (Map<String, Object>) third.get("devices");
        assertEquals(1, counts.get("pending"));
        assertEquals(1, counts.get("not_evaluable"), "the gateway without a stored Jumbo take, not the pending one");
        assertEquals("THIRD_PARTY_CVE", third.get("parse_note"));
        assertEquals("https://support.checkpoint.com/results/sk/sk9100001", third.get("source_url"));

        @SuppressWarnings("unchecked")
        var sources = (List<Map<String, Object>>) read.sources(NOW).get("sources");
        var checkpoint = sources.stream().filter(s -> s.get("source").equals("checkpoint")).findFirst().orElseThrow();
        assertEquals(2, checkpoint.get("advisories"));
        assertEquals("OK", checkpoint.get("age_state"));
    }

    @Test
    void theIngestAcceptsOnlyTheCheckPointPagesAndTheCveList() {
        assertTrue(AdvisorySources.documentUrlAllowed("checkpoint", PAGE_R82));
        assertTrue(AdvisorySources.documentUrlAllowed("checkpoint", RECORDS + "CVE-2026-90001.json"));
        assertFalse(AdvisorySources.documentUrlAllowed("checkpoint", "https://sc1.checkpoint.com/documents/Jumbo_HFA/R81.10/All_Resolved_Issues.html"));
        assertFalse(AdvisorySources.documentUrlAllowed("checkpoint", "https://raw.githubusercontent.com/other/repo/main/cves/x.json"));
        assertFalse(AdvisorySources.documentUrlAllowed("paloalto", RECORDS + "CVE-2026-90001.json"));
        assertTrue(AdvisorySources.linkAllowed("checkpoint", "https://support.checkpoint.com/results/sk/sk9100001"));
        assertFalse(AdvisorySources.linkAllowed("checkpoint", "https://example.org/sk"));
        assertEquals("checkpoint", AdvisorySources.KEYS.getLast());
    }
}
