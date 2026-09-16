package com.securityexpert.nexus.ui2.persistence.discovery;

import static org.junit.jupiter.api.Assertions.*;

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

class JooqManagementEndpointSshTrustRepositoryTest {
    @Test
    void rotationRetainsOldRowAndInsertsNewAuthorizationInsideOneTransaction() {
        List<String> sql = new ArrayList<>();
        int[] transactions = {0};
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            if (context.sql().contains("FOR UPDATE")) {
                var field = DSL.field("trust_entry_id", String.class);
                var rows = DSL.using(SQLDialect.POSTGRES).newResult(field);
                var row = DSL.using(SQLDialect.POSTGRES).newRecord(field);
                row.set(field, "old-opaque-entry"); rows.add(row);
                return new MockResult[]{new MockResult(1, rows)};
            }
            return new MockResult[]{new MockResult(1, null)};
        }), SQLDialect.POSTGRES);
        TransactionBoundary boundary = new TransactionBoundary() {
            public <T> T inTransaction(Function<DSLContext, T> work) {
                transactions[0]++; return work.apply(dsl);
            }
        };
        var repository = new JooqManagementEndpointSshTrustRepository(boundary);
        assertFalse(repository.enroll("fixture-management", 2222, "ssh-ed25519", "0".repeat(64),
                "fixture-actor", Instant.EPOCH, false));
        assertEquals(3, sql.size(), "enrollment never silently overwrites an active entry");
        sql.clear(); transactions[0] = 0;
        assertTrue(repository.enroll("fixture-management", 2222, "ssh-ed25519", "0".repeat(64),
                "fixture-actor", Instant.EPOCH, true));
        assertEquals(1, transactions[0]);
        assertTrue(sql.get(2).contains("FOR UPDATE"));
        assertTrue(sql.get(3).contains("status = 'SUPERSEDED', superseded_by"));
        assertTrue(sql.get(4).contains("INSERT INTO management_endpoint_ssh_trust"));
        assertTrue(sql.stream().noneMatch(statement -> statement.contains("DELETE")));
    }
}
