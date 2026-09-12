package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.Result;

/**
 * Contract §9 AC-12: {@code directory_posture_enabled} defaults false and
 * the re-validation pool opens no connection while it is false. This test
 * proves the "no connection while disabled" half without a directory
 * server: {@link UnboundIdRevalidationAdapter#revalidate} must refuse
 * before touching {@code LDAPConnectionPool} at all when disabled, which is
 * observable here because the adapter is constructed with an unreachable
 * host/port and a nonexistent CA bundle/secret file — if it ever attempted
 * a connection it would fail with a different error shape than the
 * disabled-refusal this test asserts.
 */
class UnboundIdRevalidationAdapterTest {

    @Test
    void revalidateRefusesWithoutOpeningAConnectionWhileDisabled() {
        UnboundIdRevalidationAdapter adapter = new UnboundIdRevalidationAdapter(
                false, "127.0.0.1", 1,
                Path.of("/nonexistent/ca-bundle.pem"),
                "cn=svc,dc=example,dc=com",
                Path.of("/nonexistent/service-account-password"),
                "ou=groups,dc=example,dc=com");

        assertFalse(adapter.directoryPostureEnabled());

        Result<java.util.Set<String>> result = adapter.revalidate("abc123def456", "cn=alice,dc=example,dc=com");

        assertTrue(result instanceof Result.Err<java.util.Set<String>>);
        Result.Err<java.util.Set<String>> err = (Result.Err<java.util.Set<String>>) result;
        assertEquals("directory_posture_disabled", err.code());
    }
}
