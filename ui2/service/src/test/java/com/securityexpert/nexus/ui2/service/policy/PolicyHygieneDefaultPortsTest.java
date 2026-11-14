package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneApplicationDefaultTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Legacy analysis with the applications' standard ports (PO approval 2026-11-14: the predefined App-ID catalogue,
 * PanoramaPolicyMapper#withPredefinedApplications; custom applications' own default/port): application-default pairs
 * the sound analysis leaves "application default ports are unknown" are decided. The sound analysis is unchanged.
 * Synthetic fixtures: documentation addresses and masked application names only.
 */
class PolicyHygieneDefaultPortsTest {
    static final String WEB = ref(SNAPSHOT, "service", "web"), WIDE = ref(SNAPSHOT, "service", "wide");

    static PolicySnapshot withCatalogue(PolicySnapshot snapshot, Map<String, List<String>> catalogue) {
        Map<String, PolicyObject> objects = new HashMap<>(snapshot.objects());
        objects.put(WEB, object(WEB, "service", "protocol/tcp/port: 443"));
        objects.put(WIDE, object(WIDE, "service", "protocol/tcp/port: 1-1024"));
        var base = new PolicySnapshot(snapshot.metadata(), snapshot.sections(), objects);
        return com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper.withPredefinedApplications(base, catalogue);
    }
    static Map<String, PolicyHygiene.Assessment> legacy(PolicySnapshot snapshot) {
        return PolicyHygiene.analyzeLegacy(PolicyLegacyView.of(snapshot), 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
    }
    static final Map<String, List<String>> CATALOGUE = Map.of(
        "APP-ALPHA-01", List.of("default/port: tcp/443"),
        "APP-BRAVO-02", List.of("default/port: tcp/80,443", "default/port: udp/53"));

    @Test void anExplicitPortRuleCoversAnApplicationDefaultRuleWhosePortsItHolds() {
        var snapshot = withCatalogue(pan(rule("r1", "any", WIDE, "allow", A), rule("r2", "any", APPDEF, "allow", A)), CATALOGUE);
        var r2 = legacy(snapshot).get("r2");
        assertEquals("REDUNDANT", r2.shadowStatus(), r2.toString());
        assertEquals("r1", r2.coveringRuleId());
        // The sound analysis is unchanged.
        assertEquals(DEFAULT_PORTS, PolicyHygieneTest.assessment(snapshot, "r2").shadowReason());
    }

    @Test void aStandardPortOutsideTheEarlierPortsMeansNoCover() {
        var snapshot = withCatalogue(pan(rule("r1", "any", WEB, "allow", B), rule("r2", "any", APPDEF, "allow", B)), CATALOGUE);
        assertEquals("NOT_SHADOWED", legacy(snapshot).get("r2").shadowStatus());
    }

    @Test void anApplicationDefaultRuleCoversAnExplicitRuleOnItsStandardPorts() {
        var snapshot = withCatalogue(pan(rule("r1", "any", APPDEF, "deny", A), rule("r2", "any", WEB, "allow", A)), CATALOGUE);
        var r2 = legacy(snapshot).get("r2");
        assertEquals("CONFLICT", r2.shadowStatus(), r2.toString());
        assertEquals("r1", r2.coveringRuleId());
        var outside = withCatalogue(pan(rule("r1", "any", APPDEF, "deny", A), rule("r2", "any", WIDE, "allow", A)), CATALOGUE);
        assertEquals("NOT_SHADOWED", legacy(outside).get("r2").shadowStatus());
    }

    @Test void unknownPortsKeepTheFormerAnswer() {
        for (var catalogue : List.of(Map.<String, List<String>>of(), Map.of("APP-ALPHA-01", List.of("default/ident-by-ip-protocol: 1")),
                Map.of("APP-ALPHA-01", List.of("default/port: UNPARSED")), Map.of("APP-ALPHA-01", List.<String>of()))) {
            var snapshot = withCatalogue(pan(rule("r1", "any", WIDE, "allow", A), rule("r2", "any", APPDEF, "allow", A)), catalogue);
            var r2 = legacy(snapshot).get("r2");
            assertEquals("UNKNOWN", r2.shadowStatus(), catalogue + " " + r2);
            assertEquals(DEFAULT_PORTS, r2.shadowReason());
        }
    }

    @Test void applicationAnyAndForeignApplicationsStayUndecided() {
        var any = withCatalogue(pan(rule("r1", "any", WIDE, "allow", A), rule("r2", "any", APPDEF, "allow")), CATALOGUE);
        assertEquals("UNKNOWN", legacy(any).get("r2").shadowStatus());
        var foreign = withCatalogue(pan(rule("r1", "any", WIDE, "allow", A), rule("r2", "any", APPDEF, "allow", B)), CATALOGUE);
        assertNotEquals("REDUNDANT", legacy(foreign).get("r2").shadowStatus());
    }

    @Test void customApplicationsUseTheirOwnDefaultPorts() {
        // An explicit-service rule naming a custom application keeps its former handling (unresolved), so the earlier rule
        // here names application any.
        var snapshot = withCatalogue(pan(rule("r1", "any", WIDE, "allow"), rule("r2", "any", APPDEF, "allow", "custom")), Map.of());
        assertEquals("NOT_SHADOWED", legacy(snapshot).get("r2").shadowStatus(), "tcp/8443 lies outside 1-1024");
        var inside = withCatalogue(pan(rule("r1", "any", ref(SNAPSHOT, "service", "high"), "allow"), rule("r2", "any", APPDEF, "allow", "custom")), Map.of());
        var objects = new HashMap<>(inside.objects());
        objects.put(ref(SNAPSHOT, "service", "high"), object(ref(SNAPSHOT, "service", "high"), "service", "protocol/tcp/port: 8000-9000"));
        var r2 = legacy(new PolicySnapshot(inside.metadata(), inside.sections(), objects)).get("r2");
        assertEquals("REDUNDANT", r2.shadowStatus(), r2.toString());
    }

    @Test void theMapperAddsCatalogueObjectsOnlyForApplicationsWithoutADefinition() {
        var snapshot = withCatalogue(pan(rule("r1", "any", APPDEF, "allow", A, "custom")), Map.of("APP-ALPHA-01", List.of("default/port: tcp/443"), "custom", List.of("default/port: tcp/1")));
        var defaults = snapshot.objects().get(ref(SNAPSHOT, "predefined-application", "APP-ALPHA-01"));
        assertEquals("application-defaults", defaults.type());
        assertEquals(List.of("default/port: tcp/443"), defaults.values());
        assertNull(snapshot.objects().get(ref(SNAPSHOT, "predefined-application", "custom")));
        assertEquals("UNRESOLVED", snapshot.objects().get(A).status(), "the application's own object is unchanged");
    }
}
