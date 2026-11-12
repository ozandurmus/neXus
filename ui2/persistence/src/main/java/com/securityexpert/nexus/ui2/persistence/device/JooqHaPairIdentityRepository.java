package com.securityexpert.nexus.ui2.persistence.device;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ implementation of {@link HaPairIdentityRepository} (V209). */
public final class JooqHaPairIdentityRepository implements HaPairIdentityRepository {

    /** The vendors whose pairs carry a serial identity (failover Phase 0). */
    static final Set<String> VENDORS = Set.of("cisco_asa", "fortinet");

    private final TransactionBoundary transactionBoundary;

    public JooqHaPairIdentityRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public Optional<HaPairIdentity.State> observe(String deviceId, String actor) {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(actor, "actor");
        return transactionBoundary.inTransaction(dsl -> {
            Record self = dsl.fetchOne("select cluster_member_ref, vendor_hint from devices where device_id = {0}", deviceId);
            if (self == null || self.get("cluster_member_ref") == null || !VENDORS.contains(self.get("vendor_hint", String.class))) {
                return Optional.<HaPairIdentity.State>empty();
            }
            String ref = self.get("cluster_member_ref", String.class);
            String vendor = self.get("vendor_hint", String.class);
            // Serialise observations of one pair: the two members' inventories may finish at the same time.
            dsl.fetch("select pg_advisory_xact_lock(hashtext({0}))", "ha_pair_identity:" + ref);
            List<Record> members = dsl.fetch("select d.device_id, f.serial_number from devices d "
                    + "left join device_platform_facts f on f.device_id = d.device_id "
                    + "where d.cluster_member_ref = {0} and d.vendor_hint = {1} and not d.disabled "
                    + "and d.enrollment_state in ('ENROLLED','DEGRADED') order by d.device_id", ref, vendor);
            if (members.size() != 2) {
                return Optional.<HaPairIdentity.State>empty();
            }
            Optional<HaPairIdentity> recorded = find(dsl, ref);
            HaPairIdentity.Decision decision = HaPairIdentity.decide(recorded,
                    Optional.ofNullable(members.get(0).get("serial_number", String.class)),
                    Optional.ofNullable(members.get(1).get("serial_number", String.class)));
            if (recorded.isEmpty() && decision.toRecord().isPresent()) {
                String[] pair = decision.toRecord().get();
                dsl.execute("insert into ha_pair_identity(cluster_member_ref, vendor, unit_serial_low, unit_serial_high, "
                        + "recorded_by, last_state) values ({0}, {1}, {2}, {3}, {4}, {5}) on conflict (cluster_member_ref) do nothing",
                        ref, vendor, pair[0], pair[1], actor, decision.state().name());
            } else if (recorded.isPresent()) {
                dsl.execute("update ha_pair_identity set last_state = {1}, last_checked_at = now() where cluster_member_ref = {0}",
                        ref, decision.state().name());
            }
            return Optional.of(decision.state());
        });
    }

    @Override
    public Optional<HaPairIdentity> find(String clusterMemberRef) {
        return transactionBoundary.inTransaction(dsl -> find(dsl, clusterMemberRef));
    }

    private static Optional<HaPairIdentity> find(DSLContext dsl, String ref) {
        return dsl.fetch("select cluster_member_ref, vendor, unit_serial_low, unit_serial_high, last_state "
                + "from ha_pair_identity where cluster_member_ref = {0}", ref).stream().findFirst()
                .map(r -> new HaPairIdentity(r.get("cluster_member_ref", String.class), r.get("vendor", String.class),
                        r.get("unit_serial_low", String.class), r.get("unit_serial_high", String.class),
                        HaPairIdentity.State.valueOf(r.get("last_state", String.class))));
    }
}
