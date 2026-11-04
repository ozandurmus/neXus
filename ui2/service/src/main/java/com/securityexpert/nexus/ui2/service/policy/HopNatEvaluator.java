package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Binding;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Device;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Placement;
import com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.Tuple;
import java.util.*;

/**
 * Path analysis stage A2: NAT first match over the stored NAT rulebase of one device's snapshot (design §5, A0 §1a, §4b,
 * §4c). Address and service cells are resolved with {@link PolicyNormalization.Resolver} -- the same resolver as access
 * evaluation -- and an earlier rule that cannot be resolved and might match makes the result UNKNOWN (fail closed).
 *
 * <p><b>Check Point</b> (section source {@code CP NAT rulebase}): rules in collected position order, manual and automatic
 * as collected (no manual-first assumption); rule numbers must increase or the order is unproven. The first matching
 * manual rule decides. A first matching automatic rule may be completed by the first later automatic rule that translates
 * the other side (source vs destination, A0 §1a); a later matching manual rule, or a rule of unknown kind, is the
 * undocumented manual/automatic combination ({@link #CP_NAT_MANUAL_AUTOMATIC_COMBINATION_UNKNOWN}). Install-on uses the
 * same binding proof as access ({@link HopPolicyEvaluator#installOn}). Only {@code static} and {@code hide} methods with a
 * single translated address (and single translated port) are expressed; the vendor's {@code Original} entry
 * (objects-dictionary name {@code Original}, type {@code Global}) is "not translated" -- an ASSUMPTION mirroring the A0
 * §3b reading of {@code Policy Targets}, to be corroborated in A6.
 *
 * <p><b>Palo Alto</b>: sections {@code Pre NAT} -> {@code Local NAT} -> {@code Post NAT}, list order inside each, honouring
 * {@code target}/{@code negate} like security ({@link HopPolicyEvaluator#panTarget}). Zones: {@code from} = ingress zone,
 * {@code to} = provisional egress zone (route lookup on the original destination). Single translated source/destination
 * address and translated port only; dynamic destination translation, floating interface addresses, NAT64/NPTv6 and
 * active/active device binding are UNKNOWN. A bi-directional static rule whose implicit reverse rule could match is UNKNOWN.
 * A Panorama snapshot carries no firewall-local NAT rules: a decision past the pre-rules (or no match) has the caveat
 * {@link HopPolicyEvaluator#PAN_LOCAL_RULES_NOT_COLLECTED}.
 */
public final class HopNatEvaluator {
    private HopNatEvaluator() {}

    public static final String TRANSLATED = "TRANSLATED", NO_TRANSLATION = "NO_TRANSLATION", UNKNOWN = "UNKNOWN";
    public static final String NAT_FIRST_MATCH = "NAT_FIRST_MATCH";
    public static final String NAT_AUTOMATIC_PAIR = "NAT_AUTOMATIC_PAIR";
    public static final String NAT_NO_RULE_MATCHED = "NAT_NO_RULE_MATCHED";
    public static final String NAT_NOT_COLLECTED = "NAT_NOT_COLLECTED";
    public static final String NAT_COLLECTION_INCOMPLETE = "NAT_COLLECTION_INCOMPLETE";
    public static final String NAT_ORDER_UNPROVEN = "NAT_ORDER_UNPROVEN";
    public static final String NAT_EARLIER_RULE_UNRESOLVED = "NAT_EARLIER_RULE_UNRESOLVED";
    public static final String NAT_TRANSLATION_NOT_EXPRESSIBLE = "NAT_TRANSLATION_NOT_EXPRESSIBLE";
    public static final String CP_NAT_MANUAL_AUTOMATIC_COMBINATION_UNKNOWN = "CP_NAT_MANUAL_AUTOMATIC_COMBINATION_UNKNOWN";
    public static final String NAT_ANALYSIS_BUDGET = "NAT_ANALYSIS_BUDGET";
    public static final String NAT_UNSUPPORTED_VENDOR = "NAT_UNSUPPORTED_VENDOR";
    // Per-rule reasons of undecidable NAT rules.
    public static final String NAT_RULE_SELECTOR_UNRESOLVED = "NAT_RULE_SELECTOR_UNRESOLVED";
    public static final String NAT_RULE_ENABLED_UNKNOWN = "NAT_RULE_ENABLED_UNKNOWN";
    public static final String NAT_RULE_ZONE_UNKNOWN = "NAT_RULE_ZONE_UNKNOWN";
    public static final String NAT_RULE_INTERFACE_UNKNOWN = "NAT_RULE_INTERFACE_UNKNOWN";
    public static final String NAT_RULE_TYPE_UNSUPPORTED = "NAT_RULE_TYPE_UNSUPPORTED";
    public static final String PAN_NAT_BIDIRECTIONAL_REVERSE = "PAN_NAT_BIDIRECTIONAL_REVERSE";
    // Notes.
    public static final String CP_HIDE_NAT_SOURCE_PORT_NOT_MODELED = "CP_HIDE_NAT_SOURCE_PORT_NOT_MODELED";

