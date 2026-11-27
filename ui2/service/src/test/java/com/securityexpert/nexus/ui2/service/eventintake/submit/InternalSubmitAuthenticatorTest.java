package com.securityexpert.nexus.ui2.service.eventintake.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.eventintake.submit.InternalSubmitAuthResult.MatchedSlot;
import com.securityexpert.nexus.ui2.service.eventintake.submit.InternalSubmitAuthResult.Status;

class InternalSubmitAuthenticatorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T04:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final String CURRENT_RAW_TOKEN = "current-internal-submit-bearer-token-do-not-leak-01";
    private static final String NEXT_RAW_TOKEN = "next-internal-submit-bearer-token-do-not-leak-0002";
    private static final String WRONG_RAW_TOKEN = "wrong-internal-submit-bearer-token-do-not-leak-003";

    private static final String CURRENT_SHA256_HEX = sha256Hex(CURRENT_RAW_TOKEN);
    private static final String NEXT_SHA256_HEX = sha256Hex(NEXT_RAW_TOKEN);

    private static final UUID SCOPE_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID EVENT_UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final String VALID_JSON = """
            {"scope_id":"550e8400-e29b-41d4-a716-446655440000","event_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7"}
            """.trim();
    private static final byte[] VALID_BODY = VALID_JSON.getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsValidCurrentTokenAndParsesExactCanonicalUuids() {
        InternalSubmitAuthenticator authenticator = new InternalSubmitAuthenticator(FIXED_CLOCK);
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.fromRawToken(CURRENT_RAW_TOKEN);

        InternalSubmitAuthResult result = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN,
                VALID_BODY,
                config);

        assertTrue(result.isAccepted());
        assertEquals(Status.ACCEPTED, result.status());
        assertEquals(200, result.httpStatus());
        assertEquals(MatchedSlot.CURRENT, result.matchedSlot());
        assertEquals("ACCEPTED", result.reasonCode());
        assertNotNull(result.request());
        assertEquals(SCOPE_UUID, result.request().scopeId());
        assertEquals(EVENT_UUID, result.request().eventId());
        assertEquals(CURRENT_SHA256_HEX, config.currentSha256Hex());
        assertNull(config.nextSha256Hex());
        assertFalse(config.hasNextToken());
    }

    @Test
    void acceptsNextTokenBeforeAndAtValidUntilAndRejectsOneNanosecondAfter() {
        Instant validUntil = FIXED_NOW.plusSeconds(300);
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.fromRawTokens(
                CURRENT_RAW_TOKEN, NEXT_RAW_TOKEN, validUntil);
        InternalSubmitAuthenticator authenticator = new InternalSubmitAuthenticator(FIXED_CLOCK);

        assertTrue(config.hasNextToken());
        assertEquals(NEXT_SHA256_HEX, config.nextSha256Hex());
        assertEquals(validUntil, config.nextValidUntil());

        // Before nextValidUntil -> ACCEPTED (NEXT).
        InternalSubmitAuthResult before = authenticator.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, VALID_BODY, config, FIXED_NOW);
        assertTrue(before.isAccepted());
        assertEquals(Status.ACCEPTED, before.status());
        assertEquals(MatchedSlot.NEXT, before.matchedSlot());
        assertEquals(SCOPE_UUID, before.request().scopeId());
        assertEquals(EVENT_UUID, before.request().eventId());

        // Exactly at nextValidUntil -> ACCEPTED (NEXT).
        InternalSubmitAuthResult exact = authenticator.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, VALID_BODY, config, validUntil);
        assertTrue(exact.isAccepted());
        assertEquals(Status.ACCEPTED, exact.status());
        assertEquals(MatchedSlot.NEXT, exact.matchedSlot());

        // 1 nanosecond after nextValidUntil -> UNAUTHORIZED (401).
        InternalSubmitAuthResult expired = authenticator.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, VALID_BODY, config, validUntil.plusNanos(1));
        assertFalse(expired.isAccepted());
        assertEquals(Status.UNAUTHORIZED, expired.status());
        assertEquals(401, expired.httpStatus());
        assertNull(expired.matchedSlot());
        assertNull(expired.request());
        assertEquals(InternalSubmitAuthenticator.REASON_INVALID_OR_EXPIRED_TOKEN, expired.reasonCode());

        // Null `now` fails closed for NEXT token while still allowing CURRENT token.
        InternalSubmitAuthResult nextWithNullNow = authenticator.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, VALID_BODY, config, null);
        assertEquals(Status.UNAUTHORIZED, nextWithNullNow.status());
        assertEquals(401, nextWithNullNow.httpStatus());

        InternalSubmitAuthResult currentWithNullNow = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, VALID_BODY, config, null);
        assertTrue(currentWithNullNow.isAccepted());
        assertEquals(MatchedSlot.CURRENT, currentWithNullNow.matchedSlot());
    }

    @Test
    void rejectsInvalidHeadersWrongTokenAndNullConfigFailClosed() {
        Instant validUntil = FIXED_NOW.plusSeconds(300);
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.fromRawTokens(
                CURRENT_RAW_TOKEN, NEXT_RAW_TOKEN, validUntil);
        InternalSubmitAuthenticator authenticator = new InternalSubmitAuthenticator(FIXED_CLOCK);

        // Null config -> UNAUTHORIZED.
        InternalSubmitAuthResult nullConfig = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, VALID_BODY, null, FIXED_NOW);
        assertEquals(Status.UNAUTHORIZED, nullConfig.status());
        assertEquals(401, nullConfig.httpStatus());
        assertNull(nullConfig.request());
        assertNull(nullConfig.matchedSlot());

        String[] badHeaders = {
                null,
                "",
                "   ",
                "Bearer",
                "Bearer ",
                "Bearer   ",
                "Basic " + CURRENT_RAW_TOKEN,
                "bearer " + CURRENT_RAW_TOKEN,
                "BEARER " + CURRENT_RAW_TOKEN,
                " Bearer " + CURRENT_RAW_TOKEN,
                "Bearer  " + CURRENT_RAW_TOKEN,
                "Bearer " + CURRENT_RAW_TOKEN + " ",
                "Bearer " + CURRENT_RAW_TOKEN + "\t",
                "Bearer " + CURRENT_RAW_TOKEN + "\n",
                "Bearer " + CURRENT_RAW_TOKEN + "\r",
                "Bearer " + CURRENT_RAW_TOKEN + "\u0000",
                "Bearer " + CURRENT_RAW_TOKEN + "\u007F",
                "Bearer token-with internal-space-0123456789abcdef",
                "Bearer " + WRONG_RAW_TOKEN
        };

        for (int i = 0; i < badHeaders.length; i++) {
            InternalSubmitAuthResult rejected = authenticator.authenticateAndParse(
                    badHeaders[i], VALID_BODY, config, FIXED_NOW);
            assertFalse(rejected.isAccepted(), "Header case " + i + " must be rejected");
            assertEquals(Status.UNAUTHORIZED, rejected.status(), "Header case " + i + " must be UNAUTHORIZED");
            assertEquals(401, rejected.httpStatus());
            assertNull(rejected.request());
            assertNull(rejected.matchedSlot());
        }
    }

    @Test
    void enforcesExact1024BytePreParseCapEvenWithMalformedJsonAndInvalidAuth() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.fromRawToken(CURRENT_RAW_TOKEN);
        InternalSubmitAuthenticator authenticator = new InternalSubmitAuthenticator(FIXED_CLOCK);

        // Exact 1,024-byte valid JSON (padded with trailing ASCII spaces) -> ACCEPTED.
        byte[] exact1024 = new byte[InternalSubmitAuthenticator.MAX_BODY_BYTES];
        System.arraycopy(VALID_BODY, 0, exact1024, 0, VALID_BODY.length);
        for (int i = VALID_BODY.length; i < exact1024.length; i++) {
            exact1024[i] = ' ';
        }
        assertEquals(1024, exact1024.length);

        InternalSubmitAuthResult exactResult = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, exact1024, config, FIXED_NOW);
        assertTrue(exactResult.isAccepted());
        assertEquals(Status.ACCEPTED, exactResult.status());
        assertEquals(SCOPE_UUID, exactResult.request().scopeId());
        assertEquals(EVENT_UUID, exactResult.request().eventId());

        // 1,025 bytes of completely malformed non-JSON -> PAYLOAD_TOO_LARGE (413) pre-parse,
        // even when called with valid auth OR missing auth.
        byte[] oversize1025 = new byte[InternalSubmitAuthenticator.MAX_BODY_BYTES + 1];
        for (int i = 0; i < oversize1025.length; i++) {
            oversize1025[i] = '{';
        }

        InternalSubmitAuthResult oversizeAuthed = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, oversize1025, config, FIXED_NOW);
        assertFalse(oversizeAuthed.isAccepted());
        assertEquals(Status.PAYLOAD_TOO_LARGE, oversizeAuthed.status());
        assertEquals(413, oversizeAuthed.httpStatus());
        assertNull(oversizeAuthed.request());
        assertNull(oversizeAuthed.matchedSlot());
        assertEquals(InternalSubmitAuthenticator.REASON_BODY_EXCEEDS_MAX_BYTES, oversizeAuthed.reasonCode());

        InternalSubmitAuthResult oversizeNoAuth = authenticator.authenticateAndParse(
                null, oversize1025, null, FIXED_NOW);
        assertEquals(Status.PAYLOAD_TOO_LARGE, oversizeNoAuth.status());
        assertEquals(413, oversizeNoAuth.httpStatus());
    }

    @Test
    void rejectsEmptyMalformedUnknownDuplicateTrailingCoercedAndNonCanonicalUuidPayloads() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.fromRawToken(CURRENT_RAW_TOKEN);
        InternalSubmitAuthenticator authenticator = new InternalSubmitAuthenticator(FIXED_CLOCK);
        String validAuth = "Bearer " + CURRENT_RAW_TOKEN;

        // Null and empty byte arrays rejected pre-parse as INVALID_PAYLOAD (400).
        InternalSubmitAuthResult nullBody = authenticator.authenticateAndParse(validAuth, null, config, FIXED_NOW);
        assertEquals(Status.INVALID_PAYLOAD, nullBody.status());
        assertEquals(400, nullBody.httpStatus());
        assertEquals(InternalSubmitAuthenticator.REASON_EMPTY_BODY, nullBody.reasonCode());

        InternalSubmitAuthResult emptyBody = authenticator.authenticateAndParse(
                validAuth, new byte[0], config, FIXED_NOW);
        assertEquals(Status.INVALID_PAYLOAD, emptyBody.status());
        assertEquals(400, emptyBody.httpStatus());
        assertEquals(InternalSubmitAuthenticator.REASON_EMPTY_BODY, emptyBody.reasonCode());

        // Invalid UTF-8 bytes fail closed as INVALID_PAYLOAD (400).
        InternalSubmitAuthResult invalidUtf8 = authenticator.authenticateAndParse(
                validAuth, new byte[] { (byte) 0xFF, (byte) 0xFE, (byte) 0xFD }, config, FIXED_NOW);
        assertEquals(Status.INVALID_PAYLOAD, invalidUtf8.status());
        assertEquals(400, invalidUtf8.httpStatus());

        String[] invalidPayloads = {
                // Malformed JSON / whitespace-only / non-object roots
                "   ",
                "{",
                "null",
                "[]",
                "\"string\"",
                "123",
                // Nested object/array field values
                "{\"scope_id\":{},\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":[],\"event_id\":\"" + EVENT_UUID + "\"}",
                // Unknown property
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":\"" + EVENT_UUID + "\",\"extra\":\"x\"}",
                // Duplicate scope_id key
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":\"" + EVENT_UUID + "\"}",
                // Duplicate event_id key
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":\"" + EVENT_UUID + "\",\"event_id\":\"" + EVENT_UUID + "\"}",
                // Trailing JSON tokens
                VALID_JSON + " {}",
                VALID_JSON + " 123",
                VALID_JSON + " trailing",
                // Scalar coercion (integer / float / boolean)
                "{\"scope_id\":12345,\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":1.25,\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":true,\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":false}",
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":99}",
                // Missing or null required fields
                "{\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\"" + SCOPE_UUID + "\"}",
                "{\"scope_id\":null,\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":null}",
                "{}",
                // Non-canonical UUID shortcuts (Java UUID.fromString accepts "1-2-3-4-5", we must reject it)
                "{\"scope_id\":\"1-2-3-4-5\",\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":\"1-2-3-4-5\"}",
                // Uppercase UUID (non-canonical lowercase RFC 4122)
                "{\"scope_id\":\"550E8400-E29B-41D4-A716-446655440000\",\"event_id\":\"" + EVENT_UUID + "\"}",
                // Hyphenless 32-char hex / whitespace-padded / blank / invalid hex
                "{\"scope_id\":\"550e8400e29b41d4a716446655440000\",\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\" " + SCOPE_UUID + " \",\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\"\",\"event_id\":\"" + EVENT_UUID + "\"}",
                "{\"scope_id\":\"550e8400-e29b-41d4-a716-44665544000g\",\"event_id\":\"" + EVENT_UUID + "\"}"
        };

        for (int i = 0; i < invalidPayloads.length; i++) {
            InternalSubmitAuthResult rejected = authenticator.authenticateAndParse(
                    validAuth,
                    invalidPayloads[i].getBytes(StandardCharsets.UTF_8),
                    config,
                    FIXED_NOW);
            assertFalse(rejected.isAccepted(), "Payload case " + i + " must be rejected");
            assertEquals(Status.INVALID_PAYLOAD, rejected.status(), "Payload case " + i + " must be INVALID_PAYLOAD");
            assertEquals(400, rejected.httpStatus());
            assertNull(rejected.request());
            assertNull(rejected.matchedSlot());
        }
    }

    @Test
    void tokenConfigRejectsWeakBlankMalformedAndInconsistentInputsFailClosed() {
        Instant validUntil = FIXED_NOW.plusSeconds(60);

        // Weak / blank / whitespace raw tokens.
        assertThrows(IllegalArgumentException.class, () -> InternalSubmitTokenConfig.fromRawToken(null));
        assertThrows(IllegalArgumentException.class, () -> InternalSubmitTokenConfig.fromRawToken(""));
        assertThrows(IllegalArgumentException.class, () -> InternalSubmitTokenConfig.fromRawToken(" ".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> InternalSubmitTokenConfig.fromRawToken("a".repeat(31)));
        assertThrows(IllegalArgumentException.class, () -> InternalSubmitTokenConfig.fromRawToken("a".repeat(20) + " " + "b".repeat(20)));
        assertThrows(IllegalArgumentException.class, () -> InternalSubmitTokenConfig.fromRawToken("a".repeat(32) + "\n"));

        // Exact 32-char raw token succeeds.
        InternalSubmitTokenConfig minLengthConfig = InternalSubmitTokenConfig.fromRawToken("x".repeat(32));
        assertEquals(sha256Hex("x".repeat(32)), minLengthConfig.currentSha256Hex());

        // Direct SHA-256 hex constructor authenticates both current and next slots identically.
        InternalSubmitTokenConfig hexConfig = new InternalSubmitTokenConfig(
                CURRENT_SHA256_HEX, NEXT_SHA256_HEX, validUntil);
        InternalSubmitAuthenticator defaultAuth = new InternalSubmitAuthenticator();
        InternalSubmitAuthResult hexCurrent = defaultAuth.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, VALID_BODY, hexConfig, FIXED_NOW);
        assertTrue(hexCurrent.isAccepted());
        assertEquals(MatchedSlot.CURRENT, hexCurrent.matchedSlot());
        InternalSubmitAuthResult hexNext = defaultAuth.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, VALID_BODY, hexConfig, FIXED_NOW);
        assertTrue(hexNext.isAccepted());
        assertEquals(MatchedSlot.NEXT, hexNext.matchedSlot());

        // Exception messages on invalid raw tokens or malformed hex must never echo the secret input.
        String secretShortCanary = "short-secret-canary-9988";
        IllegalArgumentException weakEx = assertThrows(
                IllegalArgumentException.class,
                () -> InternalSubmitTokenConfig.fromRawToken(secretShortCanary));
        assertFalse(weakEx.getMessage().contains(secretShortCanary));

        String badHexCanary = CURRENT_SHA256_HEX.toUpperCase();
        IllegalArgumentException hexEx = assertThrows(
                IllegalArgumentException.class,
                () -> new InternalSubmitTokenConfig(badHexCanary));
        assertFalse(hexEx.getMessage().contains(badHexCanary));

        // Missing or inconsistent nextValidUntil.
        assertThrows(IllegalArgumentException.class,
                () -> InternalSubmitTokenConfig.fromRawTokens(CURRENT_RAW_TOKEN, NEXT_RAW_TOKEN, null));
        assertThrows(IllegalArgumentException.class,
                () -> InternalSubmitTokenConfig.fromRawTokens(CURRENT_RAW_TOKEN, null, validUntil));
        assertThrows(IllegalArgumentException.class,
                () -> InternalSubmitTokenConfig.fromRawTokens(CURRENT_RAW_TOKEN, "short-next", validUntil));

        // Malformed hex digests.
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig(null));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig(""));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig("a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig(CURRENT_SHA256_HEX.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig("g".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig(CURRENT_SHA256_HEX, NEXT_SHA256_HEX, null));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig(CURRENT_SHA256_HEX, null, validUntil));
        assertThrows(IllegalArgumentException.class, () -> new InternalSubmitTokenConfig(CURRENT_SHA256_HEX, "badhex", validUntil));
    }

    @Test
    void neverLeaksRawTokenDigestOrRawJsonBodyInConfigOrResultStrings() {
        Instant validUntil = FIXED_NOW.plusSeconds(600);
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.fromRawTokens(
                CURRENT_RAW_TOKEN, NEXT_RAW_TOKEN, validUntil);
        InternalSubmitAuthenticator authenticator = new InternalSubmitAuthenticator(FIXED_CLOCK);

        String canaryBody = "{\"scope_id\":\"" + SCOPE_UUID + "\",\"event_id\":\"" + EVENT_UUID
                + "\",\"canary\":\"RAW-BODY-SECRET-CANARY-998877\"}";
        byte[] canaryBytes = canaryBody.getBytes(StandardCharsets.UTF_8);

        // Verify config.toString() redacts all digests and never contains raw tokens.
        String configText = config.toString();
        assertNoSecretLeak(configText, canaryBody);

        // ACCEPTED (CURRENT)
        InternalSubmitAuthResult acceptedCurrent = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, VALID_BODY, config, FIXED_NOW);
        assertTrue(acceptedCurrent.isAccepted());
        assertNoSecretLeak(acceptedCurrent, VALID_JSON);

        // ACCEPTED (NEXT)
        InternalSubmitAuthResult acceptedNext = authenticator.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, VALID_BODY, config, FIXED_NOW);
        assertTrue(acceptedNext.isAccepted());
        assertNoSecretLeak(acceptedNext, VALID_JSON);

        // UNAUTHORIZED (wrong token)
        InternalSubmitAuthResult unauthorizedWrong = authenticator.authenticateAndParse(
                "Bearer " + WRONG_RAW_TOKEN, canaryBytes, config, FIXED_NOW);
        assertEquals(Status.UNAUTHORIZED, unauthorizedWrong.status());
        assertNoSecretLeak(unauthorizedWrong, canaryBody);

        // UNAUTHORIZED (expired NEXT)
        InternalSubmitAuthResult unauthorizedExpired = authenticator.authenticateAndParse(
                "Bearer " + NEXT_RAW_TOKEN, canaryBytes, config, validUntil.plusNanos(1));
        assertEquals(Status.UNAUTHORIZED, unauthorizedExpired.status());
        assertNoSecretLeak(unauthorizedExpired, canaryBody);

        // UNAUTHORIZED (malformed header containing secret canary)
        InternalSubmitAuthResult unauthorizedMalformed = authenticator.authenticateAndParse(
                "Basic " + CURRENT_RAW_TOKEN, canaryBytes, config, FIXED_NOW);
        assertEquals(Status.UNAUTHORIZED, unauthorizedMalformed.status());
        assertNoSecretLeak(unauthorizedMalformed, canaryBody);

        // INVALID_PAYLOAD (unknown property containing canary)
        InternalSubmitAuthResult invalidPayload = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, canaryBytes, config, FIXED_NOW);
        assertEquals(Status.INVALID_PAYLOAD, invalidPayload.status());
        assertNoSecretLeak(invalidPayload, canaryBody);

        // PAYLOAD_TOO_LARGE (oversize body containing canary)
        byte[] oversizeCanary = (canaryBody + " ".repeat(1025)).getBytes(StandardCharsets.UTF_8);
        InternalSubmitAuthResult oversize = authenticator.authenticateAndParse(
                "Bearer " + CURRENT_RAW_TOKEN, oversizeCanary, config, FIXED_NOW);
        assertEquals(Status.PAYLOAD_TOO_LARGE, oversize.status());
        assertNoSecretLeak(oversize, canaryBody);
    }

    private static void assertNoSecretLeak(InternalSubmitAuthResult result, String rawBody) {
        assertNoSecretLeak(result.toString() + " | " + result.reasonCode(), rawBody);
    }

    private static void assertNoSecretLeak(String text, String rawBody) {
        assertFalse(text.contains(CURRENT_RAW_TOKEN), "Current raw token leaked");
        assertFalse(text.contains(NEXT_RAW_TOKEN), "Next raw token leaked");
        assertFalse(text.contains(WRONG_RAW_TOKEN), "Wrong raw token leaked");
        assertFalse(text.contains(CURRENT_SHA256_HEX), "Current SHA-256 hex digest leaked");
        assertFalse(text.contains(NEXT_SHA256_HEX), "Next SHA-256 hex digest leaked");
        assertFalse(text.contains(sha256Hex(WRONG_RAW_TOKEN)), "Wrong SHA-256 hex digest leaked");
        assertFalse(text.contains(rawBody), "Raw JSON body leaked");
        assertFalse(text.contains("RAW-BODY-SECRET-CANARY-998877"), "Raw JSON body canary leaked");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
