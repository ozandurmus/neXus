package com.securityexpert.nexus.ui2.persistence.policy;

import java.util.*;
import com.securityexpert.nexus.ui2.persistence.*;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobRecordDao;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;

/** Management-only admission and durable throttling; no raw response retention. */
public class PolicyCollectionRepository {
    public record Source(String sourceId, String runId, String vendor) {
        public Source(String sourceId, String runId) { this(sourceId, runId, "check_point"); }
    }
    public record Request(String sourceId, String domainRef, boolean automatic) {}
    public record FirewallEndpoint(String address, String credentialReferenceId) {}
    private final TransactionBoundary tx;
    public PolicyCollectionRepository(TransactionBoundary tx) { this.tx = tx; }

    private static TransactionBoundary scoped(org.jooq.DSLContext db) {
        return new TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) { return work.apply(db); }
        };
    }

    public List<Source> sources() {
        return tx.inTransaction(db -> db.fetch("select d.device_id, r.run_id, d.vendor_hint from devices d join endpoints e on e.device_id = d.device_id "
            + "join lateral (select run_id from discovery_run where management_address = e.address_ref "
            + "and vendor = d.vendor_hint and state = 'FINISHED' order by finished_at desc limit 1) r on true "
            + "where d.vendor_hint in ('check_point', 'palo_alto') and d.role = 'management_server' and not d.disabled "
            + "and d.enrollment_state in ('ENROLLED', 'DEGRADED')")
            .map(r -> new Source(r.get("device_id", String.class), r.get("run_id", String.class), r.get("vendor_hint", String.class))));
    }

    /** A newer discovery must not invalidate an already queued, still-eligible MDS read. */
    public boolean eligible(String sourceId, String runId) {
        return tx.inTransaction(db -> !db.fetch("select 1 from devices d join endpoints e on e.device_id = d.device_id "
            + "join discovery_run r on r.management_address = e.address_ref where d.device_id = {0} and r.run_id = {1} "
            + "and d.vendor_hint in ('check_point', 'palo_alto') and d.role = 'management_server' and not d.disabled "
            + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') and r.vendor = d.vendor_hint and r.state = 'FINISHED'",
            sourceId, runId).isEmpty());
    }

    public Optional<Request> request(String jobId) {
        return tx.inTransaction(db -> db.fetch("select source_id, domain_ref, automatic from policy_collection_request where job_id = {0}", jobId)
            .stream().findFirst().map(r -> new Request(r.get("source_id", String.class), r.get("domain_ref", String.class), r.get("automatic", Boolean.class))));
    }

    /** Serialize admission per MDS, including requests against different discovery runs. */
    public Optional<String> enqueue(String sourceId, String domainRef, boolean automatic, String actor) {
        return new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect", db -> {
            db.execute("select pg_advisory_xact_lock(hashtextextended({0}, 0))", "policy:" + sourceId);
            var source = new PolicyCollectionRepository(scoped(db)).sources().stream().filter(s -> s.sourceId().equals(sourceId)).findFirst();
            if (source.isEmpty()) return Optional.empty();
            if (!domainRef.isEmpty() && db.fetch("select 1 from policy_snapshot where metadata->>'sourceId' = {0} "
                    + "and metadata->>'containerId' = {1}", sourceId, domainRef).isEmpty()) return Optional.empty();
            var active = db.fetch("select j.job_id from jobs j join policy_collection_request p on p.job_id = j.job_id "
                + "where p.source_id = {0} and j.state in ('REQUESTED','CLAIMED','EXECUTING','RECONCILING') limit 1", sourceId);
            // A domain-only request cannot silently satisfy a different scope.
            if (!active.isEmpty()) return Optional.empty();
            if ("palo_alto".equals(source.get().vendor()) && !new PolicyCollectionRepository(scoped(db)).beginDomain(sourceId, "", automatic))
                return Optional.empty();
            String capability = "palo_alto".equals(source.get().vendor()) ? "pan_policy_collect" : "cp_policy_collect";
            String id = UUID.randomUUID().toString();
            new JooqJobRecordDao(scoped(db)).insertRequestedIfAbsentForRun(id, id, capability, source.get().runId(),
                    "read", capability, actor, "policy_collect").orElseThrow();
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

    /** Exact serial relation established by discovery; unmatched members remain scoped opaque references. */
    public List<Target> panTargets(String runId, String sourceId, String serial, String context, String syncStatus) {
        var matched = tx.inTransaction(db -> db.fetch("select distinct d.device_id, c.display_name from discovery_candidate c "
            + "join devices d on d.discovery_match_key = 'palo_alto|' || c.stable_identifier "
            + "where c.run_id = {0} and c.vendor = 'palo_alto' and c.stable_identifier = {1} and c.parent_candidate_id is null",
            runId, serial).map(r -> new Target(r.get("device_id", String.class),
                Objects.requireNonNullElse(r.get("display_name", String.class), ""), context, syncStatus)));
        if (matched.size() > 1) throw new IllegalStateException("POLICY_MEMBER_AMBIGUOUS");
        return matched.isEmpty() ? List.of(new Target(com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref(sourceId, "member", serial),
                "Unmatched firewall", context, syncStatus)) : matched;
    }

    /** Only an enrolled physical member's own endpoint and credential; never route through Panorama. */
    public Optional<FirewallEndpoint> panFirewall(String deviceId) {
        return tx.inTransaction(db -> db.fetch("select e.address_ref, d.credential_reference_id from devices d "
            + "join endpoints e on e.device_id = d.device_id where d.device_id = {0} and d.vendor_hint = 'palo_alto' "
            + "and d.role = 'gateway' and not d.disabled and d.enrollment_state in ('ENROLLED','DEGRADED') "
            + "order by e.created_at asc limit 1", deviceId).stream().findFirst().map(r ->
                new FirewallEndpoint(r.get("address_ref", String.class), r.get("credential_reference_id", String.class))));
    }

    public Optional<Map<String, Object>> latestStatus(String sourceId) {
        return tx.inTransaction(db -> db.fetch("select p.job_id from policy_collection_request p join jobs j on j.job_id = p.job_id "
            + "where p.source_id = {0} order by j.submitted_at desc limit 1", sourceId).stream().findFirst()
            .flatMap(r -> new PolicyCollectionRepository(scoped(db)).status(r.get("job_id", String.class))));
    }
    public Optional<Map<String, Object>> status(String jobId) {
        return tx.inTransaction(db -> db.fetch("select j.state, j.cancel_requested, coalesce(j.terminal_reason, '') as reason, "
            + "coalesce(a.step_index, 0) as step, coalesce(substring(a.step_kind from 'POLICY_PROGRESS_([0-9]+)')::int, 0) as total, "
            + "coalesce(split_part(l.step_kind, '_', 3)::int, 0) as layer, "
            + "coalesce(split_part(l.step_kind, '_', 4)::int, 0) as layers, "
            + "coalesce(split_part(l.step_kind, '_', 5)::int, 0) as rules "
            + "from jobs j join policy_collection_request p on p.job_id = j.job_id "
            + "left join lateral (select step_index, step_kind from job_step_attempt where job_id = j.job_id and lease_epoch = j.lease_epoch "
            + "and step_kind like 'POLICY_PROGRESS_%' order by step_index desc limit 1) a on true "
            + "left join lateral (select step_kind from job_step_attempt where job_id = j.job_id and lease_epoch = j.lease_epoch "
            + "and step_kind like 'POLICY_LAYER_%' order by step_index desc limit 1) l on true "
            + "where j.job_id = {0}", jobId).stream().findFirst().map(r -> Map.<String, Object>of(
                "jobId", jobId, "cancelRequested", Boolean.TRUE.equals(r.get("cancel_requested", Boolean.class)), "state", r.get("state", String.class), "reason", r.get("reason", String.class),
                "step", r.get("step", Integer.class), "total", r.get("total", Integer.class),
                "layer", r.get("layer", Integer.class), "layers", r.get("layers", Integer.class), "rulesFetched", r.get("rules", Integer.class))));
    }

    /** Each completed-layer checkpoint commits independently, without terminating the collection. */
    public boolean checkpoint(String jobId, long epoch, PolicySnapshotRepository.Stored snapshot, String actor) {
        return new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect_checkpoint", db -> {
            if (db.fetch("select job_id from jobs where job_id = {0} and lease_epoch = {1} and state = 'EXECUTING' "
                    + "and lease_expires_at > now() for update", jobId, epoch).isEmpty()) return false;
            new PolicySnapshotRepository(scoped(db)).save(snapshot, actor, "policy_collect_checkpoint");
            return true;
        });
    }

    /** Publication and the terminal transition share a row lock and transaction. */
    public boolean publish(String jobId, long epoch, List<PolicySnapshotRepository.Stored> snapshots, String actor) {
        return publish(jobId, epoch, snapshots, actor, "");
    }
    public boolean publish(String jobId, long epoch, List<PolicySnapshotRepository.Stored> snapshots, String actor, String failure) {
        return publish(jobId, epoch, snapshots, actor, failure, false);
    }
    public boolean publishWithWarnings(String jobId, long epoch, List<PolicySnapshotRepository.Stored> snapshots, String actor, String warning) {
        if (snapshots.isEmpty()) throw new IllegalArgumentException("POLICY_PARTIAL_SNAPSHOT_REQUIRED");
        return publish(jobId, epoch, snapshots, actor, warning, true);
    }
    private boolean publish(String jobId, long epoch, List<PolicySnapshotRepository.Stored> snapshots, String actor, String failure, boolean completedWithWarnings) {
        return new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect_publish", db -> {
            if (db.fetch("select job_id from jobs where job_id = {0} and lease_epoch = {1} and state = 'EXECUTING' "
                    + "and lease_expires_at > now() for update", jobId, epoch).isEmpty()) return false;
            var repository = new PolicySnapshotRepository(scoped(db));
            for (var snapshot : snapshots) repository.save(snapshot, actor, "policy_collect_publish");
            if (!new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(scoped(db)).transitionState(
                    jobId, epoch, "EXECUTING", failure.isEmpty() || completedWithWarnings ? "COMPLETED" : "FAILED", actor, "policy_collect_publish", failure.isEmpty() ? null : failure))
                throw new IllegalStateException("POLICY_LEASE_LOST");
            return true;
        });
    }
}
