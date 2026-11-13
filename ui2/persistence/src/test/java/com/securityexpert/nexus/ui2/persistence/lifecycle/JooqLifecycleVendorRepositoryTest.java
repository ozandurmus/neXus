package com.securityexpert.nexus.ui2.persistence.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class JooqLifecycleVendorRepositoryTest {

    private static LifecycleVendorRepository.VendorEntry entry(String origin, String uploadId) {
        return new LifecycleVendorRepository.VendorEntry(origin + ":cisco_eol:EOL00042:HARDWARE:FPR4115", origin, "cisco_eol", uploadId,
                "CISCO_ASA", "HARDWARE", "FPR4115", "FPR4115 (EOL00042)", LocalDate.of(2026, 1, 6), null, LocalDate.of(2031, 1, 31), null,
                null, List.of(), "PARSED", null, "EOL00042", "https://www.cisco.com/c/en/us/products/collateral/security/x/y.html",
                "0".repeat(64), null);
    }

    private static JooqLifecycleVendorRepository repository(List<String> queries) {
        return new JooqLifecycleVendorRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(ctx -> {
            queries.add(ctx.sql());
            return ctx.sql().startsWith("select")
                    ? new MockResult[]{new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())}
                    : new MockResult[]{new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));
    }

    @Test
    void aFeedDocumentReplacesOnlyItsOwnPublicationWithoutAnAuditContext() {
        var queries = new ArrayList<String>();
        repository(queries).replaceFeedDocument("cisco_eol", "EOL00042", List.of(entry("FEED", null)));
        assertEquals(2, queries.size());
        assertTrue(queries.getFirst().contains("delete from lifecycle_vendor_entry where origin = 'FEED' and source = ? and document_ref = ?"));
        assertTrue(queries.get(1).startsWith("insert into lifecycle_vendor_entry("));
        assertTrue(queries.get(1).contains("confirmed_at = now()"), "a re-read confirms the entry again");
        assertFalse(String.join(" ", queries).contains("SET LOCAL"), "machine-written rows, no audit trigger (V205 rule)");
    }

    @Test
    void anUploadIsOneAuditedTransactionWithItsHistoryRow() {
        var queries = new ArrayList<String>();
        repository(queries).storeUpload(new LifecycleVendorRepository.Upload("up-0123456789ab", "cisco_eol_bulletin", "CISCO_ASA",
                "0".repeat(64), 1, 0, 0, "synthetic-actor", Instant.EPOCH), "cisco_eol", List.of("EOL00042"),
                List.of(entry("OPERATOR_PAGE", "up-0123456789ab")), "synthetic-actor");
        assertTrue(queries.get(0).contains("SET LOCAL app.actor_fingerprint"));
        assertTrue(queries.get(1).contains("SET LOCAL app.action_id = 'lifecycle_catalog_write'"));
        assertTrue(queries.get(2).startsWith("insert into lifecycle_catalog_upload("));
        assertTrue(queries.get(3).contains("origin = 'OPERATOR_PAGE' and source = ? and document_ref = ?"));
        assertTrue(queries.get(4).startsWith("insert into lifecycle_vendor_entry("));
        assertEquals(5, queries.size());
    }

    @Test
    void runsAreReadPerSourceAndOnlyGoodOnesSetTheAge() {
        var queries = new ArrayList<String>();
        var repository = repository(queries);
        repository.latestRuns();
        repository.lastGoodRuns();
        assertTrue(queries.get(0).contains("from lifecycle_feed_run order by source, started_at desc"));
        assertTrue(queries.get(1).contains("where outcome in ('OK', 'PARTIAL')"));
    }
}
