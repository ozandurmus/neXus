package com.securityexpert.nexus.ui2.service.notification;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.system.DiskHeadroomPolicy;
import com.securityexpert.nexus.ui2.service.system.DiskHeadroomService;
import com.securityexpert.nexus.ui2.service.system.DiskLevel;
import com.securityexpert.nexus.ui2.service.system.DiskVolumeReading;

/**
 * Disk headroom alerts over the existing syslog notification target (backlog "disk headroom alert"). Every 60 s each
 * measured volume is settled against {@link DiskHeadroomPolicy} (with its hysteresis band) and compared with the level
 * last recorded for it in {@code notification_state} (key {@code disk_headroom_level:<volume>}):
 * <ul>
 * <li>OK -> WARNING sends one WARNING line, OK/WARNING -> CRITICAL one CRITICAL line, a return to OK one INFORMATIONAL
 * line; staying at a level, or stepping CRITICAL -> WARNING, sends nothing (the step only re-arms the critical alert);</li>
 * <li>the new level is recorded only after the line was sent, so a failed send is retried on the next tick and a
 * level that does not change never repeats;</li>
 * <li>a volume that cannot be measured (UNKNOWN) changes nothing and sends nothing.</li>
 * </ul>
 * With syslog disabled nothing is sent and nothing is recorded, so enabling it later reports a condition that still
 * holds. The line carries the volume name, the percentage and the thresholds only.
 */
@Service
public class DiskHeadroomAlerts {

    private static final System.Logger LOG = System.getLogger(DiskHeadroomAlerts.class.getName());

    static final String STATE_KEY_PREFIX = "disk_headroom_level:";
    static final String TAG = "DISK_HEADROOM";
    private static final long LOCK_ID = 10010052L;

    /** What one settled change of level sends. */
    enum Event { NONE, WARNING_CROSSED, CRITICAL_CROSSED, RECOVERED }

    /** The recorded level per volume; the database in production, a map in tests. */
    interface StateStore {
        /** The recorded level, or {@code null} when none is recorded (equivalent to OK). */
        DiskLevel get(String volume);

        void put(String volume, DiskLevel level);
    }

    private final NotificationSettingsStore settingsStore;
    private final TransactionBoundary transactionBoundary;
    private final DiskHeadroomService disk;
    private NotificationForwarder.SyslogSink syslog = SyslogSender::send;

    DiskHeadroomAlerts withSyslog(NotificationForwarder.SyslogSink sink) {
        this.syslog = Objects.requireNonNull(sink, "sink");
        return this;
    }

    public DiskHeadroomAlerts(NotificationSettingsStore settingsStore, TransactionBoundary transactionBoundary,
            DiskHeadroomService disk) {
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.disk = Objects.requireNonNull(disk, "disk");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        try {
            NotificationSettings settings = settingsStore.read();
            if (!settings.syslogEnabled()) {
                return;
            }
            List<DiskVolumeReading> readings = disk.measure();
            transactionBoundary.inTransaction(dsl -> {
                if (!Boolean.TRUE.equals(dsl.fetchValue("select pg_try_advisory_xact_lock(" + LOCK_ID + ")", Boolean.class))) {
                    return null;
                }
                StateStore state = new StateStore() {
                    @Override
                    public DiskLevel get(String volume) {
                        Record row = dsl.fetchOne("select value from notification_state where key = {0}",
                                STATE_KEY_PREFIX + volume);
                        String value = row == null ? null : row.get("value", String.class);
                        try {
                            return value == null ? null : DiskLevel.valueOf(value);
                        } catch (IllegalArgumentException e) {
                            return null;
                        }
                    }

                    @Override
                    public void put(String volume, DiskLevel level) {
                        dsl.execute("insert into notification_state(key, value) values ({0}, {1}) "
                                + "on conflict (key) do update set value = excluded.value, updated_at = now()",
                                STATE_KEY_PREFIX + volume, level.name());
                    }
                };
                process(settings, disk.policy(), readings, state, syslog);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[DISK_HEADROOM_ALERT_FAILED] {0}", e.getClass().getSimpleName());
        }
    }

    /** One pass over the readings; returns the number of lines sent. */
    static int process(NotificationSettings settings, DiskHeadroomPolicy policy, List<DiskVolumeReading> readings,
            StateStore state, NotificationForwarder.SyslogSink sink) {
        int sent = 0;
        for (DiskVolumeReading reading : readings) {
            if (reading.level() == DiskLevel.UNKNOWN || reading.usedPercent() == null) {
                continue;
            }
            DiskLevel recorded = state.get(reading.name());
            DiskLevel settled = policy.settle(recorded, reading.usedPercent());
            if (settled == (recorded == null ? DiskLevel.OK : recorded)) {
                continue;
            }
            Event event = decide(recorded, settled);
            if (event != Event.NONE) {
                try {
                    sink.send(settings, severity(event), TAG, message(event, reading, policy));
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_disk_headroom", "success");
                } catch (IOException e) {
                    com.securityexpert.nexus.ui2.platform.metrics.NexusMetrics.recordNotificationSend("syslog_disk_headroom", "failure");
                    LOG.log(System.Logger.Level.WARNING, "[NOTIFY_SYSLOG_FAILED] disk headroom: {0}", e.getMessage());
                    continue;
                }
                sent++;
            }
            state.put(reading.name(), settled);
        }
        return sent;
    }

    /** What a change from the recorded level ({@code null} = OK) to the settled level sends. */
    static Event decide(DiskLevel recorded, DiskLevel settled) {
        DiskLevel before = recorded == null || recorded == DiskLevel.UNKNOWN ? DiskLevel.OK : recorded;
        if (settled == before || settled == DiskLevel.UNKNOWN) {
            return Event.NONE;
        }
        return switch (settled) {
            case CRITICAL -> Event.CRITICAL_CROSSED;
            case WARNING -> before == DiskLevel.OK ? Event.WARNING_CROSSED : Event.NONE;
            case OK -> Event.RECOVERED;
            case UNKNOWN -> Event.NONE;
        };
    }

    private static SyslogSender.Severity severity(Event event) {
        return switch (event) {
            case CRITICAL_CROSSED -> SyslogSender.Severity.CRITICAL;
            case WARNING_CROSSED -> SyslogSender.Severity.WARNING;
            default -> SyslogSender.Severity.INFORMATIONAL;
        };
    }

    static String message(Event event, DiskVolumeReading reading, DiskHeadroomPolicy policy) {
        String where = "volume " + reading.name() + " is " + reading.usedPercent() + "% used";
        return switch (event) {
            case CRITICAL_CROSSED -> "disk headroom CRITICAL: " + where + " (critical threshold " + policy.criticalPercent() + "%)";
            case WARNING_CROSSED -> "disk headroom WARNING: " + where + " (warning threshold " + policy.warningPercent() + "%)";
            default -> "disk headroom recovered: " + where + " (below the warning threshold " + policy.warningPercent() + "%)";
        };
    }
}
