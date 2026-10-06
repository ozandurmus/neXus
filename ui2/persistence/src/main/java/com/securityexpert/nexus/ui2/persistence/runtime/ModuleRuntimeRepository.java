package com.securityexpert.nexus.ui2.persistence.runtime;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Owner liveness and generation-matched drain acknowledgement. Never changes fallback ownership. */
public final class ModuleRuntimeRepository {
    private final AuditedTransactionBoundary transactions;
    public ModuleRuntimeRepository(TransactionBoundary boundary) { transactions = new AuditedTransactionBoundary(boundary); }
    public boolean compatibleGeneralLive() {
        return transactions.inTransaction("system:worker", "module_compatibility", db -> !db.fetch(
            "select module from module_runtime_control where module='general' and owner_instance is not null "
            + "and owner_heartbeat_at>now()-interval '60 seconds' and not exists(select 1 from jobs "
            + "where state in ('CLAIMED','EXECUTING') and (lease_owner_generation is null "
            + "or split_part(lease_worker_id,'-',1) not in ('general','policy')))").isEmpty());
    }
    public boolean heartbeat(String role, String instance) {
        return transactions.inTransaction("system:worker", "module_heartbeat", db -> {
            db.fetch("select pg_advisory_xact_lock(294611)");
            // A fenced process is still alive; record it even when it cannot own the module.
            db.execute("insert into runtime_instance_heartbeat(owner_instance,heartbeat_at) values({0},now()) "
                + "on conflict(owner_instance) do update set heartbeat_at=excluded.heartbeat_at", instance);
            db.execute("update runtime_task_lease set heartbeat_at=now(),expires_at=now()+interval '60 seconds' "
                + "where owner_instance={0} and task_key<>'fleet.failover.execution' and state='LEASED' and expires_at>now() and exists(select 1 from module_runtime_control "
                + "where module={1} and owner_instance={0} and generation=runtime_task_lease.owner_generation)", instance, role);
            boolean owned = db.execute("update module_runtime_control set owner_instance={0},owner_heartbeat_at=now(),"
                + "drain_ack_at=case when drain_requested then now() else null end,drain_ack_generation=case when drain_requested then drain_generation else null end "
                + "where module={1} and (owner_instance={0} or owner_instance is null "
                + "or (owner_heartbeat_at < now()-interval '60 seconds' and not exists(select 1 from jobs "
                + "where state in ('CLAIMED','EXECUTING') and split_part(lease_worker_id,'-',1)={1}) and not exists(select 1 from runtime_task_lease where owner_role={1})))",
                instance, role) == 1;
            db.fetch("select ui2_release_orphan_quarantines()");
            return owned;
        });
    }
    public void poolStats(String role, String instance, int active, int idle, int pending, long timeouts, long waits) {
        transactions.inTransaction("system:worker", "module_pool_metrics", db -> db.execute(
            "update module_runtime_control set pool_active={2},pool_idle={3},pool_pending={4},pool_timeouts={5},pool_wait_nanos={6} "
            + "where module={0} and owner_instance={1}", role, instance, active, idle, pending, timeouts, waits));
    }
    public void runTask(String role, String instance, String task, Runnable action) {
        String token = java.util.UUID.randomUUID().toString();
        boolean granted = transactions.inTransaction("system:worker", "runtime_task_admission", db -> {
            db.fetch("select pg_advisory_xact_lock(294611)");
            db.fetch("select ui2_release_orphan_quarantines()");
            return db.execute("insert into runtime_task_lease(task_key,owner_role,owner_instance,owner_generation,token,epoch,state,expires_at) "
                + "select {0},{1},{2},generation,{3},nextval('endpoint_permit_epoch'),'LEASED',now()+interval '60 seconds' "
                + "from module_runtime_control where module={1} and owner_instance={2} and not drain_requested "
                + "and owner_heartbeat_at>now()-interval '60 seconds' on conflict(task_key) do nothing", task, role, instance, token) == 1;
        });
        if (!granted) return;
        try { action.run(); }
        finally {
            transactions.inTransaction("system:worker", "runtime_task_release", db -> db.execute(
                "delete from runtime_task_lease where task_key={0} and token={1} and owner_instance={2}", task, token, instance));
        }
    }

    public void requestDrain(String role, String instance) {
        transactions.inTransaction("system:worker", "module_drain", db -> {
            db.fetch("select pg_advisory_xact_lock(294611)");
            db.execute("update module_runtime_control set drain_requested=true,drain_generation=case when drain_requested then drain_generation else drain_generation+1 end,"
                + "drain_ack_at=now(),drain_ack_generation=case when drain_requested then drain_generation else drain_generation+1 end where module={0} and owner_instance={1}", role, instance);
            return null;
        });
    }
    public java.util.Optional<String> discoveryEndpointKey(String run) {
        return transactions.inTransaction("system:worker", "discovery_endpoint", db -> db.fetch(
            "select management_address,vendor from discovery_run where run_id={0}", run).stream().findFirst()
            .map(row -> EndpointAddress.key(row.get("management_address", String.class),
                "check_point".equals(row.get("vendor", String.class)) ? 22 : 443)));
    }
    public long generation(String job, long epoch) {
        return transactions.inTransaction("system:worker", "module_read", db -> {
            var row = db.fetchOne("select lease_owner_generation from jobs where job_id={0} and lease_epoch={1}", job, epoch);
            if (row == null || row.get(0, Long.class) == null) throw new IllegalStateException("OWNER_GENERATION_MISSING");
            return row.get(0, Long.class);
        });
    }
}
