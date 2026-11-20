package com.securityexpert.nexus.ui2.service.script;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.scheduling.annotation.Scheduled;

import com.securityexpert.nexus.ui2.persistence.script.ScriptRecord;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRunRecord;
import com.securityexpert.nexus.ui2.service.device.backup.BackupScheduleDue;

/**
 * Scheduled execution trigger for operator scripts ({@code SCRIPT_EXECUTION_CONTRACT.md} section 3.4):
 * <ul>
 *   <li>{@code @Scheduled} tick every 60 seconds,</li>
 *   <li>1-hour ({@link #CATCH_UP_WINDOW_SECONDS} = 3600 s) catch-up window so a missed run is not replayed repeatedly
 *       after an outage,</li>
 *   <li>fenced slot claim on {@code (script_id, slot)} via {@link ScriptExecutionService#runScheduledSlot},</li>
 *   <li>one {@code script_run} job per fire, and {@code enabled = false} fires nothing.</li>
 * </ul>
 */
public final class ScriptScheduler {

    private static final Logger LOG = Logger.getLogger(ScriptScheduler.class.getName());

    /** One-hour catch-up window (3600 seconds) per contract section 3.4. */
    public static final long CATCH_UP_WINDOW_SECONDS = 3600L;

    private final ScriptRepository scriptRepository;
    private final ScriptExecutionService scriptExecutionService;
    private final Clock clock;
    private final java.time.ZoneId zone;

    public ScriptScheduler(ScriptRepository scriptRepository, ScriptExecutionService scriptExecutionService) {
        this(scriptRepository, scriptExecutionService, Clock.systemUTC());
    }

    public ScriptScheduler(
            ScriptRepository scriptRepository,
            ScriptExecutionService scriptExecutionService,
            Clock clock) {
        this(scriptRepository, scriptExecutionService, clock, ScriptSchedule.zone());
    }

    /** {@code zone}: the zone five-field cron is read in (default Europe/Istanbul, {@code UI2_SCRIPT_SCHEDULE_ZONE}). */
    public ScriptScheduler(
            ScriptRepository scriptRepository,
            ScriptExecutionService scriptExecutionService,
            Clock clock,
            java.time.ZoneId zone) {
        this.scriptRepository = Objects.requireNonNull(scriptRepository, "scriptRepository");
        this.scriptExecutionService = Objects.requireNonNull(scriptExecutionService, "scriptExecutionService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Scheduled(
            fixedDelayString = "${ui2.script.scheduler.interval-ms:60000}",
            initialDelayString = "${ui2.script.scheduler.initial-delay-ms:30000}")
    public void tick() {
        evaluateAt(clock.instant());
    }

    /**
     * 30-day retention pruner for {@code script_output} artefacts on the report-store claim and inline
     * {@code script_run.output_tail} ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 2 and 4.3).
     */
    @Scheduled(
            fixedDelayString = "${ui2.script.retention.interval-ms:3600000}",
            initialDelayString = "${ui2.script.retention.initial-delay-ms:60000}")
    public int pruneExpiredOutputs() {
        try {
            return scriptExecutionService.pruneExpiredOutputs(clock.instant());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "script_output_retention_prune_error", e);
            return 0;
        }
    }

    /**
     * Evaluates all scripts at {@code now}, claiming due slots and submitting at most one {@code script_run} job per
     * due script. Returns the count of newly admitted scheduled runs.
     */
    public int evaluateAt(Instant now) {
        Objects.requireNonNull(now, "now");
        List<ScriptRecord> scripts = scriptRepository.findAll();
        int fired = 0;
        for (ScriptRecord script : scripts) {
            if (!script.enabled() || script.schedule().isEmpty()) {
                continue;
            }
            String cron = script.schedule().get();
            if (cron.isBlank()) {
                continue;
            }
            try {
                Optional<Instant> lastSlot = scriptRepository.findLastScheduledSlot(script.scriptId());
                Optional<Instant> dueSlot = BackupScheduleDue.dueSlot(
                        cron,
                        lastSlot,
                        now,
                        CATCH_UP_WINDOW_SECONDS,
                        zone);
                if (dueSlot.isPresent()) {
                    Optional<ScriptRunRecord> admitted =
                            scriptExecutionService.runScheduledSlot(script, dueSlot.get());
                    if (admitted.isPresent()) {
                        fired++;
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "script_scheduler_tick_error: script_id=" + script.scriptId(), e);
            }
        }
        return fired;
    }
}
