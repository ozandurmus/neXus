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
            WITH inventory_claim_lock AS (
                SELECT pg_advisory_xact_lock(294611)
            ), owner_control AS MATERIALIZED (
                SELECT m.* FROM module_runtime_control m, inventory_claim_lock
                ORDER BY m.module FOR UPDATE OF m
            )
            UPDATE jobs
            SET state = 'CLAIMED',
                lease_worker_id = {0},
                lease_epoch = lease_epoch + 1,
                lease_expires_at = now() + ({1} || ' seconds')::interval,
                last_heartbeat_at = now(),
                lease_owner_generation = (SELECT generation FROM owner_control WHERE module=ui2_job_module(jobs.capability_id))
            WHERE job_id = (
                SELECT job_id FROM jobs, inventory_claim_lock
                WHERE state = 'REQUESTED'
                  AND clock_timestamp() < CAST({3} AS timestamptz)
                  AND capability_id = ANY(string_to_array({2}, ','))
                  AND job_type = ANY(string_to_array({2}, ','))
                  AND job_type = capability_id
                  AND (admission_not_before IS NULL OR admission_not_before <= now())
                  AND ((SELECT count(*) FROM jobs WHERE state IN ('CLAIMED', 'EXECUTING'))
                    + (SELECT count(*) FROM runtime_task_lease WHERE task_key='fleet.failover.execution' AND owner_role='service')) < 10
                  AND EXISTS (SELECT 1 FROM owner_control m JOIN owner_control r ON r.module=m.effective_owner
                    WHERE m.module=ui2_job_module(jobs.capability_id)
                      AND m.effective_owner=split_part({0}, '-', 1)
                      AND NOT m.drain_requested AND NOT r.drain_requested
                      AND (r.owner_instance IS NULL OR (r.owner_instance={0} AND r.owner_heartbeat_at>now()-interval '60 seconds')))
                  AND (capability_id <> ALL(ARRAY['cp_inventory_collect', 'pan_inventory_collect'])
                    OR (SELECT count(*) FROM jobs
                        WHERE state IN ('CLAIMED', 'EXECUTING')
                          AND capability_id = ANY(ARRAY['cp_inventory_collect', 'pan_inventory_collect'])) < 10)
                ORDER BY CASE
                    WHEN capability_id = 'diagnostic_read' THEN 0
                    WHEN capability_id = ANY(ARRAY['cp_inventory_collect', 'pan_inventory_collect']) THEN 1
                    WHEN capability_id = ANY(ARRAY['cp_configuration_collect', 'pan_configuration_collect']) THEN 2
                    WHEN capability_id = ANY(ARRAY['cp_discovery_enumerate', 'pan_discovery_enumerate']) THEN 3
                    ELSE 4
                END, submitted_at, job_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING job_id, lease_epoch
            """;

    private ClaimStatementText() {
    }
}
