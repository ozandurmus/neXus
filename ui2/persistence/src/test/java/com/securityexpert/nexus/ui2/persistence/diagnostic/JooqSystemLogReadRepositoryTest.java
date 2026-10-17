package com.securityexpert.nexus.ui2.persistence.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The statements of the system-log read side, without a database: every one is a read (select or with ... select), binds
 * its device and filters as parameters, and selects none of the columns that hold a name digest, inode, anchor, key or
 * artefact content identity. (The statements run against PostgreSQL in the integration module.)
 */
class JooqSystemLogReadRepositoryTest {
    private static final List<String> NEVER_SELECTED = List.of("file_name_hmac", "remote_name_hmac", "name_hmac", "inode",
            "anchor_hmac", "family_snapshot", "plaintext_hmac", "content_hmac", "detail", "wrapped_data_key");

    private static final class Capture implements TransactionBoundary {
        final List<String> statements = new ArrayList<>();
        final List<Object[]> bindings = new ArrayList<>();
        final DSLContext dsl;

        Capture(Function<org.jooq.tools.jdbc.MockExecuteContext, MockResult[]> answer) {
            dsl = DSL.using(new MockConnection(context -> {
                statements.add(context.sql());
                bindings.add(context.bindings());
                return answer.apply(context);
            }), SQLDialect.POSTGRES);
        }

        @Override
        public <T> T inTransaction(Function<DSLContext, T> work) {
            return work.apply(dsl);
        }
    }

