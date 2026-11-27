package com.securityexpert.nexus.ui2.service.eventintake.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.eventintake.internal.InternalSubmitAuthResult.Status;

class InternalSubmitTokenVerifierTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T04:00:00Z");
    private static final Instant NEXT_VALID_UNTIL = Instant.parse("2026-11-27T05:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final String CURRENT_TOKEN = "test-current-internal-submit-token-do-not-leak-01";
    private static final String NEXT_TOKEN = "test-next-internal-submit-token-do-not-leak-02";
    private static final String WRONG_TOKEN = "test-wrong-internal-submit-token-do-not-leak-99";

    private static final String CURRENT_SHA256_HEX = sha256Hex(CURRENT_TOKEN);
    private static final String NEXT_SHA256_HEX = sha256Hex(NEXT_TOKEN);

    private static final String VALID_SCOPE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_EVENT_ID = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

    private static final String VALID_PAYLOAD_JSON = """
            {"scope_id":"550e8400-e29b-41d4-a716-446655440000","event_id":"6ba7b810-9dad-11d1-80b4-00c04fd430c8"}""";

    @Test
    void acceptsValidCurrentTokenAndValidUuidPayload() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(CURRENT_SHA256_HEX);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        InternalSubmitAuthResult result = verifier.verify(
                bearer(CURRENT_TOKEN),
                utf8(VALID_PAYLOAD_JSON));

        assertTrue(result.isAccepted());
        assertEquals(Status.ACCEPTED_CURRENT, result.status());
        assertEquals(200, result.httpStatus());
        assertTrue(result.request().isPresent());
        assertEquals(VALID_SCOPE_ID, result.request().get().scopeId());
        assertEquals(VALID_EVENT_ID, result.request().get().eventId());
        assertNoSecretLeak(result, CURRENT_TOKEN, CURRENT_SHA256_HEX);
    }

    @Test
    void acceptsValidNextTokenBeforeAndExactlyAtNextValidUntilAndRejectsOneNanosecondAfter() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(
                CURRENT_SHA256_HEX,
                NEXT_SHA256_HEX,
                NEXT_VALID_UNTIL);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        // Strictly before nextValidUntil
        InternalSubmitAuthResult before = verifier.verify(
                bearer(NEXT_TOKEN),
                utf8(VALID_PAYLOAD_JSON),
                NEXT_VALID_UNTIL.minusNanos(1));
        assertTrue(before.isAccepted());
        assertEquals(Status.ACCEPTED_NEXT, before.status());
        assertEquals(200, before.httpStatus());
        assertTrue(before.request().isPresent());
        assertEquals(VALID_SCOPE_ID, before.request().get().scopeId());
        assertEquals(VALID_EVENT_ID, before.request().get().eventId());
        assertNoSecretLeak(before, NEXT_TOKEN, NEXT_SHA256_HEX);

        // Exactly at nextValidUntil (inclusive)
        InternalSubmitAuthResult exact = verifier.verify(
                bearer(NEXT_TOKEN),
                utf8(VALID_PAYLOAD_JSON),
                NEXT_VALID_UNTIL);
        assertTrue(exact.isAccepted());
        assertEquals(Status.ACCEPTED_NEXT, exact.status());
        assertEquals(200, exact.httpStatus());
        assertTrue(exact.request().isPresent());
        assertEquals(VALID_SCOPE_ID, exact.request().get().scopeId());
        assertEquals(VALID_EVENT_ID, exact.request().get().eventId());

        // 1 nanosecond after nextValidUntil -> NEXT_TOKEN_EXPIRED (401)
        InternalSubmitAuthResult expired = verifier.verify(
                bearer(NEXT_TOKEN),
                utf8(VALID_PAYLOAD_JSON),
                NEXT_VALID_UNTIL.plusNanos(1));
        assertFalse(expired.isAccepted());
        assertEquals(Status.NEXT_TOKEN_EXPIRED, expired.status());
        assertEquals(401, expired.httpStatus());
        assertTrue(expired.request().isEmpty());
        assertNoSecretLeak(expired, NEXT_TOKEN, NEXT_SHA256_HEX);
    }

    @Test
    void failsClosedWhenNextTokenHasNoValidityBound() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(
                CURRENT_SHA256_HEX,
                NEXT_SHA256_HEX,
                null);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        InternalSubmitAuthResult result = verifier.verify(
                bearer(NEXT_TOKEN),
                utf8(VALID_PAYLOAD_JSON));

        assertFalse(result.isAccepted());
        assertEquals(Status.TOKEN_MISMATCH, result.status());
        assertEquals(401, result.httpStatus());
        assertTrue(result.request().isEmpty());
    }

    @Test
    void rejectsWrongAndTruncatedTokensWith401() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(
                CURRENT_SHA256_HEX,
                NEXT_SHA256_HEX,
                NEXT_VALID_UNTIL);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        // Wrong token (valid syntax)
        InternalSubmitAuthResult wrong = verifier.verify(
                bearer(WRONG_TOKEN),
                utf8(VALID_PAYLOAD_JSON));
        assertFalse(wrong.isAccepted());
        assertEquals(Status.TOKEN_MISMATCH, wrong.status());
        assertEquals(401, wrong.httpStatus());
        assertTrue(wrong.request().isEmpty());
        assertNoSecretLeak(wrong, WRONG_TOKEN, sha256Hex(WRONG_TOKEN));

        // Truncated token (>= 16 chars) -> digest mismatch (401)
        String truncatedLong = CURRENT_TOKEN.substring(0, CURRENT_TOKEN.length() - 1);
        InternalSubmitAuthResult truncatedLongResult = verifier.verify(
                bearer(truncatedLong),
                utf8(VALID_PAYLOAD_JSON));
        assertFalse(truncatedLongResult.isAccepted());
        assertEquals(Status.TOKEN_MISMATCH, truncatedLongResult.status());
        assertEquals(401, truncatedLongResult.httpStatus());
        assertTrue(truncatedLongResult.request().isEmpty());

        // Truncated token (< 16 chars) -> malformed header (401)
        String truncatedShort = CURRENT_TOKEN.substring(0, 15);
        InternalSubmitAuthResult truncatedShortResult = verifier.verify(
                bearer(truncatedShort),
                utf8(VALID_PAYLOAD_JSON));
        assertFalse(truncatedShortResult.isAccepted());
        assertEquals(Status.MISSING_OR_MALFORMED_HEADER, truncatedShortResult.status());
        assertEquals(401, truncatedShortResult.httpStatus());
        assertTrue(truncatedShortResult.request().isEmpty());
    }

    @Test
    void rejectsExtraWhitespaceMissingAndNonBearerAuthorizationHeadersWith401() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(CURRENT_SHA256_HEX);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        List<String> invalidHeaders = List.of(
                "",
                "   ",
                "Bearer",
                "Bearer ",
                "bearer " + CURRENT_TOKEN,
                "BEARER " + CURRENT_TOKEN,
                "Basic " + CURRENT_TOKEN,
                "Token " + CURRENT_TOKEN,
                " Bearer " + CURRENT_TOKEN,
                "Bearer  " + CURRENT_TOKEN,
                "Bearer " + CURRENT_TOKEN + " ",
                "Bearer " + CURRENT_TOKEN + "\t",
                "Bearer " + CURRENT_TOKEN + "\n",
                "Bearer " + CURRENT_TOKEN + "\r",
                "Bearer token-with space-1234567890",
                "Bearer token-with\u0000null-1234567890",
                "Bearer token-with\u007fdel-1234567890",
                "Bearer token-non-ascii-\u00e9-1234567890",
                "Bearer " + "a".repeat(15),
                "Bearer " + "a".repeat(513));

        // Null header
        InternalSubmitAuthResult nullHeaderResult = verifier.verify(null, utf8(VALID_PAYLOAD_JSON));
        assertFalse(nullHeaderResult.isAccepted());
        assertEquals(Status.MISSING_OR_MALFORMED_HEADER, nullHeaderResult.status());
        assertEquals(401, nullHeaderResult.httpStatus());
        assertTrue(nullHeaderResult.request().isEmpty());

        for (String header : invalidHeaders) {
            InternalSubmitAuthResult result = verifier.verify(header, utf8(VALID_PAYLOAD_JSON));
            assertFalse(result.isAccepted(), "Expected rejection for malformed header");
            assertEquals(Status.MISSING_OR_MALFORMED_HEADER, result.status());
            assertEquals(401, result.httpStatus());
            assertTrue(result.request().isEmpty());
            assertNoSecretLeak(result, CURRENT_TOKEN, CURRENT_SHA256_HEX);
        }
    }

    @Test
    void acceptsBoundaryLengthTokens16And512Chars() {
        String minToken = "a".repeat(16);
        String maxToken = "z".repeat(512);
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(
                sha256Hex(minToken),
                sha256Hex(maxToken),
                NEXT_VALID_UNTIL);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        InternalSubmitAuthResult minResult = verifier.verify(bearer(minToken), utf8(VALID_PAYLOAD_JSON));
        assertEquals(Status.ACCEPTED_CURRENT, minResult.status());
        assertEquals(200, minResult.httpStatus());

        InternalSubmitAuthResult maxResult = verifier.verify(bearer(maxToken), utf8(VALID_PAYLOAD_JSON));
        assertEquals(Status.ACCEPTED_NEXT, maxResult.status());
        assertEquals(200, maxResult.httpStatus());
    }

    @Test
    void nullConfigReturnsVerifierNotConfigured503BeforeBodyOrHeaderChecks() {
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(null, FIXED_CLOCK);

        byte[] oversizeMalformed = ("{" + "x".repeat(InternalSubmitTokenVerifier.MAX_BODY_BYTES + 100))
                .getBytes(StandardCharsets.UTF_8);

        InternalSubmitAuthResult result = verifier.verify(null, oversizeMalformed);
        assertFalse(result.isAccepted());
        assertEquals(Status.VERIFIER_NOT_CONFIGURED, result.status());
        assertEquals(503, result.httpStatus());
        assertTrue(result.request().isEmpty());

        // Also via 4-arg verify overload
        InternalSubmitAuthResult perCallNull = verifier.verify(null, bearer(CURRENT_TOKEN), null, FIXED_NOW);
        assertEquals(Status.VERIFIER_NOT_CONFIGURED, perCallNull.status());
        assertEquals(503, perCallNull.httpStatus());
        assertTrue(perCallNull.request().isEmpty());
    }

    @Test
    void rejectsInvalidHexDigestsInConfigWithoutEchoingInput() {
        List<String> invalidDigests = List.of(
                "",
                "   ",
                "a".repeat(63),
                "a".repeat(65),
                "A".repeat(64),
                "g" + "0".repeat(63),
                " " + "0".repeat(64),
                "0".repeat(64) + " ",
                "not-a-valid-sha256-digest-secret-sentinel-9876543210");

        // Null current digest
        IllegalArgumentException nullEx = assertThrows(
                IllegalArgumentException.class,
                () -> InternalSubmitTokenConfig.of(null));
        assertFalse(nullEx.getMessage().isBlank());

        for (String badDigest : invalidDigests) {
            IllegalArgumentException currentEx = assertThrows(
                    IllegalArgumentException.class,
                    () -> InternalSubmitTokenConfig.of(badDigest));
            if (!badDigest.isBlank()) {
                assertFalse(
                        currentEx.getMessage().contains(badDigest),
                        "Exception message must never echo rejected digest");
            }

            IllegalArgumentException nextEx = assertThrows(
                    IllegalArgumentException.class,
                    () -> InternalSubmitTokenConfig.of(CURRENT_SHA256_HEX, badDigest, NEXT_VALID_UNTIL));
            if (!badDigest.isBlank()) {
                assertFalse(
                        nextEx.getMessage().contains(badDigest),
                        "Exception message must never echo rejected next digest");
            }
        }

        // nextValidUntil without nextSha256Hex
        assertThrows(
                IllegalArgumentException.class,
                () -> InternalSubmitTokenConfig.of(CURRENT_SHA256_HEX, null, NEXT_VALID_UNTIL));
    }

    @Test
    void rejectsBodyOver1024BytesWith413EvenWithMalformedJsonAndMissingAuth() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(CURRENT_SHA256_HEX);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        byte[] oversizeMalformed = ("{not-json-" + "z".repeat(InternalSubmitTokenVerifier.MAX_BODY_BYTES))
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(oversizeMalformed.length > InternalSubmitTokenVerifier.MAX_BODY_BYTES);

        InternalSubmitAuthResult result = verifier.verify(null, oversizeMalformed);
        assertFalse(result.isAccepted());
        assertEquals(Status.OVERSIZE, result.status());
        assertEquals(413, result.httpStatus());
        assertTrue(result.request().isEmpty());

        // Exactly 1024 bytes is permitted
        int padding = InternalSubmitTokenVerifier.MAX_BODY_BYTES - utf8(VALID_PAYLOAD_JSON).length;
        byte[] exactCapBody = utf8(VALID_PAYLOAD_JSON + " ".repeat(padding));
        assertEquals(InternalSubmitTokenVerifier.MAX_BODY_BYTES, exactCapBody.length);

        InternalSubmitAuthResult exactCapResult = verifier.verify(bearer(CURRENT_TOKEN), exactCapBody);
        assertTrue(exactCapResult.isAccepted());
        assertEquals(Status.ACCEPTED_CURRENT, exactCapResult.status());
        assertEquals(200, exactCapResult.httpStatus());

        // Null body -> INVALID_PAYLOAD (400)
        InternalSubmitAuthResult nullBodyResult = verifier.verify(bearer(CURRENT_TOKEN), null);
        assertFalse(nullBodyResult.isAccepted());
        assertEquals(Status.INVALID_PAYLOAD, nullBodyResult.status());
        assertEquals(400, nullBodyResult.httpStatus());
        assertTrue(nullBodyResult.request().isEmpty());
    }

    @Test
    void rejectsStrictJsonSchemaAndUuidViolationsWith400() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(CURRENT_SHA256_HEX);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        List<String> invalidPayloads = List.of(
                // Unknown JSON property
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":\"" + VALID_EVENT_ID + "\",\"extra\":\"nope\"}",
                // Duplicate JSON key
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"scope_id\":\"" + VALID_SCOPE_ID
                        + "\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":\"" + VALID_EVENT_ID
                        + "\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                // Trailing JSON tokens
                VALID_PAYLOAD_JSON + " {}",
                VALID_PAYLOAD_JSON + " 123",
                // Non-string scalar coercion
                "{\"scope_id\":12345,\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":12.34,\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":true,\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":98765}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":false}",
                // Uppercase UUID (no case-folding)
                "{\"scope_id\":\"" + VALID_SCOPE_ID.toUpperCase() + "\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":\"" + VALID_EVENT_ID.toUpperCase() + "\"}",
                // Whitespace-padded UUID (no trimming)
                "{\"scope_id\":\" " + VALID_SCOPE_ID + "\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + " \",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":\" " + VALID_EVENT_ID + " \"}",
                // Malformed UUIDs
                "{\"scope_id\":\"550e8400e29b41d4a716446655440000\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"550e8400-e29b-41d4-a716-44665544000g\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"550e8400-e29b-41d4-a716-44665544000\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\\n\",\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                // Missing / null / empty / non-object JSON
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\"}",
                "{\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":null,\"event_id\":\"" + VALID_EVENT_ID + "\"}",
                "{\"scope_id\":\"" + VALID_SCOPE_ID + "\",\"event_id\":null}",
                "{}",
                "null",
                "[]",
                "",
                "{malformed");

        for (String json : invalidPayloads) {
            InternalSubmitAuthResult result = verifier.verify(bearer(CURRENT_TOKEN), utf8(json));
            assertFalse(result.isAccepted(), "Expected INVALID_PAYLOAD for: " + json);
            assertEquals(Status.INVALID_PAYLOAD, result.status(), "Unexpected status for: " + json);
            assertEquals(400, result.httpStatus());
            assertTrue(result.request().isEmpty());
            assertNoSecretLeak(result, CURRENT_TOKEN, CURRENT_SHA256_HEX);
        }

        // Auth failure must still precede payload/JSON validation (including null body -> 401)
        InternalSubmitAuthResult badAuthBadJson = verifier.verify(bearer(WRONG_TOKEN), utf8("{broken"));
        assertEquals(Status.TOKEN_MISMATCH, badAuthBadJson.status());
        assertEquals(401, badAuthBadJson.httpStatus());

        InternalSubmitAuthResult missingAuthNullBody = verifier.verify(null, (byte[]) null);
        assertFalse(missingAuthNullBody.isAccepted());
        assertEquals(Status.MISSING_OR_MALFORMED_HEADER, missingAuthNullBody.status());
        assertEquals(401, missingAuthNullBody.httpStatus());
        assertTrue(missingAuthNullBody.request().isEmpty());

        InternalSubmitAuthResult badTokenNullBody = verifier.verify(bearer(WRONG_TOKEN), null);
        assertFalse(badTokenNullBody.isAccepted());
        assertEquals(Status.TOKEN_MISMATCH, badTokenNullBody.status());
        assertEquals(401, badTokenNullBody.httpStatus());
        assertTrue(badTokenNullBody.request().isEmpty());

        InternalSubmitTokenConfig rotationConfig = InternalSubmitTokenConfig.of(
                CURRENT_SHA256_HEX,
                NEXT_SHA256_HEX,
                NEXT_VALID_UNTIL);
        InternalSubmitAuthResult expiredNextNullBody = verifier.verify(
                rotationConfig,
                bearer(NEXT_TOKEN),
                null,
                NEXT_VALID_UNTIL.plusNanos(1));
        assertFalse(expiredNextNullBody.isAccepted());
        assertEquals(Status.NEXT_TOKEN_EXPIRED, expiredNextNullBody.status());
        assertEquals(401, expiredNextNullBody.httpStatus());
        assertTrue(expiredNextNullBody.request().isEmpty());
    }

    @Test
    void neverLeaksTokensOrSha256DigestsInReasonsToStringsOrExceptions() {
        InternalSubmitTokenConfig config = InternalSubmitTokenConfig.of(
                CURRENT_SHA256_HEX,
                NEXT_SHA256_HEX,
                NEXT_VALID_UNTIL);
        InternalSubmitTokenVerifier verifier = new InternalSubmitTokenVerifier(config, FIXED_CLOCK);

        String configString = config.toString();
        assertFalse(configString.contains(CURRENT_SHA256_HEX));
        assertFalse(configString.contains(NEXT_SHA256_HEX));
        assertFalse(configString.contains(CURRENT_TOKEN));
        assertFalse(configString.contains(NEXT_TOKEN));

        List<InternalSubmitAuthResult> results = List.of(
                verifier.verify(bearer(CURRENT_TOKEN), utf8(VALID_PAYLOAD_JSON), FIXED_NOW),
                verifier.verify(bearer(NEXT_TOKEN), utf8(VALID_PAYLOAD_JSON), FIXED_NOW),
                verifier.verify(null, bearer(CURRENT_TOKEN), utf8(VALID_PAYLOAD_JSON), FIXED_NOW),
                verifier.verify(bearer(CURRENT_TOKEN), utf8("x".repeat(1025)), FIXED_NOW),
                verifier.verify("Basic " + CURRENT_TOKEN, utf8(VALID_PAYLOAD_JSON), FIXED_NOW),
                verifier.verify(bearer(WRONG_TOKEN), utf8(VALID_PAYLOAD_JSON), FIXED_NOW),
                verifier.verify(bearer(NEXT_TOKEN), utf8(VALID_PAYLOAD_JSON), NEXT_VALID_UNTIL.plusSeconds(1)),
                verifier.verify(bearer(CURRENT_TOKEN), utf8("{\"scope_id\":\"" + CURRENT_TOKEN + "\"}"), FIXED_NOW));

        for (InternalSubmitAuthResult result : results) {
            assertNoSecretLeak(result, CURRENT_TOKEN, CURRENT_SHA256_HEX);
            assertNoSecretLeak(result, NEXT_TOKEN, NEXT_SHA256_HEX);
            assertNoSecretLeak(result, WRONG_TOKEN, sha256Hex(WRONG_TOKEN));
        }

        // Direct InternalSubmitRequest constructor also never echoes secret-like invalid values
        IllegalArgumentException scopeEx = assertThrows(
                IllegalArgumentException.class,
                () -> new InternalSubmitRequest(CURRENT_TOKEN, VALID_EVENT_ID));
        assertFalse(scopeEx.getMessage().contains(CURRENT_TOKEN));

        IllegalArgumentException eventEx = assertThrows(
                IllegalArgumentException.class,
                () -> new InternalSubmitRequest(VALID_SCOPE_ID, CURRENT_SHA256_HEX));
        assertFalse(eventEx.getMessage().contains(CURRENT_SHA256_HEX));
    }

    private static void assertNoSecretLeak(
            InternalSubmitAuthResult result,
            String rawToken,
            String sha256Hex) {
        assertFalse(result.reason().isBlank());
        assertFalse(result.reason().contains(rawToken));
        assertFalse(result.reason().contains(sha256Hex));
        String rendered = result.toString();
        assertFalse(rendered.contains(rawToken));
        assertFalse(rendered.contains(sha256Hex));
    }

    private static String bearer(String token) {
        return InternalSubmitTokenVerifier.BEARER_PREFIX + token;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
