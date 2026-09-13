package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.cp.ManagementPlaneEnumerationRequest;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;

/**
 * record §3 row 2 / §10 -- THE DECISIVE TEST. The domain context switch
 * ({@code mdsenv <DOMAIN>}) MUST be issued on the SAME shell command line as
 * the query that follows it: {@code mdsenv} alters the calling shell and a
 * wrapper that runs the two as separate execs discards the context silently,
 * producing confident wrong answers, not errors -- measured by making it
 * happen, not assumed.
 *
 * <p>Written first against the pre-alignment adapter (the merged PR #241
 * transport, {@code mgmt_cli}-based) and observed failing there: that
 * adapter never issued a command containing {@code "mdsenv "} at all, so
 * {@code anyContextCommandSeen} was false and the final assertion failed.
 * Passes now because {@link ManagementShellCommands#contextSwitchAndObjectQuery}
 * is the only place either half of the command is ever produced, always
 * together.</p>
 */
class SameShellContextAndQueryTest {

    @Test
    void everyContextSwitchIsOnTheSameLineAsItsQueryAndNoQueryIsSentWithoutOne() {
        FakeDeviceTransport transport = new FakeDeviceTransport(ManagementPlaneEnumerationAdapterTest.happyPathHandler());
        SshCredentialResolver resolver = ref -> new SshCredentialMaterial("fixture-user", "x".toCharArray(), null);
        ManagementPlaneEnumerationAdapter adapter = new ManagementPlaneEnumerationAdapter(transport, resolver, d -> { });

        adapter.run(new ManagementPlaneEnumerationRequest(
                "fixture-management-host", 2222, "fixture-credential-ref", "fixture-trust-rule-ref",
                Duration.ZERO, Optional.empty()));

        boolean anyContextCommandSeen = false;
        for (String command : transport.commandsIssued()) {
            boolean hasContext = command.contains("mdsenv ");
            boolean hasQuery = command.contains("cpmiquerybin");
            if (hasContext) {
                anyContextCommandSeen = true;
                assertTrue(hasQuery, "context switch sent without a query on the same line: " + command);
            }
            if (hasQuery) {
                assertTrue(hasContext, "query sent without its context switch on the same line: " + command);
            }
        }
        assertTrue(anyContextCommandSeen, "no context-switch command was ever sent");
    }
}
