package com.securityexpert.nexus.ui2.integration.schema;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;

class PolicyChangeReportSchemaTest {
    @Test void timestampFallbackFilteringAndStableKeysetsUseRealPostgres() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_report")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                sql.execute("insert into policy_snapshot values ('p1', '2026-10-09T00:00:00Z', '{\"name\":\"POL-ALPHA-01\",\"targets\":[{\"deviceId\":\"d1\",\"name\":\"FW-TANGO-04\"}]}', '{\"sections\":[],\"objects\":{},\"failures\":[]}')");
                for (String changedOn : Arrays.asList("2026-10-08T02:00:00+02:00", null, "2026-02-30T00:00:00Z", "not a timestamp", "2026-10-08T00:00:00", "2026-10-08T00:00:00+99:99")) {
                    try (var insert = db.prepareStatement("insert into policy_rule_history (policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changed_on,changed_by,changes) "
                            + "values ('p1','uuid:r1','r1',false,'modified','2026-10-08T00:00:00Z',?,'Synthetic actor','[{\"field\":\"name\",\"before\":\"RULE-ALPHA-01\",\"after\":\"RULE-BRAVO-02\"},{\"field\":\"number\",\"before\":1,\"after\":7}]')")) {
                        insert.setString(1, changedOn); insert.executeUpdate();
                    }
                }
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                long ceiling = repository.ceiling();
                var filter = new PolicyChangeRepository.Filter(Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"), "p1", "d1", "modified", "name");
                var rows = repository.rows(filter, ceiling, null);
                assertEquals(6, rows.size()); assertEquals(5, rows.stream().filter(PolicyChangeRepository.Row::observed).count());
                assertEquals("2026-10-08T00:00:00Z", Instant.parse(rows.get(0).time()).toString());
                assertEquals("RULE-BRAVO-02", rows.get(0).name()); assertEquals(7, rows.get(0).number());
                assertEquals(6L, repository.summary(filter, ceiling).get("total"));
                var second = repository.rows(filter, ceiling, new PolicyChangeRepository.Position(Instant.parse(rows.get(1).time()), rows.get(1).revision()));
                assertEquals(rows.subList(2, 6), second);
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changes) values ('p1','uuid:r2','r2',false,'added','2026-10-08T00:00:00Z','[]')");
                assertEquals(6, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "", "", "", ""), ceiling, null).size());
                assertEquals(0, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "p1", "missing", "", ""), ceiling, null).size());
                assertEquals(0, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "", "", "removed", ""), ceiling, null).size());
                assertEquals(0, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "", "", "", "action"), ceiling, null).size());
                sql.execute("set enable_seqscan=off");
                try (var plan = sql.executeQuery("explain select revision_id from policy_rule_history where coalesce(fn_policy_change_time(changed_on),collected_at) >= '2026-10-08T00:00:00Z' order by coalesce(fn_policy_change_time(changed_on),collected_at) desc,revision_id desc limit 100")) {
                    var lines = new ArrayList<String>(); while (plan.next()) lines.add(plan.getString(1));
                    assertTrue(String.join("\n", lines).contains("policy_changes_time"));
                }
            }
        }
    }
    @Test void initialCapturesAreLeftOutOfTheDefaultReportAndCounted() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_initial")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                sql.execute("insert into policy_snapshot values ('p1', '2026-10-09T00:00:00Z', '{\"name\":\"POL-ALPHA-01\",\"targets\":[]}', '{\"sections\":[],\"objects\":{},\"failures\":[]}')");
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changes,initial_capture) values "
                    + "('p1','uuid:r1','r1',false,'added','2026-10-08T00:00:00Z','[]',true),('p1','uuid:r2','r2',false,'added','2026-10-08T00:00:00Z','[]',true),"
                    + "('p1','uuid:r1','r1',false,'modified','2026-10-08T06:00:00Z','[{\"field\":\"action\",\"before\":\"Accept\",\"after\":\"Drop\"}]',false)");
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                long ceiling = repository.ceiling();
                Instant from = Instant.parse("2026-10-08T00:00:00Z"), to = Instant.parse("2026-10-09T00:00:00Z");
                var real = new PolicyChangeRepository.Filter(from, to, "", "", "", "", false);
                var rows = repository.rows(real, ceiling, null);
                assertEquals(1, rows.size()); assertFalse(rows.get(0).initialCapture());
                assertEquals("2026-10-08T06:00:00.000000Z", rows.get(0).collectedAt());
                var summary = repository.summary(real, ceiling);
                assertEquals(1L, summary.get("total")); assertEquals(2L, summary.get("initialCaptures"));
                assertEquals(0L, ((Map<?, ?>) summary.get("counts")).get("added"));
                var all = new PolicyChangeRepository.Filter(from, to, "", "", "", "", true);
                assertEquals(3, repository.rows(all, ceiling, null).size());
                assertEquals(3L, repository.summary(all, ceiling).get("total"));
                assertEquals(2, repository.rows(all, ceiling, null).stream().filter(PolicyChangeRepository.Row::initialCapture).count());
            }
        }
    }
    private static String natRule(String id) {
        return "{\"id\":\"" + id + "\",\"uuid\":\"" + id + "\",\"name\":\"OBJ-RULE-" + id + "\",\"number\":1,\"enabled\":true,"
            + "\"source\":{\"refs\":[],\"negated\":false},\"destination\":{\"refs\":[],\"negated\":false},\"service\":{\"refs\":[],\"negated\":false},"
            + "\"application\":{\"refs\":[],\"negated\":false},\"action\":\"source-nat\",\"log\":\"\",\"comment\":\"\",\"extras\":{}}";
    }
    private static String section(String name, String... ids) {
        return "{\"source\":\"Shared\",\"name\":\"" + name + "\",\"rules\":[" + String.join(",", Arrays.stream(ids).map(PolicyChangeReportSchemaTest::natRule).toList()) + "]}";
    }
    private static void publish(java.sql.Connection db, String policy, String vendor, int hour, String... sections) throws Exception {
        try (var statement = db.prepareStatement("insert into policy_snapshot values (?, ?::timestamptz, ?::jsonb, ?::jsonb) "
                + "on conflict (policy_id) do update set collected_at=excluded.collected_at, metadata=excluded.metadata, snapshot=excluded.snapshot "
                + "where policy_snapshot.collected_at < excluded.collected_at")) {
            statement.setString(1, policy); statement.setString(2, "2026-10-02T" + String.format("%02d", hour) + ":00:00Z");
            statement.setString(3, "{\"vendor\":\"" + vendor + "\",\"name\":\"POL-ALPHA-01\",\"targets\":" + (policy.equals("p-pan") ? "[{\"deviceId\":\"d1\",\"name\":\"FW-TANGO-04\"}]" : "[]") + "}");
            statement.setString(4, "{\"sections\":[" + String.join(",", sections) + "],\"objects\":{},\"failures\":[]}");
            statement.executeUpdate();
        }
    }
    @Test void firstAppearanceOfPanNatAndDefaultSectionsIsInitialCaptureAndLaterAdditionsDiffNormally() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_nat_first")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection()) {
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                Instant from = Instant.parse("2026-10-02T00:00:00Z"), to = Instant.parse("2026-10-03T00:00:00Z");
                var real = new PolicyChangeRepository.Filter(from, to, "", "", "", "", false);
                var all = new PolicyChangeRepository.Filter(from, to, "", "", "", "", true);
                // Security rules only: the policy's own initial capture (V150).
                publish(db, "p-pan", "PAN", 1, section("Pre rules", "r1"));
                // The release that parses NAT and default rules: the next snapshot carries three new sections' rules.
                publish(db, "p-pan", "PAN", 2, section("Pre rules", "r1"), section("Pre NAT rules", "n1", "n2"), section("Post default rules", "d1"));
                long ceiling = repository.ceiling();
                assertEquals(0, repository.rows(real, ceiling, null).size(), "first capture of a section kind yields no added event");
                var summary = repository.summary(real, ceiling);
                assertEquals(0L, summary.get("total")); assertEquals(0L, ((Map<?, ?>) summary.get("counts")).get("added"));
                assertEquals(4L, summary.get("initialCaptures"), "r1 plus the three first-appearance rules are all counted as initial");
                assertEquals(4, repository.rows(all, ceiling, null).stream().filter(PolicyChangeRepository.Row::initialCapture).count());
                // The next capture adds one NAT rule to a kind that already exists: exactly one event.
                publish(db, "p-pan", "PAN", 3, section("Pre rules", "r1"), section("Pre NAT rules", "n1", "n2", "n3"), section("Post default rules", "d1"));
                ceiling = repository.ceiling();
                var rows = repository.rows(real, ceiling, null);
                assertEquals(1, rows.size()); assertEquals("added", rows.get(0).changeType()); assertEquals("uuid:n3", rows.get(0).ruleKey());
                assertFalse(rows.get(0).initialCapture());
                summary = repository.summary(real, ceiling);
                assertEquals(1L, summary.get("total")); assertEquals(1L, ((Map<?, ?>) summary.get("counts")).get("added"));
                assertEquals(4L, summary.get("initialCaptures"));
                // A security rule added in the same release window is an ordinary change.
                publish(db, "p-pan", "PAN", 4, section("Pre rules", "r1", "r2"), section("Pre NAT rules", "n1", "n2", "n3"), section("Post default rules", "d1"));
                assertEquals(2, repository.rows(real, repository.ceiling(), null).size());
                // A Check Point policy never gets the Palo Alto classification, whatever its section is called.
                publish(db, "p-cp", "CP", 1, section("Pre NAT rules", "c1"));
                publish(db, "p-cp", "CP", 2, section("Pre NAT rules", "c1", "c2"));
                var cp = repository.rows(new PolicyChangeRepository.Filter(from, to, "p-cp", "", "", "", false), repository.ceiling(), null);
                assertEquals(1, cp.size()); assertEquals("uuid:c2", cp.get(0).ruleKey());
            }
        }
    }
    // ---- The query as it was before the read-path rewrite, kept verbatim as the reference for the equivalence test. ----
    private static final String LEGACY_KIND = "(select c->>'after' from jsonb_array_elements(h.changes) c where c->>'field' = 'section' limit 1)";
    private static final String LEGACY_FIRST_KIND_APPEARANCE;
    static {
        String names = String.join(", ", java.util.stream.Stream.of("Pre NAT rules", "Post NAT rules", "Pre default rules", "Post default rules").map(n -> "'" + n + "'").toList());
        LEGACY_FIRST_KIND_APPEARANCE = "coalesce(h.change_type = 'added' and s.metadata->>'vendor' = 'PAN' and " + LEGACY_KIND + " in (" + names + ") "
            + "and not exists (select 1 from policy_rule_history e where e.policy_id = h.policy_id and e.collected_at < h.collected_at "
            + "and e.changes @> jsonb_build_array(jsonb_build_object('field'::text, 'section'::text, 'after', " + LEGACY_KIND + "))), false)";
    }
    private static final String LEGACY_EFFECTIVE_INITIAL = "(h.initial_capture or " + LEGACY_FIRST_KIND_APPEARANCE + ")";
    private static final String UTC_ISO = " at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
    private static StringBuilder legacyPredicates(PolicyChangeRepository.Filter filter, long ceiling, PolicyChangeRepository.Position position, List<Object> args, boolean applyInitial) {
        var where = new StringBuilder("coalesce(fn_policy_change_time(h.changed_on), h.collected_at) >= {0}::timestamptz "
            + "and coalesce(fn_policy_change_time(h.changed_on), h.collected_at) < {1}::timestamptz and h.revision_id <= {2}");
        args.add(filter.from().toString()); args.add(filter.to().toString()); args.add(ceiling);
        java.util.function.BiConsumer<String, Object> add = (predicate, value) -> { where.append(" and ").append(predicate.formatted(args.size())); args.add(value); };
        if (!filter.policy().isEmpty()) add.accept("h.policy_id = {%d}", filter.policy());
        if (!filter.device().isEmpty()) add.accept("s.metadata->'targets' @> jsonb_build_array(jsonb_build_object('deviceId', {%d}::text))", filter.device());
        if (!filter.changeType().isEmpty()) add.accept("h.change_type = {%d}", filter.changeType());
        if (applyInitial && !filter.includeInitial()) where.append(" and not h.initial_capture and not ").append(LEGACY_FIRST_KIND_APPEARANCE);
        if (!filter.field().isEmpty()) add.accept("h.changes @> jsonb_build_array(jsonb_build_object('field', {%d}::text))", filter.field());
        if (position != null) {
            int n = args.size(); args.add(position.time().toString()); args.add(position.revision());
            where.append(" and (coalesce(fn_policy_change_time(h.changed_on), h.collected_at), h.revision_id) < ({").append(n).append("}::timestamptz, {").append(n + 1).append("})");
        }
        return where;
    }
    private static List<PolicyChangeRepository.Row> legacyRows(java.sql.Connection db, PolicyChangeRepository.Filter filter, long ceiling, PolicyChangeRepository.Position position) {
        var args = new ArrayList<Object>();
        var where = legacyPredicates(filter, ceiling, position, args, true);
        String sql = "with page as materialized (select h.* from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id where "
            + where + " order by coalesce(fn_policy_change_time(h.changed_on), h.collected_at) desc, h.revision_id desc limit 500) "
            + "select h.revision_id, h.policy_id, h.rule_id, h.rule_key, h.change_type, "
            + "to_char(coalesce(fn_policy_change_time(h.changed_on), h.collected_at)" + UTC_ISO + " as time, "
            + "to_char(h.collected_at" + UTC_ISO + " as collected_at, " + LEGACY_EFFECTIVE_INITIAL + " as initial_capture, "
            + "fn_policy_change_time(h.changed_on) is null as observed, h.changed_by, h.changes::text as changes, s.metadata::text as metadata, "
            + "coalesce(labels.name, current_rule.rule->>'name') as name, coalesce(labels.number, current_rule.rule->'number')::text as number, (labels.name is null or labels.number is null) and current_rule.rule is not null as current_label from page h join policy_snapshot s on s.policy_id=h.policy_id "
            + "left join lateral (select "
            + "(select coalesce(c->>'after', c->>'before') from policy_rule_history r, jsonb_array_elements(r.changes) c "
            + "where r.policy_id=h.policy_id and r.rule_key=h.rule_key and r.revision_id<=h.revision_id and c->>'field'='name' order by r.revision_id desc limit 1) as name, "
            + "(select coalesce(nullif(c->'after', 'null'::jsonb), c->'before') from policy_rule_history r, jsonb_array_elements(r.changes) c "
            + "where r.policy_id=h.policy_id and r.rule_key=h.rule_key and r.revision_id<=h.revision_id and c->>'field'='number' order by r.revision_id desc limit 1) as number) labels on true "
            + "left join lateral (select rule from fn_policy_history_rules(s.snapshot) where rule_key=h.rule_key and (labels.name is null or labels.number is null) limit 1) current_rule on true "
            + "order by coalesce(fn_policy_change_time(h.changed_on), h.collected_at) desc, h.revision_id desc limit 500";
        return DSL.using(db, SQLDialect.POSTGRES).fetch(sql, args.toArray()).map(r -> {
            String number = r.get("number", String.class); Integer parsed = null;
            if (number != null && !number.equals("null")) parsed = Integer.valueOf(number);
            return new PolicyChangeRepository.Row(r.get("revision_id", Long.class), r.get("policy_id", String.class), r.get("rule_id", String.class), r.get("rule_key", String.class),
                r.get("change_type", String.class), r.get("time", String.class), r.get("observed", Boolean.class), r.get("changed_by", String.class),
                r.get("changes", String.class), r.get("metadata", String.class), r.get("name", String.class), parsed, r.get("current_label", Boolean.class),
                r.get("collected_at", String.class), Boolean.TRUE.equals(r.get("initial_capture", Boolean.class)));
        });
    }
    private static Map<String, Object> legacySummary(java.sql.Connection db, PolicyChangeRepository.Filter filter, long ceiling) {
        var args = new ArrayList<Object>();
        var where = legacyPredicates(filter, ceiling, null, args, false);
        String counted = filter.includeInitial() ? "true" : "not h.initial_capture and not " + LEGACY_FIRST_KIND_APPEARANCE;
        var r = DSL.using(db, SQLDialect.POSTGRES).fetchOne("select count(*) filter (where " + counted + ")::bigint as total, "
            + "count(distinct case when h.changed_by !~ '^[[:space:]]*$' then h.changed_by end) filter (where " + counted + ")::bigint as actors, "
            + "count(distinct (h.policy_id, h.rule_key)) filter (where " + counted + ")::bigint as rules, "
            + "count(*) filter (where " + counted + " and h.change_type='added')::bigint as added, "
            + "count(*) filter (where " + counted + " and h.change_type='modified')::bigint as modified, "
            + "count(*) filter (where " + counted + " and h.change_type='removed')::bigint as removed, "
            + "count(*) filter (where " + LEGACY_EFFECTIVE_INITIAL + ")::bigint as initial_captures "
            + "from policy_rule_history h join policy_snapshot s on s.policy_id=h.policy_id where " + where, args.toArray());
        return Map.of("total", r.get("total", Long.class), "actors", r.get("actors", Long.class), "rules", r.get("rules", Long.class),
            "counts", Map.of("added", r.get("added", Long.class), "modified", r.get("modified", Long.class), "removed", r.get("removed", Long.class)),
            "initialCaptures", r.get("initial_captures", Long.class), "initialCapturesIncluded", filter.includeInitial());
    }
    /**
     * The rewritten read path returns exactly what the previous one did, on a history dominated by initial captures:
     * same rows, order, labels and initial flags, and the same summary, for every filter combination, with and without
     * initial captures, and across keyset pages.
     */
    @Test void rewrittenReadsMatchTheOriginalDefinitionOnAHistoryOfManyInitialCaptures() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_equivalence")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                Instant from = Instant.parse("2026-10-01T00:00:00Z"), to = Instant.parse("2026-10-20T00:00:00Z");
                // Palo Alto policy: security rules first, then a release that starts parsing NAT and default rules, then later additions.
                publish(db, "p-pan", "PAN", 1, section("Pre rules", "r1", "r2"));
                publish(db, "p-pan", "PAN", 2, section("Pre rules", "r1", "r2"), section("Pre NAT rules", "n1", "n2"), section("Post default rules", "d1"));
                publish(db, "p-pan", "PAN", 3, section("Pre rules", "r1", "r2", "r3"), section("Pre NAT rules", "n1", "n2", "n3"), section("Post default rules", "d1"));
                publish(db, "p-pan", "PAN", 4, section("Pre rules", "r1", "r3"), section("Pre NAT rules", "n1", "n3"), section("Post default rules", "d1", "d2"));
                publish(db, "p-cp", "CP", 1, section("Pre NAT rules", "c1"));
                publish(db, "p-cp", "CP", 2, section("Pre NAT rules", "c1", "c2"));
                publish(db, "p-other", "PAN", 5, section("Pre rules", "o1"));
                publish(db, "p-other", "PAN", 6, section("Pre rules", "o1", "o2"), section("Post NAT rules", "x1"));
                // A rule whose history carries no label, so the current snapshot supplies it; observed and explicit change times; blank and named actors.
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changed_on,changed_by,changes) values "
                    + "('p-pan','uuid:r1','r1',false,'modified','2026-10-05T00:00:00Z','2026-10-04T10:00:00+00:00','Synthetic actor','[{\"field\":\"action\",\"before\":\"Accept\",\"after\":\"Drop\"}]'),"
                    + "('p-pan','uuid:n1','n1',false,'modified','2026-10-06T00:00:00Z',null,'  ','[{\"field\":\"name\",\"before\":\"A\",\"after\":\"B\"},{\"field\":\"number\",\"before\":1,\"after\":9}]'),"
                    + "('p-cp','uuid:c1','c1',false,'removed','2026-10-07T00:00:00Z','not a time',null,'[{\"field\":\"source\"}]'),"
                    + "('p-other','uuid:ghost','ghost',false,'modified','2026-10-08T00:00:00Z',null,'Other actor','[{\"field\":\"comment\"}]')");
                // Many initial captures, as after the estate's first complete collections: most of the history in range.
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changes,initial_capture) "
                    + "select (array['p-pan','p-cp','p-other'])[1 + g % 3], 'uuid:bulk' || g, 'bulk' || g, false, 'added', timestamptz '2026-10-03T00:00:00Z' + (g % 50) * interval '1 minute', "
                    + "jsonb_build_array(jsonb_build_object('field', 'name', 'after', 'BULK-' || g), jsonb_build_object('field', 'number', 'after', g)), true from generate_series(1, 3000) g");
                // More real changes than one page holds, so the page limit and the keyset continuation are both exercised.
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changed_by,changes) "
                    + "select (array['p-pan','p-cp'])[1 + g % 2], 'uuid:r' || (1 + g % 2), 'r' || (1 + g % 2), false, (array['modified','removed'])[1 + g % 2], "
                    + "timestamptz '2026-10-10T00:00:00Z' + g * interval '1 minute', 'Actor ' || (g % 4), "
                    + "jsonb_build_array(jsonb_build_object('field', (array['action','source'])[1 + g % 2], 'before', 'a', 'after', 'b')) from generate_series(1, 700) g");
                sql.execute("analyze policy_rule_history");
                try (var marked = sql.executeQuery("select count(*) from policy_rule_history where not initial_capture and change_type = 'added' and rule_key in ('uuid:n1','uuid:n2','uuid:d1','uuid:x1')")) {
                    assertTrue(marked.next()); assertTrue(marked.getLong(1) >= 4, "the fixture holds first-appearance rows that carry no stored marker");
                }
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                long ceiling = repository.ceiling();
                int compared = 0;
                for (boolean includeInitial : new boolean[] { false, true })
                for (String policy : new String[] { "", "p-pan", "p-cp", "missing" })
                for (String device : new String[] { "", "d1", "missing" })
                for (String changeType : new String[] { "", "added", "modified", "removed" })
                for (String field : new String[] { "", "name", "section", "action" }) {
                    var filter = new PolicyChangeRepository.Filter(from, to, policy, device, changeType, field, includeInitial);
                    var expected = legacyRows(db, filter, ceiling, null);
                    assertEquals(expected, repository.rows(filter, ceiling, null), filter.toString());
                    assertEquals(legacySummary(db, filter, ceiling), repository.summary(filter, ceiling), filter.toString());
                    for (int index : new int[] { 2, 450 }) if (expected.size() > index + 1) {
                        var at = expected.get(index);
                        var position = new PolicyChangeRepository.Position(Instant.parse(at.time()), at.revision());
                        assertEquals(legacyRows(db, filter, ceiling, position), repository.rows(filter, ceiling, position), "after " + filter);
                    }
                    compared++;
                }
                assertEquals(2 * 4 * 3 * 4 * 4, compared);
                // A window that ends before the history, and one that holds only initial captures.
                for (var window : List.of(new Instant[] { Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z") },
                                          new Instant[] { Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-03T01:00:00Z") })) {
                    for (boolean includeInitial : new boolean[] { false, true }) {
                        var filter = new PolicyChangeRepository.Filter(window[0], window[1], "", "", "", "", includeInitial);
                        assertEquals(legacyRows(db, filter, ceiling, null), repository.rows(filter, ceiling, null));
                        assertEquals(legacySummary(db, filter, ceiling), repository.summary(filter, ceiling));
                    }
                }
                // The default view must really leave the bulk out, so the comparison above exercised the interesting shape.
                var real = new PolicyChangeRepository.Filter(from, to, "", "", "", "", false);
                assertTrue(((Number) repository.summary(real, ceiling).get("initialCaptures")).longValue() >= 3000);
                assertTrue(repository.rows(real, ceiling, null).stream().noneMatch(PolicyChangeRepository.Row::initialCapture));
                assertEquals(500, repository.rows(real, ceiling, null).size());
                var all = new PolicyChangeRepository.Filter(from, to, "p-pan", "", "added", "section", true);
                var flagged = repository.rows(all, ceiling, null).stream().filter(r -> r.ruleKey().matches("uuid:(n1|n2|d1)")).toList();
                assertFalse(flagged.isEmpty()); assertTrue(flagged.stream().allMatch(PolicyChangeRepository.Row::initialCapture), "first appearances are flagged without a stored marker");
            }
        }
    }
    @Test void indexMigrationIsIdempotentAndRollbackSafe() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_rollback")) {
            fixture.runFlyway();
            Path path = Path.of("../service/src/main/resources/db/migration/V145__policy_change_report_indexes.sql");
            String migration = Files.readString(path);
            try (var db = fixture.migrateConnection(); var sql = db.createStatement()) {
                db.setAutoCommit(false);
                sql.execute("drop index policy_changes_time");
                sql.execute(migration); sql.execute(migration); db.rollback();
                try (var rows = sql.executeQuery("select to_regclass('policy_changes_time') is not null")) {
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                }
                db.rollback();
            }
        }
    }
}
