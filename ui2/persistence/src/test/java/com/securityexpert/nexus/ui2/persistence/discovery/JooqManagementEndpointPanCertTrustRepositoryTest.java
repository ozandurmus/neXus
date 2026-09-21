package com.securityexpert.nexus.ui2.persistence.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

class JooqManagementEndpointPanCertTrustRepositoryTest {
    @Test
    void rotationRetainsThePriorAuthorization() {
        List<String> sql = new ArrayList<>();
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            if (context.sql().contains("FOR UPDATE")) {
                var field = DSL.field("trust_entry_id", String.class);
                var rows = DSL.using(SQLDialect.POSTGRES).newResult(field);
                var row = DSL.using(SQLDialect.POSTGRES).newRecord(field);
                row.set(field, "old-opaque-entry");
                rows.add(row);
                return new MockResult[] { new MockResult(1, rows) };
            }
            return new MockResult[] { new MockResult(1, null) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary boundary = new TransactionBoundary() {
            public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
        };
        var repository = new JooqManagementEndpointPanCertTrustRepository(boundary);

        assertFalse(repository.enroll("192.0.2.10", 443, "0".repeat(64),
                "fixture-actor", Instant.EPOCH, false));
        sql.clear();
        assertTrue(repository.enroll("192.0.2.10", 443, "1".repeat(64),
                "fixture-actor", Instant.EPOCH, true));
        assertTrue(sql.stream().anyMatch(statement -> statement.contains("status = 'SUPERSEDED', superseded_by")));
        assertTrue(sql.stream().anyMatch(statement -> statement.contains("INSERT INTO management_endpoint_pan_cert_trust")));
        assertTrue(sql.stream().noneMatch(statement -> statement.contains("DELETE")));
    }
}
