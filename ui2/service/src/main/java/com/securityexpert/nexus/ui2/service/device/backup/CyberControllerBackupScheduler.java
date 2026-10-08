package com.securityexpert.nexus.ui2.service.device.backup;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Submits previously permitted backups at the shared six-hour slots. */
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

    @Scheduled(cron = JobWindowPolicy.CRON, zone = JobWindowPolicy.ZONE)
    public void nightly() {
        try {
            List<Record> controllers = tx.inTransaction(dsl -> dsl.fetch(
                    "select d.device_id::text as id from devices d where d.role = 'management_server' and d.vendor_hint = 'radware' "
                            + "and d.backup_target and d.enrollment_state = 'ENROLLED' and not d.disabled"));
            for (Record r : controllers) {
                if (!JobWindowPolicy.SYSTEM.isOpen()) break;
                BackupCollectService.Outcome o = collectService.requestCollect(r.get("id", String.class), ACTOR,
                        "Scheduled Cyber Controller backup", Optional.empty(), "backup");
                LOG.info("[CC_BACKUP_SCHEDULE] " + o.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            LOG.warning("[CC_BACKUP_SCHEDULE] failed: " + e.getMessage());
        }
    }
}
