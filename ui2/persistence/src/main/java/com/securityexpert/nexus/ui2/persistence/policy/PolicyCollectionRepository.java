package com.securityexpert.nexus.ui2.persistence.policy;

import java.util.*;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;

/** Management-only admission and durable throttling; no raw response retention. */
public class PolicyCollectionRepository {
    public record Source(String sourceId, String runId) {}
    public record Request(String sourceId, String domainRef, boolean automatic) {}
    private final TransactionBoundary tx;
    public PolicyCollectionRepository(TransactionBoundary tx) { this.tx = tx; }

    private static TransactionBoundary scoped(org.jooq.DSLContext db) {
        return new TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) { return work.apply(db); }
        };
    }

    public List<Source> sources() {
        return tx.inTransaction(db -> db.fetch("select d.device_id, r.run_id from devices d join endpoints e on e.device_id = d.device_id "
            + "join lateral (select run_id from discovery_run where management_address = e.address_ref "
            + "and vendor = 'check_point' and state = 'FINISHED' order by finished_at desc limit 1) r on true "
            + "where d.vendor_hint = 'check_point' and d.role = 'management_server' and not d.disabled "
            + "and d.enrollment_state in ('ENROLLED', 'DEGRADED')")
            .map(r -> new Source(r.get("device_id", String.class), r.get("run_id", String.class))));
    }

    /** A newer discovery must not invalidate an already queued, still-eligible MDS read. */
    public boolean eligible(String sourceId, String runId) {
        return tx.inTransaction(db -> !db.fetch("select 1 from devices d join endpoints e on e.device_id = d.device_id "
            + "join discovery_run r on r.management_address = e.address_ref where d.device_id = {0} and r.run_id = {1} "
            + "and d.vendor_hint = 'check_point' and d.role = 'management_server' and not d.disabled "
            + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') and r.vendor = 'check_point' and r.state = 'FINISHED'",
            sourceId, runId).isEmpty());
    }

    public Optional<Request> request(String jobId) {
        return tx.inTransaction(db -> db.fetch("select source_id, domain_ref, automatic from policy_collection_request where job_id = {0}", jobId)
            .stream().findFirst().map(r -> new Request(r.get("source_id", String.class), r.get("domain_ref", String.class), r.get("automatic", Boolean.class))));
    }

    /** Serialize admission per MDS, including requests against different discovery runs. */
    public Optional<String> enqueue(String sourceId, String domainRef, boolean automatic, String actor) {
        return new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect", db -> {
            db.execute("select pg_advisory_xact_lock(hashtextextended({0}, 0))", "cp-policy:" + sourceId);
            var source = new PolicyCollectionRepository(scoped(db)).sources().stream().filter(s -> s.sourceId().equals(sourceId)).findFirst();
            if (source.isEmpty()) return Optional.empty();
            if (!domainRef.isEmpty() && db.fetch("select 1 from policy_snapshot where metadata->>'sourceId' = {0} "
                    + "and metadata->>'containerId' = {1}", sourceId, domainRef).isEmpty()) return Optional.empty();
            var active = db.fetch("select j.job_id from jobs j join policy_collection_request p on p.job_id = j.job_id "
                + "where p.source_id = {0} and j.state in ('REQUESTED','CLAIMED','EXECUTING','RECONCILING') limit 1", sourceId);
            // A domain-only request cannot silently satisfy a different scope.
            if (!active.isEmpty()) return Optional.empty();
            String id = UUID.randomUUID().toString();
            new JooqJobRecordDao(scoped(db)).insertRequestedIfAbsentForRun(id, id, "cp_policy_collect", source.get().runId(),
                    "read", "cp_policy_collect", actor, "policy_collect").orElseThrow();
            db.execute("insert into policy_collection_request(job_id, source_id, domain_ref, automatic) values ({0},{1},{2},{3})",
                    id, sourceId, domainRef, automatic);
            return Optional.of(id);
        });
    }

    /** Failed and empty collections also consume the automatic six-hour allowance. */
    public boolean beginDomain(String sourceId, String domainRef, boolean automatic) {
        return tx.inTransaction(db -> db.execute("insert into policy_collection_domain(source_id, domain_ref, attempted_at) values ({0},{1},now()) "
            + "on conflict (source_id, domain_ref) do update set attempted_at = excluded.attempted_at "
            + (automatic ? "where policy_collection_domain.attempted_at <= now() - interval '6 hours'" : ""), sourceId, domainRef) == 1);
    }

    /** Exact stored discovery-key relation, never a display-name or address heuristic. */
    public List<Target> targets(String runId, String domainName, String targetUid) {
        return tx.inTransaction(db -> db.fetch("select distinct d.device_id, c.display_name, d.virtual_system_ref from discovery_candidate c "
            + "join devices d on d.discovery_match_key = 'check_point|' || c.owning_domain || '|' || c.stable_identifier "
            + "where c.run_id = {0} and c.owning_domain = {1} and (c.stable_identifier = {2} or c.cluster_reference = {2})",
                runId, domainName, targetUid).map(r -> new Target(r.get("device_id", String.class),
                    Objects.requireNonNullElse(r.get("display_name", String.class), ""), Objects.requireNonNullElse(r.get("virtual_system_ref", String.class), ""), "UNKNOWN")));
    }

    /** Publication and the terminal transition share a row lock and transaction. */
    public boolean publish(String jobId, long epoch, List<PolicySnapshotRepository.Stored> snapshots, String actor) {
        return new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect_publish", db -> {
            if (db.fetch("select job_id from jobs where job_id = {0} and lease_epoch = {1} and state = 'EXECUTING' "
                    + "and lease_expires_at > now() for update", jobId, epoch).isEmpty()) return false;
            var repository = new PolicySnapshotRepository(scoped(db));
            for (var snapshot : snapshots) repository.save(snapshot, actor, "policy_collect_publish");
            if (!new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(scoped(db)).transitionState(
                    jobId, epoch, "EXECUTING", "COMPLETED", actor, "policy_collect_publish"))
                throw new IllegalStateException("POLICY_LEASE_LOST");
            return true;
        });
    }
}
