package com.securityexpert.nexus.ui2.jobs.lease;

/**
 * C2 §4.1's claim statement, verbatim (column names adapted to this
 * movement's {@code V4} migration). One literal, parameterized, atomic
 * {@code UPDATE ... WHERE ... RETURNING} -- there is deliberately no
 * separate {@code SELECT} statement anywhere for finding a claimable row
 * (the {@code SELECT} here is a correlated subquery inside the same single
 * statement, not a second round trip). {@code
 * ClaimIsAtomicNoReadThenWriteTest} (persistence) asserts, textually, that
 * {@code com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao}
 * issues exactly this text and no alternate claim path exists anywhere in
 * that class (contract §8 test 1, AC-3).
 */
public final class ClaimStatementText {

    public static final String SQL = """
            UPDATE jobs
            SET state = 'CLAIMED',
                lease_worker_id = {0},
                lease_epoch = lease_epoch + 1,
                lease_expires_at = now() + ({1} || ' seconds')::interval,
                last_heartbeat_at = now()
            WHERE job_id = (
                SELECT job_id FROM jobs
                WHERE state = 'REQUESTED'
                  AND capability_id = ANY(string_to_array({2}, ','))
                ORDER BY submitted_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING job_id, lease_epoch
            """;

    private ClaimStatementText() {
    }
}
