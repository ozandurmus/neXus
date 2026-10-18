package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import java.time.Instant;
import java.util.*;

/** Indexed history reads; snapshots and collection paths are never modified. */
public final class PolicyChangeRepository {
    /** includeInitial=false leaves out a policy's initial baseline capture (V150), which is not a change anybody made. */
    public record Filter(Instant from, Instant to, String policy, String device, String changeType, String field, boolean includeInitial) {
        public Filter(Instant from, Instant to, String policy, String device, String changeType, String field) {
            this(from, to, policy, device, changeType, field, true);
        }
    }
    public record Position(Instant time, long revision) {}
    public record Row(long revision, String policy, String ruleId, String ruleKey, String changeType, String time,
                      boolean observed, String actor, String changes, String metadata, String name, Integer number, boolean currentLabel,
                      String collectedAt, boolean initialCapture) {
        public Row(long revision, String policy, String ruleId, String ruleKey, String changeType, String time,
                   boolean observed, String actor, String changes, String metadata, String name, Integer number, boolean currentLabel) {
            this(revision, policy, ruleId, ruleKey, changeType, time, observed, actor, changes, metadata, name, number, currentLabel, time, false);
        }
    }
    /**
     * First appearance of a Palo Alto NAT or default-rules section kind in a policy (device group). The history trigger
     * has no way to tell "the rule was added" from "the collector began parsing this rulebase", so every NAT and
     * default rule would otherwise be reported as added once, after the release that started parsing them. An 'added'
     * revision of such a section is treated as initial capture when no earlier revision of the same policy carries that
     * section name. A later addition finds the earlier batch and is reported normally. Section names are fixed by the
     * mapper and only trusted on Palo Alto policies; a Check Point section name is user text and is never classified.
     * Evaluated at read time, so no history row is rewritten and no migration is needed.
     *
     * <p>Whether an earlier revision exists depends only on (policy, collected_at, kind), not on the row, so the probe
     * runs once per distinct triple among the candidate rows (non-initial 'added' revisions of a Palo Alto policy)
     * instead of once per row: the {@code fresh} CTE holds the triples with no earlier revision and {@code first_rev}
     * the candidate revisions that belong to them. Requires a {@code cand(revision_id, policy_id, collected_at, kind)}
     * CTE, where kind is {@link #CANDIDATE_KIND}.
     */
    private static final String KIND = "(select c->>'after' from jsonb_array_elements(h.changes) c where c->>'field' = 'section' limit 1)";
    /** The section kind of a revision that could be a first appearance; null for every other row, so no jsonb work is done for them. */
    static final String CANDIDATE_KIND = "case when h.change_type = 'added' and s.metadata->>'vendor' = 'PAN' then " + KIND + " end";
    static final String FIRST_APPEARANCES;
    static {
        String names = String.join(", ", java.util.stream.Stream.of(PolicySnapshot.PAN_PRE_NAT, PolicySnapshot.PAN_POST_NAT,
            PolicySnapshot.PAN_PRE_DEFAULT, PolicySnapshot.PAN_POST_DEFAULT).map(n -> "'" + n + "'").toList());
        // The probe document is built once per triple, and the existence check is a per-triple index probe that stops at the first earlier match.
        FIRST_APPEARANCES = "triples as materialized (select policy_id, collected_at, kind, "
            + "jsonb_build_array(jsonb_build_object('field'::text, 'section'::text, 'after', kind)) as probe from "
            + "(select distinct policy_id, collected_at, kind from cand where kind in (" + names + ")) d), "
            + "fresh as materialized (select t.policy_id, t.collected_at, t.kind from triples t "
            + "left join lateral (select 1 as earlier from policy_rule_history e where e.policy_id = t.policy_id and e.collected_at < t.collected_at "
            + "and e.changes @> t.probe limit 1) x on true where x.earlier is null), "
            + "first_rev as materialized (select c.revision_id from cand c "
            + "join fresh f on f.policy_id = c.policy_id and f.collected_at = c.collected_at and f.kind = c.kind)";
    }
    private static final String TIME = "coalesce(fn_policy_change_time(h.changed_on), h.collected_at)";
    private static final String UTC_ISO = " at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
    private final TransactionBoundary transactions;
    public PolicyChangeRepository(TransactionBoundary transactions) { this.transactions = transactions; }
    public long ceiling() {
        return transactions.inTransaction(db -> db.fetchOne("select coalesce(max(revision_id), 0)::bigint as ceiling from policy_rule_history").get("ceiling", Long.class));
    }
    public List<Row> rows(Filter filter, long ceiling, Position position) {
        var args = new ArrayList<Object>();
        var where = predicates(filter, ceiling, position, args);
        String selection, initial, firstJoin;
        if (filter.includeInitial()) {
            // The newest 500 rows by index order; only those 500 are checked for being a first appearance.
            selection = "page as materialized (select h.revision_id, " + TIME + " as ts from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id where "
                + where + " order by " + TIME + " desc, h.revision_id desc limit 500), "
                + "cand as materialized (select h.revision_id, h.policy_id, h.collected_at, " + CANDIDATE_KIND + " as kind from page p "
                + "join policy_rule_history h on h.revision_id = p.revision_id join policy_snapshot s on s.policy_id = h.policy_id where not h.initial_capture), "
                + FIRST_APPEARANCES + " ";
            initial = "(h.initial_capture or fr.revision_id is not null)";
            firstJoin = "left join first_rev fr on fr.revision_id = h.revision_id ";
        } else {
            // Initial captures are left out by the (partial-index) marker before any per-row work; the few remaining
            // candidate rows are narrow, and the probe for first appearances runs once per distinct (policy, batch, kind).
            selection = "cand as materialized (select h.revision_id, h.policy_id, h.collected_at, " + TIME + " as ts, " + CANDIDATE_KIND + " as kind "
                + "from policy_rule_history h join policy_snapshot s on s.policy_id = h.policy_id where " + where + " and not h.initial_capture), "
                + FIRST_APPEARANCES + ", "
                + "page as materialized (select c.revision_id, c.ts from cand c where not exists (select 1 from first_rev fr where fr.revision_id = c.revision_id) "
                + "order by c.ts desc, c.revision_id desc limit 500) ";
            initial = "false";
            firstJoin = "";
        }
        // Labels, and the whole-snapshot expansion for a missing label, are computed for the page's rows only (after the limit).
        String sql = "with " + selection
            + "select h.revision_id, h.policy_id, h.rule_id, h.rule_key, h.change_type, "
            + "to_char(" + TIME + UTC_ISO + " as time, "
            + "to_char(h.collected_at" + UTC_ISO + " as collected_at, " + initial + " as initial_capture, "
            + "fn_policy_change_time(h.changed_on) is null as observed, h.changed_by, h.changes::text as changes, s.metadata::text as metadata, "
            + "coalesce(labels.name, current_rule.rule->>'name') as name, coalesce(labels.number, current_rule.rule->'number')::text as number, (labels.name is null or labels.number is null) and current_rule.rule is not null as current_label "
            + "from page p join policy_rule_history h on h.revision_id = p.revision_id join policy_snapshot s on s.policy_id=h.policy_id " + firstJoin
            + "left join lateral (select "
            + "(select coalesce(c->>'after', c->>'before') from policy_rule_history r, jsonb_array_elements(r.changes) c "
            + "where r.policy_id=h.policy_id and r.rule_key=h.rule_key and r.revision_id<=h.revision_id and c->>'field'='name' order by r.revision_id desc limit 1) as name, "
            + "(select coalesce(nullif(c->'after', 'null'::jsonb), c->'before') from policy_rule_history r, jsonb_array_elements(r.changes) c "
            + "where r.policy_id=h.policy_id and r.rule_key=h.rule_key and r.revision_id<=h.revision_id and c->>'field'='number' order by r.revision_id desc limit 1) as number) labels on true "
            // The snapshot is only handed to the expanding function when a label is missing.
            + "left join lateral (select rule from fn_policy_history_rules(case when labels.name is null or labels.number is null then s.snapshot end) "
            + "where rule_key=h.rule_key and (labels.name is null or labels.number is null) limit 1) current_rule on true "
            + "order by p.ts desc, h.revision_id desc";
        return transactions.inTransaction(db -> db.fetch(sql, args.toArray()).map(r -> new Row(
            r.get("revision_id", Long.class), r.get("policy_id", String.class), r.get("rule_id", String.class), r.get("rule_key", String.class),
            r.get("change_type", String.class), r.get("time", String.class), r.get("observed", Boolean.class), r.get("changed_by", String.class),
            r.get("changes", String.class), r.get("metadata", String.class), r.get("name", String.class), number(r.get("number", String.class)), r.get("current_label", Boolean.class),
            r.get("collected_at", String.class), Boolean.TRUE.equals(r.get("initial_capture", Boolean.class)))));
    }
    public Map<String, Object> summary(Filter filter, long ceiling) {
        var args = new ArrayList<Object>();
        var where = predicates(filter, ceiling, null, args);
        // Every non-initial revision in range, narrow; the first-appearance probe runs once per distinct (policy, batch, kind).
        String candidates = "with cand as materialized (select h.revision_id, h.policy_id, h.rule_key, h.change_type, h.changed_by, h.collected_at, "
            + CANDIDATE_KIND + " as kind from policy_rule_history h join policy_snapshot s on s.policy_id = h.policy_id where " + where + " and not h.initial_capture), "
            + FIRST_APPEARANCES + " ";
        String sql;
        if (filter.includeInitial()) {
            // Everything counts, initial captures included; first appearances are only needed for the initial_captures figure.
            sql = candidates + "select count(*)::bigint as total, "
                + "count(distinct case when h.changed_by !~ '^[[:space:]]*$' then h.changed_by end)::bigint as actors, "
                + "count(distinct (h.policy_id, h.rule_key))::bigint as rules, "
                + "count(*) filter (where h.change_type='added')::bigint as added, "
                + "count(*) filter (where h.change_type='modified')::bigint as modified, "
                + "count(*) filter (where h.change_type='removed')::bigint as removed, "
                + "count(*) filter (where h.initial_capture or fr.revision_id is not null)::bigint as initial_captures "
                + "from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id left join first_rev fr on fr.revision_id = h.revision_id where " + where;
        } else {
            // Counted rows are the non-initial candidates that are not a first appearance; the (hidden) initial captures
            // are one plain count of the marked rows, so the report can still say what it hides.
            sql = candidates + "select count(*) filter (where fr.revision_id is null)::bigint as total, "
                + "count(distinct case when fr.revision_id is null and c.changed_by !~ '^[[:space:]]*$' then c.changed_by end)::bigint as actors, "
                + "count(distinct (c.policy_id, c.rule_key)) filter (where fr.revision_id is null)::bigint as rules, "
                + "count(*) filter (where fr.revision_id is null and c.change_type='added')::bigint as added, "
                + "count(*) filter (where fr.revision_id is null and c.change_type='modified')::bigint as modified, "
                + "count(*) filter (where fr.revision_id is null and c.change_type='removed')::bigint as removed, "
                + "(count(*) filter (where fr.revision_id is not null) + (select count(*) from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id "
                + "where " + where + " and h.initial_capture))::bigint as initial_captures "
                + "from cand c left join first_rev fr on fr.revision_id = c.revision_id";
        }
        return transactions.inTransaction(db -> {
            var r = db.fetchOne(sql, args.toArray());
            return Map.of("total", r.get("total", Long.class), "actors", r.get("actors", Long.class), "rules", r.get("rules", Long.class),
                "counts", Map.of("added", r.get("added", Long.class), "modified", r.get("modified", Long.class), "removed", r.get("removed", Long.class)),
                "initialCaptures", r.get("initial_captures", Long.class), "initialCapturesIncluded", filter.includeInitial());
        });
    }
    /** Range, ceiling, user filters and keyset position; initial-capture handling is the caller's, so the same text serves both scans. */
    private static StringBuilder predicates(Filter filter, long ceiling, Position position, List<Object> args) {
        var where = new StringBuilder(TIME + " >= {0}::timestamptz and " + TIME + " < {1}::timestamptz and h.revision_id <= {2}");
        args.add(filter.from().toString()); args.add(filter.to().toString()); args.add(ceiling);
        if (!filter.policy().isEmpty()) add(where, args, "h.policy_id = {%d}", filter.policy());
        if (!filter.device().isEmpty()) add(where, args, "s.metadata->'targets' @> jsonb_build_array(jsonb_build_object('deviceId', {%d}::text))", filter.device());
        if (!filter.changeType().isEmpty()) add(where, args, "h.change_type = {%d}", filter.changeType());
        if (!filter.field().isEmpty()) add(where, args, "h.changes @> jsonb_build_array(jsonb_build_object('field', {%d}::text))", filter.field());
        if (position != null) {
            int n = args.size(); args.add(position.time().toString()); args.add(position.revision());
            where.append(" and (" + TIME + ", h.revision_id) < ({").append(n).append("}::timestamptz, {").append(n + 1).append("})");
        }
        return where;
    }
    private static Integer number(String value) {
        if (value == null || value.equals("null")) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException invalid) { return null; }
    }
    private static void add(StringBuilder sql, List<Object> args, String predicate, Object value) {
        sql.append(" and ").append(predicate.formatted(args.size())); args.add(value);
    }
}
