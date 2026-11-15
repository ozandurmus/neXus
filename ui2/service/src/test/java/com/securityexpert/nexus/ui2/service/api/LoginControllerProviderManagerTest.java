package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.platform.Mechanism;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.MechanismAuthenticator;
import com.securityexpert.nexus.ui2.service.security.MechanismRegistry;

/**
 * Migration plan Step 2: {@code POST /login} answers identically whether it dispatches straight to the registry
 * ({@code ui2.security.provider-manager-enabled=false}) or through the {@code ProviderManager}: same status, same body,
 * same cookie, same throttle, for every outcome and every mechanism.
 */
class LoginControllerProviderManagerTest {

    private static Mechanism stub(String id, AttemptOutcome outcome) {
        return new Mechanism() {
            @Override
            public String mechanismId() {
                return id;
            }

            @Override
            public AttemptOutcome attempt(String identity, char[] credential) {
                return outcome;
            }
        };
    }

    private static MechanismRegistry registry() {
        LocalCredentialsRepository repository = mock(LocalCredentialsRepository.class, invocation ->
                invocation.getMethod().getReturnType() == java.util.Optional.class ? java.util.Optional.empty() : null);
        return new MechanismRegistry(new LocalMechanism(repository, Clock.system()), List.of(
                stub("ldap", AttemptOutcome.refused("bad_bind")),
                stub("ok", new AttemptOutcome.Success("abcdef012345")),
                stub("down", AttemptOutcome.mechanismUnavailable("directory_unavailable"))));
    }

    private static LoginFlow flow() {
        LoginFlow flow = mock(LoginFlow.class);
        when(flow.login(any(AttemptOutcome.Success.class), any())).thenReturn(new LoginFlow.LoginResult.NewSession("raw-cookie", null));
        return flow;
    }

    private record Seen(int status, Object body, List<String> cookies) {
    }

    private static Seen post(LoginController controller, String mechanism, String user) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.77");
        ResponseEntity<Map<String, Object>> entity = controller.login(
                new LoginController.LoginRequest(user, "synthetic-password".toCharArray(), mechanism), response, request);
        return new Seen(entity.getStatusCode().value(), entity.getBody(), new ArrayList<>(response.getHeaders("Set-Cookie")));
    }

    @Test
    void everyOutcomeAnswersTheSameThroughTheProviderManagerAsThroughDirectDispatch() {
        MechanismRegistry registry = registry();
        for (String mechanism : new String[] {"local", "", null, "ok", "ldap", "down", "nope"}) {
            LoginController direct = new LoginController(registry, flow(), SessionCookieWriter.secureByDefault());
            LoginController viaManager = new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                    new MechanismAuthenticator(registry));
            Seen expected = post(direct, mechanism, "someone");
            Seen actual = post(viaManager, mechanism, "someone");
            assertEquals(expected, actual, "mechanism " + mechanism);
        }
        assertEquals(200, post(new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                new MechanismAuthenticator(registry)), "ok", "x").status());
        assertEquals(401, post(new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                new MechanismAuthenticator(registry)), "ldap", "x").status());
        assertEquals(503, post(new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                new MechanismAuthenticator(registry)), "down", "x").status());
        assertEquals(400, post(new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                new MechanismAuthenticator(registry)), "nope", "x").status());
    }

    @Test
    void theDirectoryThrottleStillStopsTheSixthAttemptForOneIdentity() {
        MechanismRegistry registry = registry();
        LoginController viaManager = new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                new MechanismAuthenticator(registry));
        for (int i = 0; i < 5; i++) {
            assertEquals(401, post(viaManager, "ldap", "throttled.user").status());
        }
        Seen sixth = post(viaManager, "ldap", "throttled.user");
        assertEquals(429, sixth.status());
        assertNotNull(sixth.body());
    }

    @Test
    void theRefusalBodyNeverCarriesTheMechanismsInternalReason() {
        MechanismRegistry registry = registry();
        LoginController viaManager = new LoginController(registry, flow(), SessionCookieWriter.secureByDefault(),
                new MechanismAuthenticator(registry));
        assertEquals(Map.of("error", "INVALID_CREDENTIALS"), post(viaManager, "ldap", "other.user").body());
        assertEquals(Map.of("error", "INVALID_CREDENTIALS"), post(viaManager, "local", "nobody").body());
    }
}
