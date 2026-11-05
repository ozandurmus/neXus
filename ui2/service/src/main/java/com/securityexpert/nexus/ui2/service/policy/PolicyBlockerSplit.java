package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Diagnostic split of the "earlier rule has unsupported constraints" blockers (plan POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05, X0).
 * <p>Counters only. Each distinct blocking rule is attributed once to the first thing its {@link PolicyNormalization#normalize}
 * fails: the problem text (the class), the failing cell, the stored status, type and (Check Point) native-type of the failing
 * object, and whether the blocker is a partial prior. A row carries fixed vocabulary and numbers, never a rule name, object name,
 * address, value or id; every free-form part is restricted to a closed pattern and otherwise reads {@code UNKNOWN}.
 * <p>Read-only: it re-derives from the snapshot with its own resolver and budget and changes no verdict, finding or evidence.
 * It does not run {@code normalize} per blocked rule, only once per distinct blocker. The one deliberate difference from
 * {@code normalize} is the order of the extra fields: the stored map's iteration order is unspecified (Map.copyOf), so the
 * attribution walks them in sorted key order and the split is deterministic.
 */
final class PolicyBlockerSplit {
    private PolicyBlockerSplit() {}
    static final String UNKNOWN = "UNKNOWN", NONE = "NONE", MISSING = "MISSING";
    /** Fixed cell tokens; an extra field is {@code extra:<key>} with a key matching {@link #KEY}. */
    static final Set<String> CELLS = Set.of("source", "destination", "service", "application", "zone", "install-on", "enabled", "action", "time", "rulebase", UNKNOWN);
    static final Set<String> STATUSES = Set.of("RESOLVED", "UNRESOLVED", "UNKNOWN", "DYNAMIC", "UNSUPPORTED", "CYCLE", "LIMIT", MISSING, NONE);
    static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,39}"), KEY = Pattern.compile("[a-z0-9/_-]{1,60}");
    private static final Set<String> EXTRA_PROBLEMS = Set.of("time or content constraint", "user or category constraint", "zone rule type constraint",
        "unresolved install-on selector", "unsupported rule constraint");

    record Key(String cls, String cell, String status, String type, String nativeType, Object partialPrior) {}
    private record Diagnosis(String cls, String cell, PolicyObject object, boolean missing) {}

    /** {@code blocked}: blocking rule id to the number of later rules it blocks. Rows sorted by blockedRules desc, then keys. */
    static List<Map<String, Object>> split(PolicySnapshot snapshot, Map<String, int[]> blocked) {
        Map<String, Object[]> located = new HashMap<>();
        for (var section : snapshot.sections()) for (var rule : section.rules()) if (blocked.containsKey(rule.id())) located.put(rule.id(), new Object[]{section, rule});
        var budget = new Budget(PolicyHygiene.TOTAL_WORK);
        var resolver = Resolver.shadowing(snapshot, budget);
        Map<Key, long[]> rows = new HashMap<>();
        for (String id : new TreeSet<>(blocked.keySet())) {
            Object[] at = located.get(id);
            Key key = at == null ? new Key(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)
                : attribute(snapshot, (Section) at[0], (Rule) at[1], resolver, budget);
            long[] counts = rows.computeIfAbsent(key, k -> new long[2]);
            counts[0]++; counts[1] += blocked.get(id)[0];
        }
        return rows.entrySet().stream().sorted(Comparator.<Map.Entry<Key, long[]>>comparingLong(e -> -e.getValue()[1])
                .thenComparing(e -> e.getKey().cls()).thenComparing(e -> e.getKey().cell()).thenComparing(e -> e.getKey().status())
                .thenComparing(e -> e.getKey().type()).thenComparing(e -> e.getKey().nativeType()).thenComparing(e -> String.valueOf(e.getKey().partialPrior())))
            .map(e -> {
                var k = e.getKey(); Map<String, Object> row = new LinkedHashMap<>();
                row.put("class", k.cls()); row.put("cell", k.cell()); row.put("status", k.status()); row.put("type", k.type());
                row.put("nativeType", k.nativeType()); row.put("partialPrior", k.partialPrior());
                row.put("blockers", e.getValue()[0]); row.put("blockedRules", e.getValue()[1]);
                return row;
            }).toList();
    }

    private static Key attribute(PolicySnapshot snapshot, Section section, Rule rule, Resolver resolver, Budget budget) {
        budget.rule(PolicyHygiene.RULE_WORK);
        boolean cp = snapshot.metadata().vendor().equals("CP");
        try {
            Match match = PolicyNormalization.normalize(snapshot, section, rule, resolver);
            Diagnosis d = diagnose(snapshot, rule, match.problem(), resolver, cp);
            Object partial;
            try { partial = PolicyNormalization.partial(snapshot, section, rule, resolver) != null; }
            catch (Unknown limit) { partial = UNKNOWN; }
            return key(d, partial, cp);
        } catch (Unknown limit) {
            return new Key("analysis budget", UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }
    }

    private static Key key(Diagnosis d, Object partial, boolean cp) {
        String cls = d.cls() != null && PolicyHygiene.REASONS.contains(d.cls()) ? d.cls() : UNKNOWN;
        String cell = d.cell() == null ? UNKNOWN : CELLS.contains(d.cell()) ? d.cell()
            : d.cell().startsWith("extra:") ? "extra:" + (KEY.matcher(d.cell().substring(6)).matches() ? d.cell().substring(6) : UNKNOWN) : UNKNOWN;
        String status = NONE, type = NONE, nativeType = NONE;
        if (d.missing()) { status = MISSING; type = UNKNOWN; if (cp) nativeType = UNKNOWN; }
        else if (d.object() != null) {
            var o = d.object();
            status = o.status() != null && STATUSES.contains(o.status()) ? o.status() : UNKNOWN;
            type = o.type() != null && TYPE.matcher(o.type()).matches() ? o.type() : UNKNOWN;
            if (cp) nativeType = nativeType(o);
        }
        return new Key(cls, cell, status, type, nativeType, partial);
    }

    private static String nativeType(PolicyObject o) {
        String found = null;
        for (String value : o.values()) if (value.startsWith("native-type: ")) {
            if (found != null) return UNKNOWN;
            found = value.substring("native-type: ".length());
        }
        return found != null && TYPE.matcher(found).matches() ? found : UNKNOWN;
    }

    /** The first thing {@code normalize} fails, in its own stage order (cells, zone/install-on, enabled, action, extras), extras sorted. */
    private static Diagnosis diagnose(PolicySnapshot snapshot, Rule rule, String problem, Resolver resolver, boolean cp) {
        if (problem == null) return new Diagnosis(null, UNKNOWN, null, false);
        if (problem.equals("unsupported rulebase")) return new Diagnosis(problem, "rulebase", null, false);
        // Cells first, exactly as normalize resolves them.
        var cells = List.of(rule.source(), rule.destination(), rule.service());
        var names = List.of("source", "destination", "service");
        Selector service = null;
        for (int i = 0; i < 3; i++) {
            try { var s = resolver.cell(cells.get(i), i == 2 ? "service" : "address"); if (i == 2) service = s; }
            catch (Unknown failed) { return cellFailure(failed, names.get(i), cells.get(i), i == 2 ? "service" : "address", false, resolver); }
        }
        var application = rule.application();
        if (!(cp && application.refs().isEmpty() && !application.negated())) {
            boolean defaultService = service != null && service.applicationDefault();
            try { if (defaultService) resolver.applications(application); else resolver.cell(application, "application"); }
            catch (Unknown failed) { return cellFailure(failed, "application", application, "application", defaultService, resolver); }
        }
        switch (problem) {
            case "zone evidence not collected": return new Diagnosis(problem, "zone", null, false);
            case "install-on evidence not collected": return new Diagnosis(problem, "install-on", null, false);
            case "enabled state is unknown": return new Diagnosis(problem, "enabled", null, false);
            case "non-terminal or unknown action": return new Diagnosis(problem, "action", null, false);
            case "time evidence not collected": return new Diagnosis(problem, "time", null, false);
            default: break;
        }
        if (EXTRA_PROBLEMS.contains(problem)) return extras(snapshot, rule, problem, cp);
        return new Diagnosis(problem, UNKNOWN, null, false);
    }

    private static Diagnosis cellFailure(Unknown failed, String cell, Cell value, String family, boolean applications, Resolver resolver) {
        if (failed.getMessage().equals("analysis budget")) throw failed;
        for (String id : value.refs()) {
            var culprit = find(id, family, applications, resolver, 0);
            if (culprit != null) return new Diagnosis(failed.getMessage(), cell, culprit.object(), culprit.object() == null);
        }
        return new Diagnosis(failed.getMessage(), cell, null, false);
    }

    private record Culprit(PolicyObject object) {}

    /** The leaf object that makes this reference fail: itself, or the first failing member of a resolved group; null when it resolves. */
    private static Culprit find(String id, String family, boolean applications, Resolver resolver, int depth) {
        var single = new Cell(List.of(id), false);
        try { if (applications) resolver.applications(single); else resolver.cell(single, family); return null; }
        catch (Unknown failed) { if (failed.getMessage().equals("analysis budget")) throw failed; }
        var o = resolver.objects.get(id);
        if (o == null) return new Culprit(null);
        if (depth < 33 && "RESOLVED".equals(o.status()) && Set.of("group", "address-group", "service-group", "application-group").contains(o.type()))
            for (String member : o.members()) { var inner = find(member, family, applications, resolver, depth + 1); if (inner != null) return inner; }
        return new Culprit(o);
    }

    /** The extras loop of normalize with the keys in sorted order. */
    private static Diagnosis extras(PolicySnapshot snapshot, Rule rule, String problem, boolean cp) {
        boolean applyLayer = cp && rule.action().equals("Apply Layer") && rule.extras().getOrDefault("inline-layer", List.of()).size() == 1;
        for (String key : new TreeSet<>(rule.extras().keySet())) {
            var values = rule.extras().get(key);
            String cell = "extra:" + key;
            if (applyLayer && key.equals("inline-layer")) continue;
            if (Set.of("time", "schedule", "vpn", "content").contains(key)) {
                for (String id : values) {
                    var o = snapshot.objects().get(id);
                    if (o == null || !o.status().equals("RESOLVED") || !o.type().equals("any")) return new Diagnosis("time or content constraint", cell, o, o == null);
                }
            } else if (Set.of("source-user", "category").contains(key) && !values.equals(List.of("any"))) return new Diagnosis("user or category constraint", cell, null, false);
            else if (key.equals("rule-type") && !values.equals(List.of("universal"))) return new Diagnosis("zone rule type constraint", cell, null, false);
            else if (key.equals("install-on")) {
                for (String id : values) {
                    var o = snapshot.objects().get(id);
                    if (o == null || !(o.status().equals("RESOLVED") || PolicyNormalization.OPAQUE_PLACEMENTS.contains(o.status())))
                        return new Diagnosis("unresolved install-on selector", cell, o, o == null);
                }
            } else if (!Set.of("from", "to", "install-on", "source-user", "category", "rule-type", "tag", "layer-name", "last-modified", "last-modifier", "log-setting").contains(key)
                    && !key.startsWith("track-") && !key.startsWith("profile-setting/") && !PolicyNormalization.untargeted(snapshot, rule, key))
                return new Diagnosis("unsupported rule constraint", cell, null, false);
        }
        // normalize failed on an extra that this walk does not reproduce: keep its class, say the field is unknown.
        return new Diagnosis(problem, "extra:" + UNKNOWN, null, false);
    }
}
