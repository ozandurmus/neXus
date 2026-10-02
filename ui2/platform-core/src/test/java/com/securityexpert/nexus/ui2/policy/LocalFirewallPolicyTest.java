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
}
