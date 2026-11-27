package com.securityexpert.nexus.ui2.service.eventintake.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.eventintake.tls.MtlsCertificatePinVerifier.Outcome;
import com.securityexpert.nexus.ui2.service.eventintake.tls.MtlsCertificatePinVerifier.PinConfig;

class MtlsCertificatePinVerifierTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final Instant NEXT_VALID_UNTIL = Instant.parse("2026-11-27T11:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final byte[] SYNTHETIC_DER_CURRENT =
            "synthetic-der-certificate-fixture-current-01".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SYNTHETIC_DER_NEXT =
            "synthetic-der-certificate-fixture-next-02".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SYNTHETIC_DER_OTHER =
            "synthetic-der-certificate-fixture-other-99".getBytes(StandardCharsets.UTF_8);

    private static final String CURRENT_PIN_LOWER = sha256HexLower(SYNTHETIC_DER_CURRENT);
    private static final String NEXT_PIN_LOWER = sha256HexLower(SYNTHETIC_DER_NEXT);
    private static final String OTHER_PIN_LOWER = sha256HexLower(SYNTHETIC_DER_OTHER);

    private final MtlsCertificatePinVerifier verifier = new MtlsCertificatePinVerifier(FIXED_CLOCK);

    @Test
    void matchesCurrentPinForDerBytesAndSelfSignedX509Certificate() throws Exception {
        PinConfig derConfig = PinConfig.of(CURRENT_PIN_LOWER);
        Outcome derOutcome = verifier.verify(derConfig, SYNTHETIC_DER_CURRENT, FIXED_NOW);
        assertEquals(Outcome.MATCH_CURRENT, derOutcome);
        assertTrue(derOutcome.isPermitted());
        assertEquals(Outcome.MATCH_CURRENT, verifier.verify(derConfig, SYNTHETIC_DER_CURRENT));

        X509Certificate selfSignedCurrent = generateSyntheticSelfSignedCert("synthetic-emitter-current");
        String certPin = sha256HexLower(selfSignedCurrent.getEncoded());
        PinConfig x509Config = PinConfig.of(certPin);

        Outcome x509Outcome = verifier.verify(x509Config, selfSignedCurrent, FIXED_NOW);
        assertEquals(Outcome.MATCH_CURRENT, x509Outcome);
        assertTrue(x509Outcome.isPermitted());
        assertEquals(Outcome.MATCH_CURRENT, verifier.verify(x509Config, selfSignedCurrent));
    }

    @Test
    void matchesNextPinBeforeAndAtExactValidityBoundaryAndRejectsOneNanosecondAfter() throws Exception {
        PinConfig config = PinConfig.of(CURRENT_PIN_LOWER, NEXT_PIN_LOWER, NEXT_VALID_UNTIL);

        Outcome before = verifier.verify(config, SYNTHETIC_DER_NEXT, NEXT_VALID_UNTIL.minusNanos(1));
        assertEquals(Outcome.MATCH_NEXT, before);
        assertTrue(before.isPermitted());

        Outcome exact = verifier.verify(config, SYNTHETIC_DER_NEXT, NEXT_VALID_UNTIL);
        assertEquals(Outcome.MATCH_NEXT, exact);
        assertTrue(exact.isPermitted());

        Outcome expired = verifier.verify(config, SYNTHETIC_DER_NEXT, NEXT_VALID_UNTIL.plusNanos(1));
        assertEquals(Outcome.EXPIRED_NEXT_PIN, expired);
        assertFalse(expired.isPermitted());

        Outcome nullNow = verifier.verify(config, SYNTHETIC_DER_NEXT, null);
        assertEquals(Outcome.EXPIRED_NEXT_PIN, nullNow);
        assertFalse(nullNow.isPermitted());

        // Also exercise real X509Certificate path across next boundary
        X509Certificate currentCert = generateSyntheticSelfSignedCert("synthetic-x509-current");
        X509Certificate nextCert = generateSyntheticSelfSignedCert("synthetic-x509-next");
        PinConfig x509Config = PinConfig.of(
                sha256HexLower(currentCert.getEncoded()),
                sha256HexLower(nextCert.getEncoded()),
                NEXT_VALID_UNTIL);

        assertEquals(Outcome.MATCH_NEXT, verifier.verify(x509Config, nextCert, NEXT_VALID_UNTIL));
        assertEquals(Outcome.EXPIRED_NEXT_PIN, verifier.verify(x509Config, nextCert, NEXT_VALID_UNTIL.plusNanos(1)));
    }

    @Test
    void currentPinStillSucceedsWhenNextPinIsExpiredOrNowIsNull() {
        PinConfig config = PinConfig.of(CURRENT_PIN_LOWER, NEXT_PIN_LOWER, NEXT_VALID_UNTIL);

        Outcome afterNextExpiry = verifier.verify(config, SYNTHETIC_DER_CURRENT, NEXT_VALID_UNTIL.plusSeconds(3600));
        assertEquals(Outcome.MATCH_CURRENT, afterNextExpiry);
        assertTrue(afterNextExpiry.isPermitted());

        Outcome withNullNow = verifier.verify(config, SYNTHETIC_DER_CURRENT, null);
        assertEquals(Outcome.MATCH_CURRENT, withNullNow);
        assertTrue(withNullNow.isPermitted());
    }

    @Test
    void supportsUppercaseLowercaseAndMixedCaseHexEquivalence() {
        String currentUpper = CURRENT_PIN_LOWER.toUpperCase(Locale.ROOT);
        String nextUpper = NEXT_PIN_LOWER.toUpperCase(Locale.ROOT);
        String currentMixed = toAlternatingCase(CURRENT_PIN_LOWER);

        PinConfig upperConfig = PinConfig.of(currentUpper, nextUpper, NEXT_VALID_UNTIL);
        assertEquals(Outcome.MATCH_CURRENT, verifier.verify(upperConfig, SYNTHETIC_DER_CURRENT, FIXED_NOW));
        assertEquals(Outcome.MATCH_NEXT, verifier.verify(upperConfig, SYNTHETIC_DER_NEXT, FIXED_NOW));

        PinConfig mixedConfig = PinConfig.of(currentMixed);
        assertEquals(Outcome.MATCH_CURRENT, verifier.verify(mixedConfig, SYNTHETIC_DER_CURRENT, FIXED_NOW));
    }

    @Test
    void returnsPinMismatchForUnpinnedCertificate() throws Exception {
        PinConfig config = PinConfig.of(CURRENT_PIN_LOWER, NEXT_PIN_LOWER, NEXT_VALID_UNTIL);

        Outcome derMismatch = verifier.verify(config, SYNTHETIC_DER_OTHER, FIXED_NOW);
        assertEquals(Outcome.PIN_MISMATCH, derMismatch);
        assertFalse(derMismatch.isPermitted());

        X509Certificate otherCert = generateSyntheticSelfSignedCert("synthetic-emitter-unpinned");
        Outcome x509Mismatch = verifier.verify(config, otherCert, FIXED_NOW);
        assertEquals(Outcome.PIN_MISMATCH, x509Mismatch);
        assertFalse(x509Mismatch.isPermitted());
    }

    @Test
    void returnsMissingCertificateForNullEmptyOrUnencodableCertificate() {
        PinConfig config = PinConfig.of(CURRENT_PIN_LOWER);

        assertEquals(Outcome.MISSING_CERTIFICATE, verifier.verify(config, (byte[]) null, FIXED_NOW));
        assertEquals(Outcome.MISSING_CERTIFICATE, verifier.verify(config, new byte[0], FIXED_NOW));
        assertEquals(Outcome.MISSING_CERTIFICATE, verifier.verify(config, (X509Certificate) null, FIXED_NOW));
        assertEquals(Outcome.MISSING_CERTIFICATE, verifier.verify(config, new StubX509Certificate(null, false), FIXED_NOW));
        assertEquals(Outcome.MISSING_CERTIFICATE, verifier.verify(config, new StubX509Certificate(new byte[0], false), FIXED_NOW));
        assertEquals(Outcome.MISSING_CERTIFICATE, verifier.verify(config, new StubX509Certificate(SYNTHETIC_DER_CURRENT, true), FIXED_NOW));
        assertFalse(Outcome.MISSING_CERTIFICATE.isPermitted());
    }

    @Test
    void treatsNullOrBlankNextPinAsAbsent() {
        PinConfig nullNext = new PinConfig(CURRENT_PIN_LOWER, null, null);
        PinConfig emptyNext = new PinConfig(CURRENT_PIN_LOWER, "", null);
        PinConfig blankNext = new PinConfig(CURRENT_PIN_LOWER, "   ", null);

        for (PinConfig config : List.of(nullNext, emptyNext, blankNext)) {
            assertEquals(Outcome.MATCH_CURRENT, verifier.verify(config, SYNTHETIC_DER_CURRENT, FIXED_NOW));
            assertEquals(Outcome.PIN_MISMATCH, verifier.verify(config, SYNTHETIC_DER_NEXT, FIXED_NOW));
        }
    }

    @Test
    void returnsInvalidPinConfigForMalformedPinsOrMissingNextValidUntil() {
        assertEquals(Outcome.INVALID_PIN_CONFIG, verifier.verify(null, SYNTHETIC_DER_CURRENT, FIXED_NOW));
        assertEquals(Outcome.INVALID_PIN_CONFIG, verifier.verify(null, (X509Certificate) null, FIXED_NOW));
        assertFalse(Outcome.INVALID_PIN_CONFIG.isPermitted());

        List<String> invalidCurrentPins = List.of(
                "",
                "   ",
                CURRENT_PIN_LOWER.substring(0, 63),
                CURRENT_PIN_LOWER + "0",
                " " + CURRENT_PIN_LOWER,
                CURRENT_PIN_LOWER + " ",
                "g" + CURRENT_PIN_LOWER.substring(1),
                "Z" + CURRENT_PIN_LOWER.substring(1),
                "sha256:" + CURRENT_PIN_LOWER,
                CURRENT_PIN_LOWER.substring(0, 32) + ":" + CURRENT_PIN_LOWER.substring(33));

        // Null current pin
        assertEquals(
                Outcome.INVALID_PIN_CONFIG,
                verifier.verify(new PinConfig(null, null, null), SYNTHETIC_DER_CURRENT, FIXED_NOW));

        for (String badCurrent : invalidCurrentPins) {
            PinConfig badConfig = new PinConfig(badCurrent, null, null);
            assertEquals(
                    Outcome.INVALID_PIN_CONFIG,
                    verifier.verify(badConfig, SYNTHETIC_DER_CURRENT, FIXED_NOW),
                    "Expected INVALID_PIN_CONFIG for malformed current pin");
        }

        // Malformed non-blank next pins (even if current pin matches)
        List<String> invalidNextPins = List.of(
                NEXT_PIN_LOWER.substring(0, 63),
                NEXT_PIN_LOWER + "a",
                " " + NEXT_PIN_LOWER,
                NEXT_PIN_LOWER + " ",
                "g" + NEXT_PIN_LOWER.substring(1),
                "not-a-hex-digest");

        for (String badNext : invalidNextPins) {
            PinConfig badConfig = new PinConfig(CURRENT_PIN_LOWER, badNext, NEXT_VALID_UNTIL);
            assertEquals(
                    Outcome.INVALID_PIN_CONFIG,
                    verifier.verify(badConfig, SYNTHETIC_DER_CURRENT, FIXED_NOW),
                    "Expected INVALID_PIN_CONFIG for malformed next pin");
        }

        // Valid next pin without certNextValidUntil
        PinConfig missingExpiry = new PinConfig(CURRENT_PIN_LOWER, NEXT_PIN_LOWER, null);
        assertEquals(Outcome.INVALID_PIN_CONFIG, verifier.verify(missingExpiry, SYNTHETIC_DER_CURRENT, FIXED_NOW));
        assertEquals(Outcome.INVALID_PIN_CONFIG, verifier.verify(missingExpiry, SYNTHETIC_DER_NEXT, FIXED_NOW));
    }

    @Test
    void permittedFlagIsTrueOnlyForMatchCurrentAndMatchNext() {
        for (Outcome outcome : Outcome.values()) {
            boolean expectedPermitted = outcome == Outcome.MATCH_CURRENT || outcome == Outcome.MATCH_NEXT;
            assertEquals(expectedPermitted, outcome.isPermitted(), "Unexpected isPermitted for " + outcome);
            assertNotNull(outcome.reason());
            assertFalse(outcome.reason().isBlank());
        }
    }

    @Test
    void neverEchoesFingerprintsDerBytesOrSubjectDnsInOutputs() throws Exception {
        String sentinelCn = "synthetic-sentinel-cn-do-not-echo-98765";
        X509Certificate cert = generateSyntheticSelfSignedCert(sentinelCn);
        String certPin = sha256HexLower(cert.getEncoded());
        String certPinUpper = certPin.toUpperCase(Locale.ROOT);

        PinConfig config = PinConfig.of(certPin, NEXT_PIN_LOWER, NEXT_VALID_UNTIL);
        String configStr = config.toString();
        assertFalse(configStr.contains(certPin));
        assertFalse(configStr.contains(certPinUpper));
        assertFalse(configStr.contains(NEXT_PIN_LOWER));

        List<Outcome> outcomes = List.of(
                verifier.verify(config, cert, FIXED_NOW),
                verifier.verify(config, SYNTHETIC_DER_NEXT, FIXED_NOW),
                verifier.verify(config, SYNTHETIC_DER_NEXT, NEXT_VALID_UNTIL.plusSeconds(1)),
                verifier.verify(config, SYNTHETIC_DER_OTHER, FIXED_NOW),
                verifier.verify(config, (byte[]) null, FIXED_NOW),
                verifier.verify(new PinConfig(OTHER_PIN_LOWER + "bad", null, null), cert, FIXED_NOW));

        for (Outcome outcome : outcomes) {
            String combined = outcome.name() + " " + outcome.reason() + " " + outcome;
            assertFalse(combined.contains(certPin));
            assertFalse(combined.contains(certPinUpper));
            assertFalse(combined.contains(NEXT_PIN_LOWER));
            assertFalse(combined.contains(OTHER_PIN_LOWER));
            assertFalse(combined.contains(sentinelCn));
        }
    }

    private static String sha256HexLower(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String toAlternatingCase(String hex) {
        StringBuilder sb = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            sb.append((i % 2 == 0) ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Generates a minimal synthetic self-signed X.509 v1 certificate using an ephemeral EC keypair
     * and pure standard JCA APIs (no external dependencies, no real identities).
     */
    private static X509Certificate generateSyntheticSelfSignedCert(String commonName) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = kpg.generateKeyPair();

        // OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256)
        byte[] sigAlgId = derSequence(derOid(new byte[] {0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02}));
        // OID 2.5.4.3 (commonName)
        byte[] cnAttr = derSequence(
                derOid(new byte[] {0x55, 0x04, 0x03}),
                derTag(0x0C, commonName.getBytes(StandardCharsets.UTF_8)));
        byte[] name = derSequence(derTag(0x31, cnAttr));
        byte[] validity = derSequence(
                derTag(0x17, "260101000000Z".getBytes(StandardCharsets.US_ASCII)),
                derTag(0x17, "271231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] serial = derTag(0x02, new byte[] {0x01});
        byte[] spki = keyPair.getPublic().getEncoded();

        byte[] tbsCert = derSequence(serial, sigAlgId, name, validity, name, spki);

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(tbsCert);
        byte[] sigBytes = signature.sign();

        ByteArrayOutputStream bitString = new ByteArrayOutputStream(sigBytes.length + 1);
        bitString.write(0x00); // zero unused bits
        bitString.write(sigBytes);
        byte[] certDer = derSequence(tbsCert, sigAlgId, derTag(0x03, bitString.toByteArray()));

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certDer));
        cert.verify(keyPair.getPublic());
        return cert;
    }

    private static byte[] derSequence(byte[]... elements) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] element : elements) {
            content.writeBytes(element);
        }
        return derTag(0x30, content.toByteArray());
    }

    private static byte[] derOid(byte[] encodedOidBytes) {
        return derTag(0x06, encodedOidBytes);
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static final class StubX509Certificate extends X509Certificate {
        private final byte[] encoded;
        private final boolean throwOnEncode;

        private StubX509Certificate(byte[] encoded, boolean throwOnEncode) {
            this.encoded = encoded;
            this.throwOnEncode = throwOnEncode;
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            if (throwOnEncode) {
                throw new CertificateEncodingException("synthetic-encoding-failure-do-not-leak");
            }
            return encoded;
        }

        @Override
        public void checkValidity() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkValidity(Date date) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getVersion() {
            return 3;
        }

        @Override
        public BigInteger getSerialNumber() {
            return BigInteger.ONE;
        }

        @Override
        public Principal getIssuerDN() {
            return null;
        }

        @Override
        public Principal getSubjectDN() {
            return null;
        }

        @Override
        public Date getNotBefore() {
            return null;
        }

        @Override
        public Date getNotAfter() {
            return null;
        }

        @Override
        public byte[] getTBSCertificate() {
            return new byte[0];
        }

        @Override
        public byte[] getSignature() {
            return new byte[0];
        }

        @Override
        public String getSigAlgName() {
            return "SHA256withECDSA";
        }

        @Override
        public String getSigAlgOID() {
            return "1.2.840.10045.4.3.2";
        }

        @Override
        public byte[] getSigAlgParams() {
            return null;
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            return null;
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            return null;
        }

        @Override
        public boolean[] getKeyUsage() {
            return null;
        }

        @Override
        public int getBasicConstraints() {
            return -1;
        }

        @Override
        public void verify(PublicKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void verify(PublicKey key, String sigProvider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return "StubX509Certificate";
        }

        @Override
        public PublicKey getPublicKey() {
            return null;
        }

        @Override
        public boolean hasUnsupportedCriticalExtension() {
            return false;
        }

        @Override
        public Set<String> getCriticalExtensionOIDs() {
            return Set.of();
        }

        @Override
        public Set<String> getNonCriticalExtensionOIDs() {
            return Set.of();
        }

        @Override
        public byte[] getExtensionValue(String oid) {
            return null;
        }
    }
}
