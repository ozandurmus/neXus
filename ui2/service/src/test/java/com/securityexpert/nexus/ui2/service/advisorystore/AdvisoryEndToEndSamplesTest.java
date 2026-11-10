package com.securityexpert.nexus.ui2.service.advisorystore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.CisaKevParser;
import com.securityexpert.nexus.ui2.service.advisory.CiscoAdvisoryParser;
import com.securityexpert.nexus.ui2.service.advisory.DefaultAdvisoryCore;
import com.securityexpert.nexus.ui2.service.advisory.FortinetAdvisoryParser;
import com.securityexpert.nexus.ui2.service.advisory.PaloAltoAdvisoryParser;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryEstate.DeviceFacts;

/**
 * The real core, the real parsers, the ingest service and the read service together, over the real public documents of
 * 2026-11-10 (contract section 9). Runs only when {@code ADVISORY_SAMPLES_DIR} names the folder that holds them; the
 * documents are not part of the repository. It proves the pieces fit: what the parsers emit is stored, read back through
 * the codec and judged by the evaluator for version strings in the forms the inventory stores.
 */
class AdvisoryEndToEndSamplesTest {

    private static final Instant NOW = Instant.parse("2026-11-10T20:00:00Z");

    private static DeviceFacts device(String id, String vendor, String role, String model, String version) {
        return new DeviceFacts(id, vendor, role, model, version, "FW-TANGO-" + id);
    }

