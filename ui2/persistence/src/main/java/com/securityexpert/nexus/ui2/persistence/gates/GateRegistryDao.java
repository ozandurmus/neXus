package com.securityexpert.nexus.ui2.persistence.gates;

import java.util.List;

/**
 * Raw {@code gate_registry} access (adjudication F2: the table is created
 * by this movement's own {@code V4} migration). {@link #upsert} is the
 * seeding path (F2: "seeded from a version-controlled fixture committed
 * alongside the capability specs") -- never called with author-typed,
 * runtime-invented data, only with rows read back from that fixture at
 * startup.
 */
public interface GateRegistryDao {

    List<GateRowData> findByCanonicalKey(String vendor, String platformRoleScope, String shellContext,
            String transportKind, String canonicalCommandKey);

    void upsert(GateRowData row);
}
