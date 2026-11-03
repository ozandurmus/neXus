package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import org.springframework.stereotype.Service;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.policy.*;

/** Offline fallback only when the existing management tree contains no Panorama node. */
@Service
public final class LocalFirewallPolicyService {
    private static final long DEFAULT_MAX_WEIGHT = 100_000;
    private static final long DEFAULT_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private final TransactionBoundary tx;
    private final ArtefactStoreAccess store;
    /** Decrypting and XML-parsing a stored configuration dominated every catalog call, so the parse is reused (see {@link #snapshots}). */
    private final VersionedWeightedCache<List<PolicySnapshot>> parsed;
    private final java.util.concurrent.atomic.AtomicLong parses = new java.util.concurrent.atomic.AtomicLong();
    @org.springframework.beans.factory.annotation.Autowired
    public LocalFirewallPolicyService(TransactionBoundary tx, ArtefactStoreAccess store) {
        this(tx, store, DEFAULT_MAX_WEIGHT, DEFAULT_TTL_NANOS, System::nanoTime);
    }
    LocalFirewallPolicyService(TransactionBoundary tx, ArtefactStoreAccess store, long maxWeight, long ttlNanos,
            java.util.function.LongSupplier nanoClock) {
        this.tx = tx; this.store = store; this.parsed = new VersionedWeightedCache<>(maxWeight, ttlNanos, nanoClock);
    }
    /** Number of decrypt-and-parse runs, for tests and diagnostics. */
    long parseCount() { return parses.get(); }

    /**
     * The listing query is cheap and runs on every call; the decrypt and XML parse run only when a device's effective-running
     * artefact (reference and collection time) differs from the cached one, or the entry is older than the short TTL. A
     * newer artefact therefore replaces the entry on the next call, and a device that is no longer listed is dropped.
     * Parsed snapshots are immutable records and are shared between callers.
     */
    public List<PolicySnapshot> snapshots() {
        var rows = tx.inTransaction(db -> db.fetch("select distinct on (d.device_id) d.device_id, r.collected_at, "
            + "r.artefact_ref, a.wrapped_data_key, c.compression from devices d "
            + "join device_configuration_run r on r.device_id = d.device_id and r.read_kind = 'effective_running' "
            + "join configuration_artefact c on c.artefact_ref = r.artefact_ref "
            + "join backup_artefact a on a.recovery_volume_path = r.artefact_ref and a.device_id = d.device_id "
            + "where d.vendor_hint = 'palo_alto' and d.role in ('gateway', 'firewall') and not d.disabled "
            + "and d.enrollment_state in ('ENROLLED', 'DEGRADED') "
            + "and not exists (select 1 from devices p where p.vendor_hint = 'palo_alto' and p.role = 'management_server') "
            + "and not exists (select 1 from artefact_retention_ledger l where l.artefact_id = a.artefact_id and l.event = 'removed') "
            + "order by d.device_id, r.collected_at desc"));
        if (!rows.isEmpty() && store.storeOrNull() == null) throw new IllegalStateException("LOCAL_POLICY_CONFIGURATION_UNAVAILABLE");
        List<PolicySnapshot> result = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (var row : rows) {
            String deviceId = row.get("device_id", String.class);
            String version = row.get("artefact_ref", String.class) + "|" + row.get("collected_at", java.sql.Timestamp.class).toInstant();
            listed.add(deviceId);
            var cached = parsed.get(deviceId, version);
            if (cached != null) { result.addAll(cached); continue; }
            List<PolicySnapshot> fresh;
            try (var input = store.storeOrNull().retrieve(new ArtefactRef(row.get("artefact_ref", String.class)),
                    row.get("wrapped_data_key", byte[].class), "gzip".equals(row.get("compression", String.class)))) {
                parses.incrementAndGet();
                fresh = List.copyOf(new PanoramaPolicyMapper().local(deviceId,
                        row.get("collected_at", java.sql.Timestamp.class).toInstant().toString(), PolicySnapshot.ref("configuration-artefact", row.get("artefact_ref", String.class)),
                        PolicyXml.parse(input, true).getDocumentElement()));
            } catch (Exception unavailable) {
                throw new IllegalStateException("LOCAL_POLICY_CONFIGURATION_UNAVAILABLE");
            }
            long weight = 0;
            for (var snapshot : fresh) weight += snapshot.sections().stream().mapToLong(section -> section.rules().size()).sum() + snapshot.objects().size();
            parsed.put(deviceId, version, fresh, weight);
            result.addAll(fresh);
        }
        parsed.retainOnly(listed);
        return result;
    }
}
