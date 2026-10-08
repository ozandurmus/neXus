package com.securityexpert.nexus.ui2.persistence.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class ModuleRuntimeRepositoryTest {
    @Test void releaseIsOwnerQualifiedAndDoesNotMutateDrainOrJobFences() {
        var sql = new ArrayList<String>();
        var bindings = new ArrayList<Object>();
        var runtime = new ModuleRuntimeRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            bindings.addAll(Arrays.asList(context.bindings()));
            return new MockResult[]{new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));

        runtime.releaseOwnership("general", "general-old");
        assertTrue(sql.get(1).contains("module_owner_release"));
        assertTrue(sql.get(2).contains("pg_advisory_xact_lock(294611)"));
        assertEquals("update module_runtime_control set owner_instance=null,owner_heartbeat_at=null "
            + "where module=? and owner_instance=?", sql.get(3));
        assertEquals(List.of("general", "general-old"), bindings);
        int queries = sql.size();
        assertFalse(runtime.heartbeat("general", "general-old"));
        assertEquals(queries, sql.size(), "A queued heartbeat must not reacquire the released row");
    }

    @Test void releaseWaitsForAnAlreadyRunningHeartbeatAndRejectsLaterHeartbeats() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var releasing = new CountDownLatch(1);
        var sql = Collections.synchronizedList(new ArrayList<String>());
        var runtime = new ModuleRuntimeRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            if (context.sql().startsWith("update module_runtime_control set owner_instance=?")) {
                entered.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new java.sql.SQLException("Synthetic heartbeat timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.sql.SQLException(interrupted);
                }
            }
            return new MockResult[]{new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var heartbeat = executor.submit(() -> runtime.heartbeat("policy", "policy-old"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var release = executor.submit(() -> {
                releasing.countDown();
                runtime.releaseOwnership("policy", "policy-old");
            });
            assertTrue(releasing.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> release.get(100, TimeUnit.MILLISECONDS));
            finish.countDown();
            assertTrue(heartbeat.get(5, TimeUnit.SECONDS));
            release.get(5, TimeUnit.SECONDS);
            assertTrue(sql.get(sql.size() - 1).startsWith("update module_runtime_control set owner_instance=null"));
            assertFalse(runtime.heartbeat("policy", "policy-old"));
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }
}
