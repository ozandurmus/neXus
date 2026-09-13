package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;

/**
 * AGENTS.md "Sensitive identity reporting law" / AC-8: the run report
 * contains none of a fixture run's names, addresses, domains or
 * identifiers -- counts and shapes only.
 */
class DiscoveryRunReportTest {

    @Test
    void reportContainsNoneOfTheFixtureRunsNamesAddressesDomainsOrIdentifiers() {
        FakeDeviceTransport transport = new FakeDeviceTransport(ManagementPlaneEnumerationAdapterTest.happyPathHandler());
        SshCredentialResolver resolver = ref -> new SshCredentialMaterial("fixture-user", "x".toCharArray(), null);
        ManagementPlaneEnumerationAdapter adapter = new ManagementPlaneEnumerationAdapter(transport, resolver, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(new ManagementPlaneEnumerationRequest(
                "fixture-management-host", 2222, "fixture-credential-ref", "fixture-trust-rule-ref",
                Duration.ZERO, Optional.empty()));
        assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, result);

        String report = DiscoveryRunReport.render(result);

        for (String sensitive : java.util.List.of(
                WorkerFixtures.DOMAIN_A_UID, WorkerFixtures.DOMAIN_B_UID,
                WorkerFixtures.DOMAIN_A_GATEWAY_MGMT_ADDRESS, WorkerFixtures.DOMAIN_A_MEMBER_MGMT_ADDRESS,
                WorkerFixtures.DOMAIN_B_GATEWAY_MGMT_ADDRESS, WorkerFixtures.DOMAIN_B_MEMBER_MGMT_ADDRESS,
                "fixture-gw-a-1", "fixture-gw-b-1", "fixture-cluster-a-1", "fixture-cluster-b-1",
                "fixture-member-a-1", "fixture-member-b-1", "gw-a-1", "gw-b-1", "cl-a-1", "cl-b-1",
                "mem-a-1", "mem-b-1", WorkerFixtures.TOP_SESSION_ID,
                WorkerFixtures.DOMAIN_A_SESSION_ID, WorkerFixtures.DOMAIN_B_SESSION_ID)) {
            assertFalse(report.contains(sensitive), "report leaked \"" + sensitive + "\": " + report);
        }
        assertTrue(report.contains("candidates_total=6"));
        assertTrue(report.contains("management_plane_request_count=8"));
    }

    @Test
    void refusedOutcomeRendersWithoutCandidateData() {
        String report = DiscoveryRunReport.render(new ManagementPlaneEnumerationResult.Refused("fixture-generic-reason"));
        assertTrue(report.contains("outcome=REFUSED"));
    }
}