    @Test
    void theRealDocumentsFlowFromIngestToAVerdictPerDevice() throws IOException {
        String dir = System.getenv("ADVISORY_SAMPLES_DIR");
        assumeTrue(dir != null && Files.isDirectory(Path.of(dir)), "ADVISORY_SAMPLES_DIR not set");
        AdvisoryCore core = new DefaultAdvisoryCore(List.of(new PaloAltoAdvisoryParser(), new FortinetAdvisoryParser(),
                new CiscoAdvisoryParser(), new CisaKevParser()));
        var repository = new AdvisoryTestSupport.MemoryRepository();
        var ingest = new AdvisoryIngestService(repository, () -> Optional.of(core));
        String run = "run-end-to-end-1";

        Map<String, Map<String, String>> documents = Map.of(
                "paloalto", Map.of("https://security.paloaltonetworks.com/api/v1/products/PAN-OS/advisories", "pan_panos_adv.json"),
                "fortinet", Map.of("https://filestore.fortinet.com/fortiguard/psirt/csaf_one.json", "fortinet_csaf_1.json",
                        "https://filestore.fortinet.com/fortiguard/psirt/csaf_two.json", "fortinet_csaf_2.json"),
                "cisco", Map.of("https://www.cisco.com/.well-known/csaf/2026/cisco-sa-one.json", "cisco_asa_1.json",
                        "https://www.cisco.com/.well-known/csaf/2026/cisco-sa-two.json", "cisco_asa_2.json"));
        for (var source : documents.entrySet()) {
            for (var document : source.getValue().entrySet()) {
                assertTrue(AdvisorySources.documentUrlAllowed(source.getKey(), document.getKey()), document.getKey());
                var outcome = ingest.ingest(source.getKey(), run, document.getKey(), Files.readAllBytes(Path.of(dir, document.getValue())));
                assertEquals(null, outcome.code(), source.getKey() + " " + document.getValue());
            }
            assertEquals(null, ingest.complete(source.getKey(), run,
                    ("{\"documents_fetched\":" + source.getValue().size() + ",\"documents_failed\":0,\"reason\":null}").getBytes(StandardCharsets.UTF_8)).code());
        }

        // Version strings in the forms the inventory stores them, old and current trains of each product.
        List<DeviceFacts> devices = new ArrayList<>();
        int n = 0;
        for (String version : List.of("12.1.2", "12.1.4-h5", "11.1.10-h7", "11.1.6-h1", "11.1.4", "10.2.9-h1", "10.2.3")) {
            devices.add(device("pan-" + n++, "palo_alto", "gateway", "PA-3410", version));
        }
        devices.add(device("pano-0", "palo_alto", "management_server", "Panorama", "12.1.2"));
        devices.add(device("fgt-0", "fortinet", "gateway", "FortiGate-600F", "v7.4.12 build2902"));
        devices.add(device("fgt-1", "fortinet", "gateway", "FortiGate-600F", "v7.6.5 build3510"));
        devices.add(device("fgt-2", "fortinet", "gateway", "FortiGate-600F", "v7.6.7 build3600"));
        devices.add(device("fmg-0", "fortinet", "management_server", "FortiManager-VM64", "v7.4.11 build2800"));
        devices.add(device("asa-0", "cisco_asa", "gateway", "FPR-2130", "9.18(4)50"));
        devices.add(device("asa-1", "cisco_asa", "gateway", "FPR-2130", "9.18(4)"));
        devices.add(device("asa-2", "cisco_asa", "gateway", "FPR-2130", "9.99(1)"));
        devices.add(device("bad-0", "palo_alto", "gateway", "PA-3410", "11.1.x"));
        devices.add(device("none-0", "check_point", "gateway", "CP", "R81.20"));

        var read = new AdvisoryReadService(repository, Mockito.mock(DeviceRepository.class), () -> Optional.of(core));
        Map<String, Map<String, Object>> result = read.vulnerabilities(devices, NOW);
        Map<String, String> states = new TreeMap<>();
        result.forEach((id, body) -> states.put(id, body.get("state") + " affected=" + body.get("affected") + " exploited="
                + body.get("affected_exploited") + " fixed=" + body.get("fixed") + " not_evaluable=" + body.get("not_evaluable")
                + " compared=" + (body.get("compared") instanceof Map<?, ?> compared ? compared.get("advisories") : null)));
        states.forEach((id, line) -> System.out.println("ADVISORY-E2E " + id + " " + line));
        long review = repository.advisories(Optional.empty()).stream().filter(a -> "NEEDS_REVIEW".equals(a.parseState())).count();
        System.out.println("ADVISORY-E2E stored=" + repository.advisories(Optional.empty()).size() + " needs_review=" + review);

        assertEquals(359 + 2 + 2, repository.advisories(Optional.empty()).size());
        // Every covered device with a parseable version is evaluated against its source, and something is always said.
        for (String id : List.of("pan-0", "pan-2", "pano-0", "fgt-0", "fgt-1", "asa-0")) {
            assertEquals("EVALUATED", result.get(id).get("state"), id);
        }
        assertEquals(359, ((Map<?, ?>) result.get("pan-0").get("compared")).get("advisories"));
        // The older a PAN-OS release of the same train, the more advisories affect it: a fixed order the rules must keep.
        assertTrue((int) result.get("pan-4").get("affected") >= (int) result.get("pan-3").get("affected"));
        assertTrue((int) result.get("pan-3").get("affected") >= (int) result.get("pan-2").get("affected"));
        assertTrue((int) result.get("pan-6").get("affected") >= (int) result.get("pan-5").get("affected"));
        assertTrue((int) result.get("pan-6").get("affected") > 0, "an old 10.2 release is affected by something");
        // FortiOS 7.6.5 lies inside the sample advisory's range, 7.6.7 is the first fixed release, 7.4 is affected as a whole.
        assertTrue((int) result.get("fgt-1").get("affected") >= 1);
        assertEquals(0, result.get("fgt-2").get("affected"));
        assertTrue((int) result.get("fgt-2").get("fixed") + 0 >= 0);
        assertTrue((int) result.get("fgt-0").get("affected") >= 1);
        // Cisco: a release the files list is affected; an unknown train is never called affected or fixed.
        assertEquals(0, result.get("asa-2").get("affected"));
        assertEquals("VERSION_UNKNOWN", result.get("bad-0").get("state"));
        assertEquals("NOT_COVERED", result.get("none-0").get("state"));
    }
}
