package com.securityexpert.nexus.ui2.identity.ldap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.Result;

/** Design §3: trust any certificate, and trust only the pasted certificate with a host-name / IP SAN check. */
class DirectoryTrustModesTest {

    private static Result<LdapOperatorBindPort.OperatorBindOutcome> signIn(ActiveDirectoryFixture ad, String server,
            DirectoryProfile.TrustMode mode, String pem) {
        DirectoryProfile profile = ad.profile(List.of(server), true, mode, pem);
        return new UnboundIdOperatorBindAdapter(profile, new DirectoryTrustPolicy(profile, () -> { }),
                () -> ad.servicePassword.clone()).bind("tls-user", "pw-tls".toCharArray());
    }

    private static String reason(Result<?> result) {
        return result instanceof Result.Err<?> err ? err.message() : "ok";
    }

    @Test
    void trustAnyAcceptsACertificateForAnotherNameReachedByIp() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_DNS_LOCALHOST)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            assertEquals("ok", reason(signIn(ad, "127.0.0.1", DirectoryProfile.TrustMode.TRUST_ANY, null)));
        }
    }

    @Test
    void pemModeAcceptsTheMatchingHostName() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_DNS_LOCALHOST)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            String ca = ActiveDirectoryFixture.Tls.material().caPem();
            assertEquals("ok", reason(signIn(ad, "localhost", DirectoryProfile.TrustMode.TRUST_PEM, ca)));
        }
    }

    @Test
    void pemModeAcceptsAPinnedServerCertificate() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_DNS_LOCALHOST)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            String leaf = ActiveDirectoryFixture.Tls.material().dnsLeafPem();
            assertEquals("ok", reason(signIn(ad, "localhost", DirectoryProfile.TrustMode.TRUST_PEM, leaf)));
        }
    }

    @Test
    void pemModeRefusesAnIpThatIsNotAnIpSan() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_DNS_LOCALHOST)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            String ca = ActiveDirectoryFixture.Tls.material().caPem();
            assertEquals("tls_trust_failed", reason(signIn(ad, "127.0.0.1", DirectoryProfile.TrustMode.TRUST_PEM, ca)));
        }
    }

    @Test
    void pemModeAcceptsAnIpSan() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_IP_LOOPBACK)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            String ca = ActiveDirectoryFixture.Tls.material().caPem();
            assertEquals("ok", reason(signIn(ad, "127.0.0.1", DirectoryProfile.TrustMode.TRUST_PEM, ca)));
        }
    }

    @Test
    void pemModeRefusesAnUnrelatedCa() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_DNS_LOCALHOST)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            String other = ActiveDirectoryFixture.Tls.material().otherCaPem();
            var result = signIn(ad, "localhost", DirectoryProfile.TrustMode.TRUST_PEM, other);
            assertEquals("tls_trust_failed", reason(result));
            assertEquals(LdapOperatorBindPort.FailureCodes.DIRECTORY_UNAVAILABLE, ((Result.Err<?>) result).code());
        }
    }

    @Test
    void plainLdapAgainstATlsListenerNeverSignsIn() throws Exception {
        try (var ad = ActiveDirectoryFixture.start(ActiveDirectoryFixture.Listener.TLS_DNS_LOCALHOST)) {
            ad.addUser("tls-user", "pw-tls", ActiveDirectoryFixture.ADMIN_GROUP);
            DirectoryProfile profile = ad.profile(List.of("127.0.0.1"), false, DirectoryProfile.TrustMode.TRUST_ANY, null);
            var result = new UnboundIdOperatorBindAdapter(profile, new DirectoryTrustPolicy(profile, () -> { }),
                    () -> ad.servicePassword.clone()).bind("tls-user", "pw-tls".toCharArray());
            assertFalse(result.isOk());
        }
    }
}
