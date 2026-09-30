package com.securityexpert.nexus.ui2.persistence.https;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.*;

class HttpsCertificateTrustRepositoryTest {
    private static final String ADDRESS = "192.0.2.10";
    private static final Certificate CERT = new Certificate("a".repeat(64), "FW-TANGO-04", "Synthetic issuer", Instant.parse("2030-01-01T00:00:00Z"));
    private static final class Store implements TransactionBoundary {
        String active;
        boolean pending;
        boolean strict;
        final List<String> sql = new ArrayList<>();
        final List<Object[]> inserts = new ArrayList<>();
        Object[] jobUpdate;
        final DSLContext dsl = DSL.using(new MockConnection(ctx -> {
            String query = ctx.sql(); sql.add(query);
            if (query.startsWith("update jobs set state")) jobUpdate = ctx.bindings();
            DSLContext create = DSL.using(SQLDialect.POSTGRES);
            String[][] data = {{"empty"}};
            if (query.startsWith("SELECT fingerprint_sha256"))
                data = active == null ? new String[][]{{"fingerprint_sha256"}}
                        : new String[][]{{"fingerprint_sha256"}, {active}};
            else if (query.startsWith("SELECT trust_entry_id"))
                data = pending ? new String[][]{{"trust_entry_id"}, {"pending-id"}} : new String[][]{{"trust_entry_id"}};
            else if (query.startsWith("SELECT e.address_ref"))
                data = new String[][]{{"address_ref", "https_certificate_strict", "default_port"},
                        {ADDRESS + ":8443", String.valueOf(strict), "443"}};
            else if (query.startsWith("INSERT INTO https_endpoint_cert_trust")) inserts.add(ctx.bindings());
            var rows = create.fetchFromStringData(data);
            return new MockResult[]{query.startsWith("SELECT") ? new MockResult(rows.size(), rows) : new MockResult(1, null)};
        }), SQLDialect.POSTGRES);
        @Override public <T> T inTransaction(Function<DSLContext,T> work) { return work.apply(dsl); }
    }

    @Test void jobTerminalResultRetainsTheCertificateWarning() {
        var store = new Store();
        var jobs = new com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao(store);
        try (var scope = HttpsCertificateWarnings.open()) {
            HttpsCertificateWarnings.current().set(true);
            assertTrue(jobs.transitionState("synthetic-job", 1, "EXECUTING", "COMPLETED", "synthetic-actor", "job_completed", "backup saved"));
            assertTrue(java.util.Arrays.asList(store.jobUpdate).contains("backup saved; certificate changed"));
        }
        jobs.transitionState("next-job", 1, "EXECUTING", "COMPLETED", "synthetic-actor", "job_completed", "backup saved");
        assertFalse(java.util.Arrays.asList(store.jobUpdate).contains("backup saved; certificate changed"));
    }

    @Test void firstUseAndMatchUseTheSameEndpointBeforeAndAfterEnrollment() {
        var store = new Store(); var repo = new HttpsCertificateTrustRepository(store);
        assertEquals(Decision.FIRST_USE, repo.observe(ADDRESS, 8443, CERT));
        assertEquals("ACTIVE", store.inserts.getFirst()[7]);
        assertEquals(ADDRESS, store.inserts.getFirst()[1]);
        assertEquals(8443, store.inserts.getFirst()[2]);
        store.active = CERT.fingerprintSha256();
        assertEquals(Decision.MATCH, repo.observe(ADDRESS, 8443, CERT));
        assertEquals(1, store.inserts.size());
        assertTrue(store.sql.stream().anyMatch(s -> s.contains("pg_advisory_xact_lock")));
    }

    @Test void mismatchWarnsAndRecordsPendingWithoutReplacingActive() {
        var store = new Store(); store.active = "b".repeat(64);
        var repo = new HttpsCertificateTrustRepository(store);
        assertEquals(Decision.WARN, repo.observe(ADDRESS, 8443, CERT));
        assertEquals("PENDING", store.inserts.getFirst()[7]);
        assertNull(store.inserts.getFirst()[8]);
        assertTrue(store.sql.stream().noneMatch(s -> s.startsWith("UPDATE")));
        store.pending = true;
        assertEquals(Decision.WARN, repo.observe(ADDRESS, 8443, CERT));
        assertEquals(1, store.inserts.size(), "repeated mismatch does not duplicate pending evidence or audit");
    }

    @Test void strictRefusesButStillRecordsThePendingCertificate() {
        var store = new Store(); store.active = "b".repeat(64); store.strict = true;
        assertEquals(Decision.REFUSE, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 8443, CERT));
        assertEquals("PENDING", store.inserts.getFirst()[7]);
        assertEquals(Decision.WARN, new HttpsCertificateTrustRepository(store).observe(ADDRESS, 443, CERT),
                "strict policy for another port must not apply");
    }

    @Test void acceptSupersedesOldEvidenceAndActivatesOnlyTheSelectedPendingRecord() {
        var store = new Store(); store.pending = true;
        assertTrue(new HttpsCertificateTrustRepository(store).accept(ADDRESS, 8443, "pending-id", "synthetic-actor"));
        var updates = store.sql.stream().filter(s -> s.startsWith("UPDATE")).toList();
        assertEquals(2, updates.size());
        assertTrue(updates.getFirst().contains("status = 'SUPERSEDED'"));
        assertTrue(updates.getLast().contains("status = 'ACTIVE', authorized_by"));
        assertTrue(store.sql.stream().noneMatch(s -> s.startsWith("DELETE")));
        store.pending = false; store.sql.clear();
        assertFalse(new HttpsCertificateTrustRepository(store).accept(ADDRESS, 8443, "stale-id", "synthetic-actor"));
        assertTrue(store.sql.stream().noneMatch(s -> s.startsWith("UPDATE")));
    }
}