    /**
     * The NAT outcome for one tuple. {@code translated} is the tuple after translation (the original tuple for
     * NO_TRANSLATION, {@code null} for UNKNOWN). {@code rules} are the deciding rule(s): one, or an automatic pair.
     */
    public record Nat(String status, String reason, Tuple original, Tuple translated, List<Map<String, Object>> rules,
            List<Map<String, Object>> undecidableRules, List<String> caveats, List<String> notes) {
        public Nat {
            rules = List.copyOf(rules); undecidableRules = List.copyOf(undecidableRules);
            caveats = List.copyOf(new LinkedHashSet<>(caveats)); notes = List.copyOf(new LinkedHashSet<>(notes));
        }
        public boolean sourceTranslated() { return translated != null && !translated.source().equals(original.source()); }
        public boolean destinationAddressTranslated() { return translated != null && !translated.destination().equals(original.destination()); }
        public boolean portTranslated() { return translated != null && translated.destinationPort() != original.destinationPort(); }
        /** A destination translation in the sense of design §5: address or port. */
        public boolean destinationTranslated() { return destinationAddressTranslated() || portTranslated(); }
    }

    static Nat evaluate(PolicySnapshot snapshot, Device device, Binding binding, Tuple tuple, String ingressZone,
            String provisionalEgressZone, String egressInterface, int workLimit) {
        String vendor = snapshot.metadata().vendor();
        var budget = new Budget(workLimit);
        try {
            if (vendor.equals("CP")) return checkPoint(snapshot, device, binding, tuple, budget);
            if (vendor.equals("PAN")) return paloAlto(snapshot, device, tuple, ingressZone, provisionalEgressZone, egressInterface, budget);
            return unknown(tuple, NAT_UNSUPPORTED_VENDOR, List.of());
        } catch (Unknown exhausted) {
            return unknown(tuple, NAT_ANALYSIS_BUDGET, List.of());
        }
    }

    private static Nat unknown(Tuple tuple, String reason, List<Map<String, Object>> undecidable) {
        return new Nat(UNKNOWN, reason, tuple, null, List.of(), undecidable, List.of(), List.of());
    }

    // ---- Check Point ---------------------------------------------------------------------------------------------------

    private record Candidate(Section section, Rule rule) {}
    /** Matched (true), not matched (false) or undecidable (a reason). */
    private record Outcome(boolean matched, String undecidable) {
        static final Outcome MATCH = new Outcome(true, null), NO_MATCH = new Outcome(false, null);
        static Outcome unknown(String reason) { return new Outcome(false, reason); }
    }
    /** A rule's translation: the new tuple, or {@code null} when the stored rule cannot express it. */
    private record Translation(Tuple tuple, boolean source, boolean destination) {}

