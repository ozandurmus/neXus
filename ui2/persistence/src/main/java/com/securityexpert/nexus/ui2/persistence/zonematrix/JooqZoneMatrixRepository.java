package com.securityexpert.nexus.ui2.persistence.zonematrix;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** jOOQ implementation of {@link ZoneMatrixRepository} over the V215 tables. */
public final class JooqZoneMatrixRepository implements ZoneMatrixRepository {
    /** Transaction-scoped advisory lock key serializing every zone-matrix write. */
    static final long LOCK_KEY = 0x7a6d_7631L;
    private static final String SUMMARY = "version_id, number, state, revision, content_sha256, based_on_version_id, "
            + "created_by, created_at, updated_by, updated_at, published_by, published_at";
    private final TransactionBoundary transactions;

    public JooqZoneMatrixRepository(TransactionBoundary transactions) { this.transactions = transactions; }

    @Override public List<Version> list() {
        return transactions.inTransaction(dsl -> dsl.fetch("select " + SUMMARY + ", null as content from zone_matrix_version "
                + "order by number desc nulls first, created_at desc").stream().map(JooqZoneMatrixRepository::version).toList());
    }

    @Override public Optional<Version> find(String versionId) {
        return transactions.inTransaction(dsl -> one(dsl, "select * from zone_matrix_version where version_id={0}", versionId));
    }

    @Override public Optional<Version> active() {
        return transactions.inTransaction(dsl -> one(dsl, "select * from zone_matrix_version where state='ACTIVE'"));
    }

    @Override public Optional<Version> draft() {
        return transactions.inTransaction(dsl -> one(dsl, "select * from zone_matrix_version where state='DRAFT'"));
    }

    @Override public List<Revision> revisions(String versionId) {
        return transactions.inTransaction(dsl -> dsl.fetch("select revision, actor, occurred_at, change_kind, zone_id, "
                + "content_sha256, rationale is not null as rationale_given from zone_matrix_revision where version_id={0} "
                + "order by revision", versionId).stream().map(r -> new Revision(r.get("revision", Long.class),
                r.get("actor", String.class), instant(r, "occurred_at"), r.get("change_kind", String.class),
                r.get("zone_id", String.class), r.get("content_sha256", String.class),
                Boolean.TRUE.equals(r.get("rationale_given", Boolean.class)))).toList());
    }

    @Override public void recordAccess(String actor, String kind, String versionId) {
        transactions.inTransaction(dsl -> dsl.execute("insert into zone_matrix_access_log (actor, kind, version_id) values ({0},{1},{2})",
                actor, kind, versionId));
    }

    @Override public <T> T locked(Function<DraftTransaction, T> work) {
        return transactions.inTransaction(dsl -> {
            dsl.fetch("select pg_advisory_xact_lock({0})", LOCK_KEY);
            return work.apply(new Tx(dsl));
        });
    }

    private record Tx(DSLContext dsl) implements DraftTransaction {
        @Override public Optional<Version> draft() {
            return one(dsl, "select * from zone_matrix_version where state='DRAFT' for update");
        }

        @Override public Optional<Version> active() {
            return one(dsl, "select * from zone_matrix_version where state='ACTIVE' for update");
        }

        @Override public Set<String> participants(String versionId) {
            Set<String> actors = new LinkedHashSet<>();
            for (Record r : dsl.fetch("select distinct actor from zone_matrix_revision where version_id={0} and change_kind <> 'PUBLISH'", versionId))
                actors.add(r.get("actor", String.class));
            return actors;
        }

        @Override public int nextNumber() {
            Integer max = dsl.fetchOne("select max(number) as n from zone_matrix_version").get("n", Integer.class);
            return max == null ? 1 : max + 1;
        }

        @Override public void insertDraft(String versionId, String content, String sha256, String basedOn, String actor, Instant now) {
            dsl.execute("insert into zone_matrix_version (version_id, state, revision, content, content_sha256, based_on_version_id, "
                    + "created_by, created_at, updated_by, updated_at) values ({0},'DRAFT',1,{1},{2},{3},{4},{5},{4},{5})",
                    versionId, content, sha256, basedOn, actor, odt(now));
        }

        @Override public void updateDraft(String versionId, long expected, long revision, String content, String sha256, String actor, Instant now) {
            int changed = dsl.execute("update zone_matrix_version set revision={0}, content={1}, content_sha256={2}, updated_by={3}, "
                    + "updated_at={4} where version_id={5} and state='DRAFT' and revision={6}",
                    revision, content, sha256, actor, odt(now), versionId, expected);
            if (changed != 1) throw new Conflict();
        }

        @Override public void appendRevision(String versionId, long revision, String actor, Instant now, String changeKind,
                String zoneId, String sha256, String content, String rationale) {
            dsl.execute("insert into zone_matrix_revision (version_id, revision, actor, occurred_at, change_kind, zone_id, "
                    + "content_sha256, content, rationale) values ({0},{1},{2},{3},{4},{5},{6},{7},{8})",
                    versionId, revision, actor, odt(now), changeKind, zoneId, sha256, content, rationale);
        }

        @Override public void retire(String versionId) {
            if (dsl.execute("update zone_matrix_version set state='RETIRED' where version_id={0} and state='ACTIVE'", versionId) != 1)
                throw new Conflict();
        }

        @Override public void activate(String versionId, int number, String actor, Instant now) {
            if (dsl.execute("update zone_matrix_version set state='ACTIVE', number={0}, published_by={1}, published_at={2} "
                    + "where version_id={3} and state='DRAFT'", number, actor, odt(now), versionId) != 1)
                throw new Conflict();
        }

        @Override public void discard(String versionId) {
            if (dsl.execute("update zone_matrix_version set state='DISCARDED' where version_id={0} and state='DRAFT'", versionId) != 1)
                throw new Conflict();
        }
    }

    private static Optional<Version> one(DSLContext dsl, String sql, Object... bindings) {
        return dsl.fetch(sql, bindings).stream().findFirst().map(JooqZoneMatrixRepository::version);
    }

    private static Version version(Record r) {
        return new Version(r.get("version_id", String.class), r.get("number", Integer.class), r.get("state", String.class),
                r.get("revision", Long.class), r.get("content", String.class), r.get("content_sha256", String.class),
                r.get("based_on_version_id", String.class), r.get("created_by", String.class), instant(r, "created_at"),
                r.get("updated_by", String.class), instant(r, "updated_at"), r.get("published_by", String.class),
                instant(r, "published_at"));
    }

    private static Instant instant(Record r, String field) {
        OffsetDateTime value = r.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
