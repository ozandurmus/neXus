package com.securityexpert.nexus.ui2.service.device.backup;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The nightly MDS export (V61) at 02:00 Europe/Istanbul, for each Check Point Multi-Domain Server marked as a backup
 * target -- after the weekday evening installs and the 23:00 inventory, when the MDS database lock mds_backup takes
 * disturbs nobody.
 *
 * <p>Never the first run: a server is exported on schedule only after an operator's own MDS export of it completed,
 * so the run time and the lock are measured under watch before the product runs it unattended (PO, 2026-09-23).</p>
 */
@Component
public class MdsExportScheduler {

    private static final Logger LOG = Logger.getLogger(MdsExportScheduler.class.getName());
    public static final String ACTOR = "system:mds-export-scheduler";

    private final TransactionBoundary tx;
    private final BackupCollectService collectService;

    public MdsExportScheduler(TransactionBoundary tx, BackupCollectService collectService) {
        this.tx = tx;
        this.collectService = collectService;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "Europe/Istanbul")
    public void nightly() {
        try {
            List<Record> servers = tx.inTransaction(dsl -> dsl.fetch(
                    "select d.device_id::text as id, exists (select 1 from jobs j where j.target_device_id = d.device_id "
                            + "and j.job_type = 'cp_mds_export' and j.state = 'COMPLETED' "
                            + "and j.submitted_by_actor_fingerprint <> {0}) as measured "
                            + "from devices d where d.role = 'management_server' and d.vendor_hint = 'check_point' "
                            + "and d.backup_target and d.enrollment_state = 'ENROLLED' and not d.disabled", ACTOR));
            for (Record r : servers) {
                String id = r.get("id", String.class);
                if (!Boolean.TRUE.equals(r.get("measured", Boolean.class))) {
                    LOG.info("[MDS_EXPORT_SCHEDULE] skipped a server with no operator-started MDS export yet (first run is watched)");
                    continue;
                }
                BackupCollectService.Outcome o = collectService.requestCollect(id, ACTOR,
                        "Scheduled nightly MDS export (02:00)", Optional.empty(), "mds_export");
                LOG.info("[MDS_EXPORT_SCHEDULE] " + o.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            LOG.warning("[MDS_EXPORT_SCHEDULE] failed: " + e.getMessage());
        }
    }
}
