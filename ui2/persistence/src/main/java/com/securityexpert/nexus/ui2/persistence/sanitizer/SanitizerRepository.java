package com.securityexpert.nexus.ui2.persistence.sanitizer;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * V156: Data Sanitizer jobs, the mapping vault and its ledger (docs/design/DATA_SANITIZER_CONTRACT.md §2/§3.1). This
 * class only ever sees opaque ids, counts, HMAC lookup keys and sealed bytes -- never a token or an original in
 * clear; sealing and lookup-key derivation happen in the service. Every mutation runs in an audited transaction;
 * every sanitize, revert, download and prune lands one {@code sanitize_ledger} row in the transaction it records.
 */
public final class SanitizerRepository {

    public static final String ACTION_RUN = "data_sanitize_run";
    public static final String ACTION_REVERT = "data_sanitize_revert";
    public static final String ACTION_PRUNE = "data_sanitize_prune";
    public static final String PRUNER_ACTOR = "system:sanitizer-vault-pruner";

    public record JobRow(String jobId, String createdBy, Instant createdAt, String state, String fileType,
            Long inputBytes, Long outputBytes, String countsJson, String failureReason, Instant finishedAt,
            String outputState, long vaultEntries, Instant vaultExpiresAt, Instant vaultPrunedAt) {
    }

    public record RevertRow(String revertId, String createdBy, Instant createdAt, String scope, String sourceJobId,
            String state, String fileType, Long inputBytes, Long outputBytes, String countsJson, String failureReason,
            Instant finishedAt, String outputState) {
    }

    /** One vault row as written: never a token, never an original. */
    public record Entry(byte[] lookupKey, String kind, byte[] sealedOriginal) {
    }

    /** One vault row as found by a revert, with what is needed to unseal it. */
    public record Found(String jobId, byte[] lookupKey, byte[] sealedOriginal, byte[] wrappedDataKey, String keyId) {
    }

    private static final String JOB_COLUMNS = "job_id, created_by, created_at, state, file_type, input_bytes, output_bytes, "
            + "counts::text as counts, failure_reason, finished_at, output_state, vault_entries, vault_expires_at, vault_pruned_at";
    private static final String REVERT_COLUMNS = "revert_id, created_by, created_at, scope, source_job_id, state, file_type, "
            + "input_bytes, output_bytes, counts::text as counts, failure_reason, finished_at, output_state";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public SanitizerRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    // ---- sanitize jobs -------------------------------------------------------------------------------------

    public void createJob(String jobId, String actor, byte[] wrappedDataKey, String keyId, int retentionDays) {
        audited.inTransaction(actor, ACTION_RUN, db -> db.execute("insert into sanitize_job(job_id, created_by, state, "
                + "vault_expires_at, wrapped_data_key, key_id) values ({0}, {1}, 'RUNNING', now() + make_interval(days => {2}), "
                + "{3}, {4})", jobId, actor, retentionDays, wrappedDataKey, keyId));
    }

    /** Appends a batch of vault rows in one statement (one audit row); returns how many were new. */
    public int insertEntries(String jobId, String actor, List<Entry> entries) {
        if (entries.isEmpty()) return 0;
        return audited.inTransaction(actor, ACTION_RUN, db -> db.connectionResult(c -> {
            String[] kinds = new String[entries.size()];
            byte[][] keys = new byte[entries.size()][];
            byte[][] sealed = new byte[entries.size()][];
            for (int i = 0; i < entries.size(); i++) {
                kinds[i] = entries.get(i).kind();
                keys[i] = entries.get(i).lookupKey();
                sealed[i] = entries.get(i).sealedOriginal();
            }
            try (PreparedStatement ps = c.prepareStatement("insert into sanitize_vault_entry(job_id, lookup_key, entry_kind, "
                    + "sealed_original) select ?, k, t, s from unnest(?::bytea[], ?::text[], ?::bytea[]) as u(k, t, s) "
                    + "on conflict (job_id, lookup_key) do nothing")) {
                Array keyArray = c.createArrayOf("bytea", keys);
                Array kindArray = c.createArrayOf("text", kinds);
                Array sealedArray = c.createArrayOf("bytea", sealed);
                ps.setString(1, jobId);
                ps.setArray(2, keyArray);
                ps.setArray(3, kindArray);
                ps.setArray(4, sealedArray);
                return ps.executeUpdate();
            }
        }));
    }

