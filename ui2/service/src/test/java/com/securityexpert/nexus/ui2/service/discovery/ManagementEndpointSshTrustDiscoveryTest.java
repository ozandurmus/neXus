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
        var observer = new ManagementEndpointSshTrustService.HostKeyObserver() {
            public Optional<ManagementEndpointSshTrustService.ObservedHostKey> observe(String a, int p) {
                if (p == 0) return Optional.empty();
                return Optional.of(new ManagementEndpointSshTrustService.ObservedHostKey("ssh-ed25519", "0".repeat(64), Instant.EPOCH));
            }
        };
        var service = new ManagementEndpointSshTrustService(store, observer, () -> Instant.EPOCH.plusSeconds(10));
        assertEquals(Optional.empty(), service.observe("fixture-actor", "fixture-session", "fixture-management", 0));
        
        Optional<ManagementEndpointSshTrustService.Observation> obs = service.observe("fixture-actor", "fixture-session", "fixture-management", 22);
        assertTrue(obs.isPresent());
        String observationId = obs.get().observationId();
        
        assertEquals(ManagementEndpointSshTrustService.Outcome.NOT_EVALUABLE,
                service.authorizeObserved("fixture-actor", "fixture-session", observationId, false, false));
        assertEquals(0, store.writes);
        
        var controller = new DiscoveryController(null, service);
        var request = new DiscoveryController.TrustRequest(observationId, true);
        var servlet = new MockHttpServletRequest();
        servlet.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "fixture-actor");
        servlet.setAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE, "fixture-session");
        
        assertEquals(java.util.Map.of("relationship", "MATCH"), controller.enrollTrust(request, servlet).getBody());
        assertEquals("fixture-actor", store.actor);
        
        obs = service.observe("fixture-actor", "fixture-session", "fixture-management", 22);
        request = new DiscoveryController.TrustRequest(obs.get().observationId(), true);
        assertEquals(java.util.Map.of("relationship", "MISMATCH"), controller.enrollTrust(request, servlet).getBody());
        assertEquals(1, store.writes);
        
        obs = service.observe("fixture-actor", "fixture-session", "fixture-management", 22);
        request = new DiscoveryController.TrustRequest(obs.get().observationId(), true);
        assertEquals(java.util.Map.of("relationship", "MATCH"), controller.reEnrollTrust(request, servlet).getBody());
        assertEquals(2, store.writes);
        
        obs = service.observe("fixture-actor", "fixture-session", "fixture-management", 22);
        store.fail = true;
        request = new DiscoveryController.TrustRequest(obs.get().observationId(), true);
        assertEquals(java.util.Map.of("relationship", "NOT_EVALUABLE"), controller.reEnrollTrust(request, servlet).getBody());
    }
}
