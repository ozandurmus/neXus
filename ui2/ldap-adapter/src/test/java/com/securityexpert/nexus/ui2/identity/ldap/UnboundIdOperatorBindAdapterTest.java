package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.Result;

/**
 * Container-free unit tests: no directory server is contacted (this test
 * never opens a connection). These prove the fail-closed checks C3 §2.2
 * requires happen strictly before any network call.
 */
class UnboundIdOperatorBindAdapterTest {

    @Test
    void emptyPasswordIsRefusedBeforeAnyDirectoryCall() {
        UnboundIdOperatorBindAdapter adapter = UnboundIdOperatorBindAdapter.forTestDirectory(
                "127.0.0.1", 1, javax.net.SocketFactory.getDefault(),
                "cn=%s,dc=example,dc=com", "ou=groups,dc=example,dc=com");

        Result<LdapOperatorBindPort.OperatorBindOutcome> result = adapter.bind("alice", new char[0]);

        assertTrue(result instanceof Result.Err<LdapOperatorBindPort.OperatorBindOutcome>);
        Result.Err<LdapOperatorBindPort.OperatorBindOutcome> err =
                (Result.Err<LdapOperatorBindPort.OperatorBindOutcome>) result;
        assertEquals(LdapOperatorBindPort.FailureCodes.EMPTY_PASSWORD, err.code());
    }

    @Test
    void nullPasswordIsRefusedBeforeAnyDirectoryCall() {
        UnboundIdOperatorBindAdapter adapter = UnboundIdOperatorBindAdapter.forTestDirectory(
                "127.0.0.1", 1, javax.net.SocketFactory.getDefault(),
                "cn=%s,dc=example,dc=com", "ou=groups,dc=example,dc=com");

        Result<LdapOperatorBindPort.OperatorBindOutcome> result = adapter.bind("alice", null);

        assertTrue(result instanceof Result.Err<LdapOperatorBindPort.OperatorBindOutcome>);
    }

    @Test
    void unreadableCaBundleFailsClosedAtStartup() {
        Path missing = Path.of("/nonexistent/ca-bundle-" + System.nanoTime() + ".pem");
        assertTrue(assertThrowsStartupException(missing));
    }

    private static boolean assertThrowsStartupException(Path missing) {
        try {
            UnboundIdOperatorBindAdapter.create("127.0.0.1", 636, missing,
                    "cn=%s,dc=example,dc=com", "ou=groups,dc=example,dc=com");
            return false;
        } catch (LdapStartupException expected) {
            return true;
        }
    }
}
