package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;

class MgmtCliEnumerationAdapterTest {
    private static final String DOMAINS = "{\"objects\":[{\"name\":\"DOM-A\"}]}";
    private static final String GATEWAYS = "{\"objects\":[{\"uid\":\"gw-1\",\"type\":\"simple-gateway\","
            + "\"name\":\"FW-TANGO-04\",\"ipv4-address\":\"192.0.2.10\"}]}";

    private static ManagementPlaneEnumerationRequest request() {
        return new ManagementPlaneEnumerationRequest("fixture-manager", 22, "fixture-ref", "fixture-trust",
                Duration.ZERO, Optional.empty());
    }

    private static MgmtCliEnumerationAdapter adapter(FakeDeviceTransport transport) {
        return new MgmtCliEnumerationAdapter(transport,
                ref -> new SshCredentialMaterial("fixture-user", new char[] {'x'}, null), d -> { });
    }

    @Test
    void bareCommandsShareOneInteractiveSessionAndJsonStillParses() {
        FakeDeviceTransport transport = new FakeDeviceTransport(command -> {
            if (command.equals(MgmtCliCommands.domainList())) return new ExecResult.Completed(DOMAINS, 0);
            if (command.equals(MgmtCliCommands.showGatewaysAndServers("DOM-A"))) return new ExecResult.Completed(GATEWAYS, 0);
            if (command.equals("netstat -an")) return new ExecResult.Completed("", 0);
            throw new AssertionError("unexpected command");
        });

        var result = assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, adapter(transport).run(request()));
        assertEquals(1, result.candidates().size());
        assertEquals(1, transport.connectCount());
        assertEquals(1, transport.disconnectCount());
        assertEquals(List.of(MgmtCliCommands.domainList(), MgmtCliCommands.showGatewaysAndServers("DOM-A"),
                "netstat -an", "netstat -an"), transport.commandsIssued());
    }

    @Test
    void timeoutStopsTheRunBeforeAnyLaterCommand() {
        FakeDeviceTransport transport = new FakeDeviceTransport(command -> new ExecResult.TimedOut());

        var result = assertInstanceOf(ManagementPlaneEnumerationResult.Failed.class, adapter(transport).run(request()));
        assertEquals("manager command timed out; stopped", result.reason());
        assertEquals(List.of(MgmtCliCommands.domainList()), transport.commandsIssued());
        assertEquals(1, transport.disconnectCount());
    }
}
