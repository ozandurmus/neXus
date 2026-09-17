package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.jcraft.jsch.HostKeyRepository;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.jobs.transport.*;

class PersistedManagementEndpointTrustResolverTest {
    private static final String HOST = "fixture-management";
    private static final int PORT = 2222;
    private static final String ALGORITHM = "ssh-rsa";
    private static final byte[] KEY = key();
    private static final String FINGERPRINT = fingerprint(KEY);

    private static class Store implements ManagementEndpointSshTrustRepository {
        String fingerprint = FINGERPRINT;
        public Optional<String> findActiveFingerprint(String address, int port, String algorithm) {
            return HOST.equals(address) && port == PORT && ALGORITHM.equals(algorithm)
                    ? Optional.ofNullable(fingerprint) : Optional.empty();
        }
        public List<String> findActiveAlgorithms(String address, int port) {
            return HOST.equals(address) && port == PORT && fingerprint != null ? List.of(ALGORITHM) : List.of();
        }
        public boolean enroll(String a, int p, String k, String f, String actor, Instant at, boolean re) {
            throw new AssertionError("a connection must never authorize trust");
        }
    }

    @Test
    void endpointPortAlgorithmAndRotationAreIsolatedAndEnrolledTrustIsUnchanged() {
        Store store = new Store();
        var resolver = new PersistedManagementEndpointTrustResolver(store, ref -> Optional.of("enrolled-pin"));
        var verifier = new HostKeyVerifier(resolver);
        String ref = PersistedManagementEndpointTrustResolver.scopeRef(HOST, PORT);
        assertTrue(verifier.isTrusted(ref, HOST, PORT, ALGORITHM, FINGERPRINT));
        assertFalse(verifier.isTrusted(ref, "other-management", PORT, ALGORITHM, FINGERPRINT));
        assertFalse(verifier.isTrusted(ref, HOST, 22, ALGORITHM, FINGERPRINT));
        assertFalse(verifier.isTrusted(ref, HOST, PORT, "ecdsa-sha2-nistp256", FINGERPRINT));
        assertFalse(verifier.isTrusted(ref, HOST, PORT, ALGORITHM, "rotated-key"));
        assertTrue(verifier.isTrusted("enrolled-rule", "other", 22, "ssh-rsa", "enrolled-pin"));
        assertEquals(FINGERPRINT, store.fingerprint);
    }

    @Test
    void missingEntryRefusesBeforeCredentialResolutionOrSocketOrCommands() {
        Store store = new Store();
        store.fingerprint = null;
        var transport = new SshExecTransport(ref -> { throw new AssertionError("credential resolution reached"); },
                new PersistedManagementEndpointTrustResolver(store, ref -> Optional.empty()));
        var result = transport.connect(new ConnectionTarget(HOST, HOST, PORT),
                new ConnectSpec("fixture-credential", PersistedManagementEndpointTrustResolver.scopeRef(HOST, PORT), Optional.empty()),
                Duration.ofSeconds(1));
        assertEquals("TRUST_ENTRY_MISSING", assertInstanceOf(ConnectResult.HostKeyRejected.class, result).reason());
    }

    @Test
    void missingDiscoveryTrustFallsBackToEnrolledDeviceTrust() {
        Store store = new Store();
        store.fingerprint = null;
        var resolver = new PersistedManagementEndpointTrustResolver(store, ref -> Optional.of(FINGERPRINT));
        var verifier = new HostKeyVerifier(resolver);
        String ref = PersistedManagementEndpointTrustResolver.scopeRef(HOST, PORT);

        assertTrue(verifier.isTrusted(ref, HOST, PORT, ALGORITHM, FINGERPRINT));
        assertFalse(verifier.isTrusted(ref, HOST, PORT, ALGORITHM, "rotated-key"));
    }

