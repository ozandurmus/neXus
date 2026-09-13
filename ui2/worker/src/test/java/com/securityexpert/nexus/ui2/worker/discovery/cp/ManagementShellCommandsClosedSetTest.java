package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationResult;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;

/**
 * T-2/T-3/AC-3: every command string the adapter can ever send is a member
 * of {@link ManagementShellCommands#CLOSED_COMMAND_PREFIXES} -- run over a
 * full fixture discovery, not a hand-picked sample.
 */
class ManagementShellCommandsClosedSetTest {

    @Test
    void everyCommandIssuedByAFullRunIsAMemberOfTheClosedSet() {
        FakeDeviceTransport transport = new FakeDeviceTransport(ManagementPlaneEnumerationAdapterTest.happyPathHandler());
        SshCredentialResolver resolver = ref -> new SshCredentialMaterial("fixture-user", "x".toCharArray(), null);
        ManagementPlaneEnumerationAdapter adapter = new ManagementPlaneEnumerationAdapter(transport, resolver, d -> { });

        ManagementPlaneEnumerationResult result = adapter.run(new ManagementPlaneEnumerationRequest(
                "fixture-management-host", 2222, "fixture-credential-ref", "fixture-trust-rule-ref",
                Duration.ZERO, Optional.empty()));

        assertInstanceOf(ManagementPlaneEnumerationResult.Completed.class, result);
        assertFalse(transport.commandsIssued().isEmpty());
        for (String command : transport.commandsIssued()) {
            assertTrue(ManagementShellCommands.isMemberOfClosedSet(command),
                    "command not a member of the closed set: " + command);
        }
    }
}
