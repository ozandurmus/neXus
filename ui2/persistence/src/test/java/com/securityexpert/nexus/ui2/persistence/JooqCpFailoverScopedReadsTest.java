package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * The one-cluster failover reads must run the same statements as the fleet reads, with only a
 * filter added: so the projection (columns, ordering, joins, status rules) cannot drift apart.
 */
class JooqCpFailoverScopedReadsTest {
    private record Captured(String sql, List<Object> binds) {}

    private static final class Probe {
        final List<Captured> seen = new ArrayList<>();
        final JooqCpFailoverRepository repository;
        Probe() {
            DSLContext dsl = DSL.using(new MockConnection(query -> {
                seen.add(new Captured(normalise(query.sql()), List.of(query.bindings())));
                return new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES)
                    .newResult(DSL.field("cluster_ref", String.class)))};
            }), SQLDialect.POSTGRES);
            repository = new JooqCpFailoverRepository(new TransactionBoundary() {
                @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(dsl); }
            });
        }
        Captured only() { assertEquals(1, seen.size()); return seen.get(0); }
    }

    private static String normalise(String sql) { return sql.replaceAll("\\s+", " ").trim(); }

    @Test
    void readinessScopeAddsOnlyAClusterFilter() {
        var fleet = new Probe(); fleet.repository.readinessStatuses();
        var scoped = new Probe(); scoped.repository.readinessStatuses("synthetic-cluster");
        assertTrue(fleet.only().binds().isEmpty());
        assertEquals(List.of("synthetic-cluster"), scoped.only().binds());
        assertTrue(scoped.only().sql().contains("and cluster_ref=? order by vendor,cluster_ref"));
        assertEquals(fleet.only().sql(), scoped.only().sql().replace(" and cluster_ref=?", ""));
    }

    @Test
    void statusScopeFiltersBothUnitKeySourcesAndNothingElse() {
        var fleet = new Probe(); fleet.repository.summaryStatuses();
        var scoped = new Probe(); scoped.repository.summaryStatuses("synthetic-cluster");
        assertTrue(fleet.only().binds().isEmpty());
        assertEquals(List.of("synthetic-cluster", "synthetic-cluster"), scoped.only().binds());
        assertEquals(fleet.only().sql(), scoped.only().sql().replace(" where cluster_ref=?", ""));
    }

    @Test
    void memberFactScopeRestrictsTheDevicesOnly() {
        var fleet = new Probe(); fleet.repository.summaryMembers();
        var scoped = new Probe(); scoped.repository.summaryMembers(List.of("device-a", "device-b"));
        assertFalse(fleet.only().sql().contains("where d.device_id"));
        assertEquals(List.of("device-a", "device-b"), scoped.only().binds());
        assertTrue(scoped.only().sql().endsWith(" where d.device_id in (?,?)"));
        assertEquals(fleet.only().sql(), scoped.only().sql().replace(" where d.device_id in (?,?)", ""));
        var none = new Probe();
        assertTrue(none.repository.summaryMembers(List.of()).isEmpty());
        assertTrue(none.seen.isEmpty());
    }
}