    public void finishJob(String jobId, String actor, boolean succeeded, String fileType, long inputBytes,
            long outputBytes, String countsJson, String failureReason, long entries) {
        audited.inTransaction(actor, ACTION_RUN, db -> {
            if (succeeded) {
                db.execute("update sanitize_job set state = 'SUCCEEDED', file_type = {1}, input_bytes = {2}, output_bytes = {3}, "
                        + "counts = {4}::jsonb, failure_reason = null, finished_at = now(), vault_entries = {5}, "
                        + "output_state = 'READY' where job_id = {0}", jobId, fileType, inputBytes, outputBytes, countsJson, entries);
                ledger(db, jobId, null, "SANITIZED", actor, entries);
            } else {
                int deleted = db.execute("delete from sanitize_vault_entry where job_id = {0}", jobId);
                db.execute("update sanitize_job set state = 'FAILED', file_type = {1}, input_bytes = {2}, output_bytes = null, "
                        + "counts = {3}::jsonb, failure_reason = {4}, finished_at = now(), vault_entries = 0, "
                        + "output_state = 'NONE', wrapped_data_key = null, vault_pruned_at = now() where job_id = {0}",
                        jobId, fileType, inputBytes, countsJson, failureReason);
                ledger(db, jobId, null, "SANITIZE_FAILED", actor, 0L);
                ledger(db, jobId, null, "VAULT_PRUNED", actor, (long) deleted);
            }
            return null;
        });
    }

    public Optional<JobRow> findJob(String jobId) {
        return transactions.inTransaction(db -> db.fetch("select " + JOB_COLUMNS + " from sanitize_job where job_id = {0}",
                jobId).stream().findFirst().map(SanitizerRepository::toJob));
    }

    public List<JobRow> recentJobs(String createdByOrNull, int limit) {
        return transactions.inTransaction(db -> (createdByOrNull == null
                ? db.fetch("select " + JOB_COLUMNS + " from sanitize_job order by created_at desc, job_id limit {0}", limit)
                : db.fetch("select " + JOB_COLUMNS + " from sanitize_job where created_by = {0} order by created_at desc, "
                        + "job_id limit {1}", createdByOrNull, limit)).map(SanitizerRepository::toJob));
    }

    /** Marks the sanitized file downloaded (or expired) and ledgers it; false when it was not READY. */
    public boolean markOutput(String jobId, String actor, String outputState) {
        return audited.inTransaction(actor, ACTION_RUN, db -> {
            int n = db.execute("update sanitize_job set output_state = {1} where job_id = {0} and output_state = 'READY'",
                    jobId, outputState);
            if (n == 1) ledger(db, jobId, null, outputState.equals("DOWNLOADED") ? "OUTPUT_DOWNLOADED" : "OUTPUT_EXPIRED", actor, null);
            return n == 1;
        });
    }

    // ---- revert jobs ---------------------------------------------------------------------------------------

    public void createRevert(String revertId, String actor, String scope, String sourceJobId) {
        audited.inTransaction(actor, ACTION_REVERT, db -> db.execute("insert into sanitize_revert_job(revert_id, created_by, "
                + "scope, source_job_id, state) values ({0}, {1}, {2}, {3}, 'RUNNING')", revertId, actor, scope, sourceJobId));
    }

    public void finishRevert(String revertId, String actor, boolean succeeded, String fileType, long inputBytes,
            long outputBytes, String countsJson, String failureReason, long reverted) {
        audited.inTransaction(actor, ACTION_REVERT, db -> {
            db.execute("update sanitize_revert_job set state = {1}, file_type = {2}, input_bytes = {3}, output_bytes = {4}, "
                    + "counts = {5}::jsonb, failure_reason = {6}, finished_at = now(), output_state = {7} where revert_id = {0}",
                    revertId, succeeded ? "SUCCEEDED" : "FAILED", fileType, inputBytes, succeeded ? outputBytes : null,
                    countsJson, failureReason, succeeded ? "READY" : "NONE");
            ledger(db, null, revertId, succeeded ? "REVERTED" : "REVERT_FAILED", actor, reverted);
            return null;
        });
    }

    public Optional<RevertRow> findRevert(String revertId) {
        return transactions.inTransaction(db -> db.fetch("select " + REVERT_COLUMNS + " from sanitize_revert_job where "
                + "revert_id = {0}", revertId).stream().findFirst().map(SanitizerRepository::toRevert));
    }

    public boolean markRevertOutput(String revertId, String actor, String outputState) {
        return audited.inTransaction(actor, ACTION_REVERT, db -> {
            int n = db.execute("update sanitize_revert_job set output_state = {1} where revert_id = {0} and output_state = 'READY'",
                    revertId, outputState);
            if (n == 1) ledger(db, null, revertId, outputState.equals("DOWNLOADED") ? "REVERT_DOWNLOADED" : "OUTPUT_EXPIRED", actor, null);
            return n == 1;
        });
    }

