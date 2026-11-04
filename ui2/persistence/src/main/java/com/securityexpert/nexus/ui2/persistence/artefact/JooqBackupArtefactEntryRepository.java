package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Query;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqBackupArtefactEntryRepository implements BackupArtefactEntryRepository {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(JooqBackupArtefactEntryRepository.class.getName());
    private static final int BATCH = 500;

    /** The actor of the system-raised firewall-log alert row (the listing runs in the worker and on a manual re-list). */
    static final String SYSTEM_ACTOR = "system:backup-content-listing";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqBackupArtefactEntryRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    /**
     * Stores the listing and, in the same transaction, raises the once-per-artefact "firewall logs in backup" alert when
     * the listed files under the Check Point firewall log directory reach {@link FirewallLogsInBackupRule#MIN_BYTES}.
     * The alert row is audited (backup_deviation_record has a capture trigger), hence the audited transaction.
     */
    @Override
    public void recordListed(String artefactId, List<Entry> entries) {
        audited.inTransaction(SYSTEM_ACTOR, JooqBackupSizeAnomalyRepository.ACTION_FIREWALL_LOGS_DETECTED, dsl -> {
            dsl.execute("delete from backup_artefact_entry where artefact_id = {0}", artefactId);
            for (int from = 0; from < entries.size(); from += BATCH) {
                List<Query> queries = new ArrayList<>();
                for (Entry entry : entries.subList(from, Math.min(entries.size(), from + BATCH))) {
                    queries.add(dsl.query(
                            "insert into backup_artefact_entry(artefact_id, entry_path, entry_type, entry_bytes, entry_sha256) "
                                    + "values ({0}, {1}, {2}, {3}, {4}) on conflict (artefact_id, entry_path) do nothing",
                            artefactId, entry.path(), entry.type().wire(), entry.bytes(), entry.sha256().orElse(null)));
                }
                if (!queries.isEmpty()) {
                    dsl.batch(queries).execute();
                }
            }
            dsl.execute("insert into backup_artefact_content_listing(artefact_id, state, entry_count, reason, listed_at) "
                    + "values ({0}, 'LISTED', {1}, null, now()) on conflict (artefact_id) do update set state = 'LISTED', "
                    + "entry_count = excluded.entry_count, reason = null, listed_at = now()", artefactId, entries.size());
            // Best effort inside its own savepoint: the alert must never cost the listing it was derived from.
            try {
                dsl.transaction(nested -> JooqBackupSizeAnomalyRepository.recordFirewallLogs(nested.dsl(), artefactId));
            } catch (RuntimeException e) {
                LOG.warning("[BACKUP] firewall-log alert check failed: " + e.getClass().getSimpleName());
            }
            return null;
        });
    }

    @Override
    public void recordFailed(String artefactId, String reason) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into backup_artefact_content_listing(artefact_id, state, entry_count, reason, listed_at) "
                        + "values ({0}, 'FAILED', 0, {1}, now()) on conflict (artefact_id) do update set state = 'FAILED', "
                        + "entry_count = 0, reason = excluded.reason, listed_at = now()",
                artefactId, reason));
    }

    @Override
    public Optional<Listing> findListing(String artefactId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetchOptional(
                "select artefact_id, state, entry_count, reason, listed_at from backup_artefact_content_listing "
                        + "where artefact_id = {0}", artefactId)
                .map(row -> new Listing(row.get("artefact_id", String.class),
                        "LISTED".equals(row.get("state", String.class)), row.get("entry_count", Integer.class),
                        Optional.ofNullable(row.get("reason", String.class)),
                        row.get("listed_at", java.sql.Timestamp.class).toInstant())));
    }

    @Override
    public List<Entry> findEntries(String artefactId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select entry_path, entry_type, entry_bytes, entry_sha256 from backup_artefact_entry "
                        + "where artefact_id = {0} order by entry_path", artefactId)
                .stream().map(row -> new Entry(row.get("entry_path", String.class),
                        EntryType.fromWire(row.get("entry_type", String.class)), row.get("entry_bytes", Long.class),
                        Optional.ofNullable(row.get("entry_sha256", String.class))))
                .toList());
    }
}
