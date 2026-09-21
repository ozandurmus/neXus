package com.securityexpert.nexus.ui2.service.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointPanCertTrustRepository;
import com.securityexpert.nexus.ui2.service.api.DiscoveryController;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class ManagementEndpointPanCertTrustDiscoveryTest {
    private static final class Store implements ManagementEndpointPanCertTrustRepository {
        boolean active;
        int writes;

        @Override
        public Optional<String> findActiveFingerprint(String address, int port) {
            return Optional.empty();
        }

        @Override
        public boolean enroll(String address, int port, String fingerprint, String actorFingerprint,
                Instant observedAt, boolean reEnroll) {
            if (active != reEnroll) return false;
            active = true;
            writes++;
            return true;
        }
    }

    @Test
    void explicitConfirmationAndExplicitRotationAreRequired() {
        Store store = new Store();
        var service = new ManagementEndpointPanCertTrustService(store);
        assertEquals(ManagementEndpointPanCertTrustService.Outcome.NOT_EVALUABLE,
                service.enroll("fixture-actor", "192.0.2.10", 443, "0".repeat(64), Instant.EPOCH, false, false));
        var controller = new DiscoveryController(null, null, service);
        var request = new DiscoveryController.PanTrustRequest(
                "192.0.2.10", 443, "0".repeat(64), Instant.EPOCH, true);
        var servlet = new MockHttpServletRequest();
        servlet.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fixture-actor");

        assertEquals(java.util.Map.of("relationship", "MATCH"), controller.enrollPanTrust(request, servlet).getBody());
        assertEquals(java.util.Map.of("relationship", "MISMATCH"), controller.enrollPanTrust(request, servlet).getBody());
        assertEquals(java.util.Map.of("relationship", "MATCH"), controller.reEnrollPanTrust(request, servlet).getBody());
        assertEquals(2, store.writes);
    }
}
