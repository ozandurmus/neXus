package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Query;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqBackupArtefactEntryRepository implements BackupArtefactEntryRepository {

    private static final int BATCH = 500;

    private final TransactionBoundary transactionBoundary;

    public JooqBackupArtefactEntryRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public void recordListed(String artefactId, List<Entry> entries) {
        transactionBoundary.inTransaction(dsl -> {
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
