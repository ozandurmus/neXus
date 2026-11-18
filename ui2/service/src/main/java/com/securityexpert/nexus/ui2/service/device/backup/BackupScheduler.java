package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import com.securityexpert.nexus.ui2.service.schedule.FamilySwitch;
import com.securityexpert.nexus.ui2.service.schedule.ScheduledJobFamily;

/**
 * Evaluates daily_backup_cron in Europe/Istanbul, at most once per local day.
 * Starts only at 02:00–02:59; missed days expire instead of becoming a backlog.
 */
public final class BackupScheduler {

    private static final Logger LOG = Logger.getLogger(BackupScheduler.class.getName());
    public static final String ACTOR = ScheduledBackupWindowPolicy.ACTOR;
    public static final String ACTION_SLOT_CLAIMED = "backup_schedule_slot_claimed";

    private final BackupPolicyRepository policyRepository;
    private final BackupCollectService collectService;
    private final FamilySwitch switches;

    public BackupScheduler(BackupPolicyRepository policyRepository, BackupCollectService collectService) {
        this(policyRepository, collectService, FamilySwitch.ALWAYS_ON);
    }

    public BackupScheduler(BackupPolicyRepository policyRepository, BackupCollectService collectService, FamilySwitch switches) {
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.collectService = Objects.requireNonNull(collectService, "collectService");
        this.switches = Objects.requireNonNull(switches, "switches");
    }

    private volatile java.time.LocalDate canaryDay;

    /** Minutes after the window opens (02:00) before the MDS export may be requested: 02:30. */
    public static final int MDS_EXPORT_DELAY_MINUTES = 30;
    private MdsExportEligibility mdsEligibility;
    private java.util.function.BooleanSupplier mdsExportSwitch = () -> false;
    private final java.util.Set<String> mdsRequestedTonight = new java.util.HashSet<>();
    private java.time.LocalDate mdsDay;

    /**
     * The scheduled MDS export (PO 2026-11-18). {@code enabled} is UI2_CP_MDS_EXPORT_SCHEDULE (code default off).
     */
    public BackupScheduler withMdsExport(MdsExportEligibility eligibility, java.util.function.BooleanSupplier enabled) {
        this.mdsEligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.mdsExportSwitch = Objects.requireNonNull(enabled, "enabled");
        return this;
    }

    /**
     * Called every minute of the backup window, after {@link #tick}. From 02:30 on, and only on a night whose fleet run
     * was claimed (the Gaia backups of that night were requested), each Check Point management server that is a backup
     * target and has no non-terminal job gets one MDS export request; once requested it is never requested again that
     * night (TAC: serialise the Gaia backup and mds_backup on one server). No retry after a failure.
     *
     * @return the number of exports admitted by this call
     */
    public int mdsExportTick(Instant now) {
        if (mdsEligibility == null || !mdsExportSwitch.getAsBoolean()) return 0;
        if (!ScheduledBackupWindowPolicy.isOpen(now)) return 0;
        if (now.isBefore(ScheduledBackupWindowPolicy.start(now).plusMinutes(MDS_EXPORT_DELAY_MINUTES).toInstant())) return 0;
        Optional<BackupPolicy> policy = policyRepository.find();
        if (policy.isEmpty() || !policy.get().scheduleEnabled()) return 0;
        var localDay = now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        Instant dayStart = localDay.atStartOfDay(JobWindowPolicy.ZONE_ID).toInstant();
        if (policy.get().lastScheduledRunAt().filter(last -> !last.isBefore(dayStart)).isEmpty()) return 0;
        if (!switches.isOn(ScheduledJobFamily.SCHEDULED_BACKUP)) return 0;
        if (!localDay.equals(mdsDay)) {
            mdsDay = localDay;
            mdsRequestedTonight.clear();
        }
        int admitted = 0;
        for (String deviceId : mdsEligibility.dueTargets(dayStart)) {
            if (!mdsRequestedTonight.add(deviceId)) continue;
            if (collectService.requestScheduledMdsExport(deviceId)) {
                admitted++;
                LOG.info("[BACKUP_SCHEDULE] MDS export requested for " + localDay);
            }
        }
        return admitted;
    }

