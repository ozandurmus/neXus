package com.securityexpert.nexus.ui2.service.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.service.api.DiscoveryController;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class ManagementEndpointSshTrustDiscoveryTest {
    private static class Store implements ManagementEndpointSshTrustRepository {
        boolean active;
        boolean fail;
        int writes;
        String actor;
        public Optional<String> findActiveFingerprint(String a, int p, String k) { return Optional.empty(); }
        public List<String> findActiveAlgorithms(String a, int p) { return List.of(); }
        public boolean enroll(String a, int p, String k, String f, String by, Instant at, boolean re) {
            if (fail) throw new IllegalStateException("raw-sensitive-exception");
            if (active != re) return false;
            active = true; writes++; actor = by; return true;
        }
    }
    @Test
    void attestationAndExplicitRotationAreRequiredAndApiNeverDisclosesInputsOrErrors() {
        Store store = new Store();
        var service = new ManagementEndpointSshTrustService(store);
        assertEquals(ManagementEndpointSshTrustService.Outcome.NOT_EVALUABLE,
                service.enroll("fixture-actor", "fixture-management", 22, "ssh-ed25519", "0".repeat(64), Instant.EPOCH, false, false));
        assertEquals(0, store.writes);
        assertEquals(ManagementEndpointSshTrustService.Outcome.NOT_EVALUABLE,
                service.enroll("fixture-actor", "fixture-management", 0, "ssh-ed25519", "0".repeat(64), Instant.EPOCH, true, false));
        var controller = new DiscoveryController(null, service);
        var request = new DiscoveryController.TrustRequest("fixture-management", 22, "ssh-ed25519", "0".repeat(64), Instant.EPOCH, true);
        var servlet = new MockHttpServletRequest();
        servlet.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fixture-actor");
        assertEquals(java.util.Map.of("relationship", "MATCH"), controller.enrollTrust(request, servlet).getBody());
        assertEquals("fixture-actor", store.actor);
        assertEquals(java.util.Map.of("relationship", "MISMATCH"), controller.enrollTrust(request, servlet).getBody());
        assertEquals(1, store.writes);
        assertEquals(java.util.Map.of("relationship", "MATCH"), controller.reEnrollTrust(request, servlet).getBody());
        assertEquals(2, store.writes);
        store.fail = true;
        assertEquals(java.util.Map.of("relationship", "NOT_EVALUABLE"), controller.reEnrollTrust(request, servlet).getBody());
    }
}
