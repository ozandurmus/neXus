package com.securityexpert.nexus.ui2.jobs.window;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

/**
 * PO decision 2026-10-20: an administrator's manual device job is never refused for the window; inside business hours
 * (every day 08:00-20:00 Europe/Istanbul) it needs the acknowledgement. Operator and aiview submissions never hold a
 * grant and keep OUTSIDE_JOB_WINDOW; schedulers never hold one either.
 */
class AdminManualAdmissionPolicyTest {
    private static final String ACTOR = "synthetic-actor";

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    private static JobWindowPolicy policyAt(String local) {
        return new JobWindowPolicy(Clock.fixed(at(local), JobWindowPolicy.ZONE_ID), 60, new BusinessHours(
                BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END));
    }

    enum Role { SECURITY_ADMIN, OPERATION_ADMIN, OPERATOR, AIVIEW;
        Optional<String> bypass() {
            return switch (this) {
                case SECURITY_ADMIN -> Optional.of(ManualAdmission.SECURITY_ADMIN);
                case OPERATION_ADMIN -> Optional.of(ManualAdmission.OPERATION_ADMIN);
                default -> Optional.empty();
            };
        }
    }

    /** {local time, inside window, inside business hours}. */
    static Stream<Arguments> matrix() {
        List<Arguments> rows = new ArrayList<>();
        String[][] times = {
            {"2026-10-08T12:10:00", "true", "true"},
            {"2026-10-08T13:00:00", "false", "true"},
            {"2026-10-08T06:10:00", "true", "false"},
            {"2026-10-08T03:30:00", "false", "false"},
        };
        for (Role role : Role.values())
            for (String[] t : times)
                for (boolean ack : new boolean[] {false, true})
                    rows.add(Arguments.of(role, t[0], Boolean.parseBoolean(t[1]), Boolean.parseBoolean(t[2]), ack));
        return rows.stream();
    }

    private static void submit(Role role, boolean ack, Runnable admission) {
        var grant = role.bypass().map(r -> new ManualAdmission.Grant(ACTOR, r, ack));
        if (grant.isPresent()) ManualAdmission.under(grant.get(), () -> { admission.run(); return null; });
        else admission.run();
    }

