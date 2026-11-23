package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;

/**
 * Deploy-freeze Phase 1 review D6: a drain yield (AdmissionWait) or lease loss inside a discovery run still disconnects
 * the session directly (T-1), not only through the admission scope's close.
 */
class DiscoveryDrainYieldDisconnectTest {
    private static ManagementPlaneEnumerationRequest request() {
        return new ManagementPlaneEnumerationRequest("fixture-manager", 22, "fixture-ref", "fixture-trust",
                Duration.ZERO, Optional.empty());
    }

    @Test void mgmtCliRunDisconnectsWhenItYields() {
        FakeDeviceTransport transport = new FakeDeviceTransport(command -> {
            if (command.equals(MgmtCliCommands.domainList()))
                return new ExecResult.Completed("{\"objects\":[{\"name\":\"DOM-A\"}]}", 0);
            throw new EndpointRuntime.AdmissionWait();
        });
        var adapter = new MgmtCliEnumerationAdapter(transport,
                ref -> new SshCredentialMaterial("fixture-user", new char[] {'x'}, null), d -> { });
        assertThrows(EndpointRuntime.AdmissionWait.class, () -> adapter.run(request()));
        assertEquals(1, transport.disconnectCount());
    }

    @Test void managementPlaneRunDisconnectsWhenItLosesItsLease() {
        var happy = ManagementPlaneEnumerationAdapterTest.happyPathHandler();
        var first = new boolean[] {true};
        FakeDeviceTransport transport = new FakeDeviceTransport(command -> {
            if (first[0]) { first[0] = false; return happy.apply(command); }
            throw new EndpointRuntime.LeaseLost();
        });
        transport.disconnectThrows(true); // A failing disconnect never hides the original unwinding exception.
        var adapter = new ManagementPlaneEnumerationAdapter(transport,
                ref -> new SshCredentialMaterial("fixture-user", "fixture-password".toCharArray(), null), d -> { });
        assertThrows(EndpointRuntime.LeaseLost.class, () -> adapter.run(request()));
        assertEquals(1, transport.disconnectCount());
    }
}
