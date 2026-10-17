package com.securityexpert.nexus.ui2.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;

/**
 * The System storage panel reports what is on the artefact volume. A pruned artefact keeps its manifest row (its
 * removal is a ledger row), so a read without the liveness predicate counted every archive ever taken: on 2026-10-18
 * it showed 887 GB and about 17 archives per device for a 240 GB store holding about 5 per device.
 */
class SystemStatusStorageLivenessTest {

    @Test
    void everyBackupArtefactReadInTheStoragePanelExcludesPrunedArtefacts() {
        List<String> sql = new ArrayList<>();
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String statement = ctx.sql();
            sql.add(statement);
            if (statement.contains("from configuration_artefact")) {
                var result = create.newResult(DSL.field("artefacts", Long.class), DSL.field("stored_bytes", Long.class));
                var row = create.newRecord(DSL.field("artefacts", Long.class), DSL.field("stored_bytes", Long.class));
                row.values(0L, 0L);
                result.add(row);
                return new MockResult[] {new MockResult(1, result)};
            }
            if (statement.contains("octet_length") || statement.contains("pg_database_size")) {
                var result = create.newResult(DSL.field("v", Long.class));
                var row = create.newRecord(DSL.field("v", Long.class));
                row.values(0L);
                result.add(row);
                return new MockResult[] {new MockResult(1, result)};
            }
            return new MockResult[] {new MockResult(0, create.newResult())};
        }), SQLDialect.POSTGRES);
        DeviceRepository devices = (DeviceRepository) Proxy.newProxyInstance(DeviceRepository.class.getClassLoader(),
                new Class<?>[] {DeviceRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("listAll")) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                });

        var body = new SystemStatusService(new JooqTransactionBoundary(dsl), devices).storage();

        List<String> artefactReads = sql.stream().filter(s -> s.contains("from backup_artefact")).toList();
        assertEquals(2, artefactReads.size(), "the per-vendor table and the top-devices table");
        for (String read : artefactReads) {
            assertTrue(read.contains("not exists (select 1 from artefact_retention_ledger l where l.artefact_id = ")
                    && read.contains("l.event = 'removed')"), "pruned artefacts are not stored bytes: " + read);
        }
        assertEquals(List.of(), body.get("backups"));
        assertEquals(List.of(), body.get("top_devices"));
    }
}
