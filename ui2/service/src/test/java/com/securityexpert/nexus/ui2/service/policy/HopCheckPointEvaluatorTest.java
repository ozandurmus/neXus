package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository.TargetBinding;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.HopPolicyEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Golden tests, path analysis A2, Check Point: install-on binding proof (G7), NAT first match in collected position order
 * (A0 §1a), and the frozen §5 hop order (access on both the pre- and post-NAT tuple when a destination NAT matches).
 * Synthetic fixtures only: documentation addresses, masked names, the snapshot shape CheckPointPolicyMapper produces
 * (object ids = ref(policy, "object", uid)).
 */
class HopCheckPointEvaluatorTest {
    static final String POLICY = "policy-synthetic-01";
    static final Device TANGO = new Device("device-tango", "", null);
    static final Device OTHER = new Device("device-other", "", null);
    static String obj(String uid) { return ref(POLICY, "object", uid); }
    static PolicyObject object(String uid, String name, String type, String... values) {
        return new PolicyObject(obj(uid), name, type, List.of(), List.of(values), "RESOLVED");
    }
    static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> objects = new HashMap<>();
        for (var o : List.of(object("any", "ANY", "any"),
                object("web", "OBJ-WEB-01", "address", "ipv4-address: 192.0.2.10"),
                object("public", "OBJ-PUBLIC-01", "address", "ipv4-address: 203.0.113.10"),
                object("hide", "OBJ-HIDE-01", "address", "ipv4-address: 203.0.113.1"),
                object("clients", "OBJ-CLIENTS-01", "address", "subnet4: 198.51.100.0", "mask-length4: 24"),
                object("https", "OBJ-HTTPS", "service", "protocol: tcp", "port: 443"),
                object("alt", "OBJ-HTTPS-ALT", "service", "protocol: tcp", "port: 8443"),
                object("gw-tango", "FW-TANGO-04", "gateway"),
                object("gw-other", "FW-JULIET-06", "gateway")))
            objects.put(o.id(), o);
        objects.put(obj("targets"), new PolicyObject(obj("targets"), "Policy Targets", "unresolved", List.of(), List.of("native-type: Global"), "UNSUPPORTED"));
        objects.put(obj("original"), new PolicyObject(obj("original"), "Original", "unresolved", List.of(), List.of("native-type: Global"), "UNSUPPORTED"));
        objects.put(obj("unresolved"), new PolicyObject(obj("unresolved"), "unresolved", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        return objects;
    }
    static Cell cell(String... uids) { return new Cell(Arrays.stream(uids).map(HopCheckPointEvaluatorTest::obj).toList(), false); }
    static Rule access(String id, int number, String source, String destination, String service, String action, String... installOn) {
        return new Rule(id, "uuid-" + id, number, "RULE-" + id, true, cell(source), cell(destination), cell(service), new Cell(List.of(), false),
            action, "Log", "", Map.of("install-on", Arrays.stream(installOn).map(HopCheckPointEvaluatorTest::obj).toList(),
                "time", List.of(obj("any")), "layer-name", List.of("LAYER-ALPHA")));
    }
    static Rule nat(String id, int number, boolean automatic, String method, String[] original, String[] translated, String... installOn) {
        Map<String, List<String>> extras = new LinkedHashMap<>();
        extras.put("translated-source", List.of(obj(translated[0])));
        extras.put("translated-destination", List.of(obj(translated[1])));
        extras.put("translated-service", List.of(obj(translated[2])));
        extras.put("install-on", Arrays.stream(installOn.length == 0 ? new String[]{"targets"} : installOn).map(HopCheckPointEvaluatorTest::obj).toList());
        extras.put("auto-generated", List.of(String.valueOf(automatic)));
        return new Rule(id, "uuid-" + id, number, "NAT-" + id, true, cell(original[0]), cell(original[1]), cell(original[2]),
            new Cell(List.of(), false), method, "UNKNOWN", "", extras);
    }
    static String[] of(String... values) { return values; }
    static PolicySnapshot snapshot(List<Rule> access, List<Rule> nat, List<Target> targets, List<CollectionFailure> failures) {
        var metadata = new Metadata(POLICY, "mds-1", "MGR-BRAVO-01", "CP", "domain-1", "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-11-04T08:00:00Z", "", targets);
        List<Section> sections = new ArrayList<>(List.of(new Section(ref(POLICY, "layer-uid", ""), "OBJ-SECTION-01", "CP access layer", null, access)));
        if (nat != null) sections.add(new Section(ref(POLICY, "nat"), "NAT", "CP NAT rulebase", null, nat));
        return new PolicySnapshot(metadata, sections, objects(), failures);
    }
    static List<Target> targets() {
        return List.of(new Target("device-tango", "FW-TANGO-04", "", "UNKNOWN"), new Target("device-other", "FW-JULIET-06", "", "UNKNOWN"));
    }
    static PolicySnapshot snapshot(List<Rule> access, List<Rule> nat) { return snapshot(access, nat, targets(), List.of()); }
    /** The bindings collection writes: each gateway object bound to its own enrolled device. */
    static List<TargetBinding> rows() {
        return List.of(new TargetBinding("device-tango", "", POLICY, obj("gw-tango")), new TargetBinding("device-other", "", POLICY, obj("gw-other")));
    }
    static Binding binding(Device device) { return Binding.of(POLICY, rows(), device); }
    static final Tuple CLIENT_TO_PUBLIC = new Tuple("198.51.100.20", "203.0.113.10", "tcp", 443);
    static final Tuple CLIENT_TO_WEB = new Tuple("198.51.100.20", "192.0.2.10", "tcp", 443);

    // ---- G7 install-on --------------------------------------------------------------------------------------------------

    @Test void ruleInstalledOnAnotherGatewayIsSkippedOnlyWithBindingProof() {
        var snapshot = snapshot(List.of(access("r1", 1, "any", "any", "any", "Drop", "gw-other"),
            access("r2", 2, "clients", "web", "https", "Accept", "gw-tango")), null);
        var proven = security(snapshot, TANGO, binding(TANGO), CLIENT_TO_WEB, null, null);
        assertEquals(ALLOWED, proven.verdict()); assertEquals(FIRST_MATCHING_RULE, proven.reason());
        assertEquals("r2", proven.decidingRule().get("ruleId"));
        assertTrue(proven.plain()); assertEquals(List.of(CP_IMPLIED_RULES_NOT_MODELED), proven.notes());

        var unbound = security(snapshot, TANGO, Binding.NONE, CLIENT_TO_WEB, null, null);
        assertEquals(UNKNOWN, unbound.verdict()); assertEquals(EARLIER_RULE_UNRESOLVED, unbound.reason());
        assertEquals("r1", unbound.undecidableRules().get(0).get("ruleId"));
        assertEquals(INSTALL_ON_UNPROVEN, unbound.undecidableRules().get(0).get("pathReason"));

        // For the other gateway the first rule is its own: it applies and decides.
        var other = security(snapshot, OTHER, binding(OTHER), CLIENT_TO_WEB, null, null);
        assertEquals(DENIED, other.verdict()); assertEquals("r1", other.decidingRule().get("ruleId"));
    }

    @Test void bindingThatDoesNotProveAnotherDeviceStaysUnknown() {
        var snapshot = snapshot(List.of(access("r1", 1, "any", "any", "any", "Drop", "gw-other"),
            access("r2", 2, "any", "any", "any", "Accept", "any")), null);
        // The same object also bound to this device in another context: not proven to be another device.
        var shared = Binding.of(POLICY, List.of(new TargetBinding("device-tango", "vs-02", POLICY, obj("gw-other")),
            new TargetBinding("device-other", "", POLICY, obj("gw-other"))), TANGO);
        assertEquals(UNKNOWN, security(snapshot, TANGO, shared, CLIENT_TO_WEB, null, null).verdict());
        // A NULL install-on reference (UNKNOWN) proves nothing.
        var nulls = Binding.of(POLICY, List.of(new TargetBinding("device-other", "", POLICY, null)), TANGO);
        assertEquals(UNKNOWN, security(snapshot, TANGO, nulls, CLIENT_TO_WEB, null, null).verdict());
        // Bindings of another package prove nothing either.
        var foreign = Binding.of(POLICY, List.of(new TargetBinding("device-other", "", "policy-synthetic-02", obj("gw-other"))), TANGO);
        assertEquals(UNKNOWN, security(snapshot, TANGO, foreign, CLIENT_TO_WEB, null, null).verdict());
        // An install-on reference absent from the dictionary stays unknown even with bindings.
        var missing = snapshot(List.of(access("r1", 1, "any", "any", "any", "Drop", "unresolved"),
            access("r2", 2, "any", "any", "any", "Accept", "any")), null);
        var result = security(missing, TANGO, binding(TANGO), CLIENT_TO_WEB, null, null);
        assertEquals(UNKNOWN, result.verdict()); assertEquals(INSTALL_ON_UNPROVEN, result.undecidableRules().get(0).get("pathReason"));
    }

    @Test void policyTargetsAppliesToEveryPackageTargetAndAnUnlistedDeviceIsUnproven() {
        var snapshot = snapshot(List.of(access("r1", 1, "clients", "web", "https", "Accept", "targets")), null);
        assertEquals(ALLOWED, security(snapshot, TANGO, Binding.NONE, CLIENT_TO_WEB, null, null).verdict());
        var stranger = security(snapshot, new Device("device-stranger", "", null), Binding.NONE, CLIENT_TO_WEB, null, null);
        assertEquals(UNKNOWN, stranger.verdict()); assertEquals(TARGET_CONTEXT_UNPROVEN, stranger.reason());
        var otherContext = security(snapshot, new Device("device-tango", "vs-02", null), Binding.NONE, CLIENT_TO_WEB, null, null);
        assertEquals(TARGET_CONTEXT_UNPROVEN, otherContext.reason());
    }

    @Test void unresolvedEarlierRuleAndUnsupportedProtocolStayUnknown() {
        var snapshot = snapshot(List.of(access("r1", 1, "unresolved", "any", "any", "Drop", "any"),
            access("r2", 2, "any", "any", "any", "Accept", "any")), null);
        assertEquals(EARLIER_RULE_UNRESOLVED, security(snapshot, TANGO, Binding.NONE, CLIENT_TO_WEB, null, null).reason());
        var icmp = security(snapshot, TANGO, Binding.NONE, new Tuple("198.51.100.20", "192.0.2.10", "icmp", 0), null, null);
        assertEquals(UNKNOWN, icmp.verdict()); assertEquals(UNSUPPORTED_PROTOCOL, icmp.reason());
        assertThrows(IllegalArgumentException.class, () -> new Tuple("2001:db8::1", "192.0.2.10", "tcp", 443));
        assertThrows(IllegalArgumentException.class, () -> new Tuple("198.51.100.20", "192.0.2.10", "tcp", 70000));
    }

    // ---- NAT (A0 §1a) ---------------------------------------------------------------------------------------------------

    static final Rule DNAT = nat("n2", 2, false, "static", of("any", "public", "https"), of("original", "web", "alt"));

    @Test void manualDestinationNatTranslatesAddressAndPort() {
        var snapshot = snapshot(List.of(access("r1", 1, "any", "any", "any", "Accept", "any")),
            List.of(nat("n1", 1, false, "static", of("any", "web", "any"), of("original", "public", "original")), DNAT));
        var result = prepare(snapshot, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null);
        assertEquals(HopNatEvaluator.TRANSLATED, result.status()); assertEquals(HopNatEvaluator.NAT_FIRST_MATCH, result.reason());
        assertEquals(new Tuple("198.51.100.20", "192.0.2.10", "tcp", 8443), result.translated());
        assertEquals("n2", result.rules().get(0).get("ruleId"));
        assertTrue(result.destinationTranslated() && result.portTranslated() && !result.sourceTranslated());
        var none = prepare(snapshot, TANGO, Binding.NONE).nat(new Tuple("198.51.100.20", "198.51.100.30", "tcp", 443), null, null, null);
        assertEquals(HopNatEvaluator.NO_TRANSLATION, none.status()); assertEquals(HopNatEvaluator.NAT_NO_RULE_MATCHED, none.reason());
        assertEquals(none.original(), none.translated());
    }

    @Test void natIsFirstMatchInCollectedPositionOrderWithoutManualFirstAssumption() {
        // An automatic rule above a manual one decides when it matches first; a manual "no NAT" rule above decides too.
        var autoFirst = snapshot(List.of(), List.of(nat("a1", 1, true, "static", of("any", "public", "any"), of("original", "web", "original")),
            nat("m2", 2, false, "static", of("any", "public", "https"), of("original", "public", "original"))));
        var first = prepare(autoFirst, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null);
        assertEquals(HopNatEvaluator.UNKNOWN, first.status(), "a later manual match after an automatic match is undocumented");
        assertEquals(HopNatEvaluator.CP_NAT_MANUAL_AUTOMATIC_COMBINATION_UNKNOWN, first.reason());
        var noNat = snapshot(List.of(), List.of(nat("m1", 1, false, "static", of("clients", "public", "any"), of("original", "original", "original")), DNAT));
        var kept = prepare(noNat, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null);
        assertEquals(HopNatEvaluator.NO_TRANSLATION, kept.status()); assertEquals("m1", kept.rules().get(0).get("ruleId"));
        var outOfOrder = snapshot(List.of(), List.of(nat("n1", 2, false, "static", of("any", "web", "any"), of("original", "public", "original")),
            nat("n2", 1, false, "static", of("any", "public", "any"), of("original", "web", "original"))));
        assertEquals(HopNatEvaluator.NAT_ORDER_UNPROVEN, prepare(outOfOrder, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null).reason());
    }

    @Test void twoAutomaticRulesTranslateSourceAndDestinationTogether() {
        var snapshot = snapshot(List.of(), List.of(
            nat("a1", 1, true, "static", of("any", "public", "any"), of("original", "web", "original")),
            nat("a2", 2, true, "static", of("any", "public", "any"), of("original", "web", "original")),
            nat("a3", 3, true, "hide", of("clients", "any", "any"), of("hide", "original", "original"))));
        var result = prepare(snapshot, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null);
        assertEquals(HopNatEvaluator.TRANSLATED, result.status()); assertEquals(HopNatEvaluator.NAT_AUTOMATIC_PAIR, result.reason());
        assertEquals(new Tuple("203.0.113.1", "192.0.2.10", "tcp", 443), result.translated());
        assertEquals(List.of("a1", "a3"), result.rules().stream().map(r -> r.get("ruleId")).toList(), "a second rule for the same side is not enforced");
        assertTrue(result.notes().contains(HopNatEvaluator.CP_HIDE_NAT_SOURCE_PORT_NOT_MODELED));
    }

    @Test void unexpressibleUnresolvedOrIncompleteNatIsUnknown() {
        var network = snapshot(List.of(), List.of(nat("n1", 1, false, "static", of("any", "public", "any"), of("original", "clients", "original"))));
        assertEquals(HopNatEvaluator.NAT_TRANSLATION_NOT_EXPRESSIBLE, prepare(network, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null).reason());
        var method = snapshot(List.of(), List.of(nat("n1", 1, false, "nat64", of("any", "public", "any"), of("original", "web", "original"))));
        assertEquals(HopNatEvaluator.NAT_TRANSLATION_NOT_EXPRESSIBLE, prepare(method, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null).reason());
        var earlier = snapshot(List.of(), List.of(nat("n1", 1, false, "static", of("unresolved", "any", "any"), of("original", "web", "original")), DNAT));
        var unresolved = prepare(earlier, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null);
        assertEquals(HopNatEvaluator.NAT_EARLIER_RULE_UNRESOLVED, unresolved.reason());
        assertEquals(HopNatEvaluator.NAT_RULE_SELECTOR_UNRESOLVED, unresolved.undecidableRules().get(0).get("pathReason"));
        var failed = snapshot(List.of(), List.of(DNAT), targets(), List.of(new CollectionFailure("nat-ref", "COLLECTION_PENDING", "NAT", 0)));
        assertEquals(HopNatEvaluator.NAT_COLLECTION_INCOMPLETE, prepare(failed, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null).reason());
        var absent = snapshot(List.of(), null);
        assertEquals(HopNatEvaluator.NAT_NOT_COLLECTED, prepare(absent, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null).reason());
    }

    @Test void natRuleInstalledElsewhereIsSkippedOnlyWithBindingProof() {
        var snapshot = snapshot(List.of(), List.of(nat("n1", 1, false, "static", of("any", "public", "any"), of("original", "web", "original"), "gw-other")));
        assertEquals(HopNatEvaluator.NO_TRANSLATION, prepare(snapshot, TANGO, binding(TANGO)).nat(CLIENT_TO_PUBLIC, null, null, null).status());
        var unproven = prepare(snapshot, TANGO, Binding.NONE).nat(CLIENT_TO_PUBLIC, null, null, null);
        assertEquals(HopNatEvaluator.UNKNOWN, unproven.status());
        assertEquals(INSTALL_ON_UNPROVEN, unproven.undecidableRules().get(0).get("pathReason"));
        assertEquals(HopNatEvaluator.TRANSLATED, prepare(snapshot, OTHER, binding(OTHER)).nat(CLIENT_TO_PUBLIC, null, null, null).status());
    }

    // ---- §5 hop order ---------------------------------------------------------------------------------------------------

    @Test void destinationNatWithDisagreeingPreAndPostNatAccessIsUnknown() {
        // Access allows the public address on 443 only; the translated tuple (web, 8443) hits the explicit cleanup drop.
        var snapshot = snapshot(List.of(access("r1", 1, "any", "public", "https", "Accept", "any"),
            access("r2", 2, "any", "any", "any", "Drop", "any")), List.of(DNAT));
        var hop = checkPoint(snapshot, TANGO, Binding.NONE, CLIENT_TO_PUBLIC);
        assertEquals(UNKNOWN, hop.verdict()); assertEquals(CP_ACCESS_NAT_ORDER_UNKNOWN, hop.reason());
        assertEquals(ALLOWED, hop.preNatAccess().verdict()); assertEquals(DENIED, hop.postNatAccess().verdict());
        assertTrue(hop.notes().contains(CP_ROUTE_AFTER_NAT_UNKNOWN));
    }

    @Test void destinationNatWithAgreeingAccessTakesTheVerdict() {
        var allow = snapshot(List.of(access("r1", 1, "any", "public", "https", "Accept", "any"),
            access("r2", 2, "any", "web", "alt", "Accept", "any")), List.of(DNAT));
        var hop = checkPoint(allow, TANGO, Binding.NONE, CLIENT_TO_PUBLIC);
        assertEquals(ALLOWED, hop.verdict()); assertTrue(hop.plain());
        assertEquals("r1", hop.preNatAccess().decidingRule().get("ruleId")); assertEquals("r2", hop.postNatAccess().decidingRule().get("ruleId"));
        var deny = snapshot(List.of(access("r1", 1, "any", "any", "any", "Drop", "any")), List.of(DNAT));
        assertEquals(DENIED, checkPoint(deny, TANGO, Binding.NONE, CLIENT_TO_PUBLIC).verdict());
    }

    @Test void noOrSourceOnlyNatUsesThePreNatAccessAndUnknownNatMakesTheHopUnknown() {
        var access = List.of(access("r1", 1, "clients", "web", "https", "Accept", "any"));
        var plain = checkPoint(snapshot(access, List.of(DNAT)), TANGO, Binding.NONE, CLIENT_TO_WEB);
        assertEquals(ALLOWED, plain.verdict()); assertNull(plain.postNatAccess());
        var hide = checkPoint(snapshot(access, List.of(nat("n1", 1, false, "hide", of("clients", "any", "any"), of("hide", "original", "original")))),
            TANGO, Binding.NONE, CLIENT_TO_WEB);
        assertEquals(ALLOWED, hide.verdict()); assertTrue(hide.notes().contains(CP_SOURCE_NAT_ACCESS_ON_PRE_NAT));
        assertEquals("203.0.113.1", hide.nat().translated().source());
        var unknownNat = checkPoint(snapshot(access, List.of(nat("n1", 1, false, "static", of("unresolved", "any", "any"), of("original", "web", "original")))),
            TANGO, Binding.NONE, CLIENT_TO_WEB);
        assertEquals(UNKNOWN, unknownNat.verdict()); assertEquals(NAT_UNKNOWN, unknownNat.reason());
        assertEquals(ALLOWED, unknownNat.preNatAccess().verdict(), "the pre-NAT access result is kept as context");
    }
}
