package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

class PanXmlApiTransportTest {

    private static final String ROOT = "MIIC+jCCAeICCQC349mEo7SxFDANBgkqhkiG9w0BAQsFADA/MSEwHwYDVQQDDBhTeW50aGV0aWMgQ29ycG9yYXRlIFJvb3QxGjAYBgNVBAoMEUV4YW1wbGUgVHJ1c3QgTGFiMB4XDTI2MDkyMTA2MDAxN1oXDTM2MDkxODA2MDAxN1owPzEhMB8GA1UEAwwYU3ludGhldGljIENvcnBvcmF0ZSBSb290MRowGAYDVQQKDBFFeGFtcGxlIFRydXN0IExhYjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAPlc6xB7/egGON2pwbi2qiDSUTZfV4gYTNSoteHvM2N904XgMUjEiTrFjdmObn+dP+tIEf0UQUv6i2dWI2c+8eI4iwKP96xAFPphYNm3wV+hZHEdntGHAq8mOpwlv39dkQi+f3vu9twQtaaF6Y0n/YeVmD+xLfNUYbKjNgG+8B51iuHapVj3tCm5IH7XSzZHWnt9Rm7bkFkGh5v9XsvFiRi1BeZRrnI+Js5t3euGU/udejlQf76O7H2o+QFXCNSm5Whv+Q+HmURcQEXwh8O0kegA1F9rEcaKnKyElISTvqT/0lM8TYnvkg8nkshaAX5j0HEw0pdh8Vn17hVjx29UaMUCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAoy8/a3PSrIv4Le6sb0NkEQPCJv9pYJ2wnjL/ckSbz11hc6hsdTVhvjEvFwt9ZU9tspEa2jQyh3pm6/aWM7nfcpvju6si+d1LyKdp+WbrZw7+WsISrVEUQTVULf4IipQJwsx3eH+VuGCxWhPm0I1tk+vslNMzpSXoMKcLn/B1F0wPGSlRsEgq1xN+ZHQY053cnY88JnOUg5HleOW5ycKMhB4T+4Ma3htKfbij4M5l2gTWTkvW+nIqpcZan24/yLNFUK/wuh3oExqeDV4cdX08e+8CA6ObhdMQLHYCBGuE0KqfIO499/45p/DxC/Ks3TysMoBfDLwGGfNX3f9dK5O9qw==";
    private static final String VALID = "MIIDJTCCAg2gAwIBAgIJAOsPpoP9t0QYMA0GCSqGSIb3DQEBCwUAMD8xITAfBgNVBAMMGFN5bnRoZXRpYyBDb3Jwb3JhdGUgUm9vdDEaMBgGA1UECgwRRXhhbXBsZSBUcnVzdCBMYWIwHhcNMjYwOTIxMDYwMDE3WhcNMzYwOTE4MDYwMDE3WjA0MRMwEQYDVQQDDAoxOTIuMC4yLjEwMR0wGwYDVQQKDBRFeGFtcGxlIEZpcmV3YWxsIExhYjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJygvZac0jz0C+BfnQRUG1vg/kE5pBJLGrbohH28nO8r3EYbfGNaXyDFB6r9Odh4DZST3kk3y3bzSv0z8mQraYNO0IH/tmrzR8pz1T/2LqT4flcd6KLRAGTa74DZ6A7BY8bvHp3LhaN0PgrDuIMb1kkkOsH5cSsQFnQAWWNmYDbkyvwBjL9OUTDGs6SwHIw8vughN4lStsF8rCoJHobBYCXLaQgfX1CbfGxFtL1TClhpg757YN8t2lnvdiDnGaHjCATEE8mnefrcyXZOPvzjwSaJRRYLMj+/0BJE1g6Q1kQED5El8lJ3QGCt4LztTvcSTthPQ/mTFUe2VJFu2cwwWqsCAwEAAaMvMC0wCQYDVR0TBAIwADALBgNVHQ8EBAMCBaAwEwYDVR0lBAwwCgYIKwYBBQUHAwEwDQYJKoZIhvcNAQELBQADggEBAE1ATFIxXP6tqckBZI86WFHhMpIH8KRlJ9R7+iWZpnQeQgQvZaVb+z+vr4E6t9rmaYQMuiR+s4f2aiASzUYRAJDZ0f2dxRqknVw5cnV4W3NErQ2G2Lpt9S7yp97rqG9s7y3SUNbBa15Yv9yStnJdJQKsLxT7HSPrahjt5jb7CblmiFg6vZyBvDfLe4SvUnyNfzXmSwuFNgIZJFNQzwHdYfQlWetRmcsFuQLkcXjqmYZ6U4XnSIQazMD0iBUl6ne7WD9IpdRlGNFcVJ+KjRrAvJtxsLzqnFd6bel5VA2Pi22zQOa1qtgTqJuLxnGAqhjUeH+22yN74L76jUIT1gHcmRM=";
    private static final String EXPIRED = "MIIDHjCCAgagAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwPzEhMB8GA1UEAwwYU3ludGhldGljIENvcnBvcmF0ZSBSb290MRowGAYDVQQKDBFFeGFtcGxlIFRydXN0IExhYjAeFw0yMDAxMDEwMDAwMDBaFw0yMTAxMDEwMDAwMDBaMDQxEzARBgNVBAMMCjE5Mi4wLjIuMTExHTAbBgNVBAoMFEV4YW1wbGUgRmlyZXdhbGwgTGFiMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvNE5+/6a4yO0TTYsRlu0uxq52HpeYiH75oiVqKJIn85SDPLbdUo6QSk2vgLxgcLdMdvq4I5c/uRuLk2XOn8oIGBZvgID0bS5lAgClmkk/5BNyQ+MIugPmh7l5XghLfT04+FN15O2OG7WwdcuXge55wNj6mVQet7XNVSMSHFWcMXulEcw4S5Qk55Ry2j5Gw6VSlSR6fgqybHkE1925zTo27+IOBsk9qw4Erbqo7dGIW+JscujImnBW6iSYvMSpNIuktkQr8mKUwfVXEiKcQeRw41PwDQWMQWS5pVRIScxIDu+w+LRoRjhD5GkjsxoRwMae4ixqj8chnNE8K4q6ALhnwIDAQABoy8wLTAJBgNVHRMEAjAAMAsGA1UdDwQEAwIFoDATBgNVHSUEDDAKBggrBgEFBQcDATANBgkqhkiG9w0BAQsFAAOCAQEAJY+uxn2tm2wDlE62iGswrbygZ1cs76cLHgEeAQR98OQ6bo6MQTs36nE5su+P1wDvipQs6wBA5Pmg95ALNRTIJ6AFCfhKETA5BfwRqnP3b5M9H0zKX1TJGb7XH4UFKzYW0pxy8KX4kIH2aHTTLwn3fhjF4dAHuEBK6wAcwMGkzgwbJ23c55NjWAuBYE0eIyaPI2EoLT5HDLLsmq154j62QzW6cZz39ZHqe0BlMClHZ6rbwGUey68WaevOeYQ+sHUxnunthD/ZaEw/C05l4QbythNUNVqUuoEwstWfwb/K+WPfp2QO9yMvSS4Olnc2S/25XQybmEZDFGrni7eMu3OCog==";

