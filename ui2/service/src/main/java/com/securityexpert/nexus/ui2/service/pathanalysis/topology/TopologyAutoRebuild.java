package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * System-actor topology rebuilds (PATH_ANALYSIS_DESIGN.md §5 amendment 2026-11-04, "auto rebuild"). The held graph is
 * in memory and is lost on every restart, and only the security_admin rebuild built it, so path queries answered 409
 * {@code TOPOLOGY_NOT_BUILT} until a person acted. Two triggers close that, both as {@link #SYSTEM_ACTOR}:
 *
 * <ol>
 *   <li><b>Startup:</b> once, after the application is ready, on a background thread. A failure is logged by code only
 *       and leaves the topology not built (the read keeps answering 409); the service starts either way.</li>
 *   <li><b>Inventory change:</b> inventory is persisted by the worker only, so the service has no commit hook to
 *       observe. Instead a read of the topology or a path query by a non-replay viewer raises a debounced check: at
 *       most one check per {@code checkInterval}; if the stored inventory fingerprint differs from the held graph's
 *       (or no graph is held) and the last attempt was at least {@code minRebuildGap} ago, one background rebuild is
 *       started. The read itself is always answered from the graph held at that moment. Replay viewers (aiview) never
 *       raise the check, so an aiview query still has no side effect (D-3 condition 4, D-8).</li>
 * </ol>
 *
 * <p>Single flight: at most one automatic task is queued or running, and the build itself takes the service's rebuild
 * lock, so a concurrent manual rebuild makes the automatic one stand down (and the reverse answers 409 as before). The
 * build reads stored inventory under the existing input-size limits and never contacts a device. The manual
 * rebuild, its RBAC and the aiview refusal are unchanged.</p>
 */
public final class TopologyAutoRebuild implements TopologyService.RefreshTrigger {

    public static final String SYSTEM_ACTOR = "system:topology-rebuild";
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_MIN_REBUILD_GAP = Duration.ofMinutes(5);

    private static final System.Logger LOG = System.getLogger(TopologyAutoRebuild.class.getName());
    private static final long NEVER = Long.MIN_VALUE;

    private final TopologyService service;
    private final Clock clock;
    private final Executor executor;
    private final long checkIntervalMillis;
    private final long minRebuildGapMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicLong lastCheck = new AtomicLong(NEVER);
    private final AtomicLong lastAttempt = new AtomicLong(NEVER);

    public TopologyAutoRebuild(TopologyService service, Clock clock, Executor executor, Duration checkInterval,
            Duration minRebuildGap) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.checkIntervalMillis = Objects.requireNonNull(checkInterval, "checkInterval").toMillis();
        this.minRebuildGapMillis = Objects.requireNonNull(minRebuildGap, "minRebuildGap").toMillis();
        service.attachRefreshTrigger(this);
    }

    /** Runs once per application; the work is on the executor, so startup never waits for it. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (started.compareAndSet(false, true)) {
            launch(this::startupRebuild);
        }
    }

    @Override
    public void topologyRead() {
        long now = clock.millis();
        long previous = lastCheck.get();
        if (previous != NEVER && now - previous < checkIntervalMillis) {
            return;
        }
        if (lastCheck.compareAndSet(previous, now)) {
            launch(this::changeCheck);
        }
    }

    /** Starts {@code task} unless one is already queued or running; true when it was started. */
    private boolean launch(Runnable task) {
        if (!inFlight.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "[TOPOLOGY_AUTO_REBUILD] task failed: {0}",
                            e.getClass().getSimpleName());
                } finally {
                    inFlight.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.set(false);
            return false;
        }
    }

    private void startupRebuild() {
        if (service.current().isPresent()) {
            return; // a person rebuilt first
        }
        rebuild("startup");
    }

    private void changeCheck() {
        var held = service.current();
        long sinceLast = clock.millis() - lastAttempt.get();
        if (lastAttempt.get() != NEVER && sinceLast < minRebuildGapMillis) {
            return;
        }
        String live;
        try {
            live = service.liveInventoryFingerprint();
        } catch (TopologyService.Refusal refused) {
            LOG.log(System.Logger.Level.WARNING, "[TOPOLOGY_AUTO_REBUILD] check refused: {0}", refused.code());
            return;
        }
        if (held.isPresent() && held.get().fingerprints().inventory().equals(live)) {
            return;
        }
        if (held.isPresent() && clock.instant().minus(Duration.ofMillis(minRebuildGapMillis))
                .isBefore(held.get().builtAt())) {
            return; // a build (manual included) ran within the gap
        }
        rebuild(held.isPresent() ? "inventory changed" : "not built");
    }

    private void rebuild(String reason) {
        lastAttempt.set(clock.millis());
        try {
            var rebuilt = service.rebuild(SYSTEM_ACTOR);
            LOG.log(System.Logger.Level.INFO, "[TOPOLOGY_AUTO_REBUILD] built ({0}) in {1} ms", reason,
                    rebuilt.header().buildMillis());
        } catch (TopologyService.Refusal refused) {
            LOG.log(System.Logger.Level.WARNING, "[TOPOLOGY_AUTO_REBUILD] not built ({0}): {1}", reason, refused.code());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[TOPOLOGY_AUTO_REBUILD] failed ({0}): {1}", reason,
                    e.getClass().getSimpleName());
        }
    }
}
