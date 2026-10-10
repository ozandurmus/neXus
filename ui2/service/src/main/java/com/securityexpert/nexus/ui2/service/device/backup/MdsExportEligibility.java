package com.securityexpert.nexus.ui2.service.device.backup;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

/** The first MDS export remains operator-watched; later daily exports accompany the separate Gaia backup. */
@Component
public final class MdsExportEligibility {
    private final TransactionBoundary tx;

    public MdsExportEligibility(TransactionBoundary tx) {
        this.tx = tx;
    }

    public Set<String> measuredTargets() {
        return tx.inTransaction(dsl -> dsl.fetch(
                "select d.device_id::text as id from devices d where d.role = 'management_server' "
                        + "and d.vendor_hint = 'check_point' and d.backup_target "
                        + "and d.enrollment_state = 'ENROLLED' and not d.disabled "
                        + "and exists (select 1 from jobs j where j.target_device_id = d.device_id "
                        + "and j.job_type = 'cp_mds_export' and j.state = 'COMPLETED' "
                        + "and j.submitted_by_actor_fingerprint <> {0})", ScheduledBackupWindowPolicy.MDS_ACTOR)
                .stream().map(row -> row.get("id", String.class)).collect(Collectors.toSet()));
    }
}
