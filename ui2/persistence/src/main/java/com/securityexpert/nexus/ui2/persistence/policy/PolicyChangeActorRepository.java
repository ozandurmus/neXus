package com.securityexpert.nexus.ui2.persistence.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Reads and writes for the Palo Alto change-actor join (V208; PO decision record 2026-11-12; design
 * {@code PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md} sections 4 and 6). {@code policy_rule_history} is only
 * read: the link rows are the join's result and cascade with the history row they explain.
 */
public class PolicyChangeActorRepository {
    public static final String MATCHED = "MATCHED";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String UNKNOWN = "UNKNOWN";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A history row without a link. {@code removedChangesJson} is the field diff, read only for a removed rule. */
    public record Pending(long revisionId, String policyId, String ruleKey, String changeType, Instant collectedAt,
            String containerRef, String removedChangesJson) {}

    /** The scope, section and name of a rule as the snapshot stores them (raw; hashed by the caller, never logged). */
    public record RuleIdentity(String scope, String section, String name) {}

    /** One change-class config-log entry that names a rule. {@code time} is {@code yyyy-MM-dd HH:mm:ss}, device-local. */
    public record Edit(String evidenceId, String ruleRefHmac, String adminRef, String adminName, String commandClass, String time) {}

    /** The result for one history row. {@code names} are the candidate administrator names (one, two or more, or none). */
    public record Link(long revisionId, String state, String reason, List<String> names) {
        public Link { names = List.copyOf(names); }
    }

    private final TransactionBoundary tx;

    public PolicyChangeActorRepository(TransactionBoundary tx) { this.tx = tx; }

    /** Non-initial history rows of the source's policies, collected since {@code since}, that have no link yet. */
    public List<Pending> pending(String sourceId, Instant since, int limit) {
        return tx.inTransaction(db -> db.fetch("select h.revision_id, h.policy_id, h.rule_key, h.change_type, h.collected_at, "
            + "s.metadata->>'containerId' as container, case when h.change_type = 'removed' then h.changes::text end as removed_changes "
            + "from policy_rule_history h join policy_snapshot s on s.policy_id = h.policy_id "
            + "left join policy_change_actor_link l on l.revision_id = h.revision_id "
            + "where s.metadata->>'sourceId' = {0} and l.revision_id is null and not h.initial_capture "
            + "and h.collected_at >= {1}::timestamptz order by h.revision_id limit {2}", sourceId, since.toString(), limit)
            .map(r -> new Pending(r.get("revision_id", Long.class), r.get("policy_id", String.class), r.get("rule_key", String.class),
                r.get("change_type", String.class), r.get("collected_at", java.time.OffsetDateTime.class).toInstant(),
                r.get("container", String.class), r.get("removed_changes", String.class))));
    }

    /** Rule identities for rule keys in the policy's current snapshot (added and modified rules are there; removed ones are not). */
    public Map<String, RuleIdentity> identities(String policyId, List<String> ruleKeys) {
        if (ruleKeys.isEmpty()) return Map.of();
        return tx.inTransaction(db -> {
            Map<String, RuleIdentity> out = new LinkedHashMap<>();
            for (var r : db.fetch("select x.rule_key, x.rule->>'container' as scope, x.rule->>'section' as section, x.rule->>'name' as name "
                + "from policy_snapshot ps cross join lateral fn_policy_history_rules(ps.snapshot) x "
                + "where ps.policy_id = {0} and x.rule_key in (select jsonb_array_elements_text({1}::jsonb))", policyId, json(ruleKeys)))
                out.putIfAbsent(r.get("rule_key", String.class), new RuleIdentity(r.get("scope", String.class), r.get("section", String.class), r.get("name", String.class)));
            return out;
        });
    }

    /** The newest revision observation of the container strictly before {@code before}: the previous collection. */
    public Optional<Instant> previousObservation(String sourceId, String containerRef, Instant before) {
        return tx.inTransaction(db -> db.fetch("select max(observed_at) as t from policy_revision where source_id = {0} "
            + "and container_ref = {1} and observed_at < {2}::timestamptz", sourceId, containerRef, before.toString())
            .stream().findFirst().map(r -> r.get("t", java.time.OffsetDateTime.class)).map(java.time.OffsetDateTime::toInstant));
    }

    /** Successful commit entries with device time in {@code (from, to]}, ascending; times are {@code yyyy-MM-dd HH:mm:ss}. */
    public List<String> commitTimes(String sourceId, String from, String to) {
        return tx.inTransaction(db -> db.fetch("select to_char(change_time, 'YYYY-MM-DD HH24:MI:SS') as t from policy_change_evidence "
            + "where source_id = {0} and evidence_kind = 'PAN_CONFIG_LOG' and command_class = 'commit' and result_class = 'Succeeded' "
            + "and change_time > {1}::timestamp and change_time <= {2}::timestamp order by change_time", sourceId, from, to)
            .map(r -> r.get("t", String.class)));
    }

