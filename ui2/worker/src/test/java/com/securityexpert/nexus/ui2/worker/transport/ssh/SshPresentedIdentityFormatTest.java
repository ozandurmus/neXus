package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.jcraft.jsch.HostKey;

/**
 * The presented identity must stay in the form every Check Point {@code recorded_identity_primary} was recorded in
 * (MD5 colon-hex, the {@code com.jcraft:jsch 0.1.55} default). The 2026-11-15 library upgrade silently changed the
 * default to {@code SHA256:<base64>} and every Check Point readiness run stopped at IDENTITY_NOT_VERIFIED; this pins the
 * format so a later upgrade cannot change it again.
 */
class SshPresentedIdentityFormatTest {

    @Test
    void md5ColonHexOfAKnownInputIsFixed() {
        // RFC 1321 test vector: MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("90:01:50:98:3c:d2:4f:b0:d6:96:3f:7d:28:e1:7f:72",
                SshTransportSession.md5ColonHex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theHostKeyBlobRoundTripsToTheSameIdentity() throws Exception {
        byte[] blob = syntheticRsaBlob();
        HostKey hostKey = new HostKey("host.example.invalid", blob);
        String fromHostKey = SshTransportSession.md5ColonHex(Base64.getDecoder().decode(hostKey.getKey()));
        assertEquals(SshTransportSession.md5ColonHex(blob), fromHostKey);
        assertTrue(fromHostKey.matches("([0-9a-f]{2}:){15}[0-9a-f]{2}"), fromHostKey);
    }

    private static byte[] syntheticRsaBlob() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.US_ASCII);
        out.writeInt(type.length);
        out.write(type);
        byte[] exponent = {0x01, 0x00, 0x01};
        out.writeInt(exponent.length);
        out.write(exponent);
        byte[] modulus = new byte[129];
        for (int i = 1; i < modulus.length; i++) {
            modulus[i] = (byte) (i * 7);
        }
        out.writeInt(modulus.length);
        out.write(modulus);
        return bytes.toByteArray();
    }
}
