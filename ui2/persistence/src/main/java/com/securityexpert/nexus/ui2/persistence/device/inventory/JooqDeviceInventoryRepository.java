package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link DeviceInventoryRepository} (migration V13, 14C D-4).
 * {@link #recordRun} writes the run row and every child row inside one
 * {@link AuditedTransactionBoundary} call -- a thrown exception (a
 * malformed record, a constraint violation) rolls the whole run back, so
 * no partial run is ever visible to {@link #findLatestRun}.
 */
public final class JooqDeviceInventoryRepository implements DeviceInventoryRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqDeviceInventoryRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public void recordRun(InventoryRun run, String actorFingerprint, String actionId) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count, virtual_systems) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5})",
                    run.runId(), run.deviceId(), run.jobId(), Timestamp.from(run.collectedAt()), run.contextCount(),
                    run.virtualSystems().orElse(null));

            for (InventoryContext context : run.contexts()) {
                for (InventoryInterface iface : context.interfaces()) {
                    dsl.execute("insert into device_interface(interface_id, run_id, context, name, parent, kind, "
                            + "state, vlan_id) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})",
                            iface.interfaceId(), run.runId(), context.context(), iface.name(),
                            iface.parent().orElse(null), iface.kind(), iface.state(), iface.vlanId().orElse(null));
                    for (InventoryAddress address : iface.addresses()) {
                        dsl.execute("insert into device_interface_address(address_id, interface_id, address, "
                                + "family, role) values ({0}, {1}, {2}, {3}, {4})",
                                address.addressId(), iface.interfaceId(), address.address(), address.family(),
                                address.role());
                    }
                }
                for (InventoryRoute route : context.routes()) {
                    dsl.execute("insert into device_route(route_id, run_id, context, destination, next_hop, "
                            + "interface, protocol, route_table) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})",
                            route.routeId(), run.runId(), context.context(), route.destination(),
                            route.nextHop().orElse(null), route.interfaceName().orElse(null), route.protocol(),
                            route.routeTable().orElse(null));
                }
            }
            for (InventoryHaFact fact : run.haFacts()) {
                dsl.execute("insert into device_inventory_ha(ha_id, run_id, context, role, cluster_mode, source) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5})",
                        fact.haId(), run.runId(), fact.context(), fact.role(), fact.clusterMode().orElse(null),
                        fact.source());
            }
            return null;
        });
    }

    @Override
    public Optional<InventoryRun> findLatestRun(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> runRows = dsl.fetch("select run_id, device_id, job_id, collected_at, context_count, virtual_systems "
                    + "from device_inventory_run where device_id = {0} order by collected_at desc limit 1", deviceId);
            return runRows.stream().findFirst().map(row -> assemble(dsl, row));
        });
    }

    @Override
    public List<InventoryRun> findLatestRuns(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> {
            List<InventoryRun> result = new ArrayList<>();
            for (String deviceId : deviceIds) {
                Result<Record> runRows = dsl.fetch("select run_id, device_id, job_id, collected_at, context_count, virtual_systems "
                        + "from device_inventory_run where device_id = {0} order by collected_at desc limit 1",
                        deviceId);
                runRows.stream().findFirst().map(row -> assemble(dsl, row)).ifPresent(result::add);
            }
            return List.copyOf(result);
        });
    }

    private static InventoryRun assemble(DSLContext dsl, Record runRow) {
        String runId = runRow.get("run_id", String.class);

        Map<String, List<InventoryAddress>> addressesByInterfaceId = new LinkedHashMap<>();
        Result<Record> addressRows = dsl.fetch("select address_id, interface_id, address, family, role "
                + "from device_interface_address where interface_id in "
                + "(select interface_id from device_interface where run_id = {0})", runId);
        for (Record row : addressRows) {
            addressesByInterfaceId.computeIfAbsent(row.get("interface_id", String.class), key -> new ArrayList<>())
                    .add(new InventoryAddress(row.get("address_id", String.class), row.get("address", String.class),
                            row.get("family", String.class), row.get("role", String.class)));
        }

        Map<String, List<InventoryInterface>> interfacesByContext = new LinkedHashMap<>();
        Result<Record> interfaceRows = dsl.fetch("select interface_id, context, name, parent, kind, state, vlan_id "
                + "from device_interface where run_id = {0}", runId);
        for (Record row : interfaceRows) {
            String interfaceId = row.get("interface_id", String.class);
            interfacesByContext.computeIfAbsent(row.get("context", String.class), key -> new ArrayList<>())
                    .add(new InventoryInterface(interfaceId, row.get("name", String.class),
                            Optional.ofNullable(row.get("parent", String.class)), row.get("kind", String.class),
                            row.get("state", String.class),
                            addressesByInterfaceId.getOrDefault(interfaceId, List.of()),
                            Optional.ofNullable(row.get("vlan_id", Integer.class))));
        }

        Map<String, List<InventoryRoute>> routesByContext = new LinkedHashMap<>();
        Result<Record> routeRows = dsl.fetch("select route_id, context, destination, next_hop, interface, "
                + "protocol, route_table from device_route where run_id = {0}", runId);
        for (Record row : routeRows) {
            routesByContext.computeIfAbsent(row.get("context", String.class), key -> new ArrayList<>())
                    .add(new InventoryRoute(row.get("route_id", String.class), row.get("destination", String.class),
                            Optional.ofNullable(row.get("next_hop", String.class)),
                            Optional.ofNullable(row.get("interface", String.class)), row.get("protocol", String.class),
                            Optional.ofNullable(row.get("route_table", String.class))));
        }

        List<String> contextLabels = new ArrayList<>(interfacesByContext.keySet());
        for (String label : routesByContext.keySet()) {
            if (!contextLabels.contains(label)) {
                contextLabels.add(label);
            }
        }
        List<InventoryContext> contexts = contextLabels.stream()
                .map(label -> new InventoryContext(label, interfacesByContext.getOrDefault(label, List.of()),
                        routesByContext.getOrDefault(label, List.of())))
                .toList();

        Result<Record> haRows = dsl.fetch("select ha_id, context, role, cluster_mode, source "
                + "from device_inventory_ha where run_id = {0}", runId);
        List<InventoryHaFact> haFacts = haRows.stream()
                .map(row -> new InventoryHaFact(row.get("ha_id", String.class), row.get("context", String.class),
                        row.get("role", String.class), Optional.ofNullable(row.get("cluster_mode", String.class)),
                        row.get("source", String.class)))
                .toList();

        Optional<String> virtualSystems = runRow.field("virtual_systems") != null
                ? Optional.ofNullable(runRow.get("virtual_systems", String.class))
                : Optional.empty();

        return new InventoryRun(runId, runRow.get("device_id", String.class), runRow.get("job_id", String.class),
                runRow.get("collected_at", Timestamp.class).toInstant(), runRow.get("context_count", Integer.class),
                contexts, haFacts, virtualSystems);
    }
}
