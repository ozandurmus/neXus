package com.securityexpert.nexus.ui2.persistence.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class PolicyJsonWriteTest {
    @Test void largeSnapshotUsesBoundedUnicodeChunksAndOneFinalHistoryWrite() {
        var statements = new ArrayList<String>();
        var fragments = new ArrayList<String>();
        String payload = "{\"synthetic\":\"" + "x".repeat(PolicyJsonWrite.CHUNK_CHARS - 15)
            + "😀é".repeat(30000) + "\"}";
        var db = DSL.using(new MockConnection(context -> {
            statements.add(context.sql());
            if (context.sql().startsWith("insert into policy_json_")) {
                String fragment = (String) context.bindings()[1];
                assertTrue(fragment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65536);
                assertFalse(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1)));
                assertFalse(Character.isLowSurrogate(fragment.charAt(0)));
                fragments.add(fragment);
            }
            if (context.sql().startsWith("insert into policy_snapshot")) {
                assertFalse(Arrays.asList(context.bindings()).contains(payload));
                assertTrue(context.sql().contains("string_agg(fragment, '' order by ordinal)"));
            }
            return new MockResult[] { new MockResult(1, null) };
        }), SQLDialect.POSTGRES);
        new PolicySnapshotRepository(new JooqTransactionBoundary(db)).save(
            new PolicySnapshotRepository.Stored("policy-1", "2026-10-05T00:00:00Z", "{}", payload),
            "synthetic-actor", "policy_collect_checkpoint");
        assertEquals(payload, String.join("", fragments)); assertTrue(fragments.size() > 1);
        assertEquals(1, statements.stream().filter(sql -> sql.startsWith("insert into policy_snapshot")).count());
        assertTrue(statements.stream().anyMatch(sql -> sql.startsWith("create temporary table") && sql.endsWith("on commit drop")));
    }

    @Test void smallAndNullPayloadsDoNotCreateStagingTables() {
        var db = DSL.using(SQLDialect.POSTGRES);
        assertEquals("{}", ((org.jooq.Param<?>) PolicyJsonWrite.json(db, "{}")).getValue());
        assertNull(((org.jooq.Param<?>) PolicyJsonWrite.json(db, null)).getValue());
        assertEquals(2, PolicyJsonWrite.bytes("é", null));
    }

    @Test void databaseExceptionDoesNotExposeSqlOrParameterValues() {
        var db = DSL.using(new MockConnection(context -> {
            throw new java.sql.SQLException("synthetic private SQL payload");
        }), SQLDialect.POSTGRES);
        var failure = assertThrows(PolicyDatabaseFailure.class, () ->
            new PolicySnapshotRepository(new JooqTransactionBoundary(db)).save(
                new PolicySnapshotRepository.Stored("policy-1", "2026-10-05T00:00:00Z", "{}", "{}"),
                "synthetic-actor", "policy_collect_checkpoint"));
        assertEquals("POLICY_DB_WRITE_FAILED", failure.getMessage()); assertNull(failure.getCause());
    }
}
