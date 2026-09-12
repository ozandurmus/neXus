package com.securityexpert.nexus.ui2.jobs.capability;

import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.persistence.gates.GateRegistryDao;
import com.securityexpert.nexus.ui2.persistence.gates.GateRowData;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * {@link GateRegistryPort} adapter over {@code persistence}'s {@link
 * GateRegistryDao}. Lives in {@code job-engine} because it must reference
 * both {@code capability-registry} types (the port it implements) and
 * {@code persistence} (the jOOQ-backed DAO) -- {@code job-engine} is the
 * one module allowed to depend on both. Never imports {@code org.jooq}
 * itself (DIR-7).
 */
public final class PersistenceGateRegistryPort implements GateRegistryPort {

    private final GateRegistryDao dao;

    public PersistenceGateRegistryPort(GateRegistryDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public List<GateRow> findByCanonicalKey(CanonicalCommandKey key) {
        return dao.findByCanonicalKey(key.vendor(), key.platformRoleScope(), key.shellContext(), key.transportKind(),
                        key.commandKey())
                .stream().map(PersistenceGateRegistryPort::toGateRow).toList();
    }

    private static GateRow toGateRow(GateRowData row) {
        return new GateRow(row.gateId(), row.vendor(), row.platformRoleScope(), row.shellContext(),
                row.transportKind(), row.canonicalCommandKey(), ActionClass.fromId(row.actionClass()),
                SignOffState.fromColumnValue(row.signOffState()), row.timeoutS(), row.retryRule(),
                row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(), row.secretOutputRisk(),
                row.safeTelemetryFields(), row.sourceDocumentPointer());
    }
}
