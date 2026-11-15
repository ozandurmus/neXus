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
    void hostKeyAlgorithmsAllowRsaSha2Ed25519EcdsaAndLegacyRsaWithDssLastResort() {
        List<String> algorithms = Arrays.asList(SshExecTransport.HOST_KEY_ALGORITHMS.split(","));

        assertEquals(
                List.of(
                        "rsa-sha2-512",
                        "rsa-sha2-256",
                        "ssh-rsa",
                        "ecdsa-sha2-nistp256",
                        "ecdsa-sha2-nistp384",
                        "ecdsa-sha2-nistp521",
                        "ssh-ed25519",
                        "ssh-dss"),
                algorithms);
        assertEquals("ssh-dss", algorithms.get(algorithms.size() - 1), "ssh-dss is last resort only");
        assertTrue(
                algorithms.indexOf("rsa-sha2-512") < algorithms.indexOf("ssh-rsa")
                        && algorithms.indexOf("rsa-sha2-256") < algorithms.indexOf("ssh-rsa"),
                "RFC 8332 rsa-sha2-* signatures must be preferred ahead of legacy ssh-rsa");
    }

    @Test
    void hostKeyAlgorithmsForPrioritizesPinnedTrustAlgorithmAndKeepsDssLast() {
        assertEquals(
                SshExecTransport.HOST_KEY_ALGORITHMS,
                SshExecTransport.hostKeyAlgorithmsFor(Optional.empty()));
        assertEquals(
                SshExecTransport.HOST_KEY_ALGORITHMS,
                SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of())));

        String ed25519Pinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ssh-ed25519")));
        assertEquals(
                "ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-dss",
                ed25519Pinned);

        String ecdsaPinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ecdsa-sha2-nistp256")));
        assertEquals(
                "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519,ssh-dss",
                ecdsaPinned);

        String rsaPinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ssh-rsa")));
        assertEquals(SshExecTransport.HOST_KEY_ALGORITHMS, rsaPinned);

        String dssPinned = SshExecTransport.hostKeyAlgorithmsFor(Optional.of(List.of("ssh-dss")));
        assertTrue(dssPinned.startsWith("ssh-dss,rsa-sha2-512"));
    }

    @Test
    void kexAlgorithmsIncludeModernCurvesAndSha2GroupsPlusLegacySha1Fallbacks() {
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
                        "diffie-hellman-group14-sha1",
                        "diffie-hellman-group-exchange-sha1",
                        "diffie-hellman-group1-sha1"),
                kex);
        assertTrue(kex.indexOf("diffie-hellman-group14-sha256") < kex.indexOf("diffie-hellman-group14-sha1"));
    }

    @Test
    void cipherAlgorithmsPreferGcmAndCtrWithLegacyCbcOnlyAsLastResort() {
        List<String> ciphers = Arrays.asList(SshExecTransport.CIPHER_ALGORITHMS.split(","));

        assertEquals(
                List.of(
                        "aes128-gcm@openssh.com",
                        "aes256-gcm@openssh.com",
                        "aes128-ctr",
                        "aes192-ctr",
                        "aes256-ctr",
                        "aes256-cbc",
                        "aes192-cbc",
                        "aes128-cbc",
                        "3des-cbc",
                        "3des-ctr",
                        "blowfish-cbc"),
                ciphers);
        for (String arcfour : Set.of("arcfour", "arcfour128", "arcfour256")) {
            assertFalse(ciphers.contains(arcfour), "Arcfour must not be enabled: " + arcfour);
        }
    }

    @Test
    void macAndPubkeyAlgorithmsAreModernFirstWithLegacyFallbacks() {
        List<String> macs = Arrays.asList(SshExecTransport.MAC_ALGORITHMS.split(","));
        assertEquals("hmac-sha2-256-etm@openssh.com", macs.get(0));
        assertTrue(macs.indexOf("hmac-sha2-512") < macs.indexOf("hmac-sha1"));
        assertEquals(List.of("hmac-sha1", "hmac-sha1-96", "hmac-md5", "hmac-md5-96"),
                macs.subList(macs.indexOf("hmac-sha1"), macs.size()));
        List<String> pk = Arrays.asList(SshExecTransport.PUBKEY_ACCEPTED_ALGORITHMS.split(","));
        assertEquals(List.of("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"), pk.subList(0, 3));
        assertTrue(pk.containsAll(List.of("ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-ed25519")));
    }

    /**
     * Defaults of com.jcraft:jsch 0.1.55 (JSch.java static config), hardcoded. Everything the old library could
     * negotiate must remain negotiable (PO rule: hardening must never make a working connection fail).
     */
    @Test
    void everyJsch0155DefaultAlgorithmRemainsConfigured() {
        List<String> kex = List.of("ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
                "diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha256",
                "diffie-hellman-group-exchange-sha1", "diffie-hellman-group1-sha1");
        List<String> hostKey = List.of("ssh-rsa", "ssh-dss", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384",
                "ecdsa-sha2-nistp521");
        List<String> cipher = List.of("aes128-ctr", "aes128-cbc", "3des-ctr", "3des-cbc", "blowfish-cbc",
                "aes192-ctr", "aes192-cbc", "aes256-ctr", "aes256-cbc");
        List<String> mac = List.of("hmac-md5", "hmac-sha1", "hmac-sha2-256", "hmac-sha1-96", "hmac-md5-96");

        assertTrue(Arrays.asList(SshExecTransport.KEX_ALGORITHMS.split(",")).containsAll(kex));
        assertTrue(Arrays.asList(SshExecTransport.HOST_KEY_ALGORITHMS.split(",")).containsAll(hostKey));
        assertTrue(Arrays.asList(SshExecTransport.CIPHER_ALGORITHMS.split(",")).containsAll(cipher));
        assertTrue(Arrays.asList(SshExecTransport.MAC_ALGORITHMS.split(",")).containsAll(mac));
    }

    @Test
    void legacySingleFingerprintTrustPathKeepsRsaFirstLikeJsch0155() {
        // Capability-level refs (utils.cp_ssh_trust / utils.pan_ssh_trust) resolve with no algorithm metadata.
        String legacy = SshExecTransport.hostKeyAlgorithmsFor(Optional.empty());
        List<String> order = Arrays.asList(legacy.split(","));
        assertEquals(List.of("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"), order.subList(0, 3));
        assertTrue(order.indexOf("ssh-rsa") < order.indexOf("ecdsa-sha2-nistp256"));
        assertTrue(order.indexOf("ecdsa-sha2-nistp521") < order.indexOf("ssh-ed25519"));
        assertTrue(order.indexOf("ssh-ed25519") < order.indexOf("ssh-dss"));
    }

    @Test
    void configureSessionAlgorithmsAppliesListsAndResolvesImplementationsInMwiedeJsch() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("synthetic-operator", "192.0.2.10", 22);

        SshExecTransport.configureSessionAlgorithms(session, Optional.of(List.of("ssh-ed25519")));

        assertEquals("yes", session.getConfig("StrictHostKeyChecking"));
        assertEquals(
                "ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-dss",
                session.getConfig("server_host_key"));
        assertEquals(SshExecTransport.KEX_ALGORITHMS, session.getConfig("kex"));
        assertEquals(SshExecTransport.CIPHER_ALGORITHMS, session.getConfig("cipher.s2c"));
        assertEquals(SshExecTransport.CIPHER_ALGORITHMS, session.getConfig("cipher.c2s"));
        assertEquals(SshExecTransport.MAC_ALGORITHMS, session.getConfig("mac.s2c"));
        assertEquals(SshExecTransport.MAC_ALGORITHMS, session.getConfig("mac.c2s"));
        assertEquals(SshExecTransport.PUBKEY_ACCEPTED_ALGORITHMS, session.getConfig("PubkeyAcceptedAlgorithms"));

        for (String alg : SshExecTransport.HOST_KEY_ALGORITHMS.split(",")) {
            String impl = session.getConfig(alg);
            assertNotNull(impl, "Missing JSch signature implementation for " + alg);
        }
        for (String alg : SshExecTransport.KEX_ALGORITHMS.split(",")) {
            String impl = session.getConfig(alg);
            assertNotNull(impl, "Missing JSch KEX implementation for " + alg);
        }
        for (String alg : SshExecTransport.MAC_ALGORITHMS.split(",")) {
            assertNotNull(session.getConfig(alg), "Missing JSch MAC implementation for " + alg);
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
