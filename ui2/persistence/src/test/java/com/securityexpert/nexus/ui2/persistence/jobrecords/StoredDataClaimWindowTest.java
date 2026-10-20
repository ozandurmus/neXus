package com.securityexpert.nexus.ui2.persistence.jobrecords;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisJob;

/** The windows govern device polling (PO amendment 2026-10-10); packet analysis touches no device. */
class StoredDataClaimWindowTest {
    private static final Instant OUTSIDE = Instant.parse("2026-10-08T10:00:00Z"); // 13:00 Istanbul
    private final JobWindowPolicy outside = new JobWindowPolicy(Clock.fixed(OUTSIDE, ZoneOffset.UTC), 60);

    @Test void onlyAWhollyStoredDataClaimSetIsExempt() {
        assertTrue(JobWindowPolicy.storedDataOnly(List.of(PacketAnalysisJob.CAPABILITY)));
        assertFalse(JobWindowPolicy.storedDataOnly(List.of(PacketAnalysisJob.CAPABILITY, "cp_inventory_collect")));
        assertFalse(JobWindowPolicy.storedDataOnly(List.of()));
        assertEquals(Set.of("packet_analysis_run"), JobWindowPolicy.STORED_DATA_JOB_TYPES);
    }

    /** PO 2026-10-20: outside the window only the read "is an administrator's bypass job queued" runs; with none, no claim. */
    @Test void deviceOrMixedClaimSetsOutsideTheWindowNeverIssueTheClaimWithoutAQueuedBypassJob() {
        List<String> queries = new ArrayList<>();
        var sql = DSL.using(new MockConnection(query -> {
            queries.add(query.sql());
            return new MockResult[] { new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("queued", Integer.class))) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary database = new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(sql); }
        };
        var dao = new JooqJobLeaseDao(database, outside);
        assertTrue(dao.claimNext("general-synthetic", List.of("cp_inventory_collect"), Duration.ofMinutes(10)).isEmpty());
        assertTrue(dao.claimNext("packet_analysis-synthetic", List.of(PacketAnalysisJob.CAPABILITY, "cp_inventory_collect"),
                Duration.ofMinutes(10)).isEmpty());
        assertEquals(2, queries.size());
        assertTrue(queries.stream().allMatch(q -> q.startsWith("select 1 as queued from jobs") && q.contains("window_bypass_role is not null")));
    }

    @Test void packetAnalysisClaimsOutsideTheWindowWithTheUnchangedStatement() {
        List<String> queries = new ArrayList<>();
        List<Object[]> bindings = new ArrayList<>();
        var sql = DSL.using(new MockConnection(query -> {
            queries.add(query.sql());
            bindings.add(query.bindings());
            return new MockResult[] { new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult(
                    DSL.field("job_id", String.class), DSL.field("lease_epoch", Long.class))) };
        }), SQLDialect.POSTGRES);
        TransactionBoundary database = new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { return work.apply(sql); }
        };
        assertTrue(new JooqJobLeaseDao(database, outside)
                .claimNext("packet_analysis-synthetic", List.of(PacketAnalysisJob.CAPABILITY), Duration.ofMinutes(10)).isEmpty());
        int claim = -1;
        for (int i = 0; i < queries.size(); i++) if (queries.get(i).contains("SET state = 'CLAIMED'")) claim = i;
        assertTrue(claim >= 0, "the claim statement ran outside the polling window");
        boolean futureWindowEnd = Arrays.stream(bindings.get(claim)).map(StoredDataClaimWindowTest::instant)
                .anyMatch(end -> end != null && end.isAfter(OUTSIDE.plus(Duration.ofHours(23))));
        assertTrue(futureWindowEnd, "the window end bound is beyond the claim for stored-data work");
        assertTrue(Arrays.asList(bindings.get(claim)).contains(PacketAnalysisJob.CAPABILITY));
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof java.sql.Timestamp time) return time.toInstant();
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        try {
            return value == null ? null : OffsetDateTime.parse(String.valueOf(value).replace(' ', 'T')).toInstant();
        } catch (java.time.format.DateTimeParseException notATime) {
            return null;
        }
    }
}
