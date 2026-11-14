package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Plain-SQL implementation of {@link LifecycleVendorRepository} over the V219 tables. */
public final class JooqLifecycleVendorRepository implements LifecycleVendorRepository {

    static final String WRITE_ACTION = "lifecycle_catalog_write";
    private static final String ENTRY_COLUMNS = "entry_id, origin, source, upload_id, vendor, kind, product_key, product_label, "
            + "end_of_sale, end_of_engineering, end_of_support, end_of_security_support, end_of_extended_support, "
            + "month_only_fields, parse_state, parse_note, document_ref, source_url, content_sha256, confirmed_at";
    private static final String RUN_COLUMNS = "run_id, source, started_at, ended_at, outcome, documents_received, "
            + "documents_ignored, rejected, entries_stored, entries_needing_review, documents_fetched, documents_failed, reason";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqLifecycleVendorRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public List<VendorEntry> entries() {
        return transactions.inTransaction(dsl -> dsl.fetch("select " + ENTRY_COLUMNS
                + " from lifecycle_vendor_entry order by vendor, kind, product_key, entry_id").stream()
                .map(JooqLifecycleVendorRepository::entry).toList());
    }

    @Override
    public void replaceFeedDocument(String source, String documentRef, List<VendorEntry> entries) {
        transactions.inTransaction(dsl -> {
            dsl.execute("delete from lifecycle_vendor_entry where origin = 'FEED' and source = {0} and document_ref = {1}",
                    source, documentRef);
            insert(dsl, entries);
            return null;
        });
    }

    @Override
    public void storeUpload(Upload upload, String source, List<String> documentRefs, List<VendorEntry> entries, String actor) {
        audited.inTransaction(actor, WRITE_ACTION, dsl -> {
            dsl.execute("insert into lifecycle_catalog_upload(upload_id, page_form, vendor, content_sha256, entries_parsed, "
                    + "entries_needing_review, rows_skipped, uploaded_by, uploaded_at) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, now())",
                    upload.uploadId(), upload.pageForm(), upload.vendor(), upload.contentSha256(), upload.entriesParsed(),
                    upload.entriesNeedingReview(), upload.rowsSkipped(), actor);
            for (String ref : documentRefs) {
                dsl.execute("delete from lifecycle_vendor_entry where origin = 'OPERATOR_PAGE' and source = {0} and document_ref = {1}",
                        source, ref);
            }
            insert(dsl, entries);
            return null;
        });
    }

    @Override
    public List<Upload> latestUploads(int limit) {
        return transactions.inTransaction(dsl -> dsl.fetch("select upload_id, page_form, vendor, content_sha256, entries_parsed, "
                + "entries_needing_review, rows_skipped, uploaded_by, uploaded_at from lifecycle_catalog_upload "
                + "order by uploaded_at desc, upload_id desc limit {0}", Math.max(1, Math.min(limit, 200))).stream()
                .map(row -> new Upload(row.get("upload_id", String.class), row.get("page_form", String.class),
                        row.get("vendor", String.class), row.get("content_sha256", String.class), number(row.get("entries_parsed")),
                        number(row.get("entries_needing_review")), number(row.get("rows_skipped")),
                        row.get("uploaded_by", String.class), instant(row.get("uploaded_at", OffsetDateTime.class)))).toList());
    }

    @Override
    public void openRun(String runId, String source) {
        transactions.inTransaction(dsl -> dsl.execute("insert into lifecycle_feed_run(run_id, source, started_at) "
                + "values ({0}, {1}, now()) on conflict (run_id, source) do nothing", runId, source));
    }

    @Override
    public void addToRun(String runId, String source, int documentsReceived, int documentsIgnored, int rejected,
            int entriesStored, int entriesNeedingReview) {
        transactions.inTransaction(dsl -> dsl.execute("update lifecycle_feed_run set documents_received = documents_received + {2}, "
                + "documents_ignored = documents_ignored + {3}, rejected = rejected + {4}, entries_stored = entries_stored + {5}, "
                + "entries_needing_review = entries_needing_review + {6} where run_id = {0} and source = {1}",
                runId, source, documentsReceived, documentsIgnored, rejected, entriesStored, entriesNeedingReview));
    }

    @Override
    public void closeRun(String runId, String source, String outcome, Integer documentsFetched, Integer documentsFailed,
            String reason) {
        transactions.inTransaction(dsl -> {
            dsl.execute("insert into lifecycle_feed_run(run_id, source, started_at) values ({0}, {1}, now()) "
                    + "on conflict (run_id, source) do nothing", runId, source);
            return dsl.execute("update lifecycle_feed_run set ended_at = now(), outcome = {2}, documents_fetched = {3}, "
                    + "documents_failed = {4}, reason = {5} where run_id = {0} and source = {1}",
                    runId, source, outcome, documentsFetched, documentsFailed, reason);
        });
    }

