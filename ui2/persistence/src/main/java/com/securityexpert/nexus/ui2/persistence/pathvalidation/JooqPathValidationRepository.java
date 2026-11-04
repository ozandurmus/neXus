package com.securityexpert.nexus.ui2.persistence.pathvalidation;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link PathValidationRepository}. The two V188 tables carry no audit trigger (derived rows; the request
 * is recorded in {@code authz_decisions}), so plain transactions are used. Every statement that ends a hop sets
 * {@code plan = NULL} in the same UPDATE (V188's {@code path_validation_hop_plan_only_while_pending} CHECK enforces it).
 */
public final class JooqPathValidationRepository implements PathValidationRepository {

    private final TransactionBoundary transactions;

    public JooqPathValidationRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
    }

    @Override
    public void create(String validationId, String actor, String topologySnapshotId, String pathVerdict,
            int hopsNotValidated, List<NewHop> hops) {
        transactions.inTransaction(dsl -> {
            dsl.execute("insert into path_validation(validation_id, submitted_by, topology_snapshot_id, path_verdict, "
                    + "hop_count, hops_not_validated) values ({0}, {1}, {2}, {3}, {4}, {5})",
                    validationId, actor, topologySnapshotId, pathVerdict, hops.size(), hopsNotValidated);
            for (NewHop hop : hops) {
                dsl.execute("insert into path_validation_hop(validation_id, ordinal, branch_id, hop_index, node_id, vendor, "
                        + "device_id, plan, result, reasons, evaluated_at) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, "
                        + "cast({7} as jsonb), {8}, cast({9} as text[]), case when cast({8} as text) = 'PENDING' then null else now() end)",
                        validationId, hop.ordinal(), hop.branchId(), hop.hopIndex(), hop.nodeId(), hop.vendor(),
                        hop.deviceId().orElse(null), hop.planJson().orElse(null), hop.result(),
                        hop.reasons().toArray(String[]::new));
            }
            return null;
        });
    }

    @Override
    public boolean attachJob(String validationId, int ordinal, String jobId) {
        return transactions.inTransaction(dsl -> dsl.execute("update path_validation_hop set job_id = {2} "
                + "where validation_id = {0} and ordinal = {1} and result = 'PENDING' and job_id is null",
                validationId, ordinal, jobId)) == 1;
    }

    @Override
    public void abandon(String validationId, int ordinal, String reason) {
        transactions.inTransaction(dsl -> dsl.execute("update path_validation_hop set result = 'NOT_EVALUABLE', "
                + "plan = null, reasons = array_append(reasons, cast({2} as text)), evaluated_at = now() "
                + "where validation_id = {0} and ordinal = {1} and result = 'PENDING'", validationId, ordinal, reason));
    }

    @Override
    public Optional<Pending> pendingForJob(String jobId) {
        return transactions.inTransaction(dsl -> dsl.fetch("select validation_id, ordinal, device_id, plan::text as plan "
                + "from path_validation_hop where job_id = {0} and result = 'PENDING' and plan is not null", jobId)
                .stream().findFirst().map(r -> new Pending(r.get("validation_id", String.class),
                        r.get("ordinal", Integer.class), r.get("device_id", String.class), r.get("plan", String.class))));
    }

    @Override
    public boolean recordOutcome(String jobId, long leaseEpoch, String result, String checksJson, List<String> reasons) {
        return transactions.inTransaction(dsl -> dsl.execute("update path_validation_hop h set result = {1}, plan = null, "
                + "checks = cast({2} as jsonb), reasons = h.reasons || cast({3} as text[]), evaluated_at = now() "
                + "where h.job_id = {0} and h.result = 'PENDING' and exists (select 1 from jobs j where j.job_id = {0} "
                + "and j.lease_epoch = {4} and j.state = 'EXECUTING' and j.target_device_id = h.device_id "
                + "and ui2_job_owner_valid(j.job_id, j.lease_epoch))",
                jobId, result, checksJson, reasons.toArray(String[]::new), leaseEpoch)) == 1;
    }

    @Override
    public boolean abandonJob(String jobId, String reason) {
        return transactions.inTransaction(dsl -> dsl.execute("update path_validation_hop set result = 'NOT_EVALUABLE', "
                + "plan = null, reasons = array_append(reasons, cast({1} as text)), evaluated_at = now() "
                + "where job_id = {0} and result = 'PENDING'", jobId, reason)) == 1;
    }

    @Override
    public Optional<Validation> find(String validationId) {
        return transactions.inTransaction(dsl -> {
            Record head = dsl.fetchOne("select validation_id, submitted_by, submitted_at, topology_snapshot_id, "
                    + "path_verdict, hops_not_validated from path_validation where validation_id = {0}", validationId);
            if (head == null) {
                return Optional.<Validation>empty();
            }
            List<Hop> hops = dsl.fetch("select h.ordinal, h.branch_id, h.hop_index, h.node_id, h.vendor, h.device_id, "
                    + "h.job_id, j.state as job_state, h.result, h.checks::text as checks, "
                    + "array_to_string(h.reasons, ',') as reasons, h.evaluated_at from path_validation_hop h "
                    + "left join jobs j on j.job_id = h.job_id where h.validation_id = {0} order by h.ordinal", validationId)
                    .stream().map(r -> new Hop(r.get("ordinal", Integer.class), r.get("branch_id", String.class),
                            r.get("hop_index", Integer.class), r.get("node_id", String.class), r.get("vendor", String.class),
                            Optional.ofNullable(r.get("device_id", String.class)),
                            Optional.ofNullable(r.get("job_id", String.class)),
                            Optional.ofNullable(r.get("job_state", String.class)), r.get("result", String.class),
                            r.get("checks", String.class), split(r.get("reasons", String.class)),
                            Optional.ofNullable(r.get("evaluated_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant)))
                    .toList();
            return Optional.of(new Validation(head.get("validation_id", String.class), head.get("submitted_by", String.class),
                    head.get("submitted_at", OffsetDateTime.class).toInstant(), head.get("topology_snapshot_id", String.class),
                    head.get("path_verdict", String.class), head.get("hops_not_validated", Integer.class), hops));
        });
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
    }
}