    @Test
    void unresolvableTrustFailsGracefullyWithoutThrowing() {
        PanXmlApiTransport transport = new PanXmlApiTransport("unresolved-ref", ref -> new TrustResolution.Unresolved());
        ApiTarget target = new ApiTarget("ep-1", "10.0.0.1");
        XmlApiSpec spec = new XmlApiSpec("POST", "op", "", "", Map.of("cmd", "<show/>"), Map.of());

        XmlApiResult result = transport.xmlApiCall(target, spec, Duration.ofSeconds(1));

        assertInstanceOf(XmlApiResult.Failed.class, result);
        XmlApiResult.Failed failed = (XmlApiResult.Failed) result;
        assertTrue(failed.reason().contains("trust rule could not be resolved"));
    }

    @Test
    void bareIpTargetIsHandledGracefully() {
        PanXmlApiTransport transport = new PanXmlApiTransport("unresolved-ref", ref -> new TrustResolution.Unresolved());
        ApiTarget target = new ApiTarget("ep-1", "10.241.236.113");
        XmlApiSpec spec = new XmlApiSpec("POST", "op", "", "", Map.of("cmd", "<show/>"), Map.of());

        XmlApiResult result = transport.xmlApiCall(target, spec, Duration.ofSeconds(1));
        assertInstanceOf(XmlApiResult.Failed.class, result);
    }

