package com.securityexpert.nexus.ui2.persistence.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class PolicyCollectionRepositoryTest {
    @Test void automaticAdmissionUsesDurableSixHourBoundaryAndManualStillRecordsAttempt() {
        List<String> sql = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(calls.getAndIncrement() == 1 ? 0 : 1, null) };
        }), SQLDialect.POSTGRES)));
        assertTrue(repository.beginDomain("mds-1", "domain-01", true));
        assertFalse(repository.beginDomain("mds-1", "domain-01", true));
        assertTrue(repository.beginDomain("mds-1", "domain-01", false));
        assertTrue(sql.get(0).contains("attempted_at <= now() - interval '6 hours'"));
        assertFalse(sql.get(2).contains("where policy_collection_domain"));
        assertTrue(sql.stream().allMatch(s -> s.contains("on conflict (source_id, domain_ref) do update")));
    }
    @Test void expiredLeaseCannotPublishEvenAnOtherwiseCompleteSnapshot() {
        List<String> sql = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(0, context.sql().startsWith("select job_id")
                ? create.fetchFromStringData(new String[] { "job_id" }) : null) };
        }), SQLDialect.POSTGRES)));
        var snapshot = new PolicySnapshotRepository.Stored("policy-1", "2026-10-01T12:00:00Z", "{}", "{}");
        assertFalse(repository.publish("job-1", 7, List.of(snapshot), "synthetic-actor"));
        assertTrue(sql.stream().anyMatch(s -> s.contains("lease_epoch = ?") && s.contains("lease_expires_at > now() for update")));
        assertFalse(sql.stream().anyMatch(s -> s.startsWith("insert into policy_snapshot")));
    }
}
