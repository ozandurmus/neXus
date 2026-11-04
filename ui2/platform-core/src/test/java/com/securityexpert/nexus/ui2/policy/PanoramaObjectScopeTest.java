package com.securityexpert.nexus.ui2.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Object scope of a Panorama device-group rule: Shared, then the ancestors from the top down, then the device group itself;
 * never a sibling or a descendant. Same name in several scopes: the descendant wins by default, the highest ancestor
 * with "Objects defined in ancestors will take higher precedence", and UNKNOWN when that setting is not collected. A name
 * defined nowhere in those scopes, including predefined content the approved reads do not return, stays UNRESOLVED.
 * Synthetic Panorama XML only: masked names, documentation addresses.
 */
class PanoramaObjectScopeTest {
    private static final Metadata META = new Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "container-1", "DG-LEAF",
            "Synthetic policy", "2026-11-04T12:00:00Z", "artifact-1", List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN")));
    /** DG-ROOT has two children, DG-SIBLING and DG-MID; DG-LEAF is the child of DG-MID. */
    private static final Map<String, String> PARENTS = Map.of("DG-ROOT", "", "DG-SIBLING", "DG-ROOT", "DG-MID", "DG-ROOT", "DG-LEAF", "DG-MID");

    private static String address(String name, String value) {
        return "<entry name='" + name + "'><ip-netmask>" + value + "</ip-netmask></entry>";
    }
    private static String members(String... names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) out.append("<member>").append(name).append("</member>");
        return out.toString();
    }
    private static String rule(String name, String[] source, String[] destination, String[] service, String[] application) {
        return "<entry name='" + name + "' uuid='uuid-" + name + "'><from><member>ZONE-IN</member></from><to><member>ZONE-OUT</member></to>"
                + "<source>" + members(source) + "</source><destination>" + members(destination) + "</destination>"
                + "<service>" + members(service) + "</service><application>" + members(application) + "</application>"
                + "<action>allow</action></entry>";
    }
    private static String rules(String... entries) {
        return "<pre-rulebase><security><rules>" + String.join("", entries) + "</rules></security></pre-rulebase>";
    }

    private static final String XML = "<config><shared>"
            + "<address>" + address("OBJ-SHARED-ONLY", "192.0.2.10/32") + address("OBJ-SHARED-AND-LEAF", "192.0.2.11/32") + "</address>"
            + "<service><entry name='SVC-SHARED'><protocol><tcp><port>8443</port></tcp></protocol></entry></service>"
            + "</shared><devices><entry name='localhost.localdomain'><device-group>"
            + "<entry name='DG-ROOT'><address>" + address("OBJ-ROOT-ONLY", "198.51.100.1/32") + address("OBJ-ROOT-AND-LEAF", "198.51.100.2/32")
            + address("OBJ-SAME-VALUE", "198.51.100.3/32") + "</address>"
            + "<service-group><entry name='SVG-ROOT'><members><member>SVC-SHARED</member></members></entry></service-group></entry>"
            + "<entry name='DG-SIBLING'><address>" + address("OBJ-SIBLING-ONLY", "203.0.113.1/32") + "</address></entry>"
            + "<entry name='DG-MID'><address>" + address("OBJ-MID-ONLY", "203.0.113.2/32") + "</address>"
            + rules(rule("RULE-MID", new String[] {"OBJ-LEAF-ONLY"}, new String[] {"OBJ-MID-ONLY"}, new String[] {"any"}, new String[] {"any"})) + "</entry>"
            + "<entry name='DG-LEAF'><address>" + address("OBJ-SHARED-AND-LEAF", "203.0.113.11/32") + address("OBJ-ROOT-AND-LEAF", "203.0.113.12/32")
            + address("OBJ-SAME-VALUE", "198.51.100.3/32") + address("OBJ-LEAF-ONLY", "203.0.113.13/32") + "</address>"
            + rules(rule("RULE-LEAF", new String[] {"OBJ-SHARED-ONLY", "OBJ-ROOT-ONLY", "OBJ-MID-ONLY"},
                    new String[] {"OBJ-SHARED-AND-LEAF", "OBJ-ROOT-AND-LEAF", "OBJ-SAME-VALUE", "OBJ-SIBLING-ONLY", "OBJ-NOWHERE"},
                    new String[] {"SVG-ROOT"}, new String[] {"any"}),
                // Predefined content that the approved reads do not return: a country, a built-in EDL, an App-ID application.
                rule("RULE-PREDEFINED", new String[] {"US"}, new String[] {"panw-highrisk-ip-list"}, new String[] {"any"}, new String[] {"ssl"}))
            + "</entry></device-group></entry></devices></config>";

    private static PolicyObject object(PolicySnapshot snapshot, String family, String name) {
        return Objects.requireNonNull(snapshot.objects().get(ref(META.id(), family, name)), family + ":" + name);
    }
    private static void assertResolved(PolicySnapshot snapshot, String name, String value) {
        var o = object(snapshot, "address", name);
        assertEquals("RESOLVED", o.status(), name); assertEquals("address", o.type(), name);
        assertEquals(List.of("ip-netmask: " + value), o.values(), name);
    }
    private static void assertUnresolved(PolicySnapshot snapshot, String family, String name) {
        var o = object(snapshot, family, name);
        assertEquals("UNRESOLVED", o.status(), name); assertEquals("unresolved", o.type(), name);
        assertTrue(o.values().isEmpty() && o.members().isEmpty(), name);
    }
    private static Rule rule(PolicySnapshot snapshot, String name) {
        return snapshot.sections().stream().flatMap(s -> s.rules().stream()).filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }
    private static PolicySnapshot leaf(boolean ancestorsFirst) { return new PanoramaPolicyMapper().map(META, XML, "DG-LEAF", PARENTS, ancestorsFirst); }
    private static PolicySnapshot leafUnverified() {
        return new PanoramaPolicyMapper().mapUnverifiedPrecedence(META, PolicyXml.parse(XML).getDocumentElement(), "DG-LEAF", PARENTS);
    }

    @Test void objectsDefinedOnlyInSharedOrAnAncestorResolveForADeviceGroupRule() {
        for (var snapshot : List.of(leaf(false), leaf(true), leafUnverified())) {
            assertResolved(snapshot, "OBJ-SHARED-ONLY", "192.0.2.10/32");
            assertResolved(snapshot, "OBJ-ROOT-ONLY", "198.51.100.1/32");   // grandparent
            assertResolved(snapshot, "OBJ-MID-ONLY", "203.0.113.2/32");     // parent
            // A service group in an ancestor whose member lives in Shared.
            var group = object(snapshot, "service", "SVG-ROOT");
            assertEquals("RESOLVED", group.status()); assertEquals("service-group", group.type());
            assertEquals(List.of(ref(META.id(), "service", "SVC-SHARED")), group.members());
            assertEquals(List.of("protocol/tcp/port: 8443"), object(snapshot, "service", "SVC-SHARED").values());
            // The rule cells reference exactly those objects.
            assertEquals(List.of("OBJ-SHARED-ONLY", "OBJ-ROOT-ONLY", "OBJ-MID-ONLY"),
                    rule(snapshot, "RULE-LEAF").source().refs().stream().map(id -> snapshot.objects().get(id).name()).toList());
        }
    }

    @Test void theDescendantObjectWinsByDefaultAndTheHighestAncestorWinsWithAncestorPrecedence() {
        var descendant = leaf(false);
        assertResolved(descendant, "OBJ-SHARED-AND-LEAF", "203.0.113.11/32");
        assertResolved(descendant, "OBJ-ROOT-AND-LEAF", "203.0.113.12/32");
        var ancestor = leaf(true);
        assertResolved(ancestor, "OBJ-SHARED-AND-LEAF", "192.0.2.11/32");
        assertResolved(ancestor, "OBJ-ROOT-AND-LEAF", "198.51.100.2/32");
        // The same name with the same definition in two scopes does not depend on the precedence setting.
        assertResolved(descendant, "OBJ-SAME-VALUE", "198.51.100.3/32");
        assertResolved(ancestor, "OBJ-SAME-VALUE", "198.51.100.3/32");
    }

    @Test void anUncollectedPrecedenceSettingLeavesOnlyTheDisagreeingObjectsUnknown() {
        var unverified = leafUnverified();
        for (String name : List.of("OBJ-SHARED-AND-LEAF", "OBJ-ROOT-AND-LEAF")) {
            var o = object(unverified, "address", name);
            assertEquals("UNKNOWN", o.status(), name); assertTrue(o.values().isEmpty(), name);
        }
        assertResolved(unverified, "OBJ-SAME-VALUE", "198.51.100.3/32");
        assertResolved(unverified, "OBJ-SHARED-ONLY", "192.0.2.10/32");
    }

    @Test void siblingDescendantAndMissingNamesStayUnresolved() {
        for (var snapshot : List.of(leaf(false), leaf(true), leafUnverified())) {
            assertUnresolved(snapshot, "address", "OBJ-SIBLING-ONLY");
            assertUnresolved(snapshot, "address", "OBJ-NOWHERE");
        }
        // A parent's rule never sees its child's object, even though the child defines it.
        var mid = new PanoramaPolicyMapper().map(META, XML, "DG-MID", PARENTS, false);
        assertUnresolved(mid, "address", "OBJ-LEAF-ONLY");
        assertResolved(mid, "OBJ-MID-ONLY", "203.0.113.2/32");
        assertTrue(mid.sections().stream().flatMap(s -> s.rules().stream()).noneMatch(r -> r.name().equals("RULE-LEAF")));
    }

    @Test void predefinedContentWithoutADocumentedIdentifierStaysUnresolvedAndAnyResolves() {
        var snapshot = leaf(false);
        assertUnresolved(snapshot, "address", "US");
        assertUnresolved(snapshot, "address", "panw-highrisk-ip-list");
        assertUnresolved(snapshot, "application", "ssl");
        var any = object(snapshot, "application", "any");
        assertEquals("RESOLVED", any.status()); assertEquals("any", any.type());
    }
}
