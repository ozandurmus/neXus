package com.securityexpert.nexus.ui2.worker.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.pan.PanoramaEnumerationResult;

/**
 * AGENTS.md "Sensitive identity reporting law" / AC-8: the run report
 * contains none of a fixture run's names, serials or addresses -- counts
 * and shapes only.
 */
class PanDiscoveryRunReportTest {

    @Test
    void reportContainsNoneOfTheFixtureRunsNamesSerialsOrAddresses() {
        FakePanDeviceTransport transport = new FakePanDeviceTransport(PanoramaEnumerationAdapterTest.happyPathHandler());
        PanoramaEnumerationAdapter adapter = new PanoramaEnumerationAdapter(transport,
                ref -> new com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial(
                        PanFixtures.FIXTURE_USERNAME, PanFixtures.FIXTURE_PASSWORD.toCharArray()),
                ref -> new com.securityexpert.nexus.ui2.worker.transport.xmlapi.TrustResolution.CaBundlePath("/fixture/ca-bundle.pem"));

        PanoramaEnumerationResult result = adapter.run(new PanoramaEnumerationRequest(
                "fixture-panorama-host", 4443, "fixture-credential-ref", "fixture-trust-rule-ref"));
        assertInstanceOf(PanoramaEnumerationResult.Completed.class, result);

        String report = PanDiscoveryRunReport.render(result);

        for (String sensitive : java.util.List.of("fixture-serial-1", "fixture-serial-2", "fixture-host-1",
                "fixture-host-2", "fixture-host-3", "198.51.100.10", "198.51.100.11",
                PanFixtures.FIXTURE_KEY, PanFixtures.FIXTURE_PASSWORD)) {
            assertFalse(report.contains(sensitive), "report leaked \"" + sensitive + "\": " + report);
        }
        assertTrue(report.contains("device_rows=3"));
        assertTrue(report.contains("request_count=2"));
    }

    @Test
    void refusedOutcomeRendersWithoutCandidateData() {
        String report = PanDiscoveryRunReport.render(new PanoramaEnumerationResult.Refused("fixture-generic-reason"));
        assertTrue(report.contains("outcome=REFUSED"));
    }
}
