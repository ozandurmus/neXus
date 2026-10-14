package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import java.time.Instant;
import java.util.*;

class PolicyChangeRepositoryTest {
    @Test void optionalFiltersAreBoundIndexedPredicatesAndPagingNeverUsesOffset() {
        var sql = new ArrayList<String>(); var bindings = new ArrayList<Object>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
            return new MockResult[] { new MockResult(0, create.fetchFromStringData(new String[] { "revision_id" })) };
        }), SQLDialect.POSTGRES)));
        repository.rows(new PolicyChangeRepository.Filter(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"),
            "policy-001", "device-001", "modified", "source"), 100, new PolicyChangeRepository.Position(Instant.parse("2026-10-08T00:00:00Z"), 80));
        String query = sql.get(0);
        assertTrue(query.contains("h.policy_id = ?")); assertTrue(query.contains("h.change_type = ?"));
        assertTrue(query.contains("h.changes @>")); assertTrue(query.contains("s.metadata->'targets' @>"));
        assertTrue(query.contains("h.revision_id) < (?::timestamptz, ?)")); assertFalse(query.toLowerCase().contains("offset"));
        assertTrue(query.contains("limit 500"));
        assertEquals(List.of("2026-10-01T00:00:00Z", "2026-10-09T00:00:00Z", 100L, "policy-001", "device-001", "modified", "source", "2026-10-08T00:00:00Z", 80L), bindings);
        bindings.clear(); sql.clear();
        repository.rows(new PolicyChangeRepository.Filter(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"), "", "", "", ""), 100, null);
        assertFalse(sql.get(0).contains("h.change_type = ?")); assertFalse(sql.get(0).contains("h.policy_id = ?"));
        assertEquals(3, bindings.size());
    }
}
