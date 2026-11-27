package com.securityexpert.nexus.ui2.service.eventintake.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookNonceStore.RecordOutcome;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookPayload.EventClass;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookPayload.EventOutcome;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookPayload.MessageType;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookPayload.Vendor;
import com.securityexpert.nexus.ui2.service.eventintake.webhook.WebhookVerificationResult.Status;

class WebhookEnvelopeVerifierTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final String SOURCE_ID = "source-cp-mds-01";
    private static final String CURRENT_KID = "kid-current-2026-11";
    private static final String NEXT_KID = "kid-next-2026-12";
    private static final String CURRENT_SECRET_TEXT = "test-current-hmac-secret-material-do-not-leak-01";
    private static final String NEXT_SECRET_TEXT = "test-next-hmac-secret-material-do-not-leak-02";
    private static final byte[] CURRENT_SECRET = CURRENT_SECRET_TEXT.getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT_SECRET = NEXT_SECRET_TEXT.getBytes(StandardCharsets.UTF_8);

    private static final String VALID_NONCE = "nonce-0123456789abcdef";

    private static final String VALID_CHANGE_EVENT_JSON = """
            {
              "signal_id": "sig-cp-op7-0001",
              "message_type": "CHANGE_EVENT",
              "vendor": "CHECK_POINT",
              "event_class": "POLICY_INSTALLED",
              "event_outcome": "SUCCEEDED",
              "opaque_emitter_key": "relay.cp:dom-alpha-01",
              "opaque_target_id": "00SERIAL-AbCd-0099",
              "device_event_time": "2026-11-26T11:59:58Z"
            }
            """;

    private static final String VALID_HEARTBEAT_JSON = """
            {
              "signal_id": "hb-splunk-0001",
              "message_type": "HEARTBEAT",
              "device_event_time": "2026-11-26T12:00:00Z"
            }
            """;

    @Test
    void acceptsValidChangeEventSignedWithCurrentKidAndPreservesOpaqueIdentifiersVerbatim() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);

        byte[] body = VALID_CHANGE_EVENT_JSON.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders(CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), VALID_NONCE, body);

        WebhookVerificationResult result = verifier.verify(key, headers, body);

        assertTrue(result.isAccepted());
        assertEquals(Status.ACCEPTED, result.status());
        assertEquals(200, result.httpStatus());
        assertEquals(SOURCE_ID, result.sourceId());
        assertEquals(CURRENT_KID, result.matchedKid());
        assertNull(result.errorMessage());

        WebhookPayload payload = result.payload();
        assertNotNull(payload);
        assertEquals("sig-cp-op7-0001", payload.signalId());
        assertEquals(MessageType.CHANGE_EVENT, payload.messageType());
        assertEquals(Vendor.CHECK_POINT, payload.vendor());
        assertEquals(EventClass.POLICY_INSTALLED, payload.eventClass());
        assertEquals(EventOutcome.SUCCEEDED, payload.eventOutcome());
        assertEquals("relay.cp:dom-alpha-01", payload.opaqueEmitterKey());
        // Opaque target ID must never be case-folded or have leading zeroes stripped.
        assertEquals("00SERIAL-AbCd-0099", payload.opaqueTargetId());
        assertEquals("2026-11-26T11:59:58Z", payload.deviceEventTime());

        assertEquals(1, nonceStore.calls());
        assertEquals(FIXED_NOW.plusSeconds(600), nonceStore.lastExpiresAt());
    }

    @Test
    void acceptsValidHeartbeatSignedWithCurrentKid() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);

        byte[] body = VALID_HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders(CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), VALID_NONCE, body);

        WebhookVerificationResult result = verifier.verify(key, headers, body);

        assertTrue(result.isAccepted());
        assertEquals(Status.ACCEPTED, result.status());
        assertEquals(200, result.httpStatus());
        assertNotNull(result.payload());
        assertEquals(MessageType.HEARTBEAT, result.payload().messageType());
        assertEquals("hb-splunk-0001", result.payload().signalId());
        assertNull(result.payload().opaqueEmitterKey());
        assertNull(result.payload().opaqueTargetId());
    }

    @Test
    void acceptsNextKidBeforeAndAtValidUntilAndRejectsOneSecondPastValidUntil() {
        Instant validUntil = FIXED_NOW.plusSeconds(120);
        WebhookSourceKey key = new WebhookSourceKey(
                SOURCE_ID, CURRENT_KID, CURRENT_SECRET, NEXT_KID, NEXT_SECRET, validUntil);
        byte[] body = VALID_CHANGE_EVENT_JSON.getBytes(StandardCharsets.UTF_8);

        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);

        // Before next_valid_until -> accepted.
        Map<String, String> beforeHeaders = signedHeaders(
                NEXT_KID, NEXT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-before-expiry-01", body);
        WebhookVerificationResult beforeResult = verifier.verify(key, beforeHeaders, body, FIXED_NOW);
        assertTrue(beforeResult.isAccepted());
        assertEquals(NEXT_KID, beforeResult.matchedKid());

        // Exactly at next_valid_until -> accepted (!now.isAfter(nextValidUntil)).
        Map<String, String> exactHeaders = signedHeaders(
                NEXT_KID, NEXT_SECRET, validUntil.getEpochSecond(), "nonce-exact-expiry-002", body);
        WebhookVerificationResult exactResult = verifier.verify(key, exactHeaders, body, validUntil);
        assertTrue(exactResult.isAccepted());
        assertEquals(NEXT_KID, exactResult.matchedKid());

        // 1 second past next_valid_until -> rejected as UNKNOWN_KEY (401), without recording nonce.
        Instant expiredNow = validUntil.plusSeconds(1);
        int callsBeforeExpired = nonceStore.calls();
        Map<String, String> expiredHeaders = signedHeaders(
                NEXT_KID, NEXT_SECRET, expiredNow.getEpochSecond(), "nonce-after-expiry-003", body);
        WebhookVerificationResult expiredResult = verifier.verify(key, expiredHeaders, body, expiredNow);
        assertFalse(expiredResult.isAccepted());
        assertEquals(Status.UNKNOWN_KEY, expiredResult.status());
        assertEquals(401, expiredResult.httpStatus());
        assertNull(expiredResult.payload());
        assertEquals(callsBeforeExpired, nonceStore.calls());
    }

    @Test
    void acceptsNextKidWhenNextValidUntilIsNullAndRejectsWhenNextSecretIsMissing() {
        byte[] body = VALID_HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8);

        WebhookSourceKey unboundedNext = new WebhookSourceKey(
                SOURCE_ID, CURRENT_KID, CURRENT_SECRET, NEXT_KID, NEXT_SECRET, null);
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);

        Map<String, String> headers = signedHeaders(
                NEXT_KID, NEXT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-unbounded-next-01", body);
        WebhookVerificationResult accepted = verifier.verify(unboundedNext, headers, body);
        assertTrue(accepted.isAccepted());
        assertEquals(NEXT_KID, accepted.matchedKid());

        WebhookSourceKey missingNextSecret = new WebhookSourceKey(
                SOURCE_ID, CURRENT_KID, CURRENT_SECRET, NEXT_KID, null, null);
        WebhookVerificationResult rejected = verifier.verify(missingNextSecret, headers, body);
        assertEquals(Status.UNKNOWN_KEY, rejected.status());
        assertEquals(401, rejected.httpStatus());
    }

    @Test
    void exact8192ByteBodyAcceptedAnd8193ByteBodyRejectedAsOversizeBeforeNonceOrJsonParsing() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);

        // Build an exact 8192-byte valid JSON body by padding with trailing ASCII spaces.
        byte[] baseBytes = VALID_CHANGE_EVENT_JSON.trim().getBytes(StandardCharsets.UTF_8);
        byte[] exact8192 = new byte[WebhookEnvelopeVerifier.MAX_BODY_BYTES];
        System.arraycopy(baseBytes, 0, exact8192, 0, baseBytes.length);
        for (int i = baseBytes.length; i < exact8192.length; i++) {
            exact8192[i] = ' ';
        }
        assertEquals(8192, exact8192.length);

        Map<String, String> exactHeaders = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-exact-8192-bytes", exact8192);
        WebhookVerificationResult accepted = verifier.verify(key, exactHeaders, exact8192);
        assertTrue(accepted.isAccepted());
        assertEquals(1, nonceStore.calls());

        // 8193 bytes of malformed non-JSON with no headers must still fail immediately with OVERSIZE (413)
        // and must never call the nonce store.
        byte[] oversize8193 = new byte[WebhookEnvelopeVerifier.MAX_BODY_BYTES + 1];
        for (int i = 0; i < oversize8193.length; i++) {
            oversize8193[i] = 'X';
        }
        WebhookVerificationResult oversize = verifier.verify(key, Map.of(), oversize8193);
        assertFalse(oversize.isAccepted());
        assertEquals(Status.OVERSIZE, oversize.status());
        assertEquals(413, oversize.httpStatus());
        assertNull(oversize.payload());
        assertEquals(1, nonceStore.calls());
    }

    @Test
    void enforcesTimestampSkewBoundariesAndRejectsMissingOrNonNumericTimestamps() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        byte[] body = VALID_HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8);
        long nowSec = FIXED_NOW.getEpochSecond();

        // +300 s accepted.
        WebhookVerificationResult plus300 = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec + 300, "nonce-skew-plus-300-01", body), body);
        assertTrue(plus300.isAccepted());

        // -300 s accepted.
        WebhookVerificationResult minus300 = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec - 300, "nonce-skew-minus-300-1", body), body);
        assertTrue(minus300.isAccepted());

        int callsAfterValid = nonceStore.calls();

        // +301 s rejected.
        WebhookVerificationResult plus301 = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec + 301, "nonce-skew-plus-301-01", body), body);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, plus301.status());
        assertEquals(401, plus301.httpStatus());

        // -301 s rejected.
        WebhookVerificationResult minus301 = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec - 301, "nonce-skew-minus-301-1", body), body);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, minus301.status());
        assertEquals(401, minus301.httpStatus());

        // Missing timestamp rejected.
        Map<String, String> missingTs = new HashMap<>(
                signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-missing-ts-00001", body));
        missingTs.remove(WebhookEnvelopeVerifier.HEADER_TIMESTAMP);
        WebhookVerificationResult missingResult = verifier.verify(key, missingTs, body);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, missingResult.status());
        assertEquals(401, missingResult.httpStatus());

        // Non-numeric timestamp rejected.
        Map<String, String> nonNumericTs = new HashMap<>(
                signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-non-numeric-ts-1", body));
        nonNumericTs.put(WebhookEnvelopeVerifier.HEADER_TIMESTAMP, "2026-11-26T12:00:00Z");
        WebhookVerificationResult nonNumericResult = verifier.verify(key, nonNumericTs, body);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, nonNumericResult.status());
        assertEquals(401, nonNumericResult.httpStatus());

        assertEquals(callsAfterValid, nonceStore.calls());
    }

    @Test
    void enforcesNonceLengthBoundsAndCharacterAllowlist() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        byte[] body = VALID_HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8);
        long nowSec = FIXED_NOW.getEpochSecond();

        // Exact lower (16) and upper (128) boundaries accepted.
        String nonce16 = "a".repeat(16);
        String nonce128 = "A0_.-".repeat(25) + "xyz";
        assertEquals(128, nonce128.length());

        assertTrue(verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, nonce16, body), body).isAccepted());
        assertTrue(verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, nonce128, body), body).isAccepted());

        int callsAfterValid = nonceStore.calls();

        // 15 chars, 129 chars, and disallowed characters rejected as INVALID_HEADERS (400).
        String[] badNonces = {
                "a".repeat(15),
                "a".repeat(129),
                "nonce-with space-001",
                "nonce-with/slash-001",
                "nonce-with:colon-001",
                "nonce-with@symbol-01",
                ""
        };
        for (String badNonce : badNonces) {
            Map<String, String> headers = signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, badNonce, body);
            WebhookVerificationResult rejected = verifier.verify(key, headers, body);
            assertEquals(Status.INVALID_HEADERS, rejected.status(), "Expected INVALID_HEADERS for bad nonce length/chars");
            assertEquals(400, rejected.httpStatus());
            assertNull(rejected.payload());
        }
        assertEquals(callsAfterValid, nonceStore.calls());
    }

    @Test
    void rejectsTamperedBodyWrongSignatureAndMalformedSignatureWithoutRecordingNonce() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);

        byte[] originalBody = VALID_CHANGE_EVENT_JSON.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-tamper-check-001", originalBody);

        // Tampered body -> INVALID_SIGNATURE (401), nonce store untouched.
        byte[] tamperedBody = VALID_CHANGE_EVENT_JSON.replace("sig-cp-op7-0001", "sig-cp-op7-9999")
                .getBytes(StandardCharsets.UTF_8);
        WebhookVerificationResult tamperedResult = verifier.verify(key, headers, tamperedBody);
        assertEquals(Status.INVALID_SIGNATURE, tamperedResult.status());
        assertEquals(401, tamperedResult.httpStatus());
        assertEquals(0, nonceStore.calls());

        // Signed with wrong secret -> INVALID_SIGNATURE (401), nonce store untouched.
        Map<String, String> wrongKeyHeaders = signedHeaders(
                CURRENT_KID, NEXT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-wrong-secret-001", originalBody);
        WebhookVerificationResult wrongKeyResult = verifier.verify(key, wrongKeyHeaders, originalBody);
        assertEquals(Status.INVALID_SIGNATURE, wrongKeyResult.status());
        assertEquals(401, wrongKeyResult.httpStatus());
        assertEquals(0, nonceStore.calls());

        // Uppercase hex signature -> malformed format -> INVALID_SIGNATURE (401), nonce store untouched.
        Map<String, String> upperHeaders = new HashMap<>(headers);
        String upperSig = "sha256=" + headers.get(WebhookEnvelopeVerifier.HEADER_SIGNATURE)
                .substring("sha256=".length())
                .toUpperCase();
        upperHeaders.put(WebhookEnvelopeVerifier.HEADER_SIGNATURE, upperSig);
        WebhookVerificationResult upperResult = verifier.verify(key, upperHeaders, originalBody);
        assertEquals(Status.INVALID_SIGNATURE, upperResult.status());
        assertEquals(401, upperResult.httpStatus());
        assertEquals(0, nonceStore.calls());

        // Missing sha256= prefix -> INVALID_SIGNATURE (401).
        Map<String, String> badPrefixHeaders = new HashMap<>(headers);
        badPrefixHeaders.put(WebhookEnvelopeVerifier.HEADER_SIGNATURE, "0".repeat(64));
        WebhookVerificationResult badPrefixResult = verifier.verify(key, badPrefixHeaders, originalBody);
        assertEquals(Status.INVALID_SIGNATURE, badPrefixResult.status());
        assertEquals(401, badPrefixResult.httpStatus());
        assertEquals(0, nonceStore.calls());
    }

    @Test
    void rejectsReplayedNonceWith409AndFailsClosedWith503WhenReplayStoreUnavailableOrThrows() {
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        byte[] body = VALID_CHANGE_EVENT_JSON.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-replay-test-0001", body);

        // First call succeeds; identical second call returns REPLAYED_NONCE (409).
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        assertTrue(verifier.verify(key, headers, body).isAccepted());

        WebhookVerificationResult replayResult = verifier.verify(key, headers, body);
        assertFalse(replayResult.isAccepted());
        assertEquals(Status.REPLAYED_NONCE, replayResult.status());
        assertEquals(409, replayResult.httpStatus());
        assertNull(replayResult.payload());

        // Store returning UNAVAILABLE -> REPLAY_STORE_UNAVAILABLE (503).
        WebhookEnvelopeVerifier unavailableVerifier = new WebhookEnvelopeVerifier(
                (src, nonce, expiresAt) -> RecordOutcome.UNAVAILABLE, FIXED_CLOCK);
        WebhookVerificationResult unavailableResult = unavailableVerifier.verify(key, headers, body);
        assertEquals(Status.REPLAY_STORE_UNAVAILABLE, unavailableResult.status());
        assertEquals(503, unavailableResult.httpStatus());
        assertNull(unavailableResult.payload());

        // Store throwing RuntimeException -> REPLAY_STORE_UNAVAILABLE (503), no exception detail leaked.
        WebhookEnvelopeVerifier throwingVerifier = new WebhookEnvelopeVerifier(
                (src, nonce, expiresAt) -> {
                    throw new RuntimeException("jdbc:postgresql://internal-secret-host/db-password-leak");
                }, FIXED_CLOCK);
        WebhookVerificationResult throwingResult = throwingVerifier.verify(key, headers, body);
        assertEquals(Status.REPLAY_STORE_UNAVAILABLE, throwingResult.status());
        assertEquals(503, throwingResult.httpStatus());
        assertNull(throwingResult.payload());
        assertFalse(throwingResult.errorMessage().contains("internal-secret-host"));
        assertFalse(throwingResult.toString().contains("db-password-leak"));
    }

    @Test
    void rejectsUnknownJsonPropertiesHostAndDvcAndTrailingTokensAndInvalidSchema() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        long nowSec = FIXED_NOW.getEpochSecond();

        String[] invalidPayloads = {
                // Unknown "host" property (hostname join prohibition).
                """
                {
                  "signal_id": "sig-host-1",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "CHECK_POINT",
                  "event_class": "POLICY_INSTALLED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_emitter_key": "relay.cp:dom-01",
                  "host": "fw-01"
                }
                """,
                // Unknown "dvc" property.
                """
                {
                  "signal_id": "sig-dvc-1",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "PALO_ALTO",
                  "event_class": "CONFIG_COMMITTED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_target_id": "001234567890",
                  "dvc": "fw-01"
                }
                """,
                // Trailing JSON token after valid object.
                VALID_HEARTBEAT_JSON + "{\"extra\":true}",
                // Invalid vendor enum.
                """
                {
                  "signal_id": "sig-bad-vendor",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "UNKNOWN_VENDOR",
                  "event_class": "POLICY_INSTALLED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_emitter_key": "relay.cp:dom-01"
                }
                """,
                // Missing required signal_id.
                """
                {
                  "message_type": "HEARTBEAT"
                }
                """,
                // Out-of-bounds signal_id (129 chars).
                "{\"signal_id\":\"" + "s".repeat(129) + "\",\"message_type\":\"HEARTBEAT\"}",
                // Out-of-bounds device_event_time (65 chars).
                "{\"signal_id\":\"sig-time\",\"message_type\":\"HEARTBEAT\",\"device_event_time\":\""
                        + "t".repeat(65) + "\"}",
                // Invalid opaque_emitter_key characters.
                """
                {
                  "signal_id": "sig-bad-emitter",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "CHECK_POINT",
                  "event_class": "POLICY_INSTALLED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_emitter_key": "invalid emitter/key"
                }
                """,
                // Integer coercion attempt on opaque_target_id (must stay opaque string).
                """
                {
                  "signal_id": "sig-int-target",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "PALO_ALTO",
                  "event_class": "CONFIG_COMMITTED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_target_id": 123456
                }
                """,
                // Duplicate JSON property smuggling attempt.
                """
                {
                  "signal_id": "sig-dup-1",
                  "message_type": "HEARTBEAT",
                  "signal_id": "sig-dup-2"
                }
                """
        };

        for (int i = 0; i < invalidPayloads.length; i++) {
            byte[] body = invalidPayloads[i].getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = signedHeaders(
                    CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-bad-payload-000" + i, body);
            WebhookVerificationResult result = verifier.verify(key, headers, body);
            assertEquals(Status.INVALID_PAYLOAD, result.status(), "Case " + i + " should fail with INVALID_PAYLOAD");
            assertEquals(400, result.httpStatus());
            assertNull(result.payload());
        }
    }

    @Test
    void changeEventWithoutOpaqueEmitterKeyOrOpaqueTargetIdReturnsNotEvaluable() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        long nowSec = FIXED_NOW.getEpochSecond();

        String bothNull = """
                {
                  "signal_id": "sig-no-id-01",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "CHECK_POINT",
                  "event_class": "POLICY_INSTALLED",
                  "event_outcome": "SUCCEEDED"
                }
                """;
        String bothBlank = """
                {
                  "signal_id": "sig-no-id-02",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "FORTINET",
                  "event_class": "CONFIG_COMMITTED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_emitter_key": "   ",
                  "opaque_target_id": ""
                }
                """;

        byte[] bodyNull = bothNull.getBytes(StandardCharsets.UTF_8);
        WebhookVerificationResult resultNull = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-not-eval-null-01", bodyNull), bodyNull);
        assertFalse(resultNull.isAccepted());
        assertEquals(Status.NOT_EVALUABLE, resultNull.status());
        assertNull(resultNull.payload());

        byte[] bodyBlank = bothBlank.getBytes(StandardCharsets.UTF_8);
        WebhookVerificationResult resultBlank = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-not-eval-blank-2", bodyBlank), bodyBlank);
        assertFalse(resultBlank.isAccepted());
        assertEquals(Status.NOT_EVALUABLE, resultBlank.status());
        assertNull(resultBlank.payload());
    }

    @Test
    void errorMessagesAndToStringNeverLeakSecretsRawBodiesOrSignatures() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(
                SOURCE_ID, CURRENT_KID, CURRENT_SECRET, NEXT_KID, NEXT_SECRET, FIXED_NOW.plusSeconds(60));

        // WebhookSourceKey.toString() must never expose secret material.
        assertFalse(key.toString().contains(CURRENT_SECRET_TEXT));
        assertFalse(key.toString().contains(NEXT_SECRET_TEXT));

        String rawSentinelBody = """
                {
                  "signal_id": "sig-leak-test-01",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "CHECK_POINT",
                  "event_class": "POLICY_INSTALLED",
                  "event_outcome": "SUCCEEDED",
                  "opaque_target_id": "SENSITIVE-SERIAL-998877",
                  "host": "RAW-BODY-CANARY-TOKEN"
                }
                """;
        byte[] bodyBytes = rawSentinelBody.getBytes(StandardCharsets.UTF_8);
        Map<String, String> validSigned = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-leak-check-00001", bodyBytes);
        String signatureHex = validSigned.get(WebhookEnvelopeVerifier.HEADER_SIGNATURE).substring("sha256=".length());

        // Check INVALID_PAYLOAD failure (body has unknown field "host").
        WebhookVerificationResult badPayload = verifier.verify(key, validSigned, bodyBytes);
        assertEquals(Status.INVALID_PAYLOAD, badPayload.status());
        assertNoLeak(badPayload, rawSentinelBody, signatureHex);

        // Check INVALID_SIGNATURE failure.
        Map<String, String> wrongSig = new HashMap<>(validSigned);
        String forgedHex = "ab".repeat(32);
        wrongSig.put(WebhookEnvelopeVerifier.HEADER_SIGNATURE, "sha256=" + forgedHex);
        WebhookVerificationResult badSig = verifier.verify(key, wrongSig, bodyBytes);
        assertEquals(Status.INVALID_SIGNATURE, badSig.status());
        assertNoLeak(badSig, rawSentinelBody, forgedHex);

        // Check ACCEPTED payload & result toString() never leak raw secret, body, signature, or serial.
        byte[] cleanBody = VALID_CHANGE_EVENT_JSON.getBytes(StandardCharsets.UTF_8);
        Map<String, String> cleanHeaders = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-leak-check-00002", cleanBody);
        String cleanHex = cleanHeaders.get(WebhookEnvelopeVerifier.HEADER_SIGNATURE).substring("sha256=".length());
        WebhookVerificationResult accepted = verifier.verify(key, cleanHeaders, cleanBody);
        assertTrue(accepted.isAccepted());
        assertNoLeak(accepted, VALID_CHANGE_EVENT_JSON, cleanHex);
        assertFalse(accepted.payload().toString().contains("00SERIAL-AbCd-0099"));
    }

    @Test
    void acceptsCaseInsensitiveHeadersAndRejectsConflictingDuplicateHeaders() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        byte[] body = VALID_HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8);

        Map<String, String> canonical = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, FIXED_NOW.getEpochSecond(), "nonce-case-header-0001", body);
        Map<String, String> lowerCaseHeaders = new LinkedHashMap<>();
        canonical.forEach((k, v) -> lowerCaseHeaders.put(k.toLowerCase(), v));

        assertTrue(verifier.verify(key, lowerCaseHeaders, body).isAccepted());

        Map<String, String> conflicting = new LinkedHashMap<>(canonical);
        conflicting.put("x-signal-nonce", "nonce-different-000002");
        WebhookVerificationResult rejected = verifier.verify(key, conflicting, body);
        assertEquals(Status.INVALID_HEADERS, rejected.status());
        assertEquals(400, rejected.httpStatus());
    }

    @Test
    void enforcesSubSecondTimestampSkewAndPreventsReplayGapAcrossTtlPruningStore() {
        AtomicReference<Instant> currentNow = new AtomicReference<>(FIXED_NOW);
        TtlEnforcingNonceStore nonceStore = new TtlEnforcingNonceStore(currentNow);
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        byte[] body = VALID_CHANGE_EVENT_JSON.getBytes(StandardCharsets.UTF_8);

        // Captured request with maximum forward skew (+300s) accepted at T.000s.
        long forwardTs = FIXED_NOW.getEpochSecond() + WebhookEnvelopeVerifier.MAX_TIMESTAMP_SKEW_SECONDS;
        Map<String, String> capturedHeaders = signedHeaders(
                CURRENT_KID, CURRENT_SECRET, forwardTs, "nonce-replay-gap-00001", body);

        WebhookVerificationResult initial = verifier.verify(key, capturedHeaders, body, currentNow.get());
        assertTrue(initial.isAccepted());
        Instant latestAcceptable = Instant.ofEpochSecond(forwardTs)
                .plusSeconds(WebhookEnvelopeVerifier.MAX_TIMESTAMP_SKEW_SECONDS);
        assertTrue(
                nonceStore.lastExpiresAt().isAfter(latestAcceptable),
                "Nonce expiry must be strictly after the latest instant the timestamp remains valid");

        // At exact boundary T + 600.000s, timestamp is still in-window, so TTL-pruning store (expires_at <= now)
        // must still retain the nonce and reject as REPLAYED_NONCE (409).
        currentNow.set(FIXED_NOW.plusSeconds(600));
        WebhookVerificationResult atExactBoundary = verifier.verify(key, capturedHeaders, body, currentNow.get());
        assertFalse(atExactBoundary.isAccepted());
        assertEquals(Status.REPLAYED_NONCE, atExactBoundary.status());
        assertEquals(409, atExactBoundary.httpStatus());

        // 1 nanosecond and 500ms past T + 600.000s, timestamp skew check must reject with TIMESTAMP_OUT_OF_WINDOW (401).
        currentNow.set(FIXED_NOW.plusSeconds(600).plusNanos(1));
        WebhookVerificationResult oneNanoPast = verifier.verify(key, capturedHeaders, body, currentNow.get());
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, oneNanoPast.status());
        assertEquals(401, oneNanoPast.httpStatus());

        currentNow.set(FIXED_NOW.plusSeconds(600).plusMillis(500));
        WebhookVerificationResult halfSecPast = verifier.verify(key, capturedHeaders, body, currentNow.get());
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, halfSecPast.status());
        assertEquals(401, halfSecPast.httpStatus());

        // Past nonce expiry (T + 601.000s), timestamp is also outside the skew window -> 401.
        currentNow.set(FIXED_NOW.plusSeconds(601));
        WebhookVerificationResult pastExpiry = verifier.verify(key, capturedHeaders, body, currentNow.get());
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, pastExpiry.status());
        assertEquals(401, pastExpiry.httpStatus());

        // Sub-second skew enforcement when now = T.500s: T - 300 is 300.500s old -> rejected; T - 299 -> accepted.
        Instant midSecondNow = FIXED_NOW.plusMillis(500);
        currentNow.set(midSecondNow);
        long baseSec = FIXED_NOW.getEpochSecond();

        WebhookVerificationResult staleSubSecond = verifier.verify(
                key,
                signedHeaders(CURRENT_KID, CURRENT_SECRET, baseSec - 300, "nonce-subsec-stale-001", body),
                body,
                midSecondNow);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, staleSubSecond.status());
        assertEquals(401, staleSubSecond.httpStatus());

        WebhookVerificationResult validMinus299 = verifier.verify(
                key,
                signedHeaders(CURRENT_KID, CURRENT_SECRET, baseSec - 299, "nonce-subsec-valid-001", body),
                body,
                midSecondNow);
        assertTrue(validMinus299.isAccepted());

        WebhookVerificationResult validPlus300 = verifier.verify(
                key,
                signedHeaders(CURRENT_KID, CURRENT_SECRET, baseSec + 300, "nonce-subsec-valid-002", body),
                body,
                midSecondNow);
        assertTrue(validPlus300.isAccepted());

        WebhookVerificationResult futureSubSecond = verifier.verify(
                key,
                signedHeaders(CURRENT_KID, CURRENT_SECRET, baseSec + 301, "nonce-subsec-future-01", body),
                body,
                midSecondNow);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, futureSubSecond.status());
        assertEquals(401, futureSubSecond.httpStatus());

        // Extreme 19-digit epoch second outside Instant range must fail closed as TIMESTAMP_OUT_OF_WINDOW.
        Map<String, String> extremeTsHeaders = new HashMap<>(
                signedHeaders(CURRENT_KID, CURRENT_SECRET, baseSec, "nonce-extreme-ts-00001", body));
        extremeTsHeaders.put(WebhookEnvelopeVerifier.HEADER_TIMESTAMP, "9999999999999999999");
        WebhookVerificationResult extremeTsResult = verifier.verify(key, extremeTsHeaders, body, midSecondNow);
        assertEquals(Status.TIMESTAMP_OUT_OF_WINDOW, extremeTsResult.status());
        assertEquals(401, extremeTsResult.httpStatus());
    }

    @Test
    void rejectsContradictoryChangeEventFieldsOnHeartbeatAndAcceptsExplicitHeartbeatEventClass() {
        InMemoryNonceStore nonceStore = new InMemoryNonceStore();
        WebhookEnvelopeVerifier verifier = new WebhookEnvelopeVerifier(nonceStore, FIXED_CLOCK);
        WebhookSourceKey key = new WebhookSourceKey(SOURCE_ID, CURRENT_KID, CURRENT_SECRET);
        long nowSec = FIXED_NOW.getEpochSecond();

        // Explicit event_class = HEARTBEAT with no event_outcome is accepted.
        String explicitHeartbeat = """
                {
                  "signal_id": "hb-explicit-0001",
                  "message_type": "HEARTBEAT",
                  "event_class": "HEARTBEAT",
                  "device_event_time": "2026-11-26T12:00:00Z"
                }
                """;
        byte[] validBody = explicitHeartbeat.getBytes(StandardCharsets.UTF_8);
        WebhookVerificationResult accepted = verifier.verify(
                key, signedHeaders(CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-hb-explicit-0001", validBody), validBody);
        assertTrue(accepted.isAccepted());
        assertEquals(Status.ACCEPTED, accepted.status());
        assertEquals(EventClass.HEARTBEAT, accepted.payload().eventClass());

        // Contradictory HEARTBEAT payloads and symmetric CHANGE_EVENT with HEARTBEAT event_class must fail closed (400).
        String[] contradictoryPayloads = {
                """
                {
                  "signal_id": "hb-contradict-01",
                  "message_type": "HEARTBEAT",
                  "vendor": "CHECK_POINT",
                  "event_class": "POLICY_INSTALLED",
                  "event_outcome": "SUCCEEDED"
                }
                """,
                """
                {
                  "signal_id": "hb-contradict-02",
                  "message_type": "HEARTBEAT",
                  "event_class": "CONFIG_COMMITTED"
                }
                """,
                """
                {
                  "signal_id": "hb-contradict-03",
                  "message_type": "HEARTBEAT",
                  "event_class": "HEARTBEAT",
                  "event_outcome": "SUCCEEDED"
                }
                """,
                """
                {
                  "signal_id": "hb-contradict-04",
                  "message_type": "HEARTBEAT",
                  "event_outcome": "FAILED"
                }
                """,
                """
                {
                  "signal_id": "ce-contradict-05",
                  "message_type": "CHANGE_EVENT",
                  "vendor": "CHECK_POINT",
                  "event_class": "HEARTBEAT",
                  "event_outcome": "SUCCEEDED",
                  "opaque_emitter_key": "relay.cp:dom-01"
                }
                """
        };

        for (int i = 0; i < contradictoryPayloads.length; i++) {
            byte[] body = contradictoryPayloads[i].getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = signedHeaders(
                    CURRENT_KID, CURRENT_SECRET, nowSec, "nonce-contradict-0000" + i, body);
            WebhookVerificationResult rejected = verifier.verify(key, headers, body);
            assertFalse(rejected.isAccepted(), "Case " + i + " must be rejected");
            assertEquals(Status.INVALID_PAYLOAD, rejected.status(), "Case " + i + " should return INVALID_PAYLOAD");
            assertEquals(400, rejected.httpStatus());
            assertNull(rejected.payload());
        }
    }

    private static void assertNoLeak(WebhookVerificationResult result, String rawBody, String signatureHex) {
        String combined = String.valueOf(result.errorMessage()) + " | " + result.toString();
        assertFalse(combined.contains(CURRENT_SECRET_TEXT), "Secret leaked in result");
        assertFalse(combined.contains(NEXT_SECRET_TEXT), "Next secret leaked in result");
        assertFalse(combined.contains(rawBody), "Raw body leaked in result");
        assertFalse(combined.contains("RAW-BODY-CANARY-TOKEN"), "Raw JSON field value leaked in result");
        assertFalse(combined.contains("SENSITIVE-SERIAL-998877"), "Opaque target ID leaked in result");
        assertFalse(combined.contains(signatureHex), "Raw signature hex leaked in result");
    }

    private static Map<String, String> signedHeaders(
            String kid,
            byte[] secret,
            long timestampEpochSec,
            String nonce,
            byte[] body) {
        String ts = Long.toString(timestampEpochSec);
        String sig = "sha256=" + hmacSha256Hex(secret, ts, nonce, body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(WebhookEnvelopeVerifier.HEADER_KEY_ID, kid);
        headers.put(WebhookEnvelopeVerifier.HEADER_TIMESTAMP, ts);
        headers.put(WebhookEnvelopeVerifier.HEADER_NONCE, nonce);
        headers.put(WebhookEnvelopeVerifier.HEADER_SIGNATURE, sig);
        return headers;
    }

    private static String hmacSha256Hex(byte[] secret, String timestamp, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "." + nonce + ".").getBytes(StandardCharsets.US_ASCII));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class InMemoryNonceStore implements WebhookNonceStore {
        private final Set<String> seen = new HashSet<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Instant> lastExpiresAt = new AtomicReference<>();

        @Override
        public RecordOutcome recordIfAbsent(String sourceId, String nonce, Instant expiresAt) {
            calls.incrementAndGet();
            lastExpiresAt.set(expiresAt);
            return seen.add(sourceId + "\0" + nonce) ? RecordOutcome.RECORDED : RecordOutcome.REPLAYED;
        }

        int calls() {
            return calls.get();
        }

        Instant lastExpiresAt() {
            return lastExpiresAt.get();
        }
    }

    private static final class TtlEnforcingNonceStore implements WebhookNonceStore {
        private final AtomicReference<Instant> clockRef;
        private final Map<String, Instant> active = new HashMap<>();
        private final AtomicReference<Instant> lastExpiresAt = new AtomicReference<>();

        TtlEnforcingNonceStore(AtomicReference<Instant> clockRef) {
            this.clockRef = clockRef;
        }

        @Override
        public RecordOutcome recordIfAbsent(String sourceId, String nonce, Instant expiresAt) {
            Instant now = clockRef.get();
            active.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            String key = sourceId + "\0" + nonce;
            if (active.containsKey(key)) {
                return RecordOutcome.REPLAYED;
            }
            active.put(key, expiresAt);
            lastExpiresAt.set(expiresAt);
            return RecordOutcome.RECORDED;
        }

        Instant lastExpiresAt() {
            return lastExpiresAt.get();
        }
    }
}
