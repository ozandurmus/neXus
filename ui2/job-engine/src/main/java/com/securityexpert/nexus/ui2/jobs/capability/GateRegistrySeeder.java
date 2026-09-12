package com.securityexpert.nexus.ui2.jobs.capability;

import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.persistence.gates.GateRegistryDao;
import com.securityexpert.nexus.ui2.persistence.gates.GateRowData;

/**
 * Seeds {@code gate_registry} from the committed fixture at worker startup
 * (adjudication F2). Never invents a row: every {@link GateRow} it upserts
 * came from {@code GateRegistryFixtureLoader} reading the version-controlled
 * YAML file, not from runtime input.
 */
public final class GateRegistrySeeder {

    private final GateRegistryDao dao;

    public GateRegistrySeeder(GateRegistryDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    public void seed(List<GateRow> fixtureRows) {
        for (GateRow row : fixtureRows) {
            dao.upsert(new GateRowData(
                    row.gateId(), row.vendor(), row.platformRoleScope(), row.shellContext(), row.transportKind(),
                    row.canonicalCommandKey(), row.actionClass().id(), row.signOffState().name(), row.timeoutS(),
                    row.retryRule(), row.maxFrequency(), row.sessionReuseRule(), row.unsupportedBehaviorRef(),
                    row.secretOutputRisk(), row.safeTelemetryFields(), row.sourceDocumentPointer()));
        }
    }
}
