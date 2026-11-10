package com.securityexpert.nexus.ui2.service.advisorystore;

import static com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.PRODUCT_FORTIOS;
import static com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.PRODUCT_PAN_OS;
import static com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredKnownExploited;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;

class AdvisoryReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private final MemoryRepository repo = new MemoryRepository();
    private final FakeCore core = new FakeCore();
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final AdvisoryReadService service = new AdvisoryReadService(repo, devices, () -> Optional.of(core));

    private static DeviceSummaryRecord device(String id, String vendor, String role, String name, String version) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.ofNullable(name),
                Optional.of("Example Appliance"), Optional.ofNullable(version), Optional.empty(), Optional.empty());
    }

    private void store(String source, String id, Severity severity, ParseState state, List<String> cves, ProductRule rule) {
        var parsed = advisory(source, id, severity, state, cves, List.of(rule));
        repo.upsertAdvisories(List.of(AdvisoryIngestService.toStored(core, source, "https://" + hostOf(source) + "/doc", "0".repeat(64), parsed).orElseThrow()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> vulns(String deviceId) {
        return service.vulnerabilities(devices.listAll().stream().map(AdvisoryReadService::facts).toList(), NOW).get(deviceId);
    }

    @Test void aSourceThatNeverLoadedMakesItsProductsNotEvaluatedNeverGreen() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        var v = vulns("d1");
        assertEquals("NOT_EVALUATED", v.get("state"));
        assertEquals("SOURCE_NEVER_LOADED", v.get("reason"));
        assertEquals(PRODUCT_PAN_OS, v.get("product_key"));
        assertEquals(0, v.get("affected"));
        assertNull(v.get("compared"));
        assertNull(v.get("worst_severity"));
    }

    @Test void aSourceOlderThan30DaysIsStaleAndStillSaysWhatItWas() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        store("paloalto", "CVE-2026-0001", Severity.HIGH, ParseState.PARSED, List.of(), below(PRODUCT_PAN_OS, "11.1", 5, null));
        repo.seedRun("paloalto", "OK", 31, NOW);
        var v = vulns("d1");
        assertEquals("NOT_EVALUATED", v.get("state"));
        assertEquals("SOURCE_STALE", v.get("reason"));
        assertEquals(0, v.get("affected"));
        @SuppressWarnings("unchecked") var compared = (Map<String, Object>) v.get("compared");
        assertEquals("paloalto", compared.get("source"));
        assertEquals(1, compared.get("advisories"));
        assertEquals(31L, compared.get("age_days"));
        repo.seedRun("paloalto", "OK", 30, NOW);
        assertEquals("EVALUATED", vulns("d1").get("state"), "30 days is the last good day; the limit is after 30");
    }

    @Test void aFailedRunDoesNotCountAsALoad() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        repo.seedRun("paloalto", "FAILED", 0, NOW);
        assertEquals("SOURCE_NEVER_LOADED", vulns("d1").get("reason"));
        repo.seedRun("paloalto", "PARTIAL", 2, NOW);
        assertEquals("EVALUATED", vulns("d1").get("state"));
    }

    @Test void evaluatedDevicesCountVerdictsExploitedAndWorstSeverity() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3"),
                device("d2", "palo_alto", "gateway", "FW-TANGO-05", "11.1.7")));
        repo.seedRun("paloalto", "OK", 1, NOW);
        store("paloalto", "CVE-2026-0001", Severity.CRITICAL, ParseState.PARSED, List.of("CVE-2026-0001"), below(PRODUCT_PAN_OS, "11.1", 5, "Upgrade to 11.1.5"));
        store("paloalto", "CVE-2026-0002", Severity.MEDIUM, ParseState.PARSED, List.of("CVE-2026-0002"), below(PRODUCT_PAN_OS, "11.1", 5, null));
        store("paloalto", "CVE-2026-0003", Severity.HIGH, ParseState.NEEDS_REVIEW, List.of(), below(PRODUCT_PAN_OS, "11.1", 5, null));
        repo.kev = List.of(new StoredKnownExploited("CVE-2026-0001", LocalDate.of(2026, 5, 4), false));
        var d1 = vulns("d1");
        assertEquals("EVALUATED", d1.get("state"));
        assertEquals(2, d1.get("affected"));
        assertEquals(1, d1.get("affected_exploited"));
        assertEquals(1, d1.get("affected_critical_high"));
        assertEquals(1, d1.get("not_evaluable"));
        assertEquals("CRITICAL", d1.get("worst_severity"));
        var d2 = vulns("d2");
        assertEquals(0, d2.get("affected"));
        assertEquals(2, d2.get("fixed"));
        assertNull(d2.get("worst_severity"));
        @SuppressWarnings("unchecked") var compared = (Map<String, Object>) d2.get("compared");
        assertEquals(3, compared.get("advisories"));
        assertEquals("2026-03-01", compared.get("oldest"));
        assertEquals(1L, compared.get("age_days"));
    }

    @Test void productsWithoutAComparatorAndUnusableVersionsAreSaidSo() {
        when(devices.listAll()).thenReturn(List.of(device("cp", "check_point", "gateway", "FW-TANGO-01", "R81.20"),
                device("nov", "palo_alto", "gateway", "FW-TANGO-02", null), device("bad", "palo_alto", "gateway", "FW-TANGO-03", "11.1.10-h7")));
        repo.seedRun("paloalto", "OK", 1, NOW);
        store("paloalto", "CVE-2026-0001", Severity.HIGH, ParseState.PARSED, List.of(), below(PRODUCT_PAN_OS, "11.1", 5, null));
        var all = service.vulnerabilities(devices.listAll().stream().map(AdvisoryReadService::facts).toList(), NOW);
        assertEquals("NOT_COVERED", all.get("cp").get("state"));
        assertEquals("NO_COMPARATOR", all.get("cp").get("reason"));
        assertNull(all.get("cp").get("product_key"));
        assertEquals("VERSION_UNKNOWN", all.get("nov").get("state"));
        assertEquals("VERSION_MISSING", all.get("nov").get("reason"));
        assertEquals("VERSION_UNPARSED", all.get("bad").get("reason"));
        // The two devices still count as not evaluable on the advisory that names their product.
        var row = service.detail("paloalto:CVE-2026-0001", NOW).orElseThrow();
        @SuppressWarnings("unchecked") var devs = (List<Map<String, Object>>) row.get("devices");
        assertEquals(2, devs.size());
        assertTrue(devs.stream().allMatch(d -> "NOT_EVALUABLE".equals(d.get("verdict"))));
    }

    @Test void theListHoldsOnlyAdvisoriesWithAVerdictOrAReviewNamingAnEstateProductSortedExploitedFirst() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        repo.seedRun("paloalto", "OK", 1, NOW);
        store("paloalto", "CVE-A", Severity.LOW, ParseState.PARSED, List.of("CVE-2026-0010"), below(PRODUCT_PAN_OS, "11.1", 5, null));
        store("paloalto", "CVE-B", Severity.CRITICAL, ParseState.PARSED, List.of("CVE-2026-0011"), below(PRODUCT_PAN_OS, "11.1", 5, null));
        store("paloalto", "CVE-C", Severity.HIGH, ParseState.PARSED, List.of("CVE-2026-0012"), below(PRODUCT_PAN_OS, "11.1", 5, null));
        store("paloalto", "CVE-D", Severity.HIGH, ParseState.PARSED, List.of(), below(PRODUCT_PAN_OS, "9.9", 5, null));
        store("paloalto", "CVE-E", Severity.HIGH, ParseState.NEEDS_REVIEW, List.of(), below(PRODUCT_PAN_OS, "9.9", 5, null));
        store("fortinet", "FG-IR-1", Severity.HIGH, ParseState.NEEDS_REVIEW, List.of(), below(PRODUCT_FORTIOS, "7.4", 5, null));
        repo.kev = List.of(new StoredKnownExploited("CVE-2026-0010", LocalDate.of(2026, 6, 1), true));
        @SuppressWarnings("unchecked") var list = (List<Map<String, Object>>) service.list(NOW).get("advisories");
        assertEquals(List.of("paloalto:CVE-A", "paloalto:CVE-B", "paloalto:CVE-C", "paloalto:CVE-E"),
                list.stream().map(a -> a.get("advisory_id")).toList(),
                "D names no train of the device (not listed); E is a review naming the estate's product; the Fortinet review names no estate product");
        assertEquals(true, list.getFirst().get("exploited"));
        assertEquals("2026-06-01", list.getFirst().get("exploited_since"));
        assertEquals(Map.of("affected", 1, "fixed", 0, "not_affected", 0, "not_evaluable", 0), list.getFirst().get("devices"));
        assertEquals(Map.of("affected", 0, "fixed", 0, "not_affected", 0, "not_evaluable", 1), list.getLast().get("devices"));
    }

    @Test void theDetailListsDevicesWithNameVendorVersionVerdictBasisAndFixText() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3"),
                device("d2", "palo_alto", "gateway", "FW-TANGO-05", "11.1.9")));
        repo.seedRun("paloalto", "OK", 1, NOW);
        store("paloalto", "CVE-A", Severity.HIGH, ParseState.PARSED, List.of(), below(PRODUCT_PAN_OS, "11.1", 5, "Upgrade to 11.1.5"));
        var detail = service.detail("paloalto:CVE-A", NOW).orElseThrow();
        @SuppressWarnings("unchecked") var devs = (List<Map<String, Object>>) detail.get("devices");
        assertEquals("AFFECTED", devs.get(0).get("verdict"));
        assertEquals("d1", devs.get(0).get("device_id"));
        assertEquals("FW-TANGO-04", devs.get(0).get("hostname"));
        assertEquals("PALOALTO", devs.get(0).get("vendor"));
        assertEquals("11.1.3", devs.get(0).get("version"));
        assertEquals("IN_RANGE", devs.get(0).get("basis"));
        assertEquals("Upgrade to 11.1.5", devs.get(0).get("fixed_text"));
        assertEquals("FIXED", devs.get(1).get("verdict"));
        assertFalse(devs.get(0).containsKey("name"), "the masking advice masks the key hostname next to device_id");
        assertTrue(service.detail("paloalto:UNKNOWN", NOW).isEmpty());
    }

    @Test void linksOffTheSourceHostListAreNotReturned() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        repo.seedRun("paloalto", "OK", 1, NOW);
        store("paloalto", "CVE-A", Severity.HIGH, ParseState.PARSED, List.of(), below(PRODUCT_PAN_OS, "11.1", 5, null));
        @SuppressWarnings("unchecked") var list = (List<Map<String, Object>>) service.list(NOW).get("advisories");
        assertEquals("https://security.paloaltonetworks.com/CVE-A", list.getFirst().get("source_url"));
        var stored = repo.advisories.get("paloalto:CVE-A");
        repo.advisories.put(stored.advisoryId(), new com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredAdvisory(
                stored.advisoryId(), stored.source(), stored.title(), stored.severity(), stored.cvssScore(), stored.cveIds(), stored.publishedOn(),
                stored.updatedOn(), "https://evil.example.test/x", stored.parseState(), null, stored.contentSha256(), null, stored.rules()));
        @SuppressWarnings("unchecked") var again = (List<Map<String, Object>>) service.list(NOW).get("advisories");
        assertNull(again.getFirst().get("source_url"));
    }

    @Test void aRuleTheCodecCannotReadBackIsTreatedAsNeedsReviewNotAsNoRule() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        repo.seedRun("paloalto", "OK", 1, NOW);
        var stored = AdvisoryIngestService.toStored(core, "paloalto", "https://security.paloaltonetworks.com/x", "0".repeat(64),
                advisory("paloalto", "CVE-A", Severity.HIGH, ParseState.PARSED, List.of(), List.of(below(PRODUCT_PAN_OS, "11.1", 5, null)))).orElseThrow();
        var broken = new com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredRule(PRODUCT_PAN_OS, "11.1", "{\"unknown\":true}", null);
        repo.advisories.put(stored.advisoryId(), new com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredAdvisory(
                stored.advisoryId(), stored.source(), stored.title(), stored.severity(), stored.cvssScore(), stored.cveIds(), stored.publishedOn(),
                stored.updatedOn(), stored.sourceUrl(), "PARSED", null, stored.contentSha256(), null, List.of(broken)));
        assertEquals(1, vulns("d1").get("not_evaluable"));
        assertEquals(0, vulns("d1").get("affected"));
    }

    @Test void sourcesListsAllFourWithAgeStates() {
        store("paloalto", "CVE-A", Severity.HIGH, ParseState.NEEDS_REVIEW, List.of(), below(PRODUCT_PAN_OS, "11.1", 5, null));
        repo.seedRun("paloalto", "OK", 8, NOW);
        repo.seedRun("fortinet", "PARTIAL", 31, NOW);
        repo.seedRun("cisco", "FAILED", 0, NOW);
        repo.kev = List.of(new StoredKnownExploited("CVE-2026-0010", LocalDate.of(2026, 6, 1), true),
                new StoredKnownExploited("CVE-2026-0011", LocalDate.of(2026, 8, 1), false));
        repo.seedRun("cisa_kev", "OK", 0, NOW);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) service.sources(NOW).get("sources");
        assertEquals(List.of("paloalto", "fortinet", "cisco", "cisa_kev"), rows.stream().map(r -> r.get("source")).toList());
        assertEquals("AMBER", rows.get(0).get("age_state"));
        assertEquals(8L, rows.get(0).get("age_days"));
        assertEquals(1, rows.get(0).get("needs_review"));
        assertEquals("OK", rows.get(0).get("outcome"));
        assertEquals("RED", rows.get(1).get("age_state"));
        assertEquals("NEVER", rows.get(2).get("age_state"), "a failed run is not a load");
        assertEquals("FAILED", rows.get(2).get("outcome"));
        assertNull(rows.get(2).get("age_days"));
        assertEquals("OK", rows.get(3).get("age_state"));
        assertEquals(2, rows.get(3).get("advisories"));
        assertEquals("2026-06-01", rows.get(3).get("oldest"));
    }

    @Test void fleetSummaryCountersFollowTheDeviceObjects() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3"),
                device("d2", "fortinet", "gateway", "FW-TANGO-05", "7.4.1"), device("d3", "check_point", "gateway", "FW-TANGO-06", "R81.20")));
        repo.seedRun("paloalto", "OK", 3, NOW);
        store("paloalto", "CVE-A", Severity.HIGH, ParseState.PARSED, List.of("CVE-2026-0010"), below(PRODUCT_PAN_OS, "11.1", 5, null));
        repo.kev = List.of(new StoredKnownExploited("CVE-2026-0010", LocalDate.of(2026, 6, 1), true));
        var byDevice = service.vulnerabilities(devices.listAll().stream().map(AdvisoryReadService::facts).toList(), NOW);
        var rows = new ArrayList<Map<String, Object>>();
        byDevice.forEach((id, v) -> { var row = new java.util.LinkedHashMap<String, Object>(); row.put("vulnerabilities", v); rows.add(row); });
        var summary = LifecycleService.summary(rows);
        assertEquals(1L, summary.get("vuln_devices_exploited"));
        assertEquals(1L, summary.get("vuln_devices_affected"));
        assertEquals(1L, summary.get("vuln_devices_not_evaluated"), "Fortinet never loaded");
        assertEquals(3L, summary.get("vuln_oldest_source_age_days"));
        assertNull(LifecycleService.summary(List.of()).get("vuln_oldest_source_age_days"));
    }

    @Test void anAbsentCoreGivesAnEmptyListAndNotCoveredDevices() {
        when(devices.listAll()).thenReturn(List.of(device("d1", "palo_alto", "gateway", "FW-TANGO-04", "11.1.3")));
        var bare = new AdvisoryReadService(repo, devices, () -> Optional.empty());
        assertEquals(List.of(), bare.list(NOW).get("advisories"));
        assertTrue(bare.detail("paloalto:x", NOW).isEmpty());
        assertEquals("NOT_COVERED", bare.vulnerabilities(List.of(AdvisoryReadService.facts(devices.listAll().getFirst())), NOW).get("d1").get("state"));
        assertEquals(4, ((List<?>) bare.sources(NOW).get("sources")).size());
    }

    @Test void oneHundredFiftyDevicesAgainstFourHundredAdvisoriesIsFastAndParsesEachRuleOnce() {
        List<DeviceSummaryRecord> fleet = new ArrayList<>();
        for (int i = 0; i < 150; i++) fleet.add(device("dev-" + i, "palo_alto", "gateway", "FW-TANGO-" + i, "11." + (i % 3) + "." + (i % 9)));
        when(devices.listAll()).thenReturn(fleet);
        repo.seedRun("paloalto", "OK", 1, NOW);
        for (int i = 0; i < 400; i++) {
            store("paloalto", "CVE-" + i, i % 2 == 0 ? Severity.HIGH : Severity.LOW, ParseState.PARSED, List.of(),
                    below(PRODUCT_PAN_OS, "11." + (i % 3), 5, null));
        }
        long start = System.nanoTime();
        var all = service.vulnerabilities(fleet.stream().map(AdvisoryReadService::facts).toList(), NOW);
        var list = service.list(NOW);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(150, all.size());
        assertEquals(400, ((List<?>) list.get("advisories")).size());
        assertTrue(millis < 3000, "took " + millis + " ms");
        assertEquals(800, core.fromJsonCalls, "400 advisories parsed once per request, two requests, never per device");
    }
}
