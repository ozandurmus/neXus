package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
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
    private static final String UTC_ISO = " at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
    private final TransactionBoundary transactions;
    public PolicyChangeRepository(TransactionBoundary transactions) { this.transactions = transactions; }
    public long ceiling() {
        return transactions.inTransaction(db -> db.fetchOne("select coalesce(max(revision_id), 0)::bigint as ceiling from policy_rule_history").get("ceiling", Long.class));
    }
    public List<Row> rows(Filter filter, long ceiling, Position position) {
        var args = new ArrayList<Object>();
        var where = predicates(filter, ceiling, position, args);
        String sql = "with page as materialized (select h.* from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id where "
            + where + " order by coalesce(fn_policy_change_time(h.changed_on), h.collected_at) desc, h.revision_id desc limit 500) "
            + "select h.revision_id, h.policy_id, h.rule_id, h.rule_key, h.change_type, "
            + "to_char(coalesce(fn_policy_change_time(h.changed_on), h.collected_at)" + UTC_ISO + " as time, "
            + "to_char(h.collected_at" + UTC_ISO + " as collected_at, h.initial_capture, "
            + "fn_policy_change_time(h.changed_on) is null as observed, h.changed_by, h.changes::text as changes, s.metadata::text as metadata, "
            + "coalesce(labels.name, current_rule.rule->>'name') as name, coalesce(labels.number, current_rule.rule->'number')::text as number, (labels.name is null or labels.number is null) and current_rule.rule is not null as current_label from page h join policy_snapshot s on s.policy_id=h.policy_id "
            + "left join lateral (select "
            + "(select coalesce(c->>'after', c->>'before') from policy_rule_history r, jsonb_array_elements(r.changes) c "
            + "where r.policy_id=h.policy_id and r.rule_key=h.rule_key and r.revision_id<=h.revision_id and c->>'field'='name' order by r.revision_id desc limit 1) as name, "
            + "(select coalesce(nullif(c->'after', 'null'::jsonb), c->'before') from policy_rule_history r, jsonb_array_elements(r.changes) c "
            + "where r.policy_id=h.policy_id and r.rule_key=h.rule_key and r.revision_id<=h.revision_id and c->>'field'='number' order by r.revision_id desc limit 1) as number) labels on true "
            + "left join lateral (select rule from fn_policy_history_rules(s.snapshot) where rule_key=h.rule_key and (labels.name is null or labels.number is null) limit 1) current_rule on true "
            + "order by coalesce(fn_policy_change_time(h.changed_on), h.collected_at) desc, h.revision_id desc limit 500";
        return transactions.inTransaction(db -> db.fetch(sql, args.toArray()).map(r -> new Row(
            r.get("revision_id", Long.class), r.get("policy_id", String.class), r.get("rule_id", String.class), r.get("rule_key", String.class),
            r.get("change_type", String.class), r.get("time", String.class), r.get("observed", Boolean.class), r.get("changed_by", String.class),
            r.get("changes", String.class), r.get("metadata", String.class), r.get("name", String.class), number(r.get("number", String.class)), r.get("current_label", Boolean.class),
            r.get("collected_at", String.class), Boolean.TRUE.equals(r.get("initial_capture", Boolean.class)))));
    }
    public Map<String, Object> summary(Filter filter, long ceiling) {
        var args = new ArrayList<Object>();
        // One scan: counted rows honour includeInitial; initial captures are always counted so the report can say what it hides.
        var where = predicates(filter, ceiling, null, args, false);
        String counted = filter.includeInitial() ? "true" : "not h.initial_capture";
        return transactions.inTransaction(db -> {
            var r = db.fetchOne("select count(*) filter (where " + counted + ")::bigint as total, "
                + "count(distinct case when h.changed_by !~ '^[[:space:]]*$' then h.changed_by end) filter (where " + counted + ")::bigint as actors, "
                + "count(distinct (h.policy_id, h.rule_key)) filter (where " + counted + ")::bigint as rules, "
                + "count(*) filter (where " + counted + " and h.change_type='added')::bigint as added, "
                + "count(*) filter (where " + counted + " and h.change_type='modified')::bigint as modified, "
                + "count(*) filter (where " + counted + " and h.change_type='removed')::bigint as removed, "
                + "count(*) filter (where h.initial_capture)::bigint as initial_captures "
                + "from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id where " + where, args.toArray());
            return Map.of("total", r.get("total", Long.class), "actors", r.get("actors", Long.class), "rules", r.get("rules", Long.class),
                "counts", Map.of("added", r.get("added", Long.class), "modified", r.get("modified", Long.class), "removed", r.get("removed", Long.class)),
                "initialCaptures", r.get("initial_captures", Long.class), "initialCapturesIncluded", filter.includeInitial());
        });
    }
    private static StringBuilder predicates(Filter filter, long ceiling, Position position, List<Object> args) {
        return predicates(filter, ceiling, position, args, true);
    }
    private static StringBuilder predicates(Filter filter, long ceiling, Position position, List<Object> args, boolean applyInitial) {
        var where = new StringBuilder("coalesce(fn_policy_change_time(h.changed_on), h.collected_at) >= {0}::timestamptz "
            + "and coalesce(fn_policy_change_time(h.changed_on), h.collected_at) < {1}::timestamptz and h.revision_id <= {2}");
        args.add(filter.from().toString()); args.add(filter.to().toString()); args.add(ceiling);
        if (!filter.policy().isEmpty()) add(where, args, "h.policy_id = {%d}", filter.policy());
        if (!filter.device().isEmpty()) add(where, args, "s.metadata->'targets' @> jsonb_build_array(jsonb_build_object('deviceId', {%d}::text))", filter.device());
        if (!filter.changeType().isEmpty()) add(where, args, "h.change_type = {%d}", filter.changeType());
        if (applyInitial && !filter.includeInitial()) where.append(" and not h.initial_capture");
        if (!filter.field().isEmpty()) add(where, args, "h.changes @> jsonb_build_array(jsonb_build_object('field', {%d}::text))", filter.field());
        if (position != null) {
            int n = args.size(); args.add(position.time().toString()); args.add(position.revision());
            where.append(" and (coalesce(fn_policy_change_time(h.changed_on), h.collected_at), h.revision_id) < ({").append(n).append("}::timestamptz, {").append(n + 1).append("})");
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
