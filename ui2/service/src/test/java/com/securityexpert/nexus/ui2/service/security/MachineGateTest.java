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
    @Test
    void onlyDiagnosticPostCanPassMachineGuardAndStillNeedsCsrfAndRbac() {
        String cookie = "synthetic-cookie";
        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var decisions = mock(com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository.class);
        var machine = new SessionRecord(SessionHasher.hash(cookie), "synthetic-actor", "synthetic-csrf",
                SessionState.ACTIVE, now.minusSeconds(10), now.minusSeconds(10), now.plusSeconds(600),
                now.plusSeconds(1800), Optional.empty(), Optional.empty(), Optional.empty(), true);
        when(sessions.findBySessionId(anyString(), eq(now))).thenReturn(Optional.of(machine));
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, decisions);
        assertTrue(gate.machineWriteRefusal(Optional.of(cookie), "POST", now, ActionRegistry.FMG_DIAGNOSTIC_RUN).isEmpty());
        var missingCsrf = new GateRequest("POST", Optional.of(cookie), Optional.empty(), Optional.of("https://example.invalid"),
                ActionRegistry.FMG_DIAGNOSTIC_RUN, Optional.empty());
        assertEquals(401, ((GateOutcome.Refused)gate.evaluate(missingCsrf, now)).httpStatus());
        verifyNoInteractions(rbac);
        var request = new GateRequest("POST", Optional.of(cookie), Optional.of("synthetic-csrf"), Optional.of("https://example.invalid"),
                ActionRegistry.FMG_DIAGNOSTIC_RUN, Optional.empty());
        when(rbac.evaluateAny(eq("synthetic-actor"), anySet(), eq(now))).thenReturn(
                new RbacEvaluator.Decision(com.securityexpert.nexus.ui2.platform.AuthzOutcome.DENIED,
                        Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(403, ((GateOutcome.Refused)gate.evaluate(request, now)).httpStatus());
        when(rbac.evaluateAny(eq("synthetic-actor"), anySet(), eq(now))).thenReturn(
                new RbacEvaluator.Decision(com.securityexpert.nexus.ui2.platform.AuthzOutcome.PERMITTED,
                        Optional.empty(), Optional.empty(), Optional.empty()));
        assertInstanceOf(GateOutcome.Proceed.class, gate.evaluate(request, now));
        for (String action : new String[]{ActionRegistry.CP_FAILOVER_APPROVE, ActionRegistry.CP_FAILOVER_START, "failover_schedule"}) {
            assertEquals(403, ((GateOutcome.Refused)gate.evaluate(new GateRequest("POST", Optional.of(cookie),
                    Optional.of("synthetic-csrf"), Optional.of("https://example.invalid"), action, Optional.empty()), now)).httpStatus());
        }
        assertTrue(gate.machineWriteRefusal(Optional.of(cookie), "DELETE", now, ActionRegistry.FMG_DIAGNOSTIC_RUN).isPresent());
    }
}
