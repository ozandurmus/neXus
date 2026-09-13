package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * 13G {@code LIA-3.2}/section 3, {@code AC-2}'s decisive test: no response
 * body over a full create-and-list cycle contains the plaintext password
 * used to create the identity, or any string this movement associates with
 * a verifier/salt. Observed failing before {@code toBody} existed as an
 * explicit allowlist (a naive {@code Map.of("view", view)} would have
 * serialized every record component -- there was none until this
 * controller was written this way from the start).
 */
class LocalIdentityAdministrationControllerTest {

    private static final String PLAINTEXT_PASSWORD = "correct-horse-battery-staple-13G";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** A minimal in-memory port: enough to prove the controller's own response shape, not the domain logic (covered by LocalIdentityAdministrationTest). */
    private static final class FakePort implements LocalIdentityAdministrationPort {
        private final List<LocalIdentityView> views = new ArrayList<>();
        String lastSeenPassword;

        @Override
        public LocalIdentityView create(String actingAdminActorFingerprint, String localIdentityName,
                char[] initialPassword) {
            lastSeenPassword = new String(initialPassword);
            LocalIdentityView view = new LocalIdentityView("id-1", localIdentityName, true, true, Instant.now(),
                    Instant.now());
            views.add(view);
            return view;
        }

        @Override
        public List<LocalIdentityView> list() {
            return List.copyOf(views);
        }

        @Override
        public MutationResult setPassword(String actingAdminActorFingerprint, String localIdentityId,
                char[] newPassword) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public MutationResult disable(String actingAdminActorFingerprint, String localIdentityId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public MutationResult enable(String actingAdminActorFingerprint, String localIdentityId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static HttpServletRequest fakeRequest() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE)).thenReturn("admin1");
        return request;
    }

    @Test
    void createAndListResponseBodiesNeverContainThePasswordOrAnyVerifierMaterial() throws Exception {
        FakePort port = new FakePort();
        LocalIdentityAdministrationController controller = new LocalIdentityAdministrationController(port);
        HttpServletRequest request = fakeRequest();

        var createResponse = controller.create(
                new LocalIdentityAdministrationController.CreateRequest("alice", PLAINTEXT_PASSWORD.toCharArray()),
                request);
        var listResponse = controller.list();

        String createJson = OBJECT_MAPPER.writeValueAsString(createResponse.getBody());
        String listJson = OBJECT_MAPPER.writeValueAsString(listResponse.getBody());

        assertFalse(createJson.contains(PLAINTEXT_PASSWORD), "create response must never echo the password: " + createJson);
        assertFalse(listJson.contains(PLAINTEXT_PASSWORD), "list response must never echo the password: " + listJson);
        for (String forbidden : List.of("verifier", "salt", "algorithm_id", "memory_cost_kib")) {
            assertFalse(createJson.toLowerCase().contains(forbidden), createJson);
            assertFalse(listJson.toLowerCase().contains(forbidden), listJson);
        }

        // The exact allowlisted shape (13G section 3) -- nothing more, nothing less.
        @SuppressWarnings("unchecked")
        Map<String, Object> createBody = (Map<String, Object>) createResponse.getBody();
        assertEquals(Set.of("local_identity_id", "local_identity_name", "enabled", "must_change_password",
                "created_at", "password_set_at"), createBody.keySet());
    }

    @Test
    void thePasswordArrayPassedToTheControllerIsZeroedAfterTheCallReturns() {
        FakePort port = new FakePort();
        LocalIdentityAdministrationController controller = new LocalIdentityAdministrationController(port);
        char[] password = PLAINTEXT_PASSWORD.toCharArray();

        controller.create(new LocalIdentityAdministrationController.CreateRequest("alice", password), fakeRequest());

        assertTrue(new String(password).chars().allMatch(c -> c == '\0'), "password array must be zeroed after use");
    }
}
