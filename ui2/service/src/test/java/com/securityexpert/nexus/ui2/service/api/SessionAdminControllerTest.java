package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.service.audit.AuditPresentationAllowlist;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;

@WebMvcTest(SessionAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = SessionAdminController.class)
class SessionAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SessionRepository sessionRepository;

    @MockBean
    private LocalIdentityResolver localIdentityResolver;

    @Test
    void applicationContextServesSessionAdminRoutes() throws Exception {
        ComponentScan scan = Ui2Application.class.getAnnotation(ComponentScan.class);
        if (scan != null) {
            for (ComponentScan.Filter filter : scan.excludeFilters()) {
                for (String pattern : filter.pattern()) {
                    assertFalse(SessionAdminController.class.getName().matches(pattern));
                }
            }
        }
        when(sessionRepository.findActive(any())).thenReturn(List.of());

        mvc.perform(get("/sessions")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-fixture"))
                .andExpect(status().isOk());
        mvc.perform(post("/sessions/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-1\"}")
                        .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-fixture"))
                .andExpect(status().isOk());

        verify(sessionRepository).revoke("session-1", "actor-fixture", ActionRegistry.SESSION_REVOKE);
    }

    @Test
    void listReturnsExactlyTheAllowlistedSessionColumns() {
        SessionRepository sessions = mock(SessionRepository.class);
        LocalCredentialsRepository identities = mock(LocalCredentialsRepository.class);
        when(identities.findAll()).thenReturn(List.of());
        when(sessions.findActive(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(activeSession("session-1")));

        Map<String, Object> response = new SessionAdminController(sessions, new LocalIdentityResolver(identities))
                .list().getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("sessions");
        assertEquals(AuditPresentationAllowlist.columnsOf("sessions"), rows.get(0).keySet());
    }

    private static SessionRecord activeSession(String id) {
        Instant now = Instant.parse("2026-09-21T08:00:00Z");
        return new SessionRecord(id, "actor-fixture", "csrf-fixture", SessionState.ACTIVE,
                now, now, now.plusSeconds(1800), now.plusSeconds(36000),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