    private static Capture empty() {
        return new Capture(context -> new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())});
    }

    @Test
    void everyStatementIsAReadThatBindsItsParametersAndSelectsNoNameKeyOrDigestColumn() {
        Capture db = empty();
        var repository = new JooqSystemLogReadRepository(db);

        repository.sources("dev-1");
        repository.lastJob("dev-1");
        repository.chunks("dev-1", Optional.of("cp_messages"), Optional.of("2026-10-17"), 5000);
        repository.gaps(Optional.of("dev-1"), 0);
        repository.crashEntries(Optional.empty(), 99999);
        repository.crashAlerts(Optional.of("dev-1"), 99999);
        repository.hcpFiles("dev-1", 99999);

        assertEquals(7, db.statements.size());
        for (int i = 0; i < db.statements.size(); i++) {
            String sql = db.statements.get(i).toLowerCase();
            assertTrue(sql.startsWith("select ") || sql.startsWith("with "), sql);
            for (String verb : List.of("insert ", "update ", "delete ", "drop ", "alter ", "truncate ")) {
                assertFalse(sql.contains(verb), verb + " in " + sql);
            }
            // device ids, source keys and days are parameters, never concatenated into the statement
            assertFalse(sql.contains("dev-1") || sql.contains("cp_messages") || sql.contains("2026-10-17"), sql);
            String selected = sql.substring(0, sql.indexOf(" from "));
            if (sql.startsWith("with ")) selected = sql.substring(sql.lastIndexOf("select s.source_key"), sql.lastIndexOf(" from src"));
            for (String column : NEVER_SELECTED) {
                assertFalse(selected.contains(column), column + " selected by: " + sql);
            }
        }
        assertEquals("dev-1", db.bindings.get(0)[0]);
    }

    @Test
    void theLimitIsBoundedByTheRepositoryWhateverTheCallerAsks() {
        Capture db = empty();
        var repository = new JooqSystemLogReadRepository(db);
        repository.chunks("dev-1", Optional.empty(), Optional.empty(), 5000);
        repository.chunks("dev-1", Optional.empty(), Optional.empty(), -3);
        repository.hcpFiles("dev-1", 5000);
        repository.crashAlerts(Optional.empty(), 5000);
        assertEquals(1000, last(db.bindings.get(0)));
        assertEquals(1, last(db.bindings.get(1)));
        assertEquals(200, last(db.bindings.get(2)));
        assertEquals(200, last(db.bindings.get(3)));
    }

    private static Object last(Object[] bindings) {
        return bindings[bindings.length - 1];
    }

    @Test
    void aChunkRowIsMappedWithItsFlagsTimesAndOutcome() {
        Field<String> chunkId = DSL.field(DSL.name("chunk_id"), String.class);
        Field<String> sourceKey = DSL.field(DSL.name("source_key"), String.class);
        Field<String> jobId = DSL.field(DSL.name("job_id"), String.class);
        Field<OffsetDateTime> collectedAt = DSL.field(DSL.name("collected_at"), OffsetDateTime.class);
        Field<String> logDay = DSL.field(DSL.name("log_day"), String.class);
        Field<Long> startOffset = DSL.field(DSL.name("start_offset"), Long.class);
        Field<Long> endOffset = DSL.field(DSL.name("end_offset"), Long.class);
        Field<Long> bytes = DSL.field(DSL.name("bytes"), Long.class);
        Field<Long> lines = DSL.field(DSL.name("lines"), Long.class);
        Field<String> first = DSL.field(DSL.name("first_line_time"), String.class);
        Field<String> lastLine = DSL.field(DSL.name("last_line_time"), String.class);
        Field<Long> redactions = DSL.field(DSL.name("redaction_count"), Long.class);
        Field<String> ruleSet = DSL.field(DSL.name("redaction_rule_set"), String.class);
        Field<String> flags = DSL.field(DSL.name("flags"), String.class);
        Field<Long> cipher = DSL.field(DSL.name("ciphertext_bytes"), Long.class);
        Field<OffsetDateTime> pruned = DSL.field(DSL.name("pruned_at"), OffsetDateTime.class);
        Field<String> reason = DSL.field(DSL.name("prune_reason"), String.class);
        Field<String> outcome = DSL.field(DSL.name("outcome"), String.class);
        var create = DSL.using(SQLDialect.POSTGRES);
        var result = create.newResult(chunkId, sourceKey, jobId, collectedAt, logDay, startOffset, endOffset, bytes, lines,
                first, lastLine, redactions, ruleSet, flags, cipher, pruned, reason, outcome);
        var row = create.newRecord(chunkId, sourceKey, jobId, collectedAt, logDay, startOffset, endOffset, bytes, lines,
                first, lastLine, redactions, ruleSet, flags, cipher, pruned, reason, outcome);
        row.set(chunkId, "c-1");
        row.set(sourceKey, "cp_messages");
        row.set(jobId, "job-1");
        row.set(collectedAt, OffsetDateTime.parse("2026-10-17T09:00:00Z"));
        row.set(logDay, "2026-10-17");
        row.set(startOffset, 0L);
        row.set(endOffset, 100L);
        row.set(bytes, 100L);
        row.set(lines, 4L);
        row.set(first, "2026-10-17 09:00:00");
        row.set(lastLine, "2026-10-17 09:00:03");
        row.set(redactions, 2L);
        row.set(ruleSet, "cp-text-v1");
        row.set(flags, "LINE_SPLIT,ROTATION_FOLLOWED");
        row.set(cipher, 60L);
        row.set(outcome, "GAP");
        result.add(row);
        Capture db = new Capture(context -> new MockResult[] {new MockResult(1, result)});

        var chunks = new JooqSystemLogReadRepository(db).chunks("dev-1", Optional.empty(), Optional.empty(), 10);

        assertEquals(1, chunks.size());
        var chunk = chunks.get(0);
        assertEquals("c-1", chunk.chunkId());
        assertEquals(List.of("LINE_SPLIT", "ROTATION_FOLLOWED"), chunk.flags());
        assertEquals("2026-10-17 09:00:03", chunk.lastLineTime());
        assertEquals("GAP", chunk.outcome());
        assertEquals(java.time.Instant.parse("2026-10-17T09:00:00Z"), chunk.collectedAt());
        assertEquals(null, chunk.prunedAt());
        assertEquals(2, chunk.redactionCount());
    }

}
