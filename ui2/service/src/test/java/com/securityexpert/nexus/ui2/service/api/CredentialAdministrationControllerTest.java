package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialView;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * AC-1 THE DECISIVE TEST: no response body over a full create,
 * replace-secret and list cycle contains the plaintext secret used, or any
 * string associated with the encrypted columns. Mirrors
 * {@code LocalIdentityAdministrationControllerTest} exactly.
 */
class CredentialAdministrationControllerTest {

    private static final String FIXTURE_SECRET = "correct-horse-battery-staple-CS-1-fixture";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** A minimal in-memory port: enough to prove the controller's own response shape, not the domain logic (covered by CredentialAdministrationTest). */
    private static final class FakePort implements CredentialStorePort {
        private final List<CredentialView> views = new ArrayList<>();
        String lastSeenSecret;
        String lastSeenPassphrase;
        boolean rejectPassphrase;

        @Override
        public CredentialView create(String actingAdminActorFingerprint, String displayName, CredentialKind kind,
                String username, boolean allowsCheckPoint, boolean allowsPaloAlto, char[] secret,
                Optional<char[]> passphrase) {
            lastSeenSecret = new String(secret);
            passphrase.ifPresent(p -> lastSeenPassphrase = new String(p));
            CredentialView view = new CredentialView("cred-1", "ref-1", displayName, kind, username,
                    allowsCheckPoint, allowsPaloAlto, Instant.now(), Instant.now());
            views.clear();
            views.add(view);
            return view;
        }

        @Override
        public List<CredentialView> list() {
            return List.copyOf(views);
        }

        @Override
        public ReplaceSecretResult replaceSecret(String actingAdminActorFingerprint, String credentialId,
                char[] secret, Optional<char[]> passphrase) {
            if (rejectPassphrase) {
                return new ReplaceSecretResult.PassphraseNotAllowed();
            }
            lastSeenSecret = new String(secret);
            passphrase.ifPresent(p -> lastSeenPassphrase = new String(p));
            CredentialView existing = views.get(0);
            CredentialView updated = new CredentialView(existing.credentialId(), existing.credentialReferenceId(),
                    existing.displayName(), existing.kind(), existing.username(), existing.allowsCheckPoint(),
                    existing.allowsPaloAlto(), existing.createdAt(), Instant.now());
            views.set(0, updated);
            return new ReplaceSecretResult.Ok(updated);
        }

        @Override
        public DeleteResult delete(String actingAdminActorFingerprint, String credentialId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static HttpServletRequest fakeRequest() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE)).thenReturn("admin1");
        return request;
    }

    @Test
    void createReplaceAndListResponseBodiesNeverContainTheSecretOrAnyEncryptedColumnName() throws Exception {
        FakePort port = new FakePort();
        CredentialAdministrationController controller = new CredentialAdministrationController(port);
        HttpServletRequest request = fakeRequest();

        var createResponse = controller.create(
                new CredentialAdministrationController.CreateRequest("edge-fw-1", "ssh_password", "svc-account",
                        true, false, FIXTURE_SECRET.toCharArray(), null),
                request);
        var replaceResponse = controller.replaceSecret(
                new CredentialAdministrationController.ReplaceSecretRequest("cred-1",
                        "a-second-fixture-secret".toCharArray(), null),
                request);
        var listResponse = controller.list();

        String createJson = OBJECT_MAPPER.writeValueAsString(createResponse.getBody());
        String replaceJson = OBJECT_MAPPER.writeValueAsString(replaceResponse.getBody());
        String listJson = OBJECT_MAPPER.writeValueAsString(listResponse.getBody());

        for (String json : List.of(createJson, replaceJson, listJson)) {
            assertFalse(json.contains(FIXTURE_SECRET), "response must never echo the secret: " + json);
            assertFalse(json.contains("a-second-fixture-secret"), "response must never echo the secret: " + json);
            for (String forbidden : List.of("encrypted_secret", "encrypted_passphrase", "envelope_key_id")) {
                assertFalse(json.toLowerCase().contains(forbidden), json);
            }
        }

        // The exact allowlisted shape (CS-1/CS-3) -- nothing more, nothing less.
        @SuppressWarnings("unchecked")
        Map<String, Object> createBody = (Map<String, Object>) createResponse.getBody();
        assertEquals(Set.of("credential_id", "credential_reference_id", "display_name", "kind", "username",
                "allows_check_point", "allows_palo_alto", "created_at", "secret_set_at"), createBody.keySet());
    }

    @Test
    void theSecretAndPassphraseArraysPassedToTheControllerAreZeroedAfterTheCallReturns() {
        FakePort port = new FakePort();
        CredentialAdministrationController controller = new CredentialAdministrationController(port);
        char[] secret = FIXTURE_SECRET.toCharArray();
        char[] passphrase = "a-fixture-passphrase".toCharArray();

        controller.create(new CredentialAdministrationController.CreateRequest("edge-fw-1", "ssh_private_key",
                "svc-account", true, true, secret, passphrase), fakeRequest());

        assertTrue(new String(secret).chars().allMatch(c -> c == '\0'), "secret array must be zeroed after use");
        assertTrue(new String(passphrase).chars().allMatch(c -> c == '\0'), "passphrase array must be zeroed after use");
        assertEquals(FIXTURE_SECRET, port.lastSeenSecret, "the domain layer must still receive the real secret");
        assertEquals("a-fixture-passphrase", port.lastSeenPassphrase);
    }

    @Test
    void anUnknownKindIsRejectedBeforeReachingTheDomainLayer() {
        FakePort port = new FakePort();
        CredentialAdministrationController controller = new CredentialAdministrationController(port);

        var response = controller.create(new CredentialAdministrationController.CreateRequest("edge-fw-1",
                "not_a_real_kind", "svc-account", true, false, "irrelevant".toCharArray(), null), fakeRequest());

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void passphraseNotAllowedIsReportedAsStableBadRequest() {
        FakePort port = new FakePort();
        port.rejectPassphrase = true;
        CredentialAdministrationController controller = new CredentialAdministrationController(port);

        var response = controller.replaceSecret(new CredentialAdministrationController.ReplaceSecretRequest(
                "cred-1", "replacement-secret".toCharArray(), "not-allowed".toCharArray()), fakeRequest());

        assertEquals(400, response.getStatusCode().value());
        assertEquals("PASSPHRASE_NOT_ALLOWED_FOR_CREDENTIAL_KIND", response.getBody().get("error"));
    }
}
