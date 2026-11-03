package com.securityexpert.nexus.ui2.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Panorama rule targets (PATH_ANALYSIS_VENDOR_SEMANTICS.md 4d): every target shape is recorded, so no rule whose target
 * is not fully represented looks untargeted. Before this change only the device-entry names and negate were kept, so a
 * tag-only target without a stored negate leaf read exactly like "no target" (every firewall of the device group).
 * Synthetic Panorama XML only: masked names, synthetic serials.
 */
class PanoramaTargetRepresentationTest {
    private static final Metadata META = new Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "container-1", "Synthetic group",
            "Synthetic policy", "2026-11-03T12:00:00Z", "artifact-1", List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN")));

    /** One security pre-rule and the same target on one NAT pre-rule, in a single device group. */
    private static PolicySnapshot map(String target) {
        String rule = "<from><member>any</member></from><to><member>any</member></to><source><member>any</member></source>"
                + "<destination><member>any</member></destination><service><member>any</member></service>"
                + "<application><member>any</member></application>" + target;
        String xml = "<config><devices><entry name='localhost.localdomain'><device-group><entry name='Synthetic group'><pre-rulebase>"
                + "<security><rules><entry name='Synthetic rule' uuid='uuid-s1'>" + rule + "<action>allow</action></entry></rules></security>"
                + "<nat><rules><entry name='Synthetic nat' uuid='uuid-n1'>" + rule + "</entry></rules></nat>"
                + "</pre-rulebase></entry></device-group></entry></devices></config>";
        return new PanoramaPolicyMapper().map(META, xml, "Synthetic group", Map.of(), false);
    }
    private static List<Map<String, List<String>>> extras(PolicySnapshot snapshot) {
        return snapshot.sections().stream().flatMap(s -> s.rules().stream()).map(Rule::extras).toList();
    }
    private static Map<String, List<String>> targetKeys(Map<String, List<String>> extras) {
        var out = new TreeMap<String, List<String>>();
        extras.forEach((k, v) -> { if (k.startsWith("target")) out.put(k, v); });
        return out;
    }
    private static void assertTargets(String target, Map<String, List<String>> expected) {
        var all = extras(map(target));
        assertEquals(2, all.size(), "security and NAT rule");
        for (var extras : all) assertEquals(new TreeMap<>(expected), targetKeys(extras), target);
    }

    @Test void noTargetElementRecordsNothing() {
        assertTargets("", Map.of());
        // An empty device list selects no firewall: the documented "added to all firewalls of the device group".
        assertTargets("<target><devices/></target>", Map.of());
    }
    @Test void deviceEntriesStayTargetSelectorsAsBefore() {
        assertTargets("<target><devices><entry name='SYNTHETIC-SERIAL-01'/><entry name='SYNTHETIC-SERIAL-02'/></devices><negate>no</negate></target>",
                Map.of("target-selectors", List.of("SYNTHETIC-SERIAL-01", "SYNTHETIC-SERIAL-02"), "target/negate", List.of("no")));
    }
    @Test void tagOnlyTargetIsRecordedAndNeverLooksUntargeted() {
        // Formerly no extras at all: the rule read as installed on every firewall of the device group.
        assertTargets("<target><tags><member>TAG-ALPHA-01</member><member>TAG-BRAVO-02</member></tags></target>",
                Map.of("target-tags", List.of("TAG-ALPHA-01", "TAG-BRAVO-02")));
        assertTargets("<target><tags><member>TAG-ALPHA-01</member></tags><negate>yes</negate></target>",
                Map.of("target-tags", List.of("TAG-ALPHA-01"), "target/negate", List.of("yes")));
    }
    @Test void deviceWithVsysEntriesKeepsThemUnderTheDevice() {
        assertTargets("<target><devices><entry name='SYNTHETIC-SERIAL-01'><vsys><entry name='vsys2'/><entry name='vsys3'/></vsys></entry>"
                        + "<entry name='SYNTHETIC-SERIAL-02'/></devices></target>",
                Map.of("target-selectors", List.of("SYNTHETIC-SERIAL-01", "SYNTHETIC-SERIAL-02"),
                        "target-vsys", List.of("SYNTHETIC-SERIAL-01/vsys2", "SYNTHETIC-SERIAL-01/vsys3")));
    }
    @Test void negateWithNothingSelectedIsKept() {
        // Documented: the rule is added to no firewall of the device group. Only the negate leaf represents it.
        assertTargets("<target><negate>yes</negate></target>", Map.of("target/negate", List.of("yes")));
    }
    @Test void anyShapeNobodyParsedIsRecordedAsUnrecognized() {
        assertTargets("<target><tags/></target>", Map.of("target-unrecognized", List.of("tags")));
        assertTargets("<target><tags>TAG-ALPHA-01</tags></target>", Map.of("target-unrecognized", List.of("tags")));
        assertTargets("<target><devices><entry name='SYNTHETIC-SERIAL-01'><vsys/></entry></devices></target>",
                Map.of("target-selectors", List.of("SYNTHETIC-SERIAL-01"), "target-unrecognized", List.of("devices/entry/vsys")));
        assertTargets("<target><devices><entry name='SYNTHETIC-SERIAL-01'><unexpected/></entry><member>x</member></devices><scope/></target>",
                Map.of("target-selectors", List.of("SYNTHETIC-SERIAL-01"),
                        "target-unrecognized", List.of("devices/entry/unexpected", "devices/member", "scope")));
    }
}
