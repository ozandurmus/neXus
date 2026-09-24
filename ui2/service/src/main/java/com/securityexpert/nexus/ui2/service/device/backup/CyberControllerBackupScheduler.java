package com.securityexpert.nexus.ui2.service.device.backup;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The nightly Radware Cyber Controller configuration backup (V69) at 03:00 Europe/Istanbul -- after the 02:00 MDS
 * export -- for each Cyber Controller marked as a backup target (PO, 2026-09-24). A ~35 MB export that takes seconds;
 * the Cyber Controller's own scheduler-generated backups are never touched.
 */
@Component
public class CyberControllerBackupScheduler {

    private static final Logger LOG = Logger.getLogger(CyberControllerBackupScheduler.class.getName());
    public static final String ACTOR = "system:cyber-controller-backup-scheduler";

    private final TransactionBoundary tx;
    private final BackupCollectService collectService;

    public CyberControllerBackupScheduler(TransactionBoundary tx, BackupCollectService collectService) {
        this.tx = tx;
        this.collectService = collectService;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Istanbul")
    public void nightly() {
        try {
            List<Record> controllers = tx.inTransaction(dsl -> dsl.fetch(
                    "select d.device_id::text as id from devices d where d.role = 'management_server' and d.vendor_hint = 'radware' "
                            + "and d.backup_target and d.enrollment_state = 'ENROLLED' and not d.disabled"));
            for (Record r : controllers) {
                BackupCollectService.Outcome o = collectService.requestCollect(r.get("id", String.class), ACTOR,
                        "Scheduled nightly Cyber Controller backup (03:00)", Optional.empty(), "backup");
                LOG.info("[CC_BACKUP_SCHEDULE] " + o.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            LOG.warning("[CC_BACKUP_SCHEDULE] failed: " + e.getMessage());
        }
    }
}
