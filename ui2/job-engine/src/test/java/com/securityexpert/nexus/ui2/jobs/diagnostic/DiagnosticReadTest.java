package com.securityexpert.nexus.ui2.jobs.diagnostic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.capability.*;
class DiagnosticReadTest {
    @Test void exactReviewedReadsOnlyForMatchingDeviceScope() {
        var rows=GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates=key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        assertTrue(DiagnosticRead.resolve("fortinet","management_server","diagnose fmnetwork interface detail port5",gates).isPresent());
        assertTrue(DiagnosticRead.resolve("fortinet","gateway","get system status",gates).isPresent());
        assertTrue(DiagnosticRead.resolve("cisco_asa","gateway","show version",gates).isPresent());
        for(String invalid: new String[]{"execute reboot","show","diagnose fmnetwork interface detail port5; reboot","diagnose fmnetwork interface detail port5\nget system status"})
            assertTrue(DiagnosticRead.resolve("fortinet","management_server",invalid,gates).isEmpty());
        assertTrue(DiagnosticRead.resolve("palo_alto","gateway","show version",gates).isEmpty());
        assertTrue(DiagnosticRead.resolve("fortinet","gateway","get system status",key -> java.util.List.of()).isEmpty());
    }
}