    @ParameterizedTest(name = "{0} at {1} window={2} businessHours={3} ack={4}")
    @MethodSource("matrix")
    void manualAdmissionPerRoleWindowBusinessHoursAndAcknowledgement(Role role, String local, boolean insideWindow,
            boolean insideBusinessHours, boolean ack) {
        var policy = policyAt(local);
        assertEquals(insideWindow, policy.isOpen());
        assertEquals(insideBusinessHours, policy.insideBusinessHours());
        List<Runnable> gates = List.of(
                () -> policy.requireOpenFor(ACTOR),
                () -> policy.requireDeviceJobOpen("cp_inventory_collect", "cp_inventory_collect", ACTOR));
        for (Runnable gate : gates) {
            if (role.bypass().isPresent()) {
                if (insideBusinessHours && !ack) {
                    var refusal = assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> submit(role, ack, gate));
                    assertEquals("Business hours (08:00–20:00 GMT+3). This sends commands to the device. Continue?",
                            refusal.getMessage());
                } else {
                    assertDoesNotThrow(() -> submit(role, ack, gate));
                }
            } else if (insideWindow) {
                assertDoesNotThrow(() -> submit(role, ack, gate));
            } else {
                assertThrows(JobWindowPolicy.OutsideWindow.class, () -> submit(role, ack, gate));
            }
        }
        assertTrue(ManualAdmission.current().isEmpty(), "the grant never outlives its scope");
    }

    @Test void scheduledAndExplicitTimeChecksIgnoreTheGrant() {
        var outside = policyAt("2026-10-08T13:00:00");
        var grant = new ManualAdmission.Grant(ACTOR, ManualAdmission.SECURITY_ADMIN, true);
        ManualAdmission.under(grant, () -> {
            assertFalse(outside.isOpen(), "schedulers read the window itself");
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outside.requireOpen(outside.now()));
            // A scheduled backup admitted by the backup scheduler's own actor never rides on an admin's grant.
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outside.requireDeviceJobOpen(
                    "cp_gateway_backup", "cp_gateway_backup", ScheduledBackupWindowPolicy.ACTOR));
            return null;
        });
    }

    /**
     * Argon review 2026-10-20 P2-2: the no-arg form has no actor to match a grant against, so it never consults one --
     * not even the grant of the actor whose request is on this thread. It is the plain window, for every caller.
     */
    @Test void theNoArgumentWindowCheckNeverHonoursAGrant() {
        var outside = policyAt("2026-10-08T13:00:00");
        var insideWindowInBusinessHours = policyAt("2026-10-08T12:10:00");
        for (String role : List.of(ManualAdmission.SECURITY_ADMIN, ManualAdmission.OPERATION_ADMIN)) {
            for (String holder : List.of(ACTOR, "another-actor", "system:worker")) {
                ManualAdmission.under(new ManualAdmission.Grant(holder, role, true), () -> {
                    assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outside.requireOpen());
                    return null;
                });
                // Nor does it ask for the business-hours acknowledgement: inside a window it simply admits.
                ManualAdmission.under(new ManualAdmission.Grant(holder, role, false), () -> {
                    assertDoesNotThrow(() -> insideWindowInBusinessHours.requireOpen());
                    return null;
                });
            }
        }
    }

    @Test void aGrantForAnotherActorNeverApplies() {
        var outside = policyAt("2026-10-08T13:00:00");
        ManualAdmission.under(new ManualAdmission.Grant("another-actor", ManualAdmission.OPERATION_ADMIN, true), () -> {
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outside.requireOpenFor(ACTOR));
            assertThrows(JobWindowPolicy.OutsideWindow.class,
                    () -> outside.requireDeviceJobOpen("cp_inventory_collect", "cp_inventory_collect", ACTOR));
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outside.requireOpenUnlessAdmin(ACTOR));
            return null;
        });
    }

    @Test void withoutGrantRestoresTheWindowForEveryRoleAndThenTheGrant() {
        var outside = policyAt("2026-10-08T13:00:00");
        var grant = new ManualAdmission.Grant(ACTOR, ManualAdmission.SECURITY_ADMIN, true);
        ManualAdmission.under(grant, () -> {
            assertThrows(JobWindowPolicy.OutsideWindow.class,
                    () -> ManualAdmission.withoutGrant(() -> { outside.requireOpen(); return null; }));
            assertEquals(Optional.of(grant), ManualAdmission.current());
            return null;
        });
    }

    @Test void approvalStepsNeedNoAcknowledgementForAnAdministrator() {
        var outsideInBusinessHours = policyAt("2026-10-08T13:00:00");
        ManualAdmission.under(new ManualAdmission.Grant(ACTOR, ManualAdmission.OPERATION_ADMIN, false), () -> {
            assertDoesNotThrow(() -> outsideInBusinessHours.requireOpenUnlessAdmin(ACTOR));
            return null;
        });
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outsideInBusinessHours.requireOpenUnlessAdmin(ACTOR));
    }

    @Test void businessHoursAreEveryDayEightToTwentyIstanbulByDefault() {
        var hours = BusinessHours.parse(null);
        assertEquals(LocalTime.of(8, 0), hours.start());
        assertEquals(LocalTime.of(20, 0), hours.end());
        assertFalse(hours.inside(at("2026-10-08T07:59:59")));
        assertTrue(hours.inside(at("2026-10-08T08:00:00")));
        assertTrue(hours.inside(at("2026-10-08T19:59:59")));
        assertFalse(hours.inside(at("2026-10-08T20:00:00")));
        // Saturday and Sunday are business days too (PO: every day).
        assertTrue(hours.inside(at("2026-10-10T10:00:00")));
        assertTrue(hours.inside(at("2026-10-11T10:00:00")));
        assertEquals("08:00–20:00", hours.label());
        var custom = BusinessHours.parse("09:30-18:00");
        assertFalse(custom.inside(at("2026-10-08T09:00:00")));
        assertTrue(custom.inside(at("2026-10-08T09:30:00")));
        assertThrows(IllegalArgumentException.class, () -> BusinessHours.parse("20:00-08:00"));
        assertThrows(IllegalArgumentException.class, () -> BusinessHours.parse("08:00"));
    }

    @Test void onlyTheTwoAdminRolesCanHoldAGrant() {
        assertThrows(IllegalArgumentException.class, () -> new ManualAdmission.Grant(ACTOR, "operator", true));
        assertThrows(IllegalArgumentException.class, () -> new ManualAdmission.Grant(ACTOR, "replay_viewer", true));
    }
}
