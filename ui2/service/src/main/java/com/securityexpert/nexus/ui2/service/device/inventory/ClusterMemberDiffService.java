package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.service.device.inventory.ClusterMemberDiff.Cell;
import com.securityexpert.nexus.ui2.service.device.inventory.ClusterMemberDiff.FieldSpec;

/**
 * {@code GET /clusters/{cluster_member_ref}/member-diff}: the stored facts of each cluster member, side by side. Everything
 * here is already in the database (the device summary, the latest inventory run, the reported policy install, the latest
 * primary configuration run and its section index); no device is asked anything and nothing is stored. A fact that a vendor
 * or a member has not produced is absent and the comparison reports it as UNKNOWN, never as equal or different.
 */
@Service
public final class ClusterMemberDiffService {
    static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("software_version", "Software version", null),
            new FieldSpec("model", "Model", null),
            new FieldSpec("virtual_systems", "Virtual systems", null),
            new FieldSpec("ha_cluster_mode", "HA cluster mode", "Reported by the member's latest inventory read; the member role is expected to differ and is not compared."),
            new FieldSpec("interfaces", "Interfaces", "Interface names by context; addresses and state are on the Interfaces tab."),
            new FieldSpec("routes", "Routes", "Routes by context, destination, next hop and interface."),
            new FieldSpec("policy_installed", "Installed policy", "The policy name the member reports as installed."),
            new FieldSpec("policy_installed_at", "Policy install time", "The install time exactly as the member reported it."),
            new FieldSpec("configuration_sections", "Configuration sections", "Section names and entry counts of the latest stored configuration."),
            new FieldSpec("configuration_hash", "Configuration hash", "Hash of the latest stored configuration; it differs whenever any member-specific line (host name, addresses) differs, so read the sections above first."));

    private final TransactionBoundary tx;
    private final InventoryQueryService inventory;
    private final DeviceInventoryRepository runs;

    public ClusterMemberDiffService(TransactionBoundary tx, InventoryQueryService inventory, DeviceInventoryRepository runs) {
        this.tx = tx;
        this.inventory = inventory;
        this.runs = runs;
    }

    /** Empty when no device carries the reference. */
    public Optional<ClusterMemberDiff.Result> forCluster(String clusterMemberRef) {
        if (!(inventory.clusterInventory(clusterMemberRef) instanceof InventoryQueryService.ClusterInventoryOutcome.Found found)) return Optional.empty();
        List<DeviceSummaryRecord> members = found.members();
        List<String> ids = members.stream().map(DeviceSummaryRecord::deviceId).sorted().toList();
        Map<String, Map<String, Cell>> cells = new LinkedHashMap<>();
        for (FieldSpec spec : FIELDS) cells.put(spec.key(), new HashMap<>());
        Map<String, InventoryRun> latest = new HashMap<>();
        for (InventoryRun run : runs.findLatestRuns(ids)) latest.put(run.deviceId(), run);
        for (DeviceSummaryRecord m : members) {
            String id = m.deviceId();
            put(cells, "software_version", id, m.observedSoftwareVersion().map(Cell::of).orElse(null));
            put(cells, "model", id, m.observedModel().map(Cell::of).orElse(null));
            put(cells, "virtual_systems", id, m.virtualSystems().map(ClusterMemberDiffService::vsCell).orElse(null));
            InventoryRun run = latest.get(id);
            if (run != null) {
                put(cells, "ha_cluster_mode", id, haMode(run));
                // A run with a context list is a stored fact even when the list is empty; a run absent is UNKNOWN.
                TreeSet<String> interfaces = new TreeSet<>();
                TreeSet<String> routes = new TreeSet<>();
                for (var context : run.contexts()) {
                    context.interfaces().forEach(i -> interfaces.add(context.context() + "/" + i.name()));
                    context.routes().forEach(r -> routes.add(context.context() + "|" + r.destination() + "|" + r.nextHop().orElse("-")
                            + "|" + r.interfaceName().orElse("-")));
                }
                put(cells, "interfaces", id, Cell.ofSet(interfaces.size() + (interfaces.size() == 1 ? " interface" : " interfaces"), interfaces));
                put(cells, "routes", id, Cell.ofSet(routes.size() + (routes.size() == 1 ? " route" : " routes"), routes));
            }
            policyInstall(id, cells);
            configuration(id, cells);
        }
        return Optional.of(ClusterMemberDiff.compare(clusterMemberRef, ids, FIELDS, cells));
    }

    private static void put(Map<String, Map<String, Cell>> cells, String key, String id, Cell cell) {
        if (cell != null) cells.get(key).put(id, cell);
    }

    private static Cell vsCell(String raw) {
        TreeSet<String> names = new TreeSet<>(Arrays.stream(raw.split(",\\s*")).filter(s -> !s.isBlank()).toList());
        if (names.isEmpty()) return null;
        return Cell.ofSet(names.size() + (names.size() == 1 ? " virtual system" : " virtual systems"), names);
    }

    private static Cell haMode(InventoryRun run) {
        TreeSet<String> modes = new TreeSet<>();
        for (InventoryHaFact fact : run.haFacts()) fact.clusterMode().filter(s -> !s.isBlank()).ifPresent(modes::add);
        return modes.isEmpty() ? null : Cell.of(String.join(", ", modes));
    }

    private void policyInstall(String deviceId, Map<String, Map<String, Cell>> cells) {
        var row = tx.inTransaction(db -> db.fetchOptional("select policy_name, installed_at_text from device_policy_install where device_id = {0}", deviceId));
        row.ifPresent(r -> {
            String name = r.get("policy_name", String.class), at = r.get("installed_at_text", String.class);
            if (name != null && !name.isBlank()) put(cells, "policy_installed", deviceId, Cell.of(name));
            if (at != null && !at.isBlank()) put(cells, "policy_installed_at", deviceId, Cell.of(at));
        });
    }

    private void configuration(String deviceId, Map<String, Map<String, Cell>> cells) {
        var run = tx.inTransaction(db -> db.fetchOptional("select run_id, canonical_hash from device_configuration_run "
                + "where device_id = {0} and is_primary order by collected_at desc limit 1", deviceId));
        if (run.isEmpty()) return;
        String hash = run.get().get("canonical_hash", String.class);
        if (hash != null && !hash.isBlank()) put(cells, "configuration_hash", deviceId, Cell.of(hash.length() > 12 ? hash.substring(0, 12) : hash));
        var rows = tx.inTransaction(db -> db.fetch("select context, section, source, entry_count from device_configuration_index where run_id = {0}",
                run.get().get("run_id", String.class)));
        if (rows.isEmpty()) return;
        TreeSet<String> sections = new TreeSet<>();
        long entries = 0;
        for (var r : rows) {
            String source = r.get("source", String.class);
            int count = r.get("entry_count", Integer.class);
            String context = r.get("context", String.class);
            sections.add((context == null || context.isBlank() ? "" : context + "/") + r.get("section", String.class)
                    + (source == null ? "" : " [" + source + "]") + " = " + count);
            entries += count;
        }
        put(cells, "configuration_sections", deviceId, Cell.ofSet(sections.size() + " sections, " + entries + " entries", sections));
    }
}
