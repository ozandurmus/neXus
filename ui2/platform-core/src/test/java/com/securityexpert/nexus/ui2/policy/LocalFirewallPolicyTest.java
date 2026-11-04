package com.securityexpert.nexus.ui2.policy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

class LocalFirewallPolicyTest {
    @Test void mapsStoredVsysPolicyAndExcludesExplicitInheritedRulesAndUnneededConfiguration() {
        String xml = "<response status='success'><result><config><mgt-config><unrelated>withheld</unrelated></mgt-config>"
            + "<shared><address><entry name='Synthetic address'><ip-netmask>192.0.2.8/32</ip-netmask></entry></address></shared>"
            + "<devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'><rulebase><security><rules>"
            + "<entry name='Synthetic local' uuid='synthetic-rule-01' src='local'><disabled>no</disabled>"
            + "<source><member>Synthetic address</member></source><action>allow</action></entry>"
            + "<entry name='Synthetic inherited' src='dg'/></rules></security></rulebase></entry></vsys></entry></devices></config></result></response>";
        var document = PolicyXml.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), true);
        assertEquals(0, document.getElementsByTagName("mgt-config").getLength());
        var snapshots = new PanoramaPolicyMapper().local("device-1", "2026-10-01T12:00:00Z", "artifact-1", document.getDocumentElement());
        assertEquals(1, snapshots.size());
        var snapshot = snapshots.get(0);
        assertEquals("Local rules", snapshot.sections().get(0).name());
        assertEquals(1, snapshot.sections().get(0).rules().size());
        assertEquals("synthetic-rule-01", snapshot.sections().get(0).rules().get(0).uuid());
        assertEquals("device-1", snapshot.metadata().targets().get(0).deviceId());
        assertEquals("vsys1", snapshot.metadata().targets().get(0).context());
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.values().contains("ip-netmask: 192.0.2.8/32")));
        assertThrows(RuntimeException.class, () -> new PanoramaPolicyMapper().local("device-1", "", "", "<response status='error'><result/></response>"));
    }

    /** Path analysis A2 (G3): the stored document's own NAT and overridden default rules, never inherited ones. */
    @Test void mapsLocalNatAndDefaultRulesFromTheSameStoredConfiguration() {
        String xml = "<response status='success'><result><config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>"
            + "<address><entry name='OBJ-WEB-01'><ip-netmask>192.0.2.10/32</ip-netmask></entry>"
            + "<entry name='OBJ-PUBLIC-01'><ip-netmask>203.0.113.10/32</ip-netmask></entry></address>"
            + "<rulebase><security><rules><entry name='RULE-ALPHA-01' uuid='sec-1'><from><member>ZONE-ALPHA</member></from>"
            + "<to><member>ZONE-BRAVO</member></to><source><member>any</member></source><destination><member>any</member></destination>"
            + "<service><member>any</member></service><application><member>any</member></application><action>allow</action></entry></rules></security>"
            + "<nat><rules><entry name='RULE-NAT-01' uuid='nat-1'><from><member>ZONE-BRAVO</member></from><to><member>ZONE-BRAVO</member></to>"
            + "<source><member>any</member></source><destination><member>OBJ-PUBLIC-01</member></destination><service>any</service>"
            + "<destination-translation><translated-address>OBJ-WEB-01</translated-address><translated-port>8443</translated-port></destination-translation></entry>"
            + "<entry name='RULE-NAT-PUSHED' uuid='nat-2' src='dg'/></rules></nat>"
            + "<default-security-rules><rules><entry name='interzone-default'><action>allow</action></entry>"
            + "<entry name='intrazone-default' src='dg'><action>deny</action></entry></rules></default-security-rules>"
            + "</rulebase></entry></vsys></entry></devices></config></result></response>";
        var snapshot = new PanoramaPolicyMapper().local("device-1", "2026-11-04T08:00:00Z", "artifact-1", xml).get(0);
        assertEquals(java.util.List.of(PolicySnapshot.PAN_LOCAL_RULES, PolicySnapshot.PAN_LOCAL_NAT, PolicySnapshot.PAN_LOCAL_DEFAULT),
            snapshot.sections().stream().map(PolicySnapshot.Section::name).toList());
        var nat = snapshot.sections().get(1);
        assertEquals(1, nat.rules().size(), "a rule pushed from a manager is not a local rule");
        var rule = nat.rules().get(0);
        assertEquals("destination-nat", rule.action());
        assertEquals(java.util.List.of("8443"), rule.extras().get("translated-destination-port"));
        assertEquals(PolicySnapshot.ref(snapshot.metadata().id(), "address", "OBJ-WEB-01"), rule.extras().get("translated-destination").get(0));
        var defaults = snapshot.sections().get(2);
        assertEquals(java.util.List.of("interzone-default"), defaults.rules().stream().map(PolicySnapshot.Rule::name).toList());
        assertEquals(java.util.List.of("interzone"), defaults.rules().get(0).extras().get("rule-type"));
        for (var section : snapshot.sections().subList(1, 3))
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section), "never evaluated as security rules");
        assertFalse(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), snapshot.sections().get(0)));
    }

    @Test void localDocumentWithoutNatOrDefaultRulesEmitsNoSuchSection() {
        String xml = "<response status='success'><result><config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>"
            + "<rulebase><security><rules/></security></rulebase></entry></vsys></entry></devices></config></result></response>";
        var snapshot = new PanoramaPolicyMapper().local("device-1", "2026-11-04T08:00:00Z", "artifact-1", xml).get(0);
        assertEquals(java.util.List.of(PolicySnapshot.PAN_LOCAL_RULES), snapshot.sections().stream().map(PolicySnapshot.Section::name).toList());
    }
}
