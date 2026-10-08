package com.securityexpert.nexus.ui2.service.failover;

import java.time.Clock;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;

/** Advances one readiness pass without waiting on Spring's scheduling thread. */
@Component
public final class CpReadinessScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(CpReadinessScheduler.class);
    private static final Duration RUN_CAP = Duration.ofMinutes(10);
    private final CpFailoverService service;
    private final JooqCpFailoverRepository store;
    private final Duration unitPause;
    private final JobWindowPolicy windows;
    private final Clock clock;
    // A restart waits for the next slot; ticks never create another readiness pass.
    private Iterator<CpFailoverService.ReadinessTarget> targets;
    private Instant nextUnitAt;
    private Instant runDeadline;
    private String runId;

    @Autowired
    public CpReadinessScheduler(CpFailoverService service, JooqCpFailoverRepository store,
            @Value("${ui2.failover.readiness-unit-pause-ms:2000}") long unitPauseMs) {
        this(service, store, unitPauseMs, Clock.systemUTC());
    }

    CpReadinessScheduler(CpFailoverService service, JooqCpFailoverRepository store,
            long unitPauseMs, Clock clock) {
        this.service = service;
        this.store = store;
        this.unitPause = Duration.ofMillis(Math.max(0, unitPauseMs));
        this.windows = new JobWindowPolicy(clock, JobWindowPolicy.SYSTEM.windowMinutes());
        this.clock = clock;
    }

    @Scheduled(cron = JobWindowPolicy.CRON, zone = JobWindowPolicy.ZONE)
    public void beginPass() {
        if (!windows.isOpen() || targets != null) return;
        targets = service.readinessTargets().iterator();
        nextUnitAt = clock.instant();
        run();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void run() {
        Instant now = clock.instant();
        if (!windows.isOpen(now)) {
            targets = null;
            runId = null; // Only forget the cursor; never cancel the admitted run.
            return;
        }
        if (targets == null) return;
        if (runId != null) {
            if (!now.isBefore(runDeadline)) {
                LOG.warn("Readiness run reached the 10-minute scheduler cap; advancing after the unit pause");
            } else if (!store.finished(runId)) {
                return;
            }
            runId = null;
            nextUnitAt = now.plus(unitPause);
        }
        if (now.isBefore(nextUnitAt)) return;
        if (!targets.hasNext()) {
            targets = null;
            return;
        }
        try {
            runId = service.requestScheduledReadiness(targets.next());
            runDeadline = clock.instant().plus(RUN_CAP);
        } catch (CpFailoverService.Refusal | JobWindowPolicy.OutsideWindow skipped) {
            // Admission remains fail-closed; continue with the next unit on a later tick.
            nextUnitAt = now.plus(unitPause);
        }
    }
}
