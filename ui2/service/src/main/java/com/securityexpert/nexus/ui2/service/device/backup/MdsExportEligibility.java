package com.securityexpert.nexus.ui2.service.device.backup;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Which Check Point management servers may be sent the scheduled MDS export right now (PO 2026-11-18, "al").
 * Check Point TAC: the Gaia backup and mds_backup on one server must be serialised, so a device is due only while it
 * has no non-terminal job of any type (backup, export, policy collection ...) and no cp_mds_export job at all since
 * the start of the local day (one export per night, whoever started it, no retry after a failure).
 */
@Component
public final class MdsExportEligibility {
    private static final String TERMINAL_JOB_STATES =
            "('COMPLETED','FAILED','REJECTED','CANCELLED','OUTCOME_UNKNOWN','RECONCILED')";
    private final TransactionBoundary tx;

    public MdsExportEligibility(TransactionBoundary tx) {
        this.tx = tx;
    }

    /** @param dayStart the start of the current local (Europe/Istanbul) day */
    public Set<String> dueTargets(Instant dayStart) {
        return tx.inTransaction(dsl -> dsl.fetch(
                "select d.device_id::text as id from devices d where d.role = 'management_server' "
                        + "and d.vendor_hint = 'check_point' and d.backup_target "
                        + "and d.enrollment_state = 'ENROLLED' and not d.disabled "
                        + "and not exists (select 1 from jobs j where j.target_device_id = d.device_id "
                        + "and j.state not in " + TERMINAL_JOB_STATES + ") "
                        + "and not exists (select 1 from jobs j where j.target_device_id = d.device_id "
                        + "and j.job_type = 'cp_mds_export' and j.submitted_at >= {0})", Timestamp.from(dayStart))
                .stream().map(row -> row.get("id", String.class)).collect(Collectors.toSet()));
    }
}
