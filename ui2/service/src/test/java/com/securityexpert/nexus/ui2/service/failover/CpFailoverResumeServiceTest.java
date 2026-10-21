package com.securityexpert.nexus.ui2.service.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md §5 / §7 groups 1–3: who may resume the return step, what the service
 * refuses before the store, and what it hands the store (session actor, policy, trimmed reason, acknowledgement).
 */
class CpFailoverResumeServiceTest {
    private static final String CLUSTER = "CLS-TEST-01", RUN = "run-a", A = "FW-TEST-01", B = "FW-TEST-02";
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust = mock(ManagementEndpointSshTrustRepository.class);

    private CpFailoverService service(boolean mutation) {
        return new CpFailoverService(devices, mock(DeviceInventoryRepository.class), store, rbac, trust,
            new FailoverMutationSwitch(mutation), JobWindowPolicy.SYSTEM);
    }
    private void roles(String actor, Set<String> roles) {
        when(rbac.evaluate(eq(actor), any(), any())).thenAnswer(call -> {
            Optional<String> role = call.getArgument(1);
            return new RbacEvaluator.Decision(role.filter(roles::contains).isPresent()
                ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("synthetic-authority"), Optional.empty(), Optional.empty());
        });
    }
    private void stoppedRun(boolean trusted) {
        var run = new JooqCpFailoverRepository.Run(RUN, CLUSTER, null, "approval-a", "synthetic-initiator", Instant.EPOCH,
            "job-a", "STOPPED", "STOPPED", "OUTCOME_UNKNOWN", null, "TRAFFIC_BELOW_TOLERANCE");
        when(store.detail(RUN)).thenReturn(Optional.of(new JooqCpFailoverRepository.Detail(run, List.of())));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(member(A), member(B)));
        for (String id : List.of(A, B)) when(devices.findEndpointByDeviceId(id)).thenReturn(Optional.of(
            new EndpointRecord("endpoint-" + id, id, "ssh_exec", A.equals(id) ? "192.0.2.11" : "192.0.2.12", Instant.EPOCH)));
        when(trust.findActiveAlgorithms(anyString(), anyInt())).thenReturn(trusted ? List.of("ssh-ed25519") : List.of());
    }
    private static DeviceSummaryRecord member(String id) {
        return new DeviceSummaryRecord(id, "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(CLUSTER));
    }
    private static String refusal(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(CpFailoverService.Refusal.class, call).code();
    }

    @Test void securityAdminAloneIsAdmittedWithTheSessionActorAndATrimmedReason() {
        var service = service(true); stoppedRun(true);
        roles("synthetic-admin", Set.of(RoleToken.SECURITY_ADMIN));
        when(store.resumeEligibility(RUN)).thenReturn(null);
        when(store.requestResume(RUN, "synthetic-admin", "security_admin", JooqCpFailoverRepository.ADMIN_SINGLE,
            "Return to standby", RUN, true)).thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "resume-a"));
        var decision = service.resumeReturn(RUN, RUN, "  Return to standby  ", true, "synthetic-admin");
        assertEquals("ADMITTED", decision.code()); assertEquals("resume-a", decision.runId());
        verify(store).requestResume(RUN, "synthetic-admin", "security_admin", JooqCpFailoverRepository.ADMIN_SINGLE,
            "Return to standby", RUN, true);
        assertTrue(service.mayResume("synthetic-admin"));
    }

    @Test void operationAdminStaysPendingAndASecondDistinctAdministratorApproves() {
        var service = service(true); stoppedRun(true);
        roles("synthetic-op-a", Set.of(RoleToken.OPERATION_ADMIN)); roles("synthetic-op-b", Set.of(RoleToken.OPERATION_ADMIN));
        when(store.resumeEligibility(RUN)).thenReturn(null);
        when(store.requestResume(eq(RUN), eq("synthetic-op-a"), eq("operation_admin"), eq(JooqCpFailoverRepository.TWO_PERSON),
            anyString(), eq(RUN), anyBoolean())).thenReturn(new JooqCpFailoverRepository.Decision("PENDING_APPROVAL", "resume-a"));
        assertEquals("PENDING_APPROVAL", service.resumeReturn(RUN, RUN, "Return", false, "synthetic-op-a").code());
        when(store.approveResume(RUN, "resume-a", "synthetic-op-a", "operation_admin", false))
            .thenReturn(new JooqCpFailoverRepository.Decision("SELF_APPROVAL", null));
        assertEquals("SELF_APPROVAL", refusal(() -> service.approveResume(RUN, "resume-a", false, "synthetic-op-a")));
        when(store.approveResume(RUN, "resume-a", "synthetic-op-b", "operation_admin", true))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "resume-a"));
        assertEquals("ADMITTED", service.approveResume(RUN, "resume-a", true, "synthetic-op-b").code());
        for (String code : List.of("RESUME_REQUEST_EXPIRED", "RESUME_NOT_PENDING", "BUSINESS_HOURS_ACK_REQUIRED",
                "INCIDENT_NOT_OPEN", "FLEET_MUTATION_ACTIVE", "MEMBERSHIP_CHANGED", "WRONG_POLICY")) {
            when(store.approveResume(RUN, "resume-" + code, "synthetic-op-b", "operation_admin", false))
                .thenReturn(new JooqCpFailoverRepository.Decision(code, null));
            assertEquals(code, refusal(() -> service.approveResume(RUN, "resume-" + code, false, "synthetic-op-b")));
        }
    }

    @Test void operatorViewerAndAiviewAreRefusedBeforeTheStore() {
        var service = service(true);
        for (var roles : List.of(Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER),
                Set.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER), Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER),
                Set.<String>of())) {
            String actor = "synthetic-" + roles.hashCode();
            roles(actor, roles);
            assertEquals("WRONG_ROLE", refusal(() -> service.resumeReturn(RUN, RUN, "Return", true, actor)), roles.toString());
            assertEquals("WRONG_ROLE", refusal(() -> service.approveResume(RUN, "resume-a", true, actor)), roles.toString());
            assertFalse(service.mayResume(actor));
        }
        verifyNoInteractions(store);
    }

    @Test void switchReasonIncidentAndTrustAreCheckedAndNothingIsRequested() {
        roles("synthetic-admin", Set.of(RoleToken.SECURITY_ADMIN));
        assertEquals(FailoverMutationSwitch.DISABLED, refusal(() -> service(false).resumeReturn(RUN, RUN, "Return", true, "synthetic-admin")));
        assertEquals(FailoverMutationSwitch.DISABLED, refusal(() -> service(false).approveResume(RUN, "resume-a", true, "synthetic-admin")));
        var service = service(true);
        for (String reason : new String[]{null, "", "   ", "x".repeat(JooqCpFailoverRepository.RESUME_REASON_MAX + 1)})
            assertEquals("REASON_REQUIRED", refusal(() -> service.resumeReturn(RUN, RUN, reason, true, "synthetic-admin")));
        assertEquals("INCIDENT_MISMATCH", refusal(() -> service.resumeReturn(RUN, " ", "Return", true, "synthetic-admin")));
        verifyNoInteractions(store);
        for (String code : List.of("RESUME_RUN_NOT_ELIGIBLE", "RESUME_DOWN_NOT_CONFIRMED", "RESUME_RETURN_ALREADY_ATTEMPTED",
                "RESUME_ALREADY_USED", "INCIDENT_NOT_OPEN", "OPEN_INCIDENT", "FLEET_MUTATION_ACTIVE", "UNRESOLVED_DISPATCH",
                "MEMBERSHIP_CHANGED", "RUN_ALREADY_ACTIVE")) {
            when(store.resumeEligibility(RUN)).thenReturn(code);
            assertEquals(code, refusal(() -> service.resumeReturn(RUN, RUN, "Return", true, "synthetic-admin")));
        }
        when(store.resumeEligibility(RUN)).thenReturn(null);
        assertEquals("INCIDENT_MISMATCH", refusal(() -> service.resumeReturn(RUN, "run-other", "Return", true, "synthetic-admin")));
        stoppedRun(false);
        assertEquals("TRUSTED_HOST_KEY_REQUIRED", refusal(() -> service.resumeReturn(RUN, RUN, "Return", true, "synthetic-admin")));
        verify(store, never()).requestResume(any(), any(), any(), any(), any(), any(), anyBoolean());
        stoppedRun(true);
        for (String code : List.of("BUSINESS_HOURS_ACK_REQUIRED", "RESUME_ALREADY_USED", "INCIDENT_MISMATCH")) {
            when(store.requestResume(RUN, "synthetic-admin", "security_admin", JooqCpFailoverRepository.ADMIN_SINGLE,
                "Return " + code, RUN, false)).thenReturn(new JooqCpFailoverRepository.Decision(code, null));
            assertEquals(code, refusal(() -> service.resumeReturn(RUN, RUN, "Return " + code, false, "synthetic-admin")));
        }
    }

    /** Argon review 2026-10-21 P2-1: the run view and the second approval check host-key trust like the request. */
    @Test void eligibilityAndTheSecondApprovalRequireTrustedHostKeysOnBothMembers() {
        var service = service(true);
        roles("synthetic-viewer", Set.of(RoleToken.VIEWER)); roles("synthetic-op-b", Set.of(RoleToken.OPERATION_ADMIN));
        when(store.resumeEligibility(RUN)).thenReturn(null);
        stoppedRun(true);
        assertNull(service.resumeEligibility(RUN, "synthetic-viewer"));
        stoppedRun(false);
        assertEquals("TRUSTED_HOST_KEY_REQUIRED", service.resumeEligibility(RUN, "synthetic-viewer"),
            "the run view does not offer a resume the request would refuse");
        assertEquals("TRUSTED_HOST_KEY_REQUIRED", refusal(() -> service.approveResume(RUN, "resume-a", false, "synthetic-op-b")));
        verify(store, never()).approveResume(any(), any(), any(), any(), anyBoolean());
        // The store's own refusal still comes first, and trust that cannot be evaluated is not trusted.
        when(store.resumeEligibility(RUN)).thenReturn("RESUME_REQUEST_EXPIRED");
        assertEquals("RESUME_REQUEST_EXPIRED", service.resumeEligibility(RUN, "synthetic-viewer"));
        when(store.resumeEligibility(RUN)).thenReturn(null);
        when(trust.findActiveAlgorithms(anyString(), anyInt())).thenThrow(new IllegalStateException("synthetic outage"));
        assertEquals("TRUST_NOT_EVALUABLE", service.resumeEligibility(RUN, "synthetic-viewer"));
        assertEquals("TRUST_NOT_EVALUABLE", refusal(() -> service.approveResume(RUN, "resume-a", false, "synthetic-op-b")));
        verify(store, never()).approveResume(any(), any(), any(), any(), anyBoolean());
    }

    @Test void businessHoursAreEightToTwentyEuropeIstanbulStartInclusiveEndExclusive() {
        // 07:59 and 20:00 need no acknowledgement; 08:00 and 19:59 do (PO decision 2026-10-20).
        for (var at : List.of(new Object[]{"2026-10-20T04:59:00Z", false}, new Object[]{"2026-10-20T05:00:00Z", true},
                new Object[]{"2026-10-20T16:59:00Z", true}, new Object[]{"2026-10-20T17:00:00Z", false})) {
            var windows = new JobWindowPolicy(Clock.fixed(Instant.parse((String) at[0]), ZoneOffset.UTC), 60);
            boolean inside = (Boolean) at[1];
            assertEquals(inside, windows.insideBusinessHours(), (String) at[0]);
            assertEquals(inside ? JooqCpFailoverRepository.BUSINESS_HOURS_ACK_REQUIRED : null,
                JooqCpFailoverRepository.resumeBusinessHoursRefusal(windows.insideBusinessHours(), false), (String) at[0]);
            assertNull(JooqCpFailoverRepository.resumeBusinessHoursRefusal(windows.insideBusinessHours(), true));
            var service = new CpFailoverService(devices, mock(DeviceInventoryRepository.class), store, rbac, trust,
                new FailoverMutationSwitch(true), windows);
            assertEquals(inside, service.insideBusinessHours());
        }
    }
}
