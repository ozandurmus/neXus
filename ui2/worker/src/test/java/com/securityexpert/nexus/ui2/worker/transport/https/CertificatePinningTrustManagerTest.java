package com.securityexpert.nexus.ui2.worker.transport.https;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Certificate;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository.Decision;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateWarnings;

class CertificatePinningTrustManagerTest {
    private static X509Certificate leaf() throws Exception {
        var leaf = mock(X509Certificate.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(leaf.getEncoded()).thenReturn(new byte[]{1,2,3});
        when(leaf.getSubjectX500Principal()).thenReturn(new X500Principal("CN=FW-TANGO-04,O=Synthetic"));
        when(leaf.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Synthetic Issuer"));
        when(leaf.getNotAfter()).thenReturn(Date.from(Instant.parse("2030-01-01T00:00:00Z")));
        return leaf;
    }

    @Test void capturesOnlyLeafFingerprintAndMetadataAcrossHandshakeOverloads() throws Exception {
        AtomicReference<Certificate> seen = new AtomicReference<>();
        var manager = new CertificatePinningTrustManager(seen::set);
        var chain = new X509Certificate[]{leaf(), leaf()};
        manager.checkServerTrusted(chain, "RSA");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3})), seen.get().fingerprintSha256());
        assertEquals("FW-TANGO-04", seen.get().subjectCn());
        assertEquals("Synthetic Issuer", seen.get().issuerCn());
        manager.checkServerTrusted(chain, "RSA", (SSLEngine) null);
        manager.checkServerTrusted(chain, "RSA", (java.net.Socket) null);
        verify(chain[1], never()).getEncoded();
        assertThrows(CertificateException.class, () -> manager.checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    @Test void mismatchContinuesAndTransfersWarningFromTlsThreadToJobResult() throws Exception {
        var chain = new X509Certificate[]{leaf()};
        var target = new HttpsDeviceClient.Target("192.0.2.10", 8443);
        try (var scope = HttpsCertificateWarnings.open()) {
            var manager = new HttpsDeviceClient((observedTarget, cert) -> {
                assertEquals(target, observedTarget); return Decision.WARN;
            }).trustManager(target);
            var failure = new AtomicReference<Throwable>();
            Thread tls = new Thread(() -> {
                try { manager.checkServerTrusted(chain, "RSA"); } catch (Throwable t) { failure.set(t); }
            });
            tls.start(); tls.join();
            assertNull(failure.get());
            assertEquals("COMPLETED; certificate changed", HttpsCertificateWarnings.appendTo("COMPLETED"));
        }
        assertEquals("COMPLETED", HttpsCertificateWarnings.appendTo("COMPLETED"));
    }

    @Test void strictAndMissingPersistenceFailClosedWhileMatchAndFirstUseProceed() throws Exception {
        var chain = new X509Certificate[]{leaf()};
        var target = new HttpsDeviceClient.Target("192.0.2.10", 443);
        for (Decision decision : new Decision[]{Decision.MATCH, Decision.FIRST_USE})
            assertDoesNotThrow(() -> new HttpsDeviceClient((t,c) -> decision).trustManager(target).checkServerTrusted(chain, "RSA"));
        try (var scope = HttpsCertificateWarnings.open()) {
            assertThrows(CertificateException.class, () -> new HttpsDeviceClient((t,c) -> Decision.REFUSE)
                    .trustManager(target).checkServerTrusted(chain, "RSA"));
            assertTrue(HttpsCertificateWarnings.appendTo("FAILED").contains("certificate changed"));
        }
        assertThrows(CertificateException.class, () -> new HttpsDeviceClient().trustManager(target).checkServerTrusted(chain, "RSA"));
    }
}