    @Test
    void paloAltoDeviceTrustResolvesUsableTransport() {
        PanXmlApiTransport transport = new PanXmlApiTransport("device-trust-ref",
                ref -> new TrustResolution.PaloAltoDeviceTrust(Optional.empty()));
        assertNotNull(transport);
    }

    @Test
    void platformTrustedNonPaloAltoCertificateIsAccepted() throws Exception {
        X509ExtendedTrustManager manager = PanXmlApiTransport.paloAltoDeviceTrustManager(
                Optional.empty(), trustManager(certificate(ROOT)));

        manager.checkServerTrusted(new X509Certificate[] { certificate(VALID), certificate(ROOT) }, "RSA");
    }

    @Test
    void certificateOutsidePlatformTrustIsRejectedWithFingerprintAndNoDistinguishedName() throws Exception {
        X509Certificate leaf = certificate(VALID);
        X509ExtendedTrustManager manager = PanXmlApiTransport.paloAltoDeviceTrustManager(
                Optional.empty(), trustManager(certificate(EXPIRED)));

        CertificateException failure = assertThrows(CertificateException.class,
                () -> manager.checkServerTrusted(new X509Certificate[] { leaf, certificate(ROOT) }, "RSA"));

        assertEquals("server certificate unverifiable; fingerprint_sha256=" + fingerprint(leaf), failure.getMessage());
        assertEquals(failure.getMessage(), PanXmlApiTransport.certificateFailureReason(new IOException(failure)));
        assertFalse(failure.getMessage().contains("CN="));
        assertFalse(failure.getMessage().contains("O="));
    }

    @Test
    void expiredCertificateIsRejected() throws Exception {
        X509ExtendedTrustManager manager = PanXmlApiTransport.paloAltoDeviceTrustManager(
                Optional.empty(), trustManager(certificate(ROOT)));

        assertThrows(CertificateException.class,
                () -> manager.checkServerTrusted(
                        new X509Certificate[] { certificate(EXPIRED), certificate(ROOT) }, "RSA"));
    }

    @Test
    void matchingFingerprintStillShortCircuitsPlatformVerification() throws Exception {
        X509Certificate leaf = certificate(VALID);
        X509ExtendedTrustManager rejectingPlatform = trustManager(certificate(EXPIRED));
        X509ExtendedTrustManager manager = PanXmlApiTransport.paloAltoDeviceTrustManager(
                Optional.of(fingerprint(leaf)), rejectingPlatform);

        manager.checkServerTrusted(new X509Certificate[] { leaf }, "RSA");
    }

    private static X509Certificate certificate(String base64Der) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64Der)));
    }

    private static X509ExtendedTrustManager trustManager(X509Certificate... trusted) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int i = 0; i < trusted.length; i++) {
            store.setCertificateEntry("trusted-" + i, trusted[i]);
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager extended) {
                return extended;
            }
        }
        throw new IllegalStateException("test runtime did not provide an X.509 trust manager");
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }
}
