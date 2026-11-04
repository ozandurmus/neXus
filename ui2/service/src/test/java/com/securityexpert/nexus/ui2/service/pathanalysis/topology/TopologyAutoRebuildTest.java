package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * The system-actor rebuild: once after startup (a failure leaves NOT_BUILT), after an inventory change on a debounced
 * read, and never more than one at a time. Time and the executor are controlled; no database, no device.
 */
class TopologyAutoRebuildTest {

    private static final Duration CHECK = Duration.ofMinutes(1);
    private static final Duration GAP = Duration.ofMinutes(5);

    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-04T07:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    /** Tasks wait until the test runs them, so "startup does not wait" is observable. */
    private static final class QueuedExecutor implements Executor {
        final List<Runnable> queued = new ArrayList<>();

        @Override public synchronized void execute(Runnable task) {
            queued.add(task);
        }

        synchronized void runAll() {
            List<Runnable> copy = new ArrayList<>(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }

        synchronized int size() {
            return queued.size();
        }
    }

    /** Stored inventory the test edits; stamps come from the interface default (derived from the runs). */
    private static final class FakeInventory implements DeviceInventoryRepository {
        final Map<String, InventoryRun> runs = new ConcurrentHashMap<>();
        final AtomicInteger fullReads = new AtomicInteger();

        void put(String deviceId, String runId, Instant at) {
            runs.put(deviceId, new InventoryRun(runId, deviceId, "job-" + runId, at, 0, List.of()));
        }

        @Override public void recordRun(InventoryRun run, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<InventoryRun> findLatestRun(String deviceId) {
            return Optional.ofNullable(runs.get(deviceId));
        }

        @Override public List<InventoryRun> findLatestRuns(List<String> deviceIds) {
            fullReads.incrementAndGet();
            return deviceIds.stream().map(runs::get).filter(r -> r != null).toList();
        }

        /** Run identities only, as the jOOQ implementation: no full read. */
        @Override public List<RunStamp> findLatestRunStamps(List<String> deviceIds) {
            return deviceIds.stream().map(runs::get).filter(r -> r != null)
                    .map(r -> new RunStamp(r.deviceId(), r.runId(), r.collectedAt())).toList();
        }
    }

