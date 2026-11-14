package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleLedger;
import com.securityexpert.nexus.ui2.service.failover.FailoverScheduleService;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.MechanismRegistry;
import com.securityexpert.nexus.ui2.service.security.PasswordChangeService;

/**
 * Follow-up to the empty-object fix on POST /discovery/runs: a body of {@code {}} leaves every field null, and
 * these endpoints used to dereference one (a 500). Each answers with its existing refusal shape instead.
 */
class EmptyObjectBodySweepTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static <T> T empty(Class<T> type) throws Exception {
        return JSON.readValue("{}", type);
    }

    @Test
    void cancelScheduleWithEmptyObjectIsBadRequest() throws Exception {
        var service = mock(FailoverScheduleService.class);
        var controller = new FailoverScheduleController(service, mock(FailoverScheduleLedger.class));
        ResponseEntity<?> response = controller.cancelSchedule("synthetic-schedule",
                empty(FailoverScheduleController.CancelSchedulePayload.class));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("INVALID_REQUEST", ((Map<?, ?>) response.getBody()).get("error"));
        verifyNoInteractions(service);
    }

    @Test
    void loginWithEmptyObjectIsTheGenericRefusal() throws Exception {
        var registry = mock(MechanismRegistry.class);
        var controller = new LoginController(registry, mock(LoginFlow.class));
        var response = controller.login(empty(LoginController.LoginRequest.class), new MockHttpServletResponse(),
                new MockHttpServletRequest());
        assertEquals(401, response.getStatusCode().value());
        assertEquals("INVALID_CREDENTIALS", response.getBody().get("error"));
        verifyNoInteractions(registry);
    }

    @Test
    void loginResolveWithEmptyObjectIsRefusedLikeAnUnknownToken() throws Exception {
        var flow = new LoginFlow(mock(SessionRepository.class), Duration.ofMinutes(30), Duration.ofHours(10));
        var controller = new LoginResolveController(flow, new SessionCookieWriter(true));
        var response = controller.resolve(empty(LoginResolveController.ResolveRequest.class),
                new MockHttpServletResponse());
        assertEquals(409, response.getStatusCode().value());
        assertEquals("conflict_token_expired_or_unknown", response.getBody().get("reason_code"));
    }

    @Test
    void changePasswordWithEmptyObjectIsInvalidCredentials() throws Exception {
        var controller = new PasswordChangeController(new PasswordChangeService(mock(LocalCredentialsRepository.class)));
        var response = controller.changePassword(empty(PasswordChangeController.ChangePasswordRequest.class));
        assertEquals(401, response.getStatusCode().value());
        assertEquals("INVALID_CREDENTIALS", response.getBody().get("error"));
    }

    @Test
    void createLocalIdentityWithEmptyObjectIsValidationFailed() throws Exception {
        var port = mock(LocalIdentityAdministrationPort.class);
        var controller = new LocalIdentityAdministrationController(port);
        var response = controller.create(empty(LocalIdentityAdministrationController.CreateRequest.class),
                new MockHttpServletRequest());
        assertEquals(422, response.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        verifyNoInteractions(port);
    }

    @Test
    void setLocalIdentityPasswordWithEmptyObjectIsValidationFailed() throws Exception {
        var port = mock(LocalIdentityAdministrationPort.class);
        var controller = new LocalIdentityAdministrationController(port);
        var response = controller.setPassword(empty(LocalIdentityAdministrationController.SetPasswordRequest.class),
                new MockHttpServletRequest());
        assertEquals(422, response.getStatusCode().value());
        assertEquals("VALIDATION_FAILED", response.getBody().get("error"));
        verifyNoInteractions(port);
    }
}
