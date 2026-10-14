package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class SshHostKeyControllerTest {
    @Test void explicitConfirmationIsRequiredBeforeAnyDatabaseAccess() {
        var fixture = new Fixture();
        assertEquals(400, fixture.controller.accept("observation-fixture",
                new SshHostKeyController.AcceptRequest("yes"), fixture.request).getStatusCode().value());
        assertTrue(fixture.sql.isEmpty());
    }
    @Test void acceptUsesAttestedReEnrollmentAndResolvesAtomically() {
        var fixture = new Fixture();
        assertEquals(200, fixture.controller.accept("observation-fixture",
                new SshHostKeyController.AcceptRequest("ACCEPT NEW SSH KEY"), fixture.request).getStatusCode().value());
        assertEquals(1, fixture.transactions);
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("status = 'SUPERSEDED', superseded_by")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("INSERT INTO management_endpoint_ssh_trust") && s.contains("'ACTIVE'")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("ssh_host_key_accept")));
        assertTrue(fixture.bindings.stream().anyMatch(values -> values.contains("ACCEPTED")));
    }
    @Test void stalePinCannotBeOverwritten() {
        var fixture = new Fixture(); fixture.stale = true;
        assertEquals(409, fixture.controller.accept("observation-fixture",
                new SshHostKeyController.AcceptRequest("ACCEPT NEW SSH KEY"), fixture.request).getStatusCode().value());
        assertFalse(fixture.sql.stream().anyMatch(s -> s.contains("UPDATE management_endpoint_ssh_trust")));
    }
    @Test void rejectionPreservesTrustAndForcesRefusalWithAudit() {
        var fixture = new Fixture();
        assertEquals(200, fixture.controller.reject("observation-fixture", fixture.request).getStatusCode().value());
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("ssh_host_key_reject")));
        assertTrue(fixture.sql.stream().anyMatch(s -> s.contains("SET status = ?, strict_refusal = ?")));
        assertTrue(fixture.bindings.stream().anyMatch(values -> values.contains("REJECTED") && values.contains(true)));
        assertFalse(fixture.sql.stream().anyMatch(s -> s.contains("management_endpoint_ssh_trust")));
    }
    static class Fixture {
        final List<String> sql = new ArrayList<>();
        final List<List<Object>> bindings = new ArrayList<>();
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final SshHostKeyController controller;
        int transactions;
        boolean stale;
        Fixture() {
            request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-fixture");
            var db = DSL.using(new MockConnection(ctx -> {
                var query = ctx.sql(); sql.add(query); bindings.add(Arrays.asList(ctx.bindings()));
                if (query.startsWith("SELECT mode")) return row(new String[]{"mode"}, new Object[]{"WARN"});
                if (query.startsWith("SELECT * FROM ssh_host_key_observation")) return row(
                        new String[]{"status", "device_id", "management_address", "management_port", "key_algorithm", "presented_fingerprint", "expected_fingerprint", "last_seen"},
                        new Object[]{"OPEN", "device-fixture", "192.0.2.10", 22, "ssh-ed25519", "1".repeat(64), "0".repeat(64), Timestamp.from(Instant.EPOCH)});
                if (query.startsWith("SELECT fingerprint_sha256")) return row(new String[]{"fingerprint_sha256"}, new Object[]{(stale ? "2" : "0").repeat(64)});
                if (query.startsWith("SELECT trust_entry_id")) return row(new String[]{"trust_entry_id"}, new Object[]{"old-fixture-trust"});
                return new MockResult[]{new MockResult(1, null)};
            }), SQLDialect.POSTGRES);
            controller = new SshHostKeyController(new TransactionBoundary() {
                public <T> T inTransaction(Function<DSLContext, T> work) { transactions++; return work.apply(db); }
            });
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MockResult[] row(String[] names, Object[] values) {
        Field[] fields = new Field[names.length];
        for (int i = 0; i < names.length; i++) fields[i] = DSL.field(names[i], values[i].getClass());
        var db = DSL.using(SQLDialect.POSTGRES);
        var rows = db.newResult(fields); var row = db.newRecord(fields);
        for (int i = 0; i < names.length; i++) row.set(fields[i], values[i]);
        rows.add(row); return new MockResult[]{new MockResult(1, rows)};
    }
}
