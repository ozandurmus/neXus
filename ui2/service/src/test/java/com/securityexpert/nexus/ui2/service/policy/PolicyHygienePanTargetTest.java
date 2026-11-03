package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Panorama targets end to end: synthetic Panorama XML through PanoramaPolicyMapper into the shadowing analysis. A rule
 * with any target shape -- devices, devices restricted to virtual systems, tags, negate (with or without a selection),
 * or a shape nobody parsed -- is never treated as untargeted: shadowing rejects it ("unsupported rule constraint"), it
 * is no partial prior, and it never covers a later rule. Formerly a tag-only target without a stored negate leaf mapped
 * to no target extras at all and covered the later rule below. Synthetic names and serials only.
 */
class PolicyHygienePanTargetTest {
    private static final Metadata META = new Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "container-1", "Synthetic group",
            "Synthetic policy", "2026-11-03T12:00:00Z", "artifact-1", List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN")));

    /** r1: everything any with the given target; r2: one host on TCP 443, untargeted. */
    private static PolicySnapshot map(String target) {
        String xml = "<config><devices><entry name='localhost.localdomain'><device-group><entry name='Synthetic group'>"
                + "<address><entry name='Synthetic host'><ip-netmask>192.0.2.8/32</ip-netmask></entry></address>"
                + "<service><entry name='Synthetic https'><protocol><tcp><port>443</port></tcp></protocol></entry></service>"
                + "<pre-rulebase><security><rules>"
                + "<entry name='Synthetic broad' uuid='uuid-r1'>" + any("any", "any") + target + "<action>allow</action></entry>"
                + "<entry name='Synthetic narrow' uuid='uuid-r2'>" + any("Synthetic host", "Synthetic https") + "<action>allow</action></entry>"
                + "</rules></security></pre-rulebase></entry></device-group></entry></devices></config>";
        return new PanoramaPolicyMapper().map(META, xml, "Synthetic group", Map.of(), false);
    }
    private static String any(String source, String service) {
        return "<from><member>any</member></from><to><member>any</member></to><source><member>" + source + "</member></source>"
                + "<destination><member>any</member></destination><service><member>" + service + "</member></service>"
                + "<application><member>any</member></application>";
    }
    private static Map<String, PolicyHygiene.Assessment> byName(PolicySnapshot snapshot) {
        var result = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        Map<String, PolicyHygiene.Assessment> out = new HashMap<>();
        for (var section : snapshot.sections()) for (var rule : section.rules()) out.put(rule.name(), result.get(rule.id()));
        return out;
    }

    @Test void untargetedBroadRuleCoversTheNarrowOne() {
        var result = byName(map(""));
        assertEquals("REDUNDANT", result.get("Synthetic narrow").shadowStatus());
    }
    @Test void negateNoWithoutAnySelectionIsUntargeted() {
        // Panorama stores <negate>no</negate> on rules whose target selects nothing: the rule is added to every firewall.
        var result = byName(map("<target><negate>no</negate></target>"));
        assertEquals("NOT_SHADOWED", result.get("Synthetic broad").shadowStatus());
        assertEquals("REDUNDANT", result.get("Synthetic narrow").shadowStatus());
    }
    @Test void everyTargetShapeIsRejectedAndNeverCovers() {
        for (String target : List.of(
                "<target><tags><member>TAG-ALPHA-01</member></tags></target>",
                "<target><devices><entry name='SYNTHETIC-SERIAL-01'><vsys><entry name='vsys2'/></vsys></entry></devices></target>",
                "<target><devices><entry name='SYNTHETIC-SERIAL-01'/></devices></target>",
                "<target><negate>yes</negate></target>",
                "<target><devices><entry name='SYNTHETIC-SERIAL-01'/></devices><negate>no</negate></target>",
                "<target><tags/></target>",
                "<target><unexpected/></target>")) {
            var snapshot = map(target);
            var result = byName(snapshot);
            var broad = result.get("Synthetic broad"); var narrow = result.get("Synthetic narrow");
            assertEquals("UNKNOWN", broad.shadowStatus(), target);
            assertEquals("unsupported rule constraint", broad.shadowReason(), target);
            assertEquals("UNKNOWN", narrow.shadowStatus(), target);
            assertEquals(PartialPriorOracle.UNSUPPORTED, narrow.shadowReason(), target);
            assertNull(narrow.coveringRuleId(), target);
            var r1 = snapshot.sections().get(0).rules().get(0);
            assertEquals(r1.id(), narrow.blockingRuleId(), target);
            // No partial prior either: a target may restrict or invert where the rule applies.
            var resolver = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(Integer.MAX_VALUE));
            assertNull(PolicyNormalization.partial(snapshot, snapshot.sections().get(0), r1, resolver), target);
        }
    }
}
