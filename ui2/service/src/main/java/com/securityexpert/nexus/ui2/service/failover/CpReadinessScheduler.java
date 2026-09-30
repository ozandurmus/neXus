package com.securityexpert.nexus.ui2.service.failover;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;

/** Serially admits and drains one readiness run at a time. */
@Component
public final class CpReadinessScheduler {
    private final CpFailoverService service;
    private final JooqCpFailoverRepository store;
    private final Duration unitPause;

    public CpReadinessScheduler(CpFailoverService service, JooqCpFailoverRepository store,
            @Value("${ui2.failover.readiness-unit-pause-ms:2000}") long unitPauseMs) {
        this.service=service; this.store=store; this.unitPause=Duration.ofMillis(Math.max(0,unitPauseMs));
    }

    @Scheduled(fixedDelayString="${ui2.failover.readiness-cadence-ms:14400000}",
        initialDelayString="${ui2.failover.readiness-cadence-ms:14400000}")
    public void run() {
        for (var target:service.readinessTargets()) {
            try {
                String runId=service.requestScheduledReadiness(target);
                while (!store.finished(runId)) Thread.sleep(1000);
                Thread.sleep(unitPause.toMillis());
            } catch (CpFailoverService.Refusal skipped) {
                if ("RUN_ALREADY_ACTIVE".equals(skipped.code())) continue;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
