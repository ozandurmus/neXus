package com.securityexpert.nexus.ui2.persistence.staticroute;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link StaticRoutePreviewRepository} over V252's {@code static_route_preview} table.
 * {@code mutation_possible} is always {@code false}.
 */
public final class JooqStaticRoutePreviewRepository implements StaticRoutePreviewRepository {

    private static final String SELECT_COLUMNS =
            "p.preview_id, p.idempotency_key, p.request_payload_sha256, p.requested_by, p.requested_at, "
            + "p.unit_id, p.target_device_id, p.vendor, p.routing_context, p.operation, "
            + "p.destination_cidr, p.nexthop_ip, p.interface_name, p.distance, p.metric, p.priority, "
            + "p.route_name, p.device_comment, p.representative_host, p.change_ticket_ref, p.reason, "
            + "p.job_id, j.state as job_state, p.state, p.verdict, p.stop_code, p.mutation_possible, "
            + "p.pre_route_table_sha256, p.pre_checks_json::text as pre_checks_json, "
            + "p.before_path_json::text as before_path_json, "
            + "p.command_plan_json::text as command_plan_json, "
            + "p.inverse_plan_json::text as inverse_plan_json, p.completed_at "
            + "from static_route_preview p left join jobs j on j.job_id = p.job_id ";

    private final TransactionBoundary transactions;

    public JooqStaticRoutePreviewRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
    }

    @Override
    public Optional<PreviewRow> findByIdempotencyKey(String idempotencyKey) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select " + SELECT_COLUMNS + "where p.idempotency_key = {0}", idempotencyKey)
                .stream().findFirst().map(JooqStaticRoutePreviewRepository::mapRow));
    }

    @Override
    public void create(CreateSpec spec) {
        transactions.inTransaction(dsl -> {
            dsl.execute(
                    "insert into static_route_preview("
                    + "preview_id, idempotency_key, request_payload_sha256, requested_by, unit_id, target_device_id, "
                    + "vendor, routing_context, operation, destination_cidr, nexthop_ip, interface_name, "
                    + "distance, metric, priority, route_name, device_comment, representative_host, "
                    + "change_ticket_ref, reason, state, verdict, mutation_possible, "
                    + "pre_checks_json, command_plan_json, inverse_plan_json"
                    + ") values ("
                    + "{0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, "
                    + "{12}, {13}, {14}, {15}, {16}, {17}, {18}, {19}, 'QUEUED', 'PENDING', false, "
                    + "cast({20} as jsonb), cast({21} as jsonb), cast({22} as jsonb)"
                    + ")",
                    spec.previewId(), spec.idempotencyKey(), spec.requestPayloadSha256(), spec.requestedBy(),
                    spec.unitId(), spec.targetDeviceId(), spec.vendor(), spec.routingContext(), spec.operation(),
                    spec.destinationCidr(), spec.nextHopIp(), spec.interfaceName(),
                    spec.distance(), spec.metric(), spec.priority(), spec.routeName(), spec.deviceComment(),
                    spec.representativeHost(), spec.changeTicketRef(), spec.reason(),
                    spec.preChecksJson(), spec.commandPlanJson(), spec.inversePlanJson());
            return null;
        });
    }

    @Override
    public boolean attachJob(String previewId, String jobId) {
        return transactions.inTransaction(dsl -> dsl.execute(
                "update static_route_preview set job_id = {1} "
                + "where preview_id = {0} and state = 'QUEUED' and job_id is null",
                previewId, jobId)) == 1;
    }

    @Override
    public Optional<PreviewRow> findByJob(String jobId) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select " + SELECT_COLUMNS + "where p.job_id = {0}", jobId)
                .stream().findFirst().map(JooqStaticRoutePreviewRepository::mapRow));
    }

    @Override
    public boolean markRunning(String jobId, long leaseEpoch) {
        return transactions.inTransaction(dsl -> dsl.execute(
                "update static_route_preview p set state = 'RUNNING' "
                + "where p.job_id = {0} and p.state = 'QUEUED' and exists ("
                + "select 1 from jobs j where j.job_id = {0} and j.lease_epoch = {1} "
                + "and j.state = 'EXECUTING' and j.target_device_id = p.target_device_id "
                + "and ui2_job_owner_valid(j.job_id, j.lease_epoch))",
                jobId, leaseEpoch)) == 1;
    }

    @Override
    public boolean recordOutcome(
            String jobId,
            long leaseEpoch,
            String state,
            String verdict,
            String stopCode,
            String preRouteTableSha256,
            String preChecksJson,
            String beforePathJson,
            String commandPlanJson,
            String inversePlanJson) {
        return transactions.inTransaction(dsl -> dsl.execute(
                "update static_route_preview p set "
                + "state = {1}, verdict = {2}, stop_code = {3}, mutation_possible = false, "
                + "pre_route_table_sha256 = {4}, pre_checks_json = cast({5} as jsonb), "
                + "before_path_json = cast({6} as jsonb), command_plan_json = cast({7} as jsonb), "
                + "inverse_plan_json = cast({8} as jsonb), completed_at = now() "
                + "where p.job_id = {0} and p.state in ('QUEUED', 'RUNNING') and exists ("
                + "select 1 from jobs j where j.job_id = {0} and j.lease_epoch = {9} "
                + "and j.state = 'EXECUTING' and j.target_device_id = p.target_device_id "
                + "and ui2_job_owner_valid(j.job_id, j.lease_epoch))",
                jobId, state, verdict, stopCode, preRouteTableSha256,
                preChecksJson, beforePathJson, commandPlanJson, inversePlanJson, leaseEpoch)) == 1;
    }

    @Override
    public boolean abandonByJob(String jobId, String stopCode) {
        return transactions.inTransaction(dsl -> dsl.execute(
                "update static_route_preview set state = 'STOPPED', verdict = 'UNKNOWN', "
                + "stop_code = {1}, mutation_possible = false, completed_at = now() "
                + "where job_id = {0} and state in ('QUEUED', 'RUNNING')",
                jobId, stopCode)) == 1;
    }

    @Override
    public void abandonById(String previewId, String stopCode) {
        transactions.inTransaction(dsl -> dsl.execute(
                "update static_route_preview set state = 'STOPPED', verdict = 'UNKNOWN', "
                + "stop_code = {1}, mutation_possible = false, completed_at = now() "
                + "where preview_id = {0} and state in ('QUEUED', 'RUNNING')",
                previewId, stopCode));
    }

    @Override
    public Optional<PreviewRow> find(String previewId) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select " + SELECT_COLUMNS + "where p.preview_id = {0}", previewId)
                .stream().findFirst().map(JooqStaticRoutePreviewRepository::mapRow));
    }

    @Override
    public List<PreviewRow> listRecent(int limit) {
        int cap = Math.max(1, Math.min(limit, 100));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select " + SELECT_COLUMNS + "order by p.requested_at desc limit {0}", cap)
                .stream().map(JooqStaticRoutePreviewRepository::mapRow).toList());
    }

    private static PreviewRow mapRow(Record r) {
        return new PreviewRow(
                r.get("preview_id", String.class),
                r.get("idempotency_key", String.class),
                r.get("request_payload_sha256", String.class),
                r.get("requested_by", String.class),
                r.get("requested_at", OffsetDateTime.class).toInstant(),
                r.get("unit_id", String.class),
                r.get("target_device_id", String.class),
                r.get("vendor", String.class),
                r.get("routing_context", String.class),
                r.get("operation", String.class),
                r.get("destination_cidr", String.class),
                r.get("nexthop_ip", String.class),
                Optional.ofNullable(r.get("interface_name", String.class)),
                Optional.ofNullable(r.get("distance", Integer.class)),
                Optional.ofNullable(r.get("metric", Integer.class)),
                Optional.ofNullable(r.get("priority", Integer.class)),
                Optional.ofNullable(r.get("route_name", String.class)),
                Optional.ofNullable(r.get("device_comment", String.class)),
                r.get("representative_host", String.class),
                Optional.ofNullable(r.get("change_ticket_ref", String.class)),
                Optional.ofNullable(r.get("reason", String.class)),
                Optional.ofNullable(r.get("job_id", String.class)),
                Optional.ofNullable(r.get("job_state", String.class)),
                r.get("state", String.class),
                r.get("verdict", String.class),
                Optional.ofNullable(r.get("stop_code", String.class)),
                Boolean.TRUE.equals(r.get("mutation_possible", Boolean.class)),
                Optional.ofNullable(r.get("pre_route_table_sha256", String.class)),
                r.get("pre_checks_json", String.class),
                Optional.ofNullable(r.get("before_path_json", String.class)),
                r.get("command_plan_json", String.class),
                r.get("inverse_plan_json", String.class),
                Optional.ofNullable(r.get("completed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant));
    }
}
