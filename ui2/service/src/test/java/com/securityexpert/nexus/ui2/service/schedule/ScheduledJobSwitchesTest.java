package com.securityexpert.nexus.ui2.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository.Change;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository.Row;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/** V203: the per-family switches -- validation, deployment locks, the business-hours confirmation, masking, fail-closed reads. */
class ScheduledJobSwitchesTest {
    /** 22:00 GMT+3: outside business hours. */
    private static final Instant EVENING = Instant.parse("2026-11-10T19:00:00Z");
    /** 10:00 GMT+3: inside business hours. */
    private static final Instant MORNING = Instant.parse("2026-11-10T07:00:00Z");
    private static final Instant CHANGED = Instant.parse("2026-11-10T18:05:00Z");
    private static final Function<String, String> PLAIN = raw -> raw;

    private final ScheduledJobFamilyRepository repository = mock(ScheduledJobFamilyRepository.class);

    private ScheduledJobSwitches switches(Instant now, ScheduledJobSwitches.Deployment deployment, Optional<Boolean> backupPolicy) {
        Clock clock = Clock.fixed(now, JobWindowPolicy.ZONE_ID);
        return new ScheduledJobSwitches(repository, deployment, new JobWindowPolicy(clock, 60),
                new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)), clock, () -> backupPolicy);
    }

    private ScheduledJobSwitches allOn(Instant now) {
        return switches(now, new ScheduledJobSwitches.Deployment(true, true, false), Optional.of(true));
    }

    private Map<String, Row> rows(boolean enabled) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (ScheduledJobFamily f : ScheduledJobFamily.values())
            rows.put(f.key(), new Row(f.key(), enabled, "Seeded ON: synthetic", "admin-synthetic", CHANGED));
        return rows;
    }

    @Test
    void isOnReadsTheRowEveryTimeAndFailsClosed() {
        var s = allOn(EVENING);
        when(repository.enabled("nightly_inventory")).thenReturn(Optional.of(true), Optional.of(false));
        assertTrue(s.isOn(ScheduledJobFamily.NIGHTLY_INVENTORY));
        assertFalse(s.isOn(ScheduledJobFamily.NIGHTLY_INVENTORY), "the second tick sees the change without a restart");
        when(repository.enabled("discovery_refresh")).thenReturn(Optional.empty());
        assertFalse(s.isOn(ScheduledJobFamily.DISCOVERY_REFRESH), "a missing row is OFF");
        when(repository.enabled("ha_readiness")).thenThrow(new IllegalStateException("database down"));
        assertFalse(s.isOn(ScheduledJobFamily.HA_READINESS), "an unreadable row is OFF");
    }

    @Test
    void switchingOffOutsideBusinessHoursStoresTheChangeAndPassesTheFamilysActors() {
        var s = allOn(EVENING);
        when(repository.set(eq("scheduled_backup"), eq(false), eq("Maintenance night"), eq("admin-synthetic"), anyList()))
                .thenReturn(Optional.of(new Change(new Row("scheduled_backup", false, "Maintenance night", "admin-synthetic", EVENING), 4, 0)));
        var outcome = assertInstanceOf(ScheduledJobSwitches.Outcome.Changed.class,
                s.set("admin-synthetic", "scheduled_backup", false, "  Maintenance night  ", false, PLAIN));
        assertEquals(4, outcome.cancelledJobs());
        assertEquals("OFF", outcome.family().get("state"));
        assertEquals("Maintenance night", outcome.family().get("reason"));
        verify(repository).set("scheduled_backup", false, "Maintenance night", "admin-synthetic",
                List.of("system:backup-scheduler", "system:mds-export-scheduler", "system:credential-canary"));
    }

    @Test
    void insideBusinessHoursNothingIsStoredWithoutTheAcknowledgement() {
        var s = allOn(MORNING);
        var refused = assertInstanceOf(ScheduledJobSwitches.Outcome.Refused.class,
                s.set("admin-synthetic", "nightly_inventory", false, "Quiet day please", false, PLAIN));
        assertEquals(409, refused.status());
        assertEquals("BUSINESS_HOURS_CONFIRM_REQUIRED", refused.code());
        assertTrue(refused.message().contains("changes which scheduled jobs run"), refused.message());
        verify(repository, never()).set(anyString(), anyBoolean(), anyString(), anyString(), anyList());

        when(repository.set(anyString(), anyBoolean(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.of(new Change(new Row("nightly_inventory", false, "Quiet day please", "admin-synthetic", MORNING), 0, 0)));
        assertInstanceOf(ScheduledJobSwitches.Outcome.Changed.class,
                s.set("admin-synthetic", "nightly_inventory", false, "Quiet day please", true, PLAIN));
    }

    @Test
    void reasonFamilyAndEnabledAreValidatedBeforeAnythingElse() {
        var s = allOn(EVENING);
        assertEquals("REASON_REQUIRED", refused(s.set("a", "nightly_inventory", false, "   no ", false, PLAIN)).code());
        assertEquals("REASON_REQUIRED", refused(s.set("a", "nightly_inventory", false, null, false, PLAIN)).code());
        assertEquals("REASON_REQUIRED", refused(s.set("a", "nightly_inventory", false, "x".repeat(501), false, PLAIN)).code());
        assertEquals("UNKNOWN_FAMILY", refused(s.set("a", "cluster_diff", false, "A good reason", false, PLAIN)).code());
        assertEquals(404, refused(s.set("a", null, false, "A good reason", false, PLAIN)).status());
        assertEquals("ENABLED_REQUIRED", refused(s.set("a", "nightly_inventory", null, "A good reason", false, PLAIN)).code());
        verify(repository, never()).set(anyString(), anyBoolean(), anyString(), anyString(), anyList());
    }

    @Test
    void theDeploymentMasterSwitchLocksEveryFamilyExceptAnIndependentSystemLogCollection() {
        var master = new ScheduledJobSwitches.Deployment(false, true, true);
        for (ScheduledJobFamily f : ScheduledJobFamily.values()) {
            assertEquals(f != ScheduledJobFamily.SYSTEM_LOG_COLLECTION, master.lockReason(f).isPresent(), f.key());
        }
        assertTrue(new ScheduledJobSwitches.Deployment(false, true, false).lockReason(ScheduledJobFamily.SYSTEM_LOG_COLLECTION)
                .orElseThrow().contains("UI2_SCHEDULING_ENABLED=false"));
        assertTrue(new ScheduledJobSwitches.Deployment(true, false, true).lockReason(ScheduledJobFamily.SYSTEM_LOG_COLLECTION)
                .orElseThrow().contains("UI2_SYSTEM_LOG_SCHEDULED_COLLECTION_ENABLED=false"));

        var s = switches(EVENING, new ScheduledJobSwitches.Deployment(false, true, false), Optional.of(true));
        var refused = refused(s.set("a", "discovery_refresh", true, "Start collecting", false, PLAIN));
        assertEquals(409, refused.status());
        assertEquals("SCHEDULING_LOCKED_BY_DEPLOYMENT", refused.code());
        verify(repository, never()).set(anyString(), anyBoolean(), anyString(), anyString(), anyList());

        when(repository.readAll()).thenReturn(rows(true));
        when(repository.lastSubmitted(any())).thenReturn(Map.of());
        var view = s.view(true, false, PLAIN);
        assertEquals(false, view.get("master_enabled"));
        for (Map<String, Object> family : families(view)) {
            assertEquals("LOCKED", family.get("state"), String.valueOf(family.get("family")));
            assertTrue(String.valueOf(family.get("lock_reason")).contains("UI2_SCHEDULING_ENABLED=false"));
        }
    }

    @Test
    void theViewNamesEveryFamilyWithLastRunNextWindowAndWhoChangedIt() {
        var s = switches(EVENING, new ScheduledJobSwitches.Deployment(true, true, false), Optional.of(false));
        Map<String, Row> rows = rows(true);
        rows.put("ha_readiness", new Row("ha_readiness", false, "Pilot week", "admin-synthetic", CHANGED));
        when(repository.readAll()).thenReturn(rows);
        when(repository.lastSubmitted(any())).thenReturn(Map.of(
                "system:backup-scheduler", Instant.parse("2026-11-09T23:00:01Z"),
                "system:mds-export-scheduler", Instant.parse("2026-11-09T23:00:05Z"),
                "system:nightly-inventory", Instant.parse("2026-11-10T15:00:01Z")));
        var view = s.view(false, false, PLAIN);
        var families = families(view);
        assertEquals(List.of("discovery_refresh", "nightly_inventory", "configuration_refresh", "ha_readiness",
                "scheduled_backup", "system_log_collection"), families.stream().map(f -> f.get("family")).toList());
        Map<String, Object> inventory = families.get(1);
        assertEquals("ON", inventory.get("state"));
        assertEquals("2026-11-10T15:00:01Z", inventory.get("last_run_at"));
        // 22:00 GMT+3 -> the 00:00 slot of the next day.
        assertEquals("2026-11-11T00:00+03:00", inventory.get("next_window_at"));
        assertEquals("OFF", families.get(3).get("state"));
        assertEquals("Pilot week", families.get(3).get("reason"));
        assertEquals("admin-synthetic", families.get(3).get("changed_by"));
        Map<String, Object> backup = families.get(4);
        assertEquals("2026-11-09T23:00:05Z", backup.get("last_run_at"), "the newer of the backup and MDS export actors");
        assertEquals("2026-11-11T02:00+03:00", backup.get("next_window_at"));
        assertTrue(String.valueOf(backup.get("note")).contains("backup policy schedule is off"));
        assertNull(families.get(0).get("last_run_at"), "never run is null, not a guess");
        assertEquals(false, view.get("can_write"));
        assertEquals(false, ((Map<?, ?>) view.get("business_hours")).get("inside_now"));
    }

    @Test
    void theMaskedViewTokenisesThePersonAndWithholdsTheReason() {
        var s = allOn(EVENING);
        when(repository.readAll()).thenReturn(rows(true));
        when(repository.lastSubmitted(any())).thenReturn(Map.of());
        var view = s.view(false, true, raw -> "USR-0123456789");
        assertEquals(true, view.get("masked"));
        for (Map<String, Object> family : families(view)) {
            assertNull(family.get("reason"));
            assertEquals("USR-0123456789", family.get("changed_by"));
        }
    }

    @Test
    void theSweepCancelsOnlyTheActorsOfSwitchedOffFamilies() {
        var s = allOn(EVENING);
        Map<String, Row> rows = rows(true);
        rows.put("discovery_refresh", new Row("discovery_refresh", false, "Off", "a", CHANGED));
        when(repository.readAll()).thenReturn(rows);
        when(repository.sweep(anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(new Change(null, 2, 1));
        assertEquals(2, s.sweep());
        verify(repository).sweep(ScheduledJobSwitches.SWEEP_ACTOR, Map.of("discovery_refresh", List.of("system:discovery-refresh")));
    }

    @Test
    void theFamilyListMatchesTheMigrationsClosedList() {
        assertEquals(List.of("discovery_refresh", "nightly_inventory", "configuration_refresh", "ha_readiness",
                "scheduled_backup", "system_log_collection"),
                java.util.Arrays.stream(ScheduledJobFamily.values()).map(ScheduledJobFamily::key).toList());
    }

    private static ScheduledJobSwitches.Outcome.Refused refused(ScheduledJobSwitches.Outcome outcome) {
        return assertInstanceOf(ScheduledJobSwitches.Outcome.Refused.class, outcome);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> families(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("families");
    }
}
