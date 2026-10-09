package com.securityexpert.nexus.ui2.persistence.lifecycle;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.Record;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqLifecycleCatalogRepository implements LifecycleCatalogRepository {
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqLifecycleCatalogRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override public List<LifecycleCatalogEntry> list() {
        return transactions.inTransaction(dsl -> dsl.fetch("select * from lifecycle_catalog order by vendor, kind, product")
                .stream().map(JooqLifecycleCatalogRepository::entry).toList());
    }

    @Override public java.util.Map<String, StoredInventory> storedInventory() {
        return transactions.inTransaction(dsl -> {
            var result = new java.util.LinkedHashMap<String, StoredInventory>();
            for (var row : dsl.fetch("select d.device_id, d.observed_model, d.observed_software_version, s.licenses::text as licenses "
                    + "from devices d left join (select distinct on (device_id) device_id, run_id from device_inventory_run "
                    + "order by device_id, collected_at desc, run_id desc) r on r.device_id=d.device_id "
                    + "left join infoblox_grid_summary s on s.run_id=r.run_id")) {
                result.put(row.get("device_id", String.class), new StoredInventory(row.get("observed_model", String.class),
                        row.get("observed_software_version", String.class), row.get("licenses", String.class)));
            }
            return result;
        });
    }

    @Override public void save(List<LifecycleCatalogEntry> entries, String editedId, String actor) {
        audited.inTransaction(actor, "lifecycle_catalog_write", dsl -> {
            for (var e : entries) {
                if (editedId != null) {
                    int changed = dsl.execute("update lifecycle_catalog set vendor={0}, kind={1}, product={2}, "
                            + "end_of_sale={3}, end_of_support={4}, end_of_engineering={5}, source={6}, note={7}, "
                            + "imported_by={8}, imported_at={9} where catalog_id={10}", e.vendor(), e.kind(), e.product(),
                            e.endOfSale(), e.endOfSupport(), e.endOfEngineering(), e.source(), e.note(), actor,
                            OffsetDateTime.ofInstant(e.importedAt(), java.time.ZoneOffset.UTC), editedId);
                    if (changed != 1) throw new IllegalArgumentException("Catalog row no longer exists");
                } else {
                    dsl.execute("insert into lifecycle_catalog (catalog_id, vendor, kind, product, end_of_sale, "
                            + "end_of_support, end_of_engineering, source, note, imported_by, imported_at) "
                            + "values ({0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10}) "
                            + "on conflict (vendor,kind,product) do update set end_of_sale=excluded.end_of_sale, "
                            + "end_of_support=excluded.end_of_support, end_of_engineering=excluded.end_of_engineering, "
                            + "source=excluded.source, note=excluded.note, imported_by=excluded.imported_by, imported_at=excluded.imported_at",
                            e.catalogId(), e.vendor(), e.kind(), e.product(), e.endOfSale(), e.endOfSupport(),
                            e.endOfEngineering(), e.source(), e.note(), actor,
                            OffsetDateTime.ofInstant(e.importedAt(), java.time.ZoneOffset.UTC));
                }
            }
            return null;
        });
    }

    @Override public boolean delete(String id, String actor) {
        return audited.inTransaction(actor, "lifecycle_catalog_write",
                dsl -> dsl.execute("delete from lifecycle_catalog where catalog_id={0}", id) == 1);
    }

    private static LocalDate date(Record r, String key) {
        Date value = r.get(key, Date.class);
        return value == null ? null : value.toLocalDate();
    }

    private static LifecycleCatalogEntry entry(Record r) {
        return new LifecycleCatalogEntry(r.get("catalog_id", String.class), r.get("vendor", String.class),
                r.get("kind", String.class), r.get("product", String.class), date(r, "end_of_sale"),
                date(r, "end_of_support"), date(r, "end_of_engineering"), r.get("source", String.class),
                r.get("note", String.class), r.get("imported_by", String.class),
                r.get("imported_at", OffsetDateTime.class).toInstant());
    }
}