    private static DeviceSummaryRecord gateway(String id) {
        return new DeviceSummaryRecord(id, "gateway", TopologyInput.VENDOR_CHECK_POINT, DeviceEnrollmentState.ENROLLED,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private final MovableClock clock = new MovableClock();
    private final InMemoryTopologyRepository repository = new InMemoryTopologyRepository();
    private final FakeInventory inventory = new FakeInventory();
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final TopologyService service = new TopologyService(devices, inventory, repository, clock);

    private TopologyAutoRebuild auto(Executor executor) {
        return new TopologyAutoRebuild(service, clock, executor, CHECK, GAP);
    }

    @Test
    void startupRebuildRunsOnceInTheBackgroundAsTheSystemActor() {
        when(devices.listAll()).thenReturn(List.of(gateway("dev-g")));
        inventory.put("dev-g", "run-1", clock.instant());
        var executor = new QueuedExecutor();
        var auto = auto(executor);

        auto.onApplicationReady();
        auto.onApplicationReady(); // a repeated event (or a second trigger) must not queue a second build
        assertEquals(1, executor.size());
        assertTrue(service.current().isEmpty(), "startup returns before the build");
        verifyNoInteractions(devices);

        executor.runAll();
        assertTrue(service.current().isPresent());
        assertEquals(1, repository.snapshots.size());
        assertEquals(TopologyAutoRebuild.SYSTEM_ACTOR, repository.snapshots.get(0).builtBy());
        assertEquals("system:topology-rebuild", service.latestSnapshot().orElseThrow().builtBy());

        auto.onApplicationReady();
        executor.runAll();
        assertEquals(1, repository.snapshots.size(), "the startup rebuild happens once");
    }

    @Test
    void aFailedStartupRebuildLeavesTheTopologyNotBuiltAndDoesNotThrow() {
        when(devices.listAll()).thenThrow(new IllegalStateException("database unavailable"));
        var executor = new QueuedExecutor();
        auto(executor).onApplicationReady();
        assertDoesNotThrow(executor::runAll);
        assertTrue(service.current().isEmpty());
        assertTrue(repository.snapshots.isEmpty());

        // An over-limit estate is a refusal, not a build: still NOT_BUILT.
        List<DeviceSummaryRecord> many = new ArrayList<>();
        for (int i = 0; i <= TopologyService.MAX_DEVICES; i++) {
            many.add(gateway("dev-" + i));
        }
        doReturn(many).when(devices).listAll();
        var second = new QueuedExecutor();
        new TopologyAutoRebuild(service, clock, second, CHECK, GAP).onApplicationReady();
        assertDoesNotThrow(second::runAll);
        assertTrue(service.current().isEmpty());
        assertTrue(repository.snapshots.isEmpty());
    }

    @Test
    void anInventoryChangeTriggersOneDebouncedRebuild() {
        when(devices.listAll()).thenReturn(List.of(gateway("dev-g")));
        inventory.put("dev-g", "run-1", clock.instant());
        service.rebuild("synthetic-actor"); // a person built it
        var executor = new QueuedExecutor();
        var auto = auto(executor);

        // Same inventory: the check runs, finds nothing changed and builds nothing; it reads run identities only.
        clock.advance(GAP.plusSeconds(1));
        int fullReadsBefore = inventory.fullReads.get();
        service.requestRefreshCheck();
        executor.runAll();
        assertEquals(1, repository.snapshots.size());
        assertEquals(fullReadsBefore, inventory.fullReads.get());

        // A new inventory run lands (the worker wrote it); reads inside the check interval do not even look.
        inventory.put("dev-g", "run-2", clock.instant().plusSeconds(30));
        service.requestRefreshCheck();
        assertEquals(0, executor.size());

        // After the check interval the next read starts exactly one rebuild; the same read is answered as held.
        clock.advance(CHECK.plusSeconds(1));
        String heldBefore = service.current().orElseThrow().snapshotId();
        service.requestRefreshCheck();
        service.requestRefreshCheck();
        assertEquals(1, executor.size());
        assertEquals(heldBefore, service.current().orElseThrow().snapshotId());
        executor.runAll();
        assertEquals(2, repository.snapshots.size());
        assertEquals(TopologyAutoRebuild.SYSTEM_ACTOR, repository.snapshots.get(0).builtBy());
        assertNotEquals(heldBefore, service.current().orElseThrow().snapshotId());

        // Built; the fingerprint now matches, so later checks build nothing.
        clock.advance(GAP.plusSeconds(1));
        service.requestRefreshCheck();
        executor.runAll();
        assertEquals(2, repository.snapshots.size());
    }

    @Test
    void aChangeWithinTheMinimumGapOfTheLastBuildWaits() {
        when(devices.listAll()).thenReturn(List.of(gateway("dev-g")));
        inventory.put("dev-g", "run-1", clock.instant());
        service.rebuild("synthetic-actor");
        var executor = new QueuedExecutor();
        auto(executor);

        inventory.put("dev-g", "run-2", clock.instant().plusSeconds(1));
        clock.advance(CHECK.plusSeconds(1)); // before the gap since the manual build
        service.requestRefreshCheck();
        executor.runAll();
        assertEquals(1, repository.snapshots.size(), "debounced: the manual build was less than the gap ago");

        clock.advance(GAP);
        service.requestRefreshCheck();
        executor.runAll();
        assertEquals(2, repository.snapshots.size());
    }

    @Test
    void whenNothingIsHeldAReadStartsABuildAfterTheGapAndAFailureDoesNotHammer() {
        when(devices.listAll()).thenThrow(new IllegalStateException("database unavailable"));
        var executor = new QueuedExecutor();
        var auto = auto(executor);
        auto.onApplicationReady();
        executor.runAll();
        assertTrue(service.current().isEmpty());

        clock.advance(CHECK.plusSeconds(1));
        service.requestRefreshCheck();
        executor.runAll();
        verify(devices, times(1)).listAll(); // inside the gap since the failed attempt: no retry

        reset(devices);
        doReturn(List.of(gateway("dev-g"))).when(devices).listAll();
        inventory.put("dev-g", "run-1", clock.instant());
        clock.advance(GAP);
        service.requestRefreshCheck();
        executor.runAll();
        assertTrue(service.current().isPresent());
        assertEquals(TopologyAutoRebuild.SYSTEM_ACTOR, repository.snapshots.get(0).builtBy());
    }

    @Test
    void concurrentTriggersCollapseIntoOneBuild() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger listings = new AtomicInteger();
        when(devices.listAll()).thenAnswer(call -> {
            listings.incrementAndGet();
            inside.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return List.of(gateway("dev-g"));
        });
        inventory.put("dev-g", "run-1", clock.instant());
        AtomicInteger submitted = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(4);
        Executor counting = task -> {
            submitted.incrementAndGet();
            pool.execute(task);
        };
        var auto = new TopologyAutoRebuild(service, clock, counting, Duration.ZERO, Duration.ZERO);
        try {
            auto.onApplicationReady();
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            // A burst of reads and a second startup event while the first build is still running.
            var burst = Executors.newFixedThreadPool(8);
            for (int i = 0; i < 40; i++) {
                burst.execute(service::requestRefreshCheck);
                burst.execute(auto::onApplicationReady);
            }
            burst.shutdown();
            assertTrue(burst.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, submitted.get());
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(1, listings.get());
            assertEquals(1, repository.snapshots.size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anAutomaticBuildStandsDownWhileAManualRebuildHoldsTheLock() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger listings = new AtomicInteger();
        when(devices.listAll()).thenAnswer(call -> {
            if (listings.incrementAndGet() == 1) {
                inside.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return List.of();
        });
        var manual = new Thread(() -> service.rebuild("synthetic-actor"));
        manual.start();
        try {
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            var executor = new QueuedExecutor();
            auto(executor).onApplicationReady();
            assertDoesNotThrow(executor::runAll);
            assertEquals(1, listings.get() , "the automatic build refused at the lock and did not read inventory");
        } finally {
            release.countDown();
            manual.join(10_000);
        }
        assertEquals(1, repository.snapshots.size());
        assertEquals("synthetic-actor", repository.snapshots.get(0).builtBy());
    }

    @Test
    void aReadWithNoAutomaticRebuildAttachedIsUnaffected() {
        assertDoesNotThrow(service::requestRefreshCheck);
        verifyNoInteractions(devices);
    }
}
