package com.securityexpert.nexus.ui2.persistence.jobrecords;

import java.sql.Timestamp;

import org.jooq.DSLContext;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/**
 * Records an administrator's manual admission on the job row it created (PO decision 2026-10-20, V168), in the same
 * audited transaction as the insert, so the audit ledger carries it: which admin role let the job past the window,
 * whether it was submitted inside business hours, and -- inside them -- who acknowledged the warning and when. A row
 * with {@code window_bypass_role} set is claimable outside the job windows; every other row keeps the windows.
 *
 * <p>No grant for {@code actor} on this thread (operator, aiview, any scheduler or worker): nothing is written.</p>
 */
public final class ManualAdmissionRecord {

    static final String SQL = "update jobs set window_bypass_role = {1}, submitted_in_business_hours = {2}, "
            + "business_hours_ack_by = {3}, business_hours_ack_at = {4} where job_id = {0} and window_bypass_role is null";

    private ManualAdmissionRecord() { }

    public static void record(DSLContext dsl, String jobId, String actor, JobWindowPolicy windows) {
        if (jobId == null) return;
        var grant = ManualAdmission.currentFor(actor);
        if (grant.isEmpty()) return;
        boolean inside = windows.insideBusinessHours();
        // The hours may have begun since the admission check: refuse (the insert rolls back) rather than record an
        // unacknowledged job inside them.
        if (inside && !grant.get().acknowledged())
            throw new ManualAdmission.BusinessHoursConfirmRequired(windows.businessHours());
        boolean acknowledged = inside && grant.get().acknowledged();
        dsl.execute(SQL, jobId, grant.get().role(), inside, acknowledged ? actor : null,
                acknowledged ? Timestamp.from(windows.now()) : null);
    }
}
