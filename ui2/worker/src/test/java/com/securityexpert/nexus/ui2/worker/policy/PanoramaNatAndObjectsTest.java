package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** Policy-completeness plan G7, G8, G9: NAT, default rules and extra object types from the approved Panorama reads. */
class PanoramaNatAndObjectsTest {
    private static final Map<String, String> PARENTS = Map.of("Synthetic child", "Synthetic parent");
    private final PanoramaPolicyMapper mapper = new PanoramaPolicyMapper();

    private Metadata metadata() {
        return new Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "container-1", "Synthetic container",
                "Synthetic policy", "2026-10-18T12:00:00Z", "artifact-1", List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN")));
    }
    private String fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/policy/panorama-nat.xml")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private PolicySnapshot child() throws Exception { return mapper.map(metadata(), fixture(), "Synthetic child", PARENTS, false); }
    private static List<Section> natSections(PolicySnapshot s) {
        return s.sections().stream().filter(x -> List.of(PAN_PRE_NAT, PAN_POST_NAT).contains(x.name())).toList();
    }
    private static Rule rule(PolicySnapshot s, String name) {
        return s.sections().stream().flatMap(x -> x.rules().stream()).filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }
    private static String objectName(PolicySnapshot s, String id) { return s.objects().get(id).name(); }

