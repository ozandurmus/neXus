package com.securityexpert.nexus.ui2.service.schedule;

import java.time.Clock;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.schedule.ScheduledJobFamilyRepository;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/** Wires the per-family scheduled-job switches (V203) and their one-minute queued-job sweep. */
@Configuration(proxyBeanMethods = false)
public class ScheduledJobsConfiguration {

    @Bean
    public ScheduledJobSwitches scheduledJobSwitches(TransactionBoundary transactions, BackupPolicyRepository backupPolicy,
            @Value("${ui2.scheduling.enabled:true}") boolean masterEnabled,
            @Value("${ui2.system-log.scheduled-collection.enabled:false}") boolean systemLogEnabled,
            @Value("${ui2.system-log.scheduled-collection.independent:false}") boolean systemLogIndependent) {
        return new ScheduledJobSwitches(new ScheduledJobFamilyRepository(transactions),
                new ScheduledJobSwitches.Deployment(masterEnabled, systemLogEnabled, systemLogIndependent),
                JobWindowPolicy.SYSTEM, BusinessHours.SYSTEM, Clock.systemUTC(),
                () -> backupPolicy.find().map(BackupPolicyRepository.BackupPolicy::scheduleEnabled));
    }

    @Bean
    public QueuedJobSweep scheduledJobQueuedJobSweep(ScheduledJobSwitches switches) {
        return new QueuedJobSweep(switches);
    }

    /**
     * Once a minute: cancels queued jobs that a switched-off family's scheduler submitted after the switch (a tick that
     * read "on" an instant before it). Runs only while Spring scheduling is enabled, like the schedulers themselves.
     */
    public static final class QueuedJobSweep {
        private static final Logger LOG = Logger.getLogger(QueuedJobSweep.class.getName());
        private final ScheduledJobSwitches switches;

        QueuedJobSweep(ScheduledJobSwitches switches) {
            this.switches = switches;
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        public void sweep() {
            try {
                switches.sweep();
            } catch (RuntimeException e) {
                LOG.warning("[SCHEDULED_JOBS] sweep failed: " + e.getClass().getSimpleName());
            }
        }
    }
}
