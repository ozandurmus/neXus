package com.securityexpert.nexus.ui2.service.schedule;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.device.backup.BackupCollectService;
import com.securityexpert.nexus.ui2.service.device.backup.BackupScheduler;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationCollectService;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationRefreshScheduler;
import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogCollectionScheduler;
import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogCollectionService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.NightlyInventoryScheduler;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.failover.CpReadinessSchedulerAccess;
import com.securityexpert.nexus.ui2.service.management.DiscoveryRefreshSchedulerAccess;

/**
 * V203: every scheduler asks its family's switch at the start of its tick; a family that is off submits nothing. Each
 * test switches one family off and proves its scheduler never reaches the code that admits jobs, then switches it on
 * and proves it does (the switch is read on every tick, no restart).
 */
class SchedulerFamilyGateTest {

    /** A switch with one family off; flipping {@code on} changes the next answer. */
    private static final class Toggle implements FamilySwitch {
        final ScheduledJobFamily family;
        final AtomicBoolean on = new AtomicBoolean(false);
        Toggle(ScheduledJobFamily family) { this.family = family; }
        @Override public boolean isOn(ScheduledJobFamily f) { return f != family || on.get(); }
    }

    @Test
    void inventoryOffSubmitsNothingAndOnRunsAgain() {
        var collect = mock(InventoryCollectService.class);
        when(collect.requestCollectAll(anyString())).thenReturn(new InventoryCollectService.BulkOutcome(1, 1, 0));
        var toggle = new Toggle(ScheduledJobFamily.NIGHTLY_INVENTORY);
        var scheduler = new NightlyInventoryScheduler(collect, toggle);
        scheduler.nightly();
        verifyNoInteractions(collect);
        toggle.on.set(true);
        scheduler.nightly();
        verify(collect).requestCollectAll(NightlyInventoryScheduler.ACTOR);
    }

    @Test
    void configurationOffSubmitsNothing() {
        var collect = mock(ConfigurationCollectService.class);
        when(collect.requestCollectAll(anyString())).thenReturn(new ConfigurationCollectService.BulkOutcome(1, 1, 0));
        var toggle = new Toggle(ScheduledJobFamily.CONFIGURATION_REFRESH);
        var scheduler = new ConfigurationRefreshScheduler(collect, toggle);
        scheduler.daily();
        verifyNoInteractions(collect);
        toggle.on.set(true);
        scheduler.daily();
        verify(collect).requestCollectAll(ConfigurationRefreshScheduler.ACTOR);
    }

    @Test
    void discoveryOffNeverTouchesTheDatabase() {
        var tx = mock(TransactionBoundary.class);
        var scheduler = DiscoveryRefreshSchedulerAccess.scheduler(tx, new Toggle(ScheduledJobFamily.DISCOVERY_REFRESH));
        scheduler.nightly();
        verifyNoInteractions(tx);
    }

    @Test
    void readinessOffNeverStartsAPassAndOffMidPassDropsTheCursor() {
        var service = mock(CpFailoverService.class);
        var store = mock(JooqCpFailoverRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), JobWindowPolicy.ZONE_ID); // 12:00 GMT+3, window open
        var toggle = new Toggle(ScheduledJobFamily.HA_READINESS);
        var scheduler = CpReadinessSchedulerAccess.scheduler(service, store, clock, toggle);
        scheduler.beginPass();
        verifyNoInteractions(service, store);

        var first = new CpFailoverService.ReadinessTarget("cluster-1", "unit-1", "check_point");
        var second = new CpFailoverService.ReadinessTarget("cluster-2", "unit-2", "palo_alto");
        when(service.readinessTargets()).thenReturn(List.of(first, second));
        when(service.requestScheduledReadiness(any())).thenReturn("run-1");
        when(store.finished("run-1")).thenReturn(true);
        toggle.on.set(true);
        scheduler.beginPass();
        verify(service).requestScheduledReadiness(first);
        toggle.on.set(false); // switched off mid-pass
        scheduler.run();
        scheduler.run();
        verify(service, times(1)).requestScheduledReadiness(any());
        verify(service, never()).requestScheduledReadiness(second);
    }

    @Test
    void backupOffClaimsNoDayAndSubmitsNothing() {
        var policy = mock(BackupPolicyRepository.class);
        when(policy.find()).thenReturn(Optional.of(new BackupPolicyRepository.BackupPolicy("default", true, "0 2 * * *", 7, 1, 30,
                Optional.empty(), Instant.parse("2026-11-01T00:00:00Z"))));
        var collect = mock(BackupCollectService.class);
        var toggle = new Toggle(ScheduledJobFamily.SCHEDULED_BACKUP);
        var scheduler = new BackupScheduler(policy, collect, toggle);
        Instant twoAm = Instant.parse("2026-11-10T23:00:30Z"); // 02:00:30 GMT+3
        assertTrue(scheduler.tick(twoAm).isEmpty());
        verify(policy, never()).claimScheduledRun(any(), any(), anyString(), anyString());
        verifyNoInteractions(collect);
    }

    @Test
    void systemLogCollectionOffAdmitsNothing() {
        var service = mock(SystemLogCollectionService.class);
        var toggle = new Toggle(ScheduledJobFamily.SYSTEM_LOG_COLLECTION);
        var scheduler = new SystemLogCollectionScheduler(service, true, false, toggle);
        scheduler.tick();
        verifyNoInteractions(service);
        toggle.on.set(true);
        scheduler.tick();
        verify(service).admitDue(any(), anyString());
    }
}
