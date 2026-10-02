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
    private final TransactionBoundary tx;
    private final ArtefactStoreAccess store;
    public LocalFirewallPolicyService(TransactionBoundary tx, ArtefactStoreAccess store) { this.tx = tx; this.store = store; }
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
        for (var row : rows) {
            try (var input = store.storeOrNull().retrieve(new ArtefactRef(row.get("artefact_ref", String.class)),
                    row.get("wrapped_data_key", byte[].class), "gzip".equals(row.get("compression", String.class)))) {
                result.addAll(new PanoramaPolicyMapper().local(row.get("device_id", String.class),
                        row.get("collected_at", java.sql.Timestamp.class).toInstant().toString(), PolicySnapshot.ref("configuration-artefact", row.get("artefact_ref", String.class)),
                        PolicyXml.parse(input, true).getDocumentElement()));
            } catch (Exception unavailable) {
                throw new IllegalStateException("LOCAL_POLICY_CONFIGURATION_UNAVAILABLE");
            }
        }
        return result;
    }
}
