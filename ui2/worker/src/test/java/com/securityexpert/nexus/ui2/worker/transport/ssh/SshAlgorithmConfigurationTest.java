package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshAlgorithmConfigurationTest {

    @Test
    void hostKeyAlgorithmsAllowRsaSha2Ed25519EcdsaAndLegacyRsaWithoutWeakDss() {
        List<String> algorithms = Arrays.asList(SshExecTransport.HOST_KEY_ALGORITHMS.split(","));

        assertEquals(
                List.of(
                        "rsa-sha2-512",
                        "rsa-sha2-256",
                        "ssh-rsa",
                        "ecdsa-sha2-nistp256",
                        "ecdsa-sha2-nistp384",
                        "ecdsa-sha2-nistp521",
                        "ssh-ed25519"),
                algorithms);
        assertFalse(algorithms.contains("ssh-dss"), "Weak ssh-dss host key algorithm must never be enabled");
        assertTrue(
                algorithms.indexOf("rsa-sha2-512") < algorithms.indexOf("ssh-rsa")
                        && algorithms.indexOf("rsa-sha2-256") < algorithms.indexOf("ssh-rsa"),
                "RFC 8332 rsa-sha2-* signatures must be preferred ahead of legacy ssh-rsa");
    }

    @Test
    void hostKeyAlgorithmsForPrioritizesPinnedTrustAlgorithmAndRejectsWeakDss() {
        assertEquals(
                SshExecTransport.HOST_KEY_ALGORITHMS,
                SshExecTransport.hostKeyAlgorithmsFor(Optional.empty()));
        assertEquals(
                SshExecTransport.HOST_KEY_ALGORITHMS,
                SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of())));

        String ed25519Pinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ssh-ed25519")));
        assertEquals(
                "ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521",
                ed25519Pinned);

        String ecdsaPinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ecdsa-sha2-nistp256")));
        assertEquals(
                "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519",
                ecdsaPinned);

        String rsaPinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ssh-rsa")));
        assertEquals(SshExecTransport.HOST_KEY_ALGORITHMS, rsaPinned);

        String dssIgnored = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ssh-dss")));
        assertFalse(Arrays.asList(dssIgnored.split(",")).contains("ssh-dss"));
    }

    @Test
    void kexAlgorithmsIncludeModernCurvesAndSha2GroupsPlusGroup14Sha1FallbackWithoutWeakGroup1OrGexSha1() {
        List<String> kex = Arrays.asList(SshExecTransport.KEX_ALGORITHMS.split(","));

        assertEquals(
                List.of(
                        "curve25519-sha256",
                        "curve25519-sha256@libssh.org",
                        "ecdh-sha2-nistp256",
                        "ecdh-sha2-nistp384",
                        "ecdh-sha2-nistp521",
                        "diffie-hellman-group-exchange-sha256",
                        "diffie-hellman-group16-sha512",
                        "diffie-hellman-group18-sha512",
                        "diffie-hellman-group14-sha256",
                        "diffie-hellman-group14-sha1"),
                kex);
        assertEquals("diffie-hellman-group14-sha1", kex.get(kex.size() - 1));
        assertFalse(kex.contains("diffie-hellman-group1-sha1"), "Weak 1024-bit group1-sha1 must not be enabled");
        assertFalse(kex.contains("diffie-hellman-group-exchange-sha1"), "Weak group-exchange-sha1 must not be enabled");
    }

    @Test
    void cipherAlgorithmsAllowGcmAndCtrWithoutWeakCbc3DesBlowfishOrArcfour() {
        List<String> ciphers = Arrays.asList(SshExecTransport.CIPHER_ALGORITHMS.split(","));

        assertEquals(
                List.of(
                        "aes128-gcm@openssh.com",
                        "aes256-gcm@openssh.com",
                        "aes128-ctr",
                        "aes192-ctr",
                        "aes256-ctr"),
                ciphers);

        Set<String> forbiddenWeakCiphers = Set.of(
                "aes128-cbc",
                "aes192-cbc",
                "aes256-cbc",
                "3des-cbc",
                "3des-ctr",
                "blowfish-cbc",
                "arcfour",
                "arcfour128",
                "arcfour256");
        for (String weak : forbiddenWeakCiphers) {
            assertFalse(ciphers.contains(weak), "Weak cipher must not be enabled: " + weak);
        }
    }

    @Test
    void configureSessionAlgorithmsAppliesListsAndResolvesImplementationsInMwiedeJsch() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("synthetic-operator", "192.0.2.10", 22);

        SshExecTransport.configureSessionAlgorithms(session, Optional.of(List.of("ssh-ed25519")));

        assertEquals("yes", session.getConfig("StrictHostKeyChecking"));
        assertEquals(
                "ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521",
                session.getConfig("server_host_key"));
        assertEquals(SshExecTransport.KEX_ALGORITHMS, session.getConfig("kex"));
        assertEquals(SshExecTransport.CIPHER_ALGORITHMS, session.getConfig("cipher.s2c"));
        assertEquals(SshExecTransport.CIPHER_ALGORITHMS, session.getConfig("cipher.c2s"));

        for (String alg : SshExecTransport.HOST_KEY_ALGORITHMS.split(",")) {
            String impl = session.getConfig(alg);
            assertNotNull(impl, "Missing JSch signature implementation for " + alg);
        }
        for (String alg : SshExecTransport.KEX_ALGORITHMS.split(",")) {
            String impl = session.getConfig(alg);
            assertNotNull(impl, "Missing JSch KEX implementation for " + alg);
        }
        for (String alg : SshExecTransport.CIPHER_ALGORITHMS.split(",")) {
            String impl = session.getConfig(alg);
            assertNotNull(impl, "Missing JSch cipher implementation for " + alg);
        }

        HostKey rsaKey = new HostKey("192.0.2.10", sshWireKeyBlob("ssh-rsa", new byte[]{1, 2, 3, 4}));
        assertEquals("ssh-rsa", rsaKey.getType());

        HostKey ed25519Key = new HostKey("192.0.2.10", sshWireKeyBlob("ssh-ed25519", new byte[32]));
        assertEquals("ssh-ed25519", ed25519Key.getType());
    }

    private static byte[] sshWireKeyBlob(String keyType, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        byte[] typeBytes = keyType.getBytes(StandardCharsets.US_ASCII);
        data.writeInt(typeBytes.length);
        data.write(typeBytes);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
        return out.toByteArray();
    }
}
