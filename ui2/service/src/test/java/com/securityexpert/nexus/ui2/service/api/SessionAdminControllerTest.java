package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.service.audit.AuditPresentationAllowlist;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;

class SessionAdminControllerTest {

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
