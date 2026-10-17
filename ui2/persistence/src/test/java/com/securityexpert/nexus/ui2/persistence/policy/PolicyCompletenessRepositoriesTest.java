package com.securityexpert.nexus.ui2.persistence.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/** The SQL of the revision and change-evidence repositories: bound values only, idempotent writes, no raw text columns. */
class PolicyCompletenessRepositoriesTest {
    private final List<String> sql = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();
    private final DSLContext create = DSL.using(SQLDialect.POSTGRES);

    private JooqTransactionBoundary boundary(java.util.function.Function<String, MockResult[]> rows) {
        return new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
            return rows.apply(context.sql());
        }), SQLDialect.POSTGRES));
    }

    private MockResult[] none(String... columns) { return new MockResult[] { new MockResult(0, create.fetchFromStringData(columns)) }; }
    private MockResult[] one(String[] columns, String[] row) { return new MockResult[] { new MockResult(1, create.fetchFromStringData(columns, row)) }; }

    @Test void theLatestRowIsReadByContainerExcludingTheCurrentJob() {
        var repository = new PolicyRevisionRepository(boundary(q -> one(new String[] {"revision_no", "marker_kind", "marker"},
            new String[] {"3", "CP_PUBLISHED_SESSION", "{\"uid\":\"s1\"}"})));
        var latest = repository.latest("mds-1", "domain-1", "job-9").orElseThrow();
        assertEquals(3, latest.revisionNo());
        assertEquals("CP_PUBLISHED_SESSION", latest.markerKind());
        assertEquals("{\"uid\":\"s1\"}", latest.markerJson());
        assertTrue(sql.get(0).contains("job_id <> ?"));
        assertTrue(sql.get(0).contains("order by observed_at desc, revision_id desc limit 1"));
        assertEquals(List.of("mds-1", "domain-1", "job-9"), bindings);
    }

    @Test void noEarlierRowIsEmptyNotAGuess() {
        assertTrue(new PolicyRevisionRepository(boundary(q -> none("revision_no", "marker_kind", "marker"))).latest("a", "b", "c").isEmpty());
    }

    @Test void aRevisionIsWrittenOncePerJobAndContainerWithNullCountsKeptNull() {
        var repository = new PolicyRevisionRepository(boundary(q -> new MockResult[] { new MockResult(1) }));
        assertTrue(repository.record(new PolicyRevisionRepository.Revision("mds-1", "check_point", "domain", "domain-1", "job-1", 2,
            "UNKNOWN", "NO_PREVIOUS_MARKER", null, "{}", false, null, null, null, null, null, null, null, null)));
        assertTrue(sql.get(0).startsWith("insert into policy_revision"));
        assertTrue(sql.get(0).contains("on conflict on constraint policy_revision_job_container do nothing"));
        assertTrue(sql.get(0).contains("?::jsonb"));
        assertEquals(19, bindings.size());
        assertEquals("{}", bindings.get(9));
        assertNull(bindings.get(8));
        assertNull(bindings.get(11), "an unmeasured count is bound as NULL (UNKNOWN), never zero");
    }

    @Test void aDuplicateRevisionRowIsNotAnError() {
        assertFalse(new PolicyRevisionRepository(boundary(q -> new MockResult[] { new MockResult(0) })).record(
            new PolicyRevisionRepository.Revision("s", "palo_alto", "device_group", "g", "j", 1, "UNCHANGED", null, "PAN_CONTENT_VERSION",
                "{}", true, 1, null, 0, 0, 0, null, null, null)));
    }

    @Test void ruleChangesCountTheJobsOwnNonInitialHistoryRowsOfOneContainer() {
        var repository = new PolicyRevisionRepository(boundary(q -> new MockResult[] { new MockResult(2, create.fetchFromStringData(
            new String[] {"change_type", "n"}, new String[] {"added", "3"}, new String[] {"removed", "1"})) }));
        var changes = repository.ruleChanges("mds-1", "domain-1", Instant.parse("2026-10-18T03:00:00Z"));
        assertEquals(new PolicyRevisionRepository.RuleChanges(3, 0, 1), changes);
        assertTrue(sql.get(0).contains("not h.initial_capture"));
        assertTrue(sql.get(0).contains("s.metadata->>'containerId' = ?"));
        assertTrue(sql.get(0).contains("h.collected_at >= ?::timestamptz"));
        assertEquals(List.of("mds-1", "domain-1", "2026-10-18T03:00:00Z"), bindings);
    }

    @Test void evidenceCountsAreLinkedPerJobAndContainer() {
        new PolicyRevisionRepository(boundary(q -> new MockResult[] { new MockResult(2) })).refreshEvidenceCounts("job-1");
        assertTrue(sql.get(0).contains("e.job_id = r.job_id and e.container_ref = r.container_ref"));
        assertEquals(List.of("job-1"), bindings);
    }

    @Test void theCursorIsReadPerSourceAndParsedFromJson() {
        var repository = new PolicyChangeEvidenceRepository(boundary(q -> one(new String[] {"last_receive_time", "seqnos"},
            new String[] {"2026/10/18 06:00:00", "[\"5\",\"6\"]"})));
        var cursor = repository.cursor("manager-1").orElseThrow();
        assertEquals("2026/10/18 06:00:00", cursor.lastReceiveTime());
        assertEquals(List.of("5", "6"), cursor.boundarySeqnos());
        assertTrue(sql.get(0).contains("change_source = 'pan_config_log'"));
    }

    @Test void evidenceAndCursorAreWrittenTogetherWithDuplicatesDropped() {
        var repository = new PolicyChangeEvidenceRepository(boundary(q -> new MockResult[] { new MockResult(1) }));
        var row = new PolicyChangeEvidenceRepository.Evidence("palo_alto", "PAN_CONFIG_LOG", null, "10", "2026-10-18 06:00:01", "DEVICE_LOCAL",
            "masked:5", "edit", "Succeeded", null, 2, null, null, null, null, false);
        int stored = repository.save("manager-1", "job-1", List.of(row, row), Optional.of(
            new PolicyChangeEvidenceRepository.Cursor("2026/10/18 06:00:01", List.of("10"))));
        assertEquals(2, stored);
        assertTrue(sql.get(0).startsWith("insert into policy_change_evidence"));
        assertTrue(sql.get(0).endsWith("on conflict do nothing"));
        assertTrue(sql.get(2).startsWith("insert into policy_change_cursor"));
        assertTrue(sql.get(2).contains("on conflict (source_id, change_source) do update"));
        assertTrue(bindings.contains("[\"10\"]"));
        // Evidence carries identifiers, counts and classes: no free-text column is written.
        for (String column : new String[] {"log_text", "raw_line", "before_change", "after_change", "user_name", "object_name"})
            assertFalse(sql.get(0).contains(column), column);
    }

    @Test void withoutACursorOnlyEvidenceIsWritten() {
        var repository = new PolicyChangeEvidenceRepository(boundary(q -> new MockResult[] { new MockResult(0) }));
        assertEquals(0, repository.save("s", "j", List.of(), Optional.empty()));
        assertTrue(sql.isEmpty());
    }
}
