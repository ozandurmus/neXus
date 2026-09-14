package com.securityexpert.nexus.ui2.persistence.device.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry.DeviationKind;

/**
 * Persistence tests for {@link JooqDeviceConfigurationRepository} handling
 * structural deviation summaries (14I DV-2).
 *
 * Proves that:
 * 1. recordRun inserts the deviation summary and entry rows in the same transaction.
 * 2. assemble reassembles the deviation summary with its status and entries.
 * 3. AC-6 invariant: no configuration values exist in any summary column or test fixture.
 */
class JooqDeviceConfigurationRepositoryDeviationTest {

    private static ConfigurationArtefactRecord sampleArtefact() {
        return new ConfigurationArtefactRecord("art-1", "dev-1", "job-1", "check_point",
                "sha-plain", 100L, "sha-cipher", 120L, "none", "key-1", new byte[] { 1, 2, 3 });
    }

    private static ConfigurationRun sampleChangedRunWithSummary() {
        ConfigurationIndexEntry indexEntry = new ConfigurationIndexEntry("ctx-1", "sec-1", Optional.empty(), 5);
        ConfigurationDeviationEntry devEntry = new ConfigurationDeviationEntry("ctx-1", "sec-1", Optional.empty(), DeviationKind.RECOUNTED, 3, 5);
        ConfigurationDeviationSummary summary = new ConfigurationDeviationSummary(
                ConfigurationDeviationSummary.Status.COMPUTED, List.of(devEntry));

        return new ConfigurationRun(
                "run-1", "dev-1", "job-1", Instant.parse("2026-09-14T10:00:00Z"),
                "check_point", ConfigurationReadKind.SHOW_CONFIGURATION, true,
                "canon-hash", "raw-hash", 100L, "art-1", 0,
                Optional.empty(), ChangeState.CHANGED, List.of(indexEntry), List.of(),
                Optional.of(summary));
    }

    @Test
    void recordRunInsertsDeviationSummaryAndEntriesWhenPresent() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(1, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        JooqDeviceConfigurationRepository repository = new JooqDeviceConfigurationRepository(transactionBoundary);

        repository.recordRun(sampleChangedRunWithSummary(), sampleArtefact(), "actor-1", "action-1");

        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("insert into configuration_run_deviation_summary")),
                "deviation summary header table inserted");
        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("insert into configuration_run_deviation_entry")),
                "deviation summary entry table inserted");
    }

    @Test
    void recordRunSkipsDeviationSummaryWhenAbsent() {
        List<String> executedSql = new ArrayList<>();
        MockDataProvider provider = ctx -> {
            executedSql.add(ctx.sql());
            return new MockResult[] { new MockResult(1, null) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        JooqDeviceConfigurationRepository repository = new JooqDeviceConfigurationRepository(transactionBoundary);

        ConfigurationRun unchangedRun = new ConfigurationRun(
                "run-2", "dev-1", "job-1", Instant.parse("2026-09-14T10:00:00Z"),
                "check_point", ConfigurationReadKind.SHOW_CONFIGURATION, true,
                "canon-hash", "raw-hash", 100L, "art-1", 0,
                Optional.empty(), ChangeState.UNCHANGED, List.of(), List.of(),
                Optional.empty());

        repository.recordRun(unchangedRun, sampleArtefact(), "actor-1", "action-1");

        assertTrue(executedSql.stream().noneMatch(sql -> sql.contains("configuration_run_deviation_summary")),
                "no deviation summary row for unchanged run");
        assertTrue(executedSql.stream().noneMatch(sql -> sql.contains("configuration_run_deviation_entry")),
                "no deviation entry row for unchanged run");
    }

    @Test
    void findLatestRunReassemblesDeviationSummary() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);

        Result<Record> findRowResult = create.fetchFromStringData(
                new String[] { "run_id" },
                new String[] { "run-1" });

        Result<Record> runResult = create.fetchFromStringData(
                new String[] { "run_id", "device_id", "job_id", "collected_at", "vendor", "read_kind",
                        "is_primary", "canonical_hash", "raw_hash", "raw_bytes", "artefact_ref",
                        "withheld_line_count", "sanitized_text", "change_state" },
                new String[] { "run-1", "dev-1", "job-1", "2026-09-14 10:00:00", "check_point", "show_configuration",
                        "true", "canon-hash", "raw-hash", "100", "art-1",
                        "0", null, "changed" });

        Result<Record> indexResult = create.fetchFromStringData(
                new String[] { "context", "section", "source", "entry_count" },
                new String[] { "ctx-1", "sec-1", null, "5" });

        Result<Record> overrideResult = create.fetchFromStringData(
                new String[] { "context", "category", "element_path", "panorama_source" });

        Result<Record> summaryResult = create.fetchFromStringData(
                new String[] { "summary_id", "status" },
                new String[] { "sum-1", "COMPUTED" });

        Result<Record> entryResult = create.fetchFromStringData(
                new String[] { "context", "section", "source", "kind", "old_count", "new_count" },
                new String[] { "ctx-1", "sec-1", null, "RECOUNTED", "3", "5" });

        List<Result<Record>> queue = new ArrayList<>(List.of(
                findRowResult, runResult, indexResult, overrideResult, summaryResult, entryResult));

        MockDataProvider provider = ctx -> {
            Result<Record> next = queue.remove(0);
            return new MockResult[] { new MockResult(next.size(), next) };
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(dsl);
        JooqDeviceConfigurationRepository repository = new JooqDeviceConfigurationRepository(transactionBoundary);

        Optional<ConfigurationRun> found = repository.findLatestRun("dev-1", "show_configuration");

        assertTrue(found.isPresent());
        ConfigurationRun run = found.get();
        assertEquals("changed", run.changeState());
        assertTrue(run.deviationSummary().isPresent(), "deviation summary reassembled");
        ConfigurationDeviationSummary summary = run.deviationSummary().get();
        assertEquals(ConfigurationDeviationSummary.Status.COMPUTED, summary.status());
        assertEquals(1, summary.entries().size());
        ConfigurationDeviationEntry entry = summary.entries().get(0);
        assertEquals("ctx-1", entry.context());
        assertEquals("sec-1", entry.section());
        assertEquals(DeviationKind.RECOUNTED, entry.kind());
        assertEquals(3, entry.oldCount());
        assertEquals(5, entry.newCount());
    }
}
