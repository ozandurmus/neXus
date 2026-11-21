package com.securityexpert.nexus.ui2.service.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.JooqPanConfigSyncRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md §3.1 and §6: the server's view and request decisions. */
class PanConfigSyncServiceTest {
    private static final String ACTOR = "synthetic-" + "actor", CLUSTER = "CLS-" + "ROMEO-" + 1;
    private static final String A = "member-" + "a", B = "member-" + "b";
    /** 09:30 UTC = 12:30 Europe/Istanbul: inside business hours and inside the 12:00 job window. */
    private static final JobWindowPolicy WINDOWS = new JobWindowPolicy(Clock.fixed(Instant.parse("2026-10-24T09:30:00Z"), ZoneOffset.UTC),
        60, new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));

    private static DeviceSummaryRecord member(String id) {
        return new DeviceSummaryRecord(id, "gateway", "palo_alto", DeviceEnrollmentState.ENROLLED,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(CLUSTER));
    }
    private static final CpFailoverService.Unit UNIT = new CpFailoverService.Unit("unit-a", "cluster-a", CLUSTER, null,
        List.of(member(A), member(B)));
    private static String checks(String sync, String roleA, String roleB) { return checks(sync, roleA, roleB, "PASS", "{}"); }
    private static String checks(String sync, String roleA, String roleB, String check1) {
        return checks(sync, roleA, roleB, check1, "{}");
    }
    private static String checks(String sync, String roleA, String roleB, String check1, String syncDerived) {
        return "[{\"memberRef\":\"" + A + "\",\"checkNo\":1,\"status\":\"" + check1 + "\",\"derived\":{\"role\":\"" + roleA + "\"}},"
            + "{\"memberRef\":\"" + B + "\",\"checkNo\":1,\"status\":\"" + check1 + "\",\"derived\":{\"role\":\"" + roleB + "\"}},"
            + "{\"memberRef\":\"" + A + "\",\"checkNo\":4,\"status\":\"" + sync + "\",\"derived\":" + syncDerived + "},"
            + "{\"memberRef\":\"" + B + "\",\"checkNo\":4,\"status\":\"" + sync + "\",\"derived\":" + syncDerived + "}]";
    }
    private static JooqCpFailoverRepository.ReadinessStatus readiness(String sync, Instant at, String roleA, String roleB) {
        return new JooqCpFailoverRepository.ReadinessStatus(CLUSTER, null, "palo_alto", "NOT_READY", at, "Configuration sync",
            checks(sync, roleA, roleB));
    }
    private static final com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.Grant SA_GRANT =
        new com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.Grant(RbacEvaluator.LOCAL_AUTHORITY,
            "binding-" + RoleToken.SECURITY_ADMIN, RoleToken.SECURITY_ADMIN);
    private static JooqCpFailoverRepository.ReadinessStatus readinessWithDerived(String sync, Instant at, String syncDerived) {
        return new JooqCpFailoverRepository.ReadinessStatus(CLUSTER, null, "palo_alto", "NOT_READY", at, "Configuration sync",
            checks(sync, "active", "passive", "PASS", syncDerived));
    }
    private static RbacEvaluator rbac(Set<String> roles) {
        var rbac = mock(RbacEvaluator.class);
        when(rbac.evaluate(eq(ACTOR), any(), any(Instant.class))).thenAnswer(call -> {
            Optional<String> role = call.getArgument(1);
            boolean held = role.filter(roles::contains).isPresent();
            return new RbacEvaluator.Decision(held ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                Optional.of(RbacEvaluator.LOCAL_AUTHORITY), Optional.empty(), held ? role.map(r -> "binding-" + r) : Optional.empty());
        });
        return rbac;
    }
    private static JooqPanConfigSyncRepository.ConfigSync record(String state) {
        return new JooqPanConfigSyncRepository.ConfigSync("sync-a", CLUSTER, A, B, "run-a", ACTOR, "security_admin", Instant.now(),
            false, false, "job-a", state, null, null, false, null, null, null, null);
    }
    private static PanConfigSyncService service(JooqPanConfigSyncRepository store, CpFailoverService units, Set<String> roles,
            boolean enabled) {
        return new PanConfigSyncService(store, units, rbac(roles), new FailoverMutationSwitch(enabled), WINDOWS);
    }

    @Test void theViewOffersTheButtonOnlyForAFailedCheckFourOfAPaloAltoUnit() {
        var s = service(mock(JooqPanConfigSyncRepository.class), mock(CpFailoverService.class), Set.of(RoleToken.SECURITY_ADMIN), true);
        Instant now = Instant.now();
        var offered = s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), "CHECK_NOT_READY", false, true, Optional.empty());
        assertTrue(offered.offered()); assertTrue(offered.canRun()); assertNull(offered.refusal());
        assertEquals(A, offered.sourceRef()); assertEquals(B, offered.targetRef());
        // The source is always the member the readiness saw active.
        var swapped = s.view(UNIT, "palo_alto", readiness("FAIL", now, "passive", "active"), null, false, true, Optional.empty());
        assertEquals(B, swapped.sourceRef()); assertEquals(A, swapped.targetRef());
        assertFalse(s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), null, false, false, Optional.empty()).canRun(),
            "operator, viewer");
        assertFalse(s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), null, true, true, Optional.empty()).canRun(),
            "the masked view");
        var passing = s.view(UNIT, "palo_alto", readiness("PASS", now, "active", "passive"), null, false, true, Optional.empty());
        assertFalse(passing.offered()); assertFalse(passing.canRun()); assertEquals("CONFIG_SYNC_NOT_FAILED", passing.refusal());
        assertFalse(s.view(UNIT, "palo_alto", null, null, false, true, Optional.empty()).offered());
        assertNull(s.view(UNIT, "check_point", readiness("FAIL", now, "active", "passive"), null, false, true, Optional.empty()));
        assertNull(s.view(new CpFailoverService.Unit("vs", "cluster-a", "vs", "2", UNIT.members()), "palo_alto",
            readiness("FAIL", now, "active", "passive"), null, false, true, Optional.empty()));
        // Refusals the server would answer now.
        assertEquals("READINESS_NOT_FRESH", s.view(UNIT, "palo_alto", readiness("FAIL", now.minusSeconds(3601), "active", "passive"),
            null, false, true, Optional.empty()).refusal());
        assertEquals("ROLES_CHANGED", s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "active"), null, false, true,
            Optional.empty()).refusal());
        assertEquals("OPEN_INCIDENT", s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), "OPEN_INCIDENT", false, true,
            Optional.empty()).refusal());
        assertEquals("CONFIG_SYNC_ALREADY_ACTIVE", s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), null, false,
            true, Optional.of(record("SYNCING"))).refusal());
        assertNull(s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), null, false, true,
            Optional.of(record("STOPPED"))).refusal());
        var off = service(mock(JooqPanConfigSyncRepository.class), mock(CpFailoverService.class), Set.of(RoleToken.SECURITY_ADMIN), false);
        assertEquals(FailoverMutationSwitch.DISABLED, off.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), null,
            false, true, Optional.empty()).refusal());
    }

    @Test void rolesCountOnlyFromAPassedCheckOneAndABusyUnitIsRefused() {
        var s = service(mock(JooqPanConfigSyncRepository.class), mock(CpFailoverService.class), Set.of(RoleToken.SECURITY_ADMIN), true);
        Instant now = Instant.now();
        for (String check1 : java.util.List.of("FAIL", "UNKNOWN")) {
            var r = new JooqCpFailoverRepository.ReadinessStatus(CLUSTER, null, "palo_alto", "NOT_READY", now, "Mode and roles",
                checks("FAIL", "active", "passive", check1));
            var view = s.view(UNIT, "palo_alto", r, null, false, true, Optional.empty());
            assertEquals("ROLES_CHANGED", view.refusal(), check1);
            assertNull(view.sourceRef()); assertNull(view.targetRef());
        }
        for (String busy : java.util.List.of("RUN_ALREADY_ACTIVE", "UNRESOLVED_DISPATCH")) {
            assertEquals(busy, s.view(UNIT, "palo_alto", readiness("FAIL", now, "active", "passive"), null, () -> busy, false, true,
                Optional.empty()).refusal());
        }
        // The busy read is skipped when check 4 did not fail.
        assertEquals("CONFIG_SYNC_NOT_FAILED", s.view(UNIT, "palo_alto", readiness("PASS", now, "active", "passive"), null,
            () -> { throw new AssertionError("not read"); }, false, true, Optional.empty()).refusal());
    }

    @Test void onlyAnAdministratorOutsideTheMaskedViewMayRequestAndCheckPointNever() {
        var store = mock(JooqPanConfigSyncRepository.class);
        var units = mock(CpFailoverService.class);
        assertEquals(PanConfigSyncService.NOT_SUPPORTED, assertThrows(CpFailoverService.Refusal.class, () ->
            service(store, units, Set.of(RoleToken.SECURITY_ADMIN), true).request("cluster-a", "unit-a", A, B, ACTOR, "check_point")).code());
        for (Set<String> roles : List.of(Set.of(RoleToken.OPERATOR), Set.of(RoleToken.VIEWER), Set.of(RoleToken.REPLAY_VIEWER),
                Set.of(RoleToken.SECURITY_ADMIN, RoleToken.REPLAY_VIEWER))) {
            assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class, () ->
                service(store, units, roles, true).request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto")).code(), roles.toString());
        }
        assertEquals(FailoverMutationSwitch.DISABLED, assertThrows(CpFailoverService.Refusal.class, () ->
            service(store, units, Set.of(RoleToken.OPERATION_ADMIN), false).request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto")).code());
        verifyNoInteractions(store, units);
    }

    @Test void insideBusinessHoursTheAcknowledgementIsRequiredBeforeAnythingIsCreated() {
        var store = mock(JooqPanConfigSyncRepository.class);
        var units = mock(CpFailoverService.class);
        var s = service(store, units, Set.of(RoleToken.SECURITY_ADMIN), true);
        assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> ManualAdmission.under(
            new ManualAdmission.Grant(ACTOR, ManualAdmission.SECURITY_ADMIN, false),
            () -> s.request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto")));
        verifyNoInteractions(store, units);
    }

    @Test void theMembersMustBeTheUnitsAndTheRepositoryDecides() {
        var store = mock(JooqPanConfigSyncRepository.class);
        var units = mock(CpFailoverService.class);
        when(units.unitFor("cluster-a", "unit-a", ACTOR, "palo_alto")).thenReturn(UNIT);
        var s = service(store, units, Set.of(RoleToken.SECURITY_ADMIN), true);
        var grant = new ManualAdmission.Grant(ACTOR, ManualAdmission.SECURITY_ADMIN, true);
        for (String[] pair : new String[][]{{A, A}, {A, "member-other"}, {"member-other", B}}) {
            assertEquals("MEMBER_SET_CHANGED", assertThrows(CpFailoverService.Refusal.class, () -> ManualAdmission.under(grant,
                () -> s.request("cluster-a", "unit-a", pair[0], pair[1], ACTOR, "palo_alto"))).code());
        }
        verify(store, never()).request(anyString(), anyString(), anyString(), anyString(), any());
        when(store.request(CLUSTER, A, B, ACTOR, SA_GRANT)).thenReturn(new JooqCpFailoverRepository.Decision("ROLES_CHANGED", null));
        assertEquals("ROLES_CHANGED", assertThrows(CpFailoverService.Refusal.class, () -> ManualAdmission.under(grant,
            () -> s.request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto"))).code());
        when(store.request(CLUSTER, A, B, ACTOR, SA_GRANT)).thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "sync-a"));
        when(store.find("sync-a")).thenReturn(Optional.of(record("QUEUED")));
        assertEquals("sync-a", ManualAdmission.under(grant, () -> s.request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto")).id());
    }

    @Test void directoryUnreachableOrRemovedAtConfigSyncStartIsRefusedBeforeTheStore() {
        var store = mock(JooqPanConfigSyncRepository.class);
        var units = mock(CpFailoverService.class);
        var rbac = mock(RbacEvaluator.class);
        var s = new PanConfigSyncService(store, units, rbac, new FailoverMutationSwitch(true));
        when(rbac.revalidateDirectoryActor(eq(ACTOR), any()))
            .thenReturn(RbacEvaluator.DirectoryRevalidation.DIRECTORY_UNREACHABLE);
        assertEquals(com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.DIRECTORY_UNREACHABLE,
            assertThrows(CpFailoverService.Refusal.class,
                () -> s.request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto")).code());
        when(rbac.revalidateDirectoryActor(eq(ACTOR), any()))
            .thenReturn(RbacEvaluator.DirectoryRevalidation.MEMBERSHIP_LOST);
        assertEquals(com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,
            assertThrows(CpFailoverService.Refusal.class,
                () -> s.request("cluster-a", "unit-a", A, B, ACTOR, "palo_alto")).code());
        verifyNoInteractions(store, units);
    }

    @Test void viewMarksSyncSettlingDuringTheWaitAndWhenFollowUpReadinessStillSeesInProgress() {
        var s = service(mock(JooqPanConfigSyncRepository.class), mock(CpFailoverService.class), Set.of(RoleToken.SECURITY_ADMIN), true);
        Instant preSyncAt = Instant.now().minusSeconds(120);
        Instant requestedAt = preSyncAt.plusSeconds(30);
        Instant finishedAt = requestedAt.plusSeconds(60);
        Instant followUpAt = finishedAt.plusSeconds(10);

        // Before any request: check 4 failed, not settling.
        var initial = s.view(UNIT, "palo_alto", readiness("FAIL", preSyncAt, "active", "passive"), null, false, true, Optional.empty());
        assertEquals("FAIL", initial.readinessOutcome());
        assertFalse(initial.settling());

        // While the sync job is open (waiting 60 s after the reply) or DONE before follow-up readiness finishes: settling.
        var syncing = new JooqPanConfigSyncRepository.ConfigSync("sync-a", CLUSTER, A, B, "run-0", ACTOR, "security_admin",
            requestedAt, false, false, "job-a", "SYNCING", null, "success", true, null, null, requestedAt, null);
        var duringWait = s.view(UNIT, "palo_alto", readiness("FAIL", preSyncAt, "active", "passive"), null, false, true, Optional.of(syncing));
        assertTrue(duringWait.settling());
        assertEquals("CONFIG_SYNC_ALREADY_ACTIVE", duringWait.refusal());

        var doneAwaitingFollowUp = new JooqPanConfigSyncRepository.ConfigSync("sync-a", CLUSTER, A, B, "run-0", ACTOR, "security_admin",
            requestedAt, false, false, "job-a", "DONE", JooqPanConfigSyncRepository.REQUESTED, "success", true, "run-1", null,
            requestedAt, finishedAt);
        var awaitingReRun = s.view(UNIT, "palo_alto", readiness("FAIL", preSyncAt, "active", "passive"), null, false, true,
            Optional.of(doneAwaitingFollowUp));
        assertTrue(awaitingReRun.settling());

        // Follow-up readiness finished, but still sees "synchronization in progress": still settling, outcome IN_PROGRESS.
        String inProgressDerived = "{\"first\":{\"running-sync\":\"synchronization in progress\",\"peer-reachable\":true,\"running-sync-enabled\":\"yes\"},"
            + "\"second\":{\"running-sync\":\"synchronization in progress\",\"peer-reachable\":true,\"running-sync-enabled\":\"yes\"}}";
        var stillInProgress = s.view(UNIT, "palo_alto", readinessWithDerived("FAIL", followUpAt, inProgressDerived), null, false, true,
            Optional.of(doneAwaitingFollowUp));
        assertEquals("IN_PROGRESS", stillInProgress.readinessOutcome());
        assertTrue(stillInProgress.settling());
        assertEquals("CONFIG_SYNC_IN_PROGRESS", stillInProgress.refusal());

        // Follow-up readiness finished with a FAIL that is not "in progress": no longer settling.
        String notSyncedDerived = "{\"first\":{\"running-sync\":\"not synchronized\",\"peer-reachable\":true,\"running-sync-enabled\":\"yes\"},"
            + "\"second\":{\"running-sync\":\"not synchronized\",\"peer-reachable\":true,\"running-sync-enabled\":\"yes\"}}";
        var stillFails = s.view(UNIT, "palo_alto", readinessWithDerived("FAIL", followUpAt, notSyncedDerived), null, false, true,
            Optional.of(doneAwaitingFollowUp));
        assertEquals("FAIL", stillFails.readinessOutcome());
        assertFalse(stillFails.settling());
        assertNull(stillFails.refusal());

        // Follow-up readiness finished with PASS: not settling, not offered.
        var passed = s.view(UNIT, "palo_alto", readiness("PASS", followUpAt, "active", "passive"), null, false, true,
            Optional.of(doneAwaitingFollowUp));
        assertEquals("PASS", passed.readinessOutcome());
        assertFalse(passed.settling());
        assertFalse(passed.offered());
    }
}