    private static Nat checkPoint(PolicySnapshot snapshot, Device device, Binding binding, Tuple tuple, Budget budget) {
        if (snapshot.failures().stream().anyMatch(f -> "NAT".equals(f.layerName())))
            return unknown(tuple, NAT_COLLECTION_INCOMPLETE, List.of());
        if (!HopPolicyEvaluator.packageTarget(snapshot, device)) return unknown(tuple, HopPolicyEvaluator.TARGET_CONTEXT_UNPROVEN, List.of());
        List<Candidate> rules = new ArrayList<>();
        boolean collected = false;
        int last = 0;
        for (Section section : snapshot.sections()) {
            if (!"CP NAT rulebase".equals(section.source())) continue;
            collected = true;
            for (Rule rule : section.rules()) {
                budget.spend();
                if (rule.number() <= last) return unknown(tuple, NAT_ORDER_UNPROVEN, List.of());
                last = rule.number();
                rules.add(new Candidate(section, rule));
            }
        }
        if (!collected) return unknown(tuple, NAT_NOT_COLLECTED, List.of());
        var resolver = new Resolver(snapshot.objects(), budget);
        List<Map<String, Object>> undecidable = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            var first = rules.get(i);
            Outcome outcome = cpMatch(snapshot, device, binding, first.rule(), tuple, resolver);
            if (outcome.undecidable() != null) { undecidable.add(row(first, outcome.undecidable())); continue; }
            if (!outcome.matched()) continue;
            if (!undecidable.isEmpty()) return unknown(tuple, NAT_EARLIER_RULE_UNRESOLVED, undecidable);
            Translation translation = cpTranslation(snapshot, first.rule(), tuple, resolver);
            if (translation == null) return new Nat(UNKNOWN, NAT_TRANSLATION_NOT_EXPRESSIBLE, tuple, null, List.of(rule(first)), List.of(), List.of(), List.of());
            List<String> notes = new ArrayList<>();
            if ("hide".equals(first.rule().action())) notes.add(CP_HIDE_NAT_SOURCE_PORT_NOT_MODELED);
            String kind = autoGenerated(first.rule());
            if (kind.equals("false") || translation.source() && translation.destination())
                return decided(tuple, translation.tuple(), NAT_FIRST_MATCH, List.of(rule(first)), notes);
            // An automatic first match (or one of unknown kind): a later automatic rule may translate the other side.
            for (int j = i + 1; j < rules.size(); j++) {
                var later = rules.get(j);
                Outcome next = cpMatch(snapshot, device, binding, later.rule(), tuple, resolver);
                if (next.undecidable() != null)
                    return new Nat(UNKNOWN, NAT_EARLIER_RULE_UNRESOLVED, tuple, null, List.of(rule(first)), List.of(row(later, next.undecidable())), List.of(), notes);
                if (!next.matched()) continue;
                if (!kind.equals("true") || !autoGenerated(later.rule()).equals("true"))
                    return new Nat(UNKNOWN, CP_NAT_MANUAL_AUTOMATIC_COMBINATION_UNKNOWN, tuple, null, List.of(rule(first), rule(later)), List.of(), List.of(), notes);
                Translation other = cpTranslation(snapshot, later.rule(), tuple, resolver);
                if (other == null) return new Nat(UNKNOWN, NAT_TRANSLATION_NOT_EXPRESSIBLE, tuple, null, List.of(rule(first), rule(later)), List.of(), List.of(), notes);
                boolean complementary = translation.source() && other.destination() && !other.source()
                    || translation.destination() && other.source() && !other.destination();
                if (!complementary) continue; // A second rule for the side already translated is not enforced.
                if ("hide".equals(later.rule().action())) notes.add(CP_HIDE_NAT_SOURCE_PORT_NOT_MODELED);
                Tuple combined = translation.source()
                    ? tuple.with(translation.tuple().source(), other.tuple().destination(), other.tuple().destinationPort())
                    : tuple.with(other.tuple().source(), translation.tuple().destination(), translation.tuple().destinationPort());
                return decided(tuple, combined, NAT_AUTOMATIC_PAIR, List.of(rule(first), rule(later)), notes);
            }
            return decided(tuple, translation.tuple(), NAT_FIRST_MATCH, List.of(rule(first)), notes);
        }
        if (!undecidable.isEmpty()) return unknown(tuple, NAT_EARLIER_RULE_UNRESOLVED, undecidable);
        return new Nat(NO_TRANSLATION, NAT_NO_RULE_MATCHED, tuple, tuple, List.of(), List.of(), List.of(), List.of());
    }

    private static Nat decided(Tuple original, Tuple translated, String reason, List<Map<String, Object>> rules, List<String> notes) {
        boolean same = translated.equals(original);
        return new Nat(same ? NO_TRANSLATION : TRANSLATED, reason, original, translated, rules, List.of(), List.of(), notes);
    }

    private static String autoGenerated(Rule rule) {
        var values = rule.extras().get("auto-generated");
        return values != null && values.size() == 1 && Set.of("true", "false").contains(values.get(0)) ? values.get(0) : "unknown";
    }

    private static Outcome cpMatch(PolicySnapshot snapshot, Device device, Binding binding, Rule rule, Tuple tuple, Resolver resolver) {
        if (Boolean.FALSE.equals(rule.enabled())) return Outcome.NO_MATCH;
        Outcome cells = cells(rule, tuple, resolver);
        if (cells != Outcome.MATCH) return cells;
        Placement placement = HopPolicyEvaluator.installOn(snapshot, rule.extras().get("install-on"), device, binding);
        if (placement == Placement.SKIP) return Outcome.NO_MATCH;
        if (placement == Placement.UNKNOWN) return Outcome.unknown(HopPolicyEvaluator.INSTALL_ON_UNPROVEN);
        if (rule.enabled() == null) return Outcome.unknown(NAT_RULE_ENABLED_UNKNOWN);
        return Outcome.MATCH;
    }

    /** Original source, destination and service cells against the tuple (both vendors). */
    private static Outcome cells(Rule rule, Tuple tuple, Resolver resolver) {
        try {
            Selector source = resolver.cell(rule.source(), "address"), destination = resolver.cell(rule.destination(), "address");
            Selector service = resolver.cell(rule.service(), "service");
            if (!point(source, "ipv4", tuple.sourceValue(), resolver.budget) || !point(destination, "ipv4", tuple.destinationValue(), resolver.budget))
                return Outcome.NO_MATCH;
            if (service.any()) return Outcome.MATCH;
            if (tuple.service() == null) return Outcome.NO_MATCH; // resolved services are TCP/UDP port ranges only
            return point(service, tuple.protocol(), tuple.destinationPort(), resolver.budget) ? Outcome.MATCH : Outcome.NO_MATCH;
        } catch (Unknown unresolved) {
            if (unresolved.getMessage().equals("analysis budget")) throw unresolved;
            return Outcome.unknown(NAT_RULE_SELECTOR_UNRESOLVED);
        }
    }

    private static boolean point(Selector selector, String family, long value, Budget budget) {
        return contains(selector, new Selector(false, Map.of(family, List.of(new Range(value, value)))), budget);
    }

    private static Translation cpTranslation(PolicySnapshot snapshot, Rule rule, Tuple tuple, Resolver resolver) {
        if (!Set.of("static", "hide").contains(rule.action())) return null;
        String source = cpAddress(snapshot, rule.extras().get("translated-source"), tuple.source(), resolver);
        String destination = cpAddress(snapshot, rule.extras().get("translated-destination"), tuple.destination(), resolver);
        Integer port = cpPort(snapshot, rule.extras().get("translated-service"), tuple, resolver);
        if (source == null || destination == null || port == null) return null;
        return new Translation(tuple.with(source, destination, port), !source.equals(tuple.source()),
            !destination.equals(tuple.destination()) || port != tuple.destinationPort());
    }

    /** The vendor's "not translated" entry (see class comment: ASSUMPTION until A6). */
    static boolean original(PolicyObject object) {
        return object != null && "Original".equals(object.name()) && object.values().contains("native-type: Global");
    }

    private static String cpAddress(PolicySnapshot snapshot, List<String> refs, String unchanged, Resolver resolver) {
        if (refs == null || refs.size() != 1) return null;
        if (original(snapshot.objects().get(refs.get(0)))) return unchanged;
        return single(refs, resolver);
    }

    private static Integer cpPort(PolicySnapshot snapshot, List<String> refs, Tuple tuple, Resolver resolver) {
        if (refs == null || refs.size() != 1) return null;
        if (original(snapshot.objects().get(refs.get(0)))) return tuple.destinationPort();
        try {
            Selector service = resolver.cell(new Cell(refs, false), "service");
            var ranges = service.ranges();
            if (service.any() || tuple.service() == null || ranges.size() != 1 || !ranges.containsKey(tuple.protocol())) return null;
            var list = ranges.get(tuple.protocol());
            return list.size() == 1 && list.get(0).start() == list.get(0).end() ? (int) list.get(0).start() : null;
        } catch (Unknown unresolved) {
            if (unresolved.getMessage().equals("analysis budget")) throw unresolved;
            return null;
        }
    }

    /** Exactly one resolved IPv4 address, or {@code null}. */
    private static String single(List<String> refs, Resolver resolver) {
        try {
            Selector selector = resolver.cell(new Cell(refs, false), "address");
            var ranges = selector.ranges().getOrDefault("ipv4", List.of());
            if (selector.any() || ranges.size() != 1 || ranges.get(0).start() != ranges.get(0).end()) return null;
            return ip(ranges.get(0).start());
        } catch (Unknown unresolved) {
            if (unresolved.getMessage().equals("analysis budget")) throw unresolved;
            return null;
        }
    }

    // ---- Palo Alto -----------------------------------------------------------------------------------------------------

    private static final List<String> PAN_NAT_PHASES = List.of(PAN_PRE_NAT, PAN_LOCAL_NAT, PAN_POST_NAT);

    private static Nat paloAlto(PolicySnapshot snapshot, Device device, Tuple tuple, String ingressZone, String egressZone,
            String egressInterface, Budget budget) {
        if (!snapshot.failures().isEmpty()) return unknown(tuple, NAT_COLLECTION_INCOMPLETE, List.of());
        if (!HopPolicyEvaluator.packageTarget(snapshot, device)) return unknown(tuple, HopPolicyEvaluator.TARGET_CONTEXT_UNPROVEN, List.of());
        boolean local = snapshot.sections().stream().anyMatch(s -> PAN_LOCAL_RULES.equals(s.name()));
        List<Candidate> rules = new ArrayList<>();
        for (String phase : PAN_NAT_PHASES)
            for (Section section : snapshot.sections()) {
                if (!phase.equals(section.name())) continue;
                for (Rule rule : section.rules()) { budget.spend(); rules.add(new Candidate(section, rule)); }
            }
        var resolver = new Resolver(snapshot.objects(), budget);
        List<Map<String, Object>> undecidable = new ArrayList<>();
        for (var candidate : rules) {
            Outcome outcome = panMatch(candidate.rule(), device, tuple, ingressZone, egressZone, egressInterface, resolver);
            if (outcome.undecidable() != null) { undecidable.add(row(candidate, outcome.undecidable())); continue; }
            if (!outcome.matched()) continue;
            if (!undecidable.isEmpty()) return unknown(tuple, NAT_EARLIER_RULE_UNRESOLVED, undecidable);
            List<String> caveats = !local && PAN_POST_NAT.equals(candidate.section().name())
                ? List.of(HopPolicyEvaluator.PAN_LOCAL_RULES_NOT_COLLECTED) : List.of();
            Tuple translated = panTranslation(candidate.rule(), tuple, resolver);
            if (translated == null) return new Nat(UNKNOWN, NAT_TRANSLATION_NOT_EXPRESSIBLE, tuple, null, List.of(rule(candidate)), List.of(), caveats, List.of());
            boolean same = translated.equals(tuple);
            return new Nat(same ? NO_TRANSLATION : TRANSLATED, NAT_FIRST_MATCH, tuple, translated, List.of(rule(candidate)), List.of(), caveats, List.of());
        }
        if (!undecidable.isEmpty()) return unknown(tuple, NAT_EARLIER_RULE_UNRESOLVED, undecidable);
        return new Nat(NO_TRANSLATION, NAT_NO_RULE_MATCHED, tuple, tuple, List.of(), List.of(),
            local ? List.of() : List.of(HopPolicyEvaluator.PAN_LOCAL_RULES_NOT_COLLECTED), List.of());
    }

    private static Outcome panMatch(Rule rule, Device device, Tuple tuple, String ingressZone, String egressZone,
            String egressInterface, Resolver resolver) {
        if (Boolean.FALSE.equals(rule.enabled())) return Outcome.NO_MATCH;
        var extras = rule.extras();
        Placement placement = HopPolicyEvaluator.panTarget(extras, device);
        if (placement == Placement.SKIP) return Outcome.NO_MATCH;
        // Any decided non-match on one dimension is a non-match (subject to the implicit reverse rule); otherwise the
        // first undecidable dimension is the rule's reason.
        List<Outcome> dimensions = List.of(zone(extras.get("from"), ingressZone), zone(extras.get("to"), egressZone),
            egressInterface(extras.get("to-interface"), egressInterface), cells(rule, tuple, resolver));
        if (dimensions.contains(Outcome.NO_MATCH)) return reverse(rule, tuple, resolver);
        for (Outcome dimension : dimensions) if (dimension != Outcome.MATCH) return dimension;
        if (placement == Placement.UNKNOWN) return Outcome.unknown(HopPolicyEvaluator.PAN_TARGET_UNPROVEN);
        if (!extras.getOrDefault("nat-type", List.of("ipv4")).equals(List.of("ipv4")) || extras.containsKey("active-active-device-binding"))
            return Outcome.unknown(NAT_RULE_TYPE_UNSUPPORTED);
        if (rule.enabled() == null) return Outcome.unknown(NAT_RULE_ENABLED_UNKNOWN);
        return Outcome.MATCH;
    }

    private static Outcome egressInterface(List<String> configured, String chosen) {
        if (configured == null || configured.equals(List.of("any"))) return Outcome.MATCH;
        if (configured.size() != 1 || chosen == null || chosen.isEmpty()) return Outcome.unknown(NAT_RULE_INTERFACE_UNKNOWN);
        return configured.get(0).equals(chosen) ? Outcome.MATCH : Outcome.NO_MATCH;
    }

    private static Outcome zone(List<String> zones, String chosen) {
        if (zones == null || zones.isEmpty() || chosen == null || chosen.isEmpty()) return Outcome.unknown(NAT_RULE_ZONE_UNKNOWN);
        return zones.contains("any") || zones.contains(chosen) ? Outcome.MATCH : Outcome.NO_MATCH;
    }

    /**
     * A forward non-match of a bi-directional static rule is still undecidable when the destination could be one of its
     * translated source addresses: the implicit reverse rule is not modelled.
     */
    private static Outcome reverse(Rule rule, Tuple tuple, Resolver resolver) {
        var extras = rule.extras();
        if (!List.of("static-ip").equals(extras.get("source-translation-type"))
                || !List.of("yes").equals(extras.get("source-translation-bi-directional"))) return Outcome.NO_MATCH;
        try {
            Selector translated = resolver.cell(new Cell(extras.getOrDefault("translated-source", List.of()), false), "address");
            return point(translated, "ipv4", tuple.destinationValue(), resolver.budget) ? Outcome.unknown(PAN_NAT_BIDIRECTIONAL_REVERSE) : Outcome.NO_MATCH;
        } catch (Unknown unresolved) {
            if (unresolved.getMessage().equals("analysis budget")) throw unresolved;
            return Outcome.unknown(PAN_NAT_BIDIRECTIONAL_REVERSE);
        }
    }

    private static Tuple panTranslation(Rule rule, Tuple tuple, Resolver resolver) {
        var extras = rule.extras();
        if (rule.action().equals("no-nat")) return tuple;
        String source = tuple.source(), destination = tuple.destination();
        int port = tuple.destinationPort();
        var sourceType = extras.get("source-translation-type");
        if (sourceType != null) {
            if (sourceType.size() != 1 || !Set.of("static-ip", "dynamic-ip-and-port", "dynamic-ip").contains(sourceType.get(0))) return null;
            if (extras.containsKey("translated-source")) source = single(extras.get("translated-source"), resolver);
            else if (sourceType.get(0).equals("dynamic-ip-and-port") && !extras.containsKey("source-translation-interface-floating-ip"))
                source = literal(extras.get("source-translation-interface-ip"));
            else return null;
            if (source == null) return null;
        }
        if (extras.containsKey("translated-destination-dynamic") || extras.containsKey("destination-dynamic-distribution")
                || extras.containsKey("translated-destination-dynamic-port")) return null;
        if (rule.action().endsWith("destination-nat")) {
            destination = single(extras.getOrDefault("translated-destination", List.of()), resolver);
            if (destination == null) return null;
            var translatedPort = extras.get("translated-destination-port");
            if (translatedPort != null) {
                if (translatedPort.size() != 1 || !translatedPort.get(0).matches("[1-9][0-9]{0,4}")) return null;
                port = Integer.parseInt(translatedPort.get(0));
                if (port > 65535) return null;
            }
        }
        return tuple.with(source, destination, port);
    }

    /** A literal interface IPv4 address ({@code a.b.c.d} or {@code a.b.c.d/nn}); an object name is not resolved here. */
    private static String literal(List<String> values) {
        if (values == null || values.size() != 1) return null;
        String value = values.get(0);
        if (!value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:/[0-9]{1,2})?")) return null;
        String address = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
        try { PolicyRuleQuery.interval(address); return address; }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static Map<String, Object> rule(Candidate candidate) { return HopPolicyEvaluator.ruleView(candidate.section(), candidate.rule()); }
    private static Map<String, Object> row(Candidate candidate, String reason) {
        var view = new LinkedHashMap<>(rule(candidate)); view.put("pathReason", reason); return view;
    }
    private static String ip(long value) { return (value >>> 24) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255); }
}
