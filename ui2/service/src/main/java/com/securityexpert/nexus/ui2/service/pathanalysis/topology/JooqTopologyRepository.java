package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ implementation of {@link TopologyRepository}. Snapshot and conflict rows are derived and unaudited (V186), so
 * they go through the plain boundary; every override write goes through {@link AuditedTransactionBoundary}.
 */
public final class JooqTopologyRepository implements TopologyRepository {

    private static final String OVERRIDE_COLUMNS = "override_id, action, link_kind, a_device_id, a_context, "
            + "a_interface_name, b_device_id, b_context, b_interface_name, assumed_subnet, reason_code, note, created_by, "
            + "created_at, revoked_at, revoked_by, orphaned_at";

    private final TransactionBoundary tx;
    private final AuditedTransactionBoundary audited;

    public JooqTopologyRepository(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audited = new AuditedTransactionBoundary(tx);
    }

    @Override
    public int recordSnapshot(SnapshotHeader h, List<TopologyGraph.Conflict> conflicts, int conflictLimit, int keep) {
        if (conflictLimit < 0 || keep < 1) {
            throw new IllegalArgumentException("limits");
        }
        int stored = Math.min(conflictLimit, conflicts.size());
        TopologyGraph.Counts c = h.counts();
        return tx.inTransaction(dsl -> {
            dsl.execute("insert into topology_snapshot(snapshot_id, built_at, built_by, inventory_fingerprint, "
                    + "override_fingerprint, device_count, devices_without_inventory, node_count, cluster_unit_count, "
                    + "cloud_count, segment_count, attachment_count, conflict_count, conflicts_stored, overrides_applied, "
                    + "overrides_unmatched, build_millis) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, "
                    + "{11}, {12}, {13}, {14}, {15}, {16})",
                    h.snapshotId(), utc(h.builtAt()), h.builtBy(), h.fingerprints().inventory(), h.fingerprints().overrides(),
                    c.devices(), c.devicesWithoutInventory(), c.nodes(), c.clusterUnits(), c.clouds(), c.segments(),
                    c.attachments(), c.conflicts(), stored, c.overridesApplied(), c.overridesUnmatched(), h.buildMillis());
            for (TopologyGraph.Conflict conflict : conflicts.subList(0, stored)) {
                dsl.execute("insert into topology_conflict(conflict_id, snapshot_id, kind, resolution, device_id, "
                        + "peer_device_id, context, network, override_id) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8})",
                        conflict.conflictId(), h.snapshotId(), conflict.kind().name(), conflict.resolution().name(),
                        conflict.deviceId().orElse(null), conflict.peerDeviceId().orElse(null),
                        conflict.context().filter(s -> !s.isBlank()).orElse(null),
                        conflict.network().map(Ipv4Prefix::toString).orElse(null), conflict.overrideId().orElse(null));
            }
            dsl.execute("delete from topology_snapshot where snapshot_id in (select snapshot_id from topology_snapshot "
                    + "order by built_at desc, snapshot_id desc offset {0})", keep);
            return stored;
        });
    }

    @Override
    public Optional<SnapshotHeader> latestSnapshot() {
        return tx.inTransaction(dsl -> dsl.fetch("select * from topology_snapshot order by built_at desc, snapshot_id desc "
                + "limit 1").stream().findFirst().map(JooqTopologyRepository::toHeader));
    }

    @Override
    public List<TopologyLinkOverride> listOverrides() {
        return tx.inTransaction(dsl -> dsl.fetch("select " + OVERRIDE_COLUMNS + " from topology_link_override "
                + "order by created_at, override_id").stream().map(JooqTopologyRepository::toOverride).toList());
    }

    @Override
    public Optional<TopologyLinkOverride> findOverride(String overrideId) {
        return tx.inTransaction(dsl -> dsl.fetch("select " + OVERRIDE_COLUMNS + " from topology_link_override "
                + "where override_id = {0}", overrideId).stream().findFirst().map(JooqTopologyRepository::toOverride));
    }

    @Override
    public void insertOverride(TopologyLinkOverride o, String actorFingerprint, String actionId) {
        audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute("insert into topology_link_override("
                + OVERRIDE_COLUMNS + ") values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, {11}, {12}, {13}, "
                + "null, null, null)",
                o.overrideId(), o.action(), o.linkKind(), o.aDeviceId().orElseThrow(), o.aContext(), o.aInterfaceName(),
                o.bDeviceId().orElseThrow(), o.bContext(), o.bInterfaceName(), o.assumedSubnet().orElse(null),
                o.reasonCode(), o.note().orElse(null), o.createdBy(), utc(o.createdAt())));
    }

    @Override
    public boolean revokeOverride(String overrideId, String actorFingerprint, String actionId, Instant at) {
        return audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute("update topology_link_override "
                + "set revoked_at = {0}, revoked_by = {1} where override_id = {2} and revoked_at is null",
                utc(at), actorFingerprint, overrideId)) == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Optional<Instant> instant(Record r, String column) {
        return Optional.ofNullable(r.get(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static Optional<String> text(Record r, String column) {
        return Optional.ofNullable(r.get(column, String.class));
    }

    private static int count(Record r, String column) {
        return r.get(column, Integer.class);
    }

    private static SnapshotHeader toHeader(Record r) {
        return new SnapshotHeader(r.get("snapshot_id", String.class), instant(r, "built_at").orElseThrow(),
                r.get("built_by", String.class), new TopologyGraph.Fingerprints(r.get("inventory_fingerprint", String.class),
                        r.get("override_fingerprint", String.class)),
                new TopologyGraph.Counts(count(r, "device_count"), count(r, "devices_without_inventory"),
                        count(r, "node_count"), count(r, "cluster_unit_count"), count(r, "cloud_count"),
                        count(r, "segment_count"), count(r, "attachment_count"), count(r, "conflict_count"),
                        count(r, "overrides_applied"), count(r, "overrides_unmatched")),
                count(r, "conflicts_stored"), r.get("build_millis", Long.class));
    }

    private static TopologyLinkOverride toOverride(Record r) {
        return new TopologyLinkOverride(r.get("override_id", String.class), r.get("action", String.class),
                r.get("link_kind", String.class), text(r, "a_device_id"), r.get("a_context", String.class),
                r.get("a_interface_name", String.class), text(r, "b_device_id"), r.get("b_context", String.class),
                r.get("b_interface_name", String.class), text(r, "assumed_subnet"), r.get("reason_code", String.class),
                text(r, "note"), r.get("created_by", String.class), instant(r, "created_at").orElseThrow(),
                instant(r, "revoked_at"), text(r, "revoked_by"), instant(r, "orphaned_at"));
    }
}