    @Override
    public Optional<FeedRun> run(String runId, String source) {
        return transactions.inTransaction(dsl -> dsl.fetch("select " + RUN_COLUMNS
                + " from lifecycle_feed_run where run_id = {0} and source = {1}", runId, source).stream()
                .map(JooqLifecycleVendorRepository::run).findFirst());
    }

    @Override
    public List<FeedRun> latestRuns() {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct on (source) " + RUN_COLUMNS
                + " from lifecycle_feed_run order by source, started_at desc, run_id desc").stream()
                .map(JooqLifecycleVendorRepository::run).toList());
    }

    @Override
    public List<FeedRun> lastGoodRuns() {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct on (source) " + RUN_COLUMNS
                + " from lifecycle_feed_run where outcome in ('OK', 'PARTIAL') order by source, ended_at desc, run_id desc").stream()
                .map(JooqLifecycleVendorRepository::run).toList());
    }

    private static void insert(DSLContext dsl, List<VendorEntry> entries) {
        for (VendorEntry e : entries == null ? List.<VendorEntry>of() : entries) {
            dsl.execute("insert into lifecycle_vendor_entry(" + ENTRY_COLUMNS + ") values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, "
                    + "{8}, {9}, {10}, {11}, {12}, {13}::text[], {14}, {15}, {16}, {17}, {18}, now()) "
                    + "on conflict (entry_id) do update set origin = excluded.origin, source = excluded.source, "
                    + "upload_id = excluded.upload_id, vendor = excluded.vendor, kind = excluded.kind, "
                    + "product_key = excluded.product_key, product_label = excluded.product_label, "
                    + "end_of_sale = excluded.end_of_sale, end_of_engineering = excluded.end_of_engineering, "
                    + "end_of_support = excluded.end_of_support, end_of_security_support = excluded.end_of_security_support, "
                    + "end_of_extended_support = excluded.end_of_extended_support, month_only_fields = excluded.month_only_fields, "
                    + "parse_state = excluded.parse_state, parse_note = excluded.parse_note, document_ref = excluded.document_ref, "
                    + "source_url = excluded.source_url, content_sha256 = excluded.content_sha256, confirmed_at = now()",
                    e.entryId(), e.origin(), e.source(), e.uploadId(), e.vendor(), e.kind(), e.productKey(), e.productLabel(),
                    date(e.endOfSale()), date(e.endOfEngineering()), date(e.endOfSupport()), date(e.endOfSecuritySupport()),
                    date(e.endOfExtendedSupport()), e.monthOnlyFields().toArray(new String[0]), e.parseState(), e.parseNote(),
                    e.documentRef(), e.sourceUrl(), e.contentSha256());
        }
    }

    private static VendorEntry entry(Record r) {
        String[] month = r.get("month_only_fields", String[].class);
        return new VendorEntry(r.get("entry_id", String.class), r.get("origin", String.class), r.get("source", String.class),
                r.get("upload_id", String.class), r.get("vendor", String.class), r.get("kind", String.class),
                r.get("product_key", String.class), r.get("product_label", String.class), local(r, "end_of_sale"),
                local(r, "end_of_engineering"), local(r, "end_of_support"), local(r, "end_of_security_support"),
                local(r, "end_of_extended_support"), month == null ? List.of() : List.of(month), r.get("parse_state", String.class),
                r.get("parse_note", String.class), r.get("document_ref", String.class), r.get("source_url", String.class),
                r.get("content_sha256", String.class), instant(r.get("confirmed_at", OffsetDateTime.class)));
    }

    private static FeedRun run(Record row) {
        return new FeedRun(row.get("run_id", String.class), row.get("source", String.class),
                instant(row.get("started_at", OffsetDateTime.class)), instant(row.get("ended_at", OffsetDateTime.class)),
                row.get("outcome", String.class), number(row.get("documents_received")), number(row.get("documents_ignored")),
                number(row.get("rejected")), number(row.get("entries_stored")), number(row.get("entries_needing_review")),
                row.get("documents_fetched", Integer.class), row.get("documents_failed", Integer.class), row.get("reason", String.class));
    }

    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    private static Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }

    private static LocalDate local(Record r, String key) {
        Date value = r.get(key, Date.class);
        return value == null ? null : value.toLocalDate();
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }
}