    @Test void natRulebaseFollowsTheSecurityOrderingRuleAndNeverMixesWithSecuritySections() throws Exception {
        var snapshot = child();
        var security = snapshot.sections().stream().filter(s -> List.of("Pre rules", "Post rules").contains(s.name())).toList();
        assertEquals(List.of("uuid-s1", "uuid-s3", "uuid-s2"), security.stream().flatMap(s -> s.rules().stream()).map(Rule::uuid).toList(),
                "security sections are unchanged by NAT parsing");
        // Pre: shared, parent, child. Post: child, parent (no NAT), shared. Empty scopes emit no section.
        assertEquals(List.of("Pre NAT rules:Shared:uuid-n1", "Pre NAT rules:Synthetic parent:uuid-n3", "Pre NAT rules:Synthetic child:uuid-n4",
                        "Post NAT rules:Synthetic child:uuid-n5", "Post NAT rules:Shared:uuid-n2"),
                natSections(snapshot).stream().map(s -> s.name() + ":" + s.source() + ":" + s.rules().get(0).uuid()).toList());
        // NAT sections come after every security section and have identities distinct from them.
        int lastSecurity = snapshot.sections().indexOf(security.get(security.size() - 1));
        assertTrue(natSections(snapshot).stream().allMatch(s -> snapshot.sections().indexOf(s) > lastSecurity));
        var ids = snapshot.sections().stream().map(Section::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertTrue(natSections(snapshot).stream().allMatch(s -> PolicySnapshot.panNonSecurity(snapshot.metadata(), s)));
        assertTrue(security.stream().noneMatch(s -> PolicySnapshot.panNonSecurity(snapshot.metadata(), s)));
    }

    @Test void sourceTranslationInterfaceAddressAndStaticIpAreStoredWithTheirVendorFieldNames() throws Exception {
        var snapshot = child();
        var rule = rule(snapshot, "Synthetic shared nat pre");
        assertEquals("source-nat", rule.action()); assertEquals(Boolean.TRUE, rule.enabled()); assertEquals("Synthetic NAT comment", rule.comment());
        assertEquals(List.of("dynamic-ip-and-port"), rule.extras().get("source-translation-type"));
        assertEquals(List.of("ethernet1/1"), rule.extras().get("source-translation-interface"));
        assertEquals(List.of("192.0.2.1/24"), rule.extras().get("source-translation-interface-ip"));
        assertEquals(List.of("ipv4"), rule.extras().get("nat-type")); assertEquals(List.of("ethernet1/1"), rule.extras().get("to-interface"));
        assertEquals(List.of("Synthetic zone in"), rule.extras().get("from")); assertEquals(List.of("Synthetic zone out"), rule.extras().get("to"));
        assertEquals(List.of("Synthetic inside"), rule.source().refs().stream().map(id -> objectName(snapshot, id)).toList());
        assertEquals("ANY", objectName(snapshot, rule.destination().refs().get(0)));
        assertEquals("ANY", objectName(snapshot, rule.service().refs().get(0)));
        assertTrue(rule.application().refs().isEmpty());
        var staticRule = rule(snapshot, "Synthetic shared nat post");
        assertEquals("source-nat", staticRule.action());
        assertEquals(List.of("192.0.2.60"), staticRule.extras().get("translated-source").stream().map(id -> objectName(snapshot, id)).toList());
        assertEquals(List.of("yes"), staticRule.extras().get("source-translation-bi-directional"));
    }

    @Test void destinationDynamicIpFallbackDynamicDestinationDisabledAndTargetsAreStored() throws Exception {
        var snapshot = child();
        var parent = rule(snapshot, "Synthetic parent nat pre");
        assertEquals("destination-nat", parent.action());
        assertEquals(List.of("Synthetic translated"), parent.extras().get("translated-destination").stream().map(id -> objectName(snapshot, id)).toList());
        assertEquals(List.of("8443"), parent.extras().get("translated-destination-port"));
        assertEquals(List.of("reverse"), parent.extras().get("destination-dns-rewrite"));
        assertEquals("Synthetic HTTPS", objectName(snapshot, parent.service().refs().get(0)));
        // EDL referenced from an address selector resolves to the EDL object, not to an unresolved name.
        var edl = snapshot.objects().get(parent.destination().refs().get(0));
        assertEquals("external-list", edl.type()); assertEquals("DYNAMIC", edl.status());

        var rule = rule(snapshot, "Synthetic child nat pre");
        assertEquals("source-and-destination-nat", rule.action()); assertEquals(Boolean.FALSE, rule.enabled());
        assertEquals(List.of("dynamic-ip"), rule.extras().get("source-translation-type"));
        assertEquals(List.of("Synthetic translated", "192.0.2.70"), rule.extras().get("translated-source").stream().map(id -> objectName(snapshot, id)).toList());
        assertEquals(List.of("interface-address"), rule.extras().get("source-translation-fallback-type"));
        assertEquals(List.of("ethernet1/2"), rule.extras().get("source-translation-fallback-interface"));
        assertEquals(List.of("192.0.2.2"), rule.extras().get("source-translation-fallback-floating-ip"));
        assertEquals(List.of("192.0.2.80"), rule.extras().get("translated-destination-dynamic").stream().map(id -> objectName(snapshot, id)).toList());
        assertEquals(List.of("9000"), rule.extras().get("translated-destination-dynamic-port"));
        assertEquals(List.of("round-robin"), rule.extras().get("destination-dynamic-distribution"));
        assertEquals(List.of("SYNTHETIC-SERIAL-01"), rule.extras().get("target-selectors")); assertEquals(List.of("yes"), rule.extras().get("target/negate"));
        // A region in the original source resolves to the region definition.
        assertEquals("region", snapshot.objects().get(rule.source().refs().get(0)).type());
        assertEquals("no-nat", rule(snapshot, "Synthetic child nat post").action());
    }

    @Test void defaultSecurityRulesAreStoredOnlyWhenOverriddenAndNeverSynthesized() throws Exception {
        var snapshot = child();
        var defaults = snapshot.sections().stream().filter(s -> List.of(PAN_PRE_DEFAULT, PAN_POST_DEFAULT).contains(s.name())).toList();
        // Child has none; parent contributes interzone, shared contributes intrazone. Post order: child, parent, shared.
        assertEquals(List.of("Post default rules:Synthetic parent:interzone-default", "Post default rules:Shared:intrazone-default"),
                defaults.stream().map(s -> s.name() + ":" + s.source() + ":" + s.rules().get(0).name()).toList());
        var interzone = rule(snapshot, "interzone-default");
        assertEquals("deny", interzone.action()); assertEquals("start=no, end=yes", interzone.log());
        assertEquals(List.of("interzone"), interzone.extras().get("rule-type"));
        assertEquals(List.of("intrazone"), rule(snapshot, "intrazone-default").extras().get("rule-type"));
        assertTrue(defaults.stream().allMatch(s -> PolicySnapshot.panNonSecurity(snapshot.metadata(), s)));
        // A device group with an empty NAT rulebase and no override emits no section of its own.
        var empty = mapper.map(metadata(), fixture(), "Synthetic empty", Map.of(), false);
        assertTrue(empty.sections().stream().noneMatch(s -> s.source().equals("Synthetic empty") && PolicySnapshot.panNonSecurity(empty.metadata(), s)));
        assertEquals(List.of("Shared"), empty.sections().stream().filter(s -> PolicySnapshot.panNonSecurity(empty.metadata(), s))
                .map(Section::source).distinct().toList());
    }

    @Test void sharedOnlyAndUnverifiedPrecedencePathsKeepNatSections() throws Exception {
        var shared = mapper.map(metadata(), fixture(), "", Map.of(), false);
        assertEquals(List.of("uuid-n1", "uuid-n2"), natSections(shared).stream().flatMap(s -> s.rules().stream()).map(Rule::uuid).toList());
        var config = com.securityexpert.nexus.ui2.policy.PolicyXml.parse(fixture()).getDocumentElement();
        var unverified = mapper.mapUnverifiedPrecedence(metadata(), config, "Synthetic child", PARENTS);
        assertEquals(natSections(child()).size(), natSections(unverified).size());
    }

    @Test void newObjectTypesAreParsedWithoutExternalListCredentials() throws Exception {
        var snapshot = child();
        Map<String, PolicyObject> byName = snapshot.objects().values().stream()
                .collect(Collectors.toMap(o -> o.type() + ":" + o.name(), o -> o, (a, b) -> a));
        var app = byName.get("application:Synthetic custom app");
        assertEquals("RESOLVED", app.status());
        assertEquals(List.of("category: business-systems", "subcategory: general-business", "technology: client-server", "risk: 3",
                "parent-app: synthetic-parent", "default/port: tcp/8080", "default/port: udp/5353"), app.values());
        var filter = byName.get("application-filter:Synthetic app filter");
        assertEquals("DYNAMIC", filter.status());
        assertEquals(List.of("category: networking", "risk: 4", "risk: 5", "tagging/tag: Synthetic tag", "evasive: yes"), filter.values());
        var group = byName.get("application-group:Synthetic app group");
        assertEquals(List.of("Synthetic custom app", "Synthetic app filter"), group.members().stream().map(id -> objectName(snapshot, id)).toList());
        assertEquals(List.of("application", "application-filter"), group.members().stream().map(id -> snapshot.objects().get(id).type()).toList());
        var edl = byName.get("external-list:Synthetic edl");
        assertEquals(List.of("type: ip", "url: https://edl.example.invalid:8443/lists/ip.txt", "recurring: daily"), edl.values());
        assertFalse(String.join(" ", edl.values()).contains("synthetic-edl-user"), "no user information or auth element is stored");
        var region = byName.get("region:Synthetic region");
        assertEquals("RESOLVED", region.status());
        assertEquals(List.of("address: 198.51.100.0/24", "geo-location/latitude: 10.5", "geo-location/longitude: 20.5"), region.values());
        var dug = byName.get("dynamic-user-group:Synthetic dug");
        assertEquals("DYNAMIC", dug.status()); assertEquals(List.of("filter: 'Synthetic tag' and 'Synthetic other'"), dug.values());
        var profiles = byName.get("profile-group:Synthetic profile group");
        assertEquals(List.of("virus: Synthetic av", "spyware: Synthetic as", "url-filtering: Synthetic url"), profiles.values());
        // The original seven types are unchanged.
        assertEquals(List.of("ip-netmask: 192.0.2.8/32"), byName.get("address:Synthetic inside").values());
        assertEquals(List.of("color: color1"), byName.get("tag:Synthetic tag").values());
    }

    @Test void externalListUrlLosesUserInformationAndNonUrlValuesAreKeptOnlyAsPlainIdentifiers() {
        String at = "@";
        String xml = "<config><shared><external-list>"
                + "<entry name='Synthetic edl one'><type><url><url>https://synthetic-user" + at + "edl.example.invalid/lists/u.txt?k=1</url></url></type></entry>"
                + "<entry name='Synthetic edl two'><type><predefined-ip><url>synthetic-predefined-list</url></predefined-ip></type></entry>"
                + "<entry name='Synthetic edl three'><type><domain><url>not a url; with spaces</url></domain></type></entry>"
                + "</external-list></shared></config>";
        var objects = mapper.map(metadata(), xml, "", Map.of(), false).objects().values().stream()
                .collect(Collectors.toMap(PolicyObject::name, o -> o));
        assertEquals(List.of("type: url", "url: https://edl.example.invalid/lists/u.txt"), objects.get("Synthetic edl one").values());
        assertEquals(List.of("type: predefined-ip", "url: synthetic-predefined-list"), objects.get("Synthetic edl two").values());
        assertEquals(List.of("type: domain"), objects.get("Synthetic edl three").values());
    }

    @Test void applicationCellsNowResolveCustomApplicationsAndFiltersButNotPredefinedOnes() {
        String xml = "<config><shared><application><entry name='Synthetic custom app'><risk>2</risk></entry></application>"
                + "<application-filter><entry name='Synthetic app filter'><evasive>no</evasive></entry></application-filter>"
                + "<pre-rulebase><security><rules><entry name='Synthetic rule' uuid='u1'><application>"
                + "<member>Synthetic custom app</member><member>Synthetic app filter</member><member>ssl</member></application><action>allow</action></entry></rules></security></pre-rulebase></shared></config>";
        var snapshot = mapper.map(metadata(), xml, "", Map.of(), false);
        var refs = snapshot.sections().get(0).rules().get(0).application().refs();
        assertEquals(List.of("application", "application-filter", "unresolved"), refs.stream().map(id -> snapshot.objects().get(id).type()).toList());
        assertEquals(List.of("RESOLVED", "DYNAMIC", "UNRESOLVED"), refs.stream().map(id -> snapshot.objects().get(id).status()).toList());
    }

    @Test void aSnapshotWithNatAndNewObjectTypesSurvivesTheStoredJsonRoundTrip() throws Exception {
        var json = new ObjectMapper();
        var snapshot = child();
        var restored = json.readValue(json.writeValueAsString(snapshot), PolicySnapshot.class);
        assertEquals(snapshot, restored);
        assertEquals(natSections(snapshot).size(), natSections(restored).size());
        assertTrue(restored.objects().values().stream().anyMatch(o -> o.type().equals("external-list")));
        // The stored metadata is the only thing the catalog reads; sections stay inside the snapshot document.
        assertFalse(json.writeValueAsString(snapshot.metadata()).contains("NAT"));
    }

    @Test void localFirewallProjectionIsNotExtendedByNatOrNewObjectTypes() {
        String xml = "<response status='success'><result><config><shared><external-list><entry name='Synthetic edl'/></external-list></shared>"
                + "<devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'><rulebase><security><rules>"
                + "<entry name='Synthetic local' uuid='u1' src='local'><action>allow</action></entry></rules></security>"
                + "<nat><rules><entry name='Synthetic local nat' uuid='u2'/></rules></nat></rulebase></entry></vsys></entry></devices></config></result></response>";
        var local = mapper.local("device-1", "2026-10-18T12:00:00Z", "artifact-1", xml).get(0);
        assertEquals(List.of("Local rules"), local.sections().stream().map(Section::name).toList());
        assertTrue(local.objects().values().stream().noneMatch(o -> o.type().equals("external-list")));
    }
}
