package com.securityexpert.nexus.ui2.persistence.runtime;

import java.util.Optional;
import java.util.UUID;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import org.jooq.DSLContext;

/** Short DB transactions only; capacity is shared across processes and purposes. */
public final class EndpointAdmissionRepository {
    public record Owner(String jobId, long jobEpoch, String operationRef, long operationEpoch,
            String role, String instance, long generation, String purpose, boolean parallelPolicy) {}
    public record Permit(String requestId, String token, long epoch) {}
    private final AuditedTransactionBoundary transactions;
    public EndpointAdmissionRepository(TransactionBoundary boundary) {
        transactions = new AuditedTransactionBoundary(boundary);
    }
    private <T> T transaction(String action, java.util.function.Function<DSLContext, T> work) {
        try { return transactions.inTransaction("system:worker", action, work); }
        catch (org.jooq.exception.DataAccessException unavailable) { throw new IllegalStateException("ADMISSION_DB_UNAVAILABLE"); }
    }
    private static void lock(DSLContext db) {
        db.fetch("select pg_advisory_xact_lock(294611)");
        db.fetch("select pg_advisory_xact_lock(294612)");
    }
    public Optional<Permit> acquire(String ticket, String endpoint, String session, Owner owner) {
        return transaction("endpoint_admission", db -> {
            lock(db);
            // TTL alone cannot prove remote termination: expired holders still occupy capacity.
            db.fetch("select ui2_release_orphan_quarantines()");
            db.execute("delete from endpoint_admission where state='WAITING' and (expires_at <= now() or exists(select 1 from jobs where jobs.job_id=endpoint_admission.job_id and (cancel_requested or jobs.state='CANCELLED')))");
            if (!validOwner(db, owner, true)) return Optional.empty();
            String token = UUID.randomUUID().toString();
            db.execute("insert into endpoint_admission(request_id,endpoint_ref,purpose_class,job_id,job_epoch,"
                + "operation_ref,operation_epoch,session_ref,owner_role,owner_instance,owner_generation,lease_token,state,parallel_policy)"
                + " values({0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11},'WAITING',{12}) on conflict(request_id) do nothing",
                ticket, endpoint, owner.purpose(), owner.jobId(), owner.jobId() == null ? null : owner.jobEpoch(),
                owner.operationRef(), owner.operationRef() == null ? null : owner.operationEpoch(), session,
                owner.role(), owner.instance(), owner.generation(), token, owner.parallelPolicy());
            db.execute("update endpoint_admission set heartbeat_at=now(),expires_at=now()+interval '60 seconds',"
                + "job_epoch={2},owner_instance={1},owner_generation={3},owner_role={4} "
                + "where request_id={0} and state='WAITING' and job_id={5} and endpoint_ref={6}",
                ticket, owner.instance(), owner.jobEpoch(), owner.generation(), owner.role(), owner.jobId(), endpoint);
            var row = db.fetchOne("select * from endpoint_admission where request_id={0}", ticket);
            if (row == null || !owner.instance().equals(row.get("owner_instance", String.class))) return Optional.empty();
            if (!db.fetch("select request_id from endpoint_admission where endpoint_ref={0} and state='WAITING' "
                + "and (requested_at,request_id) < (select requested_at,request_id from endpoint_admission where request_id={1})",
                endpoint, ticket).isEmpty()) return Optional.empty();
            boolean parallel = owner.parallelPolicy();
            long occupied = db.fetchOne("select count(*) from endpoint_admission where endpoint_ref={0} and state in ('LEASED','QUARANTINED')", endpoint).get(0, Long.class);
            long incompatible = db.fetchOne("select count(*) from endpoint_admission where endpoint_ref={0} and state in ('LEASED','QUARANTINED') and not parallel_policy", endpoint).get(0, Long.class);
            long fleetPolicy = db.fetchOne("select count(*) from endpoint_admission where parallel_policy and state in ('LEASED','QUARANTINED')").get(0, Long.class);
            if ((!parallel && occupied > 0) || (parallel && (incompatible > 0 || occupied >= 4 || fleetPolicy >= 4)))
                return Optional.empty();
            if ("FAILOVER_EXECUTION".equals(owner.purpose()) && !db.fetch("select request_id from endpoint_admission "
                + "where purpose_class='FAILOVER_EXECUTION' and state in ('LEASED','QUARANTINED') "
                + "and coalesce(job_id,operation_ref) <> {0}", owner.jobId() == null ? owner.operationRef() : owner.jobId()).isEmpty())
                return Optional.empty();
            int granted = db.execute("update endpoint_admission set state='LEASED',heartbeat_at=now(),"
                + "expires_at=now()+interval '60 seconds' where request_id={0} and state='WAITING'", ticket);
            if (granted == 1) db.execute("update module_runtime_control set admission_grants=admission_grants+1,"
                + "admission_wait_ms=admission_wait_ms+(select greatest(0,extract(epoch from (now()-requested_at))*1000)::bigint "
                + "from endpoint_admission where request_id={0}) where module={1}", ticket, owner.role());
            return granted == 1 ? Optional.of(new Permit(ticket, row.get("lease_token", String.class), row.get("permit_epoch", Long.class))) : Optional.empty();
        });
    }
    private static boolean validOwner(DSLContext db, Owner owner, boolean opening) {
        if (owner.jobId() == null) return !db.fetch("select task_key from runtime_task_lease where task_key={0} "
            + "and epoch={1} and owner_instance={2} and owner_generation={3} and state='LEASED' and expires_at>now()",
            owner.operationRef(), owner.operationEpoch(), owner.instance(), owner.generation()).isEmpty();
        if ("FAILOVER_EXECUTION".equals(owner.purpose()) && db.fetch("select task_key from runtime_task_lease "
                + "where task_key='fleet.failover.execution' and bound_job_id={0} and bound_job_epoch={1} "
                + "and owner_instance={2} and state='LEASED' and expires_at>now()", owner.jobId(), owner.jobEpoch(), owner.instance()).isEmpty()) return false;
        return !db.fetch("select j.job_id from jobs j join module_runtime_control m on m.module=ui2_job_module(j.capability_id) "
            + "join module_runtime_control r on r.module=m.effective_owner "
            + "where j.job_id={0} and j.lease_epoch={1} and j.state in ('CLAIMED','EXECUTING') "
            + "and j.lease_expires_at > now() and j.lease_worker_id={2} and j.lease_owner_generation=m.generation "
            + "and m.generation={3} and m.effective_owner={4} and (r.owner_instance is null or (r.owner_instance={2} and r.owner_heartbeat_at>now()-interval '60 seconds')) "
            + (opening ? "and not m.drain_requested and not r.drain_requested " : ""),
            owner.jobId(), owner.jobEpoch(), owner.instance(), owner.generation(), owner.role()).isEmpty();
    }
    public boolean renew(Permit permit, Owner owner) {
        return transaction("endpoint_heartbeat", db -> {
            lock(db);
            if (!validOwner(db, owner, false)) return false;
            return db.execute("update endpoint_admission set heartbeat_at=now(),expires_at=now()+interval '60 seconds' "
                + "where request_id={0} and lease_token={1} and permit_epoch={2} and state='LEASED' and expires_at>now()",
                permit.requestId(), permit.token(), permit.epoch()) == 1;
        });
    }
    /** Call only after confirmed local transport closure; also reconciles a quarantined slot. */
    public boolean releaseClosed(Permit permit) {
        return transaction("endpoint_release", db -> {
            lock(db);
            return db.execute("delete from endpoint_admission where request_id={0} and lease_token={1} and permit_epoch={2}",
                permit.requestId(), permit.token(), permit.epoch()) == 1;
        });
    }
    public record TaskPermit(String key, String token, long epoch, String instance) {}
    public Optional<TaskPermit> acquireFleet(String role, String instance, long generation) {
        return acquireFleet(role, instance, generation, null, 0);
    }
    public Optional<TaskPermit> acquireFleet(String role, String instance, long generation, String job, long epoch) {
        return transaction("fleet_failover_admission", db -> {
            lock(db);
            db.fetch("select ui2_release_orphan_quarantines()");
            if (job != null && db.fetch("select job_id from jobs where job_id={0} and lease_epoch={1} "
                    + "and lease_worker_id={2} and state in ('CLAIMED','EXECUTING') and ui2_job_owner_valid(job_id,lease_epoch)", job, epoch, instance).isEmpty()) return Optional.empty();
            if (job == null && db.fetchOne("select count(*) from jobs where state in ('CLAIMED','EXECUTING')").get(0, Long.class) >= 10) return Optional.empty();
            String token = UUID.randomUUID().toString();
            var row = db.fetchOne("insert into runtime_task_lease(task_key,owner_role,owner_instance,owner_generation,token,epoch,state,expires_at,bound_job_id,bound_job_epoch) "
                + "values('fleet.failover.execution',{0},{1},{2},{3},nextval('endpoint_permit_epoch'),'LEASED',now()+interval '60 seconds',{4},{5}) "
                + "on conflict(task_key) do nothing returning epoch", role, instance, generation, token, job, job == null ? null : epoch);
            return row == null ? Optional.empty() : Optional.of(new TaskPermit("fleet.failover.execution", token, row.get(0, Long.class), instance));
        });
    }
    public boolean renewFleet(TaskPermit permit) {
        return transaction("fleet_failover_heartbeat", db -> {
            lock(db);
            return db.execute("update runtime_task_lease set heartbeat_at=now(),expires_at=now()+interval '60 seconds' "
                + "where task_key={0} and token={1} and epoch={2} and state='LEASED' and expires_at>now()",
                permit.key(), permit.token(), permit.epoch()) == 1;
        });
    }
    public void releaseFleetClosed(TaskPermit permit) {
        transaction("fleet_failover_release", db -> {
            lock(db);
            db.execute("delete from runtime_task_lease where task_key={0} and token={1} and epoch={2}",
                permit.key(), permit.token(), permit.epoch()); return null;
        });
    }
    public void ownershipLost(Owner owner) {
        if (owner.jobId() == null) return;
        transaction("endpoint_ownership_lost", db -> db.execute(
            "update jobs set lease_expires_at=now() where job_id={0} and lease_epoch={1} and lease_worker_id={2} "
            + "and state in ('CLAIMED','EXECUTING')", owner.jobId(), owner.jobEpoch(), owner.instance()));
    }
    public boolean operationCertain(Owner owner) {
        if (owner.jobId() == null) return false;
        return transaction("fleet_outcome_check", db -> !db.fetch(
            "select job_id from jobs where job_id={0} and lease_epoch={1} and state not in ('OUTCOME_UNKNOWN','EXECUTING')",
            owner.jobId(), owner.jobEpoch()).isEmpty());
    }
    public boolean openingAllowed(Owner owner) {
        return transaction("endpoint_owner_check", db -> validOwner(db, owner, true));
    }

    public void defer(String job, long epoch) {
        transaction("admission_wait", db -> {
            int deferred = db.execute("update jobs set state='REQUESTED',admission_not_before=now()+interval '10 seconds',admission_deferrals=admission_deferrals+1,"
                + "lease_worker_id=null,lease_expires_at=null where job_id={0} and lease_epoch={1} "
                + "and state in ('CLAIMED','EXECUTING') and not exists(select 1 from job_step_attempt a "
                + "where a.job_id=jobs.job_id and a.lease_epoch=jobs.lease_epoch and a.mutation_boundary_crossed)", job, epoch);
            if (deferred == 1) {
                db.execute("update discovery_run set state='REQUESTED',started_at=null where run_id=(select target_ref from jobs "
                    + "where job_id={0} and ui2_job_module(capability_id)='inventory' and capability_id like '%discovery%') and state='RUNNING'", job);
            }
            return null;
        });
    }
}
