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
    public enum Mode { CHANGED_ONLY, FULL }
    public record Request(String sourceId, String domainRef, boolean automatic, Mode mode, String jobId, long epoch) {
        public Request(String sourceId, String domainRef, boolean automatic) {
            this(sourceId, domainRef, automatic, Mode.CHANGED_ONLY, "", 0);
        }
        public Request withLease(long epoch) { return new Request(sourceId, domainRef, automatic, mode, jobId, epoch); }
    }
    public record DomainRun(boolean complete, String signalJson, String snapshotsJson) {}
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
        return tx.inTransaction(db -> db.fetch("select source_id, domain_ref, automatic, mode from policy_collection_request where job_id = {0}", jobId)
            .stream().findFirst().map(r -> new Request(r.get("source_id", String.class), r.get("domain_ref", String.class), r.get("automatic", Boolean.class), Mode.valueOf(r.get("mode", String.class)), jobId, 0)));
    }

    /** Serialize admission per MDS, including requests against different discovery runs. */
    public Optional<String> enqueue(String sourceId, String domainRef, boolean automatic, String actor) {
        return enqueue(sourceId, domainRef, automatic, actor, Mode.CHANGED_ONLY);
    }
    public Optional<String> enqueue(String sourceId, String domainRef, boolean automatic, String actor, Mode mode) {
        Objects.requireNonNull(mode);
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
            db.execute("insert into policy_collection_request(job_id, source_id, domain_ref, automatic, mode) values ({0},{1},{2},{3},{4})",
                    id, sourceId, domainRef, automatic, mode.name());
            return Optional.of(id);
        });
    }

    /** Failed and empty collections also consume the automatic six-hour allowance. */
    public boolean beginDomain(String sourceId, String domainRef, boolean automatic) {
        return tx.inTransaction(db -> db.execute("insert into policy_collection_domain(source_id, domain_ref, attempted_at) values ({0},{1},now()) "
            + "on conflict (source_id, domain_ref) do update set attempted_at = excluded.attempted_at "
            + (automatic ? "where policy_collection_domain.attempted_at <= now() - interval '6 hours'" : ""), sourceId, domainRef) == 1);
    }

    public boolean beginDomain(Request request, String domain) {
        if (!request.jobId().isEmpty() && tx.inTransaction(db -> !db.fetch(
                "select job_id from cp_policy_domain_run where job_id={0} and source_id={1} and domain_ref={2}",
                request.jobId(), request.sourceId(), domain).isEmpty())) return true;
        return beginDomain(request.sourceId(), domain, request.automatic());
    }
    public Optional<DomainRun> domainForRequest(Request request, String domain) {
        if (request.jobId().isEmpty()) return Optional.empty();
        var row = tx.inTransaction(db -> db.fetch("select complete,signal::text,snapshots::text,chunk_generation,chunk_count from cp_policy_domain_run "
            + "where job_id={0} and source_id={1} and domain_ref={2}", request.jobId(), request.sourceId(), domain)
            .stream().findFirst());
        return row.map(r -> domainRun(r, request.sourceId(), domain));
    }
    public Optional<String> resumedUnit(Request request, String unit, String version) {
        if (request.jobId().isEmpty()) return Optional.empty();
        var row = tx.inTransaction(db -> db.fetch("select snapshot::text,chunk_generation,chunk_count from policy_unit_checkpoint where job_id={0} "
            + "and unit_ref={1} and source_version={2} and complete", request.jobId(), unit, version)
            .stream().findFirst());
        return row.map(r -> r.get("chunk_generation", String.class) == null ? r.get("snapshot", String.class)
            : new PolicyChunkStore(tx).read(request.sourceId(), request.jobId(), unit, "DOMAIN",
                new PolicyChunkStore.Manifest(r.get("chunk_generation", String.class), r.get("chunk_count", Integer.class))));
    }
    public boolean saveUnit(Request request, String unit, String version, String snapshot) {
        if (request.jobId().isEmpty()) return true; // Offline collector fixtures have no ledger job.
        for (String parameter : new String[]{request.jobId(), request.sourceId(), unit, version})
            PolicyChunkStore.requireBounded(PolicyJsonWrite.bytes(parameter));
        boolean live = tx.inTransaction(db -> !db.fetch("select job_id from jobs where job_id={0} and lease_epoch={1} "
            + "and state='EXECUTING' and lease_expires_at > now() and ui2_job_owner_valid(job_id,lease_epoch)",
            request.jobId(), request.epoch()).isEmpty());
        if (!live) return false;
        var manifest = new PolicyChunkStore(tx).write(request.sourceId(), request.jobId(), unit, "DOMAIN", snapshot, "system:worker");
        return new AuditedTransactionBoundary(tx).inTransaction("system:worker", "policy_unit_checkpoint", db -> {
            if (db.fetch("select job_id from jobs where job_id={0} and lease_epoch={1} and state='EXECUTING' "
                    + "and lease_expires_at > now() and ui2_job_owner_valid(job_id,lease_epoch) for update", request.jobId(), request.epoch()).isEmpty()) return false;
            db.execute("insert into policy_unit_checkpoint(job_id,unit_ref,source_version,snapshot,complete,chunk_generation,chunk_count) "
                + "values({0},{1},{2},'{}'::jsonb,true,{3},{4}) on conflict(job_id,unit_ref) do update "
                + "set source_version=excluded.source_version,snapshot=excluded.snapshot,complete=true, "
                + "chunk_generation=excluded.chunk_generation,chunk_count=excluded.chunk_count",
                request.jobId(), unit, version, manifest.generation(), manifest.count()); return true;
        });
    }

    /** Inspect the latest attempt, including incomplete attempts; never fall back past gaps. */
    public Optional<DomainRun> previousDomain(String source, String domain) {
        var row = tx.inTransaction(db -> db.fetch("select complete, signal::text as signal, snapshots::text as snapshots, chunk_generation, chunk_count "
            + "from cp_policy_domain_run where source_id = {0} and domain_ref = {1} order by attempted_at desc limit 1",
            source, domain).stream().findFirst());
        return row.map(r -> domainRun(r, source, domain));
    }
    private DomainRun domainRun(org.jooq.Record r, String source, String domain) {
        return new DomainRun(Boolean.TRUE.equals(r.get("complete", Boolean.class)), r.get("signal", String.class),
            r.field("chunk_generation") == null || r.get("chunk_generation", String.class) == null ? r.get("snapshots", String.class)
                : new PolicyChunkStore(tx).read(source, domain, "", "DOMAIN", new PolicyChunkStore.Manifest(
                    r.get("chunk_generation", String.class), r.get("chunk_count", Integer.class))));
    }

    /** A fenced, small manifest publishes only a fully written immutable generation. */
    public boolean saveDomain(Request request, String domain, String status, boolean complete, String signal,
            String snapshots, int rules, String hitsAt, String actor) {
        for (String parameter : new String[]{request.jobId(), request.sourceId(), domain, status, signal, hitsAt, actor})
            PolicyChunkStore.requireBounded(PolicyJsonWrite.bytes(parameter));
        boolean live = tx.inTransaction(db -> !db.fetch("select job_id from jobs where job_id = {0} and lease_epoch = {1} "
            + "and state = 'EXECUTING' and lease_expires_at > now()", request.jobId(), request.epoch()).isEmpty());
        if (!live) return false;
        PolicyChunkStore.validateSnapshots(snapshots);
        var manifest = new PolicyChunkStore(tx).write(request.sourceId(), domain, "", "DOMAIN", snapshots, actor);
        return PolicyJsonWrite.guarded("PolicyCollectionRepository.saveDomain", "DOMAIN_UPSERT", PolicyJsonWrite.bytes(signal),
            () -> new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect_domain", db -> {
            if (db.fetch("select job_id from jobs where job_id = {0} and lease_epoch = {1} and state = 'EXECUTING' "
                    + "and lease_expires_at > now() and ui2_job_owner_valid(job_id,lease_epoch) for update", request.jobId(), request.epoch()).isEmpty()) return false;
            db.execute("insert into cp_policy_domain_run(job_id, source_id, domain_ref, status, complete, signal, snapshots, rules_count, hits_collected_at, chunk_generation, chunk_count) "
                + "values ({0},{1},{2},{3},{4},{5}::jsonb,'[]'::jsonb,{6},{7}::timestamptz,{8},{9}) "
                + "on conflict (job_id, domain_ref) do update set status = excluded.status, complete = excluded.complete, "
                + "signal = excluded.signal, snapshots = excluded.snapshots, rules_count = excluded.rules_count, hits_collected_at = excluded.hits_collected_at, "
                + "chunk_generation = excluded.chunk_generation, chunk_count = excluded.chunk_count",
                request.jobId(), request.sourceId(), domain, status, complete, complete ? signal : null, rules, hitsAt, manifest.generation(), manifest.count());
            return true;
        }));
    }

    public List<Map<String, Object>> domainProgress(String jobId) {
        return tx.inTransaction(db -> db.fetch("select domain_ref, status, signal->>'publishTime' as publish_time, rules_count, hits_collected_at "
            + "from cp_policy_domain_run where job_id = {0} order by domain_ref", jobId).map(r -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("containerId", r.get("domain_ref", String.class));
                view.put("status", r.get("status", String.class));
                view.put("rules", r.get("rules_count", Integer.class));
                String published = r.get("publish_time", String.class);
                if (published != null) view.put("publishTime", published);
                var at = r.get("hits_collected_at", java.time.OffsetDateTime.class);
                if (at != null) view.put("hitsCollectedAt", at.toInstant().toString());
                return view;
            }));
    }

    /** Reuse each domain/type inventory, including its explicit gaps, until the configured interval expires. */
    public boolean inventoryFresh(String source, String domain, String type, java.time.Instant cutoff) {
        return tx.inTransaction(db -> !db.fetch("select 1 from cp_policy_object_inventory where source_id = {0} "
            + "and domain_ref = {1} and object_type = {2} and collected_at > {3}::timestamptz "
            + "and snapshot->>'status' <> 'UNSUPPORTED'",
            source, domain, type, cutoff.toString()).isEmpty());
    }

    /** Metadata is small; object arrays are streamed into immutable bounded chunks. */
    public void saveInventory(String source, String domain, String type, String at, String snapshot, String actor) {
        PolicyChunkStore.requireBounded(PolicyJsonWrite.bytes(at));
        String metadata = PolicyChunkStore.inventoryMetadata(snapshot);
        PolicyChunkStore.requireBounded(PolicyJsonWrite.bytes(metadata));
        var manifest = new PolicyChunkStore(tx).write(source, domain, type, "INVENTORY", snapshot, actor);
        PolicyJsonWrite.guarded("PolicyCollectionRepository.saveInventory", "INVENTORY_UPSERT", PolicyJsonWrite.bytes(metadata),
            () -> new AuditedTransactionBoundary(tx).inTransaction(actor, "policy_collect_objects", db -> {
            db.execute("insert into cp_policy_object_inventory(source_id, domain_ref, object_type, collected_at, snapshot, chunk_generation, chunk_count) "
                + "values ({0},{1},{2},{3}::timestamptz,{4}::jsonb,{5},{6}) on conflict (source_id, domain_ref, object_type) "
                + "do update set collected_at = excluded.collected_at, snapshot = excluded.snapshot, "
                + "chunk_generation = excluded.chunk_generation, chunk_count = excluded.chunk_count "
                + "where cp_policy_object_inventory.collected_at < excluded.collected_at", source, domain, type, at, metadata, manifest.generation(), manifest.count());
            return null;
        }));
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
            + "coalesce(split_part(l.step_kind, '_', 5)::int, 0) as rules, "
            + "coalesce(nullif(split_part(l.step_kind, '_', 6), '')::int, 0) as packages_done, "
            + "nullif(split_part(l.step_kind, '_', 7), '')::int as packages_total, "
            + "coalesce(nullif(split_part(l.step_kind, '_', 8), '')::bigint, 0) as read_timeout_seconds, "
            + "to_timestamp(nullif(nullif(split_part(l.step_kind, '_', 9), ''), '0')::bigint / 1000.0) as last_activity_at, "
            + "coalesce(nullif(split_part(l.step_kind, '_', 10), '')::int, 0) as domains_done, "
            + "coalesce(nullif(split_part(l.step_kind, '_', 11), '')::int, 0) as domains_total, "
            + "(select min(created_at) from job_step_attempt where job_id = j.job_id "
            + "and step_kind in ('CP_POLICY_READ', 'PAN_POLICY_READ')) as started_at, "
            + "array_to_string(array(select substring(step_kind from 'POLICY_PROGRESS_GAP_(.*)') from job_step_attempt "
            + "where job_id = j.job_id and lease_epoch = j.lease_epoch and step_kind like 'POLICY_PROGRESS_GAP_%' "
            + "order by step_index desc), ',') as failure_codes "
            + "from jobs j join policy_collection_request p on p.job_id = j.job_id "
            + "left join lateral (select step_index, step_kind from job_step_attempt where job_id = j.job_id and lease_epoch = j.lease_epoch "
            + "and step_kind ~ '^POLICY_PROGRESS_[0-9]+$' order by step_index desc limit 1) a on true "
            + "left join lateral (select step_kind from job_step_attempt where job_id = j.job_id and lease_epoch = j.lease_epoch "
            + "and step_kind like 'POLICY_LAYER_%' order by step_index desc limit 1) l on true "
            + "where j.job_id = {0}", jobId).stream().findFirst().map(r -> {
                Map<String, Object> view = new LinkedHashMap<>(Map.of(
                    "jobId", jobId, "cancelRequested", Boolean.TRUE.equals(r.get("cancel_requested", Boolean.class)),
                    "state", r.get("state", String.class), "reason", r.get("reason", String.class),
                    "step", r.get("step", Integer.class), "total", r.get("total", Integer.class),
                    "layer", r.get("layer", Integer.class), "layers", r.get("layers", Integer.class), "rulesFetched", r.get("rules", Integer.class)));
                for (String field : List.of("packages_done", "packages_total", "domains_done", "domains_total")) {
                    String key = switch (field) {
                        case "packages_done" -> "packagesDone"; case "packages_total" -> "packagesTotal";
                        case "domains_done" -> "domainsDone"; default -> "domainsTotal";
                    };
                    Integer value = r.get(field, Integer.class);
                    if (value != null) view.put(key, value);
                }
                view.put("readTimeoutSeconds", Optional.ofNullable(r.get("read_timeout_seconds", Long.class)).orElse(0L));
                for (String field : List.of("started_at", "last_activity_at")) {
                    var at = r.get(field, java.time.OffsetDateTime.class);
                    if (at != null) view.put(field.equals("started_at") ? "startedAt" : "lastActivityAt", at.toInstant().toString());
                }
                String codes = r.get("failure_codes", String.class);
                List<String> failureCodes = codes == null || codes.isEmpty() ? List.of() : List.of(codes.split(","));
                view.put("unitFailureCodes", failureCodes);
                view.put("gapUnits", failureCodes.size());
                return view;
            }));
    }

    /** Lease validation ends before the independent snapshot write; checkpoints never lock jobs. */
    public boolean checkpoint(String jobId, long epoch, PolicySnapshotRepository.Stored snapshot, String actor) {
        boolean live = tx.inTransaction(db -> !db.fetch(
            "select job_id from jobs where job_id = {0} and lease_epoch = {1} and state = 'EXECUTING' "
                + "and lease_expires_at > now()", jobId, epoch).isEmpty());
        if (!live) return false;
        // A lease can change after this read. Only final publication may transition the job,
        // and it revalidates the epoch under FOR UPDATE in its own transaction.
        new PolicySnapshotRepository(tx).save(snapshot, actor, "policy_collect_checkpoint");
        return true;
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
                    + "and lease_expires_at > now() and ui2_job_owner_valid(job_id,lease_epoch) for update", jobId, epoch).isEmpty()) return false;
            var repository = new PolicySnapshotRepository(scoped(db));
            for (var snapshot : snapshots) repository.save(snapshot, actor, "policy_collect_publish");
            if (!new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(scoped(db)).transitionState(
                    jobId, epoch, "EXECUTING", failure.isEmpty() || completedWithWarnings ? "COMPLETED" : "FAILED", actor, "policy_collect_publish", failure.isEmpty() ? null : failure))
                throw new IllegalStateException("POLICY_LEASE_LOST");
            return true;
        });
    }
}
