package com.securityexpert.nexus.ui2.persistence.policy;

import java.time.Duration;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Removes {@code cp_policy_json_chunk} generations that no manifest row names (PO approval 2026-11-23; V262).
 *
 * <p>A generation is written chunk by chunk before its manifest row is published (PolicyChunkStore), and a reader
 * streams a generation chunk by chunk outside the manifest transaction. So "no manifest names it now" is not enough
 * to delete it. Each run, in short bounded transactions:</p>
 * <ol>
 *   <li>clears the {@code unreferenced_since} stamp of any generation a manifest names again;</li>
 *   <li>stamps the lowest remaining chunk of every unstamped generation no manifest names;</li>
 *   <li>deletes, highest ordinal first, chunks of generations stamped longer ago than the safety age, in batches of
 *       at most {@code batchRows}; the same DELETE statement re-checks that no manifest row
 *       ({@code cp_policy_object_inventory}, {@code cp_policy_domain_run}, {@code policy_unit_checkpoint}) names the
 *       generation, so a referenced generation can never be deleted.</li>
 * </ol>
 * A run is skipped while a policy collection job is claimed or executing. It logs counts only.
 */
public final class PolicyChunkGarbageCollector {

    /** The minimum safety age; a collection run's deadline (UI2_POLICY_RUN_DEADLINE_SECONDS, 2 h) is far below it. */
    public static final Duration MIN_SAFETY_AGE = Duration.ofHours(24);

    public record Settings(Duration safetyAge, int batchRows, int maxBatches, Duration pause, Duration statementTimeout) {
        public Settings {
            Objects.requireNonNull(safetyAge, "safetyAge");
            Objects.requireNonNull(pause, "pause");
            Objects.requireNonNull(statementTimeout, "statementTimeout");
            if (safetyAge.compareTo(MIN_SAFETY_AGE) < 0) throw new IllegalArgumentException("POLICY_CHUNK_GC_SAFETY_AGE_BELOW_24H");
            if (batchRows < 1 || batchRows > 20_000) throw new IllegalArgumentException("POLICY_CHUNK_GC_BATCH_ROWS_OUT_OF_RANGE");
            if (maxBatches < 1 || maxBatches > 1_000) throw new IllegalArgumentException("POLICY_CHUNK_GC_MAX_BATCHES_OUT_OF_RANGE");
            if (pause.isNegative() || statementTimeout.isZero() || statementTimeout.isNegative())
                throw new IllegalArgumentException("POLICY_CHUNK_GC_TIMING_INVALID");
        }

        public static Settings defaults() {
            return new Settings(MIN_SAFETY_AGE, 5_000, 10, Duration.ofSeconds(2), Duration.ofSeconds(60));
        }
    }

    public record Result(boolean skipped, int cleared, int stamped, long deleted, int batches) {}

    private static final String UNREFERENCED = "not exists (select 1 from cp_policy_object_inventory i where i.chunk_generation = %1$s.generation) "
            + "and not exists (select 1 from cp_policy_domain_run r where r.chunk_generation = %1$s.generation) "
            + "and not exists (select 1 from policy_unit_checkpoint u where u.chunk_generation = %1$s.generation)";

    public static final String CLEAR = "update cp_policy_json_chunk c set unreferenced_since = null "
            + "where c.unreferenced_since is not null and not (" + UNREFERENCED.formatted("c") + ")";

    public static final String STAMP = "update cp_policy_json_chunk c set unreferenced_since = now() "
            + "where (c.generation, c.ordinal) in (select k.generation, k.ordinal from cp_policy_json_chunk k "
            + "where k.unreferenced_since is null "
            + "and not exists (select 1 from cp_policy_json_chunk p where p.generation = k.generation and p.ordinal < k.ordinal) "
            + "and " + UNREFERENCED.formatted("k") + " limit {0})";

    /** One statement: the outer NOT EXISTS against every manifest table is the guarantee, whatever the subquery chose. */
    public static final String DELETE = "delete from cp_policy_json_chunk c "
            + "where (c.generation, c.ordinal) in (select d.generation, d.ordinal from cp_policy_json_chunk d "
            + "where d.generation in (select m.generation from cp_policy_json_chunk m "
            + "where m.unreferenced_since < now() - ({0}::bigint * interval '1 second')) "
            + "order by d.generation desc, d.ordinal desc limit {1}) "
            + "and " + UNREFERENCED.formatted("c");

    private static final System.Logger LOG = System.getLogger(PolicyChunkGarbageCollector.class.getName());
    private static final String ACTOR = "system:worker";

    private final AuditedTransactionBoundary tx;
    private final Settings settings;

    public PolicyChunkGarbageCollector(TransactionBoundary boundary, Settings settings) {
        this.tx = new AuditedTransactionBoundary(Objects.requireNonNull(boundary, "boundary"));
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Result runOnce() {
        if (policyCollectionRunning()) {
            LOG.log(System.Logger.Level.INFO, "[POLICY_CHUNK_GC] skipped: a policy collection is running");
            return new Result(true, 0, 0, 0, 0);
        }
        int cleared = bounded("policy_chunk_gc_clear", CLEAR);
        int stamped = bounded("policy_chunk_gc_stamp", STAMP, settings.batchRows() * 4);
        long deleted = 0;
        int batches = 0;
        while (batches < settings.maxBatches()) {
            int rows = bounded("policy_chunk_gc_delete", DELETE, settings.safetyAge().toSeconds(), settings.batchRows());
            batches++;
            deleted += rows;
            if (rows < settings.batchRows()) break;
            if (!pause()) break;
        }
        if (cleared > 0 || stamped > 0 || deleted > 0)
            LOG.log(System.Logger.Level.INFO, "[POLICY_CHUNK_GC] cleared={0} stamped={1} deleted={2} batches={3}",
                    cleared, stamped, deleted, batches);
        return new Result(false, cleared, stamped, deleted, batches);
    }

    private boolean policyCollectionRunning() {
        return tx.inTransaction(ACTOR, "policy_chunk_gc_check", db -> db.fetchOne(
                "select exists(select 1 from jobs where capability_id in ('cp_policy_collect','pan_policy_collect') "
                        + "and state in ('CLAIMED','EXECUTING'))").get(0, Boolean.class));
    }

    private int bounded(String action, String sql, Object... bindings) {
        long timeoutMillis = settings.statementTimeout().toMillis();
        return tx.inTransaction(ACTOR, action, db -> {
            db.fetch("select set_config('statement_timeout', {0}, true), set_config('lock_timeout', '5s', true)",
                    String.valueOf(timeoutMillis));
            return db.execute(sql, bindings);
        });
    }

    private boolean pause() {
        if (settings.pause().isZero()) return true;
        try {
            Thread.sleep(settings.pause().toMillis());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