    /** The newest successful commit strictly before {@code before} (device time), if the evidence holds one. */
    public Optional<String> commitBefore(String sourceId, String before) {
        return tx.inTransaction(db -> db.fetch("select to_char(max(change_time), 'YYYY-MM-DD HH24:MI:SS') as t from policy_change_evidence "
            + "where source_id = {0} and evidence_kind = 'PAN_CONFIG_LOG' and command_class = 'commit' and result_class = 'Succeeded' "
            + "and change_time < {1}::timestamp", sourceId, before).stream().findFirst().map(r -> r.get("t", String.class)));
    }

    /** Successful change-class entries that name a rule, device time in {@code (from, to]}, ascending by time. */
    public List<Edit> edits(String sourceId, String from, String to) {
        return tx.inTransaction(db -> db.fetch("select evidence_id, rule_ref_hmac, admin_ref, admin_name, command_class, "
            + "to_char(change_time, 'YYYY-MM-DD HH24:MI:SS') as t from policy_change_evidence where source_id = {0} "
            + "and evidence_kind = 'PAN_CONFIG_LOG' and rule_ref_hmac is not null and admin_name is not null and result_class = 'Succeeded' "
            + "and change_time > {1}::timestamp and change_time <= {2}::timestamp order by change_time, evidence_id", sourceId, from, to)
            .map(r -> new Edit(r.get("evidence_id", String.class), r.get("rule_ref_hmac", String.class), r.get("admin_ref", String.class),
                r.get("admin_name", String.class), r.get("command_class", String.class), r.get("t", String.class))));
    }

    /** The links of the given history rows (rows without a link are absent from the map). */
    public Map<Long, Link> links(List<Long> revisionIds) {
        if (revisionIds.isEmpty()) return Map.of();
        return tx.inTransaction(db -> {
            Map<Long, Link> out = new LinkedHashMap<>();
            for (var r : db.fetch("select revision_id, state, reason, candidate_names::text as names from policy_change_actor_link "
                + "where revision_id in (select jsonb_array_elements_text({0}::jsonb)::bigint)", jsonLongs(revisionIds))) {
                long id = r.get("revision_id", Long.class);
                out.put(id, new Link(id, r.get("state", String.class), r.get("reason", String.class), names(r.get("names", String.class))));
            }
            return out;
        });
    }

    /** The candidate administrator names of one history row (empty when none or not linked). For authorised roles only. */
    public List<String> candidates(long revisionId) {
        return links(List.of(revisionId)).getOrDefault(revisionId, new Link(revisionId, UNKNOWN, "NOT_LINKED", List.of())).names();
    }

    /** Stores the results; a history row already linked keeps its link (idempotent). */
    public int saveLinks(String jobId, List<Link> links) {
        return tx.inTransaction(db -> {
            int stored = 0;
            for (Link l : links)
                stored += db.execute("insert into policy_change_actor_link(revision_id, state, reason, admin_count, candidate_names, resolved_job_id) "
                    + "values ({0},{1},{2},{3},{4}::jsonb,{5}) on conflict (revision_id) do nothing",
                    l.revisionId(), l.state(), l.reason(), l.names().size(), json(l.names()), jobId);
            return stored;
        });
    }

    /**
     * PO D5: the name lives as long as the history row it explains. A name on an evidence row that no link row can still use
     * (older than the edit lookback) is cleared; a name that a link row copied stays on the link row only.
     */
    public int clearExpiredNames(String sourceId, String olderThanDeviceTime) {
        return tx.inTransaction(db -> db.execute("update policy_change_evidence set admin_name = null where source_id = {0} "
            + "and admin_name is not null and change_time < {1}::timestamp", sourceId, olderThanDeviceTime));
    }

    private static List<String> names(String json) {
        try {
            List<String> out = new ArrayList<>();
            JSON.readTree(json).forEach(n -> out.add(n.asText()));
            return out;
        } catch (java.io.IOException invalid) { return List.of(); }
    }

    private static String jsonLongs(List<Long> values) {
        try { return JSON.writeValueAsString(values); }
        catch (JsonProcessingException impossible) { return "[]"; }
    }

    private static String json(List<String> values) {
        try { return JSON.writeValueAsString(new ArrayList<>(values)); }
        catch (JsonProcessingException impossible) { return "[]"; }
    }
}
