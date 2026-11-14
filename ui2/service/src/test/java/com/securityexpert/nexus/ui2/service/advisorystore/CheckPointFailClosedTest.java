package com.securityexpert.nexus.ui2.service.advisorystore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.CheckPointAdvisoryParser;
import com.securityexpert.nexus.ui2.service.advisory.DefaultAdvisoryCore;

/**
 * Fail-closed behaviour of the Check Point source (ADVISORY_MATCHING_CONTRACT.md amendment A-2026-11-13, corrected
 * 2026-11-14 after the independent review ARGON_CPADV_REVIEW_1114, findings 1 to 5). A Check Point device is never shown
 * as judged ("None affected") on a train whose resolved-issues page was not read in full, whose records are missing, or
 * whose advisory could not be read. Every document and device is synthetic.
 */
class CheckPointFailClosedTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final String RUN = "run-checkpoint-0001";
    private static final String PAGE = "https://sc1.checkpoint.com/documents/Jumbo_HFA/%s/All_Resolved_Issues.html";
    private static final String RECORDS = "https://raw.githubusercontent.com/CVEProject/cvelistV5/main/cves/2026/90xxx/";

    private final AdvisoryCore core = new DefaultAdvisoryCore(List.of(new CheckPointAdvisoryParser()));
    private final AdvisoryTestSupport.MemoryRepository repo = new AdvisoryTestSupport.MemoryRepository();
    private final AdvisoryIngestService ingest = new AdvisoryIngestService(repo, () -> Optional.of(core));
    private final DeviceRepository devices = mock(DeviceRepository.class);

    static String row(String description) {
        return "<tr class=\"- topic/row\"><td><p>PRJ-1</p></td><td><p>Security Gateway</p></td><td><p>" + description + "</p></td></tr>";
    }

    static String section(int take, String... rows) {
        return "<section class=\"- topic/section section\" id=\"All_Resolved_Issues__Take_" + take + "\"><h2>Take " + take
                + "</h2><table><tbody>" + String.join("", rows) + "</tbody></table></section>";
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] record(String cve, String assigner, String affected) {
        return bytes("{\"cveMetadata\":{\"cveId\":\"" + cve + "\",\"assignerShortName\":\"" + assigner + "\",\"state\":\"PUBLISHED\","
                + "\"datePublished\":\"2026-10-01T00:00:00Z\"},\"containers\":{\"cna\":{\"title\":\"Synthetic " + cve + "\",\"affected\":"
                + affected + ",\"metrics\":[{\"cvssV3_1\":{\"baseScore\":8.1,\"baseSeverity\":\"HIGH\"}}]}}}");
    }

    static String entry(String product, String version) {
        return "{\"vendor\":\"checkpoint\",\"product\":\"" + product + "\",\"versions\":[{\"status\":\"affected\",\"version\":\"" + version + "\"}]}";
    }

    private static DeviceSummaryRecord device(String id, String role, String version) {
        return new DeviceSummaryRecord(id, role, "check_point", DeviceEnrollmentState.ENROLLED, Optional.of("FW-TANGO-" + id),
                Optional.of("Example Appliance"), Optional.ofNullable(version), Optional.empty(), Optional.empty());
    }

    private void page(String run, String train, String html) {
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.ingest("checkpoint", run, PAGE.formatted(train), bytes(html)));
    }

    private void record(String run, String cve, byte[] body) {
        assertEquals(AdvisoryIngestService.Outcome.STORED, ingest.ingest("checkpoint", run, RECORDS + cve + ".json", body));
    }

    private String close(String run, int fetched, int failed) {
        ingest.complete("checkpoint", run, bytes("{\"documents_fetched\":" + fetched + ",\"documents_failed\":" + failed + "}"));
        return repo.run(run, "checkpoint").orElseThrow().outcome();
    }

    /** The vulnerabilities object of each device, with the hotfix levels given. */
    private Map<String, Map<String, Object>> judge(Map<String, String> hotfix, DeviceSummaryRecord... fleet) {
        when(devices.listAll()).thenReturn(List.of(fleet));
        var read = new AdvisoryReadService(repo, devices, () -> Optional.of(core), () -> hotfix, Map::of);
        var facts = devices.listAll().stream().map(d -> AdvisoryReadService.facts(d, hotfix.get(d.deviceId()))).toList();
        return read.vulnerabilities(facts, NOW);
    }

    /** Finding 1: a run in which no page was recognised is not a load; the devices are not judged. */
    @Test
    void aRunThatRecognisedNoPageIsFailedAndJudgesNoDevice() {
        page(RUN, "R81.20", "<html>a new layout without take sections</html>");
        assertEquals("FAILED", close(RUN, 4, 0));
        var v = judge(Map.of("gw", "R81.20 Jumbo Take 119"), device("gw", "gateway", "R81.20"));
        assertEquals("NOT_EVALUATED", v.get("gw").get("state"));
    }

    /** Finding 1: pages read but none of the records they name stored: the train is not judged. */
    @Test
    void aTrainWhoseRecordsAreMissingIsNotJudged() {
        page(RUN, "R81.20", section(166, row("UPDATE: Resolved CVE-2026-90001.")));
        close(RUN, 2, 1);
        var v = judge(Map.of("gw", "R81.20 Jumbo Take 119"), device("gw", "gateway", "R81.20"));
        assertEquals("NOT_EVALUATED", v.get("gw").get("state"));
        assertEquals("TRAIN_RECORDS_MISSING", v.get("gw").get("reason"));
    }

    /** Finding 1: a train whose last accepted page is older than 30 days is stale, whatever later runs received. */
    @Test
    void aTrainWhoseLastAcceptedPageIsOlderThan30DaysIsStale() {
        page(RUN, "R81.20", section(166, row("UPDATE: Resolved CVE-2026-90001.")));
        record(RUN, "CVE-2026-90001", record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R81.20 with Jumbo Hotfix Take 165 or below") + "]"));
        close(RUN, 2, 0);
        repo.pageLoads.put("R81.20", new com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.JumboPageLoad("R81.20",
                NOW.minus(java.time.Duration.ofDays(31)), 1));
        var v = judge(Map.of("gw", "R81.20 Jumbo Take 119"), device("gw", "gateway", "R81.20"));
        assertEquals("NOT_EVALUATED", v.get("gw").get("state"));
        assertEquals("TRAIN_STALE", v.get("gw").get("reason"));
    }

    /** Finding 4: a train whose page was never read is not judged, even when another train's page was. */
    @Test
    void aTrainWhosePageWasNeverReadIsNotJudged() {
        page(RUN, "R82.20", section(14, row("UPDATE: Resolved CVE-2026-90001.")));
        record(RUN, "CVE-2026-90001", record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R82.20 with Jumbo Hotfix Take 13 or below") + "]"));
        close(RUN, 2, 3);
        var v = judge(Map.of("old", "R81.20 Jumbo Take 119", "new", "R82.20 Jumbo Take 10"),
                device("old", "gateway", "R81.20"), device("new", "gateway", "R82.20"));
        assertEquals("NOT_EVALUATED", v.get("old").get("state"));
        assertEquals("TRAIN_NEVER_LOADED", v.get("old").get("reason"));
        assertEquals("EVALUATED", v.get("new").get("state"));
        assertEquals(1, v.get("new").get("affected"));
    }

    /** Finding 2: a page that keeps its take sections but whose table rows can no longer be read keeps the last good statements. */
    @Test
    void aPageWithUnreadableRowsKeepsTheLastGoodStatements() {
        page(RUN, "R81.20", section(166, row("UPDATE: Resolved CVE-2026-90001.")) + section(120, row("UPDATE: Resolved CVE-2026-90002.")));
        assertEquals(2, repo.jumbo.size());
        page("run-checkpoint-0002", "R81.20", section(170, "<tr class=\"- topic/row\"><td>a</td><td>b</td><td>c</td>"
                + "<td>UPDATE: Resolved CVE-2026-90003.</td></tr>"));
        assertEquals(2, repo.jumbo.size(), "a changed table never empties the train");
        // A page that lost takes the stored set has (cut short, or a changed history) is not accepted either.
        page("run-checkpoint-0003", "R81.20", section(170, row("UPDATE: Resolved CVE-2026-90003.")) + section(166, row("UPDATE: Resolved CVE-2026-90001.")));
        assertEquals(List.of("CVE-2026-90001", "CVE-2026-90002"), repo.jumbo.stream().map(j -> j.cveId()).sorted().toList());
        assertEquals("FAILED", close("run-checkpoint-0003", 1, 0), "no page was accepted in this run");
    }

    /** Finding 3: an advisory that is not read in full stays open on every train its record names. */
    @Test
    void anUnreadAdvisoryStaysOpenOnTheTrainsOnlyItsRecordNames() {
        page(RUN, "R81.20", section(166, row("UPDATE: Resolved CVE-2026-90001.")));
        page(RUN, "R82", section(100, row("UPDATE: Resolved CVE-2026-90009.")));
        record(RUN, "CVE-2026-90001", record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R82 with Jumbo Hotfix Take 125 or below") + "," + entry("Quantum Firewall Blade", "R81.20 with Jumbo Hotfix Take 165 or below") + "]"));
        record(RUN, "CVE-2026-90009", record("CVE-2026-90009", "someoneelse", "[]"));
        close(RUN, 4, 0);
        var v = judge(Map.of("gw", "R82 Jumbo Take 101", "mgmt", "R82 Jumbo Take 101"),
                device("gw", "gateway", "R82"), device("mgmt", "management_server", "R82"));
        assertEquals(1, v.get("gw").get("not_evaluable"), "R82 is named by the record, not by a resolved line");
        assertEquals(1, v.get("mgmt").get("not_evaluable"), "both products");
    }

    /** Finding 3: a self-check failure leaves the advisory open for both products and every train. */
    @Test
    void aSelfCheckFailureStaysOpenForBothProducts() {
        page(RUN, "R81.20", section(160, row("UPDATE: Resolved CVE-2026-90001.")));
        record(RUN, "CVE-2026-90001", record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R81.20 with Jumbo Hotfix Take 165 or below") + "]"));
        close(RUN, 2, 0);
        var v = judge(Map.of("mgmt", "R81.20 Jumbo Take 170"), device("mgmt", "management_server", "R81.20"));
        assertEquals(1, v.get("mgmt").get("not_evaluable"));
    }

    /** Finding 5: a Check Point record that names only products outside Gaia, for a CVE a Gaia page resolved, is open. */
    @Test
    void aRecordNamingOnlySparkForACveAGaiaPageResolvedIsOpen() {
        page(RUN, "R81.20", section(166, row("UPDATE: Resolved CVE-2026-90001.")));
        record(RUN, "CVE-2026-90001", record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Spark", "R81.10") + "]"));
        close(RUN, 2, 0);
        assertEquals("NEEDS_REVIEW", repo.advisories.get("checkpoint:CVE-2026-90001").parseState());
        var v = judge(Map.of("gw", "R81.20 Jumbo Take 119"), device("gw", "gateway", "R81.20"));
        assertEquals(1, v.get("gw").get("not_evaluable"));
    }

    /** A train a resolved-issues page names and the record does not is a proposal for the record's products, never "not listed". */
    @Test
    void aTrainOnlyAResolvedLineNamesIsAProposal() {
        page(RUN, "R81.20", section(166, row("UPDATE: Resolved CVE-2026-90001.")));
        page(RUN, "R82", section(126, row("UPDATE: Resolved CVE-2026-90001.")));
        record(RUN, "CVE-2026-90001", record("CVE-2026-90001", "checkpoint", "[" + entry("Quantum Security Gateway",
                "R81.20 with Jumbo Hotfix Take 165 or below") + "]"));
        close(RUN, 3, 0);
        var v = judge(Map.of("gw", "R82 Jumbo Take 120"), device("gw", "gateway", "R82"));
        assertEquals(1, v.get("gw").get("pending"));
    }
}