    /**
     * The vault rows for a batch of lookup keys, newest job first, restricted to unexpired vaults of
     * {@code createdBy} (null: every creator) and of {@code jobId} (null: every job).
     */
    public List<Found> lookup(Collection<byte[]> lookupKeys, String createdBy, String jobId) {
        if (lookupKeys.isEmpty()) return List.of();
        return transactions.inTransaction(db -> db.connectionResult(c -> {
            try (PreparedStatement ps = c.prepareStatement("select e.job_id, e.lookup_key, e.sealed_original, j.wrapped_data_key, "
                    + "j.key_id from sanitize_vault_entry e join sanitize_job j on j.job_id = e.job_id "
                    + "where e.lookup_key = any(?::bytea[]) and j.state = 'SUCCEEDED' and j.vault_pruned_at is null and j.vault_expires_at > now() "
                    + "and (?::text is null or j.created_by = ?) and (?::text is null or j.job_id = ?) "
                    + "order by j.created_at desc, j.job_id")) {
                ps.setArray(1, c.createArrayOf("bytea", lookupKeys.toArray(new byte[0][])));
                ps.setString(2, createdBy);
                ps.setString(3, createdBy);
                ps.setString(4, jobId);
                ps.setString(5, jobId);
                List<Found> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Found(rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4), rs.getString(5)));
                    }
                }
                return out;
            }
        }));
    }

    // ---- retention -----------------------------------------------------------------------------------------

    /**
     * Prunes every expired vault: its entries are deleted, its wrapped data key cleared (so nothing left anywhere can
     * be unsealed), and one ledger row records the count. The job row stays, with counts only. Returns the number
     * of vaults pruned.
     */
    public int pruneExpiredVaults(int maxJobs) {
        List<String> expired = transactions.inTransaction(db -> db.fetch("select job_id from sanitize_job where "
                + "vault_pruned_at is null and vault_expires_at <= now() order by vault_expires_at limit {0}", maxJobs)
                .map(r -> r.get(0, String.class)));
        int pruned = 0;
        for (String jobId : expired) {
            pruned += audited.inTransaction(PRUNER_ACTOR, ACTION_PRUNE, db -> {
                int entries = db.execute("delete from sanitize_vault_entry where job_id = {0}", jobId);
                int n = db.execute("update sanitize_job set wrapped_data_key = null, vault_pruned_at = now() where job_id = {0} "
                        + "and vault_pruned_at is null", jobId);
                if (n == 1) ledger(db, jobId, null, "VAULT_PRUNED", PRUNER_ACTOR, (long) entries);
                return n;
            });
        }
        return pruned;
    }

    /**
     * After a service restart the work area is empty: running jobs failed and ready outputs are gone. Returns the
     * number of rows changed.
     */
    public int closeInterrupted() {
        return audited.inTransaction(SYSTEM_ACTOR, ACTION_RUN, db -> {
            List<String> runningJobs = db.fetch("select job_id from sanitize_job where state = 'RUNNING'")
                    .map(r -> r.get(0, String.class));
            int n = 0;
            for (String jobId : runningJobs) {
                int deleted = db.execute("delete from sanitize_vault_entry where job_id = {0}", jobId);
                n += db.execute("update sanitize_job set state = 'FAILED', failure_reason = 'SERVICE_RESTARTED', "
                        + "finished_at = now(), output_state = 'NONE', vault_entries = 0, wrapped_data_key = null, "
                        + "vault_pruned_at = now() where job_id = {0}", jobId);
                ledger(db, jobId, null, "SANITIZE_FAILED", SYSTEM_ACTOR, 0L);
                ledger(db, jobId, null, "VAULT_PRUNED", SYSTEM_ACTOR, (long) deleted);
            }
            n += db.execute("update sanitize_job set output_state = 'EXPIRED' where output_state = 'READY'");
            n += db.execute("update sanitize_revert_job set state = 'FAILED', failure_reason = 'SERVICE_RESTARTED', "
                    + "finished_at = now(), output_state = 'NONE' where state = 'RUNNING'");
            n += db.execute("update sanitize_revert_job set output_state = 'EXPIRED' where output_state = 'READY'");
            return n;
        });
    }

    public static final String SYSTEM_ACTOR = "system:sanitizer";

    // ---- helpers -------------------------------------------------------------------------------------------

    private static void ledger(DSLContext db, String jobId, String revertId, String event, String actor, Long entries) {
        db.execute("insert into sanitize_ledger(ledger_id, job_id, revert_id, event, actor_fingerprint, entries) "
                + "values ({0}, {1}, {2}, {3}, {4}, {5})", UUID.randomUUID().toString(), jobId, revertId, event, actor, entries);
    }

    private static Instant instant(Record r, String column) {
        OffsetDateTime t = r.get(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static JobRow toJob(Record r) {
        return new JobRow(r.get("job_id", String.class), r.get("created_by", String.class), instant(r, "created_at"),
                r.get("state", String.class), r.get("file_type", String.class), r.get("input_bytes", Long.class),
                r.get("output_bytes", Long.class), r.get("counts", String.class), r.get("failure_reason", String.class),
                instant(r, "finished_at"), r.get("output_state", String.class), r.get("vault_entries", Long.class),
                instant(r, "vault_expires_at"), instant(r, "vault_pruned_at"));
    }

    private static RevertRow toRevert(Record r) {
        return new RevertRow(r.get("revert_id", String.class), r.get("created_by", String.class), instant(r, "created_at"),
                r.get("scope", String.class), r.get("source_job_id", String.class), r.get("state", String.class),
                r.get("file_type", String.class), r.get("input_bytes", Long.class), r.get("output_bytes", Long.class),
                r.get("counts", String.class), r.get("failure_reason", String.class), instant(r, "finished_at"),
                r.get("output_state", String.class));
    }
}
