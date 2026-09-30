package com.securityexpert.nexus.ui2.service.failover;

import java.time.Clock;
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
    private final Duration cadence;
    private final Clock clock;
    // ponytail: restart resets the pass; persist the cursor if restart continuity is required.
    private Iterator<CpFailoverService.ReadinessTarget> targets;
    private Instant nextPassAt;
    private Instant nextUnitAt;
    private Instant runDeadline;
    private String runId;

    @Autowired
    public CpReadinessScheduler(CpFailoverService service, JooqCpFailoverRepository store,
            @Value("${ui2.failover.readiness-unit-pause-ms:2000}") long unitPauseMs,
            @Value("${ui2.failover.readiness-cadence-ms:14400000}") long cadenceMs) {
        this(service, store, unitPauseMs, cadenceMs, Clock.systemUTC());
    }

    CpReadinessScheduler(CpFailoverService service, JooqCpFailoverRepository store,
            long unitPauseMs, long cadenceMs, Clock clock) {
        this.service = service;
        this.store = store;
        this.unitPause = Duration.ofMillis(Math.max(0, unitPauseMs));
        this.cadence = Duration.ofMillis(Math.max(0, cadenceMs));
        this.clock = clock;
        this.nextPassAt = clock.instant().plus(cadence);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void run() {
        Instant now = clock.instant();
        if (targets == null) {
            if (now.isBefore(nextPassAt)) return;
            targets = service.readinessTargets().iterator();
            nextUnitAt = now;
        }
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
            nextPassAt = now.plus(cadence);
            return;
        }
        try {
            runId = service.requestScheduledReadiness(targets.next());
            runDeadline = clock.instant().plus(RUN_CAP);
        } catch (CpFailoverService.Refusal skipped) {
            // Admission remains fail-closed; continue with the next unit on a later tick.
            nextUnitAt = now.plus(unitPause);
        }
    }
}