    /**
     * The nightly credential canary (V207): from 00:45 to 00:59 local, while the schedule and the scheduled-backup family
     * are on, admits one read-only {@code credential_canary} per backup credential (Check Point only in this release).
     * The key is per device and day, so every minute of the span after the first admits nothing new.
     *
     * @return the number of canary jobs admitted or already present
     */
    public int canaryTick(Instant now) {
        Optional<BackupPolicy> policy = policyRepository.find();
        if (policy.isEmpty() || !policy.get().scheduleEnabled()) return 0;
        if (!ScheduledBackupWindowPolicy.isCanaryTime(now)) return 0;
        if (!switches.isOn(ScheduledJobFamily.SCHEDULED_BACKUP)) return 0;
        java.time.LocalDate day = now.atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        if (day.equals(canaryDay)) return 0;
        int admitted = collectService.requestCredentialCanaries(ScheduledBackupWindowPolicy.CANARY_ACTOR, day);
        if (admitted > 0) {
            canaryDay = day;
            LOG.info("[BACKUP_SCHEDULE] credential canary submitted for " + day + ": jobs=" + admitted);
        }
        return admitted;
    }

    /** @return the slot that was run, if one was due and claimed. */
    public Optional<Instant> tick(Instant now) {
        Optional<BackupPolicy> policy = policyRepository.find();
        if (policy.isEmpty() || !policy.get().scheduleEnabled()) {
            return Optional.empty();
        }
        if (!ScheduledBackupWindowPolicy.isOpen(now)) return Optional.empty();
        var localNow = now.atZone(JobWindowPolicy.ZONE_ID);
        Instant dayStart = localNow.toLocalDate().atStartOfDay(JobWindowPolicy.ZONE_ID).toInstant();
        if (policy.get().lastScheduledRunAt().filter(last -> !last.isBefore(dayStart)).isPresent())
            return Optional.empty();
        // Only today's occurrence can be due; a missed 02:00 can run once before 03:00.
        Optional<Instant> due = BackupScheduleDue.dueSlot(policy.get().dailyBackupCron(),
                policy.get().lastScheduledRunAt(), now, 3600, JobWindowPolicy.ZONE_ID);
        if (due.isEmpty()) return Optional.empty();
        if (due.get().isBefore(ScheduledBackupWindowPolicy.start(now).toInstant())) return Optional.empty();
        // V203: the family's switch, checked before the day is claimed, so switching it back on inside 02:00-02:59 still runs today.
        if (!switches.isOn(ScheduledJobFamily.SCHEDULED_BACKUP)) {
            // One line per night, not one per minute of the 02:00 window.
            if (now.isBefore(ScheduledBackupWindowPolicy.start(now).plusMinutes(1).toInstant()))
                LOG.info("[BACKUP_SCHEDULE] skipped: scheduled family scheduled_backup is switched OFF");
            return Optional.empty();
        }
        // Fence the whole calendar day, even if another instance read an older policy or a more frequent cron.
        if (!policyRepository.claimScheduledRun(dayStart, now, ACTOR, ACTION_SLOT_CLAIMED)) {
            return Optional.empty(); // another instance took it, or the schedule was just disabled
        }
        Optional<Instant> slot = Optional.of(ScheduledBackupWindowPolicy.start(now).toInstant());
        BackupCollectService.BulkOutcome outcome = collectService.requestCollectAll(ACTOR,
                "Scheduled fleet backup (slot " + slot.get() + ")");
        LOG.info("[BACKUP_SCHEDULE] slot " + slot.get() + ": targets=" + outcome.targets() + " admitted="
                + outcome.admitted() + " refused=" + outcome.refused() + " held_by_credential_alert=" + outcome.held());
        // The MDS export is not requested here: mdsExportTick asks for it from 02:30, after the Gaia backup is terminal
        // (PO 2026-11-18, "al"; amends the 2026-11-11 "by hand only" decision).
        return slot;
    }
}