    @Test
    void keyExchangeHookRejectsRotationAndUnauthorizedAlgorithmWithoutTofu() throws Exception {
        Store store = new Store();
        var transport = new SshExecTransport(ref -> { throw new AssertionError("credentials reached"); },
                new PersistedManagementEndpointTrustResolver(store, ref -> Optional.empty()));
        var method = SshExecTransport.class.getDeclaredMethod("trustedOnlyRepository", String.class,
                ConnectionTarget.class, String[].class, boolean[].class);
        method.setAccessible(true);
        String[] failure = {null};
        boolean[] trusted = {false};
        var hook = (HostKeyRepository) method.invoke(transport,
                PersistedManagementEndpointTrustResolver.scopeRef(HOST, PORT),
                new ConnectionTarget(HOST, HOST, PORT), failure, trusted);
        assertEquals(ALGORITHM, new com.jcraft.jsch.HostKey(HOST, KEY).getType());
        assertEquals(HostKeyRepository.OK, hook.check(HOST, KEY));
        assertTrue(trusted[0]);
        assertEquals(HostKeyRepository.NOT_INCLUDED, hook.check(HOST, key("ecdsa-sha2-nistp256")));
        assertEquals("TRUST_ENTRY_MISSING", failure[0]);
        store.fingerprint = "0".repeat(64);
        assertEquals(HostKeyRepository.NOT_INCLUDED, hook.check(HOST, KEY));
        assertEquals("host_key_mismatch: " + FINGERPRINT, failure[0]);
        assertFalse(trusted[0]);
        store.fingerprint = null;
        hook.add(new com.jcraft.jsch.HostKey(HOST, KEY), null);
        assertEquals(HostKeyRepository.NOT_INCLUDED, hook.check(HOST, KEY));
        assertEquals("TRUST_ENTRY_MISSING", failure[0]);
        assertNull(store.fingerprint);
    }

    @Test
    void discoveryFailuresUseTrustDecisionAndNetworkCauseWithoutInventingAuthenticationProof() {
        var raw = new com.jcraft.jsch.JSchException("raw-sensitive-error");
        assertEquals("TRUST_MISMATCH", assertInstanceOf(ConnectResult.HostKeyRejected.class,
                SshExecTransport.discoveryFailure(raw, "TRUST_MISMATCH", false)).reason());
        assertEquals("AUTH_FAILED", assertInstanceOf(ConnectResult.AuthenticationFailed.class,
                SshExecTransport.discoveryFailure(new com.jcraft.jsch.JSchException("Auth fail"), null, true)).reason());
        var timeout = new com.jcraft.jsch.JSchException("raw-sensitive-error", new java.net.SocketTimeoutException());
        assertInstanceOf(ConnectResult.TimedOut.class, SshExecTransport.discoveryFailure(timeout, null, false));
        assertEquals("NOT_EVALUABLE", assertInstanceOf(ConnectResult.AuthenticationFailed.class,
                SshExecTransport.discoveryFailure(timeout, null, true)).reason());
        assertEquals("NOT_EVALUABLE", assertInstanceOf(ConnectResult.AuthenticationFailed.class,
                SshExecTransport.discoveryFailure(raw, null, true)).reason());
    }

    @Test
    void workerAndStandalonePoliciesResolvePersistedDiscoveryIdentically() {
        Store store = new Store();
        var worker = new HostKeyVerifier(new PersistedManagementEndpointTrustResolver(store, ref -> Optional.of(FINGERPRINT)));
        var cli = new HostKeyVerifier(new PersistedManagementEndpointTrustResolver(store, ref -> Optional.empty()));
        for (String host : List.of(HOST, "other-management")) {
            for (String pin : List.of(FINGERPRINT, "0".repeat(64))) {
                String ref = PersistedManagementEndpointTrustResolver.scopeRef(host, PORT);
                assertEquals(worker.verify(ref, host, PORT, ALGORITHM, pin), cli.verify(ref, host, PORT, ALGORITHM, pin));
            }
        }
        store.fingerprint = null;
        String ref = PersistedManagementEndpointTrustResolver.scopeRef(HOST, PORT);
        assertEquals(HostKeyVerifier.Decision.MISSING, worker.verify(ref, HOST, PORT, ALGORITHM, FINGERPRINT));
        assertEquals(HostKeyVerifier.Decision.MISSING, cli.verify(ref, HOST, PORT, ALGORITHM, FINGERPRINT));
    }

    private static byte[] key() { return key(ALGORITHM); }
    private static byte[] key(String keyAlgorithm) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            byte[] algorithm = keyAlgorithm.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(algorithm.length); out.write(algorithm);
            out.writeInt(32); out.write(new byte[32]);
            return bytes.toByteArray();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static String fingerprint(byte[] key) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
