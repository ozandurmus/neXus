package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;

class MachineGateTest {
    @Test
    void machineWritesAreRefusedBeforeCsrfAndControllersWhileHumanSessionIsUnchanged() {
        String cookie = "synthetic-cookie";
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        SessionRepository sessions = mock(SessionRepository.class);
        GateChain gate = new GateChain(sessions, null, null, null);
        SessionRecord machine = new SessionRecord(SessionHasher.hash(cookie), "synthetic-actor", "synthetic-csrf",
                SessionState.ACTIVE, now.minusSeconds(10), now.minusSeconds(10), now.plusSeconds(600),
                now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), true);
        when(sessions.findBySessionId(anyString(), eq(now))).thenReturn(Optional.of(machine));
        var request = new GateRequest("POST", Optional.of(cookie), Optional.empty(), Optional.empty(),
                "synthetic-action", Optional.empty());
        var refused = (GateOutcome.Refused) gate.evaluate(request, now);
        assertEquals(403, refused.httpStatus());
        assertEquals("MACHINE_SESSION_READ_ONLY", refused.body().get("error"));
        assertTrue(gate.machineWriteRefusal(Optional.of(cookie), "POST", now).isPresent());
        verify(sessions, times(2)).auditMachineRefusal("machine_session_read_only");

        SessionRecord human = new SessionRecord(machine.sessionId(), machine.actorFingerprint(), machine.csrfSecret(),
                machine.state(), machine.createdAt(), machine.lastSeenAt(), machine.idleDeadlineAt(),
                machine.absoluteExpiresAt(), machine.supersededBySessionId(), machine.endedByActorFingerprint(),
                machine.endReason());
        when(sessions.findBySessionId(anyString(), eq(now))).thenReturn(Optional.of(human));
        assertTrue(gate.machineWriteRefusal(Optional.of(cookie), "POST", now).isEmpty());
    }
}
